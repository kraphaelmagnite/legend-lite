# E_dialect: the dialect package and the MIR package

Slice: `core/src/main/java/com/legend/sql/dialect/` (dialects, shared data files, passes) and `core/src/main/java/com/legend/sql/` (the MIR).
Input: 3,896 hits in 35 files. Output: `classified/E_dialect.tsv`, one row per input id. The id sets were checked identical.

**Verdict on "each database's SQL knowledge lives in ONE place, its own dialect class": FALSE.** No dialect class tests
another dialect's identity, but every database's facts are spread over 4 to 9 places. Its class holds the code. Four shared data
files (`Lexicon`, `TypeNames`, `Spellings`, `CarrierStrategies.Caps`) hold its rows. On top of that, the base
renderer holds DuckDB's vocabulary and H2's DDL and carrier caps as *defaults*, and `RawSqlBoundary` holds a
`{DUCKDB, POSTGRES}` target switch. The model to copy is already in the package: `CatalogRules` is a record type in a
shared file whose rows (`DuckDb.CATALOG_RULES`, `Postgres.CATALOG_RULES`) live in each dialect.

## 1. Count table

| class | hits | groups |
|---|---:|---:|
| DIALECT-INTERNAL | 3,585 | 64 |
| DIALECT-SHARED | 247 | 53 |
| DIAG | 34 | 6 |
| FP | 17 | 6 |
| LEAK-RAW | 5 | 1 |
| SEAM | 4 | 1 |
| LANG | 2 | 2 |
| LEAK-SQL | 2 | 1 |
| **total** | **3,896** | **134** |

How the bulk rules were set. Each was read at its construct first:

- A per-database class (`DuckDb`, `H2`, `H2Modern`, `Postgres`, `SQLite`, `MariaDb`, `MySql`, `Trino`, `ClickHouse`,
  `Snowflake`, `Databricks`, `BigQuery`, `EngineStyleH2/DB2/Composite`) is DIALECT-INTERNAL, with these exceptions:
  - EngineStyleH2.java:301 borrows `Spellings.DUCKDB`, and MySql.java:30 borrows the `MARIADB` rows. Both are DIALECT-SHARED.
  - H2.java:174 `rawH2IsNative` and Postgres.java:142-143 `rawTarget` are DIALECT-SHARED identity hooks.
  - DuckDb.java:273 `RawSqlAdapt` is DIALECT-SHARED, because it is a raw translation that lives in a shared file.
  - Walls in non-DuckDB classes that say a MIR argument's semantics "are DuckDB's" are DIAG.
- A grep over all 15 classes found no `instanceof <OtherDialect>`, no `getClass()` compare and no product-name `equals`.
  The only cross-class references are subclassing: `H2Modern extends H2`, `MySql extends MariaDb`, and
  `EngineStyleDB2/Composite extends EngineStyleH2`. Another one is H2.java:239 `new H2Modern()`, which picks H2's own version.
- `SqliteRegex` is package-private and used only by `SQLite`. Its SQL is SQLite's (`column1`, `unicode()`,
  `char(10)`), so it is DIALECT-INTERNAL as a SQLite helper.
- In `AnsiSqlRenderer`, standard SQL shapes (SELECT, CAST, IN, IS DISTINCT FROM, INSERT, and so on) are DIALECT-INTERNAL
  with the note "ANSI baseline". DuckDB-only arms are DIALECT-SHARED.
- `jdbcType`/`nullType` (AnsiSqlRenderer.java:1348-1370) are `java.sql.JDBCType` names, so they are FP.

## 2. Leak groups (LEAK-* / TEST-LEAK), each one

### LEAK-SQL | `SqlSource.Join.Kind.sql` keyword text in MIR | 2 hits (E_dialect:13-14)
- **Where:** core/src/main/java/com/legend/sql/SqlSource.java:170-188. Only lines 177 and 180 were flagged; lines
  170-176 and 178 hold the same construct.
