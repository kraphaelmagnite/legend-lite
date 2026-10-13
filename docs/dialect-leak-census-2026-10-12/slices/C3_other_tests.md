# C3_other_tests: dialect-leak census, tests of the non-core modules

Input: `parts/C3_other_tests.tsv`, 2,204 hits in 118 files. Output: `classified/C3_other_tests.tsv`, 2,204 rows. The
ids match the input exactly. Rules were applied by script (`c3classify.py`, one rule per construct), and each rule was
written after reading the code at that construct.

## 1. Counts

| class | hits | groups |
|---|---:|---:|
| SINGLE-BACKEND-APP | 1,518 | 27 |
| TEST-GOLDEN | 316 | 8 |
| FP | 266 | 18 |
| LANG | 86 | 7 |
| DIAG | 18 | 6 |
| LEAK-SQL / LEAK-ID / LEAK-CONN / LEAK-RAW / LEAK-TYPE / LEAK-ERR | 0 | 0 |
| TEST-LEAK | 0 | 0 |
| SEAM / DIALECT-SHARED / DIALECT-INTERNAL | 0 | 0 |
| **total** | **2,204** | **66** |

By module:

| module | SINGLE-BACKEND-APP | TEST-GOLDEN | FP | LANG | DIAG |
|---|---:|---:|---:|---:|---:|
| warehouse (src/test) | 846 | 162 | 15 | 0 | 4 |
| datacube (test) | 554 | 154 | 236 | 78 | 12 |
| engine-client (test) | 117 | 0 | 2 | 6 | 2 |
| query (test) | 1 | 0 | 0 | 0 | 0 |
| pure-protocol, query-store, sdlc-client, sdlc-server, studio, json | 0 | 0 | 12 | 2 | 0 |

## 2. Leak groups (LEAK-* and TEST-LEAK)

**None.** No test in this slice chooses among backends. Each module's verdict, with the code read to reach it:

- **warehouse/src/test: single engine by design (DuckDB).** The warehouse is a DuckDB server. Its only other engine
  is Postgres, reached as an attached catalog through DuckDB's postgres extension: `postgres_query('attached', ...)`
  passthrough. The Postgres-specific knowledge sits in the warehouse's own per-database classes
  (`server/Postgres.java`, `server/PostgresUrl.java`, `Attachment.POSTGRES`). The tests exercise each kind of catalog
  in a test class of its own: `WarehousePostgresLiveTest` and `PostgresCatalogTest` for Postgres, the rest for DuckDB.
  Nothing switches on a backend.
  - `WarehouseJdbcTest.java:56` opens `jdbc:duckdb:` inline. That connection is the reference side of a differential
    (DuckDB's own driver against the warehouse's driver). A 12th database would not touch it.
  - The warehouse cannot reach core's `Sessions` (`//tools/deps:warehouse_closure_test`), and has no need to.
  - The embedded Postgres comes from testing's per-database helper,
    `com.legend.testing.EmbeddedPostgres.shared().dsn(...)` (`WarehousePostgresLiveTest.java:49`).
  - `TestServer.java:68-75` switches on `WAREHOUSE_BINARY`. That chooses between the in-JVM server and the native
    image, which are both DuckDB.
- **datacube/test and engine-client/test: single engine by design (DuckDB).**
  - **Where the SQL runs.** It is always DuckDB, in one of three places: DuckDB-WASM in the tab, Python's DuckDB in the
    headless lane (`datacube/test/headless/run.py`), or the warehouse's DuckDB (`live-snap`).
  - **Where the SQL comes from.** legend-lite's planner (WASM or native) writes it, so it is the DuckDb dialect's.
  - **How the dialect is chosen.** The dialect follows the Pure connection `type`. The app carries that type as
    `databaseType`, which a per-engine object declares: `engine-client/src/duckdb.ts:261 readonly databaseType = 'DuckDB'`,
    or a warehouse `CatalogObject.databaseType`. The planner picks the dialect from that type, through the Runtime.
    This is the right seam.
  - **Postgres.** It appears only as a warehouse catalog type. `infer.test.ts:114-159` asserts both dialects' output
    explicitly (TEST-GOLDEN) and does not branch.
  - **The `#test-host` switch.** It chooses between Node+WASM and the headless native library. That picks the
    planner's host, not the database.
  - **`QueryEngine.name`.** The name (`'duckdb'`, `'stub-duckdb'`) is for diagnostics only. `datacube/src/runner.ts:109`
    only uses it to build the label.
- **query/test: single engine.** `lite.ts:42` loads the DuckDB-plane Runtime (`runtime-duckdb.pure`). The page config
  chooses the plane, and the plane's Pure Runtime (`query/README.md`) fixes the database.
- **pure-protocol, query-store, sdlc-client, sdlc-server, studio, json:** these tests contain no database code. Their
  hits are English words, SDLC error text, JSON-format wording, or Pure `select()` (LANG).

## 3. Legitimate groups

### SEAM and DIALECT-SHARED

