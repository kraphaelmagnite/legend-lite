// Snap mode: freeze the rows behind the current view and explore them
// locally.
//
// This is the primary mental model, not an option beside an automatic
// heuristic. Two reasons, one of them the important one:
//
//  - Legibility. With automatic plane selection the user cannot tell
//    why a query took 20ms or 3 seconds. Snap-versus-live is two states
//    a person can reason about, and it is LESS machinery than
//    auto-selection, not more.
//  - Correctness of the work. An analyst tying out numbers needs a
//    stable denominator. If the underlying data moves mid-analysis the
//    figures shift under them and the work is unfalsifiable. "I
//    reconciled against the 09:00 snap" is auditable; "against live
//    data this morning" is not.
//
// The historical objection to caching is stale data, and it is taken
// seriously here. The defect was never that data was old -- it was that
// data was old and you could not tell. So three rules are enforced
// rather than documented:
//
//   1. A snap always carries its timestamp and row count, so the UI can
//      never fail to show what is being looked at.
//   2. An operation the embedded engine cannot express is REFUSED with
//      a reason. Silently escalating to live would break the
//      point-in-time guarantee invisibly, which is worse than an error.
//   3. The snap captures source rows at drillable grain, never
//      aggregated results, so drill-through audits the same frozen data
//      it is drilling into.

//
// The engine half, shared by every app that freezes rows into the tab (Studio plan A6; moved from DataCube, which keeps
// its cube behaviour on top): the manager, the stamp it carries, the refusals and the preflight. Where a snap's
// queries are planned is the app's (DataCube's SnapTarget adds its planner).

import type { HostName, HostOperation, HostOperationsSource } from './host-operations.ts';
import { UI_LOCALE } from './locale.ts';
import type { Lambda, ValueSpecification } from '../../pure-protocol/src/index.ts';
import type { QueryEngine } from './engine.ts';
import type { Receipt } from './receipt.ts';

/**
 * A LIVE plane on another machine (the warehouse): where a snap's rows come
 * from when they are not already in the tab. Counted there, then streamed as
 * Arrow into the local store.
 */
export interface RemoteSource {
  /** The remote plane's database type (a warehouse catalog's: DuckDB or Postgres), what its statements are for. */
  readonly databaseType: string;
  /** Raw SQL on the remote plane: the preflight count, as the planner writes it for that database. */
  run: QueryEngine['run'];
  /** `onReceipt` hears, once the statement has succeeded, what the server issued for it. */
  arrowChunks(sql: string, signal?: AbortSignal, onReceipt?: (receipt: Receipt) => void): AsyncIterable<Uint8Array>;
}

/** A local store that can take Arrow chunks (DuckDbEngine). */
interface ArrowLoader {
  loadArrow(
    target: { readonly schema?: string; readonly table: string },
    chunks: AsyncIterable<Uint8Array>,
    prepare?: readonly string[],
  ): Promise<void>;
}

/**
 * Memory ceiling. A ~500MB tab
 * budget holds about 10M rows across every data shape measured --
 * 9.3M for a UUID-bearing worst case, 33.8M for realistic shapes.
 */
export const MAX_SNAP_ROWS = 10_000_000;

export interface SnapInfo {
  readonly label: string;
  readonly takenAt: Date;
  readonly rowCount: number;
  /** Table name the snap materialised into. */
  readonly table: string;
  /** Its schema, when the live source's table has one (a warehouse's `sales.v_orders`). */
  readonly schema?: string;
  /**
   * What a query should read from while this snap holds: a relation the
   * planner can resolve, as protocol (`#>{db.TABLE_SNAP}#`), never a bare
   * SQL identifier -- the query is Pure before it is SQL.
   */
  readonly source: ValueSpecification;
  /** The pull from a remote live plane that made it: the server's receipt. */
  readonly pulledBy?: Receipt;
  /** Whose the copy is when not this tab's: its host's (`Python`: HostSnapTarget.store). */
  readonly store?: string;
}

