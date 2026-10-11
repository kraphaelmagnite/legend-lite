"""Samples per compile phase from a JFR execution-sample JSON (jfr print --json --events jdk.ExecutionSample)."""
import json, sys, collections
PHASES = [('parse', 'com.legend.parser.ElementParser.parse'), ('resolve', 'com.legend.compiler.NameResolver.resolveAlongside'),
          ('adopt', 'com.legend.compiler.KnowledgeLayer.adoptAssociationQualifiedProperties'), ('index', 'com.legend.compiler.ModelBuilder.from'),
          ('normalize', 'com.legend.normalizer.ModelNormalizer.normalize'), ('element compile', 'com.legend.compiler.element.PureModelContext.from')]
d = json.load(open(sys.argv[1])); events = d['recording']['events']
counts = collections.Counter(); total = 0; warm = 0
for e in events:
    st = e['values'].get('stackTrace')
    if not st or not st.get('frames'): continue
    names = [f['method']['type']['name'].replace('/', '.') + '.' + f['method']['name'] for f in st['frames']]   # jfr prints the class with slashes
    if any(n == 'com.legend.Compiler.compileModel' for n in names): warm += 1; continue
    total += 1
    phase = 'other'
    for label, marker in PHASES:
        if marker in names: phase = label; break
    counts[phase] += 1
print('samples in the timed loop: %d (warm-up compile excluded: %d)' % (total, warm))
for label, _ in PHASES + [('other', None)]:
    print('  %-16s %6d  %5.1f%%' % (label, counts[label], 100.0 * counts[label] / total if total else 0))
