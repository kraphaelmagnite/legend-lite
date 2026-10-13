// Where each table's rows come from, in this tab (plan A2): every table a model's Databases declare, filled from the
// model's own test data (its Data elements, in the model's own database inside the tab's DuckDB: project-data.ts) or
// from a file the person dropped in for it (CSV or Parquet) -- which stays, whatever the model's test data says, until
// it is reset -- or from nowhere yet.

import type { QueryEngine } from './engine.ts';
import { databaseOf, databaseTables, dataTables, loadDataTables, loadFileTable, seeded, type FileSink, type SeedSource } from './model-data.ts';
import type { ProjectDatabases, ProjectEngine, ProjectRunner } from './project-data.ts';

type Elements = Parameters<typeof dataTables>[0];

/** A model: its text (what the planner compiles) and its JSON's elements (what this file reads). */
export interface Model {
  readonly text: string;
  readonly elements: Elements;
}

/** Where a table's rows are from: a Data element of the model, a person's file, or nothing yet. */
export type TableSource =
  | { readonly kind: 'test'; readonly element: string }
  | { readonly kind: 'file'; readonly name: string }
  | { readonly kind: 'none' };

export interface TableState {
  readonly database: string;
  readonly schema: string;
  readonly table: string;
  readonly columns: readonly { readonly name: string }[];
  readonly source: TableSource;
  /** How many rows it has; undefined when there is no table yet. */
  readonly rows: number | undefined;
}

const key = (schema: string, table: string): string => `${schema}.${table}`;

/** A person's file for a table: its name and bytes (put in again when the model's database is made afresh). */
interface PutFile {
  readonly schema: string;
  readonly table: string;
  readonly name: string;
  readonly bytes: Uint8Array;
  /** The planner's statement that drops the table it made. */
  readonly drop: string;
}

/** The tab's own connection, as `#held` names it (a model with no Data rows runs there). */
const HOST = '';

/**
 * The tables of the model a workspace runs (Studio's), in this tab: the model's own database (project-data.ts, named
 * by what it holds: an edit to a Data row or a table is a database of its own, and an undo finds the one before), its
 * Data rows there, and a person's file for a table put in over them; or, a model with no Data rows, the tab's own
 * connection. Every database is brought up to date with the person's files each time it is opened -- each file it
 * lacks put in, each one reset since taken out and its table refilled from its Data element -- and when it is made,
 * before any query reaches it (Studio's review: a database kept is one a workspace comes back to). The screens run on
 * `runner`, pointed at the model's database as it loads.
 */
export class TabTables {
  readonly #projects: ProjectDatabases;
  readonly #runner: ProjectRunner;
  readonly #host: QueryEngine;
  readonly #register: FileSink['registerFileBuffer'];
  readonly #source: SeedSource;
  /** The tables a person's file fills now, by `schema.table`. */
  readonly #files = new Map<string, PutFile>();
  /** What each database holds of them, by its name (HOST for the tab's own connection): the file put there, by table. */
  readonly #held = new Map<string, Map<string, PutFile>>();
  #uploads = 0;

  /**
   * `host`: the tab's own connection (a model with no Data rows runs there); `register`: where a file's bytes go (the
   * tab's database); `source`: the planner's statements for a table a file fills.
   */
  constructor(projects: ProjectDatabases, runner: ProjectRunner, host: QueryEngine, register: FileSink['registerFileBuffer'],
    source: SeedSource) {
    this.#projects = projects;
    this.#runner = runner;
    this.#host = host;
    this.#register = register;
    this.#source = source;
  }

  /** The model's database, made and filled the first time, up to date with the person's files, and the screens on it. */
  async load(model: Model): Promise<void> {
    const project = await this.#open(model);
    this.#runner.use(project.engine);
  }

