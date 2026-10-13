// Reading a cube's data from object storage.
//
// The engine already ships what this needs: the duckdb-wasm binary
// has httpfs, parquet and iceberg compiled in, and `CREATE SECRET
// (TYPE S3, ...)`, `SET s3_*` and `CREATE OR REPLACE VIEW` all work.
// What was missing was a way to point a cube at a URL.
//
// THE REMOTE FILE BECOMES A VIEW under the name the model already
// uses. That is the whole design, and it is what keeps this from
// being a second plane: `#>{trades::DB.TRADES}#` still compiles to
// `SELECT ... FROM TRADES`, the planner is untouched, the tree
// assembly is untouched, and DuckDB resolves TRADES to a scan of a
// remote Parquet instead of a local table. Only the connection
// differs -- literally, and not as a figure of speech.
//
// It also means predicate and projection pushdown come free: DuckDB
// reads Parquet row-group statistics over HTTP range requests, so a
// filtered cube pulls the bytes it needs rather than the file.
//
// WHAT IS NOT HERE. Delta is absent on purpose: `delta_scan` exists
// as a symbol but the extension is not bundled, so it tries to
// autoload and fails. Offering it would be offering an action that
// cannot work -- the same rule the menu follows elsewhere.

import type { QueryEngine } from '../../engine-client/src/engine.ts';
import type { HostOperation, HostOperationsSource } from '../../engine-client/src/host-operations.ts';

export type RemoteFormat = 'parquet' | 'csv' | 'iceberg';

export interface RemoteSource {
  /**
   * The name the Pure model refers to.
   *
   * This is the point of the whole module: the view takes the table's
   * name, so nothing above the connection has to know the data is
   * remote.
   */
  readonly name: string;
  /** https://…, s3://…, or any URL httpfs understands. */
  readonly url: string;
  /** Inferred from the URL when omitted. */
  readonly format?: RemoteFormat;
}

export interface S3Credentials {
  readonly region?: string;
  readonly endpoint?: string;
  readonly accessKeyId?: string;
  readonly secretAccessKey?: string;
  readonly sessionToken?: string;
  /** MinIO and most S3-compatibles need 'path'. */
  readonly urlStyle?: 'vhost' | 'path';
  readonly useSsl?: boolean;
}

export interface RemoteOptions {
  readonly sources: readonly RemoteSource[];
  readonly s3?: S3Credentials;
}