- **What:** the MIR join-kind enum carries a public `String sql` per kind: `"JOIN"`, `"LEFT OUTER JOIN"`,
  `"FULL OUTER JOIN"`, `"CROSS JOIN LATERAL"`, `"ASOF LEFT JOIN"` (DuckDB syntax) and `"LEFT JOIN LATERAL"`. The base
  renderer writes the text verbatim at AnsiSqlRenderer.java:389 (`writer.append(j.kind().sql)`). EngineStyleH2.java:1040
  lowercases it.
- **Why a leak:** it is SQL spelling held in the MIR. AGENTS.md invariant 3a says "No MIR record has a `String` field
  encoding a SQL operation" and "No method on a MIR type returns SQL". The lateral and as-of spellings are
  per-database: ClickHouse writes `ARRAY JOIN` (ClickHouse.java:110) and BigQuery has no `JOIN LATERAL`. Today a
  dialect has to intercept `source()` before the base reads `.sql`.
- **Belongs:** an exhaustive `protected String joinKeyword(SqlSource.Join.Kind k)` switch on `AnsiSqlRenderer`, with no
  `default` arm. `ASOF_LEFT` keeps routing to `asOfJoinClause()`. EngineStyleH2 overrides it to lowercase, and
  ClickHouse and BigQuery override it as they need. Delete the `sql` field and its constructor.
- **Size:** about 25 lines (−12 in SqlSource, +12 in AnsiSqlRenderer, 1 line in EngineStyleH2).
- **Coupling:** two readers (AnsiSqlRenderer.java:389, EngineStyleH2.java:1040). No guard pins it. The
  `ArchitectureTest.sqlLayerIsStandalone` rules still hold, because the change stays inside `sql/`. The
  "no SQL-encoding String field" half of invariant 3a has no test, which is why this survived.

### LEAK-RAW | `sql/RawSql` text splitter and query classifier in the MIR package | 5 hits (E_dialect:3-7)
- **Where:** core/src/main/java/com/legend/sql/RawSql.java:16-111. `splitStatements` is at 22-46,
  `QUERY_KEYWORDS = {SELECT, WITH, VALUES, TABLE, SHOW, DESCRIBE}` at 53-54, and `isSingleQuery` at 60-71.
- **What:** it lexes raw SQL text. It splits on top-level `;` and knows only `'...'` strings with `''` doubling, so it
  has no `$$` bodies, no backslash escapes and no `"`/backtick awareness. It then decides whether a statement is a
  row-producing read from its first keyword.
- **Why a leak:** this is SQL-text knowledge outside `sql/dialect/`, in the package meant to be database-neutral
  data. The grammar is that of the text's origin: H2 for the corpus, and the runtime's database for a user's
  `executeInDb`. `SHOW`/`DESCRIBE` are DuckDB/MySQL statements, `TABLE t` is standard/Postgres/DuckDB, and a 12th
  database (T-SQL `GO`, Snowflake `$$` bodies) would need edits here. Phase G depends on it:
  compiler/spec/Overloads.java:211-212 types an `executeInDb` literal as a relation by this keyword test. The
  javadoc at RawSql.java:13-14 points to `com.legend.exec.RawSqlBoundary`, which is stale, because the class is
  in `sql.dialect` now.
- **Belongs:** with the raw boundary, keyed by the text's origin dialect. Two `SqlDialect` methods would hold it:
  `List<String> splitScript(String)` and `boolean isRowQuery(String)`. The defaults would be the current H2-corpus
  behaviour, implemented in `H2` (the corpus origin), and a dialect would override them where its grammar differs.
  Alternatively, move the class to `sql/dialect/` as the H2-origin lexer that `RawSqlBoundary` already
  presupposes.
- **Size:** about 110 lines moved and 6 call sites re-pointed: Overloads.java:211-212, exec/EffectSink.java:27,
  exec/SetupRunner.java:28, StatementExecutor.java:2924, PlanMaker.java:178, and the test
  core/src/test/java/com/legend/sql/RawSqlTest.java.
