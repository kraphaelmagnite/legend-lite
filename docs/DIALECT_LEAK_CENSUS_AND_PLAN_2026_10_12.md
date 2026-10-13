# The dialect-leak census and the plan to close it (2026-10-12)

**Status: PLAN, for review. Nothing below has been implemented, except five items that neema2/legend-lite#30 did on
its way to main (listed in the evidence folder's README).**

## 0. Start here: the handoff to the session that implements it (2026-10-12)

**Before Phase 0.**
1. **The baseline is main.** The ClickHouse and Postgres burn-downs that followed neema2/legend-lite#30 land on main
   first, in neema2/legend-lite#34, so "pure moves produce zero rendered-SQL diffs" has one fixed baseline. That PR's
   file list is what they touch: chiefly `ClickHouse.java`, `Postgres.java`, `SqlTypeCensus`, `Scalars` and
   `SqlExpr.OrderedListAgg`, with the harness (`EmbeddedClickHouse`, `PctBackend`), the guards (`PctCensusGate`,
   `ChannelBLane`, `DialectBoundaryTest`) and the ClickHouse and Postgres PCT ledgers.
2. **Main moves under you.** Other sessions land in `sql/dialect` too: the `#SQL{...}#` line added `QuantileOrder`
   and `RootNumericTypes` (see `docs/IN_FLIGHT.md`). Announce this program there before starting, re-read it before
   each phase, and merge main between phases rather than at the end.
3. **Re-run the census on the new head** (the evidence README, "To re-run"). The line numbers below cite 7afb645.
   Since then the dialects have grown: ClickHouse's session table and Variant carriers, and Postgres' printf, maps,
   string distances and jsonb decoding. One new leak to file is the ClickHouse session URL (`sessionJdbcUrl`) in
   `testing/`, a P5/P6 item.
4. **Decided by the user, 2026-10-13:** D1 to D8 and the base-renderer shape (P1b), each as §6 now records it.
   Several changed from the recommendations first written here: D2 (a rule by the declared database), D5 (all client
   SQL through the platform), D6 (only the orphan scripts and tools go), D8 (fold) and P1b (an exhaustive switch in
   every concrete dialect, families included).

**The proof for every pure move: a whole-lane SQL diff, not only the goldens.**
- Run each lane with `--jvmopt=-Dlegend.diagnostics=dump-sql` before the move and after it, and copy each target's
  `test.log` (under `bazel-testlogs/pct/<target>/`) out of the tree before anything deletes it.
- Diff whole statements, not lines. The dump prints each statement as rendered, which spans several lines (FROM,
  WHERE, GROUP BY, ORDER BY and LIMIT each start one; `AnsiSqlRenderer.nl`), and only its first line carries the tag.
  So a record runs from one tag to the next, and every tag counts: `[sql:statement]`, `[sql:side]`, `[sql:let]`,
  `[sql:value]`, `[sql:session]`, `[sql:system]` and `[script]` (`StatementOrigin`, `Executor`). A grep for one
  tag's lines would compare SELECT lists only.
- An empty diff is the evidence a move changed nothing: on all eight local databases every phase, and on the three
  clouds when they run (§5: after Phase 2 and at the end). The dialect unit goldens alone cover a fraction of the
  arms.

**Running the lanes** (measured in a root cloud container, 4 cores, 15 GB, a ~40 GB disk allowance):
- **Postgres refuses root.** Run it with
  `--strategy=TestRunner=standalone --run_under="$PWD/tools/postgres/as_unprivileged.sh ubuntu"`, after
  `chmod o+x /root` (`docs/GATES.md`). Never export `TEST_TMPDIR` in the shell: Bazel then takes it as its own output
  root, starts a second server there, and fills the disk (2026-10-12). Bazel sets it for each test itself.
- **The clouds** read their credentials from the file `LEGENDLITE_PCT_CLOUD_PROPERTIES` names
  (`~/.legendlite/cloud-pct.properties`, outside the repo). Set it on the command,
  `LEGENDLITE_PCT_CLOUD_PROPERTIES=$HOME/.legendlite/cloud-pct.properties bazel test ...`: it reaches the test only
  through `env_inherit` (`pct/BUILD.bazel`), and the container does not set it. Never print the file's values.
- **Disk.** A full run of every lane at once filled the allowance (the embedded servers' data, the test logs, and the
  disk cache copying both). So run in batches of about 20 targets with `--disk_cache= --local_test_jobs=2`. Copy out each
  `test.log` you need (its summary, and the whole log when it holds a SQL dump for the proof above), then delete
  `bazel-testlogs/pct` between batches, and the batch's runfiles trees too
  (`bazel-bin/pct/*.runfiles*`, about 130 MB a target, rebuilt on demand): 30 cloud targets after a local batch's
  leftovers filled the disk again (2026-10-13).
- **Memory.** ClickHouse and Trino in one batch at `--local_test_jobs=2` were killed by the container's memory limit
  (two embedded servers plus two test JVMs; 2026-10-13). Run them in separate batches, or Trino at
  `--local_test_jobs=1`.
- **Times** (both channels, five suites; H2 runs the Relation suite only): DuckDB, SQLite, H2, MariaDB and MySQL
  about 10 minutes a batch; ClickHouse
  and Trino about 25 (Trino's channel B Essential alone about 4); Postgres about 6; the three clouds the longest.
- **One failing test.** Channel B takes `-Dlegend.diagnostics=dump-sql,chb-only=<test-name-substring>` and prints
  each statement, the failing ones marked `[sql-fail]`. Such a run always ends FAILED: the filter shrinks the
  discovered list, and every channel B test asserts its discovery count equals the committed ratchet
  (`ChannelBLane`). Read the log, not the status (`ChannelB.java`'s comment that a scoped run satisfies the pins is
  stale). To read the IR with its types, a throwaway JUnit test in
  `core/src/test/java/com/legend/sql/dialect/` can print `Compiler.query(ctx, q).lower(runtime, false).plan()` and
  `.plan(runtime).sql()`. Use the dialect's `renderPasses()` to see what each pass does to a node's type. Delete the
  test afterwards.

**Measure in parallel: three containers, not one** (the user, 2026-10-13). Every phase re-measures the eight local
databases on both channels, and the clouds join after Phase 2 and at the end (§5): about 3 hours for all eleven in
one container, in small batches because of the memory and disk limits above. Spread it over sibling cloud sessions
instead: sibling 1 started at Phase 0 and reused for every phase, sibling 2 only for the cloud runs:

| container | databases | why |
|---|---|---|
| this session | DuckDB, SQLite, H2, MariaDB, MySQL | the light lanes, and the local gate |
| sibling 1 | Postgres, ClickHouse, Trino | the heavy embedded servers (ClickHouse and Trino together hit the memory limit) |
| sibling 2 | Snowflake, Databricks, BigQuery | the clouds, mostly waiting on the network |

- **Starting one.** `create_session` (the claude-code-remote tools) with no `environment_id`: the sibling gets this
  environment, so the same network policy and the `DB_AWS_*` variables the cloud credentials are read from. Give it
  `source_url` and the branch as `source_revision`. Each sibling is a full session with its own tokens and compute,
  and the clouds bill for their queries: start sibling 1 for a program that re-measures every phase, as this one
  does, and sibling 2 when the clouds run.
- **Its first job is the setup**, once: `docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §6 (bazelisk, the bats and
  reference-checkout overrides, the proxy flags in `~/.bazelrc`), the Postgres recipe above for sibling 1, the
  credentials file for sibling 2 (§4 there; never print a value), and a first build. Expect about half an hour before
  its first test; it stays warm for later phases until it is idle long enough to be reclaimed.
- **Each phase:** commit and push the phase's head; send each sibling the commit to fetch and its list of targets;
  each runs its lanes in the batches above and replies with every target's status and its channel B `measured:`
  lines. A sibling may not push to the branch, so its results come back as text: its reply (`send_message`), or its
  transcript (`get_session`, `list_events`) if no reply arrives.
- **One database, one container.** A cloud lane's fixtures live in the warehouse, so two containers running the same
  cloud database at once can collide; never assign a database to two containers.
- **The SQL diff runs in the same split:** each container dumps and diffs its own databases' SQL, before and after the
  move.

**Guards that a move trips** (each moves only with a dated reason, AGENTS.md):
- `CodeShapeGuardrailTest`: a method may have at most 250 lines; there may be at most 7 raw `new SqlExpr.Column(`
  sites, and there are exactly 7 today (one each in AliasPrefix, UnqualifyPivotArgs, EngineStyleH2,
  CarrierStrategies, Dedup, Fold and LambdaBinding), so a move that adds one trips it.
- `CarrierPurityRatchetTest`: no new direct carrier emission (`SqlFn.LIST_*` and the like) before the dialect; lower
  to a semantic node instead (`docs/CARRIER_REDESIGN.md` tenet #1). It blocked a fix in neema2/legend-lite#34.
- `ArchitectureTest`: dependency directions, and `staticCollectionStateIsImmutableOrRegistered` (static collections
  immutable, or registered).
- `DialectBoundaryTest`: no new database-name decision outside the dialect.
- `JdbcSurfaceCensusTest`, `RawSqlLedgerTest`, and the `SqlTextRatchetTest` and `TenetRatchetTest` that Phase 0
  itself edits.
- The census ceilings in `PctCensusGate`, and the channel B floors in `ChannelBLane`.

**Out of scope here; they belong to other lanes:**
- The platform's pins, the ones each database shares with DuckDB (34 on a database running all five suites; H2 runs
  Relation only and shares 1), belong to the compiler lane (functions never ported, instance identity);
  `docs/EXECUTION_PLAN_2026_09_26.md` is parked.
- MongoDB, Elasticsearch and SingleStore are new targets, not consolidation.
- The cloud corpus runs.

**The work behind this plan** is in `docs/dialect-leak-census-2026-10-12/`; start with its README. It holds the
method, the classification rules, the scanner that re-runs the census, and the nine reviewers' reports in full. The
census was taken at 7afb645, so line numbers below cite that commit. The code it audits is on main since c03ac6b.

**The ask (the user, 2026-10-12):** *everything SQL-related hidden in the SQL dialect; anything connection- or
workspace-related behind its own interface; SqlTypeCensus probably behind the dialect too; first a comprehensive
census of every leak, not a sample; then a plan we review together; raw SQL included.*

## 1. How the census was taken

**Two scanner passes over every tracked source file** (`.java .ts .tsx .js .mjs .py .kt`). Each file was lexed into
code, comments and string literals; comments were skipped.

- **Code patterns:**
  - dialect class names and references;
  - `DatabaseType.X`;
  - driver packages and objects;
  - JDBC calls such as `getDatabaseProductName` and `unwrap`;
  - `java.sql.Types` uses;
  - raw-SQL rewriting entry points;
  - session-setup entry points;
  - product names (DuckDB, Postgres, H2, Snowflake, ... MongoDB, Elastic).
- **String patterns:**
  - SQL statement heads and bodies;
  - SQL fragments (`COALESCE`, `ATTACH`, `information_schema`, `pg_*`, `duckdb_*`, ...);
  - product names;
  - `jdbc:` URLs;
  - type spellings;
  - vendor error text and SQLStates.

**The scanners found 27,956 candidate sites in 1,007 files.** Every one was then classified by a reviewer who read the
code, in 9 slices: core execution, core front end, the other product modules, the spec/pct/testing harness, core
tests (two slices), other modules' tests, tooling, and the dialect package plus the IR. Each slice's output was checked
to hold exactly one row per site, with the same id set. Each slice also reported **misses**: leaks the patterns could
not see. Those are in §7.

The scanners are kept in `docs/dialect-leak-census-2026-10-12/scanner/`, with the slices' reports beside them; the
per-site tables were not kept (the evidence README says how to rebuild them). Phase 0 below turns the census into a
permanent guard.

**The classes:**

| class | sites | groups | meaning |
|---|---:|---:|---|
| **LEAK-SQL** | 176 | 13 | SQL text composed outside the dialect |
| **LEAK-ID** | 91 | 10 | a branch on *which* database, outside the registries |
| **LEAK-TYPE** | 110 | 5 | a per-database type-name table outside the dialect |
| **LEAK-RAW** | 20 | 4 | raw SQL rewritten or lexed outside the dialect |
| **LEAK-CONN** | 41 | 6 | per-database connection handling outside a connector |
| **LEAK-ERR** | 3 | 1 | vendor error text matched outside the dialect or connector |
| **DIALECT-SHARED** | 247 | 53 | inside `sql/dialect/`, but a database's fact sits in a *shared* file, not its own class |
| **TEST-LEAK** | 1,002 | 45 | test code branching on the backend, or opening backend-specific sessions inline (36 groups live, 9 in dead code) |
| SEAM | 709 | 51 | the designated registries (`Databases`, `Sessions`, `PctBackend.Lane`, `EngineText`, guard registers) |
| DIALECT-INTERNAL | 3,585 | 64 | a dialect writing its own database's SQL: correct |
| LANG | 5,693 | 147 | the Legend language: store-DSL types, connection grammar, protocol names, `DynaFn`'s support table |
| SINGLE-BACKEND-APP | 3,759 | 69 | modules whose engine is DuckDB by design (datacube, engine-client, python, warehouse's own engine) |
| TEST-GOLDEN | 9,694 | 329 | tests asserting one dialect's SQL, or fixtures for the database under test |
| DIAG / FP | 2,826 | 204 | messages naming a product / false positives |

`DynaFn`'s per-database `Dialect` enum (1,298 sites) is LANG: it mirrors legend-engine's support table. Its only reader
is one error message (`RelOpTranslator.java:719`).

## 2. The verdict

**The claim "each database's differences live in one place, its dialect" is not true today.** The worst of it is
structural, not scattered:

1. **Inside the dialect package, a database's facts are spread over 4 to 9 files.** Its function names, type names,
   keywords and collection capabilities are rows in the shared `Spellings`, `TypeNames`, `Lexicon` and
   `CarrierStrategies.Caps`. Seven of the `Spellings` rows are *derived from DuckDB's row*: changing a DuckDB name
   silently changes seven other databases.
2. **The base renderer (`AnsiSqlRenderer`) is DuckDB and H2 in disguise.** About 25 arms spell DuckDB:
   `list(.. ORDER BY)`, `EXCLUDE`, `rowid`, `json_object`, `uuid()`, `time_bucket`, `epoch_ms`, `//`, `T[]`, `MAP`,
   `STRUCT` and strftime codes. Its carrier capabilities default to H2's, and its DDL types to H2's.
   - Every dialect that does not override an arm inherits DuckDB's spelling *silently*.
   - H2, H2Modern and EngineStyleH2 pass every function they do not handle up to those arms, so H2 receives DuckDB text
     where it should get a clear refusal.
3. **The IR carries DuckDB and SQL spellings** (AGENTS invariant 3a forbids this):
   - `SqlSource.Join.Kind` holds `"LEFT OUTER JOIN"` as a field.
   - Date arithmetic puts the string `"to_days"` (a DuckDB function name) into the IR.
   - Lowering compares `JSON_TYPE(..)` and `TYPEOF(..)` to DuckDB's type-name strings.
   - `RawSql`'s splitter and classifier lex SQL text inside the IR package.
4. **Raw SQL is rewritten in a shared file that switches on the target.** `RawSqlBoundary.Target { DUCKDB, POSTGRES }`
   (I added the Postgres arm last session, extending the pattern instead of fixing it). It sits beside two
   identity-in-disguise flags, `rawTarget()` and `rawH2IsNative()`.
5. **SQL is composed outside the dialect in product code:**
   - `TestDataGenerator`: about 400 lines of hand SQL.
   - `CatalogGrids`: `information_schema` SQL composed *in the type checker*.
   - `StatementExecutor`: a raw `CREATE SCHEMA`.
   - The engine-text `INSERT` and temp-table text.
   - A MariaDB session-variable query in `Sessions`.
6. **Reading results back holds per-database knowledge outside the dialect.** `SqlTypeCensus` and
   `Executor.pureOfSqlType` contain driver type-name tables, and `SqlTypeCensus` branches on dialect *class names*.
7. **There is no connector interface.** Connection knowledge is spread across five per-concern switches in `Sessions`,
   plus `StorelessRuntime`, `ConnectionResolver`, `CreateClass` and an H2 URL spelled in four places.
8. **The test harnesses have no backend interface:**
   - The corpus picks its backend with string switches and if-chains (`DuckWorkspaces` and `ServerWorkspaces` are
     called directly).
   - About 4,000 behavioural tests open `jdbc:duckdb:` inline (about 330 sites).
   - 26 resolver tests each copy a `sqlOf()` pinned to `new DuckDb()`.
9. **The guards that should catch all of this are blind to most databases** (at 7afb645):
   - `DialectBoundaryTest` counted 7 of the 11 dialect constructions and 3 of the 24 database types.
   - Its driver census and `ArchitectureTest`'s driver rules missed the Postgres, SQLite, MariaDB, ClickHouse, Trino
     and cloud drivers.
   - Since neema2/legend-lite#30's review, `DialectBoundaryTest` counts all 11 constructions and its driver census
     names every driver; `ArchitectureTest`'s driver rules still name DuckDB and H2 only.

**What is right today, and is the pattern to copy:**

- No dialect tests another dialect's identity.
- `CatalogRules` is a shared record type whose rows each dialect owns.
- `PctBackend.Lane` is already one per-database abstraction for PCT.
- `Databases` and `Sessions` are exhaustive switches with no `default`.
- `DuckDbAppenderLoad` and `DuckDbCells` are per-database objects behind interfaces (`BulkLoad`, `DriverCells`).

## 3. Real defects found on the way (not only structure)

| # | defect | evidence |
|---|---|---|
| 1 | H2 (and H2Modern, EngineStyleH2) get DuckDB spellings for functions they do not override, instead of an honest refusal | `H2.java:404` → `super.call`; base arms at `AnsiSqlRenderer.java:637-892, 1095-1270` |
| 2 | Postgres `int8`, ClickHouse `Nullable(Int64)` and BigQuery `INT64` are absent from the driver-type table that types dynamic-pivot and grid columns | `Executor.java:1062-1085` (read; a test will confirm the failure) |
| 3 | Every raw SQL on DuckDB is rewritten *as if it were H2 text*, including a user's native `executeInDb` SQL (an unaliased `count(*)` gets relabelled) | `DuckDb.java:273` → `RawSqlAdapt`; `RawSql` records no origin |
| 4 | `CorpusDifferentialTest` renders every service with the DuckDB dialect, even services whose runtime declares H2, Postgres, Snowflake or Databricks | `CorpusDifferentialTest.java:46, 63, 81-83` |
| 5 | On the Postgres corpus lane, on-demand fixtures run *without isolation*: `isolateBegin` returns null and the caller proceeds | `DuckWorkspaces.java:198-202`, `MinimalCorpus.java:692-696` |
| 6 | `AbstractDatabaseTest`'s SQLite subclass declares `type: SQLite; specification: DuckDB {}` | `AbstractDatabaseTest.java:285-296` |
| 7 | The warehouse wraps `BEGIN`/`COMMIT` in `postgres_query` on an attached Postgres catalog (expected to fail; read, not run) | `warehouse/.../WhConnection.java:66-93` |
| 8 | The warehouse reports engine "DuckDB" for an attached Postgres catalog | `warehouse/.../server/Sessions.java:46` |

## 4. The target: what adding a 12th database touches

The acceptance test for the whole program: **a new database is three classes and one line in each of three
registries.** Nothing else in the codebase may need an edit.

| layer | the one class per database | what it owns | registry (one exhaustive switch) |
|---|---|---|---|
| **SQL** (plan side, no JDBC) | `sql/dialect/<Db>.java` | All SQL text (queries, DDL, DML, session setup); its function names, type names, keywords and carrier capabilities, as constants in the class; reading raw engine-H2 text (`fromEngineH2`); catalog queries; identifier folding; reading the driver's type names back (`wireTypeName`) and its values (`normalize`, which exists) | `Databases.dialect(type)` |
| **Connection** (execution side) | `exec/connect/<Db>Connector.java` *(new)* | JDBC product name, URL from a connection spec, driver, in-process private instance, bulk loader, driver cells, held raise (MariaDB/MySQL), in-memory connection declaration | `Connectors.of(type)` *(new; replaces `Sessions`' five switches)* |
| **Test backend** (harness) | `testing/.../<Db>Backend.java` *(new; absorbs `PctBackend.Lane`'s arms)* | Starting the server (embedded or cloud), a fresh **workspace** (DuckDB `ATTACH`, Postgres `CREATE DATABASE`, H2 a fresh in-memory database, clouds a schema per package), fixture isolation, the referee's reader, the Pure connection block, seeding via the dialect, roster and pin names, census ceilings | `TestBackends.of(type)` |

The `DatabaseType` enum and the connection grammar are the Legend language (LANG). They stay where they are.

Phase 0 enforces this mechanically: database identity (product names, `DatabaseType.X` constants, dialect class names,
driver packages) may appear only in those files, the grammar and the guard registers.

## 5. The plan

Every phase lands green on its own:

- `//gates:local`;
- both channels of the local PCT lanes (DuckDB, H2, Postgres, SQLite, MariaDB, MySQL, Trino, ClickHouse), with no pin
  growing;
- the DuckDB and H2 corpus rosters unchanged;
- the leak ratchet (Phase 0) only shrinking.

**Pure moves must produce zero rendered-SQL diffs.** The proof is the whole-lane SQL diff of §0; an unchanged golden
is part of it, not all of it.

The cloud lanes (Snowflake, Databricks and BigQuery) run once after Phase 2 and once at the end.

### Phase 0: the guards see everything (first; no behaviour change)

- **`DialectBoundaryTest`:**
  - count all 11 dialect constructions. *Done in #30, 3cd1c81: the pin is now 11.*
  - name every driver package in its driver census. *Done in #30.*
  - census all 24 `DatabaseType` constants and every product name (still H2, DuckDB and Postgres only);
  - walk `core/src/main/duckdb`.
- **Driver-package rules** in `ArchitectureTest`, `JdbcSurfaceCensusTest` and `TenetRatchetTest`: add `org.postgresql`,
  `org.sqlite`, `org.mariadb`, `com.clickhouse`, `io.trino`, `net.snowflake`, `com.databricks`/`com.simba` and BigQuery.
- **`SqlTextRatchetTest`**: add `create schema` (its blind spot let `StatementExecutor.java:2951` through).
- **New `DialectLeakRatchetTest`**: the census's mechanical part as a shrink-only, per-file ratchet, outside an
  allowlist of owners. It covers product names in code, `DatabaseType.X`, dialect class names, driver imports, inline
  `jdbc:` URLs and `getDatabaseProductName`. At the end of the program every pin outside the owners is zero, and the
  ratchet becomes a ban.
- Each pin corrected here carries a dated justification ("the guard was blind; the census of 2026-10-12").

Size: about 300 lines of tests.

### Phase 1: one place per database inside the dialect package (pure moves)

- **1a. Rows into classes.** The `Spellings`, `TypeNames`, `Lexicon` and `Caps` rows become constants in their own
  dialect classes, following the `CatalogRules` pattern. The record types stay shared.
  - The seven rows derived from `DUCKDB.fnNames()` are written out as explicit lists. A one-off check at the move
    asserts each expanded set equals the derived one.
  - `MySql`'s rows are its own (P1b: every concrete dialect decides every point, families included); where they
    equal MariaDB's they say so by delegating explicitly.
  - About 750 lines move; none are added.
- **1b. A neutral base renderer.**
  - The ~25 DuckDB arms move into `DuckDb`.
  - `Caps.H2` and the H2 DDL types move into `H2`.
  - DIVIDE's literal `DOUBLE` becomes `castTypeName(DOUBLE)`.
  - Base arms throw `DialectCapability`, as `listCall` already does.
  - Every other dialect that inherits an arm today gets it explicitly or refuses it; see **decision D1**. First, an
    inventory of arm × dialect, read from the code and the PCT pins.
  - **The shape (decided by the user, 2026-10-13): an exhaustive switch per concrete dialect.**
    - Every vocabulary point is a switch expression over a closed enum, with no `default` arm, in every concrete
      dialect class: the functions (`SqlFn`, 175), the aggregates (`SqlAgg.Fn`), the scalar type names and the
      date-format parts. Adding a constant breaks the build in every dialect until each spells it or refuses it by
      name. Each dialect's switch is its complete table: what that database does with every function, in one place.
    - **Families included** (the user's call): `MySql`, `H2Modern` and the EngineStyle chain carry their own
      switches. A point the same as the parent's is one grouped arm delegating explicitly
      (`case A, B, C -> super.call(...)`), so a new constant asks "the same as the parent?" in every member too. The
      rule then has no exceptions.
    - **The base holds no spellings**: grammar only (SELECT, FROM, JOIN, WITH, window frames), and neutral helpers a
      dialect calls by name (`fn("name", args)`, infix, cast). Grammar outside ANSI (QUALIFY, ASOF, LATERAL's forms) is
      refused in the base and opted into by each dialect. A borderline point (`IS DISTINCT FROM`, which MySQL spells
      `<=>`) goes in the switch.
    - **Helpers implement, the switch decides**: a spelling several databases share is a named helper each calls in
      its own arm (the list strategies, the JSON-list family), never an inherited default.
    - **Enforced, not convention**: a guard requires each concrete dialect to declare its vocabulary switches itself,
      as switch expressions, with no `default ->`; the `Spellings` map (not checked by javac) folds into the switches.
    - **A capability matrix from the code**: every function rendered on every dialect, recorded as spelled, emulated
      or refused by name, as a generated table per database (and which arms no test reaches).
    - **Order**: first the switches reproduce exactly the SQL each dialect writes today, inherited DuckDB spellings
      included, written out (a pure move, proven by the whole-lane SQL diff); then D1 copies or refuses each inherited
      spelling as its own measured step.
    - The limit: exhaustiveness proves a decision was made, not that it is right; the lanes prove that.
    - The measurement behind the base-renderer question is in the evidence folder's README.
- **1c. Raw SQL into each dialect.** One hook, `SqlDialect.fromEngineH2(String) → List<String>`, replaces
  `RawSqlBoundary.Target`, `rawTarget()`, `rawH2IsNative()` and the shared `RawSqlAdapt` pass:
  - H2 returns the text unchanged;
  - DuckDB does today's rewrite;
  - Postgres does today's rewrite plus its type names; its identifier rule follows D8 (it quotes a bare name as
    written since d973b08);
  - the other eight refuse by name.

  Which text is translated (decision D2): raw text is in the language of the database its connection DECLARES, and
  is translated only when it runs on another one. An H2 test connection run on DuckDB is translated; `executeInDb` on a
  connection that declares Snowflake passes its text to Snowflake untouched, as legend-engine does. The DDL Java
  writes for CSV test data (`Ddl.setUpDataSqlsText`, H2's spelling today) stops being text: the dialect renders it
  from the IR. A neutral H2-statement lexer stays shared. `RawSqlLedgerTest` and `DialectBoundaryTest.RAW_H2_CALLERS` are re-pinned
  with a dated note. See **decision D2** on where raw text comes from.
- **1d. Odds and ends.**
  - `CatalogModel`'s `* REPLACE` moves to `DuckDb`.
  - `DdlSpelling.h2ExecIdentifier` moves to `H2`, and `setup/Ddl` asks the dialect instead of assuming H2's rule for
    every target.
  - The stale docs are fixed: AGENTS.md and `Lexicon` still say "SQLite is not a class"; there is also an orphaned
    `SqlDialect` javadoc and a wrong `RawSql` package note.

Size: about 1,500 lines touched, most of them moved.

### Phase 2: the IR says *what*, never *how* (invariant 3a)

- `Join.Kind.sql` becomes `joinKeyword(Kind)` on the renderer.
- Date arithmetic's `"to_days"` string becomes a typed unit operand (`DateShifts.java:67-73`, `Scalars.java:722, 3045`).
- The `JSON_TYPE(..) = 'OBJECT'` and `TYPEOF(..) = 'DATE'` compares become typed predicates (`JsonKindIs`, `TypeIs`).
  Each dialect renders them; today MariaDB refuses TYPEOF because "its names are DuckDB's". Sites:
  `CanonicalRenderSql.java:934-998`, `Scalars.java:2656`, `CollectionLanes.java:354`, `LiteralSpelling.java:232`,
  `Fold.java:1300`.
- `SqlAgg.Fn` names that double as DuckDB spellings (`AnsiSqlRenderer.java:1084-1086`) go through each dialect's
  spelling row.
- DuckDB strftime codes carried as strings become typed format tokens. **Decision D3.**
- `RawSql`'s splitter and classifier move out of the IR package into the dialect (`splitScript`, `isRowQuery`); the
  type checker's `executeInDb` sniff asks the dialect.

Size: about 400 lines. Goldens unchanged.

### Phase 3: SQL composed outside the dialect (product)

- **`StatementExecutor.dropAndCreateSchemaInDb`**: render the existing `SqlDdl.CreateSchema` through the session's
  dialect.
- **`setup/Ddl.insertText`**: the engine-text dialect renders `SqlDml`.
- **`CatalogGrids`**: the catalog SQL leaves the type checker. A typed catalog-grid node gets rendered by
  `SqlDialect.catalogGrid(kind, patterns)`. Its `data_type`→JDBC-code table becomes a dialect method.
- **`TestDataGenerator`, the largest item (about 450 lines):**
  - the live fetch, CSV envelope, compare temp tables and `EXCEPT` diff become IR rendered by the session's dialect;
  - the engine-H2 plan text is rendered by `EngineStyleH2` from IR;
  - the `duckType` table becomes the store's declared type plus the dialect's DDL spelling;
  - the hand-built `DATE '...'` literals, identifier folding and `"default"` schema go through the dialect.
- **`InProtocol` and `PlanText`** stop slicing and spelling engine text by hand; they render from IR.
- **One `EngineText` constant** for the engine's test database replaces the `"H2"` literals in `ContextReading`
  (a *default* that picks the dialect), `StatementExecutor:772`, `PlanText:35, 446` and `TestDataGenerator:1430, 1491`.
- An `EngineTextDialect` interface replaces `EngineStyleH2` as a parameter type (`StatementExecutor`, `PlanEnvelope`).
- **wasm `Boundary`**: `testDataSql` picks its dialect through `Databases`; `DuckDb.CATALOG_COLUMNS_SQL` becomes
  `SqlDialect.catalogColumnsQuery`.

Size: about 900 lines.

### Phase 4: reading results back is the dialect's

**What `SqlTypeCensus` is** (you asked): a *measurement*, not a feature. On every executed statement it compares the
type the compiler stamped on each output column with the type the database actually returned. It counts agreements,
named subtypings and outright mismatches: "label lies", pinned to zero on every PCT lane. To compare, it must translate
each driver's type names (Postgres `int8`, ClickHouse `Nullable(Int64)`, BigQuery `INT64`) into one vocabulary. That
translation table is per-database knowledge, and it lives in `exec/`, branching on dialect *class names*. It belongs on
the dialect.

- **`SqlTypeCensus`**: each dialect supplies `wireTypeName(String driverTypeName)`. The census asks the dialect and
  never names one. Its counts must not move; the PCT census gates pin them.
- **`Executor.pureOfSqlType`, `GridProbe` and `PctProbe`**: type by `java.sql.Types` code where that is unambiguous, and
  by the dialect's `wireTypeName` where it is not. This fixes defect 2, with a test per database.
- **`DynamicPivot`'s `BigInteger`/`BigDecimal` cases** (the ClickHouse one is mine, from 32e1e0b) and **`Executor`'s
  H2 `byte[]` JSON decoding** move into each dialect's `normalize`.

Size: about 400 lines.

### Phase 5: the connector interface (execution side)

- **`DatabaseConnector`, one class per database:**
  - `jdbcProduct()`;
  - `opening(spec, auth)`, the URL;
  - `inProcess()` / `openPrivate()`;
  - `bulkLoad()`, which absorbs `DuckDbAppenderLoad`;
  - `cells()`, which absorbs `DuckDbCells`. Postgres needs one too: pgjdbc hands a jsonb cell back as its `PGobject`,
    which nothing turns into text, so `testConcatenateTypeInference` stays pinned on Postgres until it exists
    (2026-10-12; converting it in the dialect's `normalize` broke the Postgres parity harness, which reads the object);
  - `heldRaise(Connection)` for MariaDB and MySQL; the SQL text comes from the dialect;
  - `inMemoryDeclaration()`, used by `StorelessRuntime` and `CreateClass`.
  - H2's private URL and its `H2Settings` suffix are spelled once, in H2's connector.
  - ClickHouse's connector carries what its burn-down put on the test harness's URL (2026-10-12,
    `docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §19.1): the raised `max_query_size` and AST limits on every connection,
    and, on a system connection, an HTTP session of its own (`session_id`), where its temporary tables live.
    Two PCT tests pass only through these (`testFormatFloat`, getAll's `testBasic`; their ledgers say so). The
    session needs more than the harness gives it (PR #34's review, 2026-10-13): `SystemDatabase.routeFor` keeps it
    for the model's life, but the server ends it after `session_timeout` idle (then every system query fails
    "unknown table"); it takes one statement at a time (SESSION_IS_LOCKED); and closing the JDBC connection does not
    end it, so its tables outlive the model by up to an hour.
- **`Sessions`' five per-concern switches collapse into `Connectors.of(type)`.**
  - `ConnectionResolver`'s `Opening` switch is a capability in disguise (`Named` means H2, `Held` means DuckDB or
    SQLite); it moves onto the connector.
  - Driver imports become legal only in connector classes, enforced by Phase 0's rule.
- **`Databases` shrinks to type → dialect.** Its facts become dialect methods:
  - `servers`, H2's version pin;
  - `loadsRowsInBulk`;
  - `inListTempTables`, which is engine text and goes to the engine-text dialect.

Size: about 600 lines. No behaviour change.

### Phase 6: test backends (the harnesses)

- **`TestBackend` per database**, built on its connector, with these methods:
  - `open()` and `openUtc()`;
  - `openWorkspace()`;
  - `isolateFixture()`;
  - `readerOn(workspace)` and `referee()`;
  - `connectionBlock()`;
  - `seed(TableDefinition)` via the dialect;
  - `name()`;
  - `censusCeilings(suite)`.

  `PctBackend.Lane`'s per-database arms move here. The five embedded-server launchers share an `EmbeddedServer`
  interface.
- **Corpus:**
  - The `BACKEND` switch and its `default -> DuckDB` arm, the `openSession` chain and the ~20 `H2_BACKEND ? h2 : duckdb`
    roster ternaries all become `TestBackend` calls.
  - `DuckWorkspaces` and `ServerWorkspaces` become `DuckDbBackend.openWorkspace()` and
    `PostgresBackend.openWorkspace()`.
  - The H2 fixture-aside SQL becomes `isolateFixture()`. A backend that cannot isolate refuses, which fixes defect 5.
  - The referee's routing by product name and its `DuckDBConnection.duplicate()` become `readerOn` and `referee()`.
  - The hidden warehouse lane, behind a second system property, becomes a backend of its own.
  - `CorpusOne` and the dead `Corpus.DIALECT` go.
  - `H2Verify`'s microsecond floor becomes a per-backend precision fact.
- **PCT:**
  - `PctCensusGate`'s environment-variable branches become per-lane ceilings that fail when a lane has none.
  - The channel B suites' `isReference()` branches become `PINS`.
- **Core tests:**
  - `AbstractDatabaseTest` (27 subclasses) takes a `backend()`. This fixes defect 6.
  - About 330 inline `jdbc:duckdb:` and `jdbc:h2:mem:` opens become `TestBackends.of(t).open()`. The change is
    mechanical.
  - The 26 resolver `sqlOf()` copies become one shared harness.
  - `StressSuites`, `StressTool` and `StressServiceSuites` take a `DatabaseType`.
  - `CorpusDifferentialTest` renders with the runtime's own dialect. This fixes defect 4 and may surface real
    differences.
  - Eight single-file leaks: `PlanMakerTest`, `PlanRunnerTest`, `StoreTypesTest`, `MissingValueInComputedColumnTest`,
    `QuotedColumnNameTest`, `PostgresArmTest`, `RowLoadTest` and `LadderRender`.
- **`Databases.implemented()`**: tests and tools iterate it instead of hand-listing databases. That covers
  `SqlWriterTest`, `SeedSpellingTest`, `StoreTypesTest`, `PlanMakerTest`, `PlanRunnerTest` and `RenderCensus`, which
  misses 4 dialects today.
- **`scripts/corpus/differential.py`** emits neutral rows, and the test renders the seed through the dialect.

Size: about 2,500 lines, most of them the mechanical replacement of inline opens across about 130 test files.

**Phase 6 is also exactly what the cloud corpus runs need:** a workspace per package on Snowflake, Databricks and
BigQuery is just three more `openWorkspace()` implementations. So this program unblocks the corpus-on-every-database
work (tasks #29 and #30), rather than only delaying it.

### Phase 7: the other modules

- **Warehouse** (decision D4). It is *mixed-engine*: DuckDB plus Postgres catalogs attached through DuckDB's postgres
  extension. It is also barred from depending on core (`//tools/deps:warehouse_closure_test`). Its Postgres knowledge
  (SQL, libpq DSN, URL, error text, password prompt, engine name) is gathered into one warehouse-owned class behind its
  existing `Attachment` registry. Defects 7 and 8 get tests. On the backlog (the user, 2026-10-13): revisit whether
  the warehouse can use core's dialects once the platform can carry it.
- **datacube and engine-client** (decision D5). Fix the paths that can reach a non-DuckDB engine:
  - the `snap.ts:205` preflight count, which runs on a warehouse catalog that may be Postgres, is planned like any
    query;
  - the query demo's seed.

  **All client SQL goes through the platform** (decision D5): no SQL text in TypeScript, a rule a guard can check.
  The tab's DuckDB-only operations (`remote.ts`'s `INSTALL httpfs` and `CREATE SECRET`, `upload.ts`'s and
  `model-data.ts`'s file reads, `duckdb.ts`'s and `snap.ts`'s local DDL) become named operations of the DuckDB dialect
  (`readFile`, `installExtension`, `createSecret`), which every other dialect refuses by name. The five TypeScript
  copies of identifier quoting go with them.
- **Dead code** (decision D6, measured 2026-10-13): they hold 255 of the 1,002 TEST-LEAK sites, and no target builds
  them.
  - **Deleted:** the orphan tools (`tools/{spikes,wrongrows,untangle,metamodel-census}`, `tools/reference/join.py`,
    `tools/native-axes.py`, `tools/upstream-drift.py`: 27 files, about 14,000 lines) and the orphan scripts (31
    `scripts/corpus/` modules and 9 others: 40 files, about 10,300 lines).
  - **Kept:** `experiments/` (kept by decision, 2026-10-02, `.bazelignore`) and the code under `docs/` (the harnesses
    that produced each folder's evidence, `docs/parked/` included). The leak ratchet skips them by path.

### Order and size

| order | phase | rough lines | behaviour change |
|---|---|---:|---|
| 1 | P0 guards | 300 | none |
| 2 | P1 dialect package | 1,500 | none, except D1 refusals where a DuckDB spelling was wrong |
| 3 | P2 IR | 400 | none |
| 4 | P3 SQL outside the dialect | 900 | none intended; `TestDataGenerator` re-measured |
| 5 | P4 reading back | 400 | fixes defect 2 |
| 6 | P5 connectors | 600 | none |
| 7 | P6 test backends | 2,500 | fixes defects 4–6 |
| 8 | P7 other modules | 400 + deletions | fixes defects 7–8 |

## 6. Decisions for review

Decided by the user, 2026-10-13. The recommendation each row first carried is kept where the decision differs.

| # | question | decision |
|---|---|---|
| **D1** | A dialect that silently inherits a DuckDB arm from the base renderer today: copy the arm into it, or make it refuse? | **Agreed.** Copy it where that dialect's PCT lanes prove the spelling works there. Refuse with a named `DialectCapability` everywhere else (H2's GUID, TIME_BUCKET and MAP_EMPTY, for example). Behaviour changes only for statements that already produce wrong text. |
| **D2** | Raw SQL comes from two places: legend-engine-style **H2 text** (corpus test data, `testDataSetupSqls`) and a user's **native** SQL (`executeInDb`). Today both are rewritten as H2 text. | **Decided: by the declared database.** Raw text is in the language of the database its connection declares; it is translated only when it runs on another (an H2 test connection on DuckDB), and passes untouched otherwise (`executeInDb` on a Snowflake connection, as legend-engine does). The DDL for CSV test data is rendered from the IR. Measure the corpus before and after. (First recommended: record the origin on `RawSql`.) |
| **D3** | The IR's *meanings* are DuckDB's in places: its typing rules, LIST_SLICE's end, H2's scan order, strftime codes. Is that a leak? | **Agreed.** Keep the meanings as the platform's written contract; every dialect already emulates them. Replace only the DuckDB *strings* inside the IR (`"to_days"`, strftime codes, json_type and typeof names) with typed values. |
| **D4** | Warehouse: share core's dialect, or keep its own per-engine classes? | **Agreed for now.** Keep the closure rule and gather its Postgres knowledge into one owner behind `Attachment`. On the backlog: revisit whether the warehouse can use core's dialects once the platform can carry it. |
| **D5** | TypeScript apps: must every SQL go through the planner? | **Decided: yes, all of it.** The tab's DuckDB-only operations become named operations of the DuckDB dialect, refused by the others; a guard bans SQL text in TypeScript. (First recommended: no for tab-only setup, which `snap.ts`'s count on a warehouse catalog had already disproved.) |
| **D6** | Delete the dead trees? | **Decided: only the orphan tools and scripts** (about 24,000 lines, listed in Phase 7). `experiments/` stays (its own decision of 2026-10-02) and so does the code under `docs/`, the harnesses behind each folder's evidence. |
| **D7** | Land phase by phase on the branch, each green, then pause for review after P1 (the most visible restructuring)? | **Agreed.** |
| **D8** | Which case does a store's bare name have on Postgres? The engine emits it unquoted, so Postgres folds it to lower case; the corpus's setup text assumes that. Main's `#SQL{...}#` Postgres fixture and DataCube's `infer.test.ts` assume the opposite: the name is kept as declared and quoted. Folding was tried and reverted on 2026-10-12 (neema2/legend-lite#30's CI). | **Decided: fold**, as legend-engine does (more correct for a drop-in replacement: most Postgres tables are created unquoted). Decide it once, as the dialect's own rule (P1). The two kinds of test then follow whichever rule is chosen. Folding matches the engine and the corpus; keeping case matches `#SQL{...}#`. If folding returns, the PR's independent review named two prerequisites: `CatalogModel.ident` must quote a mixed-case catalog name (or a Postgres table `"Orders"` is read as `orders`), and a star-expanded column's label must take the spelling its references take (`Stars.java:54`). |
| **P1b** | The base renderer's shape. | **Decided: an exhaustive switch per concrete dialect, families included**, a base with no spellings, a guard that enforces it, and a capability matrix generated from the code (Phase 1b). |


## 7. The misses (leaks the patterns could not see; each slice's reviewers found them by reading)

- **Base-renderer arms with DuckDB spellings** that no pattern flagged: `AnsiSqlRenderer.java:637, 642, 668, 674, 686,
  740, 760, 830, 832, 850, 874, 889, 892, 1095-1100, 1229-1239, 1247-1270`.
- **The IR vocabulary is DuckDB's:**
  - STRFTIME codes (`Trino.java:942`);
  - TYPEOF names (`MariaDb.java:2313`);
  - LIST_SLICE semantics (`Snowflake.java:777`);
  - the typing rules (`SqlTyping.java`, "DuckDB 1.5.0");
  - the scan order (`ScanOrder.java:18-64`, H2's).
- **DuckDB type-name compares in lowering**: `Scalars.java:2656`, `CollectionLanes.java:354`,
  `LiteralSpelling.java:232`, `Fold.java:1300`.
- **Values bypassing `normalize`**: `DynamicPivot.java:91-94` and `Executor.java:760-768` (H2's `byte[]` JSON).
- **`H2Settings` URL suffix** spliced into hand-built `jdbc:h2:mem:` URLs in pct, spec and core tests.
- **Connections:**
  - `ConnectionResolver.java:189-198`: the `Opening` switch, and H2's in-memory name minted in the server.
  - `ServiceTestRunner.java:154-160, 419` and `CsvSeed.java:233-243`: test data rides only in a `LocalH2` connection
    spec, whatever the database.
- **Text tests in disguise:**
  - `InProtocol.java:127-131`: `startsWith("TestDatabaseConnection")`.
  - `StoreCompiler.java:104-111`: string type kinds in `SqlDdl.ColumnType`, which BigQuery and MariaDB test with
    `contains("BINARY")`.
- **Corpus harness:**
  - `OTHER_LANE` means "neither DuckDB nor H2" and skips every census for Postgres.
  - The warehouse lane hides behind a second system property.
- **PCT:** `maxIntNullEmpty` falls back to DuckDB's values for any other lane.
- **Tests:**
  - `PlanRunnerTest.java:199-209, 321` copies H2's server versions and its type table.
  - `ConnectionLeaseTest.java:240` detects leaks through DuckDB's `.wal` file.
  - Two resolver tests append DuckDB's `ORDER BY ALL`.
- **Bazel and corpus scripts:**
  - The Bazel lane lists repeat the Java ones (`spec/BUILD.bazel:17`, `spec/corpus.bzl`, `core/BUILD.bazel:758`).
  - The driver-jar list lives in BUILD files.
  - `scripts/corpus/combos.py:104-109` leaves REAL out of the shared corpus because of one DuckDB connector defect.
- **Warehouse:**
  - libpq's "no password supplied" text is matched (`Catalogs.java:98, 105`).
  - A TimeZone regex is run over libpq options (`Postgres.java:142`).
- **Other modules:**
  - `datacube/test/infer.test.ts` pins Postgres catalog type names and the Postgres typed constant inside regex
    literals. It must move with those facts.
  - `WarehouseJdbcTest.java:291-306` copies the corpus's DuckDB workspace steps.

## Appendix A: every leak group (137), by class

`sites` is the number of census sites in the group; `where` lists up to four files with line ranges.


### LEAK-SQL (13 groups)

| sites | group | where | note |
|---:|---|---|---|
| 80 | wh Postgres attached-catalog SQL | Postgres.java:35-128 | Postgres SQL (pg_catalog listing, server_version_num, pg_cancel_backend) built outside core Postgres dialect |
| 49 | CatalogGrids information_schema catalog SQL | CatalogGrids.java:75-207 | fetchDb* catalog SQL composed in the Typer (phase G); probed on DuckDB+H2 only; pg_catalog exclusion |
| 12 | TDG live fetch SQL (raw on ambient session) | TestDataGenerator.java:319-1687 | hand-built select/join/limit, CREATE TEMPORARY TABLE AS, DROP, literals; run raw, no dialect |
| 9 | TDG compareCsv temp load + EXCEPT diff | TestDataGenerator.java:1091-1140 | CREATE TEMPORARY TABLE/INSERT VALUES/EXCEPT both ways composed outside dialect |
| 6 | TDG csvEnvelope SQL (DuckDB-flavoured) | TestDataGenerator.java:963-1033 | union/limit 0/sha256+repeat+// /chr/coalesce cast varchar composed outside dialect |
| 4 | lowering compares JSON_TYPE to DuckDB json_type names | CanonicalRenderSql.java:993-997 | MIR contract = DuckDB json_type vocabulary; each dialect emulates it |
| 4 | snap.preflight count SQL on remote engine | snap.ts:205 | SELECT count(*) FROM (<sql>) hand-written, run on warehouse (DuckDB or attached Postgres) |
| 3 | TDG engine-H2 plan-text SQL (select top) | TestDataGenerator.java:1541-1564 | referee: engine-H2 spelling (select top N, DATE literal) composed outside EngineStyleH2 |
| 2 | dropAndCreateSchemaInDb raw CREATE SCHEMA | StatementExecutor.java:2951 | "Create schema if not exists" run on any session; SqlDdl.CreateSchema exists |
| 2 | Sessions.raisedMessage @legend_raise SQL | Sessions.java:124-126 | MariaDB session-variable SQL outside the dialect that writes @legend_raise; SqlTextRatchet pin 1 |
| 2 | Ddl.insertText engine-text INSERT | Ddl.java:288 | INSERT text built by hand beside engineText.render(DDL); H2 ident rule |
| 2 | SqlSource.Join.Kind.sql keyword text in MIR | SqlSource.java:177-180 | MIR enum carries SQL join spellings (String sql field); violates invariant 3a; dialect should spell |
| 1 | InProtocol engine temp-table select text | InProtocol.java:233 | engine generateTempTableSelectSQLQuery text hand-built outside EngineStyle dialects |

### LEAK-ID (10 groups)

| sites | group | where | note |
|---:|---|---|---|
| 30 | WarehouseServer Postgres CLI/Config | WarehouseServer.java:66-960 | Config.postgres map, --postgres flag, Attachment.POSTGRES hard-wired outside Attachment |
| 26 | SqlTypeCensus dialect-simple-name branches | SqlTypeCensus.java:229-674; Executor.java:326 | passes dialect.getClass().getSimpleName() as the census key |
| 12 | Boundary.testDataSql hard-wires new DuckDb() | Boundary.java:185-214 | tab seed SQL picked by dialect class, not Databases.dialect(type) as sessionSetup does |
| 6 | EngineStyleH2 used as the engine-text renderer type | StatementExecutor.java:495-1061; PlanEnvelope.java:25 | concrete dialect class as a parameter type |
| 6 | Sessions.raisedMessage product check | Sessions.java:119-120 | product-name compare MariaDB/MySQL (not an exhaustive switch); belongs on MariaDb/MySql connector |
| 4 | plan text hardcodes TestDatabaseConnection(type=H2) | PlanText.java:35-446; TestDataGenerator.java:1430-1491 | default/fixed plan connection spelled H2 (engine referee), not read from the runtime |
| 2 | ContextReading default DatabaseType H2 | ContextReading.java:803-845 | unspelled connection type defaults to "H2" (referee); feeds Databases.named dialect choice |
| 2 | wh postgres:// URL-prefix dispatch | PostgresUrl.java:23 | URL prefix test picks Postgres; should be Attachment.fromUrl / connector.accepts(url) |
| 2 | Boundary.catalogColumnsSql static DuckDb.CATALOG_COLUMNS_SQL | Boundary.java:293 | catalog question read off DuckDb class + literal splice here; no SqlDialect method |
| 1 | default engine plan connection TestDatabaseConnection(type=H2) | StatementExecutor.java:772 | H2 literal duplicating EngineText.ENGINE_TEST_DATABASE |

### LEAK-TYPE (5 groups)

| sites | group | where | note |
|---:|---|---|---|
| 60 | SqlTypeCensus per-driver wire-type normalization | SqlTypeCensus.java:256-842 | per-driver type-name folding/carriers for the wire census (diagnostic, PCT-gated counts) |
| 20 | TDG duckType table | TestDataGenerator.java:1208-1232 | DuckDB type spellings for compareCsv temp tables; dialect DDL spelling should own it |
| 15 | Executor.pureOfSqlType driver type-name table | Executor.java:945-1081; GridProbe.java:78 | column type NAME fed to the cross-driver name table |
| 12 | CatalogGrids data_type -> java.sql.Types table | CatalogGrids.java:188-200 | information_schema data_type spellings (DuckDB names) mapped to JDBC codes, in the compiler |
| 3 | PctProbe name-based temporal type sniff | PctProbe.java:40-43 | DATE/TIMESTAMP* by type NAME; misses e.g. ClickHouse DateTime64/Date32 |

### LEAK-RAW (4 groups)

| sites | group | where | note |
|---:|---|---|---|
| 10 | raw-H2 adaptation (CsvSeed.adaptRaw + callers) | CsvSeed.java:252-254; StatementExecutor.java:2846-2927; PlanMaker.java:179; SetupRunner.java:31 | plan setup statements adapted via adaptRaw |
| 5 | sql/RawSql text splitter+query classifier (MIR pkg) | RawSql.java:16-61 | lexes raw SQL text (';' split, '' escapes, SELECT/WITH/SHOW/DESCRIBE heads) outside dialect; Typer calls it |
| 3 | Typer raw executeInDb split/sniff (RawSql) | Overloads.java:211-212 | unsure: ANSI ;-split + read-keyword sniff of raw SQL in the Typer; lexing differs per database |
| 2 | PlanText scalar item sliced from rendered SQL | PlanText.java:84-85 | startsWith("select ")/indexOf(" from ") on engine-style rendered text |

### LEAK-CONN (6 groups)

| sites | group | where | note |
|---:|---|---|---|
| 24 | StorelessRuntime in-process connection spec switch | StorelessRuntime.java:75-81 | DatabaseType switch -> connection grammar spec/auth; mirrors Sessions |
| 7 | TDG direct JDBC metadata reads | TestDataGenerator.java:494-1051 | unsure: generic JDBC on ambient Connection; text-type code set (VARCHAR/CHAR/LONGVARCHAR) is driver-dependent |
| 5 | wh PostgresUrl URL->DSN | PostgresUrl.java:17-85 | postgresql:// URL -> libpq DSN, default statement_timeout; per-db connection handling |
| 3 | wh Postgres libpq DSN + session zone | Postgres.java:152-163 | libpq key=value DSN parse/quote + TimeZone=UTC option outside a core Postgres connector |
| 1 | CreateClass DuckDB in-memory path | CreateClass.java:487 | branches on duckDB spec _type and writes path ":memory:" (DuckDB in-memory convention) |
| 1 | wh session engine name hard-coded | Sessions.java:46 | Session reports engine 'DuckDB' even for an attached Postgres catalog (driver product name) |

### LEAK-ERR (1 groups)

| sites | group | where | note |
|---:|---|---|---|
| 3 | wh DuckDB error-kind classification | Statements.java:530-531 | DuckDB message-prefix match (Parser/Binder/Catalog Error) in Statements, not duck/; attached DB errors unclass |

### DIALECT-SHARED (53 groups)

| sites | group | where | note |
|---:|---|---|---|
| 17 | RawSqlBoundary.Target identity enum | RawSqlBoundary.java:154-289; Postgres.java:142-143; SqlDialect.java:73-74 | Postgres names itself from an enum {DUCKDB, POSTGRES} in shared RawSqlBoundary |
| 16 | RawSqlBoundary H2->DuckDB/Postgres raw translation | RawSqlBoundary.java:30-275 | H2-origin text rewritten for DuckDB in a shared file; DuckDB/Postgres facts should be their dialects' |
| 15 | AnsiSqlRenderer DuckDB arms in base call()/expr() | AnsiSqlRenderer.java:575-888 | OrderedListAgg -> DuckDB list(x ORDER BY k); every non-DuckDB dialect overrides |
| 15 | TypeNames.CLICKHOUSE row | TypeNames.java:125-140 | per-database CAST type names in shared file; owner ClickHouse |
| 14 | TypeNames.SQLITE row | TypeNames.java:146-161 | per-database CAST type names in shared file; owner SQLite |
| 12 | TypeNames.SNOWFLAKE row | TypeNames.java:48-63 | per-database CAST type names in shared file; owner Snowflake |
| 12 | TypeNames.BIGQUERY row | TypeNames.java:69-84 | per-database CAST type names in shared file; owner BigQuery |
| 10 | TypeNames.DATABRICKS row | TypeNames.java:91-102 | per-database CAST type names in shared file; owner Databricks |
| 10 | TypeNames base() shared row | TypeNames.java:202-213 | shared baseline cast names; HUGEINT/TIMESTAMPTZ are DuckDB spellings inherited by ANSI/H2/Postgres/Trino rows |
| 8 | DdlSpelling.h2Type (H2 DDL types as base) | DdlSpelling.java:26-37 | H2's column-type spelling is the base renderer's ddlType default; owner H2 (and EngineStyleH2) |
| 8 | TypeNames.TRINO row | TypeNames.java:109-117 | per-database CAST type names in shared file; owner Trino |
| 7 | RawSqlAdapt/h2ToDuckDb raw translation (shared file) | RawSqlAdapt.java:6-26; DuckDb.java:273 | DuckDb's pass list runs RawSqlAdapt, whose H2->DuckDB text rewrite lives in shared RawSqlBoundary |
| 7 | TypeNames.MARIADB row | TypeNames.java:167-181 | per-database CAST type names in shared file; owner MariaDb(+MySql) |
| 5 | Lexicon.SQLITE row | Lexicon.java:50-65 | per-database quote char + reserved words in shared file; owner SQLite |
| 5 | TypeNames.H2 row | TypeNames.java:38-196 | per-database CAST type names in shared file; owner H2 |
| 5 | TypeNames.POSTGRES row | TypeNames.java:43-189 | per-database CAST type names in shared file; owner Postgres |
| 4 | AnsiSqlRenderer hardcoded type names | AnsiSqlRenderer.java:802-803 | DIVIDE spells 'DOUBLE' literally, bypassing TypeNames (DuckDB/H2 name; Postgres overrides) |
| 4 | Lexicon.DATABRICKS row | Lexicon.java:144-153 | per-database quote char + reserved words in shared file; owner Databricks |
| 4 | Spellings.POSTGRES row | Spellings.java:54-67 | per-database function-name row in shared file; owner Postgres |
| 4 | Spellings.DATABRICKS row | Spellings.java:77-88 | per-database function-name row in shared file; owner Databricks |
| 4 | Spellings.TRINO row | Spellings.java:105-115 | per-database function-name row in shared file; owner Trino |
| 4 | Spellings.CLICKHOUSE row | Spellings.java:126-134 | per-database function-name row in shared file; owner ClickHouse |
| 4 | Spellings.BIGQUERY row | Spellings.java:151-167 | per-database function-name row in shared file; owner BigQuery |
| 4 | Spellings.SNOWFLAKE row | Spellings.java:177-186 | per-database function-name row in shared file; owner Snowflake |
| 4 | Spellings.SQLITE row | Spellings.java:208-216 | per-database function-name row in shared file; owner SQLite |
| 4 | Spellings.MARIADB row | Spellings.java:227-234 | per-database function-name row in shared file; owner MariaDb(+MySql) |
| 3 | MySql borrows MARIADB rows | MySql.java:30 | MySQL's lexicon/types/spellings are MariaDB's rows in shared files (family; fine if rows move to MariaDb) |
| 3 | Spellings.H2 row | Spellings.java:34-36 | per-database function-name row in shared file; owner H2 |
| 3 | TypeNames.DUCKDB row | TypeNames.java:31-220 | per-database CAST type names in shared file; owner DuckDb |
| 2 | rawH2IsNative identity flag | H2.java:174; SqlDialect.java:67 | capability flag true only for H2 (+H2Modern) and named after it: an identity in disguise |
| 2 | Lexicon.DUCKDB row | Lexicon.java:25-34 | per-database quote char + reserved words in shared file; owner DuckDb |
| 2 | Lexicon.MARIADB row | Lexicon.java:73-89 | per-database quote char + reserved words in shared file; owner MariaDb(+MySql) |
| 2 | Lexicon.SNOWFLAKE row | Lexicon.java:130-137 | per-database quote char + reserved words in shared file; owner Snowflake |
| 2 | Lexicon.BIGQUERY row | Lexicon.java:159-166 | per-database quote char + reserved words in shared file; owner BigQuery |
| 2 | Lexicon.TRINO row | Lexicon.java:172-180 | per-database quote char + reserved words in shared file; owner Trino |
| 2 | Lexicon.CLICKHOUSE row | Lexicon.java:185-189 | per-database quote char + reserved words in shared file; owner ClickHouse |
| 2 | Lexicon.POSTGRES row | Lexicon.java:197-208 | per-database quote char + reserved words in shared file; owner Postgres |
| 1 | AnsiSqlRenderer base passes(): H2 carrier caps | AnsiSqlRenderer.java:100 | base default passes() = Caps.H2; only H2 inherits it -> H2.passes() |
| 1 | CarrierStrategies.Caps.DUCKDB row | CarrierStrategies.java:58 | per-database collection capabilities in shared pass; owner DuckDb (supply its Caps) |
| 1 | CarrierStrategies.Caps.H2 row | CarrierStrategies.java:61 | per-database collection capabilities in shared pass; owner H2 (supply its Caps) |
| 1 | CarrierStrategies.Caps.POSTGRES row | CarrierStrategies.java:66 | per-database collection capabilities in shared pass; owner Postgres (supply its Caps) |
| 1 | CarrierStrategies.Caps.DATABRICKS row | CarrierStrategies.java:68 | per-database collection capabilities in shared pass; owner Databricks (supply its Caps) |
| 1 | CarrierStrategies.Caps.SNOWFLAKE row | CarrierStrategies.java:71 | per-database collection capabilities in shared pass; owner Snowflake (supply its Caps) |
| 1 | CarrierStrategies.Caps.BIGQUERY row | CarrierStrategies.java:74 | per-database collection capabilities in shared pass; owner BigQuery (supply its Caps) |
| 1 | CarrierStrategies.Caps.SQLITE row | CarrierStrategies.java:77 | per-database collection capabilities in shared pass; owner SQLite (supply its Caps) |
| 1 | CarrierStrategies.Caps.MARIADB row | CarrierStrategies.java:80 | per-database collection capabilities in shared pass; owner MariaDb(+MySql) (supply its Caps) |
| 1 | CarrierStrategies.Caps.TRINO row | CarrierStrategies.java:83 | per-database collection capabilities in shared pass; owner Trino (supply its Caps) |
| 1 | CarrierStrategies.Caps.CLICKHOUSE row | CarrierStrategies.java:86 | per-database collection capabilities in shared pass; owner ClickHouse (supply its Caps) |
| 1 | CatalogModel.copySelectList DuckDB * REPLACE | CatalogModel.java:64 | shared file writes DuckDB's '* REPLACE (... AS "c")' star modifier; owner DuckDb |
| 1 | EngineStyleH2 borrows Spellings.DUCKDB | EngineStyleH2.java:301 | engine-H2 golden printer spells functions from DuckDB's row; should own an engine-H2 row |
| 1 | Lexicon.H2_ENGINE_TEXT row | Lexicon.java:110 | per-database quote char + reserved words in shared file; owner EngineStyleH2 |
| 1 | Lexicon.H2 row | Lexicon.java:120 | per-database quote char + reserved words in shared file; owner H2 |
| 1 | Spellings.DUCKDB row | Spellings.java:24 | per-database function-name row in shared file; owner DuckDb |

### TEST-LEAK (45 groups)

| sites | group | where | note |
|---:|---|---|---|
| 228 | inline jdbc:duckdb: test session | ComputedProjectIntegrationTest.java:84-406; DuckDBStructSyntaxTest.java:17-32; ResolveM2mTest.java:63-175; AssociationIntegrationTest.java:44 (+65 files) | DriverManager by URL instead of Sessions.openPrivate(DatabaseType.DuckDB)/a test connector |
| 109 | AbstractDatabaseTest backend hooks (getDatabaseType/getJdbcUrl) | AsOfJoinCheckerTest.java:42-52; ConcatenateFlattenCheckerTest.java:34-41; DuckDBIntegrationTest.java:36-46; DynaFunctionIntegrationTest.java:30-37 (+24 files) | connection template fixes specification: DuckDB {} for every backend (SQLite subclass too) |
| 58 | boundary probes branch on product name *(dead code)* | LiteralProbe.java:43-96; EnumIndex.java:23-81; ListProbe.java:67-71; ValueTypedCastProbe.java:56 | dead; if product is PostgreSQL/H2/DuckDB: per-DB fixture SQL, EXPLAIN, casts; or inline jdbc:h2 |
| 58 | InDbVerdict verdict SQL composed in harness *(dead code)* | InDbVerdict.java:54-157 | dead; parked: CREATE/DROP/EXCEPT ALL/VALUES/LIMIT SQL composed for the DuckDB session |
| 52 | resolver sqlOf() pins new DuckDb() to execute | ChainTypeDispatchTest.java:8-87; NavigationDepthTest.java:9-119; ResolveDeepEmptinessProbeTest.java:14-102; ResolveOuterDatedNavTest.java:13-101 (+9 files) | no SQL-text golden: DuckDb picked only to run the plan on the inline DuckDB session |
| 36 | InDbVerdict DuckDBAppender transfer *(dead code)* | InDbVerdict.java:193-282 | dead; parked: unwraps DuckDBConnection, bulk loads via DuckDBAppender |
| 34 | backend-probes inline DuckDB connect *(dead code)* | DuckDBUnnestSyntaxProbe.java:11-40; Catalog.java:31-32; Catalog2.java:28-29; Catalog3.java:27-28 (+3 files) | dead; jdbc:duckdb: / DuckDBDriver opened inline in a probe |
| 33 | RenderCensus hand-listed dialect classes | RenderCensus.java:7-59 | live; manual bazel run tool; names 9 dialect classes, misses MySql/Trino/ClickHouse; iterate Databases |
| 29 | QuotedColumnNameTest.run backend-string switch | QuotedColumnNameTest.java:57-164 | "duckdb".equals(engine) picks JDBC URL and connection block; backends keyed by string in @ValueSource |
| 28 | InDbVerdict JDBC->DuckDB type table *(dead code)* | InDbVerdict.java:174-184 | dead; parked: JDBC type -> DuckDB column type spelling for the transfer |
| 26 | MinimalCorpusTest H2_BACKEND?h2:duckdb roster selection | MinimalCorpusTest.java:163-1225 | binary H2_BACKEND ternary picks lane rosters/ceilings in shared test; use CorpusBackend.name() |
| 25 | DuckWorkspaces DuckDB workspace impl (no CorpusBackend iface) | DuckWorkspaces.java:6-296 | DuckDB ATTACH/USE/DETACH/SET + DuckDBConnection.duplicate, called directly by shared MinimalCorpus |
| 23 | AbstractDatabaseTest.setupDatabase shared raw-SQL seed | AbstractDatabaseTest.java:421-459 | raw DDL/DML text seeded on whichever backend the subclass picks (DuckDB, SQLite) |
| 23 | StressSuites h2/duckdb backend switch | StressSuites.java:37-149 | boolean h2 picks URL, DatabaseType, session policy and ledger suffix (L37) |
| 23 | backend-probes Probe per-backend switch *(dead code)* | Probe.java:30-78 | dead; switch(backend) opens duckdb/sqlite/h2/postgres/mariadb inline; sqlite fixture rewrite |
| 18 | MinimalCorpus BACKEND string switch | MinimalCorpus.java:478-502 | rcorpus.backend string compares, H2_BACKEND/OTHER_LANE flags, databaseType() switch (default DuckDB) |
| 18 | StressTest* hardcoded DuckDb dialect | StressTest.java:296; StressTest100K.java:219; StressTest10K.java:284; StressTestChaotic.java:333 (+2 files) | renders with new DuckDb() instead of Databases.dialect(executesOn(ctx,rt).type()) |
| 14 | StorelessRuntime DatabaseType.DuckDB pin (+inline URL) | MetamodelStoreSubstitutionTest.java:60-93; MetamodelStoreTest.java:55-109; FoldCheckerTest.java:68; MetamodelMappingStoreTest.java:90 (+1 files) | typed pick is fine; it is paired with a separately opened jdbc:duckdb: session |
| 13 | MinimalCorpus fixture-aside per-backend SQL | MinimalCorpus.java:704-746 | inline H2 CREATE SCHEMA/SET SCHEMA(_SEARCH_PATH) for asides + H2 text recorded for the mirror |
| 12 | verify-engine-differential H2 seed -> DuckDB | verify-engine-differential.mjs:118-123 | harness re-parses H2 INSERT text from trades-h2.pure into DuckDB DDL/INSERT inline |
| 12 | differential.py DuckDB seed.sql DDL/DML | differential.py:42-94 | live; writes DuckDB DDL/INSERT + type map for //core:corpus_differential_test; dialect DDL/DML could |
| 9 | ServerWorkspaces Postgres CREATE/DROP DATABASE | ServerWorkspaces.java:32-70 | 'server' workspaces hard-coded to Postgres: admin conn, CREATE DATABASE ws_N, DROP ... WITH (FORCE) |
| 9 | DbxTest inline Databricks JDBC *(dead code)* | DbxTest.java:15-38 | dead; Databricks driver class, URL params and connect inline |
| 8 | MinimalCorpus.openSession per-backend branches | MinimalCorpus.java:508-524 | inline H2 URL+aliases, postgres->ServerWorkspaces, else DuckWorkspaces: no backend interface |
| 8 | PlanMakerTest.fresh DatabaseType switch to JDBC URL | PlanMakerTest.java:407-408 | switch(DatabaseType) builds jdbc:duckdb:/jdbc:h2:mem:+H2Settings inline; belongs on a per-db connector |
| 8 | StoreTypesTest.on backend-name branch | StoreTypesTest.java:164-170 | type.equals("H2") swaps in LocalH2/DefaultH2 connection block; per-db test declaration by name |
| 8 | inline DuckDB session setup SET timezone | ComputedProjectIntegrationTest.java:86-408; ExecutionResultIntegrationTest.java:34; TypeInferenceIntegrationTest.java:41 | per-session zone set in DuckDB spelling by the test, not by a per-backend session opener |
| 7 | PctCensusGate env compare postgres/h2 | PctCensusGate.java:124-191 | shared census gate re-reads LEGENDLITE_PCT_BACKEND, branches postgres/h2; else DuckDB ceilings |
| 6 | ReplayOracle.tdgSqlReplay DuckDB duplicate | ReplayOracle.java:492-498 | unwraps session to DuckDBConnection.duplicate()+USE ws: DuckDB-only session work in referee |
| 6 | CorpusOne lane-name validation | CorpusOne.java:21-30 | CLI re-validates lane as duckdb\|h2 and maps h2->property inline; duplicate of backend() |
| 6 | corpus referee mirror wiring (H2_BACKEND) | MinimalCorpus.java:591; MinimalCorpusTest.java:1008 | shared observer opens the H2 referee mirror inline unless H2_BACKEND (topology by backend) |
| 6 | PostgresArmTest inline login-role provisioning | PostgresArmTest.java:32-37 | probes pg_roles and CREATE ROLE ... LOGIN inline; server provisioning belongs on EmbeddedPostgres |
| 6 | CorpusDifferentialTest DuckDB-pinned render+session | CorpusDifferentialTest.java:46-63 | new DuckDb() + jdbc:duckdb: instead of the runtime's declared type via Databases/Sessions |
| 6 | StressTool --backend duckdb\|h2 option | StressTool.java:25-41 | CLI backend string matched against "duckdb\|h2", mapped to the h2 boolean |
| 6 | fusion spikes inline duckdb.connect *(dead code)* | fusion_probes_2026_08_28.py:15-17; fusion_spike2_2026_08_28.py:7-9; fusion_spike_2026_08_28.py:11-13 | dead; history spike (U-22); in-process DuckDB opened inline |
| 5 | Corpus.DIALECT dead DuckDb constant | Corpus.java:30 | unused DuckDb dialect named 'the wire dialect for raw corpus SQL' in shared harness; delete |
| 5 | ServiceTestRunner opener+type pair (inline URL) | ServiceTestProvisionKeyTest.java:107-108 | opener lambda by URL and a separate DatabaseType.DuckDB; should be one per-backend fixture |
| 4 | StressServiceSuites h2 lane flag | StressServiceSuitesTest.java:37-41 | assertAtLeast(boolean h2,..) threads the backend as a boolean + "-h2" ledger name |
| 3 | ReplayOracle.verifyAuto product-name route | ReplayOracle.java:416 | referee routes by session getDatabaseProductName()=="H2" (lane identity in shared referee) |
| 3 | MissingValueInComputedColumnTest.model DatabaseType ternary | MissingValueInComputedColumnTest.java:56 | type == H2 ? H2_MODEL : DUCKDB_MODEL picks the per-db connection block (deliberate per-db literals) |
| 3 | type-audit Probe inline jdbc:duckdb *(dead code)* | Probe.java:63 | dead; history harness opens jdbc:duckdb: inline and seeds a DDL file |
| 2 | AppPostgres inline JDBC script runner | AppPostgres.java:37-66 | DriverManager + 'postgres' maintenance DB inline; EmbeddedPostgres could own runScript(db, sql) |
| 2 | PlanRunnerTest servers ternary on DatabaseType.H2 | PlanRunnerTest.java:293 | picks H2 server versions [2.1,2.2] by DatabaseType compare; that fact is Databases.servers(type) |
| 2 | RowLoadTest DuckDB DDL hand-ported to H2 | RowLoadTest.java:170 | DDL.replace("DOUBLE","DOUBLE PRECISION") adapts DuckDB DDL text to H2 by string; render via dialect DDL |
| 2 | LadderRender statement filter by SQL prefix | LadderRender.java:187 | drops SET/CREATE/INSERT by text prefix to hide DuckDB session+seed SQL; unsure: DuckDB-only tool |
