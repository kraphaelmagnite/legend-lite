# B_other_product: dialect-leak census, product code outside core

2,448 hits in 187 files. Every id is classified in `B_other_product.tsv`, one row per id. The ids match the input exactly.

## The engine of each module

I read the README or design doc of each module first.

- **warehouse** (`docs/WAREHOUSE_W1_DESIGN_2026_09_26.md`, `docs/DATACUBE_ON_POSTGRES.md`)
  - It is an HTTP SQL API server over DuckDB's C API, called through `java.lang.foreign` (FFM).
  - **It is not a Postgres wire-protocol server.** The Postgres naming is not protocol. `Attachment.POSTGRES` attaches
    a Postgres database as a read-only **catalog**, through DuckDB's `postgres` extension (`ATTACH ... (TYPE postgres,
    READ_ONLY)`). The client's Postgres SQL is then passed through with `postgres_query('attached', ...)`.
  - So the warehouse is **mixed-engine**: DuckDB native catalogs plus attached Postgres catalogs.
  - Each catalog's `databaseType` is reported to clients, who plan with that dialect.
  - `//tools/deps:warehouse_closure_test` forbids any dependency on core. That is why the warehouse duplicates core
    facts: `DuckDb.sessionSetup`, the catalog listing, and Postgres's UTC session.
- **datacube**, **engine-client**, **query**, **studio**: browser apps.
  - The local engine is DuckDB-wasm in the tab (`DuckDbEngine`).
  - The remote planes are the warehouse (`WarehouseEngine`) and legend-engine's `pure/v1` (`engine-remote.ts`).
  - The planner is core compiled to wasm.
  - The dialect is picked from data: `QueryEngine.databaseType` or `CatalogObject.databaseType`. I found **no
    TS branch on a database name.**
  - Plane selection happens in two places: `query ExecutionConfig.kind` and studio's `inTab ? ... : server`.
- **wasm** (`wasm/README.md`): core's planner compiled with TeaVM. `planner/Boundary.java` references dialect classes
  for two reasons:
  - `testDataSql` seeds the tab's DuckDB through `new DuckDb()`.
  - `catalogColumnsSql` reads `DuckDb.CATALOG_COLUMNS_SQL`.
  - Everything else selects the dialect by type through `Databases.dialect(Databases.named(t))`.
- **python**: the frames run on duckdb-python, DuckDB by design.
- **native**: the same Boundary exports as a C library.
- **json, depot-server, sdlc-server, pure-protocol, query-store**: no database at all. Every hit there is a false
  positive or a Pure name.
- **datacube/tools/apppostgres**: a test tool for `//datacube:verify_app_test`.
  - It starts Postgres 16 through `com.legend.testing.EmbeddedPostgres` and loads `sample-shop.sql` over JDBC.
  - It is test tooling for the "DataCube on Postgres" feature, not product.

## Count table

| class | hits | groups |
|---|---:|---:|
| LEAK-SQL | 84 | 2 |
| LEAK-ID | 46 | 4 |
| LEAK-CONN | 9 | 3 |
| LEAK-ERR | 3 | 1 |
| LEAK-RAW | 0 | 0 |
| LEAK-TYPE | 0 | 0 |
| TEST-LEAK | 14 | 2 |
| SEAM | 31 | 6 |
| DIALECT-INTERNAL | 0 | 0 |
| DIALECT-SHARED | 0 | 0 |
| SINGLE-BACKEND-APP | 1430 | 40 |
| TEST-GOLDEN | 127 | 6 |
| LANG | 66 | 6 |
| DIAG | 111 | 9 |
| FP | 527 | 15 |
| **total** | **2448** | **94** |

## Leak groups (LEAK-* and TEST-LEAK)

### LEAK-SQL: `wh Postgres attached-catalog SQL` (80 hits)

- **Where:** `warehouse/src/main/java/com/legend/warehouse/server/Postgres.java`
  - 43-58: `requireSupportedVersion`, which runs `current_setting('server_version_num')` against minimum version 16.
  - 70-98: `catalogListing`, a pg_catalog query over `pg_attribute`, `pg_class`, `pg_namespace` and `pg_type`, with
    `format_type`, a typmod decode and `has_column_privilege`.
  - 107-119: `query`, which tags the statement and wraps it in `postgres_query`.
  - 125-129: `cancel`, which calls `pg_cancel_backend` over `pg_stat_activity`.
  - 218-224: the `tag` comment.
