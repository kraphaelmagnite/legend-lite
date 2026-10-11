import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { DEFAULT_DRILL_LIMIT, drillConditions } from '../src/query.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';
import { printDrill, row } from './lite-compiler.ts';

const SNAPSHOT: CubeSnapshot = {
  source: { query: element('trades') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'desk', type: 'String' },
    { name: 'year', type: 'Integer' },
    { name: 'notional', type: 'Float' },
  ],
  derived: [],
  rows: ['region', 'desk'],
  pivotOn: ['year'],
  measures: [{ name: 'total', column: 'notional', fn: 'sum' }],
  sorts: [],
  epoch: 1,
};

describe('the drill query', () => {
  it('pins the row path and the pivot value, and does not aggregate', async () => {
    const sql = (await printDrill(SNAPSHOT, {
      path: ['EMEA', 'Rates'],
      pivotPath: ['2023'],
    }));
    assert.equal(
      sql,
      // each condition parenthesized (the grammar applies < <= > >= left to right with && ||)
      "|trades->filter(x|(($x.region == 'EMEA') && ($x.desk == 'Rates')) "
        + "&& ($x.year == 2023))" +
        `->limit(${DEFAULT_DRILL_LIMIT})`,
    );
    // The population, not its aggregate: drilling into a number must
    // show the rows it was computed from.
    assert.equal(sql.includes('groupBy('), false);
    assert.equal(sql.includes('pivot('), false);
  });

  it('keeps the cube filter, so the drill sees the same population', async () => {
    const s: CubeSnapshot = {
      ...SNAPSHOT,
      filter: {
        kind: 'condition',
        column: 'notional',
        operator: 'greaterThan',
        value: 0,
      },
    };
    assert.match(
      (await printDrill(s, { path: ['EMEA'] })),
      /\(\$x\.notional > 0\.0\) && \(\$x\.region == 'EMEA'\)/,
    );
  });

  it('drills the grand total to the whole filtered population', async () => {
    assert.equal(
      (await printDrill(SNAPSHOT, { path: [] })),
      `|trades->limit(${DEFAULT_DRILL_LIMIT})`,
    );
  });

  it('matches a NULL group with isEmpty', async () => {
    assert.match(
      (await printDrill(SNAPSHOT, { path: [null] })),
      /\$x\.region->isEmpty\(\)/,
    );
  });

  it('applies derived columns before filtering on them', async () => {
    const s: CubeSnapshot = {
      ...SNAPSHOT,
      derived: [{ name: 'net', lambda: (await row('$x.notional * 0.98')) }],
    };
    assert.match(
      (await printDrill(s, { path: ['EMEA'] })),
      /^\|trades->extend\(~\[net:x\|\$x\.notional \* 0\.98\]\)->filter\(/,
    );
  });

  it('caps the rows, because a drill is a peek not an export', async () => {
    assert.match((await printDrill(SNAPSHOT, { path: [], limit: 25 })), /->limit\(25\)$/);
  });
});

describe('drillConditions', () => {
  it('exposes the conditions so a reviewer can read them', () => {
    const cs = drillConditions(SNAPSHOT, {
      path: ['EMEA'],
      pivotPath: ['2024'],
    });
    assert.deepEqual(cs, [
      { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' },
      { kind: 'condition', column: 'year', operator: 'equal', value: '2024' },
    ]);
  });

  it('ignores a path deeper than the cube has dimensions', () => {
    const cs = drillConditions(SNAPSHOT, { path: ['A', 'B', 'C'] });
    assert.equal(cs.length, 2, 'only region and desk exist');
  });
});
