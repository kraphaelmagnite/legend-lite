# DataCube headless in Python: DataCube's own code, embedded, with no WebAssembly (design, 2026-10-11)

**Status: agreed by the user, 2026-10-11. Reviewed by the Plan Gen / Exec Split line (yes, with one gap, closed in
§3: the plan of a query over a held model natively) and by the Compiler Rewrite line (no blocker; the lane in
`//gates:warehouse`, and the build's shape, §6). Steps 0, 1, 2 and 4 built on branch datacube-headless (§11, §12).**
The user, on the measurement: "this is so awesome lets do it"; on §10: "yes one one package, v8 for sure, yes do this
before create*; yes rename would helpful". Builds on `docs/DATACUBE_PYTHON_SHOW_DESIGN_2026_10_08.md` (the engine in Python, the notebook cube),
`docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md` (pages built in code) and `docs/DATACUBE_MODEL_DESIGN_2026_10_10.md`
(§13's model plan; §15.5's Python). Evidence: `docs/datacube-headless-evidence-2026-10-11/`.

## 0. In one paragraph

DataCube's meaning lives in its own TypeScript, which turns a grid's settings into Pure, runs a grid's levels, totals
and pivots, and writes exports. That code needs two things, a **planner** and a **database**. It does not need a
browser, and it does not need WebAssembly. In the browser both happen to be WebAssembly: legend-lite's planner built by
TeaVM, and DuckDB-WASM. In Python they are legend-lite's native library (the same Java, built by GraalVM) and Python's
own DuckDB. So Python runs DataCube's non-screen code in an embedded JavaScript engine (V8, through `mini-racer`). It
plans through the native library and runs its queries on Python's DuckDB.

Measured: 1,123 of the 1,138 tests of DataCube's non-screen code pass in V8 as in Node. All 15 that do not pass need the
network (the fetch API) or are one test's own plumbing; none is DataCube misbehaving. There is one implementation of
DataCube's meaning, never copied into Python. It is reachable from scripts, CI and notebooks, without a screen.

## 1. What binds this design (the user's rulings, 2026-10-11)

- **The feature boundary is the relation.** Create a Class takes a relation, which is legend-lite's writer in core.
  DataCube's job is turning clicks into a relation. Python's job is turning code into one, or using a frame as it is.
- **DataCube's logic is DataCube's TypeScript, wherever it runs.** It is not copied into Python and not ported to Java.
  Python reuses it by running it.
- **Faithful to production.** The code under test runs the production paths. Test tooling may differ, but only in
  how it builds its inputs, never in what it tests. No recorded answers stand in for a computation.
- **The tests' synchronous fixture parses are a test habit, not a product need.** They become asynchronous as part of
  this design, not before (§6).
- **No WebAssembly in the Python process.**

## 2. What was measured (`docs/datacube-headless-evidence-2026-10-11/README.md`)

- **The query builder.** In V8 and QuickJS it is byte-identical to Node: on the WASM differential's 49 grids, on the
  same grids through the page-document path, and on 15,000 fuzzed builds.
- **DataCube's own tests, three engines.** In the 67 files of non-screen code: Node 1,138 passing, V8 1,123, QuickJS
  1,101; none passing outside Node that fails in it.
  - V8 loses 15: 14 need the fetch API (the remote planner's, the engine executor's and the warehouse sign-in's HTTP
    clients), and 1 is a test reading a DuckDB file synchronously.
  - QuickJS loses those and 23 more, for `Intl` (number and date formatting).
- **The engines.**
  - V8 (`mini-racer` 0.14, V8 14.4): wheels for all five targets, +15-22 MB, +51 MB of memory, `Intl` built in,
    about 7x faster than QuickJS on heavy JavaScript.
  - quickjs-ng: +0.5-2.6 MB, +7 MB; no `Intl`; no macOS x86_64 wheel; its binding one person's.
- **The choice: V8.** It formats as the browser does, it is fast, and it is packaged for every target. Its cost is
  size, and memory beside Python.

## 3. The shape

```
Python process
├── legend_lite (native library, GraalVM)   the compiler and planner: the same Java as the tab's WASM module
├── duckdb (Python)                          the database: the frames, the classes' tables
└── V8 (mini-racer), on its own thread       DataCube's non-screen bundle
      ├── WasmPlanner ── worker transport ──► native library      (asynchronous; the browser's production path)
      ├── DuckDbEngine ── connection ──────► Python's DuckDB      (asynchronous; Arrow IPC)
      └── the host's web layer               URL, TextEncoder/Decoder, AbortController, events, timers, crypto.subtle
```

- **The planner.** It is DataCube's `WasmPlanner`, unchanged, over its worker transport. The browser already runs its
  module in a Web Worker (`planner-worker.ts`); here the "worker" is the host, which answers each message with the
  native library's function of the same name (`lite_hold_model`, `lite_plan_json_held`, ...).
  - **Each request kind is answered by the same `Boundary` function in both hosts** (the Plan Gen line's review). The
    worker's nine kinds (`hold`, `release`, `relationType`, `tableModel`, `catalogColumnsSql`, `planJson`, `pureV1`,
    `testData`, `plan`) are one exported TypeScript type, which both hosts are checked against (§6).
  - Three had no native function, and have one beside the held functions in `native/.../nativelib/Compiler.java`
    (step 1c): `plan`, as `lite_plan_text_held` over `Boundary.planHeld` (the query planned against the model held,
    compiled once); `relationType`, as `lite_relation_type_text_held` over `Boundary.relationTypeHeld` (Pure text: the
    library's `lite_relation_type_held` reads a lambda's JSON); and `testData`, as `lite_test_data_sql`. The
    measurement answered `plan` by `lite_plan_text` over the held model's text, which compiles the whole model again
    for every query, and `relationType` by the JSON function: neither production's path. The design does neither.
  - `WasmPlanner` names how the browser delivers its module, not what it is. It is renamed `LitePlanner` (ruled, §10),
    with the Plan Gen / Exec Split line, whose boundary it is.
- **The database.** It is DataCube's `DuckDbEngine`, unchanged, over a connection whose `query(sql)` runs on Python's
  DuckDB and answers Arrow IPC, read by `apache-arrow`. DuckDB-WASM's file functions (`registerFileBuffer`, ...) are
  answered by writing into a folder of the host's own, which Python's DuckDB reads.
- **Big data does not cross into JavaScript.** A grid's view goes through DataCube's code as a grid shows it: levels
  capped, as on screen. A whole result goes another way. For `grid.to_pandas()` DataCube answers the query, and
  Python's DuckDB returns the Arrow table to Python directly.
- **The web layer** is a small module of the host's. The browser supplies these things and V8 alone does not:
  - `URL` and `URLSearchParams` (`whatwg-url`, the specification's reference implementation);
  - `TextEncoder` and `TextDecoder` (UTF-8);
  - `EventTarget`, `Event`, `DOMException`, `AbortController` and `AbortSignal`;
  - timers, on the host's own loop;
  - `crypto.subtle.digest`, by Python's `hashlib`.

  Each is held to its standard by a conformance test of its own (§6). The fetch API is left out, since nothing in
  Python needs DataCube's HTTP clients.

## 4. The headless API (what a developer types)

```python
page = ll.Page('Trades')
trades = page.grid(trades_df, name='trades').filter(...).calculate('big', 'x|$x.notional > 100').group('region')

trades.view()               # what the grid shows: its rows, levels, totals and truncation, as DataCube computes them
trades.relation             # its rows as a relation (Pure), at the finest level, as Create a Class makes it
trades.to_pandas()          # the relation's whole result, from Python's DuckDB
trades.create_class('Trade', package='trades')       # a class of the grid as DataCube means it, in the same cell
page.export('trades.xlsx')  # a page's export, as the screen writes it
```

- **One grid** is almost there. A grid's run loop is already screen-free: `CubeController.run(state)` produces its view
  (rows, tree, truncation, total rows), driven by the state owner (`state-owner.ts`); the screen (`app.ts`) wires them
  to the DOM. The headless grid assembles the two, behind an entry point of the bundle's.
- **A page** needs more. Its orchestration of several grids, their charts and sheets is in screen modules today
  (`page/page-app.ts`, `page/cube-page.ts`). The design's second step moves what is not drawing into a screen-free
  page engine, which the screen and Python then share. That is the largest piece of new work, and a good refactor in
  its own right.
- **`create_class` from a grid now works in code, in the same cell.** It uses DataCube's builder, so the grid's
  filters and calculated columns go into the class's function. This removes §15.5's limit, under which Python made
  classes from frames or written relations only. Those two ways stay too.

## 5. The asynchronous boundary, and notebooks

- `mini-racer` runs V8 on its own thread, and every call from JavaScript into Python is asynchronous (a Promise).
  DataCube's production paths are asynchronous already, so this costs nothing in them.
- Python's API is **synchronous** to the developer and safe in Jupyter. legend-lite owns one event loop on a
  background thread for the engine; a call such as `grid.view()` is posted to it and waited for. It never calls
  `asyncio.run()`, which fails inside Jupyter's own running loop. Asynchronous variants (`await grid.view_async()`) are
  offered for code already in a loop.
- **One engine per process, one call at a time** into DataCube's state, as on screen, where one state owner holds a
  grid's state. `mini-racer` serializes calls into one context (measured: one context from four threads, correct). Making
  contexts on several threads at once crashed it (measured), so the engine's context is made once, on its own thread.
  Its memory is capped (`set_hard_memory_limit`), so a runaway grid fails its call instead of the process.

## 6. Keeping it proven: the lane

The measurement becomes a test, `//datacube:headless_test`, run on all three platforms:

- DataCube's non-screen test files, run in V8 by Python, through the production paths of §3.
- **The test helpers made asynchronous**, landing for real: `datacube/test/lite-compiler.ts` and
  `pure-protocol/test/lite.ts`, as the overlay did (`overlay.patch`: their callers in 18 files, 232 awaits once a wrapper
  that awaits is awaited in turn, by the codemod and three hand fixes). The same tests then run unchanged in Node and in V8.
- **The host's hooks become supported options** instead of the overlay's patches:
  - the planner's worker factory: `WasmPlanner`'s option, not a global `Worker`;
  - the DuckDB connection the tests open;
  - the runfiles placeholder.
- **Conformance tests for the web layer**, each against its standard's own test vectors where they exist (`URL`:
  whatwg-url's; UTF-8: the Encoding standard's).
- **The worker contract's guard** (the real guard of §7's check): a lane test that the request kinds' type,
  `planner-worker.ts`'s switch and the Python host's table are the same set, so a new kind fails both hosts at once.
