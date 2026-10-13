// A model's own test data, in the tab's DuckDB (plan A2, the first source of rows): every relational Data element's
// tables (`relationalCSVData`: schema, table, CSV text), made and filled by the statements the server seeds a database
// with -- legend-lite's own (setup.CsvSeed, on the plan side), asked of the planner in the tab (a SeedSource) -- so a
// table here holds what the same table on the server holds, its column types the server's. This file says which tables
// a model's test data fills and which Database declares each, and runs the statements it is given.

import type { HostOperationsSource } from './host-operations.ts';

/** The model JSON parts read here (grammarToJson/model). */
interface ModelElement {
  readonly _type: string;
  readonly name?: string;
  readonly package?: string;
  readonly schemas?: readonly {
    readonly name: string;
    readonly tables: readonly { readonly name: string; readonly columns: readonly { readonly name: string }[] }[];
  }[];
  readonly data?: { readonly _type: string; readonly tables?: readonly { readonly schema: string; readonly table: string; readonly values: string }[] };
}

/** A table a model's Database declares: where it is, and its columns' names. */
export interface DeclaredTable {
  /** The Database's path. */
  readonly database: string;
  readonly schema: string;
  readonly table: string;
  readonly columns: readonly { readonly name: string }[];
}

/** One table of test data: the Database that declares it, where it goes, its CSV (header first), the Data element. */
export interface DataTable {
  readonly database: string;
  readonly schema: string;
  readonly table: string;
  readonly csv: string;
  readonly element: string;
}

/** A table asked for: where it is, and its CSV (header first; a header alone makes it empty). */
export interface SeedTable {
  readonly schema: string;
  readonly table: string;
  readonly csv: string;
}

/**
 * What the planner gives for one table: the server's statements that make and fill it, and the names that reach it
 * again as those statements spell them -- so this file spells no table or column name of the model's itself.
 */
export interface TableSeed {
  /** The schema, the table with the server's column types, its rows as one INSERT (none for a header alone). */
  readonly sql: readonly string[];
  /** The table's name in SQL (no schema for the `default` one, as the seed makes it). */
  readonly table: string;
  /** The statement that drops it. */
  readonly drop: string;
  /** Each declared column: its name as written (what a file's header says) and its name in SQL. */
  readonly columns: readonly { readonly name: string; readonly sql: string }[];
}

/** A table of test data with what the planner gave for it. */
export interface SeededTable extends DataTable {
  readonly seed: TableSeed;
}

/**
 * Where a model's test-data statements come from: legend-lite's planner in the tab (engine-client's
 * WasmGrammar.testDataSql, DataCube's LitePlanner.testDataSql): for each table `database` declares, its TableSeed, in
 * order. A table the Database does not declare is refused.
 */
export interface SeedSource {
  testDataSql(model: string, database: string, tables: readonly SeedTable[]): Promise<TableSeed[]>;
}

/** Every table the model's Databases declare (its JSON's elements), in the order declared. */
export function databaseTables(elements: readonly ModelElement[]): DeclaredTable[] {
  const out: DeclaredTable[] = [];
  for (const e of elements) {
    if (e._type !== 'relational') continue;
    for (const s of e.schemas ?? []) {
      for (const t of s.tables) {
        out.push({ database: `${e.package}::${e.name}`, schema: s.name, table: t.name, columns: t.columns.map((c) => ({ name: c.name })) });
      }
    }
  }
  return out;
}

/**
 * The one Database of the model that declares `schema.table` (its JSON's elements). None, or more than one, is refused:
 * which one would be a guess (the server is told, by the test that names its store; the tab has no test to ask).
 * `what` names whose table it is, for the refusal.
 */
export function databaseOf(elements: readonly ModelElement[], schema: string, table: string, what: string): string {
  const dbs = databaseTables(elements).filter((t) => t.schema === schema && t.table === table).map((t) => t.database);
  if (dbs.length === 0) throw new Error(`${what} ${schema}.${table}, which no Database of the model declares`);
  if (dbs.length > 1) throw new Error(`${what} ${schema}.${table}, which more than one Database declares (${dbs.join(', ')})`);
  return dbs[0]!;
}

/** The test data of a model (its JSON's elements): one entry per table of every relational Data element (databaseOf). */
export function dataTables(elements: readonly ModelElement[]): DataTable[] {
  const out: DataTable[] = [];
  for (const e of elements) {
    if (e._type !== 'dataElement' || e.data?._type !== 'relationalCSVData') continue;
    const element = `${e.package}::${e.name}`;
    for (const t of e.data.tables ?? []) {
      const database = databaseOf(elements, t.schema, t.table, `the Data element ${element} fills`);
      out.push({ database, schema: t.schema, table: t.table, csv: t.values, element });
    }
  }
  return out;
}

