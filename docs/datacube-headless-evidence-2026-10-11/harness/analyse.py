"""What the suite run says: per test file, which tests pass in Node and in QuickJS, and why QuickJS differs.

    python -I analyse.py <suite-results.json> <tree/datacube/test>
"""
import collections
import json
import re
import sys
from pathlib import Path

results = json.loads(Path(sys.argv[1]).read_text())
tests_dir = Path(sys.argv[2])


def needs_of(test):
    """What a test file imports that is not plain JavaScript: the screen (jsdom), Node, DuckDB-WASM, the WASM planner."""
    text = (tests_dir / f'{test}.test.ts').read_text()
    needs = set()
    if "from 'jsdom'" in text:
        needs.add('screen (jsdom)')
    if re.search(r"from 'node:(fs|path|url|os|http|zlib|crypto)", text):
        needs.add('Node module')
    if re.search(r"duckdb\.ts|duckdb-wasm", text):
        needs.add('DuckDB-WASM')
    if re.search(r"wasm-planner\.ts", text):
        needs.add('WASM planner')
    return needs


def reason(error):
    e = error or ''
    for pattern, label in [
        (r'unavailable here: (<node-only module>)?', 'a Node-only module (fs, path, ...)'),
        (r'(JSDOM|document|window|HTMLElement|Element|Event|navigator) is not defined|unavailable here: new <node-only', 'the screen (no DOM)'),
        (r'Intl', 'Intl (number/date formatting)'),
        (r'TextEncoder|TextDecoder', 'TextEncoder/TextDecoder'),
        (r'\bURL\b', 'URL'),
        (r'WebAssembly', 'WebAssembly'),
        (r'AbortController|AbortSignal|DOMException', 'AbortController / DOMException'),
        (r'structuredClone', 'structuredClone'),
        (r'did not finish|hung|timeout', 'hung or timed out'),
    ]:
        if re.search(pattern, e):
            return label
    return 'other: ' + e[:140]


files = collections.Counter()
totals = collections.Counter()
differ_reasons = collections.Counter()
examples = {}
rows = []
for test, r in sorted(results.items()):
    needs = needs_of(test)
    if 'bundle' in r:
        files['bundle failed'] += 1
        rows.append((test, needs, 'bundle failed', r['bundle'][:120]))
        continue
    node = {x['name']: x for x in r['node']}
    qjs = {x['name']: x for x in r['qjs']}
    np = sum(1 for x in node.values() if x['status'] == 'pass')
    qp = sum(1 for x in qjs.values() if x['status'] == 'pass')
    totals['node pass'] += np
    totals['quickjs pass'] += qp
    lost = [n for n, x in node.items() if x['status'] == 'pass' and qjs.get(n, {}).get('status') != 'pass']
    gained = [n for n, x in qjs.items() if x['status'] == 'pass' and node.get(n, {}).get('status') != 'pass']
    totals['passes in Node, not QuickJS'] += len(lost)
    totals['passes in QuickJS, not Node'] += len(gained)
    for n in lost:
        q = qjs.get(n) or next(iter(qjs.values()), {})
        why = reason(q.get('error'))
        differ_reasons[why] += 1
        examples.setdefault(why, f'{test}: {n[:80]} -- {q.get("error", "")[:160]}')
    if np == 0 and qp == 0:
        kind = 'neither runs here'
    elif not lost:
        kind = 'QuickJS = Node' if not gained else 'QuickJS >= Node'
    else:
        kind = 'QuickJS loses some'
    files[kind] += 1
    rows.append((test, needs, kind, f'node {np} / quickjs {qp}' + (f', {len(lost)} lost' if lost else '')
                 + (f', {len(gained)} gained' if gained else '')))

print('FILES:', dict(files))
print('TESTS:', dict(totals))
print('\nWHY a test passing in Node does not in QuickJS:')
for why, n in differ_reasons.most_common():
    print(f'  {n:4}  {why}\n        e.g. {examples[why]}')
print('\nPER FILE:')
for test, needs, kind, detail in rows:
    print(f'  {test:28} {kind:20} {detail:40} {", ".join(sorted(needs))}')
