# D_tooling: dialect-leak census, tooling and non-product trees

Input: `parts/D_tooling.tsv`, 5,021 hits in 206 files. Output: `classified/D_tooling.tsv`, 5,021 rows, with the same
id set (checked). Every rule in `work/classify_D.py` was written after reading the construct it names.

**How live and dead were decided.** I grepped every `BUILD.bazel` and `*.bzl` file, `.github/workflows/*.yml` and
`docs/GATES.md` for each file's path, basename and the target names built from it (for example, the `gate + ".py"`
targets in `scripts/corpus/BUILD.bazel`). A file is **live** if a target compiles or runs it. CI's
`bazel build --nobuild` analysis of the manual census targets also counts. Mentions in GATES.md's history log do not
count. Every note in the TSV starts with `live;` or `dead;`.

| Tree | Live? | Evidence |
|---|---|---|
| `parser-equivalence/src/test/**` | live | `glob(["src/test/java/**/*.java"])` in `//parser-equivalence:*`; `.github/workflows/diagnostics.yml` |
| `scripts/corpus/` 30 modules (aggregate … views, differential, functions, scoreboard) | live | `py_library`/`py_binary`/`py_test` in `scripts/corpus/BUILD.bazel`; `gen_differential` feeds `//core:corpus_differential_test`, which is in `gates/BUILD.bazel:28` |
| `scripts/corpus/` 26 other modules (`probe_*`, `taxa_*`, `refdata`, `curves*`, `timeseries`, `brokerage`, `largeexp`, `schedule`, `mutate`, `coverage`, `add_taxonomy_edges`) | dead | not in any `srcs`, and no live module imports them |
| `scripts/parser/keywords.py`, `tiers.py` | live | `//scripts/parser:keyword_coverage` and its `update_keyword_coverage` diff test |
| `scripts/parser/{mutants,parity}.py`, `scripts/{census_gate,generate_pure_constants,outstanding,walldepth}.py`, `scripts/projects/*` | dead | no target |
| `tools/census/RenderCensus.java`, `lanes_diff.py` | live (manual) | `tools/census/BUILD.bazel` (tagged manual); `gates-run.yml:245` analyses them by name |
| `tools/{deps,guards,junit,metrics,bump,browser}` hit files, `site/verify.mjs`, `fixtures/saved-queries/make.mjs` | live | their packages' BUILD files |
| `tools/{spikes,wrongrows,untangle,metamodel-census}`, `tools/reference/join.py`, `tools/{native-axes,upstream-drift}.py` | dead | `tools/BUILD.bazel` only exports `oracle_pins`; no target names these files (bazel-audit end-state ledger U-19 to U-24) |
| `experiments/**` | dead | listed in `.bazelignore` ("not part of this build") |
| `docs/**` code (`.java`, `.py`, `.ts`, `.mjs`, `.js`) | dead | `docs/BUILD.bazel` exports only `*.tsv` ledgers |

## 1. Count table

| Class | Hits | Groups | Live hits | Dead hits |
|---|---:|---:|---:|---:|
| TEST-LEAK | 300 | 11 | 45 | 255 |
| SEAM | 5 | 1 | 5 | 0 |
| SINGLE-BACKEND-APP | 811 | 2 | 0 | 811 |
| TEST-GOLDEN | 2,644 | 6 | 0 | 2,644 |
| LANG | 455 | 13 | 179 | 276 |
| DIAG | 86 | 8 | 8 | 78 |
| FP | 720 | 17 | 483 | 237 |
| LEAK-SQL / LEAK-ID / LEAK-CONN / LEAK-RAW / LEAK-TYPE / LEAK-ERR / DIALECT-* | 0 | 0 | — | — |
| **Total** | **5,021** | **58** | **720** | **4,301** |

There are no product LEAK-* rows, because nothing in this slice is product code. **Only two leak groups are live:**
`RenderCensus` and `differential.py`. Every other TEST-LEAK group is in dead experiments, parked code or history
probes.

