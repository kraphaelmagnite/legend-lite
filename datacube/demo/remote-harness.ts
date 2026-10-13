// A page that mounts a remote Parquet and queries it.
//
// Separate from the demo because the thing under test is the
// CONNECTION, not the grid: booting the whole app would prove the
// app works and say nothing about whether bytes moved. This boots
// duckdb-wasm, calls the real mountRemote, and runs the shape of
// query the planner emits.
//
// It has to run in a browser. The node build of duckdb-wasm answers
// a synthetic 404 for any http:// URL rather than making a request,
// so a node test here would be green and meaningless.


import type { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import { startDuckDbInTab } from '../../engine-client/src/duckdb-tab.ts';
import type { TableModels } from '../src/infer.ts';
import { mountRemote, type RemoteSource } from '../src/remote.ts';
import { plannerFor } from './planners.ts';

declare global {
  interface Window {
    remoteTest?: (source: RemoteSource) => Promise<unknown>;
  }
}

async function boot(): Promise<{ readonly engine: DuckDbEngine; readonly tables: TableModels }> {
  // the tab's planner: it writes the tab's DuckDB's set-up and the mount's statements (D5); the one start of a tab's
  // DuckDB (engine-client's startDuckDbInTab)
  const { tables } = await plannerFor('local', '');
  return { engine: (await startDuckDbInTab('./vendor/', tables)).engine, tables };
}

const ready = boot();

window.remoteTest = async (source: RemoteSource) => {
  const { engine, tables } = await ready;
  await mountRemote(engine, tables, { sources: [source] });

  // The shape the planner emits: a grouped aggregate over the table
  // NAME, with no idea the rows are coming over the network.
  const table = await engine.run(
    `SELECT region, sum(notional) AS m FROM ${JSON.stringify(source.name)}`
    + ' GROUP BY region ORDER BY region',
    1,
  );
  return {
    columns: table.columns.map((c) => c.name),
    rowCount: table.rowCount,
    regions: table.columns[0]?.values ?? [],
    totals: table.columns[1]?.values ?? [],
  };
};

document.body.dataset['ready'] = 'yes';
