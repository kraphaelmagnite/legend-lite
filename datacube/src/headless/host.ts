// DataCube's side of an embedding host: Python's V8, where DataCube's non-screen code runs with no browser and no
// WebAssembly (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §3). The host answers four calls, each asynchronous
// (a call out of the engine is: mini-racer's only kind), and this module turns them into what DataCube's code already
// takes: a planner worker (planner-protocol.ts, answered by legend-lite's native library) and a DuckDB connection
// (engine-client's ArrowishConnection, answered by Python's DuckDB as Arrow IPC). Nothing here plans or queries.
//
// The host defines its calls as the global `legendLiteHost` BEFORE it loads a bundle, as a browser's globals are there
// before a page's scripts run: a module may ask the planner as it loads (a test's fixture, parsed at its top level).

import { arrowIpcTable } from '../../../engine-client/src/arrow-ipc.ts';
import type { ArrowishConnection, ArrowishTable } from '../../../engine-client/src/duckdb.ts';
import type { PlannerWorker, Request, Response } from '../planner-protocol.ts';
import { hostCalls, type HostCalls } from './calls.ts';
import { base64, fromBase64 } from './web.ts';

export { hostCalls, type HostCalls };

/**
 * A planner worker whose messages the host answers, off the engine's thread, as planner-worker.ts answers them in a
 * tab. A failure of the call itself (the host's, not the planner's refusal, which comes back as an answer) is the
 * worker's death, as a crashed tab worker's is: every request in flight fails, and the planner starts another.
 */
export function hostWorker(calls: HostCalls): PlannerWorker {
  let alive = true;
  const worker: PlannerWorker = {
    onmessage: null,
    onerror: null,
    postMessage(message: Request): void {
      calls.planner(JSON.stringify(message)).then(
        (answer) => {
          if (alive) worker.onmessage?.({ data: JSON.parse(answer) as Response });
        },
        (e: unknown) => {
          if (!alive) return;
          alive = false;
          worker.onerror?.({ message: e instanceof Error ? e.message : String(e) });
        },
      );
    },
    terminate(): void {
      alive = false;
    },
  };
  return worker;
}

/** What a host's database gives beside its connection: DuckDB-WASM's file registration, the host's folder behind it. */
export interface HostDatabase {
  readonly connection: ArrowishConnection;
  registerFileBuffer(name: string, bytes: Uint8Array): Promise<void>;
  registerFileText(name: string, text: string): Promise<void>;
}

/**
 * The host's database as DataCube's engine takes one. Each query is one whole call (no `send`: the host's DuckDB
 * answers a statement at once, so a superseded query is not cancelled between batches); its Arrow IPC read by
 * apache-arrow, as DuckDB-WASM's answers are.
 */
export function hostDatabase(calls: HostCalls): HostDatabase {
  const connection: ArrowishConnection = {
    async query(sql: string): Promise<ArrowishTable> {
      return arrowIpcTable(fromBase64(await calls.query(sql)));
    },
  };
  return {
    connection,
    registerFileBuffer: (name, bytes) => calls.registerFile(name, base64(bytes)),
    registerFileText: (name, text) => calls.registerFile(name, base64(new TextEncoder().encode(text))),
  };
}
