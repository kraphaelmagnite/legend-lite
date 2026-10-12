"""Self time and inclusive time per function of a V8 .cpuprofile (WASM frames by their name section's names)."""
import json, sys, collections
p = json.load(open(sys.argv[1]))
nodes = {n['id']: n for n in p['nodes']}
parent = {}
for n in p['nodes']:
    for c in n.get('children', []):
        parent[c] = n['id']
dt = collections.Counter()
for sid, d in zip(p['samples'], p['timeDeltas']):
    dt[sid] += d
total = sum(dt.values())
selft = collections.Counter(); incl = collections.Counter()
for sid, d in dt.items():
    name = lambda i: nodes[i]['callFrame']['functionName'] or '(anon ' + nodes[i]['callFrame']['url'][-30:] + ')'
    selft[name(sid)] += d
    seen = set(); i = sid
    while i is not None:
        nm = name(i)
        if nm not in seen:
            incl[nm] += d; seen.add(nm)
        i = parent.get(i)
print(f'total sampled {total/1000:.0f} ms')
print('--- self time')
for nm, d in selft.most_common(int(sys.argv[2]) if len(sys.argv) > 2 else 30):
    print(f'{d/total*100:5.1f}%  {d/1000:7.0f} ms  {nm[:140]}')
print('--- inclusive (top)')
for nm, d in incl.most_common(int(sys.argv[3]) if len(sys.argv) > 3 else 45):
    print(f'{d/total*100:5.1f}%  {d/1000:7.0f} ms  {nm[:140]}')
