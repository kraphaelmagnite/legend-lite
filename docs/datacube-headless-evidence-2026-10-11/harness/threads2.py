"""Thread use, one pattern per run: python -I threads2.py <bundle> <reference> <engine> <shared|locked|per-thread>"""
import sys, threading
bundle, reference = (open(p, encoding='utf-8').read() for p in sys.argv[1:3])
engine, pattern = sys.argv[3], sys.argv[4]
def context():
    if engine == 'quickjs':
        import quickjs; c = quickjs.Context(); c.eval(bundle); return lambda: c.eval('buildAll()')
    from py_mini_racer import MiniRacer; m = MiniRacer(); m.eval(bundle); return lambda: m.eval('buildAll()')
shared, lock, local = context() if pattern != 'per-thread' else None, threading.Lock(), threading.local()
wrong = []
def work():
    for _ in range(10):
        if pattern == 'shared':
            out = shared()
        elif pattern == 'locked':
            with lock:
                out = shared()
        else:
            if not hasattr(local, 'call'):
                local.call = context()
            out = local.call()
        if out != reference:
            wrong.append(1)
ts = [threading.Thread(target=work) for _ in range(4)]
[t.start() for t in ts]; [t.join() for t in ts]
print(f'{engine:8} {pattern:10} 4 threads x 10 calls: {len(wrong)} wrong')
