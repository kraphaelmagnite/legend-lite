# C1_spec_pct_testing: dialect-leak census of spec/, pct/ and testing/

There are 884 hits in 64 files. Every hit is classified in `C1_spec_pct_testing.tsv`. The id set is identical to the
input's, with one row per id. Each rule was written after reading the code at its construct.

Short answer to the brief:

- **PCT is mostly right.** It has ONE lane abstraction, `PctBackend.Lane`, an enum with a per-database `open()` and
  `openSystem()`. `CloudLane.Cloud` is the same shape for the three clouds. Channel A (`PctExecuteNative`) and channel B
  (`ChannelB`) reach the database only through `PctBackend`. The one leak in shared PCT code is `PctCensusGate`. It
  re-reads the environment variable and branches on postgres and h2, and every other lane silently gets DuckDB's
  ceilings.
- **The corpus harness has no backend abstraction at all.** `MinimalCorpus` holds a `BACKEND` string, two booleans
  (`H2_BACKEND`, `OTHER_LANE`), a `databaseType()` switch with a `default -> DuckDB` arm, and an `openSession()`
  if-chain:
  - H2 is opened inline.
  - Postgres goes to `ServerWorkspaces.postgres()`.
  - Everything else goes to `DuckWorkspaces.open()`.

  The fixture-aside protocol is split three ways: DuckWorkspaces' ATTACH, H2's `SET SCHEMA` inline in MinimalCorpus,
  and nothing for Postgres. The referee (`ReplayOracle`) branches on the session's product name and unwraps
  `DuckDBConnection`. `MinimalCorpusTest` picks its rosters with about 20 `H2_BACKEND ? h2 : duckdb` ternaries.
- **testing/ is clean.** It holds one launcher per server, but the five `Embedded*` classes share their shape
  (`shared()`, `jdbcUrl(db)`, `port()`) without an interface.
- **Correction to the brief:** `MinimalCorpusTest.java` contains no inline `CREATE DATABASE` or `DROP DATABASE`. Both
  are in `ServerWorkspaces.java:40` and `ServerWorkspaces.java:70`.

## 1. Count table

| Class | Hits | Groups |
|---|---:|---:|
| TEST-LEAK | 132 | 12 |
| SEAM | 247 | 16 |
| TEST-GOLDEN | 323 | 13 |
| LANG | 9 | 2 |
| DIAG | 101 | 11 |
| FP | 72 | 9 |
| LEAK-SQL / LEAK-ID / LEAK-CONN / LEAK-RAW / LEAK-TYPE / LEAK-ERR | 0 | 0 |
| DIALECT-INTERNAL / DIALECT-SHARED / SINGLE-BACKEND-APP | 0 | 0 |
| **Total** | **884** | **75** |

No product-code leak classes apply, because the slice is all harness. The TEST-GOLDEN count is mostly the per-lane PCT
expected-failure ledgers: 280 hits, each a `one("<test>", "<that database's failure text>")` row.

## 2. TEST-LEAK groups

The proposed interfaces are collected in §2b. Each group below names the method it moves to.

### TL-1 `MinimalCorpus BACKEND string switch`: 18 hits

- **Where:** `spec/src/test/java/com/legend/rcorpus/MinimalCorpus.java:477-504`.
- **What it does:**
  - `H2_BACKEND = "h2".equalsIgnoreCase(getProperty("rcorpus.backend"))`.
  - `BACKEND = backend()`, which validates against `Set.of("duckdb","h2","postgres")`.
  - `OTHER_LANE = !duckdb && !h2`.
  - `databaseType()`: a string switch with `default -> DatabaseType.DuckDB`.
- **Why it is a leak:** shared corpus code holds the lane registry as string compares, in three overlapping forms. A
  12th lane means editing the set, the switch, the flags and every flag user. The `default -> DuckDB` arm is the
  defaulting shape that AGENTS invariant 4 forbids.
- **Where it belongs:** `CorpusBackend.of(String lane)`, the registry, together with `CorpusBackend.name()`,
  `databaseType()` and `referenceLane()`.
  - `databaseType()`: DuckDB → `DuckDB`, H2 → `H2`, Postgres → `Postgres`, warehouse → `DuckDB`.
  - `referenceLane()`: true for duckdb and h2, false for postgres.
