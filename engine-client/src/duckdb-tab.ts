// DuckDB in this tab, THE one start of it for every app that runs queries here (DataCube, Query, Studio, and the test
// pages that stand in for them): its bundle from the app's own vendor/ (never a CDN: a cross-origin worker is
// forbidden, and a deployment behind a firewall has none), and its extensions from there too (ownExtensions), one
// connection -- the query engine, its session set up as the planner's SQL assumes before anything uses it (engine.ts
// setUpSession) -- and the database itself, into which a model's test data is loaded (model-data.ts).

import * as duckdb from './duckdb-wasm.ts';
import { DuckDbEngine, type ArrowishConnection } from './duckdb.ts';
import { setUpSession } from './engine.ts';
import type { FileSink } from './model-data.ts';

export interface DuckDbInTab {
  readonly engine: DuckDbEngine;
  /** Where a model's test data and a person's files go: a file by name (text or bytes), and SQL on the engine's connection. */
  readonly data: FileSink;
  /** The database itself: what a host registers a person's file on, and lets go of it (DataCube's upload.ts). */
  readonly db: duckdb.AsyncDuckDB;
  /**
   * Another connection to the same database, set up as the first (its extensions from the site, its session the
   * planner's): what a project's own database is queried on (project-data.ts), its names pointed there alone.
   */
  connect(): Promise<DuckDbEngine>;
}

/**
 * The tab's DuckDB loads its extensions from `repository` -- the site's own (vendor/duckdb-extensions/, beside its
 * DuckDB files: //engine-client:duckdb_extensions, pinned in MODULE.bazel), in the layout DuckDB asks a repository for
 * -- never from extensions.duckdb.org. Before anything loads one: the session's time zone loads ICU (setUpSession).
 */
async function ownExtensions(engine: Pick<DuckDbEngine, 'run'>, repository: string): Promise<void> {
  const at = `'${repository.replace(/'/g, "''")}'`;
  await engine.run(`SET custom_extension_repository = ${at}`, 0);
  await engine.run(`SET autoinstall_extension_repository = ${at}`, 0);
}

/**
 * `vendor`: the URL of the folder holding duckdb-{mvp,eh}.wasm and their workers. `setup`: the planner's session setup
 * for a database's type (WasmGrammar's sessionSetup), run on the connection before it is handed out.
 */
export async function startDuckDbInTab(vendor: string,
  setup: (databaseType: string) => Promise<readonly string[]>): Promise<DuckDbInTab> {
  // absolute URLs: the worker resolves its module against its own location, not the page's
  const asset = (f: string): string => new URL(f, new URL(vendor, globalThis.location.href)).href;
  const bundle = await duckdb.selectBundle({
    mvp: { mainModule: asset('duckdb-mvp.wasm'), mainWorker: asset('duckdb-browser-mvp.worker.js') },
    eh: { mainModule: asset('duckdb-eh.wasm'), mainWorker: asset('duckdb-browser-eh.worker.js') },
  });
  const db = new duckdb.AsyncDuckDB(new duckdb.ConsoleLogger(duckdb.LogLevel.WARNING), new Worker(bundle.mainWorker!));
  await db.instantiate(bundle.mainModule, bundle.pthreadWorker);
  // every connection set up alike: a setting of DuckDB's may be a session's, so none is left to the first's
  const connect = async (): Promise<DuckDbEngine> => {
    const connection = new DuckDbEngine((await db.connect()) as unknown as ArrowishConnection);
    await ownExtensions(connection, asset('duckdb-extensions'));
    await setUpSession(connection, setup);
    return connection;
  };
  const engine = await connect();
  return {
    engine,
    data: {
      registerFileBuffer: (name, bytes) => db.registerFileBuffer(name, bytes),
      dropFile: async (name) => { await db.dropFile(name); },
      run: (sql) => engine.run(sql, 0),
    },
    db,
    connect,
  };
}
