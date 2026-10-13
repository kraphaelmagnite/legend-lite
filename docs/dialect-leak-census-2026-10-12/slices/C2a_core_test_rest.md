# C2a_core_test_rest: dialect-leak census, classified

Slice: `core/src/test/java/com/legend/**` except `integration/` and `resolver/`. That is 3,730 hits in 148 files.
`classified/C2a_core_test_rest.tsv` has one row per input id (3,730 rows, and the id set matches the input).
Rules were applied by script (`work/classify_c2a.py`), but only after reading every distinct construct. Per-line
overrides cover each leak, each guard, and every case where the generic rules got it wrong.

## 1. Counts

| class | hits | groups |
|---|---:|---:|
| TEST-GOLDEN | 2,363 | 192 (one to three per file: `golden SQL/fixtures in X`, `inline test connection in X`, `DB-under-test wiring in X`, plus a few named ones) |
| LANG | 1,019 | 1 (`Pure model text in test fixtures`) |
| FP | 173 | 8 |
| SEAM | 66 | 11 |
| TEST-LEAK | 60 | 8 |
| DIAG | 49 | 6 |
| LEAK-* (SQL/ID/CONN/RAW/TYPE/ERR) | 0 | 0 |
| DIALECT-INTERNAL / DIALECT-SHARED / SINGLE-BACKEND-APP | 0 | 0 |
| **total** | **3,730** | **226** |

The slice has no product code, so it has no LEAK-* hits. Most of the volume falls into a few kinds:

- Store DSL and connection blocks in `###Relational` and `###Connection` test models (LANG).
- Dialect tests asserting rendered SQL, such as `PostgresDialectTest`, `DuckDbRenderTest`, `LowerRelationTest` and
  `JoinTortureTest` (TEST-GOLDEN).
- About 105 inline `DriverManager.getConnection("jdbc:duckdb:")` sites in 64 files, and 14 files that open
  `jdbc:h2:mem:...`. Each opens the one database its test exercises, so they are TEST-GOLDEN. Section 4 covers them
  as a pattern.

## 2. TEST-LEAK groups (every one)

All eight are local to a single test file. None is a shared base class. No guard file pins any of them, and the only
build-file mentions are noted under Coupling. Two per-database owners already exist that they could call:

- `Sessions.openPrivate(DatabaseType)` (`core/src/main/java/com/legend/exec/Sessions.java:69-75`) opens a private
  in-process H2, DuckDB or SQLite with the right URL and `H2Settings`.
- `StorelessRuntime.specificationAndAuth(DatabaseType)` (`core/src/main/java/com/legend/test/StorelessRuntime.java`,
  currently private) is the per-database Pure connection declaration for in-process databases.

The proposals below lean on these two owners. They become methods on a per-database connector once the plan creates
one.

### TL1. PlanMakerTest.fresh: a DatabaseType switch that builds JDBC URLs

- **Where:** `core/src/test/java/com/legend/PlanMakerTest.java:405-412` (hits at 407-408).
- **What it does:** `switch (type) { case DuckDB -> getConnection("jdbc:duckdb:"); case H2 -> getConnection("jdbc:h2:mem:planmaker" + N + H2Settings.SETTINGS); default -> throw }`.
  It opens the "today" and "planned" sessions for the DuckDB × H2 matrix.
- **Why it leaks:** it is a backend switch that composes per-database JDBC URLs, and it repeats the H2 URL and
  settings that `Sessions` builds. Testing a 12th in-process database would mean adding an arm here.
- **Where it belongs:** `Sessions.openPrivate(type)`, or a per-database `DatabaseConnector.openScratch()`. A test-side
  `TestDatabases.open(DatabaseType)` that delegates to it would also serve the other inline openers in Section 4.
- **Fix size:** about 8 lines become 1.
- **Coupling:** only `assertAnswersAsToday` calls it, at line 387. No guard names the file.

### TL2. PlanRunnerTest: server versions picked by a DatabaseType compare

- **Where:** `core/src/test/java/com/legend/exec/PlanRunnerTest.java:293`. The constant `H2_SERVERS` is at line 321.
- **What it does:** `connection.databaseType() == DatabaseType.H2 ? H2_SERVERS : new Servers.Every()`. `H2_SERVERS`
  is a hand-written `Versions("2.1","2.2")`.