None in this slice. The engine-client per-engine `databaseType` (`engine-client/src/duckdb.ts:261`,
`engine-client/src/warehouse.ts:47`) is a per-database object rather than a switch. It is product code, so it is not
counted here; it is listed only because the datacube tests lean on it.

### SINGLE-BACKEND-APP (one line each; "bypass" says whether the product code under test skips a dialect that could serve it)

**warehouse**

- WarehouseEntitlementsTest DuckDB SQL fixtures (403): `WarehouseEntitlementsTest.java:52-306`. DuckDB DDL, DML and
  SELECT probes for grants and lockdown. Bypass: n/a (test input).
- WarehouseServerTest DuckDB SQL fixtures (112): `WarehouseServerTest.java:134-404`. Bypass: n/a.
- WarehouseArrowTest DuckDB SQL fixtures (43): `WarehouseArrowTest.java:48-86`. Typed values for the Arrow-vs-JSON
  check. Bypass: n/a.
- WarehouseJdbcTest DuckDB differential SQL/metadata (172): `WarehouseJdbcTest.java:62-398`. The same SQL runs through
  DuckDB's driver and through the warehouse driver. Bypass: n/a.
- WarehouseJdbcTest reference jdbc:duckdb connection (3): `WarehouseJdbcTest.java:56`. The differential's reference
  driver. The warehouse may not depend on core.
- WarehouseJdbcTest corpus-workspace mirror (24): `WarehouseJdbcTest.java:291-306`. ATTACH, USE and DETACH, done "as
  the corpus harness does". Coupled to `spec/.../rcorpus/DuckWorkspaces.java` (see misses).
- WarehouseJdbcTest product name/version parity (8): `WarehouseJdbcTest.java:407-408`. The warehouse driver reports
  DuckDB's own product name and version.
- warehouse JDBC driver URL (2): `WarehouseJdbcTest.java:285-287`. `jdbc:warehouse:` is the warehouse's own driver.
- warehouse TestServer launch flags (8): `TestServer.java:67-103`. The `--postgres` and `--duckdb-*` server flags.
- warehouse launch flags (duckdb paths) (4): `AppModeTest.java:87-223`.
- warehouse Postgres-catalog config/grant parsing (43): `PostgresCatalogTest.java:33-122`. The CLI, `Attachment.POSTGRES`
  and `GRANT USAGE ON CATALOG` parsing.
- warehouse DuckDB UTC session check (9): `PostgresCatalogTest.java:182-186`.
- warehouse catalog grant statements (11): `WarehousePostgresLiveTest.java:60-139`. Admin statements, managed in the
  DuckDB catalog.
- warehouse DuckDB postgres extension path (1): `WarehousePostgresLiveTest.java:51`.
- arrow_matches_json DuckDB interval reader (3): `arrow_matches_json.py:97-144`. Parses DuckDB's interval text.

**datacube, engine-client, query**

- DuckDB-wasm test bootstrap / DuckDbEngine (181): `createDuckDB` and `new DuckDbEngine` in 16 files (`cube-open.ts`,
  `host/node.ts`, `snap.test.ts`, `model-data.test.ts`, ...).
- app DuckDB fixture/truth SQL, tab or warehouse (431): seeds and "truth" queries that DuckDB runs directly
  (`duckdb.test.ts`, `treeview.test.ts`, `pivot-rows.ts`, `typed-values.ts`, `snap.test.ts`, `upload.test.ts`, ...).
  Bypass: n/a (test truth).
- snap SQL goldens, tab DuckDB (20): `plane.test.ts:208-287`. These pin `CREATE OR REPLACE TABLE .. AS SELECT * REPLACE
  (...)` and `DROP TABLE IF EXISTS`. **Bypass: yes.** `engine-client/src/snap.ts:257-280` writes that DuckDB DDL in
  TypeScript instead of legend-lite's DuckDb dialect. That is acceptable: the snap target is always the tab DuckDB.
- upload forget SQL golden, tab DuckDB (2): `upload.test.ts:200`. **Bypass: yes**, at `datacube/src/upload.ts:81`.
- datacube remote.ts DuckDB mount SQL goldens (10): `remote.test.ts:113-235`. INSTALL/LOAD httpfs, VIEW over
  `read_parquet`, SECRET. **Bypass: yes**, at `datacube/src/remote.ts:104-168`. These are DuckDB-WASM-only features,
  and no dialect method exists for them.
- DuckDB catalog column fixtures (6): `calc-fix.test.ts:19-21`.
- headless lane: Python DuckDB (5): `datacube/test/headless/run.py:73-79`.
- live-snap warehouse launch (2): `live-snap.mjs:16-23`.
- packaged app: DuckDB library + postgres extension (10): `package/entries.ts:44-54`.
- bundle carries no DuckDB-WASM (3): `bundle-budget.test.ts:140-142`.
- DataCube test picks DuckDB-plane runtime file (1): `saved-queries.test.ts:27`.
- Query test picks DuckDB-plane runtime file (1): `query/test/lite.ts:42`.

### TEST-GOLDEN