/**
 * Where a snap materialises: a table the app's MODEL also declares, and the
 * relation that reads it (`#>{db.TABLE_SNAP}#`), so a snapped app's queries
 * are planned exactly as live ones are.
 */
export interface SnapTable {
  readonly schema?: string;
  readonly table: string;
  readonly source: ValueSpecification;
  /**
   * What the COPY applies so it holds the model's declared types (the catalog model's
   * `conversions`, CatalogModel.database): a zoned timestamp read in place under the source's
   * UTC session is copied as its UTC wall time, so the tab's store, in whatever zone, reads the
   * same values (docs/DATACUBE_APP_PLAN_2026_10_02.md, leg B). SQL in the catalog's own terms --
   * DuckDB's, whose catalog describes every column (the catalog question) -- so it is applied by the
   * store the copy is written to, after the pull: the pull runs where the rows are, in that
   * database's SQL (a Postgres catalog's is Postgres), and a rewrite there would have to be
   * spelled in it.
   */
  readonly conversions: readonly { readonly column: string; readonly sql: string }[];
}

export type PlaneState =
  | { readonly mode: 'live' }
  | { readonly mode: 'snapped'; readonly snap: SnapInfo };

/** Refusal rather than silent escalation. See rule 2 above. */
export class SnapRefusal extends Error {
  readonly reason: string;
  constructor(reason: string) {
    super(reason);
    this.name = 'SnapRefusal';
    this.reason = reason;
  }
}

export interface PreflightEstimate {
  readonly rowCount: number;
  readonly withinLimit: boolean;
  /** Present when the snap would be refused. */
  readonly refusal?: string;
}

/** A snap's table as a host operation names it: the name the app's model declares, quoted as written. */
function named(schema: string | undefined, table: string): HostName {
  return { ...(schema ? { schema } : {}), name: table };
}

/** What a cube asks of its snaps, wherever the copy is: the page's own store (SnapManager) or its host's (HostSnaps). */
export interface Snaps {
  readonly state: PlaneState;
  readonly isSnapped: boolean;
  sourceFor(liveSource: ValueSpecification): ValueSpecification;
  release(): Promise<void>;
  detach(): SnapInfo | null;
  reattach(snap: SnapInfo): void;
  discard(snap: SnapInfo): Promise<void>;
}

export class SnapManager implements Snaps {
  /**
   * The local store a snapshot freezes INTO.
   *
   * Null on a plane where a remote engine answers the queries: there
   * is no local table to materialise into and nothing here can
   * invent one. Every method that needs it refuses by name rather
   * than half-working -- the cache mode is what will fill this in.
   */
  readonly #engine: (QueryEngine & { readonly databaseType: string }) | null;
  /** The live plane, when it is remote; absent when live is the local store. */
  readonly #remote: RemoteSource | null;
  /** What writes the copy's statements (the tab's planner: D5); none, a snap is refused by name. */
  readonly #host: HostOperationsSource | undefined;
  #state: PlaneState = { mode: 'live' };
  #counter = 0;

  constructor(engine: (QueryEngine & { readonly databaseType: string }) | null, remote: RemoteSource | null = null,
    host?: HostOperationsSource) {
    this.#engine = engine;
    this.#remote = remote;
    this.#host = host;
  }

  /** The planner's statements for `operations` on a database of `databaseType`, in order. */
  async #statements(databaseType: string, operations: readonly HostOperation[]): Promise<string[]> {
    if (!this.#host) throw new SnapRefusal('this cube has no planner to write its copy\u2019s statements: it cannot be snapped');
    return (await this.#host.hostOperations(databaseType, operations)).flat();
  }

  /**
   * The local store, or a refusal naming what is missing.
   *
   * "Cannot read properties of null" is not an answer anyone can
   * act on; "this plane has no local store to freeze into" is.
   */
  #localStore(): QueryEngine & { readonly databaseType: string } {
    if (!this.#engine) {
      throw new SnapRefusal(
        'this cube\u2019s queries run on a remote engine, which has no'
        + ' local store to freeze a snapshot into. Caching a source'
        + ' locally is a mode of its own, and not this one.',
      );
    }
    return this.#engine;
  }

