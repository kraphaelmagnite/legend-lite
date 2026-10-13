// Pointing a cube at object storage.
//
// These test the STATEMENTS, because that is the part with decisions
// in it: which extension is loaded, how a URL is escaped, whether a
// credential can escape into an error message. The page names the
// operations (remote.ts) and legend-lite's planner writes them (D5). Whether the bytes
// actually arrive is proved in the browser, in verify-remote.mjs -- the
// node build of duckdb-wasm does not make real HTTP requests (it
// answers a synthetic 404), so a node test claiming to have fetched
// something would be testing nothing.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { plannerOptions } from '#test-host';

import type { HostOperation } from '../../engine-client/src/host-operations.ts';
import type { ResultTable } from '../../engine-client/src/result.ts';
import { LitePlanner } from '../src/lite-planner.ts';
import {
  credentialOperations,
  credentialScope,
  inferFormat,
  mountOperations,
  mountRemote,
  redactSecrets,
  viewOperation,
  type RemoteOptions,
  type RemoteSource,
  type S3Credentials,
} from '../src/remote.ts';
import { FakeEngine } from './fake-engine.ts';

/** legend-lite's planner, which writes each operation's statements (D5): the module in-thread, or Python's library in
 *  the headless lane -- the statements under test are the platform's, as every host runs them. */
const planner = new LitePlanner({ model: '', runtime: '', ...plannerOptions(), cache: false });
const statements = async (operations: readonly HostOperation[]): Promise<string[]> =>
  (await planner.hostOperations('DuckDB', operations)).flat();
const viewStatement = async (source: RemoteSource): Promise<string> => (await statements([viewOperation(source)]))[0]!;
/** A view's scan: what it reads its rows from. */
const scanExpression = async (source: RemoteSource): Promise<string> =>
  (await viewStatement(source)).replace(/^.* AS SELECT \* FROM /, '');
const credentialStatements = (creds: S3Credentials | undefined, source: { readonly name: string; readonly url: string }): Promise<string[]> =>
  statements(credentialOperations(creds, source));
const mountStatements = (options: RemoteOptions): Promise<string[]> => statements(mountOperations(options));

const empty = (epoch: number): ResultTable => ({
  columns: [],
  rowCount: 0,
  epoch,
  elapsedMs: 0,
});

class RecordingEngine extends FakeEngine {
  readonly name = 'recording';
  readonly sql: string[] = [];
  failOn?: RegExp;
  async answer(sql: string, epoch: number): Promise<ResultTable> {
    this.sql.push(sql);
    if (this.failOn?.test(sql)) throw new Error(`boom running ${sql}`);
    return empty(epoch);
  }
}

describe('choosing a format', () => {
  it('reads the extension when there is one', () => {
    assert.equal(inferFormat('https://x/y.parquet'), 'parquet');
    assert.equal(inferFormat('https://x/y.csv'), 'csv');
    assert.equal(inferFormat('https://x/y.csv.gz'), 'csv');
  });

  it('ignores a query string when guessing', () => {
    // A presigned URL carries its signature as a query string, and it
    // is the common case for object storage.
    assert.equal(
      inferFormat('https://b.s3.amazonaws.com/t.csv?X-Amz-Signature=abc'),
      'csv',
    );
  });

  it('recognises an Iceberg table by shape, not extension', () => {
    // An Iceberg table is a DIRECTORY, so there is no extension to
    // read.
    assert.equal(inferFormat('s3://bucket/table/metadata'), 'iceberg');
    assert.equal(inferFormat('s3://bucket/table#iceberg'), 'iceberg');
  });

  it('defaults to parquet', () => {
    assert.equal(inferFormat('s3://bucket/table'), 'parquet');
  });
});