- **Why it leaks:** it branches on database identity to look up a fact that `Databases.servers(type)` already owns
  (`H2.SERVERS`). If H2's supported versions change, this test drifts silently.
- **Where it belongs:** `Databases.servers(connection.databaseType())`, which later becomes a per-database
  `DatabaseFacts.servers()`.
- **Fix size:** 1 to 2 lines. The constant can go too, unless a hand-made plan is the point of the test.
- **Coupling:** none. The hand-built plan fixtures around it (`H2_TYPES` at 199-209, `holed`) are deliberate and
  classed TEST-GOLDEN.

### TL3. StoreTypesTest.on: picks the connection block by backend name

- **Where:** `core/src/test/java/com/legend/StoreTypesTest.java:163-166`, plus the loop at line 170
  (`List.of("DuckDB","H2","Postgres")`).
- **What it does:** `type.equals("H2") ? MODEL.replace(duck block, "type: H2; specification: LocalH2 { }; auth: DefaultH2;") : MODEL.replace("type: DuckDB;", "type: "+type+";")`.
- **Why it leaks:** it decides by a database's name string which specification and auth keywords that database needs.
  That per-database knowledge is repeated across tests (Section 4).
- **Where it belongs:** a per-database test connection declaration. Two options: make
  `StorelessRuntime.specificationAndAuth(DatabaseType)` public as `StorelessRuntime.connectionBlock(type)`, or add
  `DatabaseConnector.testDeclaration()`. The test then loops over `DatabaseType` values.
- **Fix size:** about 6 lines.
- **Coupling:** called at 171 and 214. The test is plan-only, so Postgres needs a declaration even though nothing
  opens it.

### TL4. MissingValueInComputedColumnTest.model: picks the model with a DatabaseType ternary

- **Where:** `core/src/test/java/com/legend/exec/MissingValueInComputedColumnTest.java:32-57` (the ternary is at 56).
- **What it does:** two full model literals that differ only in the connection block, chosen by
  `type == DatabaseType.H2 ? H2_MODEL : DUCKDB_MODEL`. The methods `duckdb()` and `h2()` (lines 89-102) each open
  their own database.
- **Why it leaks:** it branches on database identity to choose a per-database Pure connection declaration. Low
  severity: the per-database literals are deliberate.
- **Where it belongs:** the same `StorelessRuntime.connectionBlock(type)` or `DatabaseConnector.testDeclaration()` as
  TL3, with connections opened by `Sessions.openPrivate(type)`.
- **Fix size:** about 12 lines.
- **Coupling:** the class comment says each literal is "a model the own-corpus parity lane can read". Folding the two
  literals into a helper could cost that lane its input. `spec/src/test/resources/reference-lane/reasons.tsv:6` cites
  this test as evidence.

### TL5. QuotedColumnNameTest.run: switches backend by string

- **Where:** `core/src/test/java/com/legend/lowering/QuotedColumnNameTest.java:56-82`.
  - The URL choice is at 57-58.
  - The connection-block swap is at 69-71.
  - `@ValueSource(strings = {"duckdb","h2"})` appears at 84, 92, 101, 110, 119, 128, 136 and 144.
  - `run("duckdb", ...)` is called at 154 and 164.
- **What it does:** `"duckdb".equals(engine) ? "jdbc:duckdb:" : "jdbc:h2:mem:quoted"+nanoTime`, then the same string
  test to rewrite the model's `{ type: DuckDB; ... }` into `{ type: H2; specification: LocalH2 { }; auth: DefaultH2; }`.
- **Why it leaks:** it chooses the backend by string in a shared helper of a parameterised test, composes the JDBC URL
  inline, and swaps the per-database connection block.
- **Where it belongs:** parameterise on `DatabaseType` with `@EnumSource(names = {"DuckDB","H2"})`. Open the
  connection with `Sessions.openPrivate(type)` and declare it with `StorelessRuntime.connectionBlock(type)`.
- **Fix size:** about 15 lines.
- **Coupling:** none in the guards. The pivot test (152-158) is DuckDB-only by design, because DataCube's engine is
  DuckDB.

