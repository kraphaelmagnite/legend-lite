# A2_core_front: dialect-leak classification

Slice: 668 hits in 84 files under `core/src/main/java/com/legend/` in model/, parser/, protocol/, lexer/,
normalizer/, plan/, platform/, server/, testdatagen/ and compiler/. All paths below are relative to that root
unless they say otherwise. Every construct was read before a rule was written for it. The rules are in
`work/a2_classify.py`.

## 1. Counts

| class | hits | groups |
|---|---:|---:|
| LEAK-SQL | 80 | 6 |
| LEAK-TYPE | 32 | 2 |
| LEAK-CONN | 8 | 2 |
| LEAK-ID | 6 | 2 |
| LEAK-RAW | 5 | 2 |
| LANG | 485 | 29 |
| FP | 52 | 8 |
| SEAM / DIALECT-INTERNAL / DIALECT-SHARED / SINGLE-BACKEND-APP / TEST-* / DIAG | 0 | 0 |
| **total** | **668** | **51** |

The leaks are in two places. Front-end code (lexer, parser, protocol, model, normalizer) is clean: it is all
Legend grammar and protocol. The real leaks are:

- `testdatagen/TestDataGenerator.java`: 8 groups, 60 hits.
- `compiler/spec/CatalogGrids.java`: SQL composed in phase G, the Typer.
- Three small sites in `plan/`.
- One default in `compiler/spec/typed/ContextReading.java`.
- One branch in `server/CreateClass.java`.

## 2. Leak groups (each listed separately)

### L1. LEAK-SQL: TDG live fetch SQL (raw on ambient session), 12 hits

- **Where:** `testdatagen/TestDataGenerator.java`:
  - `fetchRoot` 293-335 (SQL at 319);
  - `fetchChild` 375-445 (393, 421);
  - `selectAll` 477-479;
  - `milestoningFilter` 515-570;
  - `materialize` 574-582 (579);
  - `renderCondition` 693-741;
  - `lit` 1236-1260 (1249);
  - `seedDataString` 1332-1335;
  - `qualify`, `q` and `dropTemps` 1657-1693 (1687).
- **What it does:** builds the generator's fetch SQL by hand and runs it with `Statement.execute` and
  `executeQuery` on whatever session is ambient. That SQL is:
  - `select "root"."C" ... from S.T as "root" where (...) limit 20`;
  - `... from tdg_N as main inner join ... on ...`;
  - `CREATE TEMPORARY TABLE tdg_N AS <sql>`;
  - `DROP TABLE IF EXISTS`;
  - `DATE '...'` and `TIMESTAMP '...'` literals and `TRUE`/`FALSE`;
  - join conditions rendered from `RelationalOperation`;
  - identifiers that `q()` quotes and uppercases.
- **Why it is a leak:** the javadoc of `q()` says it outright: "ONE spelling valid on BOTH targets ... no dialect,
  no boundary" (H2 and DuckDB). On a 12th database this text breaks:
  - SQL Server and Sybase have no `limit`;
  - BigQuery has no `CREATE TEMPORARY TABLE ... AS` outside a script;
  - Postgres folds unquoted identifiers to lower case, so the quoted-uppercase rule misses.

  Identifier rules belong in the dialect.
- **Where it belongs:** build MIR and render it with the session's `SqlDialect`:
  - a `SqlSelect` with `SqlSource.Join` and a limit;
  - a new `SqlDdl.CreateTempTableAs(name, SqlQuery)` node, with an arm in every dialect
    (`renderCreateTempTableAs`), plus `SqlDdl.DropTable`;
  - literals as `SqlExpr` literal nodes;
  - identifiers through the dialect's quoting rule (`SqlDialect.quoteIdentifier`).

  The generator would then need the session's dialect handed in, as `foldCensus` already receives `engineText`.
- **Size:** about 250-300 lines (a new MIR node plus dialect arms, and rewriting 8 methods).
- **Coupling:**
  - Call path: `TestDataGenerationNatives.transcript` / `seedStringOrDataError`, called from
    `BodyCompiler.execute:67`, which passes `seg.env().connection()`.
  - Pins: `SqlTextRatchetTest` REGISTER `testdatagen/TestDataGenerator.java` = 15; `ParkedWorkLedgerTest`
    PARK-16, which anchors `qualify`'s raw `"default"` rule; `JdbcSurfaceCensusTest:173`; `TenetRatchetTest`
    (TDG raw-read sites); `JavaEvalLedgerTest` EVICT_NAMES (TDG `renderCsv` = 0); `ArchitectureTest:1090`, an
    exclusion; `StatementOrigin.TDG` counters.