## 2. Leak groups (every TEST-LEAK group)

### 2.1 LIVE: `RenderCensus hand-listed dialect classes` (33 hits)
- **Where:** `tools/census/RenderCensus.java:7-15` (imports) and `:50-59` (the map).
- **What it does:** it lowers every recorded case once, then renders it with 9 hand-listed dialects: DuckDb, H2,
  EngineStyleH2, Postgres, Databricks, Snowflake, BigQuery, SQLite and MariaDb. It names each dialect class and
  constructor directly.
- **Why it is a leak:** a 12th database means editing this list. The list is already stale: `Databases.dialect`
  (`core/src/main/java/com/legend/database/Databases.java:43-61`) also serves MySql, Trino and ClickHouse, and the
  census never renders them.
- **Where it belongs:** iterate the SEAM registry. Add `Databases.implemented()`, which lists every `DatabaseType`
  whose `dialect(t)` does not throw, and render with `Databases.dialect(t)`. Keep `EngineStyleH2` as one explicit,
  named legacy-text render target, because it is not a `DatabaseType`.
- **Size:** about 15 lines.
- **Coupling:** `tools/census/render.sh:18-19` compiles this file against the deploy jar of an OLDER commit. A new
  `Databases` method must therefore exist at both commits; until it does, fall back to `DatabaseType.values()`
  with try/catch. No guard pins it. `gates-run.yml:245` analyses the target.

### 2.2 LIVE: `differential.py DuckDB seed.sql DDL/DML` (12 hits)
- **Where:** `scripts/corpus/differential.py:41-94`: `_DUCK`, `_duck_type`, `_sql_literal` and `seed_sql`.
- **What it does:** it writes `diff/seed.sql` from the declared stress schema in DuckDB spelling:
  - `CREATE SCHEMA IF NOT EXISTS`, `DROP TABLE IF EXISTS`, `CREATE TABLE` and `INSERT INTO ... VALUES`;
  - its own declared-kind → DuckDB type table (FLOAT/REAL → `REAL`, bool → `BOOLEAN`);
  - `"` identifier quoting, `TRUE`/`FALSE` literals and `''` escaping.

  `CorpusDifferentialTest.java:63,121-128` (slice C2b) opens `jdbc:duckdb:` and executes the script.
- **Why it is a leak:** it is a Python copy of what each dialect already renders from `SqlDdl.CreateSchema`,
  `SqlDdl.DropTable`, `SqlDdl.CreateTable` (with `SqlDdl.ColumnType`) and `SqlDml.InsertValues`
  (`core/src/main/java/com/legend/sql/SqlDdl.java`, `SqlDml.java`). Running the differential on a second database
  would mean a second Python generator.
- **Where it belongs:** the generator should emit neutral data: tables, schema and columns with their store-DSL
  types, plus rows (JSON). The test then builds `SqlDdl`/`SqlDml` and renders them with
  `Databases.dialect(type).render(SqlDdl)` / `render(SqlDml)`. A per-database connector opens the session.
- **Size:** about −55 Python lines and +40 Java lines.
- **Coupling:**
  - `scripts/corpus/BUILD.bazel` `gen_differential` (`outs = ["diff/seed.sql"]`);
  - `core/BUILD.bazel:527-541` (`-Dcorpus.differential.seed=$(rlocationpath //scripts/corpus:diff/seed.sql)`);
  - `gates/BUILD.bazel:28` (the local gate);
  - no ledger pins it.

### 2.3 dead: `InDbVerdict verdict SQL composed in harness` (58 hits)
- **Where:** `docs/parked/InDbVerdict.java:54-161`.
- **What it does:** it builds the parked "referee in database" verdict by string concatenation:
  - `CREATE TABLE __golden_N`, then `DROP TABLE IF EXISTS`;
  - a `SELECT count(*) FROM ((... EXCEPT ALL ...) UNION ALL (... EXCEPT ALL ...))` diff;
  - `LEFT JOIN (VALUES ...) AS d(src,name)` enum decoding, `LIMIT 0` for arity and `LIMIT 3` for samples.

  All of it runs in the DuckDB session.
