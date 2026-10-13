// A PROJECT'S OWN DATABASE in this tab (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5): where a project version's Data
// rows live -- a database of their own inside the tab's DuckDB, its schemas as the model declares them, never beside a
// person's own tables. What it is, and every statement that makes, points at, fills and drops it, is the planner's
// (Boundary.projectData, the platform's ProjectData in Java, by the tab's database type); this tab spells none.

import type { QueryEngine } from './engine.ts';

/** The planner's answer for one project's database: its name, and the statements of each step, in order. */
export interface ProjectDataStatements {
  /** Its name, from the identity it is kept by. */
  readonly name: string;
  /** Make it, empty (on any connection). */
  readonly open: readonly string[];
  /** Point a connection of its own at it. */
  readonly use: readonly string[];
  /** Make and fill every table a relational Data element fills (on that connection). */
  readonly fill: readonly string[];
  /** Drop it, with everything in it (on any connection). */
  readonly close: readonly string[];
}

/** Where a project's database comes from: legend-lite's planner (WasmGrammar.projectData, DataCube's LitePlanner). */
export interface ProjectDataSource {
  /** `identity`: a project version's coordinates; '' for the model's text (a workspace whose text changes). */
  projectData(model: string, identity: string, databaseType: string): Promise<ProjectDataStatements>;
}


/** What a project's database needs of the tab's DuckDB: its type, statements on any connection, and connections of its own. */
export interface ProjectHost {
  /** The tab's database type, as the planner names it ('DuckDB'). */
  readonly databaseType: string;
  /** Run a statement on the host's own connection (a project's database is made and dropped there). */
  run(sql: string): Promise<unknown>;
  /** A connection of its own, set up as the host's (duckdb-tab.ts `connect`). */
  connect(): Promise<QueryEngine>;
}

/** A project's database, open: its connection, and what keeps it. */
interface Open {
  readonly statements: ProjectDataStatements;
  readonly engine: QueryEngine;
  busy: number;
  used: number;
  idle: ReturnType<typeof setTimeout> | undefined;
}

/** What `engineFor` answers: the engine whose queries read the project's rows, and whether it was filled just now. */
export interface ProjectEngine {
  readonly engine: QueryEngine;
  /** Made and filled by this call (a person's files for its tables are put in again: tab-data.ts). */
  readonly fresh: boolean;
  /** Its name; none when the model has no Data rows and its queries run on the host's own connection. */
  readonly name: string | undefined;
}

/**
 * The tab's project databases: each made and filled the first time a query over its model asks, on a connection of
 * its own; kept while in use, and dropped when it has sat idle `idleMs`, or when more than `most` are open and it is
 * the least recently used idle one -- it is rebuilt from the model the next time, so dropping early is never wrong. A
 * model with no Data rows has none: its queries run on the host's own connection (a demo seeded there).
 */
export class ProjectDatabases {
  readonly #host: ProjectHost;
  readonly #source: ProjectDataSource;
  readonly #idleMs: number;
  readonly #most: number;
  readonly #open = new Map<string, Promise<Open>>();
  #clock = 0;

  constructor(host: ProjectHost, source: ProjectDataSource, options: { readonly idleMs?: number; readonly most?: number } = {}) {
    this.#host = host;
    this.#source = source;
    this.#idleMs = options.idleMs ?? 15 * 60_000;
    this.#most = options.most ?? 8;
  }