  /**
   * `schema.table` filled from a person's file (a .csv or .parquet), until reset -- the table the one Database that
   * declares it makes (databaseOf: none, or two, is refused). A refused file leaves the table as it was.
   */
  async put(model: Model, schema: string, table: string, file: { readonly name: string; readonly bytes: Uint8Array }): Promise<void> {
    const project = await this.#open(model);
    const drop = await this.#putFile(model, project.engine, schema, table, file);
    const put: PutFile = { schema, table, name: file.name, bytes: file.bytes, drop };
    this.#files.set(key(schema, table), put);
    this.#heldBy(project.name ?? HOST).set(key(schema, table), put);
  }

  /** `schema.table` back to the model's own rows -- its Data element's, by the planner's statements -- or, none, no table. */
  async reset(model: Model, schema: string, table: string): Promise<void> {
    this.#files.delete(key(schema, table));
    const project = await this.#open(model);
    this.#runner.use(project.engine);
  }

  /** Every table the model declares: where its rows are from, and how many there are (counted by the planner's name). */
  async tables(model: Model): Promise<TableState[]> {
    const { engine } = await this.#open(model);
    const declared = databaseTables(model.elements);
    const tests = new Map(dataTables(model.elements).map((t) => [key(t.schema, t.table), t.element]));
    const named = await seeded(this.#source, model.text, declared.map((t) => ({
      database: t.database, schema: t.schema, table: t.table, csv: t.columns.map((c) => c.name).join(','), element: '',
    })));
    const out: TableState[] = [];
    for (const [i, t] of declared.entries()) {
      const file = this.#files.get(key(t.schema, t.table));
      const element = tests.get(key(t.schema, t.table));
      const source: TableSource = file !== undefined ? { kind: 'file', name: file.name } : element !== undefined ? { kind: 'test', element } : { kind: 'none' };
      let rows: number | undefined;
      try {
        rows = Number((await engine.run(`SELECT COUNT(*) AS n FROM ${named[i]!.seed.table}`, 0)).columns[0]!.values[0]);
      } catch {
        rows = undefined;     // not made yet: nothing fills it
      }
      out.push({ ...t, source, rows });
    }
    return out;
  }

  /** The model's database (made and brought up to date as it is made, before any query), brought up to date again. */
  async #open(model: Model): Promise<ProjectEngine> {
    const project = await this.#projects.engineFor(model.text, this.#host, (engine, name) => this.#sync(model, engine, name));
    await this.#sync(model, project.engine, project.name ?? HOST);
    return project;
  }

  /**
   * `name`'s database up to date with the person's files: each it lacks, or holds an earlier file of, put in; each it
   * holds that was reset since taken out, and its table refilled from its Data element (a project's database; the
   * tab's own connection holds no Data rows).
   */
  async #sync(model: Model, engine: QueryEngine, name: string): Promise<void> {
    const held = this.#heldBy(name);
    for (const [k, file] of this.#files) {
      if (held.get(k) === file) continue;
      await this.#putFile(model, engine, file.schema, file.table, file);
      held.set(k, file);
    }
    for (const [k, had] of [...held]) {
      if (this.#files.has(k)) continue;
      await engine.run(had.drop, 0);
      held.delete(k);
      const own = dataTables(model.elements).filter((t) => t.schema === had.schema && t.table === had.table);
      if (name !== HOST && own.length > 0) {
        await loadDataTables({ run: (sql) => engine.run(sql, 0) }, await seeded(this.#source, model.text, own));
      }
    }
  }

  #heldBy(name: string): Map<string, PutFile> {
    let held = this.#held.get(name);
    if (!held) {
      held = new Map();
      this.#held.set(name, held);
    }
    return held;
  }

  /** A file put into `schema.table` on `engine`; answers the planner's statement that drops the table. */
  async #putFile(model: Model, engine: QueryEngine, schema: string, table: string,
    file: { readonly name: string; readonly bytes: Uint8Array }): Promise<string> {
    const database = databaseOf(model.elements, schema, table, 'a file fills');
    const target = databaseTables(model.elements).find((t) => t.database === database && t.schema === schema && t.table === table)!;
    const sink: FileSink = { registerFileBuffer: this.#register, run: (sql) => engine.run(sql, 0) };
    return (await loadFileTable(sink, this.#source, model.text, target, file, `upload-${++this.#uploads}`)).drop;
  }
}
