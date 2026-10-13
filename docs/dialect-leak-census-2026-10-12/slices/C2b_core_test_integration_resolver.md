# C2b: core/src/test integration + resolver, classified

Input: `parts/C2b_core_test_integration_resolver.tsv`, which has 6,808 hits in 109 files. Output: one row per id in
`C2b_core_test_integration_resolver.tsv`. The id set was checked against the input: 6,808 = 6,808, identical.

Method: I lexed every file to find which string literal or text block holds each hit. I then found the call that
owns the literal and the argument position, and sorted the literal by content: a Pure model, a Pure query, SQL, or
plain text. I read each distinct construct before writing the rules (the base class, every harness file, and samples
of every bucket).

## Counts

| class | hits | groups |
|---|---:|---:|
| TEST-GOLDEN | 3,921 | 104 |
| LANG | 2,023 | 77 |
| TEST-LEAK | 496 | 12 |
| FP | 341 | 45 |
| DIAG | 27 | 7 |
| **total** | **6,808** | **245** |

No hit in this slice is LEAK-*, SEAM, DIALECT-INTERNAL, DIALECT-SHARED or SINGLE-BACKEND-APP.

## The shape of the problem in one paragraph

These ~4,000 tests have **no per-backend test interface**. The one shared base, `AbstractDatabaseTest`, makes the
backend two free `String` hooks: `getDatabaseType()` and `getJdbcUrl()`. All 27 subclasses override both (26 return
DuckDB, 1 returns SQLite) and open their own `DriverManager` session. About 69 other classes open
`jdbc:duckdb:` inline. The 13 resolver classes without a SQL-text golden each copy a `sqlOf()` helper that hardcodes
`new DuckDb()`. The only real backend switch is the stress-corpus harness: `StressSuites`, `StressTool` and the two
`StressServiceSuites*Test` lanes. It threads a `boolean h2` through URL, `DatabaseType`, session policy and ledger
names.

Both seams the fix needs already exist:
- `Sessions.openPrivate(DatabaseType)` (`exec/Sessions.java:318`), which opens a private in-memory instance for
  H2, DuckDB or SQLite;
- `Databases.dialect(type)`, which `CompileLatency.java:189` already uses correctly.

**Proposed interface (one class):** a test-side connector, `com.legend.testing.TestBackend`, keyed by `DatabaseType`:
- `type()`;
- `open()`, which delegates to `Sessions.openPrivate`;
- `openUtc()`, which applies the session zone the database's way;
- `dialect()`, which calls `Databases.dialect(type)`;
- `connectionBlock(fqn)`, the Pure `###Connection` text for the type;
- `seed(DatabaseDefinition, rows)`, which uses `setup/Ddl.createTable` and `SqlDialect.render(SqlDdl/SqlDml)`;
- `ledgerSuffix()`.

The stress lanes would also want `defaultSessions()` and `normalizeCell(kind, text)`.

## TEST-LEAK groups (12)

### 1. `StressSuites h2/duckdb backend switch`: 23 hits
- **Where:** `core/src/test/java/com/legend/integration/StressSuites.java` lines 37, 72, 84-85, 89-91, 99, 112, 147-149.
- **What it does:** `Config(boolean h2, …)` selects the backend.
  - L85 picks the default session policy: `FRESH_PER_TEST` for H2, `SHARED` for DuckDB.
  - L89 builds the URL: `"jdbc:h2:mem:" + H2Settings.SETTINGS` or `"jdbc:duckdb:"`.
  - L90-91 pick `DatabaseType.H2` or `DatabaseType.DuckDB`.
  - L112 opens the session with `DriverManager.getConnection(jdbcUrl)`.
  - L99 and L147-149 add a `"-h2"` suffix to the ledger file names.
- **Why it is a leak:** this is the stress harness's backend switch. A third lane would mean editing every one of
  these lines. L89 also re-spells the private-H2 URL that `Sessions.openPrivate` owns (`Sessions.java:320-321`). H2 is
  here as the engine-faithful **referee** lane.
- **Where it belongs:**
  - `Config(DatabaseType backend, …)`;
  - opener `() -> Sessions.openPrivate(backend)`, or `TestBackend.of(backend)::open`;
  - policy default `TestBackend.defaultSessions()`;
  - ledger suffix `TestBackend.ledgerSuffix()`.
- **Size:** about 15 lines.
- **Coupling:**
  - callers: `StressTool`, `StressServiceSuitesTest`, `StressServiceSuitesH2Test`;
  - `ServiceTestRunner(opener, policy, sessionType)` (main, `test/ServiceTestRunner.java:154-160`);
  - `core/BUILD.bazel:733-765` (lanes `stress_suites`, `stress_suites_h2`, `stress_tool`).