### L2. LEAK-SQL: TDG csvEnvelope SQL (DuckDB-flavoured), 6 hits

- **Where:** `testdatagen/TestDataGenerator.java:951-1046` (`csvEnvelope`; hits at 963, 984, 1030 and 1033).
- **What it does:** builds the CSV text in SQL:
  - a `union` of temps, or `select distinct * from`;
  - a `select * from (...) limit 0` probe of the metadata;
  - for hashing, `repeat(substr(sha256(c),1,5), length(c)//5) || right(...)`;
  - the scrub `replace(replace(replace(c, chr(39), ' '), ',', ';'), chr(10), ' ')`;
  - `coalesce(cast(_r.c as varchar), '---null---') || ',' || ...`, then `order by`.
- **Why it is a leak:** these are DuckDB spellings composed outside the dialect. `//` is DuckDB's
  integer-division operator. `sha256()` returning hex, `chr`, `right` and `cast as varchar` all vary by database.
  The hashStrings arm cannot run on H2, and the rest is unproven elsewhere.
- **Where it belongs:** a `SqlSelect` built from `SqlExpr` and `SqlFn` nodes (hash, repeat, replace, concat,
  cast, coalesce) and rendered by the session dialect. Failing that, one method, `SqlDialect.csvLine(...)`.
- **Size:** about 80-100 lines.
- **Coupling:**
  - Pins: `JavaEvalLedgerTest` E5 (the row text must stay SQL); `SqlTextRatchetTest` count 15, which it shares
    with L1.
  - L5's `isTextType` drives the arm choice.

### L3. LEAK-SQL: TDG compareCsv temp load + EXCEPT diff, 9 hits

- **Where:** `testdatagen/TestDataGenerator.java`, `compareCsv` 1063-1110 (EXCEPT at 1091-1094) and `loadSide`
  1122-1160 (1125, 1140).
- **What it does:**
  - `CREATE TEMPORARY TABLE tdgcmp_N (cols types)`;
  - a multi-row `INSERT INTO ... VALUES ('..'), ...` with quotes doubled;
  - `select count(*) from ((select * from a except select * from b) union all (...))`.
- **Why it is a leak:** DDL, DML and set operations hand-composed on the multi-backend path. Some databases spell
  EXCEPT as `MINUS`; some need `EXCEPT DISTINCT` (BigQuery).
- **Where it belongs:** reuse the existing dialect-rendered nodes:
  - `SqlDdl.CreateTable` with a temp flag;
  - the `RowLoad` / `SqlDml` rows that `setup/` already renders;
  - a `SqlUnion` (or a new `SqlSetOp`) EXCEPT node with a dialect arm, `renderExcept`.
- **Size:** about 60 lines.
- **Coupling:** `assertTestData` through the S2 fold; `SqlTextRatchetTest` (15, shared).

### L4. LEAK-TYPE: TDG duckType table, 20 hits

- **Where:** `testdatagen/TestDataGenerator.java:1206-1233`.
- **What it does:** maps each `RelationalDataType` to DuckDB type names for the typed compare tables of L3. It
  widens deliberately (SmallInt/TinyInt/Integer to BIGINT; Float/Real to DOUBLE; text kinds to VARCHAR).
- **What the table is for:** typing the temp tables of the assertTestData row-set compare, so that the database
  normalizes cell values.
- **Why it is a leak:** a per-database type table outside the dialect, named for DuckDB.
- **Where it belongs:** `StoreCompiler.declaredType(t)`, which yields an `SqlDdl.ColumnType`, rendered through
  the dialect's DDL type spelling (`DdlSpelling`). If the widening is needed, a `SqlDdl.ColumnType` "compare"
  kind would carry it.
- **Size:** delete about 28 lines, plus about 5 lines of glue.
- **Coupling:** only `loadSide`.

### L5. LEAK-CONN: TDG direct JDBC metadata reads, 7 hits (unsure)

- **Where:** `testdatagen/TestDataGenerator.java`:
  - `captureRows` 488-507 (494, 497);
  - the `csvEnvelope` limit-0 probe 982-992 (985, 987);
  - `isTextType` 1048-1052.
- **What it does:** reads `ResultSetMetaData` itself, and decides which columns are "text" from
  `java.sql.Types` codes (only VARCHAR, CHAR and LONGVARCHAR).