- **Coupling:** the Typer (phase G) calls it, so a dialect-keyed version has to keep phase G free of a target. Keep
  an origin-dialect parameter and do not consult the runtime. No ledger pins the file.
  `RawSqlLedgerTest.rawSqlSourceConstructionIsQuarantined` pins the `SqlSource.RawSql` constructors, which is a
  different construct.

No TEST-LEAK groups (this slice has no test files).

## 3. Shared files: every per-database fact or identity branch (step 1)

| shared file:lines | database(s) | fact | proposed owner and move |
|---|---|---|---|
| Spellings.java:24 + `build()` 243-330 | DuckDB | function-name row (77 names); javadoc 13-16: "today's row is DUCKDB's vocabulary" | `DuckDb.SPELLINGS` constant passed to `super(...)` |
| Spellings.java:34-45 | H2 | regexp_like/formatdatetime/parsedatetime over the DuckDB row | `H2.SPELLINGS` |
| Spellings.java:54-70 | Postgres | 36 names copied from `DUCKDB.fnNames()` | `Postgres.SPELLINGS` (list its own names; no derivation from DuckDB's row) |
| Spellings.java:77-98 | Databricks | 42 copied + raise_error/startswith/instr/... | `Databricks.SPELLINGS` |
| Spellings.java:105-120 | Trino | 35 copied + levenshtein_distance/reverse | `Trino.SPELLINGS` |
| Spellings.java:126-147 | ClickHouse | 20 copied + editDistanceUTF8/arrayFlatten/mapKeys/... | `ClickHouse.SPELLINGS` |
| Spellings.java:151-172 | BigQuery | upper-case names, EDIT_DISTANCE, REGEXP_CONTAINS, ARRAY_LENGTH | `BigQuery.SPELLINGS` |
| Spellings.java:177-203 | Snowflake | power/greatest_ignore_nulls/editdistance/date_from_parts/... | `Snowflake.SPELLINGS` |
| Spellings.java:208-223 | SQLite | pow/char/instr/json_array_length | `SQLite.SPELLINGS` |
| Spellings.java:227-241 | MariaDB and MySQL | POW/INSTR/JSON_LENGTH (MySql.java:30 reuses it) | `MariaDb.SPELLINGS`, inherited by `MySql` |
| TypeNames.java:28 + `base()` 200-215 | "ANSI": DuckDB's `HUGEINT` and DuckDB/PG `TIMESTAMPTZ` inside the baseline | used only by EngineStyleH2.java:301 | `EngineStyleH2.TYPE_NAMES`; the baseline stays a builder, not a row |
| TypeNames.java:31, 217-222 | DuckDB | bare DOUBLE, JSON, STRUCT=true | `DuckDb.TYPE_NAMES` |
| TypeNames.java:38, 193-198 | H2 | NUMERIC(38) for HUGEINT, JSON | `H2.TYPE_NAMES` |
| TypeNames.java:43, 186-191 | Postgres | NUMERIC(38), JSONB | `Postgres.TYPE_NAMES` |
| TypeNames.java:48-65 | Snowflake | NUMBER(38,0), TIMESTAMP_NTZ/LTZ, VARIANT | `Snowflake.TYPE_NAMES` |
| TypeNames.java:69-86 | BigQuery | INT64/FLOAT64/STRING/BOOL/BIGNUMERIC/DATETIME | `BigQuery.TYPE_NAMES` |
| TypeNames.java:91-104 | Databricks | INT/STRING/DECIMAL(38,0)/VARIANT | `Databricks.TYPE_NAMES` |
| TypeNames.java:109-119 | Trino | TIMESTAMP(6) [WITH TIME ZONE], DECIMAL(38,0) | `Trino.TYPE_NAMES` |
| TypeNames.java:125-142 | ClickHouse | Nullable(Int64)/DateTime64(6,'UTC')/Date32/Int128 | `ClickHouse.TYPE_NAMES` |
| TypeNames.java:146-163 | SQLite | affinities INTEGER/REAL/TEXT | `SQLite.TYPE_NAMES` |
| TypeNames.java:167-184 | MariaDB and MySQL | SIGNED/CHAR/DATETIME(6) | `MariaDb.TYPE_NAMES` |
| Lexicon.java:25-37 | DuckDB | `"` and reserved words (pinned by DuckDbKeywordsTest) | `DuckDb.LEXICON` |
| Lexicon.java:50-68 | SQLite | reserved words; javadoc 8-10 says "SQLite ... its class is gone" (stale) | `SQLite.LEXICON` |
| Lexicon.java:73-92 | MariaDB and MySQL | reserved words | `MariaDb.LEXICON` |
| Lexicon.java:101-124 | H2 | `H2_ENGINE_TEXT` (engine list) and `H2` (+`right`) | `H2.LEXICON`; `EngineStyleH2.ENGINE_TEXT_LEXICON` (DdlSpelling.java:74 reads it) |
| Lexicon.java:126 | engine-style | no reserved words | `EngineStyleH2` |
| Lexicon.java:130-210 | Snowflake, Databricks (backtick), BigQuery (backtick), Trino, ClickHouse, Postgres | quote char and reserved words | the 6 dialect classes |
| CarrierStrategies.java:58-86 | all 10 | `Caps(nativeLists, correlatedExplode, jsonCarrier, nativeFullOuter)` rows | a `CARRIER_CAPS` constant per dialect, or a `protected Caps carrierCaps()` hook on AnsiSqlRenderer that `passes()` reads |
| AnsiSqlRenderer.java:99-100 | H2 | base `passes()` = `new CarrierStrategies(Caps.H2)`; only H2 (and the test's "portable" base) inherits it | base uses a neutral `Caps.PORTABLE` (all false); `H2.passes()` names `Caps.H2` |
| AnsiSqlRenderer.java:575, 637, 642, 668, 674, 686-690, 760, 830, 832, 843, 847, 850, 865, 870, 874-876, 880-886, 888, 889, 892, 1095-1100, 1229, 1234, 1239, 1247-1270 | DuckDB | `list(.. ORDER BY)`, EXCLUDE, rowid, json_object/json_array/json_group_array/to_json(list()), json_merge_patch, `MAP {}`, `xor(x,-1)`, to_base64(BLOB), uuid(), now(), date_trunc returns TIMESTAMP, make_timestamp, `d + to_years(n)`, time_bucket, epoch_ms, `//`, decode(from_base64), INTERVAL frame units, `T[]`, `MAP(K,V)`, `STRUCT(..)`, strftime codes | `DuckDb` overrides; base arms throw `DialectCapability` (as `listCall` does). Note: only 575/687/843/847/865/870/884-888 were flagged |
| AnsiSqlRenderer.java:802-803 | DuckDB/H2 | DIVIDE writes literal `DOUBLE`, bypassing `TypeNames` (Postgres has to override) | `castTypeName(SqlType.Scalar.DOUBLE)` |
| AnsiSqlRenderer.java:1596-1599 + DdlSpelling.java:23-44 | H2 | base `ddlType` = `DdlSpelling.h2Type` ("the H2 base every target shares") | `H2.ddlType`; base throws or uses a neutral table; DuckDb's FLOAT/BIT deltas become a full method |
| DdlSpelling.java:52-89 | engine-H2 / H2 | `engineText`, `engineColumnName` (H2_ENGINE_TEXT reserved words), `h2ExecIdentifier` (called from setup/Ddl.java:280, outside the package) | `EngineStyleH2` (engine text) and `H2` (exec identifier) |
| RawSqlBoundary.java:106-151, 167-198, 272-275 | H2 origin → DuckDB target | `CURRENT_TIMESTAMP()`, `H2VERSION()`→'2.1.214', unaliased `count(*)`→`AS "COUNT(*)"`, name-first `DROP SCHEMA`, idempotent `CREATE SCHEMA`, FLOAT→DOUBLE, BIT→BOOLEAN, CLOB→TEXT | `SqlDialect.fromEngineH2(String)` (default: refuse); `DuckDb` implements today's text; a neutral H2-statement lexer stays shared |
| RawSqlBoundary.java:160, 276-281, 286-291 | DuckDB, Postgres | `enum Target { DUCKDB, POSTGRES }` + two switches (DOUBLE PRECISION, TINYINT→SMALLINT, lower-case folding) | `Postgres.fromEngineH2` adds its type kinds and folding; delete `Target` |
| RawSqlAdapt.java:19-33 | DuckDB | DuckDB-only render pass calling `h2ToDuckDb(sql)` | a `DuckDb` inner pass calling `this.fromEngineH2` |
| SqlDialect.java:67-69 | H2 | `rawH2IsNative()` (identity flag, §5) | retire into `fromEngineH2` (H2: identity) |
| SqlDialect.java:73-75 | DuckDB | `rawTarget()` default `Target.DUCKDB` (identity enum; a fallback for 9 dialects) | retire with `Target` |
| CatalogModel.java:57-67, 160-162 | DuckDB | `copySelectList()` writes DuckDB's `* REPLACE (... AS "c")`; javadoc: "every copy is a DuckDB table" | `DuckDb.copySelectList(List<Conversion>)` or a `SqlDialect` method |
| ConstantKeysAsExpressions.java:51, 61 | (Postgres in text) | shared pass used by 8 dialects, but its walls say "reached Postgres" | DIAG only: name the dialect or say "this dialect" (2 lines) |
| CatalogRules.java (whole) | none | a neutral record; rows live in DuckDb/Postgres | **the pattern to copy** |
| SqliteRegex, ExplosionMarkers, QualifyToSubselect, and the other passes | none (MIR→MIR rewrites) | no SQL text except SqliteRegex, which is SQLite-only | fine |

Size of the row relocation: about 750 lines moved and none added (Spellings 300, TypeNames 200, Lexicon 190, Caps
30, plus 4 constants per dialect). The four record types stay shared. Coupling: `SpellingsTest`
(`Spellings.DUCKDB`, `everySqlFnClassified`), `DuckDbKeywordsTest` (`Lexicon.DUCKDB`), `CarrierDifferentialTest`
(builds `new AnsiSqlRenderer(Lexicon.DUCKDB, TypeNames.DUCKDB, Spellings.DUCKDB)` as the "portable" renderer, so it
depends on the base's DuckDB arms and H2 caps), `DdlSpelling` (`Lexicon.H2_ENGINE_TEXT`), and the constructor of
every dialect.

### Where each database's facts live today

| database | places |
|---|---|
| DuckDB | DuckDb; Spellings; TypeNames; Lexicon; Caps; AnsiSqlRenderer (vocabulary defaults); RawSqlBoundary (target); RawSqlAdapt; SqlDialect.rawTarget default; CatalogModel.copySelectList; MIR SqlTyping and SqlSource.Join.Kind (see §6) |
| H2 | H2; H2Modern; EngineStyleH2; Spellings; TypeNames; Lexicon ×2; Caps (and the base passes default); DdlSpelling (and the base ddlType default); RawSqlBoundary (origin and version pin); SqlDialect.rawH2IsNative; MIR ScanOrder |
| Postgres | Postgres; Spellings; TypeNames; Lexicon; Caps; RawSqlBoundary.Target arm; ConstantKeysAsExpressions messages |
| SQLite | SQLite; SqliteRegex; Spellings; TypeNames; Lexicon; Caps |
| MariaDB/MySQL | MariaDb; MySql; Spellings; TypeNames; Lexicon; Caps (one shared row each, family-owned) |
| Snowflake, Databricks, BigQuery, Trino, ClickHouse | own class; Spellings; TypeNames; Lexicon; Caps |

## 4. Identity checks between dialects (step 2)

- **None.** No `instanceof <Dialect>`, no `getClass()` compare, and no product-name `equals` anywhere in `sql/dialect/`
  or `sql/`. The `getClass().getSimpleName()` uses are all in messages.
- **AnsiSqlRenderer does not branch on `this instanceof`, the lexicon or a product.** It does worse in a quieter way: it
  hard-codes DuckDB (the query half) and H2 (DDL and carrier caps) as defaults, so the identity is decided by *not
  overriding*. H2 (H2.java:404 `return super.call(...)`), H2Modern and EngineStyleH2 (EngineStyleH2.java:1842
  `default -> super.call`) fall through to the DuckDB arms for every `SqlFn` they do not intercept. One case was seen
  happen: `//` is a line comment on H2 (H2.java:262-268). The newer dialects avoid this with an exhaustive portable-arm
  list (e.g. Postgres.java:843-860).
- The family subclassing is clean: `MySql`/`MariaDb` use capability-named hooks such as `jsonIsBinary`,
  `correlatesDerived`, `groupedModesCounted` and `windowQuantilesNative`. `H2.forServer` chooses `H2Modern`.

## 5. Capability flags that are an identity in disguise

| flag | true/overridden for | evidence | replace with |
|---|---|---|---|
| `SqlDialect.rawH2IsNative()` (SqlDialect.java:67) | H2 only (H2.java:174; H2Modern by inheritance) | named after H2; single caller setup/CsvSeed.java:253, pinned by `DialectBoundaryTest.RAW_H2_CALLERS` ("CsvSeed.java", 1) | `fromEngineH2(String)` on the dialect (H2: identity) |
| `SqlDialect.rawTarget()` → `RawSqlBoundary.Target {DUCKDB, POSTGRES}` (SqlDialect.java:73) | Postgres only (Postgres.java:142) | an enum of databases on the dialect interface; its default hands DuckDB's translation to Snowflake, BigQuery, Databricks, Trino, ClickHouse, SQLite, MariaDB and MySQL (an invariant-4 fallback) | the same `fromEngineH2`, refused by default |
| `CarrierStrategies.Caps.correlatedExplode`, `jsonCarrier` (CarrierStrategies.java:56-57) | false only for H2 | **never read**: only `nativeLists` (108, 367, 802) and `nativeFullOuter` (115) are read | delete them, or read them; today they only say "is H2" |

Not identities: `needsStaticPivot` (9 dialects), `supportsQualify` (6), `script`/`scriptAbort` (DuckDB and Postgres),
`failingStatement` (H2 only, but it is H2's vendor-message parser, rightly placed), and `spellsFloatCanon`
(MariaDB family).

## 6. MIR leaks (step 3)

- **LEAK-SQL** `SqlSource.Join.Kind.sql` and **LEAK-RAW** `sql/RawSql.java`, both in §2.
- `SqlSource.RawSql` (SqlSource.java:139) is the designed quarantine node. It is pinned by
  `RawSqlLedgerTest.RAW_SOURCE_CTORS` and `ArchitectureTest.rawSqlSourceIsConstructedOnlyAtTheCharteredSeam`. It holds
  authored text and no database knowledge itself, so it is FP. The same goes for `AliasPrefix.java:46` (re-alias) and
  `SqlRewriter.java:97` (leaf). It has **no origin-dialect field**, though. See the misses.
- Not flagged by the scanner, in the misses below: `SqlTyping` encodes DuckDB 1.5.0's type rules, `ScanOrder` encodes
  H2's nested-loop scan order, and the `SqlFn`/`SqlAgg.Fn` vocabulary is defined by DuckDB's behaviour.

## 7. SEAM / DIALECT-SHARED / SINGLE-BACKEND-APP summary (one line each)

SEAM:
- `SqlDialect per-database hooks` (SqlDialect.java:27, 34, 88, 95): a per-database *object* interface. Its
  `sessionSetup`/`sessionTable`/`scriptAbort`/`failingStatement` defaults are neutral, and each dialect supplies its own.

DIALECT-SHARED (53 groups; database and owner in §3):
- `Spellings.<DB> row` ×10 (DUCKDB, H2, POSTGRES, DATABRICKS, TRINO, CLICKHOUSE, BIGQUERY, SNOWFLAKE, SQLITE,
  MARIADB): function-name rows in a shared file. Seven rows are derived from `DUCKDB.fnNames()` (Spellings.java:67, 88,
  115, 134, 186, 216, 234), so a DuckDB spelling change silently changes seven other databases.
- `TypeNames.<DB> row` ×10 and `TypeNames base() shared row`: CAST type names in a shared file. The baseline carries
  DuckDB's HUGEINT.
- `Lexicon.<DB> row` ×11 (DUCKDB, SQLITE, MARIADB, H2_ENGINE_TEXT, H2, SNOWFLAKE, DATABRICKS, BIGQUERY, TRINO,
  CLICKHOUSE, POSTGRES): quote characters and reserved words in a shared file.
- `CarrierStrategies.Caps.<DB> row` ×10: collection capabilities in a shared pass.
- `AnsiSqlRenderer base passes(): H2 carrier caps`: the base default is `Caps.H2`.
- `AnsiSqlRenderer DuckDB arms in base call()/expr()`: DuckDB vocabulary as base defaults (15 flagged; many more
  unflagged).
- `AnsiSqlRenderer hardcoded type names`: DIVIDE's literal `DOUBLE`.
- `DdlSpelling.h2Type (H2 DDL types as base)`: H2 column types are every target's DDL default.
- `RawSqlBoundary H2->DuckDB/Postgres raw translation`: H2-origin text rewritten in a shared file.
- `RawSqlBoundary.Target identity enum`: `{DUCKDB, POSTGRES}`, its two switches, `SqlDialect.rawTarget`, and the
  Postgres override.
- `RawSqlAdapt/h2ToDuckDb raw translation (shared file)`: a DuckDB-only pass in its own file, plus DuckDb.java:273.
- `rawH2IsNative identity flag`: SqlDialect.java:67 and H2.java:174.
- `CatalogModel.copySelectList DuckDB * REPLACE`: DuckDB's star modifier in a shared file.
- `EngineStyleH2 borrows Spellings.DUCKDB`: the engine-H2 golden printer spells functions from DuckDB's row.
- `MySql borrows MARIADB rows`: a family share. It is fine once the rows live in `MariaDb`.

SINGLE-BACKEND-APP: none in this slice.

## 8. Misses (not flagged by the scanner)

1. **Base renderer DuckDB arms that were not flagged:** AnsiSqlRenderer.java:637 `EXCLUDE`, 642 `rowid`, 668 `json_object(`,
   674 `json_array(`, 686 `json_group_array`, 740/744 `chr(31)` around `error()`, 760 `json_merge_patch`,
   830 `MAP {}`, 832 `xor(x, -1)`, 850 `now()`, 874-876 `d + to_years(n)`, 889 `//`, 892 `decode(from_base64(..))`,
   1095-1100 `INTERVAL n DAYS` frame units ("DuckDB interval spelling"), 1229 `T[]`, 1234 `MAP(K, V)`,
   1239 `STRUCT(a T)`, and 1247-1270 strftime codes in `formatText`.
2. **H2 inherits the base by fall-through.** H2.java:404, H2Modern.java:74 and EngineStyleH2.java:1842 hand every
   unintercepted `SqlFn` to the DuckDB arms. GUID, ENCODE_BASE64, DECODE_BASE64, TIME_BUCKET, MAKE_TIMESTAMP,
   FROM_EPOCH_MS, BIT_NOT, MAP_EMPTY, JSON_MERGE_PATCH and NOW have no H2 arm (grep count 0). So H2 is sent DuckDB
   text instead of hitting a wall. That is a fallback (invariant 4); the past `//` bug (H2.java:262) is one of these.
3. **`rawTarget()` default DUCKDB** (SqlDialect.java:73-75) is a fallback. setup/CsvSeed.java:253-254 translates
   corpus H2 text to DuckDB's flavour for every non-H2, non-Postgres database.
4. **`RawSqlAdapt` treats every `RawSql` as H2 text** (DuckDb.java:273, RawSqlAdapt.java:24). A DuckDB user's
   `executeInDb('select count(*) from t')` gets `AS "COUNT(*)"` added. `SqlSource.RawSql` (SqlSource.java:139)
   records no origin dialect, so the origin is assumed rather than carried. This is H2-referee behaviour on the
   platform path. Unsure how much it matters; flagged as a leak.
5. **`CatalogModel.copySelectList`** (CatalogModel.java:66, `* REPLACE (`) and `sqlIdent` (160-162, `"` quoting).
   Only line 64 (` AS `) was flagged.
6. **`SqlSource.Join.Kind` lines 170-176 and 178** (`"JOIN"`, `"LEFT OUTER JOIN"`, `"ASOF LEFT JOIN"`, ...) are the
   same LEAK-SQL as lines 177 and 180.
7. **MIR typing is DuckDB's:** SqlTyping.java:23, 308-313, 379-385, 785-792, 1428-1431 and 1539-1548. These are
   arithmetic, branch, decimal and nullability promotion rules "probed on DuckDB 1.5.0". Other dialects must conform
   through passes (`RootNumericTypes`, `AggregatesDeliverDouble`). Unsure whether this is the platform's contract or
   a leak: the rules describe "what WE deliver", but a 12th database's emission must reproduce DuckDB's lattice.
8. **MIR ordering is H2's:** ScanOrder.java:18-25, 58-64 and 108-110 build the deterministic key from H2's
   nested-loop join order (H2 as referee). It is used always-on by lowering/CanonicalRenderSql.java:436 on every
   backend, and by `StableScanOrder` on DuckDB behind `-Dlegend.exec.engineScanOrder` (DuckDb.java:274).
9. **MIR vocabulary semantics are DuckDB's:**
   - Non-literal STRFTIME/STRPTIME formats carry DuckDB codes. Walls name this in 8 dialects, e.g. Trino.java:942
     and SQLite.java:1479.
   - TYPEOF returns DuckDB type names (MariaDb.java:2313, Snowflake.java:799).
   - LIST_SLICE's end is DuckDB's (Snowflake.java:777).
   - ADD_INTERVAL and TIME_BUCKET carry DuckDB *function names* as a `StringLit`: `"to_years"`, `"to_weeks"`, ...
     They are produced in lowering/DateShifts.java:67-73 and lowering/Scalars.java:722 and 3045, and
     string-switched in Trino.java:1153, MariaDb.java:2563, SQLite.java:2214, ClickHouse.java:1295, H2's
     `dateUnit` and AnsiSqlRenderer.java:874. This is a MIR string encoding a SQL function name, against invariant
     3a; it should be a `DurationUnit` enum argument.
10. **`SqlAgg.Fn` names double as DuckDB spellings.** AnsiSqlRenderer.java:1084-1086 has `aggregateName(fn) =
    fn.toString()`: QUANTILE_CONT, ARG_MAX, LIST and ANY_VALUE are DuckDB's names (SqlAgg.java:21-27). DuckDb, H2 and
    Postgres do not override it.
11. **Stale documentation that misstates ownership:**
    - Lexicon.java:8-10 and AGENTS.md (§3) say "SQLite is not a class — it is `Lexicon.SQLITE`", but SQLite.java
      (2,899 lines) exists.
    - SqlDialect.java:56-59 is the `rawH2IsNative` javadoc, orphaned above `needsStaticPivot`.
    - RawSql.java:13-14 names `com.legend.exec.RawSqlBoundary`.
12. **`DdlSpelling.h2ExecIdentifier`** (DdlSpelling.java:84-89) is H2's identifier rule. setup/Ddl.java:280 calls it
    for CSV seed DML on every target. The fact is in the shared file and the caller is outside the package.
13. **`TypeNames.ANSI`/`base()`** (TypeNames.java:200-215) spell `HUGEINT` and `TIMESTAMPTZ`, which are DuckDB/PG
    names, in what is labelled the ANSI baseline. Its only user is EngineStyleH2.java:301.