/** What the planner gives for each table: one ask per Database (the model compiled once for each). */
export async function seeded(source: SeedSource, model: string, tables: readonly DataTable[]): Promise<SeededTable[]> {
  const byDatabase = new Map<string, DataTable[]>();
  for (const t of tables) byDatabase.set(t.database, [...(byDatabase.get(t.database) ?? []), t]);
  const seeds = new Map<DataTable, TableSeed>();
  for (const [database, ts] of byDatabase) {
    const answers = await source.testDataSql(model, database, ts.map((t) => ({ schema: t.schema, table: t.table, csv: t.csv })));
    if (answers.length !== ts.length) throw new Error(`the planner answered ${answers.length} tables of test data for ${database}'s ${ts.length}`);
    ts.forEach((t, i) => seeds.set(t, answers[i]!));
  }
  return tables.map((t) => ({ ...t, seed: seeds.get(t)! }));
}

/** What loading needs of the tab's DuckDB: SQL. */
export interface DataSink {
  run(sql: string): Promise<unknown>;
}

/**
 * A sink that also takes a file's bytes: a person's own file (Parquet is binary). DuckDB-WASM takes the buffer it is
 * given (it hands it to its worker: the view is empty after), so the caller's bytes are copied before they go in; a
 * file registered for one read is let go of after it (`dropFile`, where the sink can).
 */
export interface FileSink extends DataSink {
  registerFileBuffer(name: string, bytes: Uint8Array): Promise<void>;
  dropFile?(name: string): Promise<void>;
}

/**
 * A table made from a person's own file (plan A2's second source), in one transaction: the table made empty by the
 * server's statements (a header alone: its column types the server's), then the file's rows put in -- each declared
 * column from the file's column of that name, a CSV's as text and a Parquet's as typed, DuckDB converting each value to
 * its column's type -- so a refused file (a column missing, a value its type cannot take) leaves the table as it was.
 * The file's name says which kind it is. It is registered as `<as>.csv` or `<as>.parquet`, never by the person's own
 * name: read_csv and read_parquet read a name as a glob (`sales[2024].csv` matches nothing). The table's names are the
 * planner's (`seed`); returned, to drop it by later.
 */
export async function loadFileTable(sink: FileSink, source: SeedSource & HostOperationsSource, databaseType: string, model: string,
  target: { readonly database: string; readonly schema: string; readonly table: string; readonly columns: readonly { readonly name: string }[] },
  file: { readonly name: string; readonly bytes: Uint8Array }, as: string): Promise<TableSeed> {
  const format = /\.csv$/i.test(file.name) ? 'csv' : /\.parquet$/i.test(file.name) ? 'parquet' : undefined;
  if (!format) throw new Error(`${file.name}: a table is filled from a .csv or a .parquet file`);
  const [made] = await seeded(source, model, [{
    database: target.database, schema: target.schema, table: target.table, element: file.name,
    csv: target.columns.map((c) => c.name).join(','),
  }]);
  const seed = made!.seed;
  const registered = `${as}.${format}`;
  // the transaction's steps and the rows put in: the planner's statements (D5), each declared column from the file's
  // column of its name -- a CSV's read as text, a Parquet's as typed, DuckDB converting each value to its column's type
  const [begin, insert, commit, rollback] = await source.hostOperations(databaseType, [
    { op: 'transaction', step: 'begin' },
    {
      op: 'insert', target: { schema: target.schema, name: target.table, declared: true },
      // the planner's own columns: each as declared, the name a file's header says (no store quotes)
      columns: seed.columns.map((c) => ({ target: c.name, from: c.name })),
      from: { file: { at: registered, format: format === 'csv' ? 'csvText' : 'parquet' } },
    },
    { op: 'transaction', step: 'commit' },
    { op: 'transaction', step: 'rollback' },
  ]);
  // a copy: the database takes the buffer it is given, and the caller keeps its bytes (Studio puts them in again)
  await sink.registerFileBuffer(registered, file.bytes.slice());
  try {
    for (const sql of begin!) await sink.run(sql);
    try {
      for (const sql of [...seed.sql, ...insert!, ...commit!]) await sink.run(sql);
    } catch (e) {
      try {
        for (const sql of rollback!) await sink.run(sql);
      } catch (r) {
        throw new Error(`${e instanceof Error ? e.message : String(e)} -- and putting the table back failed: ${r instanceof Error ? r.message : String(r)}`);
      }
      throw e;
    }
  } finally {
    // the rows are the table's now (or the table is as it was): the file is not read again -- and a drop that fails
    // neither fails a put that worked nor hides why a read did not (at worst the file stays registered)
    await sink.dropFile?.(registered).catch(() => undefined);
  }
  return seed;
}

/** Each table made and filled: the planner's statements for it, run in order. */
export async function loadDataTables(sink: DataSink, tables: readonly SeededTable[]): Promise<void> {
  for (const t of tables) {
    for (const sql of t.seed.sql) await sink.run(sql);
  }
}