### 2. `StressServiceSuites h2 lane flag`: 4 hits
- **Where:** `StressServiceSuitesTest.java:37-41`.
- **What it does:** `assertAtLeast(boolean h2, int floor)` passes the boolean on to `StressSuites.Config` and builds
  the name of the `-h2` fail ledger.
- **Why it is a leak:** the backend is a positional boolean. Its callers are listed under misses:
  - `assertAtLeast(false, MIN_PASS)` at :33;
  - `assertAtLeast(true, MIN_PASS_H2)` at `StressServiceSuitesH2Test.java:17`.
- **Where it belongs:** `assertAtLeast(DatabaseType, floor)`.
- **Size:** about 5 lines.
- **Coupling:** `MIN_PASS` and `MIN_PASS_H2` (:26, :28) are **ratchet pins**. Per AGENTS.md they move only with a
  dated justification. Keep their values and rename only if keyed.

### 3. `StressTool --backend duckdb|h2 option`: 6 hits
- **Where:** `StressTool.java:25, 27-28, 41`.
- **What it does:** the CLI default is `"duckdb"`. It validates against the regex `duckdb|h2` and maps the value to
  `"h2".equals(backend)`. The same list is repeated in the javadoc at :13 and in `core/BUILD.bazel:758`.
- **Why it is a leak:** this is a hand-written list of backend names.
- **Where it belongs:** parse to a `DatabaseType` and refuse anything that is not `Sessions.inProcess(type)`.
- **Size:** about 5 lines.
- **Coupling:** `//core:stress_tool`.

### 4. `CorpusDifferentialTest DuckDB-pinned render+session`: 6 hits
- **Where:** `CorpusDifferentialTest.java:46` (`new com.legend.sql.dialect.DuckDb()`) and :63 (`jdbc:duckdb:`).
- **What it does:** it renders every stress service with the DuckDB dialect and runs it on an inline DuckDB session.
- **Why it is a leak:** it bypasses the runtime's declared type. See misses for :81-83, which render every runtime
  with DuckDb, and :156-157, a DuckDB-only TIMESTAMP text fix-up.
- **Where it belongs:**
  - render with `Databases.dialect(Compiler.executesOn(ctx, rt).type())`, as `CompileLatency.java:189` does;
  - open the session with `TestBackend.open()`;
  - move the cell normalisation to `TestBackend.normalizeCell(kind, text)`.
- **Size:** about 6 lines.
- **Coupling:**
  - `//core:corpus_differential_test` (`core/BUILD.bazel:528`);
  - its `seed.sql` is DuckDB DDL from `scripts/corpus/differential.py:41-49` (out of slice);
  - `QUARANTINE.txt`;
  - `SkipCensusTest:54` (comment only).

### 5. `ServiceTestRunner opener+type pair (inline URL)`: 5 hits
- **Where:** `ServiceTestProvisionKeyTest.java:107-108`.
- **What it does:** it builds `new ServiceTestRunner(ctx, () -> DriverManager.getConnection("jdbc:duckdb:"), policy,
  DatabaseType.DuckDB)`. The URL and the type are paired by hand and must agree.
- **Where it belongs:** a constructor `ServiceTestRunner(ctx, DatabaseType, policy)` that opens through
  `Sessions.openPrivate`. Alternatively, `TestBackend.of(DuckDB)::open`.
- **Size:** 2 lines here, plus about 5 in `ServiceTestRunner`.
- **Coupling:** the `ServiceTestRunner` constructors (main). `StressSuites` uses the same pairing.

### 6. `AbstractDatabaseTest backend hooks (getDatabaseType/getJdbcUrl)`: 109 hits
- **Where:**
  - `AbstractDatabaseTest.java:285` (abstract `String getDatabaseType()`);
  - :290-300, the connection template with `type: %s` and `specification: DuckDB { };` at :296;
  - :411 (abstract `String getJdbcUrl()`);
  - three lines in each of the 27 subclasses: the `getDatabaseType()` return, the `getJdbcUrl()` return, and
    `DriverManager.getConnection(getJdbcUrl())`. The subclasses are AsOfJoinChecker, ConcatenateFlattenChecker,
    DuckDBIntegration, DynaFunctionIntegration, ExecutionResultIntegration, ExtendChecker, ExtendWindowChecker,
    FilterChecker, FoldChecker, FromChecker, GroupByChecker, JoinChecker, LetChecker, PivotChecker,
    RelationApiIntegration, RenameChecker, RenderCsvIntegration, **SQLiteIntegration**, ScalarFunctionIntegration,
    SelectDistinctChecker, SlicingChecker, SortChecker, StructFilterIntegration, SubsumptionWitness,
    TypeConversionChecker, TypeInferenceIntegration and WriteChecker. The exact lines are in the TSV.
