"""Who can reach the failing bodies (the census README's first table), as RULES over failures.tsv's FAILED rows.

    python3 -I classify.py [failures.tsv]      (default: the file beside this script)

Each FAILED row's subject is a function's signature id; one body per row; "distinct" counts names (the id without its
signature tail). The classes, decided in this order (the first rule that matches wins):
  TEST       upstream's own test functions: a package segment `tests` or `test`, or a last segment starting with
             `test` followed by an upper-case letter or a digit
  MACHINERY  engine machinery the platform never runs: protocol translators (`meta::protocols::`), the router, plan
             generation and binding, the engine's SQL generator, JSON, graph-fetch internals, model-to-model
             internals, the engine's runtime, metadata and other servers (the PACKAGES list below)
  LIBRARY    functions user code can call, in the platform's library packages (the LIBRARY list below): legacy TDS
             functions, collection and string functions, reflection
  OTHER      the rest
Written 2026-10-11 (Phase 6 homework 6-H9): the README's split (931 / 460 / 52 / 26, hand-made on 2026-10-07) had no
committed script; this one is the rule the next census re-measures with, and its counts are printed beside the
README's so the difference is visible, not hidden.
"""
import collections, csv, os, re, sys

PACKAGES = (
    'meta::protocols::', 'meta::pure::router::', 'meta::pure::executionPlan::', 'meta::relational::functions::',
    'meta::relational::executionPlan::', 'meta::relational::runtime::', 'meta::relational::mapping::',
    'meta::relational::metamodel::execute', 'meta::relational::transform::', 'meta::relational::validation::',
    'meta::relational::milestoning::', 'meta::relational::contract::', 'meta::relational::extension::',
    'meta::relational::graphFetch::', 'meta::relational::relation::', 'meta::relational::postProcessor::',
    'meta::relational::dbTestRunner::', 'meta::relational::lineage::', 'meta::relational::translation::',
    'meta::json::', 'meta::external::format::json::', 'meta::external::format::', 'meta::external::store::',
    'meta::external::query::', 'meta::external::language::', 'meta::external::function::',
    'meta::pure::graphFetch::execution', 'meta::pure::graphFetch::executionPlan', 'meta::pure::graphFetch::routing',
    'meta::pure::graphFetch::serialization', 'meta::pure::mapping::modelToModel::', 'meta::pure::mapping::aggregationAware::',
    'meta::pure::mapping::xStore::', 'meta::pure::mapping::relation::', 'meta::pure::mapping::', 'meta::pure::milestoning::',
    'meta::pure::runtime::', 'meta::core::runtime::', 'meta::alloy::', 'meta::legend::', 'meta::pure::extension::',
    'meta::pure::lineage::', 'meta::pure::dataQuality::', 'meta::pure::test::', 'meta::pure::ide::', 'meta::pure::generation::',
    'meta::analytics::', 'meta::pure::data::', 'meta::pure::store::', 'meta::pure::model::', 'meta::pure::changetoken::',
    'meta::pure::persistence::', 'meta::pure::mft::', 'meta::pure::profiles::', 'meta::pure::tds::schema::',
    'meta::pure::functions::tests::', 'meta::pure::executionPlan', 'meta::pure::mapping', 'meta::pure::routing::',
    'meta::pure::router', 'meta::pure::metamodel::serialization::', 'meta::pure::metamodel::relation::',
    'meta::pure::metamodel::dataSpace::', 'meta::pure::metamodel::serialization', 'meta::pure::metamodel::path::',
)
LIBRARY = (
    'meta::pure::tds::', 'meta::pure::functions::', 'meta::pure::metamodel::', 'meta::core::', 'meta::pure::',
)
TEST_NAME = re.compile(r'^test(?:[A-Z0-9_]|$)')


def classify(name):
    parts = name.split('::')
    pkg = '::'.join(parts[:-1]) + '::'
    last = parts[-1]
    if 'tests' in parts[:-1] or 'test' in parts[:-1] or TEST_NAME.match(last):
        return 'TEST'
    if any(pkg.startswith(p) for p in PACKAGES):
        return 'MACHINERY'
    if any(pkg.startswith(p) for p in LIBRARY):
        return 'LIBRARY'
    return 'OTHER'


def base_name(subject):
    # the id's signature tail begins at the first '_' after the last '::' segment's name
    last = subject.rfind('::')
    cut = subject.find('_', last + 2)
    return subject if cut < 0 else subject[:cut]


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), 'failures.tsv')
    with open(path, encoding='utf-8') as f:
        rows = [r for r in csv.reader(f, delimiter='\t') if r and r[0] == 'FAILED']
    bodies = collections.Counter()
    names = collections.defaultdict(set)
    members = collections.defaultdict(list)
    for r in rows:
        name = base_name(r[1])
        c = classify(name)
        bodies[c] += 1
        names[c].add(name)
        members[c].append(name)
    readme = {'MACHINERY': (931, 364), 'TEST': (460, 460), 'LIBRARY': (52, 52), 'OTHER': (26, 26)}
    # the README's "distinct functions" for the machinery (364 for 931 bodies) counts SIMPLE names: the 16 protocol
    # versions declare the same ~40 translators under different packages; both counts are printed
    print('class      bodies  names  simple   README(bodies distinct)')
    for c in ('MACHINERY', 'TEST', 'LIBRARY', 'OTHER'):
        simple = len({n.split('::')[-1] for n in names[c]})
        print('%-10s %6d %6d %7d   %6d %8d' % (c, bodies[c], len(names[c]), simple, *readme[c]))
    print('total      %6d %6d' % (sum(bodies.values()), sum(len(v) for v in names.values())))
    for c in ('LIBRARY', 'OTHER'):
        print('\n%s (%d names):' % (c, len(names[c])))
        for n in sorted(names[c]):
            print('  ' + n)


if __name__ == '__main__':
    main()