- **Why it is a leak:** it is harness SQL composed outside any dialect for one database.
- **Where it belongs:** if the code is ever revived, a MIR `SqlQuery` (set-difference node) rendered by the DuckDb
  dialect. Recommend **delete**: it is parked, `docs/` is not built, and ledger U-26 classes it as history.
- **Size:** none if deleted.
- **Coupling:** none; it is only referenced from docs (`REFEREE_IN_DATABASE_DESIGN_2026_09_07.md` and others).

### 2.4 dead: `InDbVerdict JDBC->DuckDB type table` (28 hits)
- **Where:** `docs/parked/InDbVerdict.java:172-185` (`duckType`).
- **What it does:** it maps a `java.sql.Types` code to a DuckDB column-type spelling for the H2 → DuckDB transfer.
- **Why it is a leak:** it is a per-database type table outside the dialect. It is a LEAK-TYPE in shape, but test
  code.
- **Where it belongs:** `SqlDdl.ColumnType` plus each dialect's DDL spelling (`render(SqlDdl.CreateTable)`).
  **Delete** with the file.

### 2.5 dead: `InDbVerdict DuckDBAppender transfer` (36 hits)
- **Where:** `docs/parked/InDbVerdict.java:190-290`.
- **What it does:** it calls `session.unwrap(org.duckdb.DuckDBConnection.class)`, then `createAppender`, and
  appends typed values per JDBC type.
- **Why it is a leak:** driver-object unwrapping and bulk loading outside a connector.
- **Where it belongs:** the product already has the seam: `com.legend.exec.BulkLoad`
  (`core/src/main/java/com/legend/exec/BulkLoad.java:20`, found through ServiceLoader). Its DuckDB implementation is
  `core/src/main/duckdb/com/legend/exec/DuckDbAppenderLoad.java`. **Delete**; this is a duplicate.

### 2.6 dead: `boundary probes branch on product name` (58 hits)
- **Where:** in `docs/execution-plan-boundary-2026-10-05/probes/`:
  - `EnumIndex.java:23-31,67,80-81`;
  - `ListProbe.java:67-71`;
  - `LiteralProbe.java:43,82,85-96`;
  - `ValueTypedCastProbe.java:56`.
- **What it does:** it branches on `getDatabaseProductName()` to choose:
  - the series generator: `generate_series` (PG), `system_range` (H2) or `range` (DuckDB);
  - `ANALYZE`, and `EXPLAIN` against `EXPLAIN ANALYZE`;
  - the plan-text markers that show an index scan;
  - `text` against `varchar`, and `DECFLOAT` against `DECIMAL(38,1)`.

  It also opens `jdbc:h2:mem:` inline with lite's H2 settings.
- **Why it is a leak:** it is per-database identity branching in a harness.
- **Where it belongs:** nowhere in the product. These are evidence probes for
  `docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md`, and their `*-results.txt` files are committed beside them. If the
  product ever needs one of these facts, it is a dialect fact (for example `SqlDialect.seriesSource(n)` or
  `SqlDialect.explain(...)`). **Keep** them as evidence; no fix.

