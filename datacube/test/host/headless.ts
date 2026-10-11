// Where a test's planner and database come from, in the headless lane (//datacube:headless_test): the host's -- the
// planner's worker answered by legend-lite's native library, the database Python's DuckDB -- through the same modules
// DataCube's headless bundle runs on (src/headless/host.ts). Named by the `headless` condition (package.json "imports",
// #test-host); host/node.ts says why.

import { hostCalls, hostDatabase, hostWorker } from '../../src/headless/host.ts';
import type { LitePlannerOptions } from '../../src/lite-planner.ts';
import type { TestDatabase } from './node.ts';

export type { TestDatabase };

export const HOST: 'node' | 'headless' = 'headless';

/** What a test's LitePlanner is given beside its model: a worker of the host's. */
export function plannerOptions(): Pick<LitePlannerOptions, 'assetBaseUrl' | 'worker'> {
  return { worker: () => hostWorker(hostCalls()) };
}

/** The host's database. One per host: a test's tables share it, as DuckDB-WASM's per-test databases did not. */
export async function openDatabase(): Promise<TestDatabase> {
  const db = hostDatabase(hostCalls());
  return { connection: db.connection, files: { registerFileText: db.registerFileText, registerFileBuffer: db.registerFileBuffer } };
}