- **What it does:** builds Postgres SQL text and wraps it in DuckDB's `postgres_query(...)`.
- **Why it is a leak:**
  - These are Postgres catalog, version and cancel spellings, composed outside core's `sql/dialect/Postgres`.
  - Core's Postgres dialect already owns `CATALOG_TYPES` and `CatalogRules`, which read exactly this listing's
    `logical_type`, `numeric_precision` and `numeric_scale` columns. So the question and its reader live apart.
  - The minimum version 16 duplicates the dialect's own version floor.
- **Where it belongs:**
  - On core's `Postgres` dialect:
    - `catalogListingSql(List<String> systemSchemas)`, the mirror of `DuckDb.CATALOG_COLUMNS_SQL`;
    - `serverVersionSql()` plus `MIN_SERVER_VERSION_NUM`;
    - `cancelTaggedSql(String tag)`.
  - On the DuckDB side: the `postgres_query(alias, text)` wrapping becomes `DuckDb.passthrough(alias, sql)`.
- **Size:** about 80 lines moved.
- **Coupling:**
  - Callers: `Attachment.{catalogListing, passthrough, cancel, requireSupported}` (`Attachment.java:39-71`),
    `Statements.passthrough` (383) and `Statements.cancelAttached` (221).
  - Pinned by `warehouse/src/test/.../PostgresCatalogTest.java`, `AppModeTest.java` and `WarehousePostgresLiveTest`.
  - **Blocked by `//tools/deps:warehouse_closure_test`**, which forbids the warehouse from reaching core. The move needs
    a small shared "database facts" module, or a relaxed closure.

### LEAK-SQL: `snap.preflight count SQL on remote engine` (4 hits)

- **Where:** `engine-client/src/snap.ts:201-224`, flagged at 205.
- **What it does:** runs `SELECT count(*) AS n FROM (${sourceSql})` on `this.#remote ?? local`. The remote is a
  warehouse catalog, either DuckDB or an attached Postgres one.
- **Why it is a leak:**
  - It is hand-written SQL in TS, on a path that serves several databases.
  - The only thing that makes it work is that the text happens to be valid in both. For example, a subquery alias is
    optional only from Postgres 16.
  - A third attached database would have to be checked by hand.
- **Where it belongs:** either option works.
  - Plan the count through the planner: `<source>->size()` on the source's own runtime.
  - Or add `QueryEngine.countRows(sql)`, implemented per engine and backed by a dialect method
    `SqlDialect.countRowsOf(SqlQuery)`.
- **Size:** about 10 to 15 lines.
- **Coupling:**
  - Callers: `SnapController.snap` (`snap.ts:242`), reached from DataCube's Snap on a warehouse table
    (`datacube/demo/boot.ts:928-940`).
  - Pinned by stubs in `engine-client/test/snap.test.ts:110`, `datacube/test/plane.test.ts:101` and
    `datacube/test/app-dom.test.ts:59`.

### LEAK-ID: `WarehouseServer Postgres CLI/Config` (30 hits)

- **Where:** `warehouse/src/main/java/com/legend/warehouse/server/WarehouseServer.java`
  - 54-126: the `Config` record has a `Map<String,String> postgres` field and `withPostgres`.
  - 171: `Attachment.POSTGRES` is hard-wired.
  - 772: catalog names.
  - 790-799: `withPasswordAsked`, a libpq password prompt.
  - 850-890: the CLI, which tests `PostgresUrl.isUrl(arg)` and parses `--postgres NAME=DSN`.
  - 911-937: validation and the extension directory for `Attachment.POSTGRES.extensionFile`.
  - 960.