- **Why it is a leak:** the JDBC handling is generic, but the text-code set depends on the driver. NVARCHAR,
  CLOB and Postgres `text` reported as other codes would go unhashed or unscrubbed. It is also a second JDBC
  surface outside the executor and connector. This is the least certain of the groups.
- **Where it belongs:** decide text-ness from the store model (`RelationalDataType` of the column), which is
  already at hand. Read rows through the exec seam rather than raw `Statement`. If a driver code is truly needed,
  use `DatabaseConnector.isTextColumn(ResultSetMetaData, int)`.
- **Size:** about 20 lines.
- **Coupling:** `JdbcSurfaceCensusTest:173` registers this file as a JDBC site; the "one getObject" carriage in
  `TenetRatchetTest`.

### L6. LEAK-SQL: TDG engine-H2 plan-text SQL (select top), 3 hits (referee)

- **Where:** `testdatagen/TestDataGenerator.java`:
  - `planText` 1368-1390 and `planNode` 1392-1440;
  - `planRootErrorNode` 1468-1500 and `planRootSql` 1502-1535;
  - `sampleSelect` 1539-1545 (1541) and `planChildSql` 1547-1570 (1561, 1564);
  - `planCond` 1575-1610 and `planMilestone` 1612-1650.
- **What it does:** prints legend-engine's `planTestDataGeneration` SQL in engine-H2 spelling:
  - `select top N "a".c as "c" ... from S.T as "a"`;
  - `from (select * from (${res_cN}) as "root") as "root" inner join`;
  - `DATE'...'` literals;
  - join conditions in `planCond`.
- **Why it is a leak:** this is H2 SQL text written outside `EngineStyleH2`. It is the referee and golden format,
  but still one database's syntax. The dialect already exists and is handed to the fold as `engineText`.
- **Where it belongs:** build a `SqlSelect` with a top/limit, `SqlSource.Join`, and a `SqlExpr.PlanParam`
  (RAW) for `${res_cN}`. Render it with the engine-text dialect (`EngineStyleH2.render(SqlQuery)`).
- **Size:** about 120-150 lines.
- **Coupling:** `TestDataGenerationNatives.planText:229-245`, then `planTextResult` (note at
  `JavaEvalLedgerTest:629`); `SqlTextRatchetTest` (15, shared).

### L7. LEAK-SQL: InProtocol engine temp-table select text, 1 hit (referee)

- **Where:** `plan/InProtocol.java:230-236` (`tempSelectSql`).
- **What it does:** hand-builds the engine's `generateTempTableSelectSQLQuery` text:
  `select "t_0".ColumnForStoringInCollection as ColumnForStoringInCollection from <temp> as "t_0"`.
- **Why it is a leak:** SQL text composed in `plan/`. The temp table's name is already per-database (DB2's
  `SESSION.` prefix, from `Databases.inListTempTables`), yet the select is spelled once for every engine-text
  target.
- **Where it belongs:** `SqlSelect(SqlSource.Table(temp), projection Column)` rendered by the plan's engine-text
  dialect (`EngineStyleH2` / `EngineStyleDB2`.render).
- **Size:** about 15 lines.
- **Coupling:** `InProtocol.nodeTexts:157-201`, `PlanEnvelope:26-30`; `SqlTextRatchetTest` REGISTER
  `plan/InProtocol.java` = 1.

### L8. LEAK-SQL: CatalogGrids information_schema catalog SQL, 49 hits

- **Where:** `compiler/spec/CatalogGrids.java`:
  - `NOT_SYSTEM` at 74-75;
  - `fetchSql` at 79-139;
  - `like()` at 204-209.
- **What it does:** for the `fetchDb{Schemas,Tables,Columns,PrimaryKeys}MetaData` natives, the Typer (phase G)
  composes catalog SQL over `information_schema.{schemata,tables,columns,key_column_usage,table_constraints}`. That
  SQL has `upper(...)` identifier columns, a `NOT IN ('information_schema','pg_catalog')` exclusion (DuckDB's
  system schemas) and `LIKE '<pattern>'` with quotes doubled. The Typer stamps it into a
  `TypedRawSqlRelation`.
- **Why it is a leak:** this is SQL for particular databases, composed in the compiler (invariant 1 and the
  dialect rule). The javadoc says it was probed on DuckDB 1.4.4 and H2 2.1.214 only. Other databases differ:
  - SQLite has no `information_schema`;
  - MySQL and MariaDB report the schema as the database;
  - Snowflake and BigQuery are scoped differently and have other system schemas;
  - the `upper()` engine-parity rule is H2's.
