// THE SERVICES: everything a host gives DataCube's app (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §3.2). The app --
// the page, its sources, its saved pages, its model actions -- is written against these and nothing else of its host,
// so the browser tab, Python's tab and widget, and Python headless are each a set of services for the same app. Each
// service is an existing client over its host's transport, never a second implementation; what a host cannot do is a
// service it does not give, and the app does not offer its entries (§3.4).
//
// Types only: a host's services are built by the host (src/services/browser.ts for the tab, src/services/python-host.ts
// for Python's tab and notebook widget).

import type { ModelHome } from '../../../depot-client/src/model-home.ts';
import type { QueryEngine } from '../../../engine-client/src/engine.ts';
import type { SeedSource } from '../../../engine-client/src/model-data.ts';
import type { CatalogObject, WarehouseEngine, WarehouseSession } from '../../../engine-client/src/warehouse.ts';
import type { ValueSpecification } from '../../../pure-protocol/src/index.ts';
import type { QueryReader } from '../../../query-store/src/index.ts';
import type { HeldCopy } from '../app.ts';
import type { CubeConfiguration } from '../config.ts';
import type { Planner, SnapTarget } from '../cube.ts';
import type { CubeStore } from '../cube-store.ts';
import type { FileHandles } from '../file-handles.ts';
import type { InferredModel, TableModels } from '../infer.ts';
import type { PageDocument } from '../page-document.ts';
import type { ModelOptions } from '../planner.ts';
import type { S3Credentials } from '../remote.ts';
import type { QueryRunner } from '../runner.ts';
import type { CubeSnapshot } from '../snapshot.ts';
import type { UploadResult } from '../upload.ts';

/** Everything a host gives the app. */
export interface Services {
  /** Where queries are planned and run, and where data goes in (§4). */
  readonly engine: EngineService;
  /** Where pages are saved (§5): opened as the app starts, and waited for only where a page is saved or opened. Absent:
   *  the page offers no Save, Save As… or Open… (a Python host until its page store, step 2d). */
  readonly pages?: Promise<PageService>;
  /** Another editor of what the page shows (§5): Python, whose code changes the page and its frames, and reads the page
   *  back. Absent in the tab: the page is the person's alone. */
  readonly live?: LiveService;
  /** Where saved queries are, and the projects they compile against. Absent: the page offers no saved query. */
  readonly queries?: QueryService;
  /** Where classes go: an SDLC and its Depot (§6). Absent: the page makes no class, and opens no project by name. */
  readonly modelHome?: ModelHomeService;
  /** A warehouse the page signs in to, its tables read Live there. Absent: the page offers none. */
  readonly warehouse?: WarehouseService;
  /** The browser's own things. */
  readonly browser: BrowserService;
}

// -------------------------------------------------------------------------------------------------------- the engine

/** Where the page plans: one word, as its status bar says it (`local`, `remote`, `engine`). */
export type PlaneWord = 'local' | 'remote' | 'engine';

/** A plane the page can open on, as its menu offers it. */
export interface Plane {
  readonly id: `host.plane.${string}`;
  readonly word: PlaneWord;
  readonly label: string;
}

/**
 * THE PLANE, chosen once as the app opens (the user, 2026-09-30, and the one-app design's R3): shown in the status bar,
 * never substituted -- a planner that does not answer is said, and the page stops. Another is chosen by opening the app
 * again on it.
 */
export interface PlaneService {
  readonly word: PlaneWord;
  readonly planes: readonly Plane[];
  /** Open the app again on `plane` (the tab: the same page, its `?planner=` the plane's). */
  choose(plane: Plane): void;
}

/**
 * A grid's rows (§4.5), in the two shapes a grid takes: planned here and run here -- a planner over the source's own
 * model and the database it runs on (the tab, headless) -- or planned and run by the host, the page sending Pure and
 * never SQL (a Python host: R4).
 */
export type GridRows =
  | { readonly planner: Planner; readonly engine: QueryEngine; readonly runner?: undefined }
  | { readonly runner: QueryRunner; readonly planner?: undefined; readonly engine?: undefined };

