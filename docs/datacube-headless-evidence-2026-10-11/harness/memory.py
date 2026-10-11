"""Resident memory the engine and the loaded bundle add to a Python process (maxrss, before and after)."""
import resource, sys
bundle = open(sys.argv[1], encoding='utf-8').read()
def rss():
    r = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return r / 1e6 if sys.platform == 'darwin' else r / 1e3   # MB (bytes on macOS, KB on Linux)
before = rss()
if sys.argv[2] == 'quickjs':
    import quickjs; c = quickjs.Context(); c.eval(bundle); c.eval('buildAll()')
else:
    from py_mini_racer import MiniRacer; m = MiniRacer(); m.eval(bundle); m.eval('buildAll()')
print(f'{sys.argv[2]:8} +{rss() - before:.1f} MB resident (engine, bundle, one build of 49 grids)')