### TL6. PostgresArmTest: provisions a login role inline

- **Where:** `core/src/test/java/com/legend/server/PostgresArmTest.java:28-40` (hits at 32 and 37).
- **What it does:** it opens an admin connection to the embedded Postgres, probes `SELECT 1 FROM pg_roles WHERE rolname = ?`,
  and runs `CREATE ROLE "<os user>" LOGIN`. This is needed because the product's Postgres arm logs in as the OS
  account.
- **Why it leaks:** it is Postgres-specific server provisioning (catalog SQL and role DDL) written inline in a test,
  not done by the per-backend test server.
- **Where it belongs:** `testing/src/main/java/com/legend/testing/EmbeddedPostgres.java`. Add
  `ensureLoginRole(String account)`, or have `shared()` provision the OS account once.
- **Fix size:** about 12 lines move, and the test shrinks by about 10.
- **Coupling:** `core/BUILD.bazel:364-368, 577-583` (`:postgres_arm_test`, which needs the embedded Postgres in its
  runfiles). Other tests that call `ReservedNames` and `PlanCases` on Postgres (lines 72-160) depend on the role
  existing.

### TL7. RowLoadTest: hand-ports DuckDB DDL to H2

- **Where:** `core/src/test/java/com/legend/exec/RowLoadTest.java:170`. `DDL` is defined at 33-34.
- **What it does:** `st.execute(DDL.replace("DOUBLE", "DOUBLE PRECISION"))`. It reuses the DuckDB-spelled DDL on H2 by
  rewriting the text.
- **Why it leaks:** it adapts SQL text between databases outside the target dialect. This is a small raw-SQL rewrite
  in a test.
- **Where it belongs:** render the table as `SqlDdl` through each dialect (`dialect.render(SqlDdl)`, with type names
  from the dialect), or keep an H2-spelled DDL literal.
- **Fix size:** about 3 to 5 lines.
- **Coupling:** none.

### TL8. LadderRender: filters statements by SQL prefix (unsure)

- **Where:** `core/src/test/java/com/legend/ladder/LadderRender.java:182-190` (the hit is at 187).
- **What it does:** it drops recorded statements that start with `SET `, `CREATE ` or `INSERT ` so that the ladder
  pins show only query SQL.
- **Why it leaks:** it recognises session-setup and seed statements by their SQL text. `SET ` is DuckDB's (and
  Postgres's) session spelling. Other databases' setup, such as `ALTER SESSION`, `PRAGMA` or `USE`, would leak
  through. I am unsure here, because the ladder is DuckDB-only by design, but the knowledge is still per-database.
- **Where it belongs:** filter by statement origin instead of text. Tag recorded statements with `StatementOrigin`
  (`SEED`, `SEED_GENERATED`) or skip the session statements the dialect gives in `sessionSetup()`.
- **Fix size:** about 5 to 10 lines.
- **Coupling:** `//core:update_ladder` and the `<rung>.current.sql` pins (`core/BUILD.bazel:892-916`). Changing the
  filter can move the pins, which must be re-pinned and reviewed.

## 3. SEAM, DIALECT-SHARED and SINGLE-BACKEND-APP (one line each)

SEAM. These are guard registers in the root package that name per-database files as data. None of them is a
per-database object.

- `DialectBoundaryTest one-owner census`, `DialectBoundaryTest.java:116-141`: a per-concern census.
  - Dialect constructions are pinned to `Databases.java` (7) and `EngineText.java` (5).
  - `"jdbc:` is pinned to `Sessions.java` (10).
  - DatabaseType decisions are pinned to `Databases.java` (2), `EngineText.java` (1) and `Sessions.java` (1).
  - Name decisions are pinned to `ConnectionSectionGrammar.java` (2) and `SqlTypeCensus.java` (2).
  - `rawH2IsNative` is pinned to `CsvSeed.java` (1).
  - It has coverage gaps; see misses.
- `RawSqlLedgerTest h2ToDuckDb caller ledger`, `RawSqlLedgerTest.java:70,96`: pins the `RawSqlBoundary.h2ToDuckDb`
  callers `CsvSeed.java` (1) and `RawSqlAdapt.java` (1). These are product LEAK-RAW sites for the other slices.
