// DATA IN, HEADLESS (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §4.2, §4.3; step 2b): a file read into the host's
// database by DataCube's own code -- upload.ts's readFileInto, the tab's -- so Python and the tab take a file the same
// way: DuckDB reading the file's own bytes, a table of its own, the model legend-lite's writer writes from what the
// catalog found, and the rebuild that table then applies (its conversions). Python has written the file's bytes where
// its DuckDB reads them, and hands in only that path, the file's name and the table Python chose: the bytes never cross
// into the engine, and the SQL is DataCube's, never the page's (R4).

import { DuckDbEngine } from '../../../engine-client/src/duckdb.ts';
import { LitePlanner } from '../lite-planner.ts';
import { accessRemote, inferFormat, scanUrl, type S3Credentials } from '../remote.ts';
import { dropFileTable, mountRemoteView, readFileInto } from '../upload.ts';
import { hostCalls, hostDatabase, hostWorker } from './host.ts';

/** A file as Python hands it in: where its DuckDB reads it, its name as picked (its format), the table to read it into. */
export interface IngestSpec {
  readonly path: string;
  readonly name: string;
  readonly table: string;
}

/** The host's database and legend-lite's model writer over the host's planner, for one call. */
async function using<T>(use: (o: { engine: DuckDbEngine; writer: LitePlanner }) => Promise<T>): Promise<T> {
  const calls = hostCalls();
  // the model writer needs no model of its own: it writes one (the worker's tableModel)
  const writer = new LitePlanner({ model: '', runtime: '', worker: () => hostWorker(calls) });
  try {
    return await use({ engine: new DuckDbEngine(hostDatabase(calls).connection), writer });
  } finally {
    writer.dispose();
  }
}

/** The file read into its table: what arrived, as upload.ts says it (its model, runtime, source and row count), JSON. */
export async function ingest(spec: string): Promise<string> {
  const s = JSON.parse(spec) as IngestSpec;
  return using(async ({ engine, writer }) =>
    JSON.stringify(await readFileInto(engine, { at: s.path, fileName: s.name, table: s.table }, writer)));
}

/** A remote file mounted as the view Python named, through the httpfs extension Python's DuckDB carries: its model,
 *  as the tab's mount says it, JSON. Python checked its URL is remote before anything ran. */
export async function mount(spec: string): Promise<string> {
  const s = JSON.parse(spec) as { readonly url: string; readonly name: string; readonly s3?: S3Credentials };
  return using(async ({ engine, writer }) =>
    JSON.stringify(await mountRemoteView(engine, writer, { url: s.url, name: s.name, ...(s.s3 ? { s3: s.s3 } : {}) })));
}

/** A remote file's extensions and keys put in place, no view yet (remote.ts `accessOperations`), and what it is read as
 *  and from where, JSON (`{"format", "url"}`): an Iceberg table's URL is its table's, whose metadata names every file
 *  it reads by path, which Python checks are remote too before it mounts the table (R4). */
export async function remoteAccess(spec: string): Promise<string> {
  const s = JSON.parse(spec) as { readonly url: string; readonly name: string; readonly s3?: S3Credentials };
  return using(async ({ engine, writer }) => {
    await accessRemote(engine, writer, { sources: [{ name: s.name, url: s.url }], ...(s.s3 ? { s3: s.s3 } : {}) });
    return JSON.stringify({ format: inferFormat(s.url), url: scanUrl(s.url) });
  });
}

/** A file's table dropped (its last grid gone, or an open overtaken): the name is free again. */
export async function forget(spec: string): Promise<void> {
  const s = JSON.parse(spec) as { readonly table: string };
  await using(async ({ engine, writer }) => dropFileTable(engine, writer, s.table));
}
