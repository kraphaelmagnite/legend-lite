// Where each table's rows come from, in this tab (plan A2): every table a model's Databases declare, filled from the
// model's own test data (its Data elements, in the model's own database inside the tab's DuckDB: project-data.ts) or
// from a file the person dropped in for it (CSV or Parquet) -- which stays, whatever the model's test data says, until
// it is reset -- or from nowhere yet.

import type { QueryEngine } from './engine.ts';
import { databaseOf, databaseTables, dataTables, loadFileTable, seeded, type FileSink, type SeedSource } from './model-data.ts';
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

/** A person's file for a table: its name and bytes (put in again whenever the workspace's database is made). */
interface PutFile {
  readonly schema: string;
  readonly table: string;
  readonly name: string;
  readonly bytes: Uint8Array;
}

/**
 * The tables of the model a workspace runs (Studio's), in this tab: ONE database at a time, the workspace's own
 * (project-data.ts) -- its model's Data rows, and a person's file for a table put in over them. Whenever what fills it
 * changes -- a Data row or a table edited, an undo, a file reset -- it is dropped and made again from the model and the
 * person's files as they are now, so it can never hold anything else (the user, 2026-10-13: "blow away and recreate";
 * nothing about earlier databases is tracked). An edit that changes neither (a class) keeps it: it is named by what it
 * holds. A file put goes straight into the database there. The screens run on `runner`, pointed at it.
 */
export class TabTables {
  readonly #projects: ProjectDatabases;
  readonly #runner: ProjectRunner;
  readonly #host: QueryEngine;
  readonly #register: FileSink['registerFileBuffer'];
  readonly #source: SeedSource;
  /** The tables a person's file fills, by `schema.table`: the person's own state. */
  readonly #files = new Map<string, PutFile>();
  /** How many times the person's files changed; the database there now was made at `#current.files`. */
  #filesChanged = 0;
  /** The database there now: its name, and the files' count it was made with. */
  #current: { readonly name: string; readonly files: number } | undefined;
  #uploads = 0;

  /**
   * `host`: the tab's own connection (no model's database is ever it here); `register`: where a file's bytes go (the
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

  /** The workspace's database for the model as it is now, and the screens on it. */
  async load(model: Model): Promise<void> {
    await this.#open(model);
  }

  /**
   * `schema.table` filled from a person's file (a .csv or .parquet), until reset -- the table the one Database that
   * declares it makes (databaseOf: none, or two, is refused). A refused file leaves the table as it was.
   */
  async put(model: Model, schema: string, table: string, file: { readonly name: string; readonly bytes: Uint8Array }): Promise<void> {
    const { engine } = await this.#open(model);
    await this.#putFile(model, engine, schema, table, file);
    this.#files.set(key(schema, table), { schema, table, name: file.name, bytes: file.bytes });
    // the database there holds it already: it is as the files are now
    this.#current = { name: this.#current!.name, files: ++this.#filesChanged };
  }

  /** `schema.table` back to the model's own rows (none: no table): the database made again without the file. */
  async reset(model: Model, schema: string, table: string): Promise<void> {
    if (this.#files.delete(key(schema, table))) this.#filesChanged++;
    await this.#open(model);
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

  /**
   * The workspace's database for `model` as it is now: the one there when nothing that fills it changed, else made
   * from the model's Data rows and the person's files (they go in before any query reaches it), the screens pointed at
   * it, and the one before dropped -- after, so a run the screens began on it finishes there.
   */
  async #open(model: Model): Promise<ProjectEngine> {
    const made = async (engine: QueryEngine): Promise<void> => {
      for (const file of this.#files.values()) await this.#putFile(model, engine, file.schema, file.table, file);
    };
    const want = { own: true } as const;
    let project = await this.#projects.engineFor(model.text, this.#host, made, want);
    const name = project.name!;
    if (this.#current?.name === name && this.#current.files !== this.#filesChanged) {
      // the same model, the person's files changed (a reset): made again
      await this.#projects.drop(name);
      project = await this.#projects.engineFor(model.text, this.#host, made, want);
    }
    const before = this.#current?.name;
    this.#current = { name, files: this.#filesChanged };
    this.#runner.use(project.engine);
    if (before !== undefined && before !== name) await this.#projects.drop(before);
    return project;
  }

  /** A file put into `schema.table` on `engine`. */
  async #putFile(model: Model, engine: QueryEngine, schema: string, table: string,
    file: { readonly name: string; readonly bytes: Uint8Array }): Promise<void> {
    const database = databaseOf(model.elements, schema, table, 'a file fills');
    const target = databaseTables(model.elements).find((t) => t.database === database && t.schema === schema && t.table === table)!;
    const sink: FileSink = { registerFileBuffer: this.#register, run: (sql) => engine.run(sql, 0) };
    await loadFileTable(sink, this.#source, model.text, target, file, `upload-${++this.#uploads}`);
  }
}
