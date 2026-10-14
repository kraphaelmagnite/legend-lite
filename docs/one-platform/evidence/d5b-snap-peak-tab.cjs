// D5b'S SNAP PEAK IN THE TAB'S DUCKDB (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5b; Studio's re-review): a local
// re-snap at MAX_SNAP_ROWS (engine-client/src/snap.ts) and at 4M rows, on DuckDB-WASM -- the tab's own build, run
// blocking in Node -- in three ways, each in a process of its own:
//   before-d5b  one statement the tab wrote around the pull: CREATE OR REPLACE TABLE snap AS <the pull>
//   b832ab481   D5b as first reviewed: the pull's whole answer through the page (query, then insertArrowTable) into
//               a landing, the landing COPIED into the snap (CREATE OR REPLACE ... AS SELECT * FROM landing), dropped
//   now         the pull streamed a batch at a time (send, allowStreamResult) into a landing on a second connection
//               (DuckDbEngine.loadQuery), the landing then RENAMED the snap in one transaction (the rename operation)
// WebAssembly memory only grows, so its size after a step is that step's peak: each line gives the memory before the
// step (the live table and the old snap made: two tables) and after it, the growth in tables of this shape (one table
// is DuckDB's own count of what it holds, duckdb_memory(), halved), and that the snap holds the live rows exactly.
// The shape is the worst case the limit was sized on: a UUID text beside numbers, a timestamp and a short text.
//
// Run from the repository's root after `bazel build //engine-client:node_modules`:
//   node docs/one-platform/evidence/d5b-snap-peak-tab.cjs bazel-bin/engine-client/node_modules
// Measured 2026-10-13 (Apple silicon laptop, DuckDB-WASM 1.33.1-dev57.0): d5b-snap-peak-tab.out.txt. At the limit
// itself the tab's DuckDB (3.1 GiB) holds this shape's live table and an old snap but refuses the re-snap in every way,
// before D5b too (an Out of Memory error), so the ways are compared at 4M rows.

const path = require('node:path');
const { execFileSync } = require('node:child_process');

const SHAPE = (rows) => `SELECT i::BIGINT AS id, uuid()::VARCHAR AS ref, 'T' || (i % 500)::VARCHAR AS ticker,
  (i % 1000)::INTEGER AS qty, random() * 100 AS price, TIMESTAMP '2026-01-01' + to_seconds(i) AS at
  FROM range(${rows}) t(i)`;

let stage = 'starting';

async function one(modules, mode, rows) {
  const duckdb = require(path.resolve(modules, '@duckdb/duckdb-wasm/dist/duckdb-node-blocking.cjs'));
  const dist = path.resolve(modules, '@duckdb/duckdb-wasm/dist');
  const db = await duckdb.createDuckDB({
    mvp: { mainModule: path.join(dist, 'duckdb-mvp.wasm'), mainWorker: path.join(dist, 'duckdb-node-mvp.worker.cjs') },
    eh: { mainModule: path.join(dist, 'duckdb-eh.wasm'), mainWorker: path.join(dist, 'duckdb-node-eh.worker.cjs') },
  }, new duckdb.VoidLogger(), duckdb.NODE_RUNTIME);
  await db.instantiate();
  const conn = db.connect();
  const mb = () => Math.round(db.mod.HEAPU8.length / 1048576);
  const n = (sql) => Number(conn.query(sql).getChildAt(0).get(0));
  stage = 'the live table and the old snap made';
  conn.query(`CREATE TABLE live AS ${SHAPE(rows)}`);
  conn.query('CREATE TABLE snap AS SELECT * FROM live');
  const table = n('SELECT sum(memory_usage_bytes) FROM duckdb_memory()') / 2 / 1048576;
  const before = mb();
  const started = Date.now();
  stage = 'the re-snap';
  if (mode === 'before-d5b') {
    conn.query('CREATE OR REPLACE TABLE snap AS SELECT * FROM live');
  } else if (mode === 'b832ab481') {
    conn.insertArrowTable(conn.query('SELECT * FROM live'), { name: 'snap__pulled', create: true });
    conn.query('CREATE OR REPLACE TABLE snap AS SELECT * FROM snap__pulled');
    conn.query('DROP TABLE snap__pulled');
  } else {
    const loading = db.connect();
    let first = true;
    for await (const batch of await conn.send('SELECT * FROM live', true)) {
      loading.insertArrowTable([batch], { name: 'snap__pulled', create: first });
      first = false;
    }
    for (const sql of ['BEGIN TRANSACTION', 'DROP TABLE IF EXISTS snap', 'ALTER TABLE snap__pulled RENAME TO snap',
      'COMMIT']) conn.query(sql);
  }
  const after = mb();
  return {
    mode, rows, 'one table (MB)': Math.round(table), 'before the step (MB)': before, 'after it (MB)': after,
    'growth (tables)': Number(((after - before) / table).toFixed(2)), ms: Date.now() - started,
    'snap rows': n('SELECT count(*) FROM snap'),
    'rows unlike live': n('SELECT count(*) FROM (SELECT * FROM snap EXCEPT ALL SELECT * FROM live)'),
    'landing left': n("SELECT count(*) FROM duckdb_tables() WHERE table_name = 'snap__pulled'"),
  };
}

const [modules, mode, rows] = process.argv.slice(2);
if (mode) {
  one(modules, mode, Number(rows)).then((r) => console.log(JSON.stringify(r)), (e) => console.log(JSON.stringify({
    mode, rows: Number(rows), refused: `${stage}: ${String(e?.message ?? e).split('\n')[0]}` })));
} else {
  // the limit itself, then rows of this shape the tab's DuckDB has room to re-snap in every way
  for (const n of [10_000_000, 4_000_000]) {
    for (const m of ['before-d5b', 'b832ab481', 'now']) {
      process.stdout.write(execFileSync(process.execPath, [__filename, modules, m, String(n)],
        { encoding: 'utf8', maxBuffer: 1 << 20 }));
    }
  }
}
