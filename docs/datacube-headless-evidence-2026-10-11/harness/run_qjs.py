"""One test bundle in QuickJS (quickjs-ng), DataCube's code as Python would host it: legend-lite's pure/v1 from the
native library, a console, timers on a virtual clock the host advances when nothing else can run.

    python -I run_qjs.py <bundle.mjs>      (prints the results as JSON)
"""
import json
import sys
import time
from pathlib import Path

import quickjs
from legend_lite._library import library

PRELUDE = r"""
(() => {
  const say = (...a) => __log(a.map((x) => typeof x === 'string' ? x : String(x)).join(' '));
  globalThis.console = { log: say, error: say, warn: say, info: say, debug: say };
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
  // the planner's module, as Python's native library serves it: the same Java (Boundary), each answer OK/ERR text
  const models = new Map();
  const exports = {
    pureV1OrError: (p, q, b) => __native('lite_pure_v1', p, q, b),
    holdModelOrError: (model) => {
      const a = __native('lite_hold_model', model);
      if (a.startsWith('OK\n')) models.set(Number(a.slice(3)), model);
      return a;
    },
    releaseModel: (h) => { __native('lite_release_model', String(h)); },
    planJsonHeldOrError: (h, json, runtime) => __native('lite_plan_json_held', String(h), json, runtime),
    planHeldOrError: (h, text, runtime) => __native('lite_plan_text', models.get(h) ?? '', text, runtime),
    relationTypeHeldOrError: (h, json) => __native('lite_relation_type_held', String(h), json),
    tableModelOrError: (json) => __native('lite_table_model', json),
    catalogColumnsSqlOrError: (schema, table) => __native('lite_catalog_columns_sql', schema, table),
    testDataSqlOrError: () => 'ERR\nunavailable\nthe native library has no testDataSql',
  };
  globalThis.__loadRuntime = async () => ({ load: async () => ({ exports }) });
  globalThis.__hostRunfiles = true;
})();
"""


def drive(ctx, done_expr, deadline):
    """Runs pending jobs, then the next timer, until `done_expr` holds, nothing can move (a hang), or time is up."""
    while True:
        while ctx.execute_pending_job():
            pass
        if ctx.eval(done_expr):
            return 'done'
        if time.monotonic() > deadline:
            return 'timeout'
        if not ctx.eval('__advance()'):
            return 'hung'