  get state(): PlaneState {
    return this.#state;
  }

  get isSnapped(): boolean {
    return this.#state.mode === 'snapped';
  }

  /**
   * The relation a query should read from, given the current plane.
   *
   * This existed and NOTHING CALLED IT, so snapping materialised a
   * table and then went on querying the live source: the badge
   * changed and the data did not. The controller calls it on every
   * refresh now, and a test pins that the snapped plane reads the
   * snap.
   */
  sourceFor(liveSource: ValueSpecification): ValueSpecification {
    return this.#state.mode === 'snapped'
      ? this.#state.snap.source
      : liveSource;
  }

  /**
   * Check size before materialising anything, so the user is told
   * "this is 40 million rows" instead of watching a tab die.
   */
  async preflight(sourceSql: string, epoch: number): Promise<PreflightEstimate> {
    // counted where the rows are: the remote live plane when there is one, by its own database's statement (a
    // warehouse catalog that is Postgres, Postgres's: D5)
    const engine = this.#remote ?? this.#localStore();
    const [count] = await this.#statements(engine.databaseType, [{ op: 'count', from: { query: sourceSql } }]);
    const r = await engine.run(count!, epoch);
    const raw = r.columns[0]?.values[0] ?? 0;
    const rowCount = typeof raw === 'number' ? raw : Number(raw);

    if (rowCount > MAX_SNAP_ROWS) {
      return {
        rowCount,
        withinLimit: false,
        refusal:
          `${rowCount.toLocaleString(UI_LOCALE)} rows exceeds the ` +
          `${MAX_SNAP_ROWS.toLocaleString(UI_LOCALE)} row snap limit. ` +
          `Narrow the filter, or stay live.`,
      };
    }
    return { rowCount, withinLimit: true };
  }