- **What it does:** this is the only shared "pick a backend" device. The backend is two free strings, and every
  subclass opens its own session.
- **Why it is a leak:**
  - The template fixes `specification: DuckDB { }` for every backend, so SQLite declares
    `type: SQLite; specification: DuckDB {}`. It works only because both parse as in-memory.
  - The hook is not authoritative. Subclass bodies inline their own `type: DuckDB` connection blocks (about 70 in
    `DuckDBIntegrationTest`).
  - Subclass bodies also run DuckDB-only SQL: `SET timezone` (group 11), and `dbScalar` in
    `ExtendCheckerTest:1979-2005`.
- **Where it belongs:** the base takes a `TestBackend backend()` (a `DatabaseType`). It opens the session in its own
  `@BeforeEach` through `backend().open()` and derives the connection block from `backend().connectionBlock(...)`.
- **Size:** base about +15/-12 lines. Subclasses lose about 8 lines each across 27 files, roughly -215 lines.
- **Coupling:** no guard pins these files. `JdbcSurfaceCensusTest`'s test register was deleted on 2026-09-29, and
  `ArchitectureTest.javaSqlIsFunnelledToTheCharteredSeam` checks `CORE_PROD_CLASSES` only. Lane:
  `core_tests_integration`.

### 7. `AbstractDatabaseTest.setupDatabase shared raw-SQL seed`: 23 hits
- **Where:** `AbstractDatabaseTest.java:418-462`.
- **What it does:** raw `CREATE TABLE T_PERSON/T_ADDRESS` and `INSERT` text. It runs on whichever backend the
  subclass picks: DuckDB in 26 subclasses, SQLite in 1.
- **Why it is a leak:**
  - Multi-backend seeding is composed as SQL text outside any dialect.
  - The same schema is already declared as the Pure store `PERSON_DATABASE` at :88-108, so it exists twice.
- **Where it belongs:** `TestBackend.seed(...)`. It builds each table with
  `com.legend.setup.Ddl.createTable(TableDefinition, null)` (`main/.../setup/Ddl.java:67`), renders it with
  `backend.dialect().render(SqlDdl)`, and writes the rows as `SqlDml`.
- **Size:** about 40 lines become about 10.
- **Coupling:** called from the `setUp` of all 27 subclasses.

### 8. `inline jdbc:duckdb: test session`: 228 hits in 69 files
- **Where:** `DriverManager.getConnection("jdbc:duckdb:")` in `@BeforeEach`/`@BeforeAll`/try blocks, one line per
  site. Some sites, with their lines:
  - RelationalMappingIntegrationTest:38, AssociationIntegrationTest:44, ComputedProjectIntegrationTest:84, 148, 212,
    264, 333, 406;
  - every resolver test that executes, e.g. ResolveSimpleClassTest:80, ResolveNavigationTest:73,
    ResolveM2mTest:63, 175;
  - StressTest*:20-43, DuckDBStructSyntaxTest:17, 32.

  The full list is in the TSV (group label).
- **What it does:** each class opens a backend by URL.
- **Why it is a leak:** URLs and drivers belong behind a per-database interface. One already exists:
  `Sessions.openPrivate(DatabaseType.DuckDB)`.
- **Where it belongs:** `TestBackend.of(DuckDB).open()`, or `Sessions.openPrivate(DuckDB)` directly.
- **Size:** 1 line per site, about 75 sites.
- **Coupling:** none pinned. `DuckDBStructSyntaxTest` is a DuckDB driver probe (struct literals). It belongs with a
  DuckDB connector test rather than in `integration/`.

### 9. `StorelessRuntime DatabaseType.DuckDB pin (+inline URL)`: 14 hits
- **Where:** FoldCheckerTest:68, MetamodelMappingStoreTest:90, MetamodelQueryFunctionsTest:100,
  MetamodelStoreSubstitutionTest:60, 93, MetamodelStoreTest:55, 109.
- **What it does:** each test types its storeless runtime as `DatabaseType.DuckDB`, then executes on a session it
  opened separately by URL. The URLs are at :46/:51/:81/:91 in those files, in group 8.
- **Why it is a leak:** the typed pick on its own is fine. The leak is the two sources of truth: the type here and
  the URL elsewhere. I was unsure here and chose the leak class, as the guide says.
- **Where it belongs:** one `TestBackend` constant per class, used for both the type and the session.
- **Size:** 1 line per site.
- **Coupling:** `com.legend.test.StorelessRuntime` (main).

