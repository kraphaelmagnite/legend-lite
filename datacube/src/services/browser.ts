// THE BROWSER TAB'S SERVICES (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §3.2): what DataCube's app runs on in a tab --
// the planner the page chose (the tab's WebAssembly module, or a server), DuckDB-WASM as the database every file,
// remote file and Snap goes into, the saved pages in this browser's IndexedDB (memory when it refuses), the saved
// queries in the query store the deployment names (this browser's own when none), the model home a depot names, the
// warehouse the picker offers, and the browser's downloads, clipboard and kept settings. Each is the client the tab
// already used, built here once rather than at each use; the tab's entry hands in what only it knows (its plane, its
// planner, its DuckDB, its configuration).

import type { AsyncDuckDB } from '../../../engine-client/src/duckdb-wasm.ts';
import type { DuckDbEngine } from '../../../engine-client/src/duckdb.ts';
import type { SeedSource } from '../../../engine-client/src/model-data.ts';
import { connect, listObjects, signInWithKey, WarehouseEngine } from '../../../engine-client/src/warehouse.ts';
import type { QueryReader } from '../../../query-store/src/index.ts';
import type { Planner } from '../cube.ts';
import { BrowserRecords, MemoryRecords, RuleStore, openCubeDatabase, persistStorage, type CubeStore } from '../cube-store.ts';
import { FileHandles } from '../file-handles.ts';
import type { TableModels } from '../infer.ts';
import type { ModelOptions } from '../planner.ts';
import { forgetUpload, ingestFile, mountRemoteView } from '../upload.ts';
import { browserBasics, tabTitle } from './browser-basics.ts';
import type {
  ConfiguredProject,
  DatabaseService,
  ModelHomeService,
  PageService,
  PlaneService,
  QueryService,
  Services,
  WarehouseService,
} from '../app/services.ts';

// the saved queries' code, fetched the first time it is needed -- the Saved queries section, a query link -- as charts
// are: a grid-only page never downloads it (test/bundle-budget.test.ts)
const queryStores = () => import('../../../query-store/src/index.ts');

/** The tab's planner, as its plane builds it (demo/planners.ts). */
export interface TabPlanner {
  readonly planner: Planner;
  readonly label: string;
  readonly tables: TableModels;
  readonly seeds: SeedSource;
  readonly models: {
    another(model: string, runtime: string, how?: ModelOptions): Planner;
    elements(text: string): Promise<unknown[]>;
  };
}

/** What the tab's deployment says (demo/page-config.ts `PageConfig`): its servers, its store, its projects, its depot. */
export interface TabConfig {
  readonly warehouse: string;
  readonly queryStore: string;
  readonly user: string;
  readonly projects: readonly ConfiguredProject[];
  readonly depot?: { readonly sdlc: string; readonly vendor: string };
}

export interface BrowserServicesOptions {
  readonly plane: PlaneService;
  readonly planner: TabPlanner;
  /** This tab's DuckDB: its connection's engine, and the database files are registered on. */
  readonly duckdb: { readonly engine: DuckDbEngine; readonly db: AsyncDuckDB };
  readonly config: TabConfig;
  /** Where the page is, for the links it makes (Studio on a project). */
  readonly location: { readonly href: string };
}

/**
 * THE TAB'S DUCKDB SET UP as the planner's SQL expects, as Python's Frames sets up its own: the dialect's own session
 * setup, from the compiler (`Boundary.sessionSetup`: a DuckDB session in UTC), run on the connection before any query
 * the planner wrote. Left alone, DuckDB-WASM takes the browser's zone -- a fixed offset of it at that ("Etc/GMT+5" in New
 * York, in summer too) -- and `now()`, `today()` and any zoned timestamp answered otherwise than on Python.
 */
export async function setUpSession(engine: DuckDbEngine, tables: TableModels): Promise<void> {
  for (const statement of await tables.sessionSetup(engine.databaseType)) await engine.run(statement, 0);
}

/** The tab's services, built once as the page starts: its DuckDB set up first (`setUpSession`). */
export async function browserServices(o: BrowserServicesOptions): Promise<Services> {
  const { config } = o;
  await setUpSession(o.duckdb.engine, o.planner.tables);
  const modelHome = o.config.depot ? depotHome(o.config.depot, o.location) : undefined;
  return {
    engine: {
      plane: o.plane,
      label: o.planner.label,
      // planned here and run here: a planner over the source's own model, on this tab's DuckDB
      rows: (model, runtime, how) => ({ planner: o.planner.models.another(model, runtime, how), engine: o.duckdb.engine }),
      database: tabDatabase(o.duckdb, o.planner.tables),
      local: {
        planner: o.planner.planner,
        another: (model, runtime, how) => o.planner.models.another(model, runtime, how),
        elements: (text) => o.planner.models.elements(text),
        tables: o.planner.tables,
        seeds: o.planner.seeds,
        engine: o.duckdb.engine,
      },
    },
    // opened now and waited for where a page is saved or opened: the start's first grid does not wait on IndexedDB
    pages: browserPages(),
    queries: queryService(config),
    ...(modelHome ? { modelHome } : {}),
    warehouse: warehouseService(config),
    browser: {
      ...browserBasics,
      address: () => o.location.href,
      title: tabTitle(),
    },
  };
}