- **A guard:** the headless bundle imports nothing of the DOM or of Node (esbuild `--platform=neutral`, and the lane's
  run).
- **An opportunity, not a commitment:** with the lane, DataCube's unit tests no longer need Node to run (the "no Node in
  legend-lite" goal).

**In the build** (the Compiler Rewrite line's review, 2026-10-11):

- **Its lane is `//gates:warehouse`**, beside `//datacube:python_engine_test`, not `datacube` and not a lane of its own.
  The test loads the native library (`//native:compiler`), a whole-machine native-image build. The warehouse lane builds
  that image in a step of its own before its tests; the `datacube` lane is in `//gates:local` and does not build it.
  So the test is not in the local gate: it is run by name (`bazel test //datacube:headless_test`) before a push, as
  `//python`'s tests are. In CI it runs on all three platforms, and linux-arm runs the warehouse lane alone, so four
  of the five wheel targets are tested (macOS x86_64 has no runner, as for the wheel today).
- **`mini-racer==0.14.1`** in `tools/python/requirements.in`, with a dated reason, relocked by
  `bazel run //tools/python:requirements.update`. `MODULE.bazel.lock` moves with it, held by
  `//tools/python:lock_matches_requirements`. The test takes `deps = ["@pypi//mini_racer"]`, as `//python`'s take
  `@pypi//duckdb`: Bazel fetches the wheel by its lock hash and unpacks it at fetch time, so the test installs
  nothing, and V8 and its ICU data come inside the wheel. `target_compatible_with =
  compatible_with(COMPILER_LIBRARY_PLATFORMS)`, so Windows on Arm skips it rather than failing it.
- **The bundles:** one testonly `esbuild_bundle` (`--platform=neutral`), the test files as its entry points into one
  output directory (one cached action). The fetch-needing files are left out in BUILD by name, with the reason, so the
  exclusion is the build's, not the run's. `apache-arrow` comes through `//engine-client:engine_client`. The `node:stream`
  stub is a file of the package named as an esbuild alias, and the no-Node guard knows of it. `whatwg-url` goes into
  `datacube/package.json`, relocked through Bazel's pnpm and held by `//tools/js:lock_matches_package_json_test`.
- **The test:** a `py_test`, `size = "medium"`; deps `//python:compiler`, `@pypi//mini_racer`, `@pypi//duckdb`,
  `@pypi//pyarrow`, `@rules_python//python/runfiles`; data `//native:compiler` and the bundles; env as
  `python/BUILD.bazel`'s `_ENV`; its DuckDB files under `TEST_TMPDIR`. No path, program or PATH lookup.
- **Windows:** the native library and duckdb's extension load under Bazel on Windows already. The first Windows run
  checks that `py_mini_racer` loads its `mini_racer.dll` (with `icudtl.dat` and the snapshot beside it, and the MSVC
  runtime, which windows-2022 has). If it does not, the fix is how the DLL is loaded, never a host install.

## 7. Packaging

- **One package with an extra: `pip install legend-lite[datacube]`** (ruled, §10). The extra
  brings `mini-racer` (V8), and the wheel carries DataCube's headless bundle (small), built by Bazel's esbuild.
- Two wheels (`legend-lite` and `legend-lite-datacube`) would keep the same parts apart. But the bundle and the native
  library must agree on their interface exactly, so two wheels would need a pinned pair and a version handshake. One
  package has one version.
- The existing `notebook` extra is the same pattern. `//python:wheel_test`'s offline install from Bazel's downloads
  gains `mini_racer` in its package lists, per platform, as the notebook's lists are.
- **The extra raises the glibc floor for its users only:** `mini-racer`'s Linux wheels are `manylinux_2_27`, and
  legend-lite's wheel says `2_17`. The extra's documentation says so.
- **An interface check at start, by lists, not a version** (the Plan Gen line's review: `Boundary` has no version, and a
  hand-kept one gets forgotten). The bundle lists the request kinds it sends (`REQUEST_KINDS`); the host's one table
  of kind to native function (`KINDS`, `legend_lite/_headless.py`) is checked against it, and each function against
  the library's own export table (`Library.exports`: the linker's list, none kept by hand; agreed with the Plan Gen
  line in place of a `lite_interface()`). A mismatch fails at start, with both sides named.