- `JdbcSurfaceCensusTest MAIN_REGISTER`, `JdbcSurfaceCensusTest.java:94,97,158`: registers `DuckDbAppenderLoad`,
  `DuckDbCells` (`:duckdb_load`) and `SqlReplayOracle` as JDBC surface.
- `TestLaneOrderGuardrailTest DuckDb pass-list pin`, `TestLaneOrderGuardrailTest.java:74-80`: pins the
  `StableScanOrder` install and the `legend.exec.engineScanOrder` switch to `DuckDb.java`. That code is
  DIALECT-INTERNAL.
- `ArchitectureTest invariant 4d EngineStyle quarantine`, `ArchitectureTest.java:233-239`: the EngineStyle golden-text
  dialects are allowed only in the root layer.
- `ArchitectureTest product-library register`, `ArchitectureTest.java:93`: names `:duckdb_load` as a product library.
- `ArchitectureTest EXEC_PLANNING_REFERENCES ledger`, `ArchitectureTest.java:748`: pins `SqlReplayOracle` (the H2
  referee SPI).
- `JavaEvalLedgerTest residue register`, `JavaEvalLedgerTest.java:1188`: lists `SqlReplayOracle.java` (the referee).
- `CodeShapeGuardrailTest static-mutable allowlist`, `CodeShapeGuardrailTest.java:182`: lists `Snowflake.fresh`,
  which is dialect-internal state.
- `CarrierPurityRatchetTest SqlFn pins`, `CarrierPurityRatchetTest.java:188`: `SqlFn.UNNEST` = 13.
- `SqlTextRatchetTest SQL-shape ratchet pattern`, `SqlTextRatchetTest.java:58-60`: the regex that patrols SQL text
  composed outside the dialect.

There is no DIALECT-SHARED code in this slice, because it holds no `sql/dialect` main code. Some tests are coupled to
the shared tables, though:

- `CarrierDifferentialTest.java:52-53,94-95,124-125,162-163,623-624` builds
  `new AnsiSqlRenderer(Lexicon.DUCKDB, TypeNames.DUCKDB, Spellings.DUCKDB)`.
- `DuckDbKeywordsTest.java:31` reads `Lexicon.DUCKDB`.
- `SpellingsTest.java:137,141` reads `Spellings.DUCKDB`.

All of these must move when DuckDB's facts move into `DuckDb`.

There is no SINGLE-BACKEND-APP code either. `LadderRender` is DuckDB-only by design and is classed TEST-GOLDEN, apart
from TL8.

## 4. TEST-GOLDEN patterns worth acting on (classed golden, not leaks)

- **Inline in-process connections.** There are about 105 `DriverManager.getConnection("jdbc:duckdb:")` sites in 64
  files and `jdbc:h2:mem:` in 14 files. Examples:
  - `AssertErrorNativeTest.java:38`
  - `PlanMakerTest.java:130-131`
  - `exec/RowLoadTest.java:154`, which appends `H2Settings.SETTINGS`
  - `exec/PlanRunnerTest.java:234,311`
  - `server/PureV1ApiTest.java:556-557` (the legend-engine H2 referee)

  Each one opens the database its own test exercises, but together they are why these tests cannot run on another
  backend. The tests that append `H2Settings.SETTINGS` repeat `Sessions.java:59,71`. One test-side
  `TestDatabases.open(DatabaseType)` delegating to `Sessions.openPrivate` would absorb them.
- **Storeless runtimes pinned to DuckDB.** There are 68 `StorelessRuntime.with(..., DatabaseType.DuckDB)` sites in 32
  files. They are the correct owner for the declaration, but they are pinned to DuckDB.
- **Hand-written connection blocks.** 37 files hand-write `type: DuckDB; specification: DuckDB { }; auth: Test;` and 10
  hand-write `LocalH2`/`DefaultH2` blocks (LANG). The per-database pair of specification and auth is test knowledge
  that `StorelessRuntime.specificationAndAuth` already holds privately.

## 5. Misses (database-specific behaviour the scanner did not flag)

