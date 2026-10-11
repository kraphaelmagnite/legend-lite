"""One test bundle in V8 (mini-racer), DataCube's code as Python would host it there: every call into Python
asynchronous (mini-racer's only kind) -- the planner through WasmPlanner's worker transport (the browser's production
path), its messages answered by the native library; the test helper's parses by the same library; DuckDB Python's own.

    python -I run_v8.py <bundle.mjs>      (prints the results as JSON)
"""
import asyncio
import base64
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path

import duckdb
import pyarrow as pa
from py_mini_racer import MiniRacer
from legend_lite._library import library

HERE = Path(__file__).resolve().parent / 'host'

PRELUDE = r"""
(() => {
  // base64 (V8 alone has no atob/btoa)
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const R = new Map([...A].map((c, i) => [c, i]));
  globalThis.__b64 = (bytes) => {
    const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes.buffer ?? bytes);
    let out = '';
    for (let i = 0; i < u.length; i += 3) {
      const n = (u[i] << 16) | ((u[i + 1] ?? 0) << 8) | (u[i + 2] ?? 0);
      out += A[(n >> 18) & 63] + A[(n >> 12) & 63] + (i + 1 < u.length ? A[(n >> 6) & 63] : '=') + (i + 2 < u.length ? A[n & 63] : '=');
    }
    return out;
  };
  globalThis.__unb64 = (s) => {
    const clean = s.replace(/[^A-Za-z0-9+/]/g, '');
    const out = new Uint8Array(Math.floor(clean.length * 3 / 4));
    let o = 0;
    for (let i = 0; i < clean.length; i += 4) {
      const n = (R.get(clean[i]) << 18) | (R.get(clean[i + 1]) << 12) | ((R.get(clean[i + 2]) ?? 0) << 6) | (R.get(clean[i + 3]) ?? 0);
      out[o++] = (n >> 16) & 255;
      if (i + 2 < clean.length) out[o++] = (n >> 8) & 255;
      if (i + 3 < clean.length) out[o++] = n & 255;
    }
    return out.subarray(0, o);
  };
  globalThis.atob ??= (s) => String.fromCharCode(...__unb64(s));
  globalThis.btoa ??= (s) => __b64(Uint8Array.from(s, (c) => c.charCodeAt(0)));
  globalThis.queueMicrotask ??= (f) => { Promise.resolve().then(f); };
  globalThis.performance ??= { now: () => Date.now() };
  const say = () => {};
  globalThis.console = { log: say, error: say, warn: say, info: say, debug: say };
  // timers on a virtual clock the host advances when nothing else can run (as in the QuickJS run)
  let now = 0, id = 1, queue = [];
  globalThis.setTimeout = (fn, ms, ...args) => { const t = { id: id++, at: now + Math.max(0, Number(ms) || 0), fn, args }; queue.push(t); return t.id; };
  globalThis.setInterval = (fn, ms, ...args) => { const e = Math.max(1, Number(ms) || 1); const t = { id: id++, at: now + e, every: e, fn, args }; queue.push(t); return t.id; };
  globalThis.clearTimeout = (x) => { queue = queue.filter((t) => t.id !== x); };
  globalThis.clearInterval = globalThis.clearTimeout;
  globalThis.__advance = () => {
    if (queue.length === 0) return false;
    queue.sort((a, b) => a.at - b.at || a.id - b.id);
    const t = queue[0];
    now = Math.max(now, t.at);
    if (t.every) t.at += t.every; else queue.shift();
    t.fn(...t.args);
    return true;
  };
  // calls into Python in flight: the driver knows JS is idle only when none is
  globalThis.__pending = 0;
  globalThis.__hostRunfiles = true;
  globalThis.__workerUrl = 'host:planner-worker';
})();
"""

