"""THE COVERAGE MAP (README.md section 3): the primitives the relational corpus carries to success, against each
database's PCT run. Both sides are the primitive census's tallies (-Dlegend.diagnostics=primitives, printed at the
JVM's exit as '[primitives] ok=N fail=M <primitive>'). On a database, a corpus primitive is 'ok' when a statement
carrying it succeeded there, 'FAIL' when it appeared only in failing statements, '--' when no statement carrying it
reached the database. A variadic call's arity past 3 is one primitive.

usage: primitive_map.py <corpus log> <db>=<log>[,<log>...] ...   (prints markdown)"""
import collections
import re
import sys


def fold(key):
    return re.sub(r'\((\d+)\)$', lambda a: a.group(0) if int(a.group(1)) <= 3 else '(>3)', key)


def tally(paths):
    t = collections.defaultdict(lambda: [0, 0])
    for p in paths:
        for line in open(p, encoding='utf-8', errors='replace'):
            m = re.search(r'\[primitives\] ok=(\d+) fail=(\d+) (\S+)', line)
            if m:
                k = fold(m.group(3))
                t[k][0] += int(m.group(1))
                t[k][1] += int(m.group(2))
    return t


def main():
    corpus = tally([sys.argv[1]])
    dbs = []
    for arg in sys.argv[2:]:
        name, paths = arg.split('=', 1)
        dbs.append((name, tally(paths.split(','))))
    used = sorted(k for k, (ok, _) in corpus.items() if ok > 0)
    print(f'corpus primitives (in a statement that succeeded on its lane): {len(used)}\n')
    print('| primitive | corpus ok | ' + ' | '.join(n for n, _ in dbs) + ' |')
    print('|---|---|' + '---|' * len(dbs))
    gaps = collections.defaultdict(list)
    for k in used:
        cells = []
        for n, t in dbs:
            ok, fail = t.get(k, (0, 0))
            cell = 'ok' if ok > 0 else ('FAIL' if fail > 0 else '--')
            if ok == 0:
                gaps[n].append((k, cell))
            cells.append(cell)
        print(f'| `{k}` | {corpus[k][0]} | ' + ' | '.join(cells) + ' |')
    print()
    for n, _ in dbs:
        print(f'**{n}**: {len(gaps[n])} gaps -- '
              + ', '.join(f'`{k}`' + (' (fails)' if c == 'FAIL' else '') for k, c in gaps[n]) + '\n')


if __name__ == '__main__':
    main()
