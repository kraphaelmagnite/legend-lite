// Snap mode against a real DuckDB-WASM, because the interesting
// behaviour is what actually materialises.

import assert from 'node:assert/strict';
import { engineClientRequire } from '../src/node-require.ts';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';

import { DuckDbEngine, type ArrowishConnection } from '../src/duckdb.ts';
import type { WasmGrammar } from '../src/legend/wasm-grammar.ts';
import { loadPlanner, snapPlannerFor } from './planner.ts';
import { accessor, element, from, type Lambda } from '../../pure-protocol/src/index.ts';
import { SnapManager, SnapRefusal, type RemoteSource, type SnapPlanner } from '../src/snap.ts';
import { Table, TimestampMillisecond, Utf8, tableToIPC, vectorFromArray } from 'apache-arrow';

/** The live source, and where a snap goes: the model would declare both. */
const LIVE = element('trades');
const target = (table: string) => ({ table, source: element(table) });

/** The tables a snap pulls from, as the model declares them: the planner plans each pull (D5b). */
const MODEL = `###Relational
Database t::DB
(
  Table trades (region BIGINT, book BIGINT, year INTEGER, notional DOUBLE)
  Table trades_frozen (region BIGINT, book BIGINT, year INTEGER, notional DOUBLE)
  Table huge (i BIGINT)
  Table zoned (region VARCHAR(8), at TIMESTAMP)
)

###Connection
RelationalDatabaseConnection t::Conn
{
  type: DuckDB;
  specification: DuckDB { };
  auth: Test;
}

###Runtime
Runtime t::RT
{
  mappings: [];
  connections: [ t::DB: [ c: t::Conn ] ];
}
`;

/** A pull: every column of `table`, as the cube writes it (cube.ts snap). */
const pullOf = (table: string, columns = ['region', 'book', 'year', 'notional']): Lambda =>
  from(accessor('t::DB', table)).select(columns).lambda();

let engine: DuckDbEngine;

/** The planner, which writes each drop's statements (D5) and each snap's (D5b). */
let planner: WasmGrammar;
let snaps: SnapPlanner;

before(async () => {
  planner = await loadPlanner();
  snaps = await snapPlannerFor(MODEL, 't::RT');
  const duckdb = engineClientRequire('@duckdb/duckdb-wasm/blocking');
  const dist = path.dirname(engineClientRequire.resolve('@duckdb/duckdb-wasm/blocking'));
  const db = await duckdb.createDuckDB(
    {
      mvp: {
        mainModule: path.join(dist, 'duckdb-mvp.wasm'),
        mainWorker: path.join(dist, 'duckdb-node-mvp.worker.cjs'),
      },
      eh: {
        mainModule: path.join(dist, 'duckdb-eh.wasm'),
        mainWorker: path.join(dist, 'duckdb-node-eh.worker.cjs'),
      },
    },
    new duckdb.VoidLogger(),
    duckdb.NODE_RUNTIME,
  );
  await db.instantiate();
  engine = new DuckDbEngine(db.connect() as ArrowishConnection);
  await engine.run(
    `CREATE TABLE trades AS
       SELECT (i%5) AS region, (i%20) AS book,
              CAST(2020 + (i%4) AS INTEGER) AS year,
              (i*7.5) AS notional
       FROM range(1000) t(i)`,
    0,
  );
  // past the row ceiling, counted without being made (a view of a range)
  await engine.run('CREATE VIEW huge AS SELECT i FROM range(20000000) t(i)', 0);
  // the warehouse's table a pull reads (counted there: this DuckDB stands in for it)
  await engine.run("CREATE TABLE zoned AS SELECT * FROM (VALUES ('EMEA', TIMESTAMP '2024-01-01 10:00:00'), "
    + "('AMER', TIMESTAMP '2024-01-02 11:00:00')) t(region, \"at\")", 0);
});

/** `zoned`'s rows as a warehouse streams them: one Arrow IPC stream a chunk. */
const zonedRows = (): Uint8Array => tableToIPC(new Table({
  region: vectorFromArray(['EMEA', 'AMER'], new Utf8()),
  at: vectorFromArray([new Date('2024-01-01T10:00:00Z'), new Date('2024-01-02T11:00:00Z')], new TimestampMillisecond()),
}), 'stream');

/** A warehouse: its count run on this DuckDB, its pull's rows streamed -- or cut short after the first chunk. */
class Warehouse implements RemoteSource {
  readonly databaseType = 'DuckDB';
  cut = false;
  run(sql: string, epoch: number) {
    return engine.run(sql, epoch);
  }
  async *arrowChunks(): AsyncIterable<Uint8Array> {
    yield zonedRows();
    if (this.cut) throw new Error('the connection dropped');
  }
}

const tableNamed = async (name: string): Promise<number> => Number((await engine.run(
  `SELECT count(*) AS n FROM duckdb_tables() WHERE table_name = '${name}'`, 0)).columns[0]?.values[0]);

after(async () => {
  await engine?.close();
});

