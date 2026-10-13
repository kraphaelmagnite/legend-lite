// Where saved cubes live: upstream's query-store contract, exactly
// (legend-engine `ApplicationQuery` / `DataCubeQueryStoreManager`, read at 4.145.0;
// docs/DATACUBE_SAVE_SHARE_2026_09_28.md step 0).
//
// The record and the operations are upstream's, so the same client later talks to
// legend-lite's server or legend-engine proper (`pure/v1/query/dataCube`) unchanged; only
// the `content` is ours (a CubeDocument). Its rules, kept:
//   - the CLIENT supplies the id; creating an id that exists is refused;
//   - the owner is forced to the current user; only the owner updates or deletes;
//   - reading a cube stamps `lastOpenAt`;
//   - search answers a LIGHT record (no content): the current user's first, then the chosen
//     sort (newest first), then the limit; a search term matches the id, the name or the owner.
//
// Two backends here: memory (tests) and the browser's IndexedDB (a tab with no server).
// Both keep the content as the protocol library's EXACT JSON text: a calculated column's
// `12.30` or an integer past 2^53 must come back as written (JSON.stringify and the
// browser's structured clone would turn them into doubles).

import { fromJson, toJson } from '../../pure-protocol/src/index.ts';

/** One saved cube, as upstream's store keeps it. Times are epoch milliseconds. */
export interface DataCubeQuery {
  readonly id: string;
  readonly name: string;
  readonly description?: string;
  readonly content: Readonly<Record<string, unknown>>;
  readonly owner?: string;
  readonly createdAt?: number;
  readonly lastUpdatedAt?: number;
  readonly lastOpenAt?: number;
}

/** What search answers: the record without its content. */
export type LightDataCubeQuery = Omit<DataCubeQuery, 'content'>;

export type QuerySearchSortBy = 'SORT_BY_CREATE' | 'SORT_BY_VIEW' | 'SORT_BY_UPDATE';

/** Upstream's `QuerySearchSpecification`, the parts a cube search uses. */
export interface QuerySearchSpecification {
  readonly searchTermSpecification?: {
    readonly searchTerm: string;
    readonly exactMatchName?: boolean;
    readonly includeOwner?: boolean;
  };
  readonly limit?: number;
  readonly showCurrentUserQueriesOnly?: boolean;
  readonly sortByOption?: QuerySearchSortBy;
}

export class CubeStoreError extends Error {
  /** The HTTP status upstream's store answers the same refusal with. */
  readonly status: 400 | 403 | 404;
  constructor(message: string, status: 400 | 403 | 404) {
    super(message);
    this.name = 'CubeStoreError';
    this.status = status;
  }
}

export interface CubeStore {
  currentUser(): Promise<string>;
  search(spec: QuerySearchSpecification): Promise<LightDataCubeQuery[]>;
  get(id: string): Promise<DataCubeQuery>;
  create(query: DataCubeQuery): Promise<DataCubeQuery>;
  update(id: string, query: DataCubeQuery): Promise<DataCubeQuery>;
  delete(id: string): Promise<void>;
}

/** Upstream's cap on one search's answer. */
export const MAX_SEARCH = 100;

/** Where records are kept: the part a backend supplies. */
export interface CubeRecords {
  all(): Promise<DataCubeQuery[]>;
  find(id: string): Promise<DataCubeQuery | undefined>;
  put(query: DataCubeQuery): Promise<void>;
  remove(id: string): Promise<void>;
}

/**
 * The store's RULES over any records: one implementation, so memory and IndexedDB cannot
 * disagree about what a store does.
 */
export class RuleStore implements CubeStore {
  readonly #records: CubeRecords;
  readonly #user: string;
  readonly #now: () => number;

  constructor(records: CubeRecords, user: string, now: () => number = Date.now) {
    this.#records = records;
    this.#user = user;
    this.#now = now;
  }

  async currentUser(): Promise<string> {
    return this.#user;
  }