describe('the scan expression', () => {
  it('uses the right reader per format', async () => {
    assert.match(
      await scanExpression({ name: 't', url: 'https://x/y.parquet' }),
      /^read_parquet\('https:\/\/x\/y\.parquet'\)$/,
    );
    assert.match(
      await scanExpression({ name: 't', url: 'https://x/y.csv' }),
      /^read_csv_auto\(/,
    );
    assert.match(
      await scanExpression({ name: 't', url: 's3://b/t', format: 'iceberg' }),
      /^iceberg_scan\('s3:\/\/b\/t'\)$/,
    );
  });

  it('strips the iceberg marker from the URL it passes on', async () => {
    assert.equal(
      await scanExpression({ name: 't', url: 's3://b/t#iceberg' }),
      "iceberg_scan('s3://b/t')",
    );
  });

  it('escapes a quote in the URL by doubling it', async () => {
    // SQL doubles the quote; it does NOT use a backslash. The same
    // class of bug as the Pure escaping already fixed here.
    assert.match(
      await scanExpression({ name: 't', url: "s3://b/it's.parquet" }),
      /'s3:\/\/b\/it''s\.parquet'/,
    );
  });
});

describe('the view that hides the remoteness', () => {
  it('takes the name the model already uses', async () => {
    // The whole design: the planner keeps emitting SELECT ... FROM
    // TRADES and never learns the data is somewhere else.
    assert.equal(
      await viewStatement({ name: 'TRADES', url: 'https://x/y.parquet' }),
      'CREATE OR REPLACE VIEW "TRADES" AS SELECT * FROM'
      + " read_parquet('https://x/y.parquet')",
    );
  });

  it('quotes a name that needs it', async () => {
    const s = await viewStatement({ name: 'with space', url: 'https://x/y.parquet' });
    assert.match(s, /VIEW "with space"/);
  });

  it('replaces rather than failing on a remount', async () => {
    // Pointing at yesterday's file and back is a normal thing to do.
    assert.match(
      await viewStatement({ name: 'T', url: 'https://x/y.parquet' }),
      /CREATE OR REPLACE VIEW/,
    );
  });
});

describe('credentials', () => {
  const source = { name: 'trades', url: 's3://bucket/trades/2024.parquet' };

  it('writes nothing when there are none', async () => {
    assert.deepEqual(await credentialStatements(undefined, source), []);
    assert.deepEqual(await credentialStatements({}, source), []);
  });

  it('writes one secret per source, scoped to its own path, rather than global settings', async () => {
    const [stmt] = await credentialStatements({
      accessKeyId: 'AKIA',
      secretAccessKey: 'shh',
      region: 'eu-west-2',
    }, source);
    assert.match(stmt ?? '', /^CREATE OR REPLACE SECRET "datacube_s3_trades" \(/);
    assert.match(stmt ?? '', /SCOPE 's3:\/\/bucket\/trades\/2024\.parquet'/);
    assert.match(stmt ?? '', /TYPE S3/);
    assert.match(stmt ?? '', /KEY_ID 'AKIA'/);
    assert.match(stmt ?? '', /REGION 'eu-west-2'/);
  });

  it('carries the settings an S3-compatible endpoint needs', async () => {
    const [stmt] = await credentialStatements({
      endpoint: 'minio:9000',
      urlStyle: 'path',
      useSsl: false,
    }, source);
    assert.match(stmt ?? '', /ENDPOINT 'minio:9000'/);
    assert.match(stmt ?? '', /URL_STYLE 'path'/);
    assert.match(stmt ?? '', /USE_SSL false/);
  });

  it('escapes a credential containing a quote', async () => {
    const [stmt] = await credentialStatements({ secretAccessKey: "a'b" }, source);
    assert.match(stmt ?? '', /SECRET 'a''b'/);
  });

  it('scopes an Iceberg table to its table, and a glob to the path before it', () => {
    assert.equal(credentialScope('s3://b/warehouse/orders/metadata#iceberg'), 's3://b/warehouse/orders');
    assert.equal(credentialScope('s3://b/trades/*.parquet'), 's3://b/trades/');
    assert.equal(credentialScope('https://host/x.csv'), 'https://host/x.csv');
  });

  it('gives two sources two secrets: one page\'s keys never replace another\'s', async () => {
    const secrets = (await mountStatements({
      sources: [{ name: 'a', url: 's3://one/a.parquet' }, { name: 'b', url: 's3://two/b.parquet' }],
      s3: { accessKeyId: 'AKIA', secretAccessKey: 'shh' },
    })).filter((s) => s.startsWith('CREATE OR REPLACE SECRET'));
    assert.equal(secrets.length, 2);
    assert.match(secrets[0]!, /"datacube_s3_a".*SCOPE 's3:\/\/one\/a\.parquet'/);
    assert.match(secrets[1]!, /"datacube_s3_b".*SCOPE 's3:\/\/two\/b\.parquet'/);
  });
});

describe('keeping secrets out of messages', () => {
  const creds = {
    accessKeyId: 'AKIAEXAMPLE',
    secretAccessKey: 'super-secret-value',
    sessionToken: 'tok-123',
  };

  it('redacts every credential by value', () => {
    const text = 'failed: KEY_ID AKIAEXAMPLE SECRET super-secret-value'
      + ' SESSION_TOKEN tok-123';
    const out = redactSecrets(text, creds);
    assert.ok(!out.includes('super-secret-value'), out);
    assert.ok(!out.includes('AKIAEXAMPLE'), out);
    assert.ok(!out.includes('tok-123'), out);
    assert.match(out, /\*\*\*/);
  });

  it('leaves text alone when there are no credentials', () => {
    assert.equal(redactSecrets('plain', undefined), 'plain');
  });

  it('does not leak a secret through a failed mount', async () => {
    // QueryError carries the SQL that failed, so without this a live
    // access key reaches an error banner and whatever collects it.
    const engine = new RecordingEngine();
    engine.failOn = /SECRET/;
    let message = '';
    try {
      await mountRemote(engine, planner, {
        sources: [{ name: 'T', url: 'https://x/y.parquet' }],
        s3: creds,
      });
    } catch (e) {
      message = e instanceof Error ? e.message : String(e);
    }
    assert.ok(message.length > 0, 'it did fail');
    assert.ok(!message.includes('super-secret-value'), message);
    assert.ok(!message.includes('AKIAEXAMPLE'), message);
  });
});

describe('the mount sequence', () => {
  it('loads httpfs before anything references a URL', async () => {
    const stmts = await mountStatements({
      sources: [{ name: 'T', url: 'https://x/y.parquet' }],
    });
    assert.equal(stmts[0], 'INSTALL httpfs');
    assert.equal(stmts[1], 'LOAD httpfs');
    assert.match(stmts[stmts.length - 1] ?? '', /CREATE OR REPLACE VIEW/);
  });

  it('loads iceberg only when an iceberg source is present', async () => {
    const without = await mountStatements({
      sources: [{ name: 'T', url: 'https://x/y.parquet' }],
    });
    assert.ok(!without.some((s) => /iceberg/.test(s)), without.join('; '));

    const with_ = await mountStatements({
      sources: [{ name: 'T', url: 's3://b/t', format: 'iceberg' }],
    });
    assert.ok(with_.includes('LOAD iceberg'));
  });

  it('creates the secret before the views that need it', async () => {
    const stmts = await mountStatements({
      sources: [{ name: 'T', url: 's3://b/y.parquet' }],
      s3: { accessKeyId: 'a', secretAccessKey: 'b' },
    });
    const secret = stmts.findIndex((s) => s.startsWith('CREATE OR REPLACE SECRET'));
    const view = stmts.findIndex((s) => s.includes('VIEW'));
    assert.ok(secret >= 0 && view > secret, stmts.join('; '));
  });

  it('mounts several sources in one pass', async () => {
    const engine = new RecordingEngine();
    await mountRemote(engine, planner, {
      sources: [
        { name: 'TRADES', url: 'https://x/trades.parquet' },
        { name: 'BOOKS', url: 'https://x/books.csv' },
      ],
    });
    assert.ok(engine.sql.some((s) => /VIEW "TRADES"/.test(s)));
    assert.ok(engine.sql.some((s) => /VIEW "BOOKS"/.test(s)));
    assert.ok(engine.sql.some((s) => /read_csv_auto/.test(s)));
  });
});
