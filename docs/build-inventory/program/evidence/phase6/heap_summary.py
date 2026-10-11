"""Peak heap before collection and the heap's committed size from -Xlog:gc* logs: python3 heap_summary.py <dir with <pass>/gc.log>"""
import re, sys, os
root = sys.argv[1]
for pas in sorted(os.listdir(root)):
    g = os.path.join(root, pas, 'gc.log')
    if not os.path.isfile(g): continue
    peak = committed = 0; pauses = 0
    for line in open(g, errors='replace'):
        m = re.search(r'(\d+)M->(\d+)M\((\d+)M\)', line)
        if m:
            peak = max(peak, int(m.group(1))); committed = max(committed, int(m.group(3))); pauses += 1
    print('%-26s peak before a collection %5d MB, heap committed up to %5d MB, %d pauses' % (pas, peak, committed, pauses))