/** Where queries are planned and run, and where data comes in. */
export interface EngineService {
  readonly plane: PlaneService;
  /** What the status bar says of the planner, in one word. */
  readonly label: string;
  /** A grid's rows over a source's model (§4.5): its own (a page's grids share no model), in the host's shape. */
  rows(model: string, runtime: string, how?: ModelOptions): GridRows;
  /** Where data comes in. */
  readonly database: DatabaseService;
  /**
   * THE PAGE'S OWN PLANNER AND DATABASE, where the page plans and runs SQL itself (the tab): what only that shape has
   * (§4.5) -- the sample's planner and the one a saved query's text is read by, the model writer of a warehouse table,
   * test data, Snap. Absent on a Python host, where the page writes no SQL (R4): what needs it is not offered there.
   */
  readonly local?: LocalEngine;
}

/** The page's own planner and database (the tab's: its module or a server, and DuckDB-WASM). */
export interface LocalEngine {
  /** The page's planner: the sample's, and the one a saved query's text is read by. */
  readonly planner: Planner;
  /** A planner over another source's model (a page's grids share no model), on the same worker or server. */
  another(model: string, runtime: string, how?: ModelOptions): Planner;
  /** A model's elements as the compiler reads them: a saved query's project (data spaces, enumerations). */
  elements(text: string): Promise<unknown[]>;
  /** legend-lite's model writer for a table of the host's database, whichever planner plans it (infer.ts). */
  readonly tables: TableModels;
  /** legend-lite's planner for a project's test data: the statements that make and fill its tables (model-data.ts). */
  readonly seeds: SeedSource;
  /** Where a grid over one of the database's tables runs, its database type the model's connection names. */
  readonly engine: QueryEngine & { readonly databaseType: string; run(sql: string, epoch: number): Promise<unknown> };
}

/**
 * The host's database: every way data comes in -- a file, a remote file, a Snap (§4.2), a frame -- each answered with
 * the model the host wrote for it. A way a host does not take yet is absent, and the page does not offer it (§3.4).
 */
export interface DatabaseService {
  /** A file read into a table of its own (`table`), its model written from what the catalog found (upload.ts). Given
   *  with `forgetFile`. */
  readonly ingestFile?: (file: File, options: { readonly table: string }) => Promise<UploadResult>;
  /** A file's table dropped, its name free again. */
  readonly forgetFile?: (fileName: string, table: string) => Promise<void>;
  /** A remote file mounted as a view named `name`, its model written from its catalog (read-only: no conversion). */
  readonly mountRemote?: (url: string, s3: S3Credentials | undefined, name: string) => Promise<InferredModel>;
  /** A frame (Python's dataframe) as the host says it is now: the model it wrote when the frame was registered. */
  readonly frame?: (name: string) => Promise<FrameModel>;
}

/** A frame's model, as its host wrote it (Python's `Frames`): its model, runtime and source, and how often it changed. */
export interface FrameModel {
  readonly model: string;
  readonly runtime: string;
  readonly source: ValueSpecification;
  /** How many times the frame changed (its rows, its columns): a later version is a change to follow. */
  readonly version: number;
}

// --------------------------------------------------------------------------------------------------------- the pages

/** Where pages are saved: upstream's DataCube query API (cube-store.ts). */
export interface PageService {
  readonly store: CubeStore;
  /** Said where a page is saved and opened: "in this browser", "for this visit only (...)". */
  readonly where: string;
  /** What the store keeps lasts only this visit (the browser refused storage). */
  readonly memoryOnly: boolean;
  /** Where a page keeps the handle each grid's file was picked through, when the host can keep one. */
  readonly handles?: FileHandles;
  /** Ask that what is saved be kept (the browser's persistent storage): whether it was promised. */
  persist(): Promise<boolean>;
}

/** What another editor says of what the page shows: the page's version (when it serves a page) and each frame's. */
export interface Versions {
  readonly version?: number;
  readonly frames: Readonly<Record<string, number>>;
}

/**
 * ANOTHER EDITOR OF WHAT THE PAGE SHOWS (§5): Python, whose code changes the page (`ll.Page`) and its frames, and reads
 * the page back (`page.read()`). Its protocol is the page's document, its versions, and a refusal of a stale report.
 */