- **Where it belongs:** the Typer emits a typed node, `TypedCatalogGrid(kind, patterns)`, whose schema it already
  knows (`gridSchema` and `gridColumns` stay compiler facts). The SQL comes from
  `SqlDialect.catalogGrid(FetchDbGrid kind, String schemaPattern, String tablePattern, String columnPattern)` at the
  lowering or exec seam. An alternative is the connector's `DatabaseMetaData` read.
- **Size:** about 120 lines moved, plus about 40 lines for the new node and plumbing.
- **Coupling:** `Overloads.rawGridOrSelf:200-229`; `SqlTextRatchetTest` REGISTER `compiler/spec/CatalogGrids.java`
  = 10; `JavaEvalLedgerTest:893` (history note); `JdbcSurfaceCensusTest:125` (retired row).

### L9. LEAK-TYPE: CatalogGrids data_type -> java.sql.Types table, 12 hits

- **Where:** `compiler/spec/CatalogGrids.java:184-200` (`BASE_TYPE`, `BASE_TYPE_EXPR`, `TYPE_CODE`).
- **What it does:** a SQL `CASE` maps `information_schema.columns.data_type` spellings (INTEGER, VARCHAR, BIGINT,
  SMALLINT, TINYINT, DOUBLE, FLOAT, REAL, DECIMAL%, DATE, TIMESTAMP, BOOLEAN) to JDBC `java.sql.Types` codes.
  Anything else is 1111.
- **What the table is for:** the `DATA_TYPE`, `TYPE_NAME` and `SQL_TYPE_NAME` columns of the fetchDbColumns grid.
- **Why it is a leak:** these are the type names one database reports. They are DuckDB's spellings. I did not
  verify this, but H2 2.x reports `CHARACTER VARYING`, so its VARCHAR would likely fall to 1111. Each database
  needs its own table.
- **Where it belongs:** `SqlDialect.catalogTypeCodes()`, or inside `catalogGrid` from L8.
- **Size:** about 20 lines.
- **Coupling:** the same as L8.

### L10. LEAK-ID: plan text hardcodes TestDatabaseConnection(type=H2), 4 hits (referee)

- **Where:**
  - `plan/PlanText.java:35`: `single(...)` defaults `connectionName`;
  - `plan/PlanText.java:446`: `scalarRelational`, always H2;
  - `testdatagen/TestDataGenerator.java:1430` and `:1491`: the TDG plan nodes, always H2.
- **What it does:** prints `connection = TestDatabaseConnection(type = "H2")` in plan text when no connection is
  known, and always for the scalar and TDG nodes.
- **Why it is a leak:** it hardcodes which database the plan's connection is, instead of reading the runtime. The
  same literal is repeated at `StatementExecutor.java:770-772` (outside this slice). There is already a seam
  constant, `EngineText.ENGINE_TEST_DATABASE = DatabaseType.H2` (`EngineText.java:26`).
- **Where it belongs:** take the connection spelling from `ExecutionContext.connectionName()`, as the other
  `single` overloads do, or from one helper on the seam, for example
  `EngineText.testConnectionSpelling(ENGINE_TEST_DATABASE)`. A per-database connector could expose
  `planConnectionName()`.
- **Size:** about 10 lines.
- **Coupling:** the callers of `PlanText.single` (`StatementExecutor:809/915/934`, `PlanAllocations:91`,
  `PlanEnvelope:34`); `InProtocol.thresholdFor` tests the printed prefix `TestDatabaseConnection` (see misses).

### L11. LEAK-ID: ContextReading default DatabaseType H2, 2 hits

- **Where:** `compiler/spec/typed/ContextReading.java:801-804` (`databaseType`) and `:843-846`
  (`rawConnectionName`). It is exposed as `ExecutionContext.Reader.databaseType`
  (`compiler/spec/typed/ExecutionContext.java:286-289`).
- **What it does:** when an inline `^...Connection(...)` has no `type`, it is read as `"H2"`.
  `StatementExecutor.planDatabase` and `SqlTextVerdicts:105` then pick the dialect from that value with
  `Databases.named`.
- **Why it is a leak:** a silent default to one database (invariant 4), outside the seam. `planDatabase` already
  handles a null correctly, by falling back to `EngineText.ENGINE_TEST_DATABASE`.