1. **The guard is blind to four of the eleven dialects.**
   - `DialectBoundaryTest.java:116-118`: the `DIALECT_CONSTRUCTIONS` regex lists H2, H2Modern, DuckDb, Postgres,
     SQLite, Databricks, Snowflake and BigQuery. It omits MariaDb, MySql, Trino and ClickHouse, which
     `core/src/main/java/com/legend/database/Databases.java:55-61` constructs.
   - The pin `"Databases.java", 7` therefore under-counts; the real number is 11.
   - A `new MariaDb()` anywhere in main code passes the guard.
2. **The DatabaseType census covers 3 of 11 databases.** `DialectBoundaryTest.java:135` counts only `H2|DuckDB|Postgres`.
   A `DatabaseType.SQLite`, `.Snowflake` or `.MariaDB` decision outside the owners goes unseen. The name census at
   139-141 also lacks Snowflake, BigQuery, Databricks, MariaDB, MySQL, Trino and ClickHouse.
3. **The driver census misses most drivers and one source root.**
   - `DialectBoundaryTest.java:123` checks only `org.(duckdb|h2|postgresql|sqlite)`.
   - It misses `org.mariadb`, `com.mysql`, `net.snowflake`, `com.databricks`, `io.trino`, `com.clickhouse` and
     `com.google.cloud.bigquery`.
   - The census walks only `core/src/main/java/com/legend` (the `MAIN` constant), not `core/src/main/duckdb`.
4. **The JDBC funnel rules miss the Postgres and SQLite drivers.**
   - `ArchitectureTest.java:650,676,821` lists only `org.duckdb..` and `org.h2..`.
   - `org.postgresql..` (a core driver, `core/BUILD.bazel:311`) and `org.sqlite..` (`:312`) native APIs would bypass
     the funnel, interpreter and PureV1Api rules.
   - `JdbcSurfaceCensusTest.java:66-67` and `TenetRatchetTest.java:95-97` have the same gap: no `org.postgresql`.
5. **A duplicated H2 server-version fact.** `exec/PlanRunnerTest.java:321`, `H2_SERVERS = Versions("2.1","2.2")`,
   copies `H2.SERVERS` / `Databases.servers(H2)`. The scanner missed it because `H2_SERVERS` gets no `\bh2\b` word
   boundary. `PlanMakerTest.java:106` asserts the same literal list. `exec/PlanRunnerTest.java:199-209` (`H2_TYPES`)
   copies H2's per-ValueKind type-spelling table (`NUMERIC`, `TIMESTAMP(9)`). It is a hand-made plan fixture, but it
   drifts if H2's table changes.
6. **Hand-enumerated backend matrices with no registry to iterate.**
   - `sql/dialect/SqlWriterTest.java:73` (`List.of(new DuckDb(), new H2(), new Postgres())`)
   - `setup/SeedSpellingTest.java:35-36`
   - `StoreTypesTest.java:170`
   - `PlanMakerTest.java:74,85,164,184,196,243,353`
   - `exec/PlanRunnerTest.java:291-292,368-369`
   - `lowering/QuotedColumnNameTest.java` `@ValueSource`

   A 12th database is silently left untested. A `Databases.all()`, or iterating `DatabaseType` filtered by a
   capability such as `Sessions.inProcess`, would make coverage explicit.
7. **A DuckDB file convention used as the leak probe.** `server/ConnectionLeaseTest.java:240`,
   `Path wal = Path.of(own + ".wal")`, uses DuckDB's write-ahead-log file name as the connection-leak signal. The
   scanner had no product token to catch. It is test-only and DuckDB-specific by design. A per-database connector
   probe such as `openConnections()` would generalise it.
8. **The H2 specification keyword used for every database type.** `PlanMakerTest.java:43-60` and `:178` declare every
   database type, DuckDB included, with `specification: LocalH2 { testDataSetupCSV: ... }; auth: DefaultH2`. Test
   data rides on H2's keyword whatever the database. The scanner did not flag it because the type is `%s` or
   `type.name()`. Note this if the per-database connector takes over the test-data declaration.
9. **Not a leak, despite the name.** `testing/Engine.java` and `testing/Platform.java` use `com.legend.parser.Dialect`.
   That is the Pure grammar level (`LEGEND_ENGINE`, `LEGEND_PLATFORM`), not a SQL dialect.
