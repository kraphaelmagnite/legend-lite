# DataCube: one app, every host (design, 2026-10-11)

Status: **agreed** (the user, 2026-10-11: "agree on all 6 ruling"; §13, §14). Nothing here is built yet. It supersedes
parts of four agreed designs, each named in §13 with what changes.

## 0. In one paragraph

DataCube is started in four places today: the browser tab (`demo/boot.ts`, 2,384 lines), Python's tab and notebook
widget (`demo/engine-page.ts` for a page, `demo/engine-cube.ts` for one cube), and an old page fixture
(`demo/page.ts`). Python headless runs pieces of it without a screen. They share the page and the grid (`PageApp`,
`CubePage`, `CubeApp`), but everything around them -- opening sources, saving pages, sharing, Create a Class…, the
planner menu -- lives in `boot.ts` alone, so Python's pages have none of it. This design makes **one DataCube app**
that every host opens. A host supplies only **services**: where queries are planned, where they run and where data
goes in, where pages are saved, where classes go, and where saved queries are. The browser tab, Python's tab, Python's
widget and Python headless become four sets of services for the same app. A feature is built once, and every host has
it. Python also becomes a full local host for legend-lite's apps: its own SDLC over a git folder, so Studio and Query
can start from Python too.

## 1. What binds this design (the user's words, 2026-10-11)

- "why would we ever want two wrappers at all?"
- "i dont really think there should be any of those differences - for example upload a file should just use whatever
  is configured as the Snap database (duckdb wasm for browser, pyhton duckdb for python) - same with choosing a planner -
  both modes can choose whatever planner they want - local is wasm in browser, native in python but can both choose
  remote if they want"
- "we also eventually want to start studio and query from python too", so where classes go must not differ by host.
- "for 'where the data is' again that is just configuration of where planner/database come from?"
- "i dont want to land anything here that does not 'stand alone'": each step of §12 lands on its own merits.

And the rules already agreed, which this design keeps:

- **DataCube's logic is DataCube's TypeScript, wherever it runs** (headless design §1). It is not copied into Python.
- **No fallbacks** (AGENTS.md invariant 4). A planner that does not answer is said, never substituted
  (`test/guardrails.test.ts`).
- **The page document is the contract** between hosts (Python pages design §3).
- **No WebAssembly in the Python process** (headless design §1).

## 2. What exists (read 2026-10-11)

**The shared layer.**
- `PageApp` (`src/page/page-app.ts`) draws the page: its bar and menu, sheets, layout, stacks, charts, export of the
  page file.
- `CubeApp` (`src/app.ts`) is one grid.
- A host hands `PageApp` a `GridMaker` per grid, and each `CubeApp` its rows: either `{planner, engine, live?}`
  (planned here, run here) or `{runner}` (planned and run elsewhere: `RemoteRun`).

**What only the tab's wrapper does** (`demo/boot.ts`):
- the source picker, and opening every kind of source: a file uploaded into DuckDB-WASM, an example, a remote
  Parquet/CSV/Iceberg file, a warehouse table, a saved query (`boot.ts` 1158-2198);
- reopening each kind for a saved page (1368-1458);
- the saved pages: Open…, Save, Save As… in IndexedDB (1248-1631);
- share links, and the start from a link, a page fragment or a launch key (152-167, 1637-1679, 2215-2272);
- the blank page; "changed since saved"; asking before leaving;
- settings kept in this browser;
- the planner menu (116-228);
- the Generated Pure & SQL window;
- Create a Class…, Create an Association…, Create a Data Space… and the Model menu (1770-1863).

**What Python's wrappers do** (`engine-page.ts`, `engine-cube.ts`):
- grids over Python's frames;
- every query planned and run by Python (`RemoteRun` over `LegendEngineExecutor`);
- the page followed when Python changes it, and reported back for `page.read()`.

They have none of `boot.ts`'s list. The engine page loads no compiler and no DuckDB-WASM (budget 300 KB). The widget is
one file of at most 500 KB, because a module imported from a blob URL can load no chunk.

**Python headless** runs DataCube's TypeScript in V8 with no screen:
- the planner is `LitePlanner` over the host's worker (the native library);
- the database is `DuckDbEngine` over Python's DuckDB.

It has grids (`view`, `relation`, `to_pandas`), not pages.