  /**
   * Freeze `sourceSql` into a local table and switch to snapped. A
   * pivot's values are found by the cube's own query (plan.ts) on every
   * refresh, snapped or not, so nothing about them is captured here.
   */
  async snap(
    sourceSql: string,
    epoch: number,
    options: {
      readonly label?: string;
      /**
       * Where to materialise, and what to call it in a query afterwards:
       * a table the app's model also declares. Supplied by the caller,
       * which owns the model.
       */
      readonly target: SnapTable;
    },
  ): Promise<SnapInfo> {
    const estimate = await this.preflight(sourceSql, epoch);
    if (!estimate.withinLimit) {
      throw new SnapRefusal(estimate.refusal ?? 'snap refused');
    }

    this.#counter += 1;
    let pulledBy: Receipt | undefined;
    const schema = options.target.schema;
    const bare = options.target.table;
    const table = named(schema, bare);
    const engine = this.#localStore();
    // the copy's conversions, applied by the store the copy is written to (SnapTarget.conversions): the same rewrite an
    // upload applies at ingest (upload.ts), each column replaced by the planner's expression for it
    const replacing = options.target.conversions.map((c) => ({ column: c.column, sql: c.sql }));
    if (this.#remote) {
      // The rows live on the server: the live plan runs there, and its Arrow
      // chunks stream into a local table of the same name, so the model -- and
      // the planned SQL -- read it unchanged. Exactly the rows the server let
      // this user read. A copy that needs converting lands beside its name
      // first, and is rewritten into it here.
      const loader = engine as unknown as Partial<ArrowLoader>;
      if (typeof loader.loadArrow !== 'function') {
        throw new SnapRefusal('the local store cannot load Arrow data, so a remote live plane cannot be snapped');
      }
      const landing = replacing.length === 0 ? bare : `${bare}__pulled`;
      const pulled = named(schema, landing);
      // its schema made and the table there before dropped, by the planner's statements, before the rows load
      const prepare = await this.#statements(engine.databaseType, [
        ...(schema ? [{ op: 'schema' as const, name: schema }] : []),
        { op: 'drop', kind: 'table', target: pulled },
      ]);
      await loader.loadArrow({ ...(schema ? { schema } : {}), table: landing },
        this.#remote.arrowChunks(sourceSql, undefined, (r) => { pulledBy = r; }), prepare);
      if (landing !== bare) {
        const [copy, drop] = await this.#host!.hostOperations(engine.databaseType, [
          { op: 'relation', kind: 'table', target: table, replace: true, replacing, from: { relation: pulled } },
          { op: 'drop', kind: 'table', target: pulled },
        ]);
        try {
          for (const sql of copy!) await engine.run(sql, epoch);
        } finally {
          for (const sql of drop!) await engine.run(sql, epoch);
        }
      }
    } else {
      for (const sql of await this.#statements(engine.databaseType, [
        { op: 'relation', kind: 'table', target: table, replace: true, replacing, from: { query: sourceSql } },
      ])) await engine.run(sql, epoch);
    }

    const takenAt = new Date();
    const snap: SnapInfo = {
      ...(pulledBy ? { pulledBy } : {}),
      label: options.label ?? defaultLabel(takenAt),
      takenAt,
      rowCount: estimate.rowCount,
      table: bare,
      ...(schema ? { schema } : {}),
      source: options.target.source,
    };
    this.#state = { mode: 'snapped', snap };
    return snap;
  }

  /** Drop the snap and return to live. */
  async release(): Promise<void> {
    const held = this.detach();
    if (held) await this.discard(held);
  }

  /**
   * Back to live, KEEPING the snap's table: going live is only done once a live
   * query has answered. Dropping first left a warehouse that could not be
   * reached showing the dropped copy's rows under "Live" (2026-09-29).
   */
  detach(): SnapInfo | null {
    if (this.#state.mode !== 'snapped') return null;
    const held = this.#state.snap;
    this.#state = { mode: 'live' };
    return held;
  }

  /** Live did not answer: back on the snap `detach` kept. */
  reattach(snap: SnapInfo): void {
    this.#state = { mode: 'snapped', snap };
  }

  /** Live answered: the kept snap's table goes. */
  async discard(snap: SnapInfo): Promise<void> {
    const store = this.#localStore();
    for (const sql of await this.#statements(store.databaseType, [
      { op: 'drop', kind: 'table', target: named(snap.schema, snap.table) },
    ])) await store.run(sql, 0);
  }
}

/**
 * The plane as every app states it (rule 1: what you are looking at is never inferable): the pill's word and its
 * tooltip -- WHEN a snap was frozen and HOW MANY rows it holds, or where live data is. `remote`: live is another
 * machine (a warehouse); `toggles`: a click changes the plane (both planes exist), which the tooltip offers.
 */
export function describePlane(state: PlaneState, where: {
  readonly remote: boolean;
  readonly toggles: boolean;
  /** Where a snap's copy is kept: this tab (the default), or its host's database (`Python`). */
  readonly store?: string;
}): { text: string; title: string } {
  const into = where.store === undefined ? 'this tab' : `${where.store}'s database`;
  if (state.mode === 'snapped') {
    const s = state.snap;
    return {
      text: 'Snapped',
      title: `${s.label} — frozen at ${s.takenAt.toLocaleTimeString(UI_LOCALE)}, ${s.rowCount.toLocaleString(UI_LOCALE)} rows`
        + (where.remote ? `, a copy in ${into}` : '') + '.' + (where.toggles ? ' Click to go live.' : ' Nothing can change it while you work.'),
    };
  }
  if (!where.toggles) return { text: 'Live', title: 'Live — this plane has no store in this tab to snap into.' };
  return {
    text: 'Live',
    title: where.remote
      ? `Live — running on the warehouse, as you. Click to snap a copy of your rows into ${into}.`
      : 'Live data, which may move while you work. Click to snap.',
  };
}