- **Size:** about 30 lines removed, plus about 20 for the registry.
- **Coupling:**
  - `MinimalCorpus.java:304` passes `databaseType()` into the connection overlay.
  - The flags are read in MinimalCorpus at 507, 523, 590, 694 and 730, and in MinimalCorpusTest about 20 times.
  - `spec/BUILD.bazel:220` sets `-Drcorpus.backend=h2`.
  - `CorpusOn.java:23` and `CorpusOne.java:30` set the property.
  - `HarnessDisciplineTest` pins `MinimalCorpus.java` at 2 sort sites. Those sites are unaffected, but the file key
    must stay.

### TL-2 `MinimalCorpus.openSession per-backend branches`: 8 hits

- **Where:** `MinimalCorpus.java:506-527`.
- **What it does:**
  - `if (H2_BACKEND)`: an inline `DriverManager.getConnection("jdbc:h2:mem:c2s"+N+H2Settings.SETTINGS,"sa","")`, then
    the H2 extension `CREATE ALIAS` statements.
  - `if (BACKEND.equals("postgres"))`: `ServerWorkspaces.postgres()`.
  - Otherwise: `DuckWorkspaces.open()`.
- **Why it is a leak:** each backend's connection and workspace opening is chosen by an if-chain in shared code, and
  H2's is written inline.
- **Where it belongs:** `CorpusBackend.openWorkspace()`. The runner keeps `MinimalCorpus::openSession` as
  `backend::openWorkspace`.
- **Size:** about 20 lines moved. The H2 implementation is about 15 lines, as a new `H2Workspaces`, or inline in an
  `H2CorpusBackend`.
- **Coupling:**
  - `PureTestRunner` (`core/src/main/java/com/legend/test/PureTestRunner.java`) takes the opener as a lambda. It is
    backend-neutral and needs no change.
  - The H2 alias list comes from `harness/H2ExtensionFunctions.aliases()`.

### TL-3 `DuckWorkspaces DuckDB workspace impl (no CorpusBackend iface)`: 25 hits

- **Where:** `spec/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:6, 91-135, 198-248, 292-311`.
- **What it does:**
  - Opens one DuckDB root: `jdbc:duckdb:`, or the warehouse URL.
  - `SET threads=1`.
  - Per package: `ATTACH ':memory:' AS __ws_N`, then `DuckDBConnection.duplicate()`, `USE ws`, `SET TimeZone='UTC'`,
    and a close proxy that runs `DETACH`.
  - Fixture asides: `ATTACH ... AS ws_aside_k`, plus `SET search_path`.
- **Why it is a leak:** the content is correct DuckDB work, but it is a free-standing class with static entry points.
  Shared MinimalCorpus calls it by name (`DuckWorkspaces.open`, `isolateBegin`, `asideConnection`, `isolateEnd`,
  `asidesOf`), and `isolateBegin` returns null to mean "not a DuckDB connection". That null is an identity probe (see
  the misses).
- **Where it belongs:** it becomes `DuckDbCorpusBackend`, with these methods:
  - `openWorkspace()` = `open()`.
  - `isolateFixture(ws)` = `isolateBegin` + `asideConnection`.
  - `FixtureAside.end()` = `isolateEnd`.
  - `readerOn(ws)` = `another()` + `USE`.

  `SET TimeZone='UTC'` (lines 123 and 226) repeats the DuckDb dialect's session setup, as its comment admits ("the
  DECISION lives in DuckDb.initSession"). It should call the dialect's `sessionSetup()`, not spell it.
- **Size:** about 30 lines of adaptation. The SQL stays the same.
- **Coupling:**
  - `HarnessDisciplineTest` pins `"DuckWorkspaces.java", 1` (the `TreeSet` LIVE registry). A rename moves that key and
    needs a dated justification.
  - `MinimalCorpusTest.java:724-726` reads `DuckWorkspaces.DETACHES`, `DETACH_NANOS` and `DETACH_MAX_NANOS`.
  - The `org.duckdb` import is allowed because ArchitectureTest's driver ban covers `src/main` only.

### TL-4 `ServerWorkspaces Postgres CREATE/DROP DATABASE`: 9 hits

