"""Which host facilities each engine has: the things DataCube's non-screen code might use beyond plain JavaScript."""
names = ['Intl', 'TextEncoder', 'TextDecoder', 'setTimeout', 'clearTimeout', 'queueMicrotask', 'structuredClone',
         'crypto', 'WebAssembly', 'URL', 'URLSearchParams', 'AbortController', 'performance', 'console', 'atob', 'btoa',
         'Blob', 'fetch', 'WeakRef', 'FinalizationRegistry', 'SharedArrayBuffer', 'Atomics']
probe = '(' + repr(names) + ').map(n => n + "=" + typeof globalThis[n]).join(" ")'
extra = ("[ (function(){ try { return 'toLocaleString:' + (1234.5).toLocaleString('en-US'); } catch(e) { return 'toLocaleString:ERR'; } })(),"
         " (function(){ try { return 'Date.toISOString:' + new Date(0).toISOString(); } catch(e) { return 'ERR'; } })(),"
         " (function(){ try { JSON.parse('1', (k, v, c) => c && c.source); return 'JSON.parse source:' + (JSON.parse('12.30', (k, v, c) => c ? c.source : 'none')); } catch(e) { return 'JSON.parse source: ERR ' + e; } })() ].join(' ')")
import quickjs
q = quickjs.Context()
print('quickjs-ng:', q.eval(probe)); print('   ', q.eval(extra))
print('    Context methods:', [m for m in dir(q) if not m.startswith('_')])
from py_mini_racer import MiniRacer
m = MiniRacer()
print('V8        :', m.eval(probe)); print('   ', m.eval(extra))
