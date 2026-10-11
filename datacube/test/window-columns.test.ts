// Window columns, as the compiler prints them: every function in the `over()` form the
// planner takes (the WASM differential plans these same shapes on both
// planners), and how a group-level window follows the tree's levels.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { extendWindow } from '../src/query.ts';
import { renameColumnReferences, type CubeSnapshot, type WindowSpec } from '../src/snapshot.ts';
import { element, from } from '../../pure-protocol/src/index.ts';
import { print, printLevel } from './lite-compiler.ts';

/** A window column over `t`, as the compiler prints the query that extends it. */
function windowExtend(name: string, w: WindowSpec): Promise<string> {
  return print(extendWindow(from(element('t')), name, w).lambda());
}

const base = { partition: [] as string[], order: [] as { column: string; direction: 'asc' | 'desc' }[] };

describe('window columns as Pure', () => {
  it('a running sum: partition, order, frame', async () => {
    assert.equal((await windowExtend('cum', { ...base, fn: 'sum', column: 'notional',
      partition: ['region', 'desk'], order: [{ column: 'year', direction: 'asc' }], frame: 'running' })),
    '|t->extend(over(~[region, desk], [~year->ascending()], unbounded()->rows(0)), ~[cum:{p, w, r|$r.notional}:y|$y->plus()])');
  });

  it('with no partition, the order alone -- or, to carry a frame, one constant partition (engine has no unpartitioned frame, S24)', async () => {
    assert.equal((await windowExtend('rn', { ...base, fn: 'rowNumber', order: [{ column: 'pnl', direction: 'desc' }] })),
      '|t->extend(over([~pnl->descending()]), ~[rn:{p, w, r|$p->rowNumber($r)}])');
    assert.equal((await windowExtend('m', { ...base, fn: 'average', column: 'pnl',
      order: [{ column: 'year', direction: 'asc' }], frame: { lastRows: 3 } })),
    '|t->extend(~[__all__m:x|\'all\'])->extend(over(~[__all__m], [~year->ascending()], (-2)->rows(0)), ~[m:{p, w, r|$r.pnl}:y|$y->average()])');
  });

  it('a whole-table aggregate: every row, whatever the order', async () => {
    assert.equal((await windowExtend('t', { ...base, fn: 'max', column: 'pnl' })),
      '|t->extend(~[__all__t:x|\'all\'])->extend(over(~[__all__t], [~pnl->ascending()], unbounded()->rows(unbounded())), ~[t:{p, w, r|$r.pnl}:y|$y->max()])');
  });

  it('ranking takes no frame; lag reads its column n rows back; last reads the whole partition', async () => {
    const o = [{ column: 'year', direction: 'asc' as const }];
    assert.equal((await windowExtend('r', { ...base, fn: 'rank', order: o, frame: 'running' })),
      '|t->extend(over([~year->ascending()]), ~[r:{p, w, r|$p->rank($w, $r)}])');
    assert.equal((await windowExtend('p', { ...base, fn: 'lag', column: 'pnl', partition: ['book'], order: o, offset: 2 })),
      '|t->extend(over(~[book], [~year->ascending()]), ~[p:{p, w, r|$p->lag($r, 2).pnl}])');
    assert.equal((await windowExtend('n', { ...base, fn: 'lead', column: 'pnl', order: o })),
      '|t->extend(over([~year->ascending()]), ~[n:{p, w, r|$p->lead($r).pnl}])');
    assert.equal((await windowExtend('l', { ...base, fn: 'last', column: 'pnl', partition: ['book'], order: o })),
      '|t->extend(over(~[book], [~year->ascending()], unbounded()->rows(unbounded())), ~[l:{p, w, r|$p->last($w, $r).pnl}])');
    assert.equal((await windowExtend('b', { ...base, fn: 'ntile', order: o, buckets: 10 })),
      '|t->extend(over([~year->ascending()]), ~[b:{p, w, r|$p->ntile($r, 10)}])');
  });

  it('refuses what cannot mean anything', () => {
    assert.throws(() => windowExtend('r', { ...base, fn: 'rank' }), /needs an order/);
    assert.throws(() => windowExtend('s', { ...base, fn: 'sum', order: [{ column: 'year', direction: 'asc' }] }),
      /needs a column/);
  });
});