AFTER_HOST = r"""
(() => {
  const counted = (f) => async (...a) => { __pending++; try { return await f(...a); } finally { __pending--; } };
  for (const n of ['__pyPlanner', '__pyPureV1', '__pyQuery', '__pyRegister', '__pySha']) globalThis[n] = counted(globalThis[n]);
  globalThis.__pureV1 = (p, q, b) => __pyPureV1(p, q, b);
  // the planner worker, as the browser runs it: each message answered off this thread (here: by Python), asynchronously
  globalThis.Worker = class {
    constructor(url) { this.url = url; this.onmessage = null; this.onerror = null; }
    postMessage(msg) {
      __pyPlanner(JSON.stringify(msg)).then(
        (answer) => this.onmessage?.({ data: JSON.parse(answer) }),
        (e) => this.onerror?.({ message: String(e) }));
    }
    terminate() {}
  };
  globalThis.crypto ??= {};
  globalThis.crypto.subtle ??= { digest: async (algo, data) => __unb64(await __pySha(typeof algo === 'string' ? algo : algo.name,
    __b64(data instanceof ArrayBuffer ? new Uint8Array(data) : data))).buffer };
  globalThis.crypto.getRandomValues ??= (a) => { for (let i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); return a; };
  // TEST-SIDE ONLY: the Buffer some tests use to read back what an export wrote (latin1, base64, utf8)
  class Buffer extends Uint8Array {
    static from(x, enc) {
      if (typeof x === 'string') {
        if (enc === 'base64') return new Buffer(__unb64(x));
        if (enc === 'latin1' || enc === 'binary') return new Buffer(Uint8Array.from(x, (c) => c.charCodeAt(0) & 255));
        return new Buffer(new TextEncoder().encode(x));
      }
      return new Buffer(x instanceof ArrayBuffer ? new Uint8Array(x) : x);
    }
    toString(enc = 'utf8') {
      if (enc === 'base64') return __b64(this);
      if (enc === 'latin1' || enc === 'binary') { let s = ''; for (const b of this) s += String.fromCharCode(b); return s; }
      return new TextDecoder().decode(this);
    }
  }
  globalThis.Buffer ??= Buffer;
  // Python's DuckDB behind a duckdb-wasm-shaped module: each query's answer Arrow IPC, read by apache-arrow
  globalThis.__duckdbDist = '/host/duckdb';
  globalThis.__duckdbModule = {
    VoidLogger: class {}, ConsoleLogger: class {}, NODE_RUNTIME: {},
    createDuckDB: async () => ({
      instantiate: async () => {},
      connect: () => ({ query: async (sql) => __arrow.tableFromIPC(__unb64(await __pyQuery(sql))), close: () => {} }),
      terminate: async () => {},
      registerFileBuffer: async (name, bytes) => { await __pyRegister(name, __b64(bytes)); },
      registerFileText: async (name, text) => { await __pyRegister(name, __b64(new TextEncoder().encode(text))); },
      dropFile: async () => {},
      copyFileToBuffer: () => { throw new Error('unavailable here: a synchronous read of DuckDB\'s files (mini-racer calls Python asynchronously)'); },
    }),
  };
})();
"""


