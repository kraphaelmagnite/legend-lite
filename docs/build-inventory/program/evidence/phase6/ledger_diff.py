"""Per-assert diff of two judge ledgers (test, index, assert function, verdict): rows only in A, only in B, verdict changed.
python3 -I ledger_diff.py <base ledger> <experiment ledger>"""
import collections, sys
def load(p):
    d = {}
    for line in open(p, encoding='utf-8', errors='replace'):
        if not line.strip() or line.startswith('#'):
            continue
        f = line.rstrip('\n').split('\t')
        d[(f[0], f[1])] = (f[2] if len(f) > 2 else '', f[3] if len(f) > 3 else '')
    return d
a, b = load(sys.argv[1]), load(sys.argv[2])
only_a = sorted(k for k in a if k not in b); only_b = sorted(k for k in b if k not in a)
changed = sorted(k for k in a if k in b and a[k][1] != b[k][1])
va, vb = collections.Counter(v[1] for v in a.values()), collections.Counter(v[1] for v in b.values())
print('rows base=%d exp=%d | verdicts base=%s exp=%s' % (len(a), len(b), dict(va), dict(vb)))
print('only in base: %d, only in experiment: %d, verdict changed: %d' % (len(only_a), len(only_b), len(changed)))
for k in only_a[:15]: print('  -', k[0], k[1], a[k])
for k in only_b[:15]: print('  +', k[0], k[1], b[k])
for k in changed[:40]: print('  ~', k[0], k[1], a[k][1], '->', b[k][1])
tests_changed = collections.Counter(k[0] for k in changed)
if tests_changed: print('tests with changed verdicts:', len(tests_changed))