- WarehousePostgresLiveTest Postgres catalog fixtures (83): `:68-192`, plus the embedded-Postgres DSN (1) at `:49`.
- PostgresCatalogTest `Postgres.query`/`cancel`/`inSessionZone` goldens (52): `:77-175`.
- AppModeTest PostgresUrl parse fixtures (26): `:44-128`.
- infer.test model-writer goldens, DuckDB and Postgres (63): `infer.test.ts:53-210`.
- stub plan SQL (72): opaque `SELECT 1` tokens from fake planners and engines, never run.
- stubbed legend-engine response, H2 runtime (17): `engine-remote*.test.ts`. The engine is a stub, and the H2 is
  upstream's.
- stubbed warehouse /objects response (2).

### LANG, DIAG and FP

- **LANG:** Pure `###Relational` store DSL and `###Connection type: DuckDB` text in test models; `databaseType: 'DuckDB'`
  passed as a Pure connection type; Pure `select()` function names.
- **DIAG:** `describe` and `it` titles naming DuckDB, docstrings, diagnostic engine names, relayed compiler messages.
- **FP:** UI labels and DOM `select`; English capitals in test titles (AND, NOT, NULL, ON); `JSON.stringify`; CSS
  `px` values and five-digit numbers read as SQLSTATEs; `<h2>` and spreadsheet cell `H2`; host names `h2` and `db2`;
  SDLC error text; injection-probe strings.

## 4. Misses (database-specific content the scanner did not flag)

All of these are benign under the guide. They are listed because the scanner missed them and because some of them pin
facts that live elsewhere.

1. **`datacube/test/infer.test.ts`**
   - `:117-118`, `:134`, `:148`: Postgres catalog type names (`int4`, `integer[]`, `inet`, `jsonb`, `bytea`, `int8`,
     `text`). They are lowercase, so the type-spelling pattern missed them.
   - `:123-124`: the Postgres golden `CAST("t0"."doc" AS JSONB)`.
   - `:155-158`: the constant group key, `GROUP BY CAST('[ROOT]' AS VARCHAR)` on Postgres against a bare `'[ROOT]'` on
     DuckDB. Regex literals are not scanned.

   These are TEST-GOLDEN. They pin two per-database facts that core owns: the model writer's rules from Postgres
   catalog types to store types, and the Postgres dialect's typed constant. Whoever moves those facts must update this
   file.
2. **Pure store DSL inside regex literals:** `infer.test.ts:64,80,205` and `upload.test.ts:72`. LANG.
3. **`datacube/test/remote.test.ts:77,81,85,92`:** the DuckDB-only scan functions `read_parquet`, `read_csv_auto` and
   `iceberg_scan`, as regex goldens. The product side is `datacube/src/remote.ts:104-168`. SINGLE-BACKEND-APP, written
   outside any dialect.
4. **`datacube/test/plane.test.ts:215,224`:** `SELECT * REPLACE (...)` regex goldens. The product side writes DuckDB DDL
   in TypeScript in four places: `engine-client/src/snap.ts:257-280`, `datacube/src/upload.ts:81,140,152` and
   `engine-client/src/duckdb.ts:386`. This is single-backend by design. It would only matter if snap ever targeted
   anything other than DuckDB.
5. **DuckDB-only types and syntax in the warehouse tests:**
   - The unsigned types `UTINYINT`, `USMALLINT`, `UINTEGER`, `UBIGINT` and `UHUGEINT` are not in the type-spelling
     list. They appear at `WarehouseJdbcTest.java:137,181,362` and `WarehouseArrowTest.java:62`.
   - The FROM-first form `FROM sales.v_orders SELECT id` at `WarehouseEntitlementsTest.java:204` was not flagged.

   SINGLE-BACKEND-APP.
6. **Coupling:** `WarehouseJdbcTest.java:291-306` says it is "the corpus harness's workspace, exactly". That harness is
   `spec/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:113,209` (`ATTACH ':memory:' AS ws`, then `USE`). If
   DuckDB workspace handling moves behind a per-database workspace interface, this test's statements should move with
   it, or call it.
7. **Checked and not a leak:**
   - `TestServer.java:42,68` switches on the `WAREHOUSE_BINARY` env var, between JVM and native image. Both are
     DuckDB.
   - `readonly name = 'duckdb'` at `json-read.ts:71`, `pivot-rows.ts:87` and `typed-values.ts:75` is a diagnostic
     name, and nothing in `datacube/src` or `engine-client/src` branches on it.
   - `convertible` in `datacube/src/infer.ts:88-93` is a real capability (can the source apply a conversion), not
     DuckDB's identity in disguise.
8. **Adjacent to the tests:** `query/demo/models/runtime-duckdb.pure` and `runtime-h2.pure`, together with the
   `config*.json` files, choose each plane's database by Pure Runtime. That is the correct seam, and no test switches
   on it.
9. **Guard files:** only `core/src/test/java/com/legend/SkipCensusTest.java:52,149` names this slice. It walks
   `warehouse/src/test/java` for skips and mentions `WarehousePostgresLiveTest`. No dialect ledger or pin names any
   file here.
