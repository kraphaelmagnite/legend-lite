"""THE COVERING SUBSET (README.md section 3): corpus tests that, together, carry every primitive the corpus carries to
success -- a greedy set cover over a primitives=per-test run's '[primitives-test] <test>\t<primitive>' lines
(-Dlegend.diagnostics=primitives=per-test). Ties go to the test with fewer primitives (a smaller test), then its name.

usage: primitive_cover.py <corpus per-test log> [<k>: cover each primitive k times, default 1]"""
import collections
import re
import sys


def fold(key):
    return re.sub(r'\((\d+)\)$', lambda a: a.group(0) if int(a.group(1)) <= 3 else '(>3)', key)


def main():
    tests = collections.defaultdict(set)
    for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
        m = re.match(r'\[primitives-test\] (\S+)\t(\S+)', line)
        if m:
            tests[m.group(1)].add(fold(m.group(2)))
    k = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    need = collections.Counter()
    for ps in tests.values():
        for p in ps:
            need[p] = min(k, need[p] + 1)
    chosen = []
    while +need:
        best = max(tests, key=lambda t: (sum(1 for p in tests[t] if need[p] > 0), -len(tests[t]), t))
        gain = [p for p in tests[best] if need[p] > 0]
        if not gain:
            break
        chosen.append((best, len(gain)))
        for p in gain:
            need[p] -= 1
        del tests[best]
    print(f'# {len(chosen)} tests carry every primitive {k}x')
    for t, g in chosen:
        print(f'{t}\t+{g}')


if __name__ == '__main__':
    main()
