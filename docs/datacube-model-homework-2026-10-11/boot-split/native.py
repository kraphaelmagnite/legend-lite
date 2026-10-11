"""A MEASUREMENT (scratch): the same model texts held by legend-lite's native library (GraalVM, the same Java as the
tab's WASM module: lite_hold_model is Boundary.holdModel's twin), timed as boot-split.ts times the module."""
import statistics, sys, time
from pathlib import Path
from legend_lite._library import library

lib = library()
here = Path(sys.argv[1])
def hold(text):
    t = time.perf_counter(); a = lib.call('lite_hold_model', text); ms = (time.perf_counter() - t) * 1000
    assert a.startswith('OK\n'), a[:300]
    lib.call('lite_release_model', a.split('\n')[1])
    return ms
first = hold(''); print({'what': 'first call: boot + empty model held', 'ms': round(first)})
print({'what': 'empty held again', 'ms': [round(hold(f'// {i}\n')) for i in range(5)]})
hold((here / 'classModel-1.pure').read_text() + '\n// warm\n')   # a first class model's code paths, outside the clock
for n in (1, 10, 50, 200):
    text = (here / f'classModel-{n}.pure').read_text()
    ms = [hold(f'{text}\n// compile {i}\n') for i in range(5)]
    print({'what': f'classModel({n}) held', 'ms': [round(x, 1) for x in ms], 'median': round(statistics.median(ms), 1)})