- **Where:** `spec/src/test/java/com/legend/rcorpus/ServerWorkspaces.java:32-75`.
- **What it does:** a class named for "a SERVER database" has one method, `postgres()`.
  - An admin connection to `EmbeddedPostgres.shared().jdbcUrl("postgres")`.
  - `CREATE DATABASE ws_N`.
  - A connection to `jdbcUrl(ws_N)`, behind a close proxy that runs `DROP DATABASE IF EXISTS ws_N WITH (FORCE)`.
    `WITH (FORCE)` is Postgres 13+ syntax.
- **Why it is a leak:** the generic name hides one database's workspace SQL and URL. This is the brief's own example,
  `CREATE DATABASE ws_N` inline in a corpus class. A second server lane, such as MariaDB, would need a parallel method
  and another `BACKEND.equals` arm in MinimalCorpus.
- **Where it belongs:** `PostgresCorpusBackend.openWorkspace()`. For server lanes generally, a
  `ServerCorpusBackend(EmbeddedServer server, WorkspaceDdl ddl)` could take the per-database SQL as a small strategy:
  - Postgres: `CREATE DATABASE` / `DROP DATABASE … WITH (FORCE)`.
  - MariaDB and MySQL: `CREATE DATABASE` / `DROP DATABASE`, or `createDatabaseIfNotExist` in the URL.
- **Size:** about 15 lines.
- **Coupling:**
  - Called only from `MinimalCorpus.java:524`.
  - The lane is `//spec:corpus_on_postgres` (`spec/BUILD.bazel:255`), launched through `CorpusOn`.
  - `JdbcSurfaceCensusTest`'s FILE_FLOOR (778) counts files under `spec/src`. Merging this file away lowers the count
    and needs a justification line.

### TL-5 `MinimalCorpus fixture-aside per-backend SQL`: 14 hits

- **Where:** `MinimalCorpus.java:689-753`, inside `CorpusObserver.isolateFixture` and `fixtureIsolated`.
- **What it does:**
  - **DuckDB:** it first calls `DuckWorkspaces.isolateBegin(conn)`.
  - **H2, when that call returns null and `H2_BACKEND` is set:** it runs H2 text inline. `isolateFixture` runs
    `CREATE SCHEMA IF NOT EXISTS ASIDE_n`, `SET SCHEMA ASIDE_n` and `SET SCHEMA_SEARCH_PATH ASIDE_n`.
    `fixtureIsolated` runs `SET SCHEMA PUBLIC` and `SET SCHEMA_SEARCH_PATH PUBLIC, …`.
  - **DuckDB lane, for the H2 referee mirror:** it records the same H2 text into the raw-SQL recorder, at lines
    719-720 and 745-747.
- **Why it is a leak:** shared observer code branches on the backend and composes two databases' SQL inline. One is
  the session's (H2 lane). The other is the referee mirror's (H2 text replayed beside DuckDB). Postgres gets no
  isolation, with no explicit decision.
- **Where it belongs:**
  - The session side goes to `CorpusBackend.isolateFixture(Connection ws)`, which returns
    `Optional<FixtureAside{connection(), end()}>`.
    - DuckDB: ATTACH aside.
    - H2: a schema aside on the session.
    - Postgres: an explicit `Optional.empty()` with its reason, or `CREATE SCHEMA` plus `SET search_path`.
  - The mirror side goes to `ReplayOracle.mirrorIsolate(String aside)` and `ReplayOracle.mirrorRestore(List<String>
    asides)`. The mirror is always H2, so the referee owns that text.
- **Size:** about 35 lines moved.
- **Coupling:**
  - `PureTestRunner.runFixture` (`core/src/main/java/com/legend/test/PureTestRunner.java:340-360`) treats a null aside
    as "no primitive (an H2 session)".
  - The recorder is `sql/dialect/RawSqlBoundary.Recorder`, used here only as a ledger.

### TL-6 `corpus referee mirror wiring (H2_BACKEND)`: 2 hits

- **Where:**
  - `MinimalCorpus.java:590-595`: `if (!H2_BACKEND && H2Verify.ready())`, then an inline
    `DriverManager.getConnection("jdbc:h2:mem:c2Mirror"+N+SETTINGS,"sa","")` and `ReplayOracle.mirrorBegin`.
  - `MinimalCorpusTest.java:1008`: the log label `oracle=same-session` / `oracle=h2-mirror`.
- **Why it is a leak:** the referee topology is decided by backend identity in shared code (H2 lane: the golden runs
  on the session; anything else: an H2 mirror), and the mirror's H2 connection is opened inline. The same fact is
  decided a third time, by product name, in TL-8.