/** Guess the format from the URL, so the common case needs no field. */
export function inferFormat(url: string): RemoteFormat {
  const path = url.split('?')[0] ?? url;
  if (/\.csv(\.gz)?$/i.test(path)) return 'csv';
  // An Iceberg table is a DIRECTORY with metadata in it, not a file,
  // so it is recognised by shape rather than extension.
  if (/\/metadata\/?$/i.test(path) || /#iceberg$/i.test(url)) return 'iceberg';
  return 'parquet';
}

/** The URL a source's data is read from: as given, but an Iceberg table's without the mark that names it one. */
export function scanUrl(url: string): string {
  return url.replace(/#iceberg$/i, '');
}

/** The view a source is read through: its name the model's table, its rows the remote file's, read by its format. */
export function viewOperation(source: RemoteSource): HostOperation {
  return {
    op: 'relation', kind: 'view', target: { name: source.name }, replace: true,
    from: { remote: { url: scanUrl(source.url), format: source.format ?? inferFormat(source.url) } },
  };
}

/**
 * Where a source's keys apply: its own path, as a secret's SCOPE (a prefix of the URLs it is used for) -- the URL
 * without an Iceberg mark, cut before a glob, and an Iceberg table's metadata folder its table's (its data files lie
 * beside it).
 */
export function credentialScope(url: string): string {
  let scope = scanUrl(url);
  const glob = scope.search(/[*?[]/);
  if (glob >= 0) scope = scope.slice(0, glob);
  return scope.replace(/\/metadata\/?$/i, '');
}

/**
 * The secret one source's credentials are read with.
 *
 * A scoped, replaceable secret rather than process-wide settings, which leak between sources when a cube reads two
 * buckets. One secret per source, named by it and scoped to its own path: where one database serves several pages
 * (Python's), one page's keys never replace another's, nor reach another's files. None without credentials.
 */
export function credentialOperations(
  creds: S3Credentials | undefined,
  source: { readonly name: string; readonly url: string },
): HostOperation[] {
  if (!creds) return [];
  const credentials: Record<string, string> = {};
  if (creds.accessKeyId) credentials.KEY_ID = creds.accessKeyId;
  if (creds.secretAccessKey) credentials.SECRET = creds.secretAccessKey;
  if (creds.sessionToken) credentials.SESSION_TOKEN = creds.sessionToken;
  if (creds.region) credentials.REGION = creds.region;
  if (creds.endpoint) credentials.ENDPOINT = creds.endpoint;
  if (creds.urlStyle) credentials.URL_STYLE = creds.urlStyle;
  if (creds.useSsl !== undefined) credentials.USE_SSL = creds.useSsl ? 'true' : 'false';
  if (Object.keys(credentials).length === 0) return [];
  return [{ op: 'secret', name: `datacube_s3_${source.name}`, scope: credentialScope(source.url), credentials }];
}

/**
 * Remove credentials from text before it can be shown or logged.
 *
 * QueryError carries the SQL that failed, and a failing CREATE SECRET
 * would otherwise put a live access key into an error banner, a
 * console line and whatever collects them. The values are redacted
 * by VALUE rather than by parsing the statement, so a secret survives
 * no matter which statement it turns up in.
 */
export function redactSecrets(
  text: string,
  creds: S3Credentials | undefined,
): string {
  if (!creds) return text;
  let out = text;
  for (const secret of [
    creds.secretAccessKey,
    creds.sessionToken,
    creds.accessKeyId,
  ]) {
    if (secret && secret.length > 0) {
      out = out.split(secret).join('***');
    }
  }
  return out;
}

/** Extensions a format needs, loaded before anything references it: httpfs, and iceberg for an Iceberg table. */
export function extensionOperation(sources: readonly RemoteSource[]): HostOperation {
  const formats = new Set(sources.map((s) => s.format ?? inferFormat(s.url)));
  return { op: 'extensions', names: ['httpfs', ...(formats.has('iceberg') ? ['iceberg'] : [])] };
}

/**
 * The extensions and credentials the sources are read with, before any view of them: what a host that checks what a
 * source names runs first (Python's, for an Iceberg table, whose metadata names its files by path: R4).
 */
export function accessOperations(options: RemoteOptions): HostOperation[] {
  return [extensionOperation(options.sources), ...options.sources.flatMap((source) => credentialOperations(options.s3, source))];
}

/** Every operation, in the order it has to run. */
export function mountOperations(options: RemoteOptions): HostOperation[] {
  return [...accessOperations(options), ...options.sources.map(viewOperation)];
}

/**
 * Point the engine's connection at remote data: the operations' statements as the planner writes them for the
 * engine's database (`host`), run in order.
 *
 * Runs before the first query. Failures are rethrown with the
 * credentials stripped, and name the source rather than the
 * statement, because "could not mount TRADES" is actionable where a
 * redacted CREATE SECRET is not.
 */
export async function mountRemote(
  engine: QueryEngine & { readonly databaseType: string },
  host: HostOperationsSource,
  options: RemoteOptions,
): Promise<void> {
  await runRedacted(engine, host, mountOperations(options), options.s3);
}

/** The engine's connection given the sources' extensions and credentials, no view yet (`accessOperations`). */
export async function accessRemote(engine: QueryEngine & { readonly databaseType: string }, host: HostOperationsSource,
  options: RemoteOptions): Promise<void> {
  await runRedacted(engine, host, accessOperations(options), options.s3);
}

async function runRedacted(engine: QueryEngine & { readonly databaseType: string }, host: HostOperationsSource,
  operations: readonly HostOperation[], s3: S3Credentials | undefined): Promise<void> {
  const redacted = (cause: unknown): Error =>
    new Error(`could not mount remote data: ${redactSecrets(cause instanceof Error ? cause.message : String(cause), s3)}`);
  let statements: string[][];
  try {
    statements = await host.hostOperations(engine.databaseType, operations);
  } catch (cause) {
    throw redacted(cause);
  }
  for (const statement of statements.flat()) {
    try {
      await engine.run(statement, 0);
    } catch (cause) {
      throw redacted(cause);
    }
  }
}