### 2.7 dead: `backend-probes Probe per-backend switch` (23 hits)
- **Where:** `experiments/backend-probes/harness/src/main/java/Probe.java:28-33,54-80`.
- **What it does:**
  - `switch (backend)` opens duckdb, sqlite, h2 (a LEGACY-mode URL), postgres (zonky `EmbeddedPostgres` through
    reflection) or mariadb (mariaDB4j) inline;
  - for SQLite, the fixture's `DATE '` literals are rewritten.
- **Why it is a leak:** per-backend connections opened inline instead of through a per-backend workspace or
  connector.
- **Where it belongs:** today's equivalents are the `Sessions` URL builders and testing's per-backend workspaces.
  **Delete the code:** no target references it (it is a Maven `pom.xml` in a bazelignored tree). The
  `corpora/`, `results/` and `fragments/` evidence can stay.

### 2.8 dead: `backend-probes inline DuckDB connect` (34 hits)
- **Where:**
  - `experiments/backend-probes/duckdb-census/Catalog{,2,3,4,5}.java`, at `Class.forName("org.duckdb.DuckDBDriver")`
    and `getConnection("jdbc:duckdb:")`, lines 11-32;
  - `duckdb-syntax/DuckDBUnnestSyntaxProbe.java:11,40`;
  - `DuckDBVariantLoadProbe.java:13`.
- **What it does:** one-off DuckDB catalog and syntax probes.
- **Where it belongs:** nowhere. **Delete:** no target references them, and `Catalog.java:90` and
  `gen_census.py`/`cmp.py` hard-code a `/private/tmp/claude-502/...` scratch path.

### 2.9 dead: `DbxTest inline Databricks JDBC` (9 hits)
- **Where:** `experiments/backend-probes/databricks/DbxTest.java:15-38`.
- **What it does:** it loads the driver class `com.databricks.client.jdbc.Driver` and builds the URL
  `jdbc:databricks://<host>:443/default;transportMode=http;ssl=1;httpPath=...;AuthMech=3;UID=token;ConnCatalog=samples`
  with token properties.
- **Why it is a leak:** this is the only Databricks connection knowledge in the repository. `Sessions.java:191,202`
  refuses to build a Databricks URL.
- **Where it belongs:** a Databricks connector, either `Sessions.url(Databricks spec)` or a
  `DatabricksConnector.open(spec)`, when one is built. **Delete** the probe once that knowledge has been moved there.
- **Size:** about 15 lines in a connector.

### 2.10 dead: `fusion spikes inline duckdb.connect` (6 hits)
- **Where:** `tools/spikes/fusion_{probes,spike,spike2}_2026_08_28.py`, lines 7-17 (`import duckdb`,
  `duckdb.connect()`).
- **What it does:** these are history spikes for the V12/V13 verdict-fusion design.
- **Where it belongs:** nowhere. **Delete** or move to `docs/history`; ledger U-22 already classes them as history.

### 2.11 dead: `type-audit Probe inline jdbc:duckdb` (3 hits)
- **Where:** `docs/type-audit-2026-08/harness/Probe.java:63`.
- **What it does:** it opens `DriverManager.getConnection("jdbc:duckdb:")`, executes a DDL file and calls
  `Execution.execute(model, query, runtime, c)`.
- **Where it belongs:** a per-database connector. **Delete:** ledger U-27 classes it as history, and no target
  builds it.

## 3. SEAM, DIALECT-SHARED and SINGLE-BACKEND-APP

- **SEAM | `CoreClosureTest DRIVER_JARS pin`** (`tools/deps/CoreClosureTest.java:30-37`, live): this guard lists the
  driver jars `duckdb_jdbc`, `h2`, `postgresql` and `sqlite_jdbc`. It must equal the closure of `//core:drivers`
  (`core/BUILD.bazel:306-315`). It is a per-concern list, not a per-database object: a new database edits both the
  BUILD file and this pin, with a dated reason, as the guard rules require. The jar names are build configuration,
  so a connector interface would not remove this list.
- **DIALECT-SHARED:** none in this slice.
- **SINGLE-BACKEND-APP | `warehouse prototype probes (DuckDB)`** (787 hits, dead):
  - covers `experiments/warehouse-ffm/*` (DuckDB C API through FFM), `warehouse-w0/*` (HTTP and authz prototypes,
    `SET VARIABLE app_user`, macros) and `warehouse-w1d/ArrowProbe.java`;
  - this is DuckDB by design (the warehouse is a DuckDB server), and no dialect would serve it;
  - **delete**, or move to `docs/history`: the `warehouse/` module exists, and no target references these files.