/**
 * A SNAP ITS HOST MAKES (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C3): where the page has no store of its own (a page on
 * Python), the host pulls the live source's rows into its own database, and the copy's queries are the host's. The page
 * sends the pull as Pure (R4); the host plans it where the rows are, counts it against the same limit
 * (MAX_SNAP_ROWS), copies it with the model's conversions, and says what it froze.
 */
export interface HostSnapTarget {
  /** Freeze `pull` (the live source's every column, as Pure) into the host's copy: its count, and the pull's receipt. */
  snap(pull: Lambda): Promise<{ readonly rowCount: number; readonly pulledBy?: Receipt }>;
  /** The copy dropped. */
  release(): Promise<void>;
  /** The table the copy is (the live table's own name, which the model declares), and the relation that reads it. */
  readonly table: string;
  readonly schema?: string;
  readonly source: ValueSpecification;
  /** Whose database the copy is in, as a person reads it: `Python`. */
  readonly store: string;
}

/** The states SnapManager keeps, for a copy its host keeps (HostSnapTarget). */
export class HostSnaps implements Snaps {
  readonly #target: HostSnapTarget;
  #state: PlaneState = { mode: 'live' };
  /** A snap asked for and not answered yet: a grid closed meanwhile lets its copy go once it is made (`close`). */
  #pending: Promise<unknown> | undefined;
  #closed = false;

  constructor(target: HostSnapTarget) {
    this.#target = target;
  }

  get state(): PlaneState {
    return this.#state;
  }

  get isSnapped(): boolean {
    return this.#state.mode === 'snapped';
  }

  sourceFor(liveSource: ValueSpecification): ValueSpecification {
    return this.#state.mode === 'snapped' ? this.#state.snap.source : liveSource;
  }

  /** Freeze `pull` there and switch to snapped. */
  async snap(pull: Lambda, label?: string): Promise<SnapInfo> {
    const asked = this.#target.snap(pull);
    this.#pending = asked;
    let frozen: Awaited<typeof asked>;
    try {
      frozen = await asked;
    } finally {
      this.#pending = undefined;
    }
    if (this.#closed) {
      // the grid closed while the copy was made: it holds none
      await this.#target.release();
      throw new Error('the grid was closed while its snap was made');
    }
    const takenAt = new Date();
    const snap: SnapInfo = {
      ...(frozen.pulledBy ? { pulledBy: frozen.pulledBy } : {}),
      label: label ?? defaultLabel(takenAt),
      takenAt,
      rowCount: frozen.rowCount,
      table: this.#target.table,
      ...(this.#target.schema ? { schema: this.#target.schema } : {}),
      source: this.#target.source,
      store: this.#target.store,
    };
    this.#state = { mode: 'snapped', snap };
    return snap;
  }

  async release(): Promise<void> {
    const held = this.detach();
    if (held) await this.discard(held);
  }

  detach(): SnapInfo | null {
    if (this.#state.mode !== 'snapped') return null;
    const held = this.#state.snap;
    this.#state = { mode: 'live' };
    return held;
  }

  reattach(snap: SnapInfo): void {
    this.#state = { mode: 'snapped', snap };
  }

  async discard(_snap: SnapInfo): Promise<void> {
    await this.#target.release();
  }

  /** The grid is gone: the copy it holds let go, and one a snap in flight makes let go once it is made. */
  async close(): Promise<void> {
    this.#closed = true;
    if (this.#pending === undefined) await this.release();
  }
}

function defaultLabel(at: Date): string {
  const hh = String(at.getHours()).padStart(2, '0');
  const mm = String(at.getMinutes()).padStart(2, '0');
  return `Snap ${hh}:${mm}`;
}
