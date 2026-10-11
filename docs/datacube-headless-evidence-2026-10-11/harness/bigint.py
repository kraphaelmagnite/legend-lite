"""Does each engine have BigInt (the builder's exact integers past 2^53)?"""
probe = "typeof BigInt === 'function' ? String(BigInt('9007199254740993') + 1n) : 'no BigInt'"
def run(name, ev):
    try:
        print(f'{name:16} {ev(probe)}')
    except Exception as e:
        print(f'{name:16} FAIL {type(e).__name__}: {str(e).splitlines()[0][:120]}')
import quickjs; c = quickjs.Context(); run('quickjs-ng', c.eval)
from py_mini_racer import MiniRacer; m = MiniRacer(); run('mini-racer (V8)', m.eval)
import dukpy; d = dukpy.JSInterpreter(); run('dukpy (Duktape)', d.evaljs)