- **Where it belongs:** return null, and let the seam's referee constant decide. Better, refuse an untyped
  connection.
- **Size:** about 6 lines.
- **Coupling:** `ExecutionContext` record field `databaseType`; `StatementExecutor:480/490/1178`;
  `SqlTextVerdicts:105`.

### L12. LEAK-RAW: PlanText scalar item sliced from rendered SQL, 2 hits

- **Where:** `plan/PlanText.java:84-86`.
- **What it does:** it takes the select item for the scalar resultColumns as
  `sql.substring("select ".length(), sql.indexOf(" from "))` from the rendered engine-style SQL text.
- **Why it is a leak:** it reads rendered SQL back, and relies on one dialect's lowercase `select ` and ` from `
  spelling.
- **Where it belongs:** render the projection expression from the IR the caller already passes (`plan`). That
  needs a dialect method, `SqlDialect.renderExpr(SqlExpr)` or `renderSelectItem(SqlSelect.Projection)`.
- **Size:** about 10 lines.
- **Coupling:** `JavaEvalLedgerTest` pins `plan/PlanText.java` = 882; `SqlTextRatchetTest` `plan/PlanText.java` =
  2; `CodeShapeGuardrailTest:295`; `ArchitectureTest:673` (the PlanText rule).

### L13. LEAK-RAW: Typer raw executeInDb split/sniff (RawSql), 3 hits (unsure)

- **Where:** `compiler/spec/Overloads.java:209-213` (`rawGridOrSelf`), calling `sql/RawSql.java` (`splitStatements`
  and `isSingleQuery`).
- **What it does:** to type an `executeInDb` literal, the Typer splits the raw SQL on top-level `;`, using
  ANSI `''` quoting only. It then treats the literal as a relation when the first keyword is in
  `{SELECT, WITH, VALUES, TABLE, SHOW, DESCRIBE}`.
- **Why it is a leak:** the lexical rules differ by database (MySQL backslash escapes, Postgres `$$`, `PRAGMA` on
  SQLite, the `FROM t SELECT` form on DuckDB). The keyword set is a mix of dialects. This is a judgment call:
  the code is neutral-ish, but it has no target.
- **Where it belongs:** `SqlDialect.rawStatements(String)` and `isRowProducing(String)` on the target dialect,
  next to `RawSqlBoundary`. The Typer would have to defer to the target, or keep a neutral "maybe-relation" node.
- **Size:** about 15 lines, plus plumbing the target, because phase G has no target.
- **Coupling:** `sql/RawSql.java` (shared; not this slice); `resolver/RawGridSchema`; `BodyCompiler.containsRawGrid`.

### L14. LEAK-CONN: CreateClass DuckDB in-memory path, 1 hit

- **Where:** `server/CreateClass.java:486-491`.
- **What it does:** when making a class's connection, it branches on the protocol spec `_type == "duckDB"` with no
  `path`, and writes `path: ":memory:"`.
- **Why it is a leak:** this is a branch on which database it is, plus DuckDB's in-memory path convention (SQLite
  shares it, see `exec/Sessions.java:323`), in server code.
- **Where it belongs:** a per-database connector method, for example
  `DatabaseConnector.inMemoryDatasourceSpec()` / `defaultsForHostedModel(spec)`. The protocol writer would then
  ask the connector for the DuckDB type.
- **Size:** about 5 lines.
- **Coupling:** `server/CreateClassTest.java` (asserts `:memory:`); `JavaEvalLedgerTest:1326` (the server
  allowlist names `CreateClass.java`).

## 3. SEAM, DIALECT-SHARED and SINGLE-BACKEND-APP groups

There are none in this slice.

- `plan/InProtocol.java:71,131` calls the seam correctly: `Databases.inListTempTables(dbType)` gives the prefix and
  threshold. The scanner did not hit it.
- `server/ConnectionResolver.java` opens sessions through `exec.Sessions`, the SEAM, which is not in this slice.
  See misses.

### LANG and FP groups (not leaks)