describe('SnapManager', () => {
  it('starts live and reads from the live source', () => {
    const s = new SnapManager(engine, null, planner);
    assert.equal(s.state.mode, 'live');
    assert.equal(s.isSnapped, false);
    assert.equal(s.sourceFor(LIVE), LIVE);
  });

  it('freezes rows and reports what was taken, and when', async () => {
    const s = new SnapManager(engine, null, planner);
    const before = Date.now();
    const info = await s.snap(snaps, pullOf('trades'), 1, { target: target('snap_a') });

    assert.equal(info.rowCount, 1000);
    assert.ok(info.takenAt.getTime() >= before);
    assert.match(info.label, /^Snap \d\d:\d\d$/);
    assert.equal(s.isSnapped, true);
    // Queries now read the frozen table, not the live source.
    assert.deepEqual(s.sourceFor(LIVE), element('snap_a'));
  });

  it('is genuinely frozen: live changes do not reach the snap', async () => {
    // its own live table, so the row it adds reaches no other test (Bazel workplan P3-10)
    await engine.run('CREATE TABLE trades_frozen AS SELECT * FROM trades', 0);
    const s = new SnapManager(engine, null, planner);
    const { table: snapped } = await s.snap(snaps, pullOf('trades_frozen'), 1, { target: target('snap_frozen') });

    // The world moves on underneath.
    await engine.run(
      'INSERT INTO trades_frozen SELECT 9, 9, 2024, 1.0',
      2,
    );

    const fromSnap = await engine.run(
      `SELECT count(*) AS n FROM ${snapped}`,
      3,
    );
    const fromLive = await engine.run(
      'SELECT count(*) AS n FROM trades_frozen',
      4,
    );
    assert.equal(fromSnap.columns[0]?.values[0], 1000, 'snap is stable');
    assert.equal(fromLive.columns[0]?.values[0], 1001, 'live moved');
  });

  it('returns to live on release', async () => {
    const s = new SnapManager(engine, null, planner);
    await s.snap(snaps, pullOf('trades'), 1, { target: target('snap_released') });
    await s.release();
    assert.equal(s.state.mode, 'live');
    assert.equal(s.sourceFor(LIVE), LIVE);
  });

  it('refuses a snap beyond the row ceiling, before materialising', async () => {
    const s = new SnapManager(engine, null, planner);
    await assert.rejects(
      () => s.snap(snaps, pullOf('huge', ['i']), 2, { target: target('snap_huge') }),
      (e: unknown) => {
        assert.ok(e instanceof SnapRefusal);
        assert.match(e.reason, /20,000,000 rows exceeds the .* row snap limit/);
        return true;
      },
    );
    const made = await engine.run("SELECT count(*) AS n FROM duckdb_tables() WHERE table_name = 'snap_huge'", 3);
    assert.equal(Number(made.columns[0]?.values[0]), 0, 'nothing made');
    // Refused means nothing was created and we are still live.
    assert.equal(s.isSnapped, false);
  });

  it('refuses a host operation that carries SQL, by name: no operation has a field for it (D5b)', async () => {
    await assert.rejects(planner.hostOperations('DuckDB', [{
      op: 'relation', kind: 'table', target: { name: 'x' }, selectList: 'upper(x)', from: { relation: { name: 'y' } },
    } as never]), /relation operation has no field 'selectList'/);
    await assert.rejects(planner.hostOperations('DuckDB', [{ op: 'count', from: { query: 'SELECT 1' } } as never]),
      /a source is a relation, a file or a remote file/);
  });

  it('a pull cut short keeps the last snap and leaves no landing behind: the next lands afresh', async () => {
    const warehouse = new Warehouse();
    const s = new SnapManager(engine, warehouse, planner);
    const into = { target: target('snap_zoned') };
    assert.equal((await s.snap(snaps, pullOf('zoned', ['region', 'at']), 1, into)).rowCount, 2);
    warehouse.cut = true;
    await assert.rejects(() => s.snap(snaps, pullOf('zoned', ['region', 'at']), 2, into), /the connection dropped/);
    assert.equal(await tableNamed('snap_zoned__pulled'), 0, 'the landing a cut pull began is gone');
    const kept = await engine.run('SELECT count(*) AS n FROM snap_zoned', 3);
    assert.equal(Number(kept.columns[0]?.values[0]), 2, 'the last snap is still there');
    warehouse.cut = false;
    const info = await s.snap(snaps, pullOf('zoned', ['region', 'at']), 4, into);
    assert.equal(info.rowCount, 2);
    assert.equal(await tableNamed('snap_zoned__pulled'), 0);
  });

  it('refuses SQL in an operation\'s nested field (D5b)', async () => {
    await assert.rejects(planner.hostOperations('DuckDB', [{
      op: 'count', from: { relation: { name: 't' }, query: 'SELECT 1' },
    } as never]), /a source is one of a relation, a file or a remote file/);
    await assert.rejects(planner.hostOperations('DuckDB', [{
      op: 'drop', kind: 'table', target: { name: 't', sql: 'DROP TABLE x' },
    } as never]), /name has no field 'sql'/);
  });

  it('gives each snap its own table so two can coexist', async () => {
    const s = new SnapManager(engine, null, planner);
    const a = await s.snap(snaps, pullOf('trades'), 1, { target: target('snap_region_0') });
    const b = await s.snap(snaps, pullOf('trades', ['region', 'notional']), 2, { target: target('snap_region_1') });
    assert.notEqual(a.table, b.table);
    // Snap A survives taking snap B, which is what makes an
    // as-of-A vs as-of-B comparison possible.
    const stillThere = await engine.run(
      `SELECT count(*) AS n FROM "${a.table}"`,
      3,
    );
    assert.equal(stillThere.columns[0]?.values[0], 1000);
  });
});
