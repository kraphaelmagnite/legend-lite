// Where a test's planner and database come from, in Node: legend-lite's WebAssembly module, in-thread, and DuckDB-WASM
// -- as DataCube's tests have always run. The headless lane builds the same tests with the `headless` condition, which
// names host/headless.ts instead (package.json "imports", #test-host: the swap is the build's, never decided at run
// time). The code under test is the same in both: LitePlanner and DuckDbEngine
// (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import path from 'node:path';

import type { ArrowishConnection } from '../../../engine-client/src/duckdb.ts';
import { engineClientRequire } from '../../../engine-client/src/node-require.ts';
import type { LitePlannerOptions } from '../../src/lite-planner.ts';
import type { DuckDbFiles } from '../../src/upload.ts';
import { runfileDirUrl } from '../../../tools/js/runfiles.mts';

/** A test's database: its connection, and the files it can read by name, as DataCube's page registers them. */
export interface TestDatabase {
  readonly connection: ArrowishConnection;
  readonly files: DuckDbFiles;
}

/** Which host a test runs on. */
export const HOST: 'node' | 'headless' = 'node';

/** What a test's LitePlanner is given beside its model: the module's folder, run in-thread. */
export function plannerOptions(): Pick<LitePlannerOptions, 'assetBaseUrl' | 'worker'> {
  return { assetBaseUrl: runfileDirUrl('WASM_PLANNER') };
}

/** A database of its own: DuckDB-WASM's blocking build, from engine-client's package. */
export async function openDatabase(): Promise<TestDatabase> {
  const duckdb = engineClientRequire('@duckdb/duckdb-wasm/blocking');
  const dist = path.dirname(engineClientRequire.resolve('@duckdb/duckdb-wasm/blocking'));
  const db = await duckdb.createDuckDB(
    {
      mvp: { mainModule: path.join(dist, 'duckdb-mvp.wasm'), mainWorker: path.join(dist, 'duckdb-node-mvp.worker.cjs') },
      eh: { mainModule: path.join(dist, 'duckdb-eh.wasm'), mainWorker: path.join(dist, 'duckdb-node-eh.worker.cjs') },
    },
    new duckdb.VoidLogger(),
    duckdb.NODE_RUNTIME,
  );
  await db.instantiate();
  return {
    connection: db.connect() as ArrowishConnection,
    // the blocking bindings register at once; DataCube's page takes the async form
    files: {
      registerFileText: async (name, text) => { db.registerFileText(name, text); },
      registerFileBuffer: async (name, buffer) => { db.registerFileBuffer(name, buffer); },
    },
  };
}