- **Where it belongs:** `CorpusBackend.referee()`, returning `MIRROR` or `SAME_SESSION`.
  - DuckDB, warehouse and Postgres → `MIRROR`.
  - H2 → `SAME_SESSION`.

  Then `ReplayOracle.openMirror(int id)` owns the H2 URL. That is one H2 in-memory opener for the referee.
- **Size:** about 10 lines.
- **Coupling:** `H2Verify.ready()`, and `ReplayOracle.mirrorBegin` and `mirrorEnd`.

### TL-7 `MinimalCorpusTest H2_BACKEND?h2:duckdb roster selection`: 26 hits

- **Where:** `spec/src/test/java/com/legend/rcorpus/MinimalCorpusTest.java` at lines 163, 171, 392, 394, 396, 431,
  433, 441-443, 447, 456, 475, 488, 552, 737-738, 748-749, 924, 1057 and 1225.
- **What it does:** every pin is chosen by a binary ternary, either `MinimalCorpus.H2_BACKEND ? H2_X : DUCKDB_X` or
  `String lane = MinimalCorpus.H2_BACKEND ? "h2" : "duckdb"`.
- **Why it is a leak:** shared test code branches on the backend, and any lane that is not H2 is labelled "duckdb".
  Today `OTHER_LANE` returns early at line 371, so postgres never reaches these lines. A third reference lane, or the
  warehouse lane, would. `holdToLaneRoster` (line 1193) already shows the right shape:
  `"/rcorpus/" + BACKEND + "-fail-roster.txt"`.
- **Where it belongs:**
  - `CorpusBackend.rosterPrefix()`: duckdb → `"duckdb"`, h2 → `"h2"`, warehouse → `"duckdb"` (as `corpus.bzl`'s
    `rosters`), postgres → `"postgres"`.
  - Resource names become `"/rcorpus/" + prefix + "-<kind>.txt"`.
  - The Java pin values (`*_STRENGTH`, `*_TEXT_DECIDED`, `*_LENIENCY`) become a `Map<String lane, Pins>`, like
    `ChannelBLane.PINS`.
- **Size:** about 40 lines changed, and about 30 constant lines deleted.
- **Coupling:**
  - `spec/BUILD.bazel:17` `_MEASURED_ROSTERS` enumerates `("duckdb","h2")` and mirrors `MEASURED` (lines 52-57).
  - `spec/corpus.bzl`'s `rosters` attribute.
  - The `//spec:update_rcorpus_<lane>` diff tests.
  - `HarnessDisciplineTest` pins `MinimalCorpusTest.java` at 2 sites.

### TL-8 `ReplayOracle.verifyAuto product-name route`: 3 hits

- **Where:** `spec/src/test/java/com/legend/harness/ReplayOracle.java:416`.
- **What it does:** `"H2".equals(session.getMetaData().getDatabaseProductName()) ? verifyOnSession(...) : verify(...)`.
- **Why it is a leak:** a product-name compare on the lane's session inside the shared referee. It is TL-6's decision
  made a third time, this time by driver string.
- **Where it belongs:** `CorpusBackend.referee() == SAME_SESSION`, handed to the referee when it is constructed
  (`new ReplayOracle(recorder, backend.referee())`).
- **Size:** about 5 lines.
- **Coupling:** called from `ReplayOracle.verifyArmed`, the SPI `com.legend.exec.SqlReplayOracle.verify(...)` that
  core's verdict arms call. `ArchitectureTest:748` pins a `SqlReplayOracle` entry, but the SPI shape does not change.

### TL-9 `ReplayOracle.tdgSqlReplay DuckDB duplicate`: 6 hits

- **Where:** `ReplayOracle.java:478-507`. The parameter is literally named `Connection duck`.
- **What it does:** `duck.unwrap(org.duckdb.DuckDBConnection.class).duplicate()`, then `USE <catalog>`, to read "our"
  side without disturbing a streaming result.
- **Why it is a leak:** this is DuckDB-only session work in shared referee code. On the H2, Postgres or warehouse
  lanes, `unwrap` throws, and every TDG fetch verdict silently becomes the decline "our-side replay: …".