  /** The project databases open now, by name (for a test, and for a person asking what the tab holds). */
  get names(): readonly string[] {
    return [...this.#open.keys()];
  }

  /** `model`'s project database (kept by `identity`; '' for the model's text): made and filled the first time. */
  async engineFor(model: string, identity: string, hostEngine: QueryEngine): Promise<ProjectEngine> {
    const statements = await this.#source.projectData(model, identity, this.#host.databaseType);
    if (statements.fill.length === 0) return { engine: hostEngine, fresh: false, name: undefined };
    let opening = this.#open.get(statements.name);
    const fresh = opening === undefined;
    if (!opening) {
      opening = this.#make(statements);
      this.#open.set(statements.name, opening);
      opening.catch(() => this.#open.delete(statements.name));
    }
    const open = await opening;
    await this.#trim(statements.name);
    return { engine: this.#kept(open), fresh, name: statements.name };
  }

  /** Drop `name` now (a model whose rows must be made again), if it is open. */
  async drop(name: string): Promise<void> {
    const opening = this.#open.get(name);
    if (!opening) return;
    this.#open.delete(name);
    const open = await opening.catch(() => undefined);
    if (open) await this.#close(open);
  }

  /** Every project database dropped (the page leaves). */
  async dropAll(): Promise<void> {
    for (const name of [...this.#open.keys()]) await this.drop(name);
  }

  async #make(statements: ProjectDataStatements): Promise<Open> {
    for (const sql of statements.open) await this.#host.run(sql);
    let engine: QueryEngine | undefined;
    try {
      engine = await this.#host.connect();
      for (const sql of [...statements.use, ...statements.fill]) await engine.run(sql, 0);
    } catch (e) {
      // a half-made database left behind would be found again by its name: drop it
      if (engine) await engine.close().catch(() => undefined);
      for (const sql of statements.close) await this.#host.run(sql).catch(() => undefined);
      throw e;
    }
    return { statements, engine, busy: 0, used: ++this.#clock, idle: undefined };
  }

  async #close(open: Open): Promise<void> {
    if (open.idle !== undefined) clearTimeout(open.idle);
    await open.engine.close().catch(() => undefined);
    for (const sql of open.statements.close) await this.#host.run(sql);
  }

  /** The least recently used idle databases dropped while more than `most` are open (never `keep`). */
  async #trim(keep: string): Promise<void> {
    while (this.#open.size > this.#most) {
      const settled = await Promise.all([...this.#open].map(async ([name, p]) => [name, await p.catch(() => undefined)] as const));
      const idle = settled.filter(([name, o]) => name !== keep && o !== undefined && o.busy === 0)
        .sort(([, a], [, b]) => a!.used - b!.used);
      if (idle.length === 0) return;
      await this.drop(idle[0]![0]);
    }
  }

  /** The project's connection as an engine: each call keeps it in use, and the idle clock starts once none runs. */
  #kept(open: Open): QueryEngine {
    const using = async <T>(call: () => Promise<T>): Promise<T> => {
      open.busy++;
      open.used = ++this.#clock;
      if (open.idle !== undefined) clearTimeout(open.idle);
      try {
        return await call();
      } finally {
        if (--open.busy === 0) {
          open.idle = setTimeout(() => void this.drop(open.statements.name), this.#idleMs);
          // under Node (a test, Python's headless page) the idle clock keeps no process alive
          (open.idle as { unref?: () => void }).unref?.();
        }
      }
    };
    return {
      name: open.engine.name,
      execute: (plan, epoch, signal) => using(() => open.engine.execute(plan, epoch, signal)),
      stream: (plan, epoch, onChunk, signal) => using(() => open.engine.stream(plan, epoch, onChunk, signal)),
      run: (sql, epoch, signal) => using(() => open.engine.run(sql, epoch, signal)),
      // the connection is the keeper's: dropped when idle, never by one of its users
      close: () => Promise.resolve(),
    };
  }
}

/**
 * The engine an app's screens run on when one project is open at a time (Query's, Studio's): the host's own
 * connection, or the open project's database once `use`d. Switching drops nothing: a query running keeps the
 * connection it began on, and the project left stays filled until it sits idle.
 */
export class ProjectRunner implements QueryEngine {
  readonly #host: QueryEngine;
  #current: QueryEngine;

  constructor(host: QueryEngine) {
    this.#host = host;
    this.#current = host;
  }

  get name(): string {
    return this.#host.name;
  }

  /** Run on `engine` from now (a project's, from ProjectDatabases), or on the host's own with none. */
  use(engine: QueryEngine | undefined): void {
    this.#current = engine ?? this.#host;
  }

  execute(...args: Parameters<QueryEngine['execute']>): ReturnType<QueryEngine['execute']> {
    return this.#current.execute(...args);
  }

  stream(...args: Parameters<QueryEngine['stream']>): ReturnType<QueryEngine['stream']> {
    return this.#current.stream(...args);
  }

  run(...args: Parameters<QueryEngine['run']>): ReturnType<QueryEngine['run']> {
    return this.#current.run(...args);
  }

  close(): Promise<void> {
    return this.#host.close();
  }
}
