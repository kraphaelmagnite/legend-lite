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
import { fn, lambda, type Lambda, type ValueSpecification } from '../../pure-protocol/src/index.ts';
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

/** A local store that can put a planned query's rows into a table of its own (DuckDbEngine.loadQuery). */
interface QueryLoader {
  loadQuery(target: { readonly schema?: string; readonly table: string }, sql: string, prepare?: readonly string[]): Promise<void>;
}

/** The count of `pull`'s rows, as Pure: its relation's size, a query the cube's planner plans as it plans any. */
function sizeOf(pull: Lambda): Lambda {
  const [relation, ...rest] = pull.body;
  if (!relation || rest.length > 0) throw new SnapRefusal('a snap\u2019s pull is one relation');
  return lambda(pull.parameters, fn('size', relation));
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
}

/**
 * What plans a snap's two queries (D5b): the cube's own planner, whichever it is -- the tab's, Python's, a server's
 * (legend-lite's, legend-engine's). A snap is ordinary queries: the pull's count, and the pull.
 */
export interface SnapPlanner {
  plan(query: Lambda): Promise<{ readonly sql: string }>;
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
    return (await this.#operations(databaseType, [operations]))[0]!;
  }

  /** Each step's statements -- its operations' in order -- in one question to the planner. */
  async #operations(databaseType: string, steps: readonly (readonly HostOperation[])[]): Promise<string[][]> {
    if (!this.#host) throw new SnapRefusal('this cube has no planner to write its copy\u2019s statements: it cannot be snapped');
    const answered = await this.#host.hostOperations(databaseType, steps.flat());
    let at = 0;
    return steps.map((step) => answered.slice(at, at += step.length).flat());
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
   * "this is 40 million rows" instead of watching a tab die. Counted where the rows are (the remote live plane when
   * there is one), by the planner's count in that database's own SQL (a Postgres catalog's, Postgres's).
   */
  async #preflight(engine: { run(sql: string, epoch: number): ReturnType<QueryEngine['run']> },
    count: string, epoch: number): Promise<PreflightEstimate> {
    const r = await engine.run(count, epoch);
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
   * Freeze `pull` (the live source's every column, as Pure) into a local table and switch to snapped (D5b): two
   * ordinary queries the cube's planner plans -- the pull's count first, a cheap check against the limit, then the
   * pull -- each run where the rows are; the rows land beside the copy's name and are copied into it, the copy there
   * replaced only once the new one is made, and the landing dropped whatever happens. Every other statement is a named
   * operation of the store's (D5); none is written here. A pivot's values are found by the cube's own query (plan.ts)
   * on every refresh, snapped or not, so nothing about them is captured here.
   */
  async snap(
    planner: SnapPlanner,
    pull: Lambda,
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
    const engine = this.#localStore();
    const schema = options.target.schema;
    const bare = options.target.table;
    const at = (table: string) => ({ ...(schema ? { schema } : {}), table });
    const target = named(schema, bare);
    const landing = named(schema, `${bare}__pulled`);
    // counted where the rows are: the remote live plane when there is one
    const estimate = await this.#preflight(this.#remote ?? engine, (await planner.plan(sizeOf(pull))).sql, epoch);
    if (!estimate.withinLimit) {
      throw new SnapRefusal(estimate.refusal ?? 'snap refused');
    }
    const pulled = (await planner.plan(pull)).sql;
    const [made, copy, dropped, counted] = await this.#operations(engine.databaseType, [
      [...(schema ? [{ op: 'schema' as const, name: schema }] : []), { op: 'drop', kind: 'table', target: landing }],
      [{ op: 'relation', kind: 'table', target, replace: true, from: { relation: landing } }],
      [{ op: 'drop', kind: 'table', target: landing }],
      [{ op: 'count', from: { relation: target } }],
    ]);

    this.#counter += 1;
    let pulledBy: Receipt | undefined;
    try {
      if (this.#remote) {
        // The rows live on the server: the live plan runs there, and its Arrow chunks stream into the landing --
        // exactly the rows the server let this user read
        const loader = engine as unknown as Partial<ArrowLoader>;
        if (typeof loader.loadArrow !== 'function') {
          throw new SnapRefusal('the local store cannot load Arrow data, so a remote live plane cannot be snapped');
        }
        await loader.loadArrow(at(landing.name), this.#remote.arrowChunks(pulled, undefined, (r) => { pulledBy = r; }), made);
      } else {
        // the rows are the store's own: the pull's answer handed to its Arrow insert
        const loader = engine as unknown as Partial<QueryLoader>;
        if (typeof loader.loadQuery !== 'function') {
          throw new SnapRefusal('the local store cannot load a query\u2019s rows, so it cannot be snapped');
        }
        await loader.loadQuery(at(landing.name), pulled, made);
      }
      for (const sql of copy!) await engine.run(sql, epoch);
    } finally {
      for (const sql of dropped!) await engine.run(sql, epoch);
    }
    const rowCount = Number((await engine.run(counted![0]!, epoch)).columns[0]?.values[0] ?? 0);

    const takenAt = new Date();
    const snap: SnapInfo = {
      ...(pulledBy ? { pulledBy } : {}),
      label: options.label ?? defaultLabel(takenAt),
      takenAt,
      rowCount,
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
