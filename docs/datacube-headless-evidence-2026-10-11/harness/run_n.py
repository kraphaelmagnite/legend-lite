"""python -I run_n.py <bundle> <reference> <n>: the bundle's buildAll(n) in quickjs-ng and V8, compared with Node's."""
import json, sys, time
bundle, reference = (open(p, encoding='utf-8').read() for p in sys.argv[1:3])
n = int(sys.argv[3])
def compare(name, ev):
    ev(bundle)
    t = time.perf_counter()
    out = ev(f'buildAll({n})')
    ms = (time.perf_counter() - t) * 1000
    if out == reference:
        print(f'{name:16} {ms:7.0f} ms  byte-identical to Node ({len(json.loads(out))} builds)')
        return
    ref, got = json.loads(reference), json.loads(out)
    diffs = [i for i, (a, b) in enumerate(zip(ref, got)) if a != b]
    print(f'{name:16} {ms:7.0f} ms  DIFFERS: {len(diffs)} of {len(ref)} builds; first at {diffs[:3]}')
    if diffs:
        i = diffs[0]
        print('   node:', ref[i][:200]); print('   this:', got[i][:200])
import quickjs
c = quickjs.Context(); c.set_memory_limit(1024 * 1024 * 1024); c.set_max_stack_size(8 * 1024 * 1024)
compare('quickjs-ng', c.eval)
from py_mini_racer import MiniRacer
m = MiniRacer(); compare('mini-racer (V8)', m.eval)