### 10. `StressTest* hardcoded DuckDb dialect`: 18 hits
- **Where:** StressTest:296, StressTest100K:219, StressTest10K:284, StressTestChaotic:333,
  StressTestComplexQueries:243, StressTestDense:253.
- **What it does:** `var dialect = new com.legend.sql.dialect.DuckDb()` renders plans so they can be timed and run.
  The model's runtime already declares `type: DuckDB`.
- **Why it is a leak:** it is a second source of truth. It skips the `Databases` seam.
- **Where it belongs:** `Databases.dialect(Compiler.executesOn(ctx, "test::RT").type())`, as
  `CompileLatency.java:189` does.
- **Size:** 1 line per file.
- **Coupling:** none.

### 11. `inline DuckDB session setup SET timezone`: 8 hits
- **Where:** ComputedProjectIntegrationTest:86, 150, 214, 266, 335, 408; ExecutionResultIntegrationTest:34;
  TypeInferenceIntegrationTest:41.
- **What it does:** `stmt.execute("SET timezone='UTC'")` sets the zone on a fresh session.
- **Why it is a leak:**
  - Session setup is spelled the DuckDB way. H2 and Postgres use `SET TIME ZONE`.
  - Two of these classes are `AbstractDatabaseTest` subclasses, so this undoes the base's pluggable hook.
- **Where it belongs:** `TestBackend.openUtc()`, or a zone option on `Sessions.openPrivate`. This is the session-side
  counterpart of `exec/H2Settings`.
- **Size:** 8 call sites become 1 line each, plus about 8 lines in the connector.
- **Coupling:** none.

### 12. `resolver sqlOf() pins new DuckDb() to execute`: 52 hits
- **Where:** 13 resolver classes that have no SQL-text golden: ChainTypeDispatchTest:8, 87; NavigationDepthTest:9,
  119; ResolveDeepEmptinessProbeTest:14, 102; ResolveOuterDatedNavTest:13, 101; ResolveUnionChainTest:13, 114;
  ResolveUnionJtcProbeTest:13, 113; ResolveUnionMultiHopProbeTest:13, 107; ResolveUnionOuterDateProbeTest:13, 112;
  ResolveUnionSelfJoinProbeTest:13, 73; ResolveUnionV4ProbeTest:13, 208; StackDesignWitnessTest:12, 192;
  StackRatchetWitnessTest:12, 148; StackShapeWitnessTest:12, 151.
- **What it does:** each class carries its own copy of `sqlOf()`. The copy runs `StoreResolver`, then `Lowerer`, then
  `new DuckDb().render(plan)`, and executes the result on the class's inline DuckDB connection. Their runtimes often
  declare no connection at all.
- **Why it is a leak:** the backend for executing these tests is picked by a dialect class name in 13 copies. In the
  13 resolver files (and `UnionTargetLeanJoinTest`) that do golden the SQL text, the same pin is TEST-GOLDEN.
- **Where it belongs:** one shared resolver harness with `BACKEND = TestBackend.of(DuckDB)`. It renders through
  `BACKEND.dialect()` and executes through `BACKEND.open()`.
- **Size:** the helper is about 30 lines. Each file then loses about 15-20 lines; across the 26 resolver files that pin `DuckDb`, that is
  roughly -450.
- **Coupling:** none.

## SEAM, DIALECT-SHARED, SINGLE-BACKEND-APP

- None in this slice.
- SEAM *use*, not a hit: `CompileLatency.java:189` renders with
  `Databases.dialect(Compiler.executesOn(ctx, rt).type())`. It is the pattern groups 4, 10 and 12 should copy.

## Benign groups, briefly (one line per family; the per-file groups are in the TSV)

- **TEST-GOLDEN: `DuckDB fixture SQL in <F>`** (3,722): `CREATE TABLE`, `INSERT`, `DROP` and probe `SELECT` run on
  the DuckDB session the test exercises, through `execute`, `sql(...)`, `exec`, `rows` and `dbScalar` helpers. Two
  notes:
  - `ExtendCheckerTest:1979-2005` reads DuckDB's own clock in DuckDB SQL (`year(current_timestamp AT TIME ZONE…)`).
  - `RelationalMappingIntegrationTest:2976` creates a schema for its Pure model.
- **TEST-GOLDEN: `asserted SQL fragment` / `expected DuckDB SQL` / `DuckDb dialect pinned for golden SQL`**
  (81 + 20 + 67): `sql.contains("PARTITION BY")`, `count(sql,"SELECT")`, and whole-SQL `assertEquals("""SELECT …""", sql)`
  in ResolveSimpleClassTest.