async def main():
    code = open(sys.argv[1], encoding='utf-8').read()
    files = tempfile.mkdtemp(prefix='v8-files-')
    os.chdir(files)  # a database's relative file lands here, never in the checkout
    con = duckdb.connect()
    con.execute(f"SET file_search_path = '{files}'")
    models = {}

    def native(name, *args):
        return library().call(name, *[str(a) for a in args])

    async def planner(msg_json):
        # planner-worker.ts's answerOf, each kind its export -- here the native library's function of that export
        m = json.loads(msg_json)
        k = m['kind']
        try:
            if k == 'hold':
                a = native('lite_hold_model', m['model'])
                if a.startswith('OK\n'):
                    models[int(a[3:])] = m['model']
            elif k == 'release':
                native('lite_release_model', m['handle'])
                a = 'OK\n'
            elif k == 'plan':
                a = native('lite_plan_text', models.get(int(m['handle']), ''), m['query'], m['runtime'])
            elif k == 'planJson':
                a = native('lite_plan_json_held', m['handle'], m['lambda'], m['runtime'])
            elif k == 'relationType':
                a = native('lite_relation_type_held', m['handle'], m['query'])
            elif k == 'tableModel':
                a = native('lite_table_model', m['table'])
            elif k == 'catalogColumnsSql':
                a = native('lite_catalog_columns_sql', m['schema'], m['table'])
            elif k == 'pureV1':
                a = native('lite_pure_v1', m['path'], m['query'], m['body'])
            elif k == 'testData':
                a = 'ERR\nunavailable\nthe native library has no testDataSql'
            else:
                raise ValueError(f'unknown request {k}')
            return json.dumps({'id': m['id'], 'ok': True, 'answer': a})
        except Exception as e:  # what the worker answers when its module fails
            return json.dumps({'id': m['id'], 'ok': False, 'error': f'{type(e).__name__}: {e}'})

    async def pure_v1(p, q, b):
        return native('lite_pure_v1', p, q, b)

    async def query(sql):
        r = con.execute(sql)
        t = r.to_arrow_table() if r.description is not None else pa.table({})
        sink = io.BytesIO()
        with pa.ipc.new_stream(sink, t.schema) as w:
            w.write_table(t)
        return base64.b64encode(sink.getvalue()).decode('ascii')

    async def register(name, b64):
        target = os.path.join(files, name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        open(target, 'wb').write(base64.b64decode(b64))
        return ''

    async def sha(algo, b64):
        return base64.b64encode(hashlib.new(algo.replace('-', '').lower(), base64.b64decode(b64)).digest()).decode()

    def fail(where, e):
        print(json.dumps([{'name': where, 'status': 'fail', 'error': str(e).splitlines()[0][:300] if str(e) else repr(e)}]))

    mr = MiniRacer()
    mr.eval(PRELUDE)
    for f in ('web.js', 'url.js', 'arrow.js'):
        mr.eval((HERE / f).read_text(encoding='utf-8'))
    setter = mr.eval('(name, f) => { globalThis[name] = f; }')
    async with mr.wrap_py_function(planner) as j_planner, mr.wrap_py_function(pure_v1) as j_pure, \
            mr.wrap_py_function(query) as j_query, mr.wrap_py_function(register) as j_register, \
            mr.wrap_py_function(sha) as j_sha:
        for name, fn in (('__pyPlanner', j_planner), ('__pyPureV1', j_pure), ('__pyQuery', j_query),
                         ('__pyRegister', j_register), ('__pySha', j_sha)):
            setter(name, fn)
        mr.eval(AFTER_HOST)

        async def drive(done_expr, deadline):
            idle = 0
            while True:
                await asyncio.sleep(0)
                if mr.eval(done_expr):
                    return 'done'
                if time.monotonic() > deadline:
                    return 'timeout'
                if mr.eval('__pending') > 0:
                    idle = 0
                    await asyncio.sleep(0.0005)
                    continue
                # nothing in Python's hands: let JS settle a moment, then the next timer, else it is stuck
                await asyncio.sleep(0.0005)
                if mr.eval(done_expr) or mr.eval('__pending') > 0:
                    continue
                if not mr.eval('__advance()'):
                    idle += 1
                    if idle > 200:
                        return 'hung'

        body = re.sub(r'^export \{[^}]*\};?\s*$', '', code.replace('globalThis.__loaded = true;', ''), flags=re.M)
        body = body.replace('import.meta.url', '"file:///host/bundle.mjs"').replace('import.meta', '({ url: "file:///host/bundle.mjs" })')
        wrapped = ('globalThis.__loadError = undefined; (async () => {\n' + body + '\n})().then(() => { globalThis.__loaded = true; },'
                   ' (e) => { globalThis.__loadError = (e && e.stack) ? String(e) + " @ " + String(e.stack).split("\\n").slice(0, 3).join(" | ") : String(e); });')
        deadline = time.monotonic() + 240
        try:
            mr.eval(wrapped)
            state = await drive('globalThis.__loaded === true || globalThis.__loadError !== undefined', deadline)
            if state != 'done':
                return fail('<load>', f'the module did not finish loading: {state}')
            if mr.eval('globalThis.__loadError !== undefined'):
                return fail('<load>', mr.eval('globalThis.__loadError'))
            mr.eval("globalThis.__done = undefined; __runTests().then((r) => { globalThis.__done = r; }, "
                    "(e) => { globalThis.__done = JSON.stringify([{ name: '<run>', status: 'fail', error: String(e) }]); });")
            state = await drive('globalThis.__done !== undefined', deadline)
            if state != 'done':
                return fail('<run>', f'the tests did not finish: {state}')
            print(mr.eval('globalThis.__done'))
        except Exception as e:  # the engine refusing the code: what it said
            fail('<load>', e)


asyncio.run(main())