## 8. What it costs, and the risks

- **The page engine (§4)** is the largest new piece: the screen's orchestration moved into screen-free modules.
- **Three runtimes in one process:** Python, GraalVM's native library and V8. That means +51 MB, start-up of the order
  of a hundred milliseconds (to be measured), and errors that cross three languages. A V8 out-of-memory failure kills
  the process unless capped (§5).
- **The same DataCube is not the same database.** The browser's DuckDB-WASM and Python's DuckDB are different
  releases: an edge-case type or an error's words can differ. `Intl` follows the ICU data bundled with `mini-racer`'s
  V8. It matched Node's in all 23 measured formatting tests, and may differ from a user's Chrome in an edge case.
- **A dependency to own:** `mini-racer`, its V8 upgrades and security releases at its pace. The JavaScript run is ours,
  never a user's, which lowers the exposure.
- **Two worker kinds have no native function** (`plan` and `testData`, §3). They are added to the native library beside
  its other held functions, so the headless planner answers what the browser's does, by the same Java.
- **Size:** +15-22 MB for the extra's users.

## 9. The plan (in order; each a landing of its own)

0. **The rename** (§3): `WasmPlanner` to `LitePlanner`, `wasm-planner.ts` to `lite-planner.ts`, with its test, its
   callers and the comments that name it (in `wasm/`'s `Boundary.java` and `README.md`, and `tools/teavm/TeaVmCompile.java`,
   comment-only, told to the Compiler Rewrite line). A landing of its own, so the lane's diff is only the lane. The
   name agreed by the Plan Gen / Exec Split line.
