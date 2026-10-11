import assert from 'node:assert/strict';
import { describe, it } from 'node:test';


import type { CubeSnapshot } from '../src/snapshot.ts';
import {
  dimensionColumns,
  kindOf,
  measureColumns,
  totalOrderSorts,
} from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';
import { printLevel } from './lite-compiler.ts';

const CUBE: CubeSnapshot = {
  source: { query: element('t') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'year', type: 'Integer', kind: 'dimension' },
    { name: 'notional', type: 'Float' },
    { name: 'traded', type: 'Date' },
  ],
  derived: [],
  rows: ['region'],
  pivotOn: ['year'],
  measures: [{ name: 'total', column: 'notional', fn: 'sum' }],
  sorts: [],
  epoch: 1,
};

describe('column kind', () => {
  it('defaults numeric columns to measures and the rest to dimensions', () => {
    assert.equal(kindOf({ name: 'n', type: 'Float' }), 'measure');
    assert.equal(kindOf({ name: 'n', type: 'Integer' }), 'measure');
    assert.equal(kindOf({ name: 's', type: 'String' }), 'dimension');
    assert.equal(kindOf({ name: 'd', type: 'Date' }), 'dimension');
  });

  it('lets an explicit kind win, which a year needs', () => {
    // A year is numeric and is almost always a dimension; defaulting
    // it to a measure would offer to sum it.
    assert.equal(
      kindOf({ name: 'year', type: 'Integer', kind: 'dimension' }),
      'dimension',
    );
  });

  it('partitions the columns for a UI to offer', () => {
    assert.deepEqual(
      dimensionColumns(CUBE).map((c) => c.name),
      ['region', 'year', 'traded'],
    );
    assert.deepEqual(
      measureColumns(CUBE).map((c) => c.name),
      ['notional'],
    );
  });
});

describe('excludedFromPivot', () => {
  // A MEASURE setting: which measures are spread across the pivot's values. It never touches
  // a pivot KEY -- upstream marks every dimension excluded (its validator requires it) and a
  // pivot key is a dimension. The rule these tests replace dropped a marked key from the query,
  // so an Integer made a dimension went into the pivot zone and pivoted nothing (the user,
  // 2026-09-28).
  it('a pivot KEY pivots even when marked excluded, as every dimension is', async () => {
    const s: CubeSnapshot = {
      ...CUBE,
      columns: CUBE.columns.map((c) =>
        c.name === 'year' ? { ...c, excludedFromPivot: true } : c,
      ),
    };
    const out = (await printLevel(s, undefined, { tuples: [['2023']] }));
    assert.match(out, /if\(\$x\.year == 2023, /, 'split by year');
    assert.match(out, /2023__\|__total/);
  });

  it('keeps a MEASURE marked excluded out of the spread', async () => {
    const s: CubeSnapshot = {
      ...CUBE,
      columns: CUBE.columns.map((c) =>
        c.name === 'notional' ? { ...c, excludedFromPivot: true } : c,
      ),
    };
    const out = (await printLevel(s, undefined, { tuples: [['2023']] }));
    assert.equal(out.includes('2023__|__total'), false, 'total is not spread by year');
  });
});

describe('treeColumnSort', () => {
  it('orders the groups descending when asked', async () => {
    // It applies at every level, including ones not yet opened, which
    // is why it is separate from the per-column sorts.
    const s: CubeSnapshot = { ...CUBE, treeColumnSort: 'desc' };
    assert.deepEqual(totalOrderSorts(s, ['region']), [
      { column: 'region', direction: 'desc' },
    ]);
    assert.match((await printLevel(s, undefined, { tuples: [['2023']] })), /sort\(\[~region->descending\(\)\]\)/);
  });

  it('defaults to ascending', () => {
    assert.deepEqual(totalOrderSorts(CUBE, ['region']), [
      { column: 'region', direction: 'asc' },
    ]);
  });

  it('does not override an explicit sort on that column', () => {
    const s: CubeSnapshot = {
      ...CUBE,
      treeColumnSort: 'desc',
      sorts: [{ column: 'region', direction: 'asc' }],
    };
    assert.deepEqual(totalOrderSorts(s, ['region']), [
      { column: 'region', direction: 'asc' },
    ]);
  });
});