- **SINGLE-BACKEND-APP | `datacube headless harness DuckDB stand-in`** (24 hits, dead):
  - covers `docs/datacube-headless-evidence-2026-10-11/harness/{run-node.mjs,run_qjs.py,run_v8.py}`;
  - DataCube runs on DuckDB-wasm, and the harness swaps in Python `duckdb` behind the same module shape
    (`SET file_search_path`);
  - it bypasses no dialect.

Other benign groups, for the record:
- **TEST-GOLDEN** (all dead): the backend-probes census and syntax SQL (803 hits), the probe-corpus generators
  `gen_*.py` (1,094), the portable fixture (23), the Databricks probe SQL (16), the boundary-probe SQL (371) and
  the fusion-spike SQL (337).
- **LANG:** generated `###Relational` Table DSL, and `type: DuckDB` connection blocks in the stress corpus and its
  probes (these pin the corpus runtime to DuckDB). Also parser-equivalence's grammar fixtures and keyword censuses,
  and `damage.py`, which emits `###Data` values by declared store type.
- **DIAG:** log-triage regexes over DuckDB error text in `scripts/outstanding.py` and
  `docs/burndown-2026-08-14/tools/*`. These are dead, and they are data analysis, not behaviour.
- **FP:** "oracle" means the reference evaluator or the engine parser (288 hits), plus English keywords and
  `JSON.stringify`.

## 4. Misses (database-specific behaviour the scanner did not flag)

1. `tools/census/RenderCensus.java:50-59` and `:33-34` (javadoc), plus `tools/census/render.sh:3`. The census's
   dialect set is a hand list that has drifted: it lacks MySql, Trino, ClickHouse and H2Modern, which
   `Databases.dialect` serves. The javadoc and render.sh still say "DuckDb, H2, EngineStyleH2, Postgres". The
   scanner flagged the names but not the drift.
2. `scripts/corpus/differential.py:80, 90, 56-63`. These lines are part of the same DuckDB seed construct but
   were not flagged:
   - `"`-quoted identifiers (the comment at lines 84-89 says this is because `LIMIT` is reserved in DuckDB);
   - `DROP TABLE IF EXISTS`;
   - the `_sql_literal` spellings (`TRUE`/`FALSE`, `''` escaping).
3. `scripts/corpus/combos.py:104-109`. The live generator's `TYPES` axis leaves out `REAL` deliberately, because
   the DuckDB connector fails on it (F31). One backend's defect shapes the corpus that every backend judges.
4. `docs/execution-plan-boundary-2026-10-05/probes/ValueTypedCastProbe.java:53-55`. It copies lite's H2 session
   settings inline (`NON_KEYWORDS=...`, `MODE=LEGACY`, `DEFAULT_NULL_ORDERING=HIGH`). The product's one definition
   is `core/src/main/java/com/legend/exec/H2Settings.java:45`. This is a second copy of the H2 connector
   configuration (dead).
5. `tools/wrongrows/damage.py:98, 128-131`. This dead tool cuts strings to the declared width by H2's rules
   ("H2 enforces it", "width is in UTF-16 units for H2"), because legend-engine's session database is H2. That is
   referee-specific value shaping in a data generator.
6. `tools/census/lanes_diff.py:15`. The live tool normalises `mem:[A-Za-z_]*\d{6,}`, which is H2's in-memory
   database naming. A log differ knows H2's workspace names. This is benign data normalisation.
7. `core/BUILD.bazel:306-315` (`//core:drivers`) and `tools/deps/BUILD.bazel:20-25` (`drivers_closure`). The
   per-database driver-jar list lives in the build graph and is mirrored by the SEAM pin in §3. The scanner does not
   read BUILD files.
8. `experiments/backend-probes/README.md` ("Read `fragments/` before implementing a dialect"). The per-database
   working constructions (the "exact working construction for every B bucket") live as Markdown under
   `experiments/`, outside the dialect classes and their docs. This is documentation, not code, but it is dialect
   knowledge kept outside `sql/dialect/`.