/** DuckDB in this tab: every grid's rows, and where a file, a remote file or a Snap goes. */
function tabDatabase(duck: { readonly engine: DuckDbEngine; readonly db: AsyncDuckDB }, tables: TableModels): DatabaseService {
  const { engine, db } = duck;
  return {
    ingestFile: (file, options) => ingestFile(engine, db, file, tables, options),
    forgetFile: (fileName, table) => forgetUpload(engine, db, fileName, table),
    mountRemote: (url, s3, name) => mountRemoteView(engine, tables, { url, name, ...(s3 ? { s3 } : {}) }),
  };
}

/** SAVED PAGES, in this browser: IndexedDB when the browser gives it, else memory (this visit only, and said so). One
 *  database holds the pages and their files' handles. */
async function browserPages(): Promise<PageService> {
  let store: CubeStore;
  let handles: FileHandles | undefined;
  let memoryOnly = false;
  try {
    const database = openCubeDatabase();
    await database;
    store = new RuleStore(new BrowserRecords(database), 'this browser');
    handles = new FileHandles(database);
  } catch {
    store = new RuleStore(new MemoryRecords(), 'this browser');
    memoryOnly = true;
  }
  return {
    store,
    where: memoryOnly ? 'for this visit only (this browser refused storage)' : 'in this browser',
    memoryOnly,
    ...(handles ? { handles } : {}),
    persist: persistStorage,
  };
}

/**
 * THE QUERY STORE, through the one client (query-store/README.md): the server the deployment names, or -- none named --
 * the same API answered in this page from this origin's browser store, the one Legend Query keeps its saved queries in
 * when it runs without a server.
 */
function queryService(config: TabConfig): QueryService {
  const url = config.queryStore;
  let store: Promise<QueryReader> | undefined;
  return {
    store: () => (store ??= queryStores().then((m) => (url
      ? new m.QueryStoreClient(url)
      : new m.QueryStoreClient(m.LOCAL_API, m.localQueryServer({ records: new m.BrowserRecords(), user: config.user }).fetch)))),
    where: url ? `on ${hostOf(url)}` : 'in this browser',
    // the browser's store: a query saved in another tab (Legend Query) appears without reopening
    ...(url ? {} : {
      watch: (changed: () => void) => {
        let stop = (): void => undefined;
        let stopped = false;
        void queryStores().then((m) => { if (!stopped) stop = m.watchBrowserStore(changed); });
        return () => { stopped = true; stop(); };
      },
    }),
    projects: config.projects,
    fetchText: async (url) => {
      const r = await fetch(url);
      if (!r.ok) throw new Error(`${url} answered ${r.status}`);
      return r.text();
    },
  };
}

/** A server's host as the page names it; an address that does not parse is said as written (it is refused when used). */
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

/** The model home a depot names (the page's own SDLC and Depot, or servers), opened once -- loaded only when first
 *  asked for: the grid-only page's budget does not carry it. */
function depotHome(depot: { readonly sdlc: string; readonly vendor: string }, location: { readonly href: string }): ModelHomeService {
  let home: ReturnType<ModelHomeService['connect']> | undefined;
  return {
    connect: () => (home ??= import('../../../depot-client/src/model-home.ts')
      .then(({ connectModelHome }) => connectModelHome({ sdlc: depot.sdlc, vendor: depot.vendor }))),
    studio: (project, workspace) => new URL(
      `/studio/demo/index.html#/edit/${encodeURIComponent(project)}/${encodeURIComponent(workspace)}`, location.href).href,
  };
}

/** Which warehouse to offer: the deployment's (config.json, ?warehouse=), else the last one this browser signed in
 *  to -- a convenience kept in this browser only; storage may be refused. */
const REMEMBERED = 'datacube.warehouse.url';

function warehouseService(config: TabConfig): WarehouseService {
  return {
    offered: () => {
      if (config.warehouse) return config.warehouse;
      try {
        return window.localStorage.getItem(REMEMBERED) ?? '';
      } catch {
        return '';
      }
    },
    remember: (url) => {
      try {
        window.localStorage.setItem(REMEMBERED, url);
      } catch {
        // storage refused: not remembered, nothing else changes
      }
    },
    connect: (url, user, password) => connect(url, user, password),
    signInWithKey: async (key) => {
      if (!config.warehouse) throw new Error('this page was not served by a warehouse: its config.json names none');
      const session = await signInWithKey(config.warehouse, key);
      return { session, objects: await listObjects(session) };
    },
    live: (session, catalog) => new WarehouseEngine(session, catalog),
  };
}