- **TEST-GOLDEN: `SQL suffix on rendered SQL`** (17): `exec(sql + " ORDER BY 1")`. The DuckDB-only
  `" ORDER BY ALL"` appears at StackDesignWitnessTest:199 and StackRatchetWitnessTest:155. Group 12's harness fix
  covers these.
- **TEST-GOLDEN: `SQLite fixture SQL in SQLiteIntegrationTest`** (14).
- **LANG: `Pure model text in <F>`** (2,013): the `###Relational` store DSL (`VARCHAR(200)`, `NOT NULL`, `BIT`),
  `###Connection` blocks (`type: DuckDB; specification: DuckDB { }`), and generated Pure stores (the StressTest*
  `.append(" (ID INT, …")` and `StressTestChaotic.TYPE_MAP`, which builds Pure `Table` column types, not DDL).
  LegendLiteGapTest's `BOOLEAN` store-type rejection is here too.
- **LANG: `Pure metamodel column type`** (10): MetamodelQueryFunctionsTest asserts `meta::relational` column types
  such as `"VARCHAR(20)"`.
- **FP** (341):
  - DisplayName and assertion English ("filter with OR", "JSON should contain…", "NULL FK");
  - data values (`"000hi"`, `"2|VARCHAR"`);
  - `getMetaData().getColumnCount()` (19);
  - class names such as `DuckDBIntegrationTest`;
  - `store::DB2` (a Pure store name);
  - "oracle" meaning the reference evaluator (CorpusDifferentialTest:31).
- **DIAG** (27): DisplayNames and messages that name DuckDB, H2 or SQLite. Examples: WindowFunctionTest's
  "→ QueryService → DuckDB", and the ResolveNavigationTest:338-339 note "H2 CONCAT / DuckDB concat".

## Misses (database-specific behaviour the scanner did not flag)

1. **`AbstractDatabaseTest.java:285` and `:411`:** the backend hooks are declared as free `String`s, not a
   `DatabaseType`. At :295 `type: %s` is formatted from that string, while :296 pins the spec to DuckDB.
2. **`AbstractDatabaseTest.java:88-108` vs `:418-462`:** the T_PERSON/T_ADDRESS schema is declared twice, once as a
   Pure store and once as raw DDL. `setup/Ddl.createTable` plus `SqlDialect.render(SqlDdl)` could derive the DDL per
   backend from the store.
3. **`StressServiceSuitesTest.java:26` and `:28`:** `MIN_PASS_H2` and `MIN_PASS` are per-backend ratchet floors kept
   as separately named constants. **`StressServiceSuitesTest.java:33`** calls `assertAtLeast(false, …)` and
   **`StressServiceSuitesH2Test.java:17`** calls `assertAtLeast(true, …)`: the boolean literal *is* the backend.
   `core/BUILD.bazel:733-756` has one `junit_test` lane per backend.
4. **`CorpusDifferentialTest.java:81-83`:** every service is rendered with the pinned DuckDb dialect, whatever its
   runtime declares. The corpus under `core/src/test/resources/{stress,projects}` declares some H2, Postgres,
   Snowflake and Databricks connections.
5. **`CorpusDifferentialTest.java:156-157`:** a DuckDB-specific result-text fix-up strips `".0"` from TIMESTAMP text
   ("DuckDB renders a TIMESTAMP with fractional seconds").
6. **`StressSuites.java:89`:** it re-spells the private H2 instance (`jdbc:h2:mem:` + `H2Settings.SETTINGS`, no user).
   `Sessions.openPrivate(H2)` owns that spelling at `Sessions.java:320-321`, with a private name and `"sa"`. That
   makes two spellings of the referee session.
7. **`main/.../test/ServiceTestRunner.java:154-160`:** the API takes an opener lambda and a `DatabaseType`
   separately, so every caller pairs a URL with a type by hand (StressSuites:111-112,
   ServiceTestProvisionKeyTest:107-108). This is adjacent to the slice.
8. **`TypeInferenceIntegrationTest.java:2507` and `:2534`:** the assertions accept `val instanceof java.sql.Array` as
   a pass, which tolerates the DuckDB driver's list shape. On another driver, a wrong array result would pass the
   same way.
9. **No resolver test base:** 26 `com.legend.resolver` test classes each copy `sqlOf()` (with `new DuckDb()`)
   and an `exec()` over a static inline DuckDB connection. Running the resolver suite on another backend therefore
   means 26 edits; see group 12.
10. **`StressTool.java:13` and `core/BUILD.bazel:758`:** the javadoc and BUILD comment repeat the `duckdb|h2`
    backend list.