describe('a group-level window follows the tree', () => {
  const CUBE: CubeSnapshot = {
    source: { query: element('t') },
    columns: [
      { name: 'region', type: 'String' }, { name: 'desk', type: 'String' },
      { name: 'notional', type: 'Float' },
    ],
    derived: [],
    rows: ['region', 'desk'],
    pivotOn: [],
    measures: [{ name: 'notional', column: 'notional', fn: 'sum' }],
    sorts: [],
    epoch: 1,
    groupDerived: [{ name: 'running', window: {
      fn: 'sum', column: 'notional', partition: ['region'], order: [], frame: 'running' } as WindowSpec }],
  };

  it('at a level, an empty order is the level\'s own; a dimension it lacks leaves the partition', async () => {
    // Level 1 has region: each region is its own partition there.
    const q1 = (await printLevel(CUBE, { level: 1, parent: [] }));
    assert.match(q1, /extend\(over\(~\[region\], \[~region->ascending\(\)\], unbounded\(\)->rows\(0\)\), ~\[running:/);
    // A partition on a dimension the level lacks drops out.
    const byDesk = { ...CUBE, groupDerived: [{ ...CUBE.groupDerived![0]!, window: {
      ...CUBE.groupDerived![0]!.window!, partition: ['desk'] } }] };
    assert.match((await printLevel(byDesk, { level: 1, parent: [] })),
      /extend\(~\[__all__running:x\|'all'\]\)->extend\(over\(~\[__all__running\], \[~region->ascending\(\)\], unbounded\(\)->rows\(0\)\)/);
    const q2 = (await printLevel(CUBE, { level: 2, parent: ['EMEA'] }));
    assert.match(q2, /extend\(over\(~\[region\], \[~region->ascending\(\), ~desk->ascending\(\)\], unbounded\(\)->rows\(0\)\)/);
  });

  it('follows the grid\'s own sort when it has one', async () => {
    const sorted = { ...CUBE, sorts: [{ column: 'notional', direction: 'desc' as const }] };
    assert.match((await printLevel(sorted, { level: 1, parent: [] })),
      /over\(~\[region\], \[~notional->descending\(\), ~region->ascending\(\)\], unbounded\(\)->rows\(0\)\)/);
  });

  it('the grand total orders by its one group', async () => {
    assert.match((await printLevel(CUBE, { level: 0, parent: [] })), /over\(~\[__all__running\], \[~__root__->ascending\(\)\], unbounded\(\)->rows\(0\)\)/);
  });

  it('a row-level window is written as its extend, before the filter', async () => {
    const row: CubeSnapshot = { ...CUBE, groupDerived: [], derived: [{ name: 'rk', window: {
      fn: 'rank', partition: ['region'], order: [{ column: 'notional', direction: 'desc' }] } }],
      filter: { kind: 'condition', column: 'desk', operator: 'equal', value: 'Rates' } };
    const q = (await printLevel(row, { level: 1, parent: [] }));
    assert.ok(q.indexOf('rank(') < q.indexOf('filter('), q);
    assert.equal((await windowExtend('rk', row.derived[0]!.window!)),
      '|t->extend(over(~[region], [~notional->descending()]), ~[rk:{p, w, r|$p->rank($w, $r)}])');
  });

  it('a rename reaches the columns a window names', () => {
    const renamed = renameColumnReferences(CUBE, 'notional', 'amount');
    assert.equal(renamed.groupDerived?.[0]?.window?.column, 'amount');
  });
});