- **LANG** (485 hits). All of these are Legend grammar, protocol or metamodel:
  - connection grammar: 80;
  - protocol reader, emitter and composer names: 187;
  - section and activator registry: 30;
  - the DatabaseType enum declaration: 22;
  - extractFromSemiStructured type vocabulary: 19;
  - ###Relational type grammar, composer and model parse: 10+11+10;
  - Deephaven, MongoDB and Elasticsearch store grammars;
  - FromProtocol and ModelFromProtocol;
  - metamodel datatype spellings in plan text (`PlanText.spell`, `PreciseTypes`, `UpstreamRelationType`,
    InProtocol's `VARCHAR(1024)`): these are legend-engine's `dataTypeToSqlText` / `pureTypeToDataTypeMap`
    vocabulary, the same for every database;
  - `StoreCompiler.declaredType`;
  - Pure FQN constants.
- **FP** (52 hits): English in messages ("with", "select", "merge", "JSON", "NOT", "use"); the Pure `select()`
  function name; the local variable `db2` in `RelationalTypeInference`; and our own TypeInferenceException texts
  ("unknown column", "already exists"), which are not matched against vendor errors.

## 4. Misses: database-specific behaviour the scanner did not flag

1. **`testdatagen/TestDataGenerator.java:515-570` (`milestoningFilter`) and `:693-741` (`renderCondition`).**
   `DATE '...'` literals, comparison operators and `and`/`or`/`is null` join SQL are rendered by hand from
   `RelationalOperation`. They belong to L1.
2. **`testdatagen/TestDataGenerator.java:1575-1610` (`planCond`) and `:1612-1650` (`planMilestone`).**
   Engine-H2 `DATE'...'` and condition text. They belong to L6.
3. **`testdatagen/TestDataGenerator.java:1670-1681` (`q`), `:939-942` (`headerCase`) and `:916-937` (`column`).**
   The identifier rules are H2's (unquoted identifiers fold to UPPER) and DuckDB's (quoted identifiers are
   case-insensitive). `headerCase` encodes "H2 uppercases unquoted result labels". These rules belong on the
   dialect (`quoteIdentifier`, `foldsUnquoted`).
4. **`testdatagen/TestDataGenerator.java:1657-1660` (`qualify`).** The `"default"` schema is spelled raw. This is
   PARK-16 in `ParkedWorkLedgerTest`.
5. **`server/ConnectionResolver.java:189-198`.** A switch over `Sessions.Opening` that is a capability in disguise:
   - `Named` means H2's named in-memory database, and only H2;
   - `Held` means DuckDB or SQLite;
   - the server mints H2's database name (`"c_" + contentKey(...).hex()`), a workspace-naming policy for one
     database that lives outside `Sessions` and outside a connector.

   It belongs on a connector method, for example `DatabaseConnector.openForModel(Hash key)`.
6. **`plan/InProtocol.java:127-131` and `:145`.** `connName.startsWith("TestDatabaseConnection")` gives threshold
   50 and `isTest`. This is a prefix test on printed plan text. It tests the connection kind, not the database,
   so it is an engine rule. It is fragile, though, and couples to L10's literal.
7. **`compiler/element/StoreCompiler.java:104-111`.** It writes String kinds ("VARCHAR", "CHAR", "BINARY",
   "VARBINARY", "DECIMAL", "NUMERIC") into `SqlDdl.ColumnType.Sized` and `.Scaled`. Dialects then dispatch on
   `kind().toUpperCase().contains("BINARY")` (`sql/dialect/BigQuery.java:2697`, `MariaDb.java:3217`). That is
   stringly-typed MIR (invariant 3a), not a database leak. It should be an enum, as `Kind` is.
8. **`compiler/spec/typed/ContextReading.java:663` and `:721`.** `LocalH2DatasourceSpecification.testDataSetupSqls`
   is collected as raw setup SQL. That is H2 text the user wrote. It is LANG here, but it flows to the exec
   raw-SQL boundary (slice A1/E) for every target.
9. **`normalizer/RelOpTranslator.java:264-285` (dynafunction `dayOfWeekNumber`, "Engine H2: SUNDAY=1") and
   `:459-466` (`substring`, "the H2 sub-1-start clamp").** The semantics come from the H2 referee and are emitted
   as Pure, not SQL. I judge this LANG (engine spec, `SEMANTICS_REGISTER`), and list it so the decision is
   visible.
10. **`compiler/spec/Typer.java:1623-1627`.** An `LL_TDG_DEBUG` print keyed on `"DatabaseType"`. It is DIAG only,
    with no behaviour.

Front end checked and clean: there are no `case <DatabaseType> ->` arms, no `instanceof` checks on a dialect, and
no dialect imports in lexer/, parser/, protocol/, model/, normalizer/ or platform/. Of compiler/, only
`CatalogGrids` composes SQL.
