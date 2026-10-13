// C5's MEASUREMENTS IN THE TAB (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5; c5-timings.py measures Python and the
// server): a project's own database inside the tab's DuckDB (DuckDB-WASM, here in Node, its blocking build) -- made and
// filled by engine-client's ProjectDatabases the first time a query over the model asks, then that query, first and
// repeated -- the planner the tab's module (//wasm:planner_dir), answering as the tab's worker does. The same three
// models as c5-timings.py: the trading demo, and a party model of 1,000 and 100,000 rows.
//
// Run from the repository's root after `bazel build //engine-client:engine_client //wasm:planner_dir`, with Node 22:
//   node --experimental-strip-types --experimental-wasm-exnref docs/one-platform/evidence/c5-timings-tab.mts "$PWD/bazel-bin"
// Measured 2026-10-13 (Apple silicon laptop): the runbook's C5 Built notes quote the numbers.

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const bin = process.argv[2];
if (!bin) throw new Error('give bazel-bin\'s path');
const at = (p: string): string => pathToFileURL(path.join(bin, p)).href;
const { ProjectDatabases } = await import(at('engine-client/src/project-data.ts'));
const { DuckDbEngine } = await import(at('engine-client/src/duckdb.ts'));
const { WasmGrammar } = await import(at('engine-client/src/legend/wasm-grammar.ts'));
const { answer } = await import(at('engine-client/src/legend/planner-answer.ts'));

const planner = await (await import(at('wasm/planner_dir/wasm-gc-module-runtime.js'))).load(
  path.join(bin, 'wasm/planner_dir/classes.wasm'),
  { stackDeobfuscator: { enabled: false }, installImports(i: Record<string, unknown>) { i.teavmConsole = { putcharStdout() {}, putcharStderr() {} }; } },
);
const ask = async (r: unknown): Promise<string> => answer(planner, r);
const value = (tagged: string): string => {
  if (!tagged.startsWith('OK\n')) throw new Error(tagged);
  return tagged.slice(3);
};
const grammar = new WasmGrammar({ ask });

const require = createRequire(path.join(bin, 'engine-client/package.json'));
const duckdb = require('@duckdb/duckdb-wasm/blocking');
const dist = path.dirname(require.resolve('@duckdb/duckdb-wasm/blocking'));

const root = process.cwd();
const TRADING = ['trading.pure', 'runtime-duckdb.pure', 'trading-data.pure']
  .map((f) => readFileSync(path.join(root, 'query/demo/models', f), 'utf8')).join('\n');
const TRADING_QUERY = '|demo::trading::Trade.all()->project(~[tradeId: x|$x.tradeId, trader: x|$x.trader.lastName, '
  + 'ticker: x|$x.product.ticker])->from(demo::trading::TradingMapping, demo::trading::Runtime)';
// the party model c5-timings.py writes, read from it so the two measure the same text
const python = readFileSync(path.join(root, 'docs/one-platform/evidence/c5-timings.py'), 'utf8');
const template = /return f"""(###Relational[\s\S]*?)"""/.exec(python)![1]!.replace(/\{\{/g, '{').replace(/\}\}/g, '}');
const party = (rows: number): string => {
  let csv = 'ID,NAME,COUNTRY\\n';
  for (let i = 1; i <= rows; i++) csv += `${i},Party ${i},${i % 2 ? 'GB' : 'US'}\\n`;
  return template.replace('{csv}', csv);
};
const PARTY_QUERY = "|demo::party::Party.all()->filter(p|$p.country == 'GB')->project(~[id: p|$p.id, name: p|$p.name])"
  + '->from(demo::party::PartyMapping, demo::party::Runtime)';

const ms = (t: number): string => `${t.toFixed(1)} ms`;
const median = (xs: number[]): number => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]!;

console.log("== the tab (engine-client's ProjectDatabases on DuckDB-WASM, the tab's planner module)");
for (const [label, model, query, runtime] of [
  ['trading (6 tables, 39 rows)', TRADING, TRADING_QUERY, 'demo::trading::Runtime'],
  ['party, 1,000 rows', party(1_000), PARTY_QUERY, 'demo::party::Runtime'],
  ['party, 100,000 rows', party(100_000), PARTY_QUERY, 'demo::party::Runtime'],
] as const) {
  const db = await duckdb.createDuckDB({
    mvp: { mainModule: path.join(dist, 'duckdb-mvp.wasm'), mainWorker: path.join(dist, 'duckdb-node-mvp.worker.cjs') },
    eh: { mainModule: path.join(dist, 'duckdb-eh.wasm'), mainWorker: path.join(dist, 'duckdb-node-eh.worker.cjs') },
  }, new duckdb.VoidLogger(), duckdb.NODE_RUNTIME);
  await db.instantiate();
  const host = new DuckDbEngine(db.connect());
  const projects = new ProjectDatabases({
    databaseType: 'DuckDB', run: (sql: string) => host.run(sql, 0), connect: async () => new DuckDbEngine(db.connect()),
  }, grammar);
  let begun = performance.now();
  const { engine } = await projects.engineFor(model, host);
  const filled = performance.now() - begun;
  const handle = Number(value(await ask({ kind: 'hold', model })));
  begun = performance.now();
  const sql = JSON.parse(value(await ask({ kind: 'plan', handle, query, runtime }))).sql as string;
  const first = await engine.run(sql, 0);
  const firstMs = performance.now() - begun;
  // the app's path: the project's engine asked once as it opens (Query's activate), each query then planned and run
  const repeats: number[] = [];
  for (let i = 0; i < 5; i++) {
    begun = performance.now();
    const again = JSON.parse(value(await ask({ kind: 'plan', handle, query, runtime }))).sql as string;
    await engine.run(again, 0);
    repeats.push(performance.now() - begun);
  }
  // the project opened again (its database kept): the planner names it again from the model's text
  const reopened: number[] = [];
  for (let i = 0; i < 3; i++) {
    begun = performance.now();
    await projects.engineFor(model, host);
    reopened.push(performance.now() - begun);
  }
  console.log(`${label}: made and filled ${ms(filled)}; first query ${ms(firstMs)} (${first.rowCount.toLocaleString('en')} rows); `
    + `repeated (plan + run) ${ms(median(repeats))}; opened again (its database kept) ${ms(median(reopened))}`);
  await host.close();
}
