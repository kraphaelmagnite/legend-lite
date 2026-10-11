"""DataCube's test files in Node and in QuickJS, with the same stand-ins: bundle each, run it in both, compare.

    python -I suite.py <esbuild> <node> <planner_dir> <venv python> [test name ...]
"""
import concurrent.futures
import json
import subprocess
import sys
import time
from pathlib import Path

ESBUILD, NODE, PLANNER, VPY, ENGINE_NM = sys.argv[1:6]
ONLY = set(sys.argv[6:])
HERE = Path(__file__).resolve().parent
TREE = HERE / 'tree'
OUT = HERE / 'suite'
OUT.mkdir(exist_ok=True)
SHIM = HERE / 'shim'
NODE_ONLY = ['node:fs', 'node:fs/promises', 'node:zlib', 'node:url', 'node:os', 'node:http', 'node:crypto',
             'node:child_process', 'node:worker_threads', 'node:process', 'node:events', 'node:stream', 'node:util',
             'node:buffer', 'node:net', 'node:readline', 'node:timers/promises', 'jsdom', 'playwright']


def bundle(test):
    out = OUT / f'{test}.mjs'
    args = [ESBUILD, str(TREE / 'datacube' / 'test' / f'{test}.test.ts'), '--bundle', '--format=esm', '--platform=neutral',
            '--main-fields=module,main', '--target=es2022', '--log-level=error', f'--outfile={out}',
            '--footer:js=globalThis.__loaded = true;',
            f'--alias:node:test={SHIM / "node-test.js"}', f'--alias:node:assert/strict={SHIM / "node-assert.js"}',
            f'--alias:node:assert={SHIM / "node-assert.js"}', f'--alias:node:path={SHIM / "node-path.js"}', f'--alias:node:module={SHIM / "node-module.js"}']
    args += [f'--alias:{m}={SHIM / "unavailable.cjs"}' for m in NODE_ONLY]
    r = subprocess.run(args, capture_output=True, text=True, cwd=TREE)
    return out if r.returncode == 0 else ' '.join(r.stderr.split())[:300]


def run(cmd):
    t = time.monotonic()
    # each run in a scratch directory of its own: a database writing a relative file writes it there
    import tempfile
    with tempfile.TemporaryDirectory(prefix='suite-run-') as cwd:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=600, cwd=cwd)
    took = time.monotonic() - t
    try:
        return json.loads(r.stdout), took
    except json.JSONDecodeError:
        why = (r.stderr.strip().splitlines() or [f'exit {r.returncode}'])[-1][:300]
        return [{'name': '<crash>', 'status': 'fail', 'error': f'exit {r.returncode}: {why}'}], took


def one(test):
    b = bundle(test)
    if not isinstance(b, Path):
        return test, {'bundle': b}
    node, tn = run([NODE, '--experimental-wasm-exnref', '--disable-warning=ExperimentalWarning', str(HERE / 'run-node.mjs'), PLANNER, str(b), ENGINE_NM])
    qjs, tq = run([VPY, '-I', str(HERE / 'run_qjs.py'), str(b)])
    v8, tv = run([VPY, '-I', str(HERE / 'run_v8.py'), str(b)])
    return test, {'node': node, 'qjs': qjs, 'v8': v8, 'node_s': round(tn, 1), 'qjs_s': round(tq, 1), 'v8_s': round(tv, 1)}


tests = sorted(p.name[:-len('.test.ts')] for p in (TREE / 'datacube' / 'test').glob('*.test.ts'))
if ONLY:
    tests = [t for t in tests if t in ONLY]
results = {}
with concurrent.futures.ThreadPoolExecutor(3) as pool:
    for test, r in pool.map(one, tests):
        results[test] = r
        if 'bundle' in r:
            print(f'{test:28} BUNDLE FAILED: {r["bundle"][:150]}', flush=True)
            continue
        count = lambda rs, s: sum(1 for x in rs if x['status'] == s)
        n, q, v = r['node'], r['qjs'], r['v8']
        st = lambda rs: {x['name']: x['status'] for x in rs}
        print(f'{test:24} node {count(n, "pass"):4} ({r["node_s"]:5}s) | quickjs {count(q, "pass"):4} ({r["qjs_s"]:5}s) '
              f'{"=" if st(q) == st(n) else "!"} | v8 {count(v, "pass"):4} ({r["v8_s"]:5}s) {"=" if st(v) == st(n) else "!"}', flush=True)
(HERE / ('suite-results-3.json' if not ONLY else 'suite-results.json')).write_text(json.dumps(results, indent=1))
