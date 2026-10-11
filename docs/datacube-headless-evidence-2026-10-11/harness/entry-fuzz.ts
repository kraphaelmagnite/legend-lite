// Random grids from DataCube's own fuzz generator (copied verbatim from test/fuzz.test.ts, lines 53-157, by sed),
// each made into every kind of query DataCube builds -- run in each engine, compared with Node.
import { classLambda, countLambda, levelLambda, pivotValuesLambda, type PivotFacts } from '../../datacube/src/query.ts';
import type { AggregateFn, CubeSnapshot, FilterNode, SortSpec } from '../../datacube/src/snapshot.ts';
import { element, lambda, lit, fn, toJson, type Lambda } from '../../pure-protocol/src/index.ts';

// test/lite-compiler.ts's row() parses Pure text with the compiler; here the same kind of lambda, built directly
const row = (_text: string): Lambda =>
  lambda(['x'], fn('plus', { _type: 'property', property: 'a', parameters: [{ _type: 'var', name: 'x' }] } as never, lit.integer(1)));
function rng(seed: number): () => number {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    s >>>= 0;
    return s / 0x100000000;
  };
}

const NAMES = [
  'region',
  'desk',
  'book',
  'year',
  'qtr',
  'notional',
  'pnl',
  'qty',
  // Names chosen to break naive string handling.
  'a b',
  'Ünïcødé',
  "quote'name",
  'very_long_column_name_that_goes_on_and_on_and_on',
  'select',
  '1',
];
const TYPES = ['String', 'Integer', 'Float', 'Date', 'Boolean'];
const FNS: AggregateFn[] = ['sum', 'count', 'average', 'min', 'max', 'wavg'];

function makeSnapshot(r: () => number): CubeSnapshot {
  const pick = <T>(xs: readonly T[]): T =>
    xs[Math.floor(r() * xs.length)] as T;
  const upto = (n: number): number => Math.floor(r() * (n + 1));

  const columns = Array.from({ length: upto(6) }, () => ({
    name: pick(NAMES),
    type: pick(TYPES),
    ...(r() < 0.2 ? { kind: r() < 0.5 ? ('dimension' as const) : ('measure' as const) } : {}),
    ...(r() < 0.15 ? { excludedFromPivot: true } : {}),
  }));
  const names = columns.map((c) => c.name);
  const some = (n: number): string[] =>
    Array.from({ length: upto(n) }, () => pick(names.length ? names : NAMES));

  const measures = Array.from({ length: upto(3) }, (_, i) => {
    const fn = pick(FNS);
    return {
      name: `m${i}`,
      column: pick(names.length ? names : NAMES),
      fn,
      ...(fn === 'wavg' && r() < 0.7
        ? { weight: pick(names.length ? names : NAMES) }
        : {}),
    };
  });

  const sorts: SortSpec[] = Array.from({ length: upto(2) }, () => ({
    column: pick([...names, 'm0', 'nonexistent']),
    direction: r() < 0.5 ? ('asc' as const) : ('desc' as const),
  }));

  const filter: FilterNode | undefined =
    r() < 0.5
      ? undefined
      : {
          kind: 'and',
          children: [
            {
              kind: 'condition',
              column: pick(names.length ? names : NAMES),
              operator: 'equal',
              value: pick(['EMEA', '', "it's", 42, 0, true]) as never,
            },
            ...(r() < 0.5
              ? [
                  {
                    kind: 'not' as const,
                    child: {
                      kind: 'condition' as const,
                      column: pick(names.length ? names : NAMES),
                      operator: 'isEmpty' as const,
                    },
                  },
                ]
              : []),
          ],
        };

  return {
    source: { query: element('t') },
    columns,
    derived: r() < 0.3 ? [{ name: 'd0', lambda: row('$x.a + 1') }] : [],
    rows: some(3),
    pivotOn: some(2),
    measures,
    sorts,
    ...(filter ? { filter } : {}),
    ...(r() < 0.3 ? { maxRows: upto(1000) } : {}),
    epoch: 1,
  };
}

/** A result table shaped like something an engine might return. */

const factsFor = (s: CubeSnapshot): PivotFacts | undefined =>
  s.pivotOn.length === 0 ? undefined : { tuples: [s.pivotOn.map(() => 'v'), s.pivotOn.map(() => null)] };
const attempt = (build: () => Lambda | null): string => {
  try {
    const q = build();
    return q === null ? 'none' : toJson(q);
  } catch (e) {
    return `refused: ${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}`;
  }
};

(globalThis as { buildAll?: (n: number) => string }).buildAll = (n: number): string => {
  const out: string[] = [];
  for (let seed = 1; seed <= n; seed++) {
    const s = makeSnapshot(rng(seed));
    const facts = factsFor(s);
    out.push(
      attempt(() => levelLambda(s, undefined, facts)),
      attempt(() => levelLambda(s, { level: 1, parent: ['x'], limit: 10 }, facts)),
      attempt(() => classLambda(s)),
      attempt(() => countLambda(s)),
      attempt(() => pivotValuesLambda(s)),
    );
  }
  return JSON.stringify(out);
};
