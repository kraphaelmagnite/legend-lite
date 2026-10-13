// The demo's sample trades (src/sample-trades.ts), held to the statement that made them before D5: every row of
// 200,000 and every column's type the same as DuckDB's own `range()` made them, put in by DuckDB's Arrow reader.

import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { openDatabase } from '#test-host';

import { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import { SAMPLE_TRADES, sampleTrades } from '../src/sample-trades.ts';

let engine: DuckDbEngine;

before(async () => {
  engine = new DuckDbEngine((await openDatabase()).connection);
});

after(async () => {
  await engine?.close();
});

/** The statement demo/boot.ts ran before D5, as it was. */
const BEFORE = `CREATE OR REPLACE TABLE before AS
     SELECT
       CASE (i % 3) WHEN 0 THEN 'EMEA' WHEN 1 THEN 'AMER' WHEN 2 THEN 'APAC' END AS region,
       CASE ((i // 3) % 5) WHEN 0 THEN 'Rates' WHEN 1 THEN 'Credit' WHEN 2 THEN 'FX' WHEN 3 THEN 'Equity'
         WHEN 4 THEN 'Commodities' END AS desk,
       (2021 + ((i // 15) % 5))                AS year,
       ('Q' || (1 + ((i // 75) % 4)))          AS qtr,
       ('Book ' || (1 + ((i // 300) % 4)))     AS book,
       ((i * 7919) % 1000000) / 100.0  AS notional,
       ((i * 104729) % 200000) / 100.0 - 1000.0 AS pnl,
       ((i * 31) % 97) + 1             AS qty
     FROM range(${SAMPLE_TRADES}) t(i)`;

const one = (sql: string): Promise<unknown> => engine.run(sql, 0).then((t) => t.columns[0]?.values[0]);
const columns = async (table: string): Promise<string[]> => {
  const t = await engine.run(`SELECT column_name, data_type FROM duckdb_columns() WHERE table_name = '${table}'
    ORDER BY column_index`, 0);
  return t.columns[0]!.values.map((name, i) => `${String(name)} ${String(t.columns[1]!.values[i])}`);
};

describe("the demo's sample trades", () => {
  it('every row and every column type as the statement before D5 made them', async () => {
    await engine.run(BEFORE, 0);
    await engine.loadArrow({ table: 'made' }, (async function* () { yield sampleTrades(); })());
    assert.deepEqual(await columns('made'), await columns('before'));
    assert.equal(Number(await one('SELECT count(*) FROM made')), SAMPLE_TRADES);
    // both ways, every row counted (EXCEPT ALL keeps duplicates)
    assert.equal(Number(await one(`SELECT count(*) FROM ((SELECT * FROM made EXCEPT ALL SELECT * FROM before)
      UNION ALL (SELECT * FROM before EXCEPT ALL SELECT * FROM made))`)), 0);
  });
});