  async search(spec: QuerySearchSpecification): Promise<LightDataCubeQuery[]> {
    let found = await this.#records.all();
    const term = spec.searchTermSpecification;
    if (term) {
      const t = term.searchTerm;
      found = found.filter((q) => term.exactMatchName
        ? q.name === t || (term.includeOwner === true && q.owner === t)
        : q.id === t
          || q.name.toLowerCase().includes(t.toLowerCase())
          || (q.owner ?? '').toLowerCase().includes(t.toLowerCase()));
    }
    if (spec.showCurrentUserQueriesOnly) found = found.filter((q) => q.owner === this.#user);
    const field = spec.sortByOption === 'SORT_BY_CREATE' ? 'createdAt'
      : spec.sortByOption === 'SORT_BY_UPDATE' ? 'lastUpdatedAt'
        : spec.sortByOption === 'SORT_BY_VIEW' ? 'lastOpenAt' : undefined;
    found.sort((a, b) => {
      const mine = Number(b.owner === this.#user) - Number(a.owner === this.#user);
      if (mine !== 0 || field === undefined) return mine;
      return (b[field] ?? 0) - (a[field] ?? 0);
    });
    const limit = Math.min(MAX_SEARCH, spec.limit ?? Number.MAX_SAFE_INTEGER);
    return found.slice(0, limit).map(({ content: _content, ...light }) => light);
  }

  async get(id: string): Promise<DataCubeQuery> {
    const found = await this.#records.find(id);
    if (!found) throw new CubeStoreError(`Can't find query with ID '${id}'`, 404);
    const opened = { ...found, lastOpenAt: this.#now() };
    await this.#records.put(opened);
    return opened;
  }

  async create(query: DataCubeQuery): Promise<DataCubeQuery> {
    validate(query);
    if (await this.#records.find(query.id)) {
      throw new CubeStoreError(`Query with ID '${query.id}' already existed`, 400);
    }
    const now = this.#now();
    // the owner is the current user, whatever the request says
    const created = { ...query, owner: this.#user, createdAt: now, lastUpdatedAt: now, lastOpenAt: now };
    await this.#records.put(created);
    return created;
  }

  async update(id: string, query: DataCubeQuery): Promise<DataCubeQuery> {
    validate(query);
    if (query.id !== id) throw new CubeStoreError('Updating query ID is not supported', 400);
    const current = await this.#records.find(id);
    if (!current) throw new CubeStoreError(`Can't find query with ID '${id}'`, 404);
    if (current.owner !== undefined && current.owner !== this.#user) {
      throw new CubeStoreError('Only owner can update the query', 403);
    }
    const now = this.#now();
    const updated = {
      ...query,
      owner: this.#user,
      ...(current.createdAt !== undefined ? { createdAt: current.createdAt } : {}),
      lastUpdatedAt: now,
      lastOpenAt: now,
    };
    await this.#records.put(updated);
    return updated;
  }

  async delete(id: string): Promise<void> {
    const current = await this.#records.find(id);
    if (!current) throw new CubeStoreError(`Can't find query with ID '${id}'`, 404);
    if (current.owner !== undefined && current.owner !== this.#user) {
      throw new CubeStoreError('Only owner can delete the query', 403);
    }
    await this.#records.remove(id);
  }
}

function validate(q: DataCubeQuery): void {
  if (!q.id) throw new CubeStoreError('Query ID is missing or empty', 400);
  if (!q.name) throw new CubeStoreError('Query name is missing or empty', 400);
  if (!q.content) throw new CubeStoreError('Query content is missing', 400);
}

// ---------------------------------------------------------------- backends

/** Records in memory, for tests and for a page that cannot store anything. */
export class MemoryRecords implements CubeRecords {
  readonly #map = new Map<string, Record<string, unknown>>();
  async all(): Promise<DataCubeQuery[]> {
    return [...this.#map.values()].map(fromRow);
  }
  async find(id: string): Promise<DataCubeQuery | undefined> {
    const row = this.#map.get(id);
    return row === undefined ? undefined : fromRow(row);
  }
  async put(query: DataCubeQuery): Promise<void> {
    this.#map.set(query.id, toRow(query));
  }
  async remove(id: string): Promise<void> {
    this.#map.delete(id);
  }
}

/**
 * The browser database saved cubes live in: `cubes` (records, keyed by id) and `handles`
 * (file handles by cube id: `file-handles.ts`).
 */
export function openCubeDatabase(factory: IDBFactory = globalThis.indexedDB): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const open = factory.open('datacube', 1);
    open.onupgradeneeded = () => {
      open.result.createObjectStore(BrowserRecords.STORE, { keyPath: 'id' });
      open.result.createObjectStore('handles');
    };
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error ?? new Error('this browser refused its database'));
  });
}

/** Records in the browser's IndexedDB: one object store keyed by id, every write its own transaction. */
export class BrowserRecords implements CubeRecords {
  static readonly STORE = 'cubes';
  readonly #db: Promise<IDBDatabase>;