1. **The lane (§6).**
   - The test helpers made asynchronous; the hooks made supported options; the web layer with its conformance tests;
     the worker's request kinds as one type, with their guard; `lite_plan_text_held`, `lite_relation_type_text_held`
     and `lite_test_data_sql` natively (entry points in `native/` over `Boundary`'s existing functions). Built: §11.
   - Reviews: the Compiler Rewrite line for the BUILD diff and the new pinned Python dependency (`mini-racer` in
     `@pypi`); the Plan Gen / Exec Split line for the planner's hooks, the worker contract and the native entry points.
   - Before the push: the lane query (`tests(//...) except tests(//gates:lanes) except tests(//gates:heavy)` empty),
     `bazel test //datacube:headless_test` by name, and CI on the branch with `-f gates=warehouse,checks,datacube -f
     platforms=all`. The lock change rolls CI's downloads cache key, so that first run downloads everything again,
     once.
2. **The headless grid** (§4: the bundle's entry point; `grid.view()`, `grid.relation`, `grid.to_pandas()`), and the
   engine's loop thread (§5).
3. **The page engine** (§4): the page's orchestration moved out of the screen, shared by the screen and Python; then
   `page.export(...)`.
4. **Packaging** (§7): the `datacube` extra, the interface check, the wheels on all five targets.
5. **The model plan's Python step** (§15.5) rides on it: `grid.create_class`, `create_association` and
   `create_data_space`, from grids, relations and frames.

**Where it goes relative to the model plan** (ruled, §10): before §15.5's Python calls, since `grid.create_class`
depends on steps 1-2. Steps 3-4 can follow the model plan's step 3 (dashboards on classes).

## 10. Ruled (the user, 2026-10-11)

1. **One package**, with a `datacube` extra, not two wheels.
2. **V8** (`mini-racer`), at its size (+15-22 MB, +51 MB of memory), not QuickJS.
3. **Before the model plan's Python calls** (§15.5): this design's steps 0-2 first.
4. **The rename**, `WasmPlanner` to `LitePlanner`, with the Plan Gen / Exec Split line (agreed by it, 2026-10-11).

## 11. Step 1, as built (2026-10-11, on branch datacube-headless)

The user, 2026-10-11: "just do everything on the branch first with multiple commits then push". Step 1 is six
commits; what they settled beyond §3 and §6:

- **1a, the test helpers asynchronous** (6973ef19f). `lite-compiler.ts` and `pure-protocol/test/lite.ts` await each
  answer; their callers in 18 files were converted by the measurement's codemod, its three flagged spots by hand, and
  ten return types the type checker refused by hand. Every changed target runs the same number of tests before and after
  (`docs/datacube-headless-evidence-2026-10-11/step1a/`).
- **1b, the worker contract** (4d46a0e57). `datacube/src/planner-protocol.ts`: `REQUEST_KINDS` and one union tied to it at
  compile time, so planner-worker.ts's switch is checked by tsc. `LitePlanner`'s `worker` option, a worker its host makes.
- **1c, the native entry points** (d5a1f70e1). The three of §3, tested against the corpus differential over a held model.
- **1d, the host** (ad7719bde). DataCube's side, `datacube/src/headless/`:
  - The web layer to the standards' own algorithms, not the measurement's approximations. For example, UTF-8 decoding
    gives one U+FFFD per maximal invalid subpart; capture listeners run first at the target; a passive listener cannot
    cancel. It is held case for case to Node's built-ins by `headless-web.test.ts`, and to the DOM standard alone where
    Node 22 departs from it.
  - URL from whatwg-url, now a dependency of DataCube's.
  - The planner's worker and the DuckDB connection, over the host's calls.

  Python's side is `legend_lite/_headless.py`. It runs V8 on a loop of legend-lite's own, with WebAssembly deleted
  before anything loads.
- **1e, the tests' host** (9c3923ce2). `#test-host`, a package.json "imports" entry with a `headless` condition: in Node,
  the module in-thread and DuckDB-WASM; in the lane, the host's. The swap is the build's. The fixtures are parsed and
  printed by the product's planner (`LitePlanner.parse`/`compose`). **The host contract:** Python defines its calls as
  `legendLiteHost` before a bundle loads, as a browser's globals are there before a page's scripts. A module may plan as
  it loads.
- **1f, the lane** (aff16009f). `//datacube:headless_test`, in `//gates:warehouse`:
  - **53 test files, 985 of 985 tests, about 5 s**, each file in a Headless of its own, plus the host's own checks over
    the product's bundle.
  - **Left out, by name in BUILD:** the 4 files that test DataCube's HTTP clients (no fetch here); and 10 files that mix
    screen tests (jsdom, or Node's own `fileURLToPath`/`node:crypto` as a reference) with non-screen ones. **A follow-up
    splits those 10** into a screen file and a non-screen file, and the non-screen halves join the lane.
  - **The stand-ins that judge the tests are held to Node's own in Node's lane:**
    - `node-assert.ts` against `node:assert/strict`, over 66 x 66 value pairs and every expectation kind;
    - `node-test.ts` against node:test's run of the same suite.

    Two of the measurement's runner's errors are fixed: an async describe's tests are its own, and `afterEach` runs
    after a failing test.
  - **mini-racer 0.14's `setTimeout` throws for a delay of 0.** It waits on `Atomics.waitAsync`, which then answers at
    once. The host holds each delay to at least a millisecond. To report upstream.
  - **Made web-standard in four tests:** two describes no longer awaited, and two load-time fixtures moved into `before`
    hooks, because a script bundle cannot await at load. Three tests that read Node's `Buffer` now use `atob` and
    `TextDecoder`.

## 12. Steps 2 and 4, as built (2026-10-11, on branch datacube-headless)

- **Step 2, the headless grid** (15099302d).
  - `datacube/src/headless/grid.ts` holds the bundle's `gridView`, `gridRelation` and `gridSql` (`gridSql` gone
    2026-10-12: `Grid.to_pandas()` plans `gridRelation`'s Pure in Python and types its rows by the plan,
    docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §15.3a). A page's cube is opened
    over its source as the compiler types it now (`openCube`, as a page opens a saved cube). It is run by
    `CubeController.run` on the tab's own pair: `LitePlanner` over the host's worker, `DuckDbEngine` over the host's
    connection.
  - Nothing is held between calls: the cube document Python's page keeps is the grid's state.
  - Python: `Grid.view()` gives a `GridView` (columns, rows, tree, what the cap cut, the query and its SQL,
    `to_pandas()`); `Grid.relation` gives Pure; `Grid.to_pandas()` gives every row of the relation from the frames'
    own database, never through V8.
  - The session's `Headless` is made on first use over its `Frames`' connection, where the frames are.
  - **What "the finest level" is:** the grid's innermost grouping, as Create a Class makes a class of it
    (`classLambda`). A grouped grid's relation groups by its innermost group, so a class of it has one row per group;
    a flat grid's relation is its rows. (A first docstring of `Grid.relation` said "before any grouping"; it was
    wrong, and the tests say so.)
  - **A view is what the grid shows:** a grouped grid's first column is `__tree`, its groups' labels, as on screen.
  - **Values cross exactly:** an integer past 2^53 and a non-finite float are tagged, and each value is typed by its
    column in Python (a Decimal a `Decimal`, a Float a `float`).
  - Safe inside a running event loop (a notebook cell's): `//python:headless_test`.
- **Step 4, packaging** (ede6ce1f9), **ahead of step 3:** it needs nothing of the page engine, and without it the
  feature cannot be used outside this repository.
  - The wheel carries the bundle (`legend_lite/_datacube/`) and `_headless.py`.
  - `pip install legend-lite[datacube]` adds mini-racer.
  - `//python:wheel_test` views a grid from an offline install of the wheel alone.
- **Left for later:** step 3, the page engine (the page's orchestration out of the screen, then `page.export`); step 5,
  the model plan's Python `create_*` (§15.5), which rides on step 2. A grid's view from Python is read-only for now: a
  grid is changed through the page builder (`grid.filter`, `grid.group`, ...), then viewed.

## 13. Every non-screen test headless (2026-10-11, 7d973ea9e)

The user: "we have to make sure we do the test split for all headless tests to run so we are not coding blind". §11's
14 left-out files are each split in two, the half that needs more than V8 named for why: `<name>-dom` (the screen,
jsdom), `<name>-http` (DataCube's HTTP clients over a stubbed fetch), `<name>-node` (Node as the reference). The rest
keeps its name and runs in the lane.

- **The lane: 67 files, 1,122 tests, every one passing**, the same count Node runs for those files. The 28 split files
  hold 324 tests in Node, as the 14 did.
- **Left out by name:** the four `-http` halves (the host has no fetch), `chart-echarts` (ECharts draws on a DOM
  canvas), and the lane's own three conformance tests. The `-dom` and `-node` halves are left out by what they import,
  which the roster guard reads.
- **node:test's mock timers** are in the stand-in now (setTimeout and setInterval), held to Node's own by one scenario.
  One quirk of Node's own mock is not copied: an interval cleared inside its own callback runs again there.
- **mini-racer's timer defect** is reported upstream (https://github.com/bpcreech/PyMiniRacer/issues/97). It is not by
  design (ECMAScript defines both results), not reported before, and not fixed in 0.14.1 or on main. The host's floor
  goes when the pin moves past the fix; the lane tests every timeout form.

## 14. The branch's audit (2026-10-11): 2 blockers, 9 should-fix, every one answered

- **B1, the configuration.** A headless grid ignored its configuration (its row cap, each column's aggregate, a hidden
  column), so Python could show other numbers than the tab. It now folds the configuration into its query as the tab's
  state owner does (`cube-state.ts` `fold`). Its columns are laid out by `shownColumns`, which the tab's repaint calls
  too: one function, moved out of `app.ts`.
- **B2, two locks in opposite orders.** `Grid.view` gets the session's engine before it holds the frames still, the order
  a frame's registering takes them. A test reads a grid while another thread registers frames.
- **The judges.**
  - The assertion stand-in's deep equality kept a failed pair as equal (a Set's members sharing an object). It now
    lets a pair go when its comparison ends, as Node does; the case is in the corpus.
  - The node:test stand-in is now compared live with Node's own node:test, which runs the same suite in a child Node.
    That found one more difference, fixed: a suite's `after` hooks run when its `before` fails.
  - The lane asserts that no test is skipped.
  - One gap is stated in the stand-in: a promise rejected and never handled fails a file in Node, not here.
- **The host.**
  - Its calls run one at a time.
  - A call past the memory cap breaks the engine, and the session makes another.
  - A call can take a timeout.
  - The native library sets up each function on first use, so the start-up check names a missing one instead of
    legend-lite failing to load.
- **Two things said rather than built:**
  - A connection given to `Headless` (the frames') is left as it is, so a file registered meanwhile is not on its search
    path. No grid registers one.
  - The host's database answers a query whole (no `send`), so a superseded query is not cancelled between batches as in
    the tab. `duckdb-cancel`, in the lane, tests `DuckDbEngine` over a connection that has `send`, not this host's.