**Already one implementation, many places** (the pattern this design extends):
- **The SDLC.** `Sdlc.java`'s rules run in the browser (compiled to WebAssembly, kept in IndexedDB) or as a server over
  a real git repository (`GitStorage`: plain Java, no git program, no JGit).
- **The saved-query store.** `SavedQueries.java` runs in legend-lite's server (`--query-store DIR`); `query-store`'s
  in-page server is its port.
- **The planner's worker protocol.** `planner-protocol.ts` is answered by the tab's module or by Python's native
  library.

**The planner and the database are already independent in the code.** Any `Planner` pairs with any `QueryEngine`. Three
things tie them today:
1. the SQL dialect follows the model's connection type;
2. `RemoteRun` fuses the two (plan and run in one call);
3. opening a table needs the compiler's table-model writer.

## 3. The shape

### 3.1 The app

`datacube/src/app/` holds **everything a DataCube page does**:
- the page and its grids (`PageApp`, unchanged);
- the source picker and the opening and reopening of each source kind;
- the saved pages (Open…, Save, Save As…), share links and the page file;
- the start (what to open first);
- the blank page, "changed since saved", and asking before leaving;
- the planner menu and the Generated Pure & SQL window;
- the model actions (Create a Class…, Create an Association…, Create a Data Space…);
- the status line.

It is written against `Services` (§3.2) and nothing else of its host. It is the code `boot.ts` has today, moved and
cut loose from the tab.

One entry, used by every host that has a screen:

```ts
const app = await openDataCube(host: HTMLElement, services: Services);
```

### 3.2 The services (the whole of a host's job)

```ts
interface Services {
  /** Where queries are planned and run, and where data goes in (§4). */
  readonly engine: EngineService;
  /** Where pages are saved, and -- when another editor has the open page -- that editor (§5). */
  readonly pages: PageService;
  /** Where classes go: an SDLC and its Depot (§6). */
  readonly modelHome: ModelHomeService;
  /** Where saved queries are (upstream's query store API). */
  readonly queries: QueryStoreService;
  /** What to open first: the tab's address, the page Python hands over. */
  readonly start: Start;
  /** The browser's own things: download, clipboard, settings kept, files picked, confirm (§3.4). Absent headless. */
  readonly browser?: BrowserService;
}
```

**Every service is an existing client over a different transport**, never a second implementation:

| Service | Its client today | In a browser tab | On a Python host (tab or widget) | Headless (in Python) |
|---|---|---|---|---|
| engine: planner | `LitePlanner` / `UpstreamPlanner` | the tab's WebAssembly module (local), or a server | Python's: the native library (local), or a server, called by Python (§4.3) | `LitePlanner` over the host's worker |
| engine: database | `DuckDbEngine` | DuckDB-WASM | Python's DuckDB, behind Python (§4.3) | Python's DuckDB, through the host's connection |
| pages | `CubeStore` (upstream's DataCube query API) | IndexedDB (`RuleStore`) | a folder, served by Python (§5) | the `ll.Page` object itself |
| model home | `SdlcClient` + `DepotClient` | the in-page SDLC (WebAssembly + IndexedDB), or a server | Python's SDLC over a git folder, served by Python (§6) | Python's SDLC, through a host call |
| queries | `QueryStoreClient` | the in-page store, or a server | Python's store over a folder (`SavedQueries.java`) | the same, through a host call |

### 3.3 The hosts become

- **The browser tab** (`demo/main.ts`): the browser's services, then `openDataCube`. `boot.ts` goes; what is left is
  building the services from `config.json` and the address, about 150 lines.
- **Python's tab and widget** (`demo/engine.ts`, `demo/widget.ts`): Python's services over the token-guarded HTTP or
  the widget's channel, then `openDataCube`. `engine-page.ts` and `engine-cube.ts` go. `ll.show(df)` becomes a page of
  one grid (§13, R1).
- **Python headless:** the in-process services and the app's actions without its windows (§8).
- **Legend Query** keeps embedding one `CubeApp` (a grid inside another app, not a DataCube page). Nothing changes for
  it.
- **`demo/page.ts`** (the F6 fixture, before `PageApp`) goes, with its `verify_page` test; the app's own page tests
  cover what it covered.

### 3.4 What differs by host is a capability, said by its services

A host offers what its services can do, and the app shows what is offered, exactly as `boot.ts` already hides Save
when the planner opens no files. Examples:
- a warehouse source while Python's host does not reach one yet;
- a share link, which works only where the page's address is reachable by others: the tab's, not Python's loopback
  (Python's page offers the page file instead);
- the browser's own keeping of settings, absent headless.

**A capability is never a second path through the app:** the same code runs, and an absent service means its
entries are not offered.

## 4. Where queries are planned and run

### 4.1 The planner: chosen on every host, the same way

The planes are the same three words everywhere:
- `local`: the compiler in this host's process. That is the tab's WebAssembly module in a browser tab, and the
  native library on a Python host.
- `remote`: legend-lite's server.
- `engine`: legend-engine.

The rule of 2026-09-30 holds on every host:
- **chosen once**, when the app opens, in one place per host: the tab's `?planner=`, and Python's
  `ll.show(page, planner=...)` or the session's setting;
- **always shown**, in the status bar's readout;
- **never substituted**: a planner that does not answer is said, and the page stops.

Choosing another plane in the menu opens the app again on it (the tab navigates; Python serves the page again on the
new plane). The guardrail that today allows exactly one reader of `?planner=` (`demo/boot.ts`) becomes one reader per
host, in that host's services module.

### 4.2 The database: where SQL runs, and where data goes in

The database is the host's: DuckDB-WASM in a browser tab, Python's DuckDB on a Python host. A warehouse table is still
read Live on its warehouse, as the source's own engine. **Every way data comes in goes into the host's database:**
- a file uploaded (`upload.ts`'s ingest);
- an example generated;
- a remote file mounted (`remote.ts`);
- a Snap (`SnapManager`): the copy goes into the configured Snap database, which is the host's.

The project's place is named after the database: `Browser` for DuckDB-WASM, `Python` for Python's DuckDB (§6). The
route already takes any place name (`CreateClass.java`, `where.as`).

### 4.3 On a Python host, the page sends Pure, never SQL

The Python show design's safety rule is kept: **only SQL that Python's configured planner wrote is ever run on Python's
DuckDB**. Python's DuckDB can read the machine's files, so a page must not be able to send it SQL.

- **Plan and run.** On a Python host the app's grids use the host's `{runner}` shape, which `CubeApp` already takes.
  The page sends Pure; Python plans with its chosen planner and runs the SQL itself:
  - `local`: the native library, as today;
  - `remote` or `engine`: Python asks that server for the plan (`generatePlan`), then runs it on its DuckDB.
- **Data in.** On a Python host it is a call to Python, with the bytes, the URL or the snap's source. **Python answers
  it with DataCube's own code**, run headless in Python (`upload.ts`, `remote.ts`, `SnapManager`) against its DuckDB,
  so the tab and Python ingest a file the same way (the headless design's rule: DataCube's logic is its TypeScript,
  wherever it runs). The page never writes the SQL.
- **The headless host gains a `fetch`,** so DataCube's `UpstreamPlanner` (the `remote` and `engine` planes) runs in
  Python too. Headless design §3 left fetch out "since nothing in Python needs DataCube's HTTP clients"; the planner
  menu now needs one.

The browser tab keeps planning and running in-page, as today. Its DuckDB is inside the browser's sandbox, so the page
may write SQL for it.

**The tenet, stated precisely** (the user asked, 2026-10-11, whether "everything goes through Python native" was already
decided: it was, as the Python show design's rule). With the planner chosen on every host (R3):
- **the SQL Python runs is always written by the planner the person chose, and asked for by Python, never by the
  page**;
- on the `local` plane that planner is Python's native library, so everything does go through it;
- on `remote` it is legend-lite's server;
- on `engine` it is legend-engine, exactly as the tab's `engine` plane runs legend-engine's SQL in the tab's DuckDB.

No Legend protocol logic is written in Python for it: the remote planner is called by DataCube's own TypeScript, run
headless in Python (R6), and Python only carries the request.

### 4.4 Sources by host

The picker's sections come from the engine service. A host offers what its database can open:

| Source | Browser tab | Python host |
|---|---|---|
| a frame (Python's dataframe) | — (only Python has it) | yes |
| a file from the computer, an example | yes, into DuckDB-WASM | yes, into Python's DuckDB (§4.3) |
| a remote Parquet/CSV/Iceberg file | yes | yes |
| a saved query | yes | yes, once Python has its query store and model home (§6, §7) |
| a warehouse table (Live, Snap into the host) | yes | later: Python reaching a warehouse is its own piece ("the shared warehouse from Python", IN_FLIGHT) |

A saved page reopens each source the way its host can. A page over a frame opens on the Python that serves the frame,
and nowhere else, as today.

## 5. Where pages live, and who else edits an open page

"Who owns the page" is two separate things:

1. **Where pages are saved.** That is the page service's store: upstream's DataCube query API (`CubeStore`), the one the
   tab already uses.
   - In the tab, IndexedDB.
   - On a Python host, a folder (beside the model home: §6, Q1), served by Python with the same API, so Open… and Save
     work the same. `page.save(path)` and `Page.load` stay as they are.
2. **Another editor of the open page.** That is an optional `live` part of the page service: the page as the other
   editor says it is, a signal when it changes, and the page's reports back.
   - Only Python is such an editor today: `ll.Page` in code, and `page.read()`.
   - Its protocol is today's: `page.json`, `version.json`, the 409 on a stale report.
   - The app follows and reports through it whatever the host. This is `engine-page.ts`'s `follow`, `#report` and
     `#reopen`, moved into the app. The model now goes through with the page document, which today's report drops.

## 6. Where classes go: an SDLC on every host

A page's classes are a **project**: a model with its own history. A page pins the revision it was built on, Studio
edits the project, Query reads it, and it is promoted and published as a project. The thing that keeps projects and
their revisions is the SDLC. So **every host's model home is an SDLC**, reached by the same `SdlcClient`:
- in the tab: the in-page SDLC (WebAssembly, IndexedDB) or a server, as today;
- **on a Python host and headless: Python's SDLC**, the same `Sdlc.java` rules and the Depot's, built into Python's
  native library over `GitStorage`.
  - Python serves it at its own address (`/sdlc/api`, `/depot/api`) to the browser side.
  - Headless calls it in-process, as the browser's in-page SDLC turns each `fetch` into one call (`wasm-server.ts`).

**Python's is the browser's, in Python's terms** (Q1, the user's principle: "the python analogous version of what we did
there so its all consistent"):

| The browser's in-page SDLC | Python's |
|---|---|
| this site's storage in this browser profile (IndexedDB), shared by every page and app of the site (DataCube, Studio, Query) | this user's storage on this machine: the operating system's per-user data folder (macOS `~/Library/Application Support/legend-lite`, Linux `~/.local/share/legend-lite`, Windows `%LOCALAPPDATA%\legend-lite`), shared by every notebook and script of the user; `ll.home()` says where |
| the SDLC's records (git objects, refs, projects) kept as IndexedDB records | the same records as a bare git repository there (`GitStorage`): the same keys on disk, and `git push` works on it |
| a project made quietly at the page's first class, named after the page, in DataCube's group and workspace | the same: the same app code makes it |
| a page points to its project, pinned to its revision | the same |
| leaving it is deliberate: a bundle when a page travels, a push when it is promoted to a team SDLC | the same |
| a link shared says "its classes stay in this browser" | a notebook handed over points to a project only its author has, said the same way, with the same remedies |
| IndexedDB serializes what several tabs write | a lock around each SDLC call, held across Python processes (two notebooks at once) |
| the tab's config points the model home at a server instead | `ll.home(path)` points it at a folder of one's choosing (Studio's level 1: a repository beside the notebook, kept in its git) or a server |

Saved pages (§5) and saved queries follow the same rule: Python's per-user folder, as the browser's saved pages and
query store are per site.

The page document's `model` keeps one form, the SDLC's: the project, the workspace, the package, each grid's table and
class, and (owed already: model design §6 says so, `PageModelRef` has no field for it) the revision the page is pinned
to.

**The model held in the page itself** becomes only what model design §6 calls the bundle: what a page carries when it
travels as one file. It is never a home. Python's `create_class`, `create_association` and `create_data_space` (built,
parked on `datacube/python-model-parked` with 13 tests) come back on this home, through the same code the tab's windows
use.

The model home is per origin in a browser. On a Python host it is a server's (Python's), so the notebook's origin does
not matter.

## 7. Studio and Query from Python

With §6, Python is a local legend-lite host: it answers `pure/v1` and the lite routes (as today), the SDLC and Depot
(§6), the saved-query store (`SavedQueries.java` over a folder), and the page store (§5). Then:
- Python serves the site (DataCube, Studio, Query) on its loopback address, each app pointed at Python's services;
- `ll.studio()` and `ll.query()` open them, as `ll.show` opens DataCube.

Studio and Query are the Studio line's apps. This design asks of them only what they already take: their services'
addresses (Studio design S1, S21: "what answers it is configuration").

## 8. Headless: the app without its screen

Headless needs the app's actions, not its windows. So **every window's action has a core of its own**, and the window
only asks the person:
- Create a Class… is the window plus `makeClass(request)`;
- Save… is the window plus `savePage(name)`;
- an export is its menu entry plus `exportPage(format)`.

The window calls the core, and so does headless. This is the headless design's step 3 (the page engine) done piece by
piece as each window moves into the app, rather than as one separate extraction.

`grid.create_class`, `page.create_association`, `page.create_data_space` and `page.export` are those cores, called with
Python's in-process services.

## 9. What moves where

| Today | Becomes |
|---|---|
| `demo/boot.ts`: the sources, the saved pages, share and start, the model actions, the planes, the Pure & SQL window | `src/app/` (the app), each part a module of its own |
| `demo/boot.ts`: DuckDB-WASM, the planner worker, IndexedDB, config.json, `?planner=` | `src/services/browser.ts` (the tab's services) |
| `demo/engine-page.ts`: following and reporting the page | the app's live page (§5) |
| `demo/engine-page.ts`, `demo/engine-cube.ts`: frames, `RemoteRun` to Python | `src/services/python-host.ts` (Python's services over HTTP or the widget channel) |
| `src/headless/` | the headless services (in-process) and the cores' entry points |
| `demo/main.ts`, `demo/engine.ts`, `demo/widget.ts` | each: build its services, `openDataCube` |
| `demo/page.ts` and `verify_page` | gone |
| `create-class.ts`, `model-actions.ts` | the app's model actions, over the model home service; each a core and a window |
| Python's `engine.py` | answers the new services' routes: the page store, the data-in calls, the SDLC and Depot, the query store, the plane |
| `native/` (the native library) | adds the SDLC and Depot rules over `GitStorage`, and the folder query store |

## 10. What it costs, and the risks

- **The largest refactor left.** `boot.ts` touches everything on screen. That is why it moves in steps (§12), the tab
  first, with no visible change in each step and the browser harnesses (`verify_*.mjs`) and screen tests green after
  each.
- **The widget's one file grows.** A blob-URL module loads no chunk, so the app's windows, lazy chunks in the tab, are
  inside the widget's file.
  - The widget measured 489 KB of its 500 KB budget when last raised (2026-10-09). The app's windows will push it past
    that; measured at step 2, the budget raised with its reason.
  - If the size bites, the loader can learn to fetch chunks over the channel. That is its own piece, later, on a
    measurement.
- **The engine page's startup** (300 KB) holds: the windows stay lazy chunks there, as in the tab.
- **Python's server grows** (§7). Each new route is the existing Java (SDLC, Depot, query store) or DataCube's own
  TypeScript run headless, never new logic in Python. Each is held to the same conformance suite as its other places:
  `sdlc-client`'s and `query-store`'s suites already run against more than one implementation.
- **Three runtimes in Python** (Python, the native library, V8) were measured and accepted in the headless design.
  This adds no fourth.

## 11. How it is kept proven

- **The app is tested once:** the screen tests (jsdom) and the browser harnesses open it over the tab's services, as
  they open `boot.ts` today.
- **Each host's services are held to one suite:** the page store, the model home and the query store each run their
  existing conformance suite against every place that answers them, Python's included.
- **Python's host is tested through the app:**
  - `python_engine_test` opens the app over Python's services, in a tab and in the widget;
  - the cases it runs today stay;
  - the app's features are added to it (a file uploaded into Python's DuckDB, a class made, a page saved and
    opened).
- **The headless lane** runs the cores, as it runs the grid today.

## 12. The plan (each step lands on its own)

1. **The tab is the app's first host.** No visible change. Four landings:
   - **1a.** `Services` and the browser's services. `boot.ts` builds them and reads its DuckDB, planner and stores
     through them.
   - **1b.** The sources (picker, opening, reopening) move into the app.
   - **1c.** The saved pages, share, start, blank page, "changed since saved", and leaving.
   - **1d.** The model actions (each a core and a window), the planes, the Pure & SQL window. `boot.ts` is then the
     tab's services. `demo/page.ts` goes, and with it the dead `if (models)` branches (both planners always return
     `models`).
2. **Python's pages open the app.** Four landings:
   - **2a.** `engine-page.ts` and `engine-cube.ts` go: the app over Python's services, the live page, `ll.show(df)` a
     page of one grid. Python's pages gain every app feature that needs no new service: the Pure & SQL window,
     settings, renaming, "changed".
   - **2b.** Data in: upload, examples, remote files and Snap into Python's DuckDB, answered by DataCube's code headless.
   - **2c.** The planes on Python: the headless `fetch`; the remote planners called by Python.
   - **2d.** The page store on Python: Open… and Save into a folder.
3. **Python's model home.**
   - The SDLC, Depot and query store in the native library over Python's per-user folders (§6), served by Python.
   - Nothing is built for Create a Class… itself: the app's model actions are already on Python's pages from step 2,
     and appear once this service answers.
   - `grid.create_class` and the rest come back from the parked branch, as the cores of those same actions (§8).

**On Python, the work is services, never features** (the user, 2026-10-11: "i thought we were doing all of this work so
python gets all of this for 'free'"). Every feature's code comes with the app. What a host must build is only the
service a feature reads or writes: a database for upload, a page store for Save, a model home for Create a Class….
Until the service answers, its entries are not offered (§3.4).
4. **Headless pages:** the cores, `page.export`.
5. **Studio and Query from Python:** the site served by Python; `ll.studio()`, `ll.query()`. Built with the Studio line.

Each landing goes through an audit, the local gate, CI on the affected lanes on every platform, then main; Bazel
changes are reviewed by the Compiler Rewrite line, and planner-boundary changes by the Plan Gen line, as always.

## 13. Ruled (the user, 2026-10-11: "agree on all 6 ruling"; each supersedes something agreed)

- **R1. One app for every host.** It supersedes:
  - the Python show design's "a page of its own … so the demo's `boot.ts` … untouched";
  - the pages design's "a cube embedded alone (… a notebook …) stays a `CubeApp`".

  `ll.show(df)` becomes a page of one grid: its lone grid sits in the page's bar, as a one-grid page's does in the tab.
  Legend Query's embedded grid is unchanged.
- **R2. Python's model home is an SDLC** over a git folder (§6). It supersedes model design §8, §14 ("Python's a
  bundle by default and a project folder on request") and §15.5 ("Python's page is its model's home"). The bundle stays
  as the travel form.
- **R3. The planner is chosen on every host** (§4.1). It supersedes `guardrails.test.ts`'s single reader of
  `?planner=` in `demo/boot.ts`, replaced by one reader per host. The rest of the 2026-09-30 rule stays: chosen once,
  shown, never substituted.
- **R4. On a Python host the page sends Pure, never SQL** (§4.3). This keeps the Python show design's rule, already a
  tenet, stated precisely for every plane in §4.3. It is why the planner on a Python host is called by Python, not by
  the page. The alternative (the page plans in the browser and sends SQL to Python's DuckDB) would let any page that has
  the token run SQL against the machine's files.
- **R5. Data in goes to the host's database**, Snap included. It supersedes the show design's "the only feature that
  mode refuses is Snap into the tab, by design": on a Python host, Snap copies into Python's DuckDB.
- **R6. The headless host gains `fetch`** (§4.3), for the remote planners. It supersedes headless design §3's "the
  fetch API is left out".

## 14. The two questions, answered

- **Q1. Where Python's model home and page folder live by default.** The user's principle (2026-10-11): "python should
  mimic as close a s posible/as much as makes sense the decisions we made for browser/indeDB SDLC - it should be the
  python analogous version of what we did there so its all consistent". Applied: **the per-user store** (§6's table),
  the analogue of the browser's per-site storage. A folder beside the notebook is the opt-in (`ll.home(path)`), the
  analogue of pointing the tab at a server. This replaces the first revision's recommendation of a `legend/` folder
  beside the notebook by default, which no browser decision mirrors.
- **Q2. The order of steps 2 and 3.** The user, 2026-10-11: "i think it should become the app before yes". Step 2
  lands before Python has a model home; the model actions appear on Python's pages when step 3's service answers.