  constructor(db: Promise<IDBDatabase> = openCubeDatabase()) {
    this.#db = db;
  }

  async all(): Promise<DataCubeQuery[]> {
    return (await this.#request<unknown[]>('readonly', (s) => s.getAll())).map(fromRow);
  }

  async find(id: string): Promise<DataCubeQuery | undefined> {
    const row = await this.#request<unknown>('readonly', (s) => s.get(id));
    return row === undefined ? undefined : fromRow(row);
  }

  async put(query: DataCubeQuery): Promise<void> {
    await this.#request('readwrite', (s) => s.put(toRow(query)));
  }

  async remove(id: string): Promise<void> {
    await this.#request('readwrite', (s) => s.delete(id));
  }

  async #request<T>(mode: IDBTransactionMode, go: (s: IDBObjectStore) => IDBRequest): Promise<T> {
    const db = await this.#db;
    return new Promise<T>((resolve, reject) => {
      const tx = db.transaction(BrowserRecords.STORE, mode);
      const request = go(tx.objectStore(BrowserRecords.STORE));
      let result: T;
      request.onsuccess = () => { result = request.result as T; };
      // resolved when the TRANSACTION commits, not when the request succeeds: a write is
      // only saved once its transaction is
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error ?? new Error('the browser refused the write'));
      tx.onabort = () => reject(tx.error ?? new Error('the browser aborted the write (storage full?)'));
    });
  }
}

/** One of the four calls a host answers for the records it keeps (python/legend_lite/_home.py's SavedPages). */
export type HostRecordsCall =
  | { readonly call: 'all' }
  | { readonly call: 'find' | 'remove'; readonly id: string }
  | { readonly call: 'put'; readonly record: Record<string, unknown> };

/**
 * Records the page's HOST keeps (Python: this user's folder; docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md, step 2d):
 * each call carried to it, each record the browser's row (its content the exact JSON text), so the store's rules
 * stay the page's own -- RuleStore over these, as over the browser's IndexedDB. `carry` answers a call: the records,
 * the record, or undefined (none found; put and removed).
 */
export class HostRecords implements CubeRecords {
  readonly #carry: (call: HostRecordsCall) => Promise<unknown>;

  constructor(carry: (call: HostRecordsCall) => Promise<unknown>) {
    this.#carry = carry;
  }

  async all(): Promise<DataCubeQuery[]> {
    const rows = await this.#carry({ call: 'all' });
    if (!Array.isArray(rows)) throw new Error('the host answered no list of saved pages');
    return rows.map(fromRow);
  }

  async find(id: string): Promise<DataCubeQuery | undefined> {
    const row = await this.#carry({ call: 'find', id });
    return row === undefined ? undefined : fromRow(row);
  }

  async put(query: DataCubeQuery): Promise<void> {
    await this.#carry({ call: 'put', record: toRow(query) });
  }

  async remove(id: string): Promise<void> {
    await this.#carry({ call: 'remove', id });
  }
}

/** The content as exact JSON text in the row; the rest as it is. */
function toRow(q: DataCubeQuery): Record<string, unknown> {
  return { ...q, content: toJson(q.content) };
}

function fromRow(row: unknown): DataCubeQuery {
  const r = row as Record<string, unknown>;
  return { ...(r as unknown as DataCubeQuery), content: fromJson(r['content'] as string) as Record<string, unknown> };
}

/**
 * Ask the browser to keep this site's data under storage pressure. Says whether it will;
 * a browser that cannot answer is "best effort".
 */
export async function persistStorage(): Promise<boolean> {
  try {
    const storage = globalThis.navigator?.storage;
    if (!storage?.persist) return false;
    return (await storage.persisted?.()) || (await storage.persist());
  } catch {
    return false;
  }
}