- **Where it belongs:** `CorpusBackend.readerOn(Connection ws)`.
  - DuckDB: `duplicate()` plus `USE ws`, as today.
  - Warehouse: a new session on the warehouse URL plus `USE`.
  - H2: the session itself, or a second connection to the named in-memory database.
  - Postgres: a new connection to `jdbcUrl(ws_N)`.
- **Size:** about 15 lines.
- **Coupling:** reached through the SPI `verifyFetchTexts`.

### TL-10 `PctCensusGate env compare postgres/h2`: 7 hits

- **Where:** `pct/src/test/java/org/finos/legend/lite/pct/PctCensusGate.java:124-125, 144-152, 190-199`.
- **What it does:**
  - Re-reads `System.getenv("LEGENDLITE_PCT_BACKEND")`, bypassing `PctBackend`.
  - `maxWireDiverge`: Postgres → Essential 13 and Grammar 5; everything else → 0.
  - `maxIntNullEmpty`: Postgres or H2 → 0; everything else → DuckDB's Essential/Standard 20 and Unclassified 4.
- **Why it is a leak:** a second, partial lane switch in shared code. SQLite, MariaDB, MySQL, Trino, ClickHouse and
  the clouds silently inherit DuckDB's int-null-empty ceilings. That is a defaulting branch, not a measured pin.
- **Where it belongs:** `PctBackend.Lane.censusCeilings(String suite)`, or a `Map<String lane, Map<String suite,
  Ceilings>>` beside `ChannelBLane.PINS` keyed by `PctBackend.name()`. A lane with no row must fail, as ChannelBLane
  does. Alternatively, each lane's ledger class passes its ceilings to `PctCensusGate.wrap(suite, ceilings, test)`.
- **Size:** about 20 lines.
- **Coupling:**
  - `PctCensusGate.wrap` is called by every `Test_LegendLite_<Lane>_<Suite>Functions_PCT`.
  - `pct/BUILD.bazel` sets the environment variable per lane.

### TL-11 `CorpusOne lane-name validation`: 6 hits

- **Where:** `spec/src/test/java/com/legend/rcorpus/CorpusOne.java:21-31`.
- **What it does:** `args[0].matches("duckdb|h2")`, and `if (args[0].equals("h2")) setProperty("rcorpus.backend","h2")`.
- **Why it is a leak:** it is a third copy of the lane list, and it is narrower than MinimalCorpus's: it refuses
  postgres. `CorpusOn.java:23` passes the lane through unchecked.
- **Where it belongs:** `CorpusBackend.of(args[0])` validates, then `setProperty("rcorpus.backend", args[0])`.
- **Size:** about 5 lines.
- **Coupling:** `//spec:corpus_one` (`spec/BUILD.bazel:226-246`).

### TL-12 `Corpus.DIALECT dead DuckDb constant`: 5 hits

- **Where:** `spec/src/test/java/com/legend/rcorpus/Corpus.java:29-30`.
- **What it does:** `static final DuckDb DIALECT = new DuckDb()`, documented as "the wire dialect for raw corpus SQL —
  ONE implementation (core DuckDb)".
- **Why it is a leak:** shared corpus code names a dialect class and claims every lane's wire dialect is DuckDB's.
  Nothing reads it: a grep over the repo finds no use.
- **Where it belongs:** nowhere. Delete it. The dialect follows the connection
  (`Compiler.dialectOf`/`Databases`).
- **Size:** 2 lines.
- **Coupling:** none.

## 2b. Proposed per-backend interfaces

### `CorpusBackend` (spec, package `com.legend.rcorpus`)

This interface replaces TL-1 through TL-9 and TL-11. It is one object per corpus lane, chosen once by
`CorpusBackend.of(System.getProperty("rcorpus.backend", "duckdb"))`.