- **What it does:** handles CLI parsing, configuration and the password retry, all specifically for Postgres.
- **Why it is a leak:**
  - `Attachment`'s javadoc says it is "the only place the warehouse names" an attachable database, with exhaustive
    switches. But the server's `Config`, CLI and password flow name Postgres directly.
  - A second attachable database (MySQL, or SQLite through DuckDB's extensions) would mean editing all of these.
- **Where it belongs:**
  - `Config` holds `Map<String, Catalogs.Attach> attached`.
  - The CLI asks `Attachment.forArgument(arg)`, a per-row URL scheme and flag name.
  - The password prompt becomes `Attachment.withPassword(dsn, pw)` / `Attachment.asksPassword`.
- **Size:** about 40 to 50 lines.
- **Coupling:** `AppModeTest` and `WarehouseServerTest` (`Config.parse`), and `datacube:app` (`DATACUBE_ON_POSTGRES.md` §6
  documents `--postgres`).

### LEAK-ID: `wh postgres:// URL-prefix dispatch` (2 hits)

- **Where:**
  - `warehouse/.../server/PostgresUrl.java:22-24`: `isUrl` tests `postgresql://` and `postgres://`.
  - Used at `WarehouseServer.java:793` and `:858`, and at `Postgres.java:152`.
- **What it does:** a URL prefix decides that an argument is a Postgres catalog.
- **Why it is a leak:** it is a URL-prefix identity test.
- **Where it belongs:** `Attachment.forUrl(String)`, a per-row scheme list, or `connector.acceptsUrl(url)`.
- **Size:** about 5 lines.
- **Coupling:** the CLI group above.

### LEAK-ID: `Boundary.testDataSql hard-wires new DuckDb()` (12 hits)

- **Where:** `wasm/src/main/java/planner/Boundary.java:183-219`, flagged at 185 and 206-214.
- **What it does:** renders the tab's seed SQL with a dialect built by class name. This covers `CsvSeed.sqls`,
  `physicalName` and `Ddl.dropTable`.
- **Why it is a leak:**
  - It selects a database by dialect class, not through the `Databases` registry.
  - In the same file, `sessionSetup(databaseType)` already goes through `Databases.dialect(named(t))`.
  - The tab engine is DuckDB-wasm by design, so this is minor. The rendering itself goes through the dialect, which is
    the right shape.
- **Where it belongs:** `testDataSql(model, database, tablesJson, databaseType)` resolves the dialect with
  `dialectOf(databaseType)`. The tab passes `DuckDbEngine.databaseType`, or the code uses `Databases.PLATFORM`.
- **Size:** about 6 lines of Java, plus about 10 lines of TS plumbing (`planner-protocol.ts`, `wasm-grammar.ts`,
  `model-data.ts:121`).
- **Coupling:**
  - `TabExports.testDataSqlOrError` and `native/.../Compiler.java`.
  - `engine-client/src/model-data.ts` and `python/legend_lite/_headless.py`.
  - Pinned by `python/tests/test_compiler.py:166` and the wasm differentials (`//wasm:differential_test`,
    `//datacube:wasm_differential_test`).

### LEAK-ID: `Boundary.catalogColumnsSql static DuckDb.CATALOG_COLUMNS_SQL` (2 hits)

- **Where:** `wasm/src/main/java/planner/Boundary.java:291-301`, flagged at 293. The literal spelling is `sqlLiteral`,
  at 362-364.
- **What it does:** reads a DuckDB-only static constant off the dialect class. It splices `{schema}` and `{table}` in as
  `'...'` literals that Boundary spells itself.
- **Why it is a leak:**
  - "The catalog question" is a per-database fact. Only DuckDB has it, as a static field, with no `SqlDialect` method.
  - Postgres's equivalent lives in the warehouse (`Postgres.catalogListing`).
  - String-literal spelling is dialect work.
- **Where it belongs:** `SqlDialect.catalogColumnsQuery(String schema, String table)`. DuckDb returns its template with
  literals it spells itself. Postgres returns the query from the LEAK-SQL group above. Boundary calls
  `dialectOf(databaseType).catalogColumnsQuery(...)`.
- **Size:** about 15 lines in core, plus about 5 in Boundary and its TS/Python callers.
- **Coupling:**
  - `datacube/src/upload.ts:173`, `datacube/src/lite-planner.ts:609` and `python/legend_lite/frames.py:96`.
  - `core/src/test/.../CatalogModelTest.java:82` uses the constant directly.

### LEAK-CONN: `wh Postgres libpq DSN + session zone` (3 hits)

- **Where:** `warehouse/.../server/Postgres.java:131-216`:
  - `SESSION_ZONE`;
  - the `TIME_ZONE_OPTION` regex over libpq `options`;
  - `inSessionZone`, which adds `-c TimeZone=UTC`;
  - `keyValues`, a libpq `conninfo_parse` clone;
  - `quote`.
- **What it does:** builds and validates the libpq connection string that a Postgres catalog attaches with. It pins
  the UTC session that core's `Postgres.sessionSetup` sets on its own connections.
- **Why it is a leak:**
  - This is per-database connection handling: DSN syntax, session options and the UTC contract.
  - It sits outside any per-database connector interface, and duplicates core's Postgres session contract.
- **Where it belongs:** a per-database connector, `PostgresConnector`, with:
  - `libpqDsn(String urlOrKeyValues)`;
  - `sessionOptions()`, the UTC zone as a startup option, shared with `Postgres.sessionSetup`;
  - `quoteDsnValue`.
- **Size:** about 90 lines moved.
- **Coupling:**
  - Callers: `Attachment.connectionString` and `PostgresUrl`.
  - Pinned by `PostgresCatalogTest`.
  - The warehouse closure rule applies here too.

### LEAK-CONN: `wh PostgresUrl URL->DSN` (5 hits)

- **Where:** `warehouse/.../server/PostgresUrl.java:17-93`:
  - `parse`: catalog naming and the default `-c statement_timeout=60000`;
  - `params`: a psql-style URL read into libpq key names;
  - `withPassword`;
  - `keyValues`.
- **What it does:** turns a `postgresql://` URL into a libpq DSN.
- **Why it is a leak:** per-database connection handling (URL grammar, default timeout) outside a connector.
- **Where it belongs:** `PostgresConnector.fromUrl(url)` / `.withPassword(dsn, pw)`, next to the LEAK-CONN group above.
- **Size:** about 75 lines moved.
- **Coupling:** `WarehouseServer.parse` and `withPasswordAsked`, and `AppModeTest`.

### LEAK-CONN: `wh session engine name hard-coded` (1 hit)

- **Where:** `warehouse/.../server/Sessions.java:44-47`.
- **What it does:** `new SqlApi.Session(id, catalog, "DuckDB", DuckLibrary.version())`.
- **Why it is a leak:**
  - This is the product name the warehouse JDBC driver reports (`WhDatabaseMetaData.getDatabaseProductName`, which
    returns `conn.engine`).
  - It says "DuckDB" even when the session's catalog is an attached Postgres one, whose SQL is Postgres's.
  - `Catalogs.databaseType(catalog)` already knows the right answer.
  - I am unsure whether the host engine name was intended here. Classed as a leak per the guide's rule for doubt.
- **Where it belongs:** `Attachment.productName` (or `Catalogs.databaseType(catalog)`) for the session's catalog.
- **Size:** about 3 lines.
- **Coupling:** `WhConnection.engine` and core's JDBC product-name dispatch (`exec/Sessions`), when core connects
  through `jdbc:warehouse:`.

### LEAK-ERR: `wh DuckDB error-kind classification` (3 hits)

- **Where:** `warehouse/.../server/Statements.java:525-534`, `classify(DuckException)`.
- **What it does:** matches message prefixes: `Parser Error` gives SQL_PARSE; `Binder Error` or `Catalog Error` gives
  SQL_BIND.
- **Why it is a leak:**
  - Vendor error text is matched in generic statement code, not in `server/duck/` (the DuckDB connector package).
  - Errors from an attached Postgres catalog arrive wrapped by DuckDB's extension and always fall to SQL_EXECUTE.
- **Where it belongs:** `DuckException.kind()` in `duck/`, plus `Attachment.classify(message)` for passthrough errors.
- **Size:** about 10 lines.
- **Coupling:** `Statements` (287, 369) and `WarehouseServerTest` error-code expectations.

### TEST-LEAK: `verify-engine-differential H2 seed -> DuckDB` (12 hits)

- **Where:** `datacube/demo/verify-engine-differential.mjs:76-125`. The scanner flagged 118-123 (DDL and INSERT).
  `seededRows`' regex over `INSERT INTO ... VALUES` text, at 82, is unflagged.
- **What it does:**
  - Reads the H2 `testDataSetupSqls` text out of `trades-h2.pure` and regex-splits its `INSERT` tuples.
  - Replays them into a hand-written DuckDB `CREATE TABLE TRADES (...)` on DuckDB-wasm.
- **Why it is a leak:** the harness adapts one database's raw SQL (H2 seed text) into another's inline. It does not go
  through a per-backend loader.
- **Where it belongs:** a per-backend test-data loader.
  - Put the rows in a Pure `Data` element (or CSV) shared by both models.
  - Seed DuckDB through `Boundary.testDataSql` (`engine-client` `seeded()`).
  - Seed H2 through its own connection setup.
- **Size:** about 40 lines.
- **Coupling:** `datacube/demo/trades-h2.pure`, `trades.pure`, `engine-cases.mjs` and the `//datacube:verify_engine_differential`
  target.

### TEST-LEAK: `AppPostgres inline JDBC script runner` (2 hits)

- **Where:** `datacube/tools/apppostgres/AppPostgres.java`:
  - 37: the `"postgres"` maintenance database;
  - 40-52: psql `\c` meta-command handling;
  - 61-69: `DriverManager.getConnection(pg.jdbcUrl(db))`, flagged at 66.
- **What it does:** runs a psql script against EmbeddedPostgres.
- **Why it is a leak:** the per-backend harness (`testing/.../EmbeddedPostgres`) owns launch and URL. The script runner,
  the JDBC open and the maintenance-DB name sit inline in a datacube tool.
- **Where it belongs:** `EmbeddedPostgres.runScript(String database, String psqlText)` or
  `EmbeddedPostgres.connect(database)`.
- **Size:** about 15 lines.
- **Coupling:** `//datacube:verify_app_test`, through `verify-app.mjs:34-56`.

## SEAM, DIALECT-SHARED and SINGLE-BACKEND-APP groups

**SEAM** (6 groups)

- `wh Attachment registry` (`warehouse/.../Attachment.java`): the warehouse's own registry of attachable databases.
  - It is a **per-concern switch** design: an enum with exhaustive switches, one per operation, each delegating to the
    per-database class `Postgres`.
  - It parallels core's `Databases` and `Sessions`. The javadoc claims sole naming, but `WarehouseServer` breaks that
    claim (LEAK-ID above).
- `Boundary.dialectOf via Databases registry` (`Boundary.java:307-308`, `TabExports.java:113`): a consumer. It goes
  `databaseType` → `Databases.dialect(named)`, which is the correct shape.
- `native sessionSetup export` (`native/.../Compiler.java:233-237`): the same consumer, exposed through the C API.
- `python Frames session_setup via registry` (`frames.py:186`): `session_setup('DuckDB')` goes through Databases.
- `query ExecutionConfig plane factory` (`query/src/app/context.ts:28`, `query/demo/main.ts:96-126`): config `kind` →
  `QueryEngine`. The choice is between planes (tab DuckDB, warehouse, server), not database identities.
- `studio plane selection` (`studio/src/app/app.ts:43`): `inTab ? inTabSession : serverSession`.

**DIALECT-SHARED:** none in this slice.

**SINGLE-BACKEND-APP** (40 groups). Each one runs on DuckDB by design. "Bypass" marks a place where core's DuckDb dialect
could have rendered the SQL.

*Warehouse*

- `wh Duck FFM C-API bindings` (`Duck.java`, 448 hits): the DuckDB C API symbol table.
- `wh DuckDB type names (Arrow/values/encoder)`: switches over DuckDB type names for Arrow, JSON and values.
- `wh DuckDB session setup + identity macros` (`Database.java:64-76`): `SET SESSION TimeZone`. A bypass: it duplicates
  `DuckDb.sessionSetup` (closure rule).
- `wh DuckDB lockdown/attach statements` (`Database.java:95-111`, `Catalogs.java:89`): `SET` / `LOAD` / `ATTACH`, generic
  over the Attachment row.
- `wh DuckDB catalog functions` (`Database.java:153`, `Statements.java:476-482`): `duckdb_*()` lookups.
- `wh DuckDB native catalog listing` (`WarehouseServer.java:520-531`): a bypass. It duplicates
  `DuckDb.CATALOG_COLUMNS_SQL`'s shape.
- `wh Authorizer on DuckDB json_serialize_sql`: the reader authorizer walks DuckDB's parse tree.
- `wh system DB DDL/DML (grants, history)` (`Grants.java`, `History.java`): a bypass. It is fixed DDL that `SqlDdl` /
  the DuckDb dialect could render (closure rule).
- `wh SHOW GRANTS VALUES query` (`Statements.java:510-518`): DuckDB `VALUES`, `::VARCHAR` and `ORDER BY ALL`.
- `wh admin-statement grammar` (`AdminStatements.java:79`): the warehouse's own GRANT grammar.
- `wh catalog file naming`: `*.duckdb` files.
- `wh Config DuckDB library/extension paths`: the `--duckdb-library` and `--duckdb-extensions` flags.
- `wh DuckDB native library location` (`DuckLibrary.java`): the native library file name for each OS.
- `wh JDBC client mimics DuckDB JDBC carriers` (`Carriers.java`): Java objects that match DuckDB's own JDBC driver.
- `wh JDBC client transaction SQL` (`WhConnection.java:66-93`): `BEGIN TRANSACTION` / `COMMIT` / `ROLLBACK`.
  - On an attached Postgres catalog these would be wrapped in `postgres_query`, and would fail.
- `wh JDBC driver own URL scheme` (`WhDriver.java:27`): `jdbc:warehouse:`.
- `wh sqlapi DuckType names` (`ApiValues`, `ArrowIpcReader`, `Columnar`): the API's types are DuckDB's.

*wasm and engine-client*

- `engine-client DuckDB-wasm boot (tab)` (`duckdb-tab.ts`, `duckdb-wasm.ts`).
- `engine-client DuckDbEngine (tab connector)` (`duckdb.ts:258-261`): `databaseType = 'DuckDB'`.
- `engine-client DuckDbEngine.loadArrow DDL` (`duckdb.ts:382-386`): `CREATE SCHEMA` / `DROP TABLE` inside the engine
  class. A bypass: `Ddl.dropTable` exists.
- `engine-client loadFileTable (read_csv/parquet)` (`model-data.ts:139-176`): the seed DDL comes from the dialect, while
  `read_csv` and `INSERT…SELECT` are composed here.
- `snap local-store DDL` (`snap.ts:255-280, 324`): `SELECT * REPLACE` and `CREATE OR REPLACE TABLE` in DuckDB-only
  text. A bypass, although the copy store carries a typed `snapDatabaseType`.
- `tab-data row count` (`tab-data.ts:111`).

*datacube*

- `datacube headless grid on DuckDbEngine` (`src/headless/*`).
- `datacube remote-file mount (httpfs/iceberg/SECRET)` (`src/remote.ts`).
- `datacube upload ingest` (`src/upload.ts:78-156`): `CREATE OR REPLACE TABLE … AS read_csv(...)` from an upload. A
  bypass: the CREATE TABLE AS and DROP could be rendered by the DuckDb dialect.
- `datacube demo DuckDB-wasm boot`: boot code copied into about 10 demo, harness and bench scripts.
- `datacube demo generateTrades` (`boot.ts:379-391`): DuckDB `range()`.
- `remote-harness hand-written grouped query` (`demo/remote-harness.ts:52-53`).
  - A stand-in for planner SQL. "Remote" means Parquet over HTTP into DuckDB-wasm, **not** a server database.
- `datacube DuckDB-wasm dist assets` (`make-dist.mjs`).
- `datacube bench DuckDB microbenchmarks` (`datacube/bench/*`, 328 hits).

*python*

- `python headless DuckDB connection` (`_headless.py:89-97`).
- `python engine DuckDB error type` (`engine.py:47, 215`).
- `python Frames DuckDB connection`.
- `python Frames DDL` (`frames.py:92-125`): `CREATE` / `DROP VIEW|TABLE`. A bypass.
- `python Frames databaseType literal` (`frames.py:102`).
- `python Frames existence via duckdb_tables()` (`frames.py:240-244`).
- `python wheel requires duckdb`.

*query and studio*

- `query/studio in-tab DuckDB session`.
- `query verify warehouse launch flag`.

The other classes are TEST-GOLDEN, LANG, DIAG and FP. The TSV notes each one; the main points:

- **TEST-GOLDEN:** DuckDB fixtures in datacube's verify scripts, python `test_compiler` / `test_frames` goldens,
  verify-app's test Postgres, and studio's playground SQL.
- **LANG:** the Pure classifier paths in `sdlc-server Classifiers.java`, the Pure demo models in `TabExports` and
  `emit.ts`, the `trades::h2` element names, and the Boundary connection text. Every connection gets
  `specification: DuckDB { }`, because the planner reads only the type.
- **FP:** UI words such as "Show", "Select", "Create" and "Copy", `<h2>` tags, "JSON", git `commit` / `40000`, and the
  SHA-1 variable `h2`.

## Misses

These are places in or near these files with database-specific behaviour that the scanner did not flag.

1. **`warehouse/.../server/Catalogs.java:98, 105`: LEAK-ERR.**
   - `NO_PASSWORD = "no password supplied"` is libpq's own error text, matched with `why.contains(NO_PASSWORD)`. The
     match decides whether to prompt for a Postgres password.
   - It is Postgres (libpq) vendor-message knowledge in generic catalog code.
   - It belongs in `Attachment.missingPassword(String message)`, delegating to `Postgres`.
2. **`warehouse/.../server/WarehouseServer.java:790-799`, `withPasswordAsked`.** It is libpq's password model
   (`~/.pgpass` / `PGPASSWORD`, then ask once). It is only partly flagged; line 794 is not. It belongs with the LEAK-ID
   CLI group, as `Attachment.withPassword`.
3. **`warehouse/.../server/Postgres.java:142-143`, `TIME_ZONE_OPTION`.** A regex over libpq `options` (`-c timezone=` /
   `--timezone=`). Unflagged; part of the LEAK-CONN DSN group.
4. **`wasm/src/main/java/planner/Boundary.java:299-300, 362-364`, `sqlLiteral`.** SQL string-literal spelling
   (`'` doubled) outside the dialect, used to fill `DuckDb.CATALOG_COLUMNS_SQL`. Part of the `catalogColumnsSql`
   LEAK-ID group.
5. **`datacube/demo/verify-engine-differential.mjs:82`.** A regex parse of H2 `INSERT INTO … VALUES (…);'` text out of
   a Pure file. Unflagged, because it is a regex literal and not a string. Part of the TEST-LEAK group.
6. **`datacube/tools/apppostgres/AppPostgres.java:40-52`.** psql `\c` / `\connect` meta-command parsing, which is
   Postgres client knowledge. Part of its TEST-LEAK group.
7. **`query/demo/main.ts:34-41`, `seed()`.** Splits a `.sql` file (`models/trading-seed.sql`) into lines and runs them,
   whichever plane the config chose: the tab's DuckDB or a **warehouse** catalog (`config-warehouse.json`).
   - The SQL is DuckDB's and goes through no dialect.
   - A warehouse catalog attached to Postgres (read-only) would refuse it.
   - Low severity: a demo seeding path.
8. **Identifier-quoting helpers duplicated in TS.** They spell the DuckDB/ANSI identifier rule outside the dialect:
   - `engine-client/src/snap.ts:129-135` (`quoteIdent`, `qualified`);
   - `engine-client/src/duckdb.ts:382` (`qi`);
   - `engine-client/src/model-data.ts:139-140` (`ident`, `literal`);
   - `datacube/src/upload.ts:34` (`dq`);
   - `datacube/src/remote.ts:96-98` (`ident`).

   The context is single-backend: they serve the tab's DuckDB only. But the planner already returns dialect-spelled
   names (`Boundary.testDataSql` → `table` / `columns[].sql` through `DuckDb.physicalName`), so these are a second
   spelling.
9. **DuckDB's default schema name `'main'`, hard-coded.**
   - `datacube/src/upload.ts:173`: `catalogColumnsSql('main', table)`.
   - `python/legend_lite/frames.py:96` (unflagged) and 240-244.

   Single-backend, but it is a DuckDB catalog fact. It would come with `SqlDialect.catalogColumnsQuery` if the default
   schema became a dialect fact.
10. **`warehouse/.../client/jdbc/WhConnection.java:66-93`** is flagged, but its behavioural consequence is not: an
    attached-Postgres session given `setAutoCommit(false)` sends `BEGIN TRANSACTION`. `Statements.passthrough` would
    wrap that in `postgres_query(...)`, and it would fail. The transaction statements should be the attachment's
    concern, or be refused for attached catalogs.
