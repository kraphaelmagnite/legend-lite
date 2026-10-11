// An Arrow IPC stream read as a DuckDB connection's answer: apache-arrow, the reader DuckDB-WASM's own answers come
// from. For a connection whose database answers in IPC -- Python's DuckDB under DataCube's headless host
// (datacube/src/headless/host.ts; docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §3). A module of its own, so a
// page that never reads IPC this way imports nothing more.

import { tableFromIPC } from 'apache-arrow';

import type { ArrowishTable } from './duckdb.ts';

/** One Arrow IPC stream (the stream format) as the table a connection's `query` answers. */
export function arrowIpcTable(stream: Uint8Array): ArrowishTable {
  return tableFromIPC(stream) as unknown as ArrowishTable;
}