export interface LiveService {
  /**
   * Told each time the editor moves what the page shows (its versions), or -- a string -- why it no longer says (the
   * page or the frame closed, Python gone): told nothing after that. Returns what stops it.
   */
  watch(moved: (now: Versions | string) => void): () => void;
  /** The page the editor serves, when it serves one (`ll.Page`; `ll.show(df)` serves a frame, a page of one grid). */
  readonly page?: LivePage;
}

/** A page another editor serves: read as it is now, and told what it is now. */
export interface LivePage {
  /** The page as the editor says it is now, at its version; refused with the editor's reason. */
  read(): Promise<{ readonly version: number; readonly page: PageDocument }>;
  /** The page as it is now, said back with the version it opened (what it says of a version replaced is refused). */
  report(version: number, page: PageDocument): Promise<void>;
}

// ------------------------------------------------------------------------------------------------------- the queries

/**
 * A project a saved query can belong to, as the host's configuration names it: what it compiles against (`models`,
 * Pure text joined in order) and the SQL that puts its rows in the host's database (`seed`, one statement per line).
 */
export interface ConfiguredProject {
  readonly groupId: string;
  readonly artifactId: string;
  readonly versionId: string;
  readonly title: string;
  readonly models: readonly string[];
  readonly seed: readonly string[];
}

/** Where saved queries are (upstream's query store API), and the projects the host configured for them. */
export interface QueryService {
  readonly store: () => Promise<QueryReader>;
  /** Said where they are: "in this browser", "on host:port". */
  readonly where: string;
  /** Told when another app on the host saves one (the browser's own store), when the host can say. */
  readonly watch?: (changed: () => void) => () => void;
  readonly projects: readonly ConfiguredProject[];
  /** A file of a configured project (a model, a seed), by its URL. */
  fetchText(url: string): Promise<string>;
}

// ----------------------------------------------------------------------------------------------------- the model home

/** Where classes go, and where a saved query's project is opened by name (§6). */
export interface ModelHomeService {
  /** The SDLC and its Depot, connected the first time they are asked for. */
  connect(): Promise<ModelHome>;
  /** Studio, opened on a project's workspace, where the host serves it. */
  studio(project: string, workspace: string): string;
}

// ------------------------------------------------------------------------------------------------------ the warehouse

/** A warehouse the page signs in to as its user (engine-client/src/warehouse.ts). */
export interface WarehouseService {
  /** The warehouse the picker offers first: the deployment's, else the one this browser signed in to last. */
  offered(): string;
  /** Remember the warehouse signed in to, for the next visit. */
  remember(url: string): void;
  connect(url: string, user: string, password: string): Promise<{ session: WarehouseSession; objects: CatalogObject[] }>;
  /** The single-user app's launch key: signed in with it, at the warehouse that served the page. */
  signInWithKey(key: string): Promise<{ session: WarehouseSession; objects: CatalogObject[] }>;
  /** A table's rows read Live there, as the signed-in user. */
  live(session: WarehouseSession, catalog: string): WarehouseEngine;
}

// -------------------------------------------------------------------------------------------------------- the browser

/** The browser's own things: the same in a tab and under a notebook's cell. */
export interface BrowserService {
  download(name: string, mime: string, content: string | Uint8Array): void;
  /** Text onto the clipboard; refused, in words, where the browser does not let the page copy. */
  writeClipboard(text: string): Promise<void>;
  /** The grids' settings kept between visits (upstream's settingsData); undefined when none or not readable. */
  readSettings(): Record<string, unknown> | undefined;
  writeSettings(values: Record<string, unknown>): void;
  /** This page's address, which a share link opens; absent where no one else can open it, and no link is offered. */
  readonly address?: () => string;
  /** The page's own tab's title (its name, and whether it changed); absent where the page is not a tab of its own. */
  readonly title?: { readonly base: string; set(text: string): void };
}

// --------------------------------------------------------------------------------------------------------- the start

/** The tab's sample: generated rows (or a remote file read through the same model) under the demo's model. */
export interface Sample {
  /** Its opening view, typed by the compiler over its source. */
  readonly snapshot: () => Promise<CubeSnapshot>;
  readonly snapTarget: SnapTarget;
  /** Generated rows are a copy in the host's database from the start; a mounted remote file is live. */
  readonly heldCopy?: HeldCopy;
  readonly configuration: CubeConfiguration;
  readonly dimensions: { name: string; columns: string[] }[];
}
