"""Runs DataCube's builder bundle in each embedded engine from Python and compares each output with Node's.

    python -I run_engines.py <bundle.js> <reference.json>
"""
import json
import sys
import time
import traceback

bundle = open(sys.argv[1], encoding='utf-8').read()
reference = open(sys.argv[2], encoding='utf-8').read()


def quickjs_ng():
    import quickjs
    ctx = quickjs.Context()
    ctx.set_memory_limit(512 * 1024 * 1024)
    t0 = time.perf_counter()
    ctx.eval(bundle)
    t1 = time.perf_counter()
    out = ctx.eval('buildAll()')
    return out, t1 - t0, time.perf_counter() - t1, lambda: ctx.eval('buildAll()')


def mini_racer():
    from py_mini_racer import MiniRacer
    ctx = MiniRacer()
    t0 = time.perf_counter()
    ctx.eval(bundle)
    t1 = time.perf_counter()
    out = ctx.eval('buildAll()')
    return out, t1 - t0, time.perf_counter() - t1, lambda: ctx.eval('buildAll()')


def dukpy():
    import dukpy as dk
    interp = dk.JSInterpreter()
    t0 = time.perf_counter()
    interp.evaljs(bundle)
    t1 = time.perf_counter()
    out = interp.evaljs('buildAll()')
    return out, t1 - t0, time.perf_counter() - t1, lambda: interp.evaljs('buildAll()')


for name, run in (('quickjs-ng', quickjs_ng), ('mini-racer (V8)', mini_racer), ('dukpy (Duktape)', dukpy)):
    try:
        out, load, first, again = run()
        repeats = []
        for _ in range(5):
            t = time.perf_counter()
            again()
            repeats.append(time.perf_counter() - t)
        same = out == reference
        n = len(json.loads(out))
        detail = 'byte-identical to Node' if same else 'DIFFERS from Node'
        if not same:
            ref = json.loads(reference)
            got = json.loads(out)
            diffs = [r['name'] for r, g in zip(ref, got) if r != g]
            detail += f' ({len(diffs)} of {len(ref)} queries differ, e.g. {diffs[:3]})'
        print(f'{name:16} OK   load {load * 1000:6.1f} ms  first {first * 1000:6.1f} ms  '
              f'then {min(repeats) * 1000:6.1f} ms (49 queries)  {n} queries, {detail}')
    except Exception as e:  # an engine that cannot run the bundle: what it said
        print(f'{name:16} FAIL {type(e).__name__}: {str(e).splitlines()[0][:160]}')
        if '-v' in sys.argv:
            traceback.print_exc()