def main():
    code = open(sys.argv[1], encoding='utf-8').read()
    ctx = quickjs.Context()
    ctx.set_memory_limit(2 * 1024 * 1024 * 1024)
    ctx.set_max_stack_size(64 * 1024 * 1024)
    recorded = {}

    def helper_pure_v1(p, q, b):
        answer = library().call('lite_pure_v1', p, q, b)
        recorded[json.dumps([p, q, b])] = answer
        return answer
    ctx.add_callable('__pureV1', helper_pure_v1)
    ctx.add_callable('__native', lambda name, *args: library().call(name, *[str(a) for a in args]))
    ctx.add_callable('__log', lambda s: None)
    ctx.eval(PRELUDE)
    # the host's web layer: what an embedding host supplies (host/web.js, then whatwg-url's URL)
    here = __import__('pathlib').Path(__file__).resolve().parent / 'host'
    ctx.eval((here / 'web.js').read_text(encoding='utf-8'))
    ctx.eval((here / 'url.js').read_text(encoding='utf-8'))
    ctx.eval((here / 'arrow.js').read_text(encoding='utf-8'))
    ctx.eval(r"""
      globalThis.__b64 = (bytes) => { const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes.buffer ?? bytes);
        let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode(...u.subarray(i, i + 0x8000)); return btoa(s); };
      globalThis.__unb64 = (b64) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      globalThis.crypto ??= {};
      globalThis.crypto.subtle ??= { digest: async (algo, data) => __unb64(__sha(typeof algo === 'string' ? algo : algo.name,
        __b64(data instanceof ArrayBuffer ? new Uint8Array(data) : data))).buffer };
      globalThis.crypto.getRandomValues ??= (a) => { for (let i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); return a; };
      // TEST-SIDE ONLY: the Buffer some tests use to read back what an export wrote (latin1, base64, utf8)
      class Buffer extends Uint8Array {
        static from(x, enc) {
          if (typeof x === 'string') {
            if (enc === 'base64') return new Buffer(__unb64(x.replace(/[^A-Za-z0-9+/=]/g, '')));
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
    """)
    import base64, io, duckdb, pyarrow as pa
    con = duckdb.connect()

    def query(sql):
        r = con.execute(sql)
        t = r.to_arrow_table() if r.description is not None else pa.table({})
        sink = io.BytesIO()
        with pa.ipc.new_stream(sink, t.schema) as w:
            w.write_table(t)
        return base64.b64encode(sink.getvalue()).decode('ascii')
    ctx.add_callable('__duckdbQuery', query)
    import hashlib, tempfile, os
    files = tempfile.mkdtemp(prefix='qjs-files-')
    os.chdir(files)  # DuckDB writes a relative file (COPY ... TO 'x.parquet') here, never into the checkout
    con.execute(f"SET file_search_path = '{files}'")

    def register(name, b64):
        target = os.path.join(files, name)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        open(target, 'wb').write(base64.b64decode(b64))
        return ''
    ctx.add_callable('__registerFile', register)
    ctx.add_callable('__readFile', lambda name: base64.b64encode(open(os.path.join(files, name), 'rb').read()).decode())
    ctx.add_callable('__sha', lambda algo, b64: base64.b64encode(hashlib.new(algo.replace('-', '').lower(), base64.b64decode(b64)).digest()).decode())
    ctx.eval("""globalThis.__duckdbDist = '/host/duckdb';
      globalThis.__duckdbModule = {
        VoidLogger: class {}, ConsoleLogger: class {}, NODE_RUNTIME: {},
        createDuckDB: async () => ({
          instantiate: async () => {},
          connect: () => ({
            query: (sql) => __arrow.tableFromIPC(Uint8Array.from(atob(__duckdbQuery(sql)), (c) => c.charCodeAt(0))),
            close: () => {},
          }),
          terminate: async () => {},
          registerFileBuffer: async (name, bytes) => { __registerFile(name, __b64(bytes)); },
          registerFileText: async (name, text) => { __registerFile(name, __b64(new TextEncoder().encode(text))); },
          dropFile: async () => {},
          copyFileToBuffer: (name) => __unb64(__readFile(name)),
        }),
      };""")
    deadline = time.monotonic() + 240
    fail = lambda where, e: print(json.dumps([{'name': where, 'status': 'fail', 'error': str(e).splitlines()[0][:300]}]))
    # the module's body run as an async function, so an error after a top-level await is caught and said (a module's
    # own evaluation would swallow it); a test bundle has no imports left, and exports nothing a test needs
    import re
    body = re.sub(r'^export \{[^}]*\};?\s*$', '', code.replace('globalThis.__loaded = true;', ''), flags=re.M)
    body = body.replace('import.meta.url', '"file:///host/bundle.mjs"').replace('import.meta', '({ url: "file:///host/bundle.mjs" })')
    wrapped = ('globalThis.__loadError = undefined; (async () => {\n' + body + '\n})().then(() => { globalThis.__loaded = true; },'
               ' (e) => { globalThis.__loadError = (e && e.stack) ? String(e) + " @ " + String(e.stack).split("\\n").slice(0, 3).join(" | ") : String(e); });')
    try:
        ctx.eval(wrapped)
        state = drive(ctx, 'globalThis.__loaded === true || globalThis.__loadError !== undefined', deadline)
        if state != 'done':
            return fail('<load>', f'the module did not finish loading: {state}')
        if ctx.eval('globalThis.__loadError !== undefined'):
            return fail('<load>', ctx.eval('globalThis.__loadError'))
        ctx.eval("globalThis.__done = undefined; __runTests().then((r) => { globalThis.__done = r; }, "
                 "(e) => { globalThis.__done = JSON.stringify([{ name: '<run>', status: 'fail', error: String(e) }]); });")
        state = drive(ctx, 'globalThis.__done !== undefined', deadline)
        if state != 'done':
            return fail('<run>', f'the tests did not finish: {state}')
        print(ctx.eval('globalThis.__done'))
        Path(sys.argv[1] + '.fixtures.json').write_text(json.dumps(recorded))
    except quickjs.JSException as e:
        fail('<load>', e)


main()
