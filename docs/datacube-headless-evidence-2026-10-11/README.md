# DataCube headless in Python: the measurement (2026-10-11)

The evidence for `docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md`. The question: can DataCube's non-screen
TypeScript run inside a Python process, with no WebAssembly? It runs in an embedded JavaScript engine, planning by
legend-lite's native library, its queries run by Python's own DuckDB.

Everything here was run by hand on macOS arm64, Python 3.12, outside any Bazel target. Local paths are written
`<checkout>`, `<output_base>`, `<tmp>`.

## What was run, in order

1. **The query builder alone** (`harness/entry.ts`, `entry-docs.ts`, `entry-fuzz.ts`; reference `ref.js`, `ref-n.js`;
   engines `run_engines.py`, `run_n.py`). DataCube's builder (`datacube/src/query.ts`), bundled into one file by
   esbuild, was run in Node and in each engine, and its output compared byte for byte:
   - the WASM differential's 49 grids, every query kind: identical in quickjs-ng, V8 (mini-racer) and dukpy;
   - the same 49 grids saved as page documents, read back and made class queries (39 queries, 10 refusals):
     identical in quickjs-ng and V8. **dukpy fails**: its engine has no `JSON.parse` source-text access, which the
     page-document reader needs to keep numbers exact;
   - 3,000 grids from the fuzz generator (copied verbatim from `datacube/test/fuzz.test.ts` by `entry-fuzz.ts`), five
     queries each, 15,000 builds (4,657 queries, 9,340 refusals, 1,003 none): identical in quickjs-ng (673 ms) and V8
     (207 ms).
2. **The engines' packaging** (`harness/wheels.py` over PyPI's metadata; `probe.py`, `bigint.py`, `threads2.py`,
   `memory.py`):

   | engine | wheels (the five targets, CPython 3.12+) | size | memory | notes |
   |---|---|---|---|---|
   | quickjs-ng 0.17.0.1 | 4 of 5 (no macOS x86_64), abi3 | 0.5-2.6 MB | +7 MB | no Intl, URL, TextEncoder, timers; one context crashes used by two threads at once |
   | mini-racer 0.14.1 (V8 14.4) | 5 of 5, py3-none | 15-22 MB | +51 MB | Intl (ICU bundled); calls into Python asynchronous only; creating contexts on several threads at once crashes |
   | dukpy 0.6.0 | 5 of 5, per CPython version | 2.6-4.3 MB | -- | now QuickJS inside; fails the page-document path |
   | pythonmonkey 1.3.2 | -- | -- | -- | needs `pminit` (npm at install); does not import without it |
   | quickjs 1.19.4 | no Linux arm64; last release 2023 | -- | -- | -- |
   | stpyv8 13.1 | no Linux arm64 | 14-25 MB | -- | Python callbacks synchronous; its ICU data must be installed under the user's home or `/Library` |

   legend-lite's own wheel is 18.5 MB (macOS arm64).
3. **DataCube's own test suite, in three engines** (`harness/suite.py`, `run-node.mjs`, `run_qjs.py`, `run_v8.py`,
   `analyse.py`; stand-ins in `shim/`; the host's web layer in `host/`; the overlay's changes in `overlay.patch`;
   results in `results/suite-three-engines.tsv`, one line per test). Each of `datacube/test/*.test.ts` (105 files) was bundled once
   from an overlay copy of the sources and run in Node (the reference), quickjs-ng and V8, with the same stand-ins for
   `node:test` and `node:assert`, and Node-only modules (`node:fs`, `jsdom`, ...) stubbed to fail when used:

   | | Node | quickjs-ng | V8 (mini-racer) |
   |---|---|---|---|
   | tests passing (the 67 files of non-screen code) | 1,138 | 1,101 | **1,123** |
   | passing in Node only | -- | 37: 23 `Intl`, 14 the fetch API | 15: 14 the fetch API, 1 a test's synchronous DuckDB file read |
   | passing here, not in Node | -- | 0 | 0 |
   | the whole run | 50 s | 40 s | 23 s |
   | the page-layout tests (`bands`) | 2.0 s | 21.7 s | 3.0 s |

   The other 38 files run in no engine here: 29 test the screen (`jsdom`, directly or through a helper, and the chart
   renderer), 9 read the repository's own files (source scanners, guards, a sample project).

## How each engine was hosted

- **The planner**: DataCube's `WasmPlanner`, unchanged. In Node, the WASM module (`//wasm:planner_dir`) through its
  `loadRuntime` option. In QuickJS, the same option, handed a module whose functions call the native library
  (`lite_hold_model`, `lite_plan_json_held`, `lite_relation_type_held`, `lite_table_model`, `lite_catalog_columns_sql`,
  `lite_pure_v1`; `planHeldOrError` by `lite_plan_text` over the held model's text; no native `testDataSql`). In V8,
  its **worker transport** (`workerUrl`), the browser's production path: a `Worker` stand-in whose messages the native
  library answers, asynchronously, as `planner-worker.ts` would.
- **The database**: DataCube's `DuckDbEngine`, unchanged, over a connection whose `query(sql)` runs on Python's
  `duckdb` and answers Arrow IPC, read by `apache-arrow` (`host/arrow-entry.mjs`). In Node, DuckDB-WASM, as the
  tests do.
- **The web layer** (QuickJS and V8; `host/web.js`, `host/url-entry.mjs`): `TextEncoder`/`TextDecoder` (UTF-8),
  `EventTarget`/`Event`, `DOMException`, `AbortController`/`AbortSignal`, and `URL` from `whatwg-url` (896 KB bundled).
  The runners add timers on a virtual clock, `crypto.subtle.digest` from Python's `hashlib`, base64 (V8), and, for
  some tests' own use only, a `Buffer`.
- **The tests' fixture parses**: `datacube/test/lite-compiler.ts` and `pure-protocol/test/lite.ts`, in the overlay,
  made asynchronous: each answer of the compiler awaited, the query still built synchronously, so a refusal of the
  builder throws as before. The test files' calls were converted by `harness/asyncify.py`, a codemod over tree-sitter's
  TypeScript syntax tree, plus three hand fixes it reported. That conversion was checked first: the 18 converted
  files gave the same result for every test, in Node and in QuickJS, as before it (325 passing either way).

## Regenerating

From the checkout, after `bazel build //wasm:planner_dir //python:wheel //tools/js:esbuild`:

1. **The overlay.** Copy `datacube`, `pure-protocol`, `engine-client`, `query-store`, `sdlc-client`, `tools/js`,
   `query`, `studio` and `wasm/src` to `<scratch>/tree`, and link each package's `node_modules` to
   `<bazel-bin>/<package>/node_modules`. Then apply `overlay.patch`, which is test plumbing only: no file under
   `datacube/src`, `engine-client/src` or `pure-protocol/src` changes.
2. **The Python environment.** A venv with `quickjs-ng`, `mini-racer` and `duckdb`, plus legend-lite's wheel from
   `<bazel-bin>/python/`.
3. **The web layer.** Bundle `host/url-entry.mjs` against jsdom's `whatwg-url`, and `host/arrow-entry.mjs` against
   engine-client's `apache-arrow` (browser build, `node:stream` stubbed), with the esbuild binary (`--format=iife
   --platform=neutral`).
4. **The run.** Run `harness/suite.py <esbuild> <node> <planner_dir> <venv python> <engine-client node_modules>`.

The harness expects the layout of its scratch folder (`tree/`, `shim/`, `host/`, `suite/`). It is kept here as the
record of what was run, not as a maintained tool: the design's first step makes it a lane.