| Method | DuckDB (`corpus_duckdb`) | Warehouse (`corpus_warehouse`) | H2 (`corpus_h2`) | Postgres (`corpus_on_postgres`) |
|---|---|---|---|---|
| `name()` | `duckdb` | `warehouse` | `h2` | `postgres` |
| `rosterPrefix()` | `duckdb` | `duckdb` (corpus.bzl) | `h2` | `postgres` |
| `databaseType()` | DuckDB | DuckDB | H2 | Postgres |
| `referenceLane()` | true | false (`golden = False`) | true | false |
| `openWorkspace()` | in-process root; `ATTACH ':memory:' AS __ws_N`; `duplicate()`; `USE`; close → `DETACH` | the same SQL, each connection a new warehouse session | `jdbc:h2:mem:c2sN` + `H2Settings` + `H2ExtensionFunctions.aliases()` | admin conn; `CREATE DATABASE ws_N`; close → `DROP DATABASE … WITH (FORCE)` |
| `isolateFixture(ws)` → `Optional<FixtureAside>` | ATTACH aside on root; `USE aside`; `end()` = `SET search_path ws.main, asides…` | same | `CREATE SCHEMA ASIDE_n` / `SET SCHEMA` / `SET SCHEMA_SEARCH_PATH`; `end()` = `SET SCHEMA PUBLIC` + path | explicit `empty()` with a reason (today's silent fall-through), or a schema + `search_path` |
| `readerOn(ws)` | `unwrap(DuckDBConnection).duplicate()` + `USE` | new session + `USE` | the session itself | new connection to `ws_N` |
| `referee()` | `MIRROR` | `MIRROR` | `SAME_SESSION` | `MIRROR` |
| `sessionWarmup()` (optional) | the DuckDb dialect's `sessionSetup()` (replaces the literal `SET TimeZone='UTC'`) | same | none (H2Settings in the URL) | none |

The referee keeps its own H2 by design, and it gains three methods:

- `ReplayOracle.openMirror(id)`: the one place that spells `jdbc:h2:mem:` for the referee.
- `mirrorIsolate(aside)` and `mirrorRestore(asides)`: the H2 aside text now in TL-5.

### `PctBackend.Lane` (pct): already the per-lane object; three additions

- `String runtimeDeclaration(String model)` replaces the `withStorelessRuntime` per-concern switch
  (`PctBackend.java:218-238`):
  - DuckDB, H2 and SQLite: `StorelessRuntime.with`.
  - Postgres, MariaDB, MySQL and ClickHouse: `onServer(server.host(), server.port(), server.laneDatabase())`.
  - Trino: `declaring(... Trino spec ...)`.
  - Clouds: `CloudLane.withStorelessRuntime`.
- `Ceilings censusCeilings(String suite)` replaces TL-10. The measured values exist for duckdb, h2 and postgres. Every
  other lane must be measured, not defaulted.
- `boolean reference()` replaces `isDuckDb()`. DuckDB returns true. It lets `ChannelBLane.isReference` stop naming
  DuckDB.

### `EmbeddedServer` (testing, package `com.legend.testing`)

This is a shared interface for the five launchers. It is not a leak fix (the launchers are SEAM-like), but it lets
`Lane` and `ServerCorpusBackend` hold one server object instead of naming five classes.

| Method | Postgres | MariaDB | MySQL | Trino | ClickHouse |
|---|---|---|---|---|---|
| `jdbcUrl(db)` | `jdbc:postgresql://…?user=postgres` | `jdbc:mariadb://…&createDatabaseIfNotExist=true` | `jdbc:mariadb://…` (Connector/J for MySQL) | `jdbc:trino://…/memory/<schema>?…&timezone=UTC` | `jdbc:clickhouse://…?user=default` |
| `host()` | `127.0.0.1` | `127.0.0.1` | `127.0.0.1` | `127.0.0.1` | `127.0.0.1` |
| `port()` | as today | as today | as today | as today | as today |
| `laneDatabase()` | `postgres` | `pct` | `pct` | `default` (schema) | `default` |

## 3. SEAM, DIALECT-SHARED and SINGLE-BACKEND-APP groups

There are no DIALECT-SHARED or SINGLE-BACKEND-APP groups in this slice. The SEAM groups, one line each:

- `PctBackend.Lane enum (per-lane object)`, 73 hits, `PctBackend.java:52-178`: **a per-database object**. Each enum
  constant opens its own session and system connection. It is the PCT lane seam.
- `PctBackend.lane() env selection`, 32 hits, `PctBackend.java:180-200`: the one designated selection point,
  `LEGENDLITE_PCT_BACKEND` → `Lane`.
- `PctBackend.withStorelessRuntime per-concern switch`, 15 hits, `PctBackend.java:218-238`: **a per-concern switch**
  inside the seam. Fold it into `Lane.runtimeDeclaration(model)`.
- `PctBackend.isDuckDb reference-lane test`, 1 hit, `PctBackend.java:208-210`: the seam exposes the reference lane's
  identity. Consider replacing it with `Lane.reference()`.
- `PctBackend seam consumers`, 2 hits, `ChannelB.java:283` and `PctExecuteNative.java:166`: the correct shape, calls
  made through the seam.
- `CloudLane.Cloud enum (per-cloud object)`, 16 hits, `CloudLane.java:44-150`: **a per-database object** for
  Databricks, Snowflake and BigQuery. Its URL and properties come from the account's properties file.
- `ChannelBLane.PINS per-lane pins`, 10 hits, `ChannelBLane.java:43-121`: pin values keyed by lane name. It is the
  model the corpus pins should copy.
- `MinimalCorpusTest per-lane roster file names`, 31 hits, `MinimalCorpusTest.java:52-103`: pin-list names for the
  duckdb and h2 lanes. The data is legitimate; the selection is TL-7.
- `MinimalCorpusTest per-lane pin values`, 3 hits, `MinimalCorpusTest.java:636, 824, 856`: the DuckDB lane's ceilings.
  The data is legitimate.
- `H2 referee internals (H2 by design)`, 9 hits:
  - `ReplayOracle.java:72-88` and `293-296`, and `H2Verify.java:78`.
  - The referee is H2 by design. Its fresh in-memory H2 URL duplicates the H2 opens in PctBackend and MinimalCorpus.
- `DuckWorkspaces warehouse launcher`, 2 hits, `DuckWorkspaces.java:140-185`: starts the warehouse server binary and
  builds its URL. This is launcher work. The lane selection is a miss.
- `EmbeddedPostgres launcher`, 15 hits; `EmbeddedMariaDb launcher`, 11; `EmbeddedMySql launcher`, 12;
  `EmbeddedTrino launcher`, 7; `EmbeddedClickHouse launcher`, 8, all under `testing/src/main/java/com/legend/testing/`:
  one launcher per server. Each holds its own process, configuration, bootstrap SQL (`CREATE USER` / `GRANT` for
  MariaDB and MySQL) and JDBC URL. Together they are SEAM-like, with no shared interface (§2b).

Other benign groups, for completeness:

- **TEST-GOLDEN:**
  - The `PCT expected-failure ledger: <Lane>` groups, 280 hits in total, one group per lane.
  - `referee H2 fixture SQL`, 41 hits: H2 temp-table, alias and allocation DDL replayed on the referee only.
  - `referee reads engine golden SQL text`, 2 hits.
- **LANG:** Pure connection-specification text, in CloudLane and in PctBackend's Trino arm.
- **DIAG:** launcher and referee messages.
- **FP:** "oracle" meaning the referee, the H2 probe hypothesis, upstream engine paths, generic JDBC metadata, and the
  `RawSqlBoundary.Recorder` type used as a ledger.

## 4. Misses: database-specific behaviour the scanner did not flag

1. **`MinimalCorpus.java:590, 694, 730`.** The `H2_BACKEND` branch lines themselves:
   - line 590: `if (!H2_BACKEND && H2Verify.ready())`;
   - line 694: `if (!H2_BACKEND) return null;`;
   - line 730: `if (H2_BACKEND && !h2Asides.isEmpty())`.

   Only the SQL lines under them were flagged. They belong to TL-5 and TL-6.
2. **`MinimalCorpus.java:486` and `MinimalCorpusTest.java:162, 371`.** `OTHER_LANE` means "not DuckDB and not H2". It
   is reference-lane identity in disguise, and it short-circuits every census for postgres. It should be
   `CorpusBackend.referenceLane()`.
3. **`DuckWorkspaces.java:73-79, 93-96, 131-134`.** The warehouse lane (`corpus_warehouse`, `spec/BUILD.bazel:189-210`)
   is a fourth backend hidden inside the DuckDB workspace class. It is selected by the system property
   `rcorpus.warehouse.server`, `another()` branches on `warehouseUrl != null`, and `rcorpus.backend` still reads
   "duckdb". It should be its own `CorpusBackend` (`WarehouseCorpusBackend`), sharing DuckDB's workspace SQL.
4. **`DuckWorkspaces.java:198-202` together with `MinimalCorpus.java:692-696`.** `isolateBegin` returns null for "not
   a DuckDB workspace". Shared code uses that null as an identity probe: null plus `H2_BACKEND` means a schema aside,
   and null alone means no isolation. On the **Postgres** lane, on-demand fixtures therefore run un-isolated in the
   session with no decision recorded. `PureTestRunner.java:337-357`'s comment even attributes the no-primitive path to
   "an H2 session", which is no longer true. This is a capability default (invariant 4).
5. **`H2Verify.java:1373-1381`, the `norm()` micro-floor.** DuckDB's microsecond TIMESTAMP storage ("DuckDB
   TIMESTAMP is microsecond BY STORAGE while H2 holds nanos") is baked into the shared comparison policy and applied
   on every lane, including the H2 lane, where both sides hold nanoseconds. It is a lane capability in disguise:
   `CorpusBackend.timestampPrecision()`. DuckDB and Postgres are micro; H2 is nano.
6. **The H2 in-memory open is spelled in four places:**
   - `PctBackend.java:62`;
   - `MinimalCorpus.java:508` (the lane session);
   - `MinimalCorpus.java:591` (the mirror);
   - `ReplayOracle.java:293-295` (the fresh referee).

   Each writes `"jdbc:h2:mem:" + name + H2Settings.SETTINGS, "sa", ""`. Each line was flagged on its own, but the
   duplication was not. One `H2Sessions.inMemory(name)` helper in testing, or the referee's `openMirror`, should own
   it.
7. **`PctBackend.java:115-125` with `221-233`, and `ServerWorkspaces.java:35, 42`.** The server lanes build URLs
   through `Embedded*.jdbcUrl(db)`, which is a second per-database URL builder beside the product's
   `exec/Sessions.java:220-237`. Sessions already maps a `StaticDatasource` to `jdbc:postgresql://` or
   `jdbc:mariadb://` for Postgres, MariaDB and MySQL. The lanes also re-declare the same host, port and database in
   the storeless runtime. Server lanes could open through `Sessions` from the runtime they declare, so the test
   exercises the product's connector rather than a parallel one. Trino and ClickHouse are not implemented in
   Sessions, so they keep the launcher URL.
8. **The five channel B suite tests.** `ChannelBEssentialTest.java:70`, `ChannelBGrammarTest.java:64`,
   `ChannelBStandardTest.java:70`, `ChannelBRelationTest.java:71` and `ChannelBUnclassifiedTest.java:66` each have
   `if (!ChannelBLane.isReference())`, which is `PctBackend.isDuckDb()`. DuckDB's channel B pins live in each suite
   test, and every other lane's live in `ChannelBLane.PINS`. Adding a `"duckdb"` row to PINS would remove the split.
   This is low severity: a 12th lane needs no edit here.
9. **`PctCensusGate.java:190-199`.** The `else` arm of `maxIntNullEmpty` hands DuckDB's measured 20/20/4 to every
   lane that is neither postgres nor h2. This is a silent default, flagged only via the `POSTGRES || H2` line. It is
   detailed in TL-10.
10. **`MinimalCorpus.java:502`.** `databaseType()`'s `default -> DuckDB` arm. It was flagged as `database-type`, but
    the defaulting arm deserves calling out: an unknown lane is impossible only because `backend()` validates first.
11. **`EmbeddedMySql.java:88-91`.** The MySQL launcher hands out a MariaDB Connector/J URL (`jdbc:mariadb://`). That
    is deliberate ("one driver for both lanes") and consistent with `Sessions.java:99-102, 233-236`. It is recorded
    because the MySQL lane's driver identity is MariaDB's: any product-name check must read the server, not the
    driver.
12. **`spec/BUILD.bazel:17` (`_MEASURED_ROSTERS`, lane in `("duckdb","h2")`) and `spec/corpus.bzl` (`rosters`).**
    These are the Bazel twins of TL-7's lane list. A per-lane `CorpusBackend.rosterPrefix()` should stay in step with
    them.
13. **The ledger files are wired by hand, one Java class per lane and suite (51 files: 5 for DuckDB, 45 for nine other lanes, 1 for H2).** Examples are
    `Test_LegendLite_Postgres_EssentialFunctions_PCT` and the rest of `pct/src/test/java/.../Test_LegendLite_*`. Each
    differs only in its expected-failure list and its `reportScope`. That is per-lane data in class form, which is
    legitimate but heavy. A 12th database means five new classes plus BUILD rows.
