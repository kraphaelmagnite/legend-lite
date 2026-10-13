# Legend SQL ("AlloySQL"): what upstream built, how to port it, and SQL/dbt as modeling surfaces

**Status: research and brainstorm (2026-10-11), with the user's first decisions recorded in §8. Nothing is
scheduled or announced in `docs/IN_FLIGHT.md`.** It answers the user's question of 2026-10-11: study the Postgres-compatible SQL front end
of legend-engine/legend-pure deeply, port it to legend-lite, and consider (a) SQL as a modeling surface beside
Pure grammar and Python, and (b) a dbt-compatible front end ("Postgres-compatible for queries, dbt-compatible
for modeling, Postgres dialect throughout"). §0 is the answer; §1–§2 are the evidence; §3–§6 are the design;
§7–§8 are the plan and the open decisions.

Reference checkout used: `finos/legend-engine` at `e60ab67a` (2026-10-10). Paths below are relative to that
repository unless they start with `core/`, `docs/`, `python/`, `warehouse/`. "AlloySQL" does not occur in either
upstream repository. Alloy is Legend's internal name, and the thing the user means is legend-engine's
`legend-engine-xts-sql` module family plus its grammar in `legend-engine-xt-relationalStore-postgresSql`.
**legend-pure has no SQL front end at all.** Its only SQL code is the relational store's natives
(`legend-pure-store-relational`).

---

## 0. The answer: yes, and it is one project, not three

1. **The three ideas share one core.** Each is a **Postgres-SQL → Pure Relation translator** plus a shell:
   - *Query front end* = translator + Postgres wire server + a catalog.
   - *dbt front end* = translator (every dbt model body is a SELECT) + a project loader (YAML, a Jinja subset) +
     materializations (writes).
   - *SQL modeling* = translator (view and function bodies) + a DDL reader (CREATE TABLE/TYPE/DOMAIN/VIEW/FUNCTION
     → model elements).

   Build the translator once, well, and the rest are thin layers.

2. **Port the spec and the tests, not the code.** Upstream has three parts:
   - A **CrateDB grammar**. About 80% of it is dead surface that is parsed and then rejected.
   - An **8.2k-line translator written in Pure**. It builds Pure graphs by hand, prints them to text and recompiles
     them. It keeps a TDS branch and a Relation branch for almost every operation.
   - A **Crate-derived wire server**. It starts a Docker `postgres:10.5` to answer catalog queries and calls the
     engine over HTTP.

   legend-lite's tenets rule out running or porting engine Pure: the world map, C6, and the reference-checkout
   tenet. The code's quality argues the same way. What is worth taking:
   - the source conventions (`tb`/`func`/`service`/`csv`, the `tb__pkg` schema mangling);
   - the function table, as a spec;
   - above all, the **test corpora**:
     - 1,703 parity cases with a real-Postgres oracle at the pinned 4.145.0 (2,340 in a later upstream);
     - 230 SQL→Pure transpile pairs;
     - about 1,060 reverse-PCT triples;
     - 83 parser round-trip cases.

3. **Target `protocol.spec.ValueSpecification` (Relation API), entering at `Compiler.query(ctx, vs)`; do not
   target the SQL IR.** Legend sources (functions, services, classes through mappings) are resolved in G and H,
   which the IR is downstream of. The real compiler then types the query **once**. Upstream instead re-derives
   the schema at 22 sites and re-compiles through text.

4. **Postgres is the spec** (the user, 2026-10-11: "stick as close as possible to native Postgres for
   everything"). This covers the wire protocol, the grammar, names, the catalog **and results**:
   - The translator emits Pure whose results equal Postgres's on every backend.
   - The parity corpus's real-Postgres oracle defines "done".
   - Where matching Postgres would need Java to compute values, the gap is a `docs/SEMANTICS_REGISTER.md` row,
     never a silent default (§3.4). Postgres's many date input formats on DuckDB are one such case.

   Upstream does not hold itself to this (§1.3).

5. **SQL is the right modeling surface for the relational layer and the wrong one for the object layer.**
   - It fits stores, relation-valued functions, enums and constrained primitives.
   - It cannot say classes with inheritance, to-many properties, M2M mappings, milestoned navigation or graph
     fetch.

   dbt's YAML fills exactly that gap: columns, contracts, constraints, `semantic_models` (entities, dimensions,
   measures) and the open `meta:` key. So "dbt-shaped project, Postgres-dialect SQL" is coherent, and it maps
   almost one to one onto Legend (§5.1).

6. **The dbt angle has a real gap to fill.**
   - **What dbt v2 offers:** it is a Rust rewrite that "produces and validates a logical plan for every query"
     before running anything.
   - **Where it falls short:** strict type-checking needs `dbt login`, the type checker ("Level 2 SQL
     comprehension") is proprietary, and **Postgres is not a v2 adapter**.
   - **What legend-lite would add:** it would compile dbt-shaped Postgres projects with full typing, column
     lineage, a real semantic layer (classes and associations) and multi-backend rendering. dbt has none of
     these, and they come from legend-lite's own tenets. Sources: §9.

7. **The cost is real, and so is the contention.** Two things are missing in the compiler:
   - writes: `TypedWrite` to a store throws at `core/.../lowering/Lowerer.java:3062`;
   - `intersect`/`except` natives and recursive CTEs.

   There is also contention. pgwire is the paused server program's leg W3, and "Make a class" belongs to the
   DataCube line. A first useful slice is smaller: SQL over HTTP and in `#SQL{}#` islands, with no wire protocol
   (§7, phase 1).

---

## 1. What upstream built (the study)

### 1.1 Module map and sizes

| Part | Where | Size (lines) |
|---|---|---|
| Grammar (ANTLR4, **CrateDB's `SqlBase`**, Crate.io Apache header) | `legend-engine-xts-relationalStore/.../legend-engine-xt-relationalStore-postgresSql/...-grammar/src/main/antlr4/.../SqlBase{Lexer,Parser}.g4` | 497 + 868 |
| Parse tree → AST visitor | `...-grammar/.../from/SqlVisitor.java` | 2,327 (about 700 are `unsupported` stubs) |
| AST → SQL text | `...-grammar/.../to/SQLGrammarComposer.java` | 746 |
| Postgres date-string parser | `...-grammar/.../from/PostgresDateParser.java` | 485 |
| AST metamodel (Pure; Java POJOs generated from it) | `...-postgresSqlModel-pure/.../metamodel.pure` | 626 (81 classes, 15 enums) |
| SQL → Pure translator (**Pure**) | `legend-engine-xts-sql/legend-engine-xt-sql-transformation/legend-engine-xt-sql-pure/.../binding/fromPure/` | 8,159 (`fromPure.pure` alone 6,347) |
| Source providers (`service`, `func`, `relationalStore`) | `legend-engine-xts-sql/.../legend-engine-xt-sql-providers/*` | 1,334 |
| HTTP API (`/sql/v1/...`) | `legend-engine-xts-sql/legend-engine-xt-sql-http-api` | 2,034 |
| `#SQL{...}#` expression island | `legend-engine-xts-sql/legend-engine-xt-sql-expression/*` | 1,148 |
| Postgres wire server (Crate-derived pgwire, Netty) | `legend-engine-xts-sql/legend-engine-xt-sql-postgres-server` | 15,506 main + 5,557 test |
| reverse-PCT corpus | `legend-engine-xts-sql/.../legend-engine-xt-sql-reversePCT` | 15,244 Pure |
| e2e parity corpus | `legend-engine-xts-sql/legend-engine-xt-sql-e2e-tests/src/test/resources/parity-tests/` (4.145.0) | 73 YAML files, **1,703 cases** at the pin (2,340 in a later upstream) |

### 1.2 Grammar and AST

**Surface.** The grammar has 41 statement alternatives: transactions, SET, SHOW, DDL, DML, GRANT, cursors, and
CrateDB-only constructs (BLOB TABLE, SNAPSHOT, KILL, OPTIMIZE, `SET LICENSE`). **Only the query alternative
produces an AST.** Every other alternative throws `Unsupported Operation` (119 call sites, `SqlVisitor.java:2323`).

**Supported in queries:**
- CTEs, including `RECURSIVE` (kept as a flag).
- UNION, INTERSECT and EXCEPT [ALL].
- CROSS, INNER, LEFT, RIGHT, FULL and NATURAL joins, with ON or USING, plus `LATERAL`.
- Scalar, IN, EXISTS and ANY/ALL subqueries.
- Windows: inline, named `WINDOW w AS`, ROWS/RANGE frames, `FILTER (WHERE)`, `WITHIN GROUP`, ordered
  aggregates.
- HAVING; `ORDER BY ... NULLS FIRST/LAST`; LIMIT, OFFSET and FETCH FIRST.
- VALUES.
- Table functions with `name => value` arguments.
- `CAST`, `::` and `type 'lit'`.
- INTERVAL, arrays and slices.
- `$n` and `?` parameters.
- `$$`/`$tag$` strings.
- Postgres regex operators (`~ ~* !~`) and JSON operators (`-> ->> #> @>`...).

**Absent:**
- GROUPING SETS, ROLLUP and CUBE (not even tokens).
- QUALIFY, DISTINCT ON, SIMILAR TO, GROUPS frames, `E''` strings, `TRY_CAST`, `AT TIME ZONE`.
- Multi-statement input.

**Quirks that must not be ported:**
- Unquoted identifiers **keep their case**; Postgres folds them to lowercase (`SqlVisitor.java:902`).
- INTERSECT binds with the same precedence as UNION (`:1010`); in Postgres it binds tighter.
- Every decimal literal becomes a `DoubleLiteral`.
- **Silent drops:**
  - a parenthesised query loses its trailing ORDER BY/LIMIT (`:1009`, `:1039-1052`);
  - interval signs, qualifiers and unknown units are dropped (the regex at `:43` uses a character class instead of
    alternation);
  - `WITH ORDINALITY` and `IGNORE NULLS` are dropped.
- A column named `user` parses as `CURRENT_USER`.
- The AST has **no source positions**, and no record of whether an identifier was quoted.

**The composer is not a fixpoint.** Its 83 round-trip tests compare text case-insensitively, and the lowercased
input is checked too. Bugs found by reading the code:
- `FULL JOIN` renders as `null JOIN`.
- `count(DISTINCT x) FILTER (...)` loses both the DISTINCT and the filter.
- `'` is not re-escaped.
- `NOT (a OR b)` renders as `not a OR b`.
- Arrays print in CrateDB's `[...]` syntax.

### 1.3 The translator (SQL AST → Pure)

**Pipeline**:
1. Java parses the SQL, and the generated `ProtocolToMetamodelTranslator` turns the AST into Pure instances
   (`.../http-api/.../SQLExecutor.java:452`).
2. Java `QueryRealiaser` gives every relation a unique alias.
3. Pure `sqlToPure` (`fromPure.pure:194-256`) builds `SimpleFunctionExpression` graphs by hand
   (`compileUtils.pure:67-111`, `TDSBuilder` `:5894-6328`).
4. In relation mode the result is **printed to Pure text together with every dependent element, wrapped in a
   container function, and recompiled** (`compileUtils.pure:247-283`).
5. Then the plan, then `PlanExecutor`.

**Target shape: two branches.**
- Legacy TDS (`project/restrict/olapGroupBy`, `$row.getInteger('X')`), or the Relation API (`~col`,
  `extend(over(...))`).
- The choice is made by the sources (`rootContext`, `:5540-5546`); mixing the two throws.
- A `$relation` flag is tested at 116 sites. Several features are Relation-only: intersect/except, subqueries,
  LATERAL, VALUES, recursive CTEs, ordered/filtered aggregates.
- **A port targets the Relation API only, which roughly halves the logic.**

Example (`testTranspile.pure`, `testGroupBy`):

```sql
SELECT count(Integer) AS "count" FROM service."/service/service1" GROUP BY String
```

```pure
...->groupBy(~[String], ~[count:{x|$x.Integer}:{y|$y->count()}])->select(~[count])
```

**Sources.** Every FROM item becomes `(type, args)`:

| SQL | Meaning | Provider |
|---|---|---|
| `service('/pattern', coordinates=>'g:a:v', p=>1)` or `service."/pattern"` | a Legend service (single or multi-execution) | Java `LegendServiceSQLSourceProvider` |
| `func('pkg::f__Relation_1_', p=>v)` | a function returning a Relation/TDS | Java `FunctionSQLSourceProvider`, Pure dynamic source |
| `relationalStore(connection=>.., store=>.., schema=>.., table=>..)` | a raw table plus a synthesised runtime | Java |
| `tb('pkg::DB.schema.table')` | `#>{pkg::DB.schema.table}#` | Pure dynamic source |
| `csv('a,b\n1,2')` | an inline `#TDS{}#` | Pure dynamic source |
| `unnest(arr)`, `var('name')` | flatten, or a Pure variable in scope (for `#SQL{}#`) | table-function processors |
| `x` (one part) | a CTE | — |
| `a.b` (two parts) | rewritten to `a('b')` | — |

There is no class, data space or data product source; Legend's object layer is not addressable from Legend SQL.

**Catalog exposure:**
- `sql/v1/schema/getSchema` publishes only Database tables, as `tb` relations.
- The schema name is mangled as `tb__<pkg>`, with `_` → `__` and `::` → `_`.

**Expressions:**
- `processExpression` handles 31 node kinds.
- `functionProcessors()` has about 110 named Postgres functions: 17 aggregate, 29 math, 33 string, 6 date,
  11 window, plus conditional, collection, JSON and `to_char` with its own token engine. Each one picks a Pure
  overload from its argument types. Unknown names fail.
- Types come from a hand-maintained `QueryColumn{name,type,multiplicity}` environment, re-derived by `clone()`
  (`:5832-5865`, "resolve schema is expensive").
- A contextual "expected type" slot coerces literals. In `DateCol > '2023-01-01'`, the string becomes a date
  through the **Java native `parsePostgresDate` at translation time**. Under legend-lite's C2 that is a host
  computation, and a port must not do it (§3.4).
- **NULL semantics:**
  - Comparisons choose `[0..1]`-aware overloads.
  - `NOT IN (subquery)` is spelled out three-valued.
  - `=` keeps SQL three-valued logic only through a **plan-level flag**, `LEGACY_SQL_NULL_UNSAFE_EQUALS`
    (`fromPure.pure:326`). This is upstream conceding that SQL `=` is not Pure `==`.

**Explicitly unsupported (throws):**
- NATURAL JOIN; LEFT, RIGHT or FULL LATERAL.
- Interval frame bounds.
- Multi-column IN.
- Duplicate output names.
- WITH across runtimes.
- WITH RECURSIVE with UNION, or with ORDER BY/LIMIT.
- DDL, DML and SET.

The parity corpus quantifies the gap. Its per-case `expected_rel_status` columns read **1,106 PASS / 1,039
ERROR / 88 BUG / 45 FAIL / 62 SKIP of 2,340** for the Relation path in a later upstream; at the pinned 4.145.0,
**1,026 PASS / 518 ERROR / 61 BUG / 36 FAIL / 62 SKIP of 1,703** (§9 compares legend-lite's).

**The `#SQL{...}#` island** (`xt-sql-expression`) makes SQL a Pure *expression*: `#SQL{select a from tb('p::DB.t')}#->filter(x|$x.a==1)`.
- Lambda variables in scope are visible through `var('name')`.
- legend-lite already parses the island (`core/.../parser/SpecParser.java:3140`) and refuses it at the Typer
  (`core/.../compiler/spec/Typer.java:180`).
- Once a translator exists, that refusal becomes a call to it.

### 1.4 The wire server

- **Inherited from CrateDB:**
  - **Protocol:** Netty, protocol v3, simple and extended query (Parse, Bind, Describe, Execute, Sync, Close,
    portals). No COPY.
  - **Authentication:** cleartext, GSS/Kerberos with credential delegation, or none. No MD5 or SCRAM, and **no
    TLS** (always answers `N`).
  - **Cancel:** BackendKeyData is commented out, so a client's cancel only closes a connection.
  - **Transaction status:** ReadyForQuery always reports `I`.
  - **Errors:** every error is SQLSTATE `XX000`, and a 20-frame Java stack trace goes back to the client in the
    `W` field.
- **Dispatch:**
  - The raw ANTLR tree classifies each statement:
    - BEGIN and SET are no-ops ("TODO: Handle set queries");
    - `SHOW TRANSACTION ISOLATION LEVEL` gets a constant answer;
    - a query with no table, or one naming `information_schema` or `pg_*`, is sent to the metadata database;
    - everything else is a Legend query.
- **Catalog:** the server **starts a Docker `postgres:10.5`** (`.../protocol/sql/SQLManager.java:110`) with a
  90-connection Hikari pool.
  - Per session it creates a `metadata_<n>` schema, copies `pg_class`/`pg_attribute`, and inserts Legend's
    tables and columns.
  - Only the five *qualified* `pg_catalog.pg_*` names are rewritten. Unqualified `pg_class`, `pg_type` and
    `information_schema.*` read the container's real catalog.
- **Legend execution:**
  - **Remote:** the SQL text goes over HTTP to `/api/sql/v1/execution/executeQueryString`, or, for databases
    named `projects|g:a:v|...`, as `#SQL{q}#->from(runtime)` to `pure/v1/execution/execute`. The TDS JSON comes
    back stream-parsed.
  - **Describe:** it does a second full compile.
  - **Parameters:** bind parameters are **unsupported** on Legend queries (`setObject` throws).
- **Types:**
  - Decimal, Number and Numeric are sent as `float8`.
  - Timestamps are carried as epoch milliseconds.
  - Unknown types fall back to `varchar`.
- **Tests:**
  - pgjdbc only. There are no psql `\d`, DBeaver, Tableau or Power BI probe tests, and the README says "More
    details to follow".
  - The e2e suite uses Testcontainers `postgres:16-alpine` as the oracle and ratchets per-case statuses.

**What would be portable as plumbing (about 7–8k lines, Apache-2.0, keep the Crate NOTICE).** It is not used:
D3 decided on a hand-written protocol.
- the decoder, messages, type codecs, `Session`/`Portal`, the per-connection future chain, and `DelayableWriteChannel`.

**What must be replaced:**
- the HTTP bridge, the Docker catalog, the type table, the `projects|` database convention, and Kerberos
  `Subject.doAs`.

### 1.5 The test estates, and how legend-lite may use them

Under the reference-checkout tenet these files are **test input**, never runtime.

| Corpus | Content | Use |
|---|---|---|
| `parity-tests/**/*.yaml` + `schema.yaml` | 1,703 SQL cases at the pin (2,340 later) over a seeded schema (persons, departments, orders…), categorised (functions 882, operators 638, structural 590, format_tokens 80, compositions 59, window_frames 44, predicates 37), oracle = real Postgres | **The conformance target.** Commit the oracle's answers as fixtures generated once against PG16, so CI needs no Docker, and ratchet statuses as upstream does |
| `testTranspile.pure` | 230 SQL → expected Pure pairs (TDS and/or Relation) | Translator unit tests: the Relation half, normalised to legend-lite's canonical Pure text |
| reverse-PCT | about 1,060 `(PCT id, Pure expr, SQL)` triples, self-contained through `csv()`, plus 259 expected errors | Ties the function table to the PCT lane: each SQL string must agree with the PCT expectation of its Pure twin |
| `TestSQLRoundTrip.java` | 83 parse/compose cases | Parser round trip, rebased onto a real AST fixpoint |
| `parsePostgresDate.pure` | 27 date-format cases | Spec for which literal spellings to accept (§3.4) |

---

## 2. What legend-lite has today (the seams)

- **No SQL text parser of any kind.** `docs/LEGEND_SQL_VISION.md`'s "N-in readers" were never built:
  - its writers (dialects) and its two prerequisites (`SqlType`, `ArchitectureTest.sqlLayerIsStandalone`) are done;
  - its parser core and its "SQL → Pure writer" are not.

  The closest relatives:
  - `normalizer/RelOpTranslator` turns `###Relational` dynaFunction trees, which are SQL-ish, into Pure.
  - `parser/GqlParser` (720 lines) is a hand-written parser for a foreign grammar.
  - `sql/dialect/RawSqlBoundary` is a regex H2→backend shim. Its own javadoc names the parser leg as its
    replacement.
- **No pgwire.** The paused `docs/SERVER_PROGRAM_2026_09_26.md` plans it as leg W3 (decision 5, pgwire before
  Flight SQL), but for the *warehouse*, a DuckDB SQL passthrough, not for Legend SQL.
- **Entry points a front end needs, which exist:**
  - **Queries:** `Compiler.query(ModelContext, protocol.spec.ValueSpecification)` (`core/.../Compiler.java:559`),
    "a query that arrives ALREADY PARSED… nothing is re-spelled as text". `TypedQuery.plan`/`.resultType` then
    give Describe its columns without a second compile.
  - **Models:** `Protocol` records → `model/FromProtocol` → `ParsedModel` → `Compiler.buildModel`
    (`Compiler.java:221`). `FromProtocol` says "everything legend-lite compiles comes through here".
  - `protocol.spec` records (`AppliedFunction`, `LambdaFunction`, `ColSpec`, `AppliedFunction.tableReference`
    for `#>{}#`) are the construction surface. `pure-protocol/src/relation.ts` is a working Relation builder
    that is byte-identical to the parser's output, and the best model for a SQL→Relation emitter.
- **Relation natives** cover what a SELECT needs: filter, extend, groupBy, aggregate, join, asOfJoin, lateral,
  concatenate, distinct, select, rename, sort, limit/drop/slice, over and frames, the ranking/value windows,
  `exists`/`in`, quantified comparisons. **Missing:** intersect, except, unpivot, recursion.
- **Writes are absent:**
  - `Lowerer.java:3062` "TypedWrite to a store destination is not yet implemented";
  - `SqlDdl` has only Create/Drop Table/Schema;
  - `SqlDml` has no MERGE or UPDATE.

  `docs/BI_AND_ETL_PLAN_2026_09_29.md` F1 and Track C (C1 materialize, C2 pipelines of Pure functions, C4
  incremental, C5 compiled data-quality checks) plan exactly what dbt materializations need.
- **Modeling:**
  - **Parse-only today:** `PersistenceDefinition` (nothing executes it), `MeasureDefinition` (units, not
    metrics), `DataDefinition`, `PrimitiveExtensionDefinition`.
  - **Working:** the relation class mapping `ClassMapping.RelationFunction` (`~func`), which is what "a SQL view
    becomes a class" lowers to.
  - **Planned:** `docs/DATACUBE_MODEL_DESIGN_2026_10_10.md` plans "Make a class" (upstream's
    `promoteRelationToModel`) and Python's `grid.make_class`, `page.relate`.
- **Python** (`python/legend_lite`) works on Pure text and protocol JSON through the native library. **There is
  no Pythonic class/mapping/store API yet**, and PMCD JSON is not compiled directly: `protocol/ModelReader` feeds
  only the composer, and `PureV1Api` refuses non-text contexts.
- **Overlay grammars are opaque** (`parser/OverlayElementSink`, pinned by
  `ArchitectureTest.opaqueCarrierIsLockedToTheOverlaySeam`). A SQL modeling section that should *compile* must
  therefore be built in, or must emit `Protocol` records outside the grammar SPI.

---

## 3. The query front end: port design

```
Postgres SQL text ─┐
#SQL{...}# island ─┼─▶ sql lexer + parser ─▶ syntax AST ─▶ translator ─▶ protocol.spec.ValueSpecification
pgwire Q/P         ┘    (hand-written)       (records,       (scopes,      (Relation API calls)
/api/sql/v1/*                                 spans)          functions)          │
                                                                                  ▼
                                    Compiler.query ─▶ G ─▶ G½ ─▶ H ─▶ I ─▶ J ─▶ K   (unchanged)
```

### 3.1 Parser and AST

- **Hand-written** (lexer plus Pratt/recursive descent), like `GqlParser` and the Pure lexer. Core compiles to
  TeaVM WebAssembly and GraalVM native-image, and an ANTLR runtime, Calcite or JSqlParser is a poor fit for both.
- **Grammar:**
  - the **Postgres** query subset, with Postgres precedence (INTERSECT tighter than UNION);
  - session statements (SET, SHOW, BEGIN/COMMIT/ROLLBACK, DISCARD) as typed nodes for the wire layer;
  - a DDL subset only when §4 needs it;
  - spec: the Postgres manual, not Crate's grammar.
- **AST:** about 60 immutable records with **source spans** and a **quoted-identifier flag**. Errors carry
  positions so the LSP can underline SQL.
- **Placement (D2: a new package, not `com.legend.sql`; settled by the architecture tests in step 1):
  `com.legend.protocol.sql`**, the reader and the translator together. Three rules decided it:
  - the compiler layer never touches `com.legend.parser` (`ParserBoundaryArchTest`, no exceptions), so the Typer
    cannot call a translator in the parser;
  - only the parser and the protocol packages build calls before name resolution
    (`ArchitectureTest.callsAreBuiltOnlyByTheBuilder`, `protocolNodesAreConstructedOnlyByTheParserAndTheNormalizer`);
  - `com.legend.protocol` is the bottom layer and depends only on the JDK, `values` and JSON
    (`ArchitectureTest.protocolIsTheBottomLayer`), so the protocol package cannot depend on a separate `pg` library.

  So the reader (lexer, parser, AST) and the translator sit in the protocol layer, where every other pre-resolution
  front end already is, and depend on nothing but the JDK. The compiler's one seam is `compiler/spec/SqlIslands`.
  The future wire server reads SQL through the same package.
  Later, `docs/LEGEND_SQL_VISION.md`'s "standalone N-in" reader can add a syntax-AST → IR step for raw-SQL
  transpiling (`RawSqlBoundary`'s replacement).
- **Printer:** a true fixpoint, checked as `parse(print(ast)) == ast` over a property corpus and the 83 upstream
  cases.

### 3.2 Translator (SQL AST → Relation `ValueSpecification`)

- **Relation API only. No TDS branch.**
- **Scopes:**
  - each FROM item contributes a column environment;
  - correlated subqueries see outer scopes;
  - CTEs become `let` bindings.

  The environment of a source comes from **the compiler**: the relation type of the source sub-expression,
  computed by the same routine that `compilation/lambdaRelationType` serves. The translator does not keep a
  parallel type system; the final query is typed once by G (invariant 1).
- **Clause mapping:**

  | SQL clause | Relation API |
  |---|---|
  | FROM / JOIN | `join(..., JoinKind, {l,r|...})` |
  | WHERE | `filter` |
  | SELECT expressions | `extend` + `select`/`rename` |
  | GROUP BY / HAVING | `groupBy` + `filter` |
  | window | `extend(over(...))` |
  | ORDER BY | `sort` |
  | LIMIT / OFFSET | `limit` / `drop` / `slice` |
  | DISTINCT | `distinct` |
  | UNION ALL | `concatenate` |
  | UNION | `concatenate->distinct` |
  | EXISTS / IN | `exists` / `in` |

  `intersect` and `except` need new natives (or a desugaring into `exists`, registered).
- **Function table:**
  - **data, not code:** Postgres name → Pure native, plus the argument and type rule that chooses the overload;
  - **spec:** upstream's about 110 processors;
  - **verification:** every entry's Pure side against the registry's signatures, in the "spec by VERIFICATION"
    spirit of the reference-checkout tenet;
  - an unknown function is a positioned compile error.
- **Expected outputs:**
  - Upstream's join column renaming (`col_<alias>`), HAVING matched by expression text and `_1` suffixes are
    artifacts of its typing. The port drops them.
  - The emitted Relation text should read like hand-written Pure. This is the "adoption weapon" of
    `LEGEND_SQL_VISION.md`: SQL in, idiomatic Pure out.

### 3.3 Sources (what a table name means)

Upstream's conventions are kept so that existing queries run: `tb`, `func`, `service`, `csv`, `var`,
`relationalStore`, CTEs, and `a.b` → `a('b')`. The `tb__pkg` mangling is accepted on input. **The proposal
adds** a *catalog-native* naming, so that BI tools browsing `information_schema` see something sensible:

| Postgres | Legend |
|---|---|
| database (startup `database=`) | a project or **data space**: its default mapping and runtime supply execution context |
| schema | a package, spelled verbatim as a quoted identifier (`"my::app"`; `::` is legal inside quotes, so there is no mangling). The session's `search_path` (native) resolves unqualified names, and it defaults to the data space's package |
| table | a zero-parameter Relation function, a Database table, a service, or a **class** (§3.6) |
| table function `f(...)` | a parameterised function, a service with parameters, or a milestoned class |
| foreign keys in `pg_constraint` | **associations**, so BI tools auto-join along the semantic model |

Classes as tables is the feature upstream lacks, and it is where Legend beats a raw warehouse: the SQL user
queries the *logical* model. §3.6 is the proposed projection.

### 3.4 Semantics: Postgres is the spec

**Postgres is the spec (decided 2026-10-11).**
- The translator emits Pure whose results equal Postgres's, whichever backend runs it.
- Where Pure has no native for a Postgres behaviour, the fix is a new native (one SQL lowering rule each), never
  Java computing a value.
- Until that native exists, the gap is a register row.

| Topic | Postgres (the spec) | Upstream | Plan |
|---|---|---|---|
| Identifiers | unquoted ones fold to lowercase; quoted ones are exact; names over 63 bytes are truncated | case preserved, exact match | **Recommended (D1, below): Postgres's rule first, then a case-insensitive match.** A quoted name matches exactly. An unquoted one matches the name equal to its folding when there is one (Postgres's rule), else the ONE name equal to it ignoring case; two such names are an error asking for quotes. Every query Postgres accepts resolves exactly as in Postgres, and `select firstName` finds the model's `firstName` unquoted. Output names keep the case they are written in. A quoted name in the wrong case gets Postgres's hint ("perhaps you meant \"firstName\""). Implemented in step 1 (register S43) |
| Output column names | an alias as written (folded); a column reference keeps its name; a function call is named after the function (`count`); anything else is `?column?`; **duplicates are allowed** | derived from the expression text; duplicates throw | **Postgres rules.** A Relation needs unique column names, so duplicates get internal names while the wire's RowDescription carries the Postgres names. A reference to an ambiguous name raises Postgres's `column reference "x" is ambiguous` |
| `=` with NULL | three-valued | plan flag `LEGACY_SQL_NULL_UNSAFE_EQUALS` | Postgres three-valued logic, explicit in the translation |
| Numeric literals | exact `numeric` | `DoubleLiteral` | exact Decimal, sent as OID `numeric` |
| `'2023-01-01'` against a date column | an implicit cast, with Postgres's many input formats | parsed in Java (`parsePostgresDate`) | **The database executes the cast** (`docs/TENET_CHARTER.md` C2.1/C2.2; register S2), and `now`/`today` become the current-date natives. Non-Postgres backends do not accept every Postgres input format (`'January 8, 1999'`), so the translator accepts the formats all backends agree on (ISO 8601) and refuses the rest with a positioned error. **A register row: the one place where "as close as possible" stops**, because Java does not compute |
| INTERSECT precedence | tighter than UNION | equal | Postgres |
| NULL order | NULLS LAST for ASC, NULLS FIRST for DESC | — | Postgres, spelled out per dialect |
| Integer `/` | truncates toward zero | — | Postgres. DuckDB's `/` returns a double, so the translation must say so |
| Unknown result type over the wire | n/a | falls back to `varchar` | compile error |

### 3.5 HTTP, island, wire

- **HTTP.** Upstream's `/api/sql/v1/execution/{execute,lambda,plan,schema,parseToMetamodel}`,
  `/sql/v1/schema/getSchema` and `/sql/v1/grammar/*`, with upstream's shapes, following the ruling "lite serves
  upstream's APIs and nothing of its own" (`docs/UPSTREAM_ENDPOINTS_DESIGN_2026_09_27.md`). `lambda` returning the
  generated Pure is the cheapest demo of the whole thing.
- **Island.** The `Typer` arm for `SqlIsland` calls the translator, so `#SQL{}#` composes with Pure.
- **pgwire: hand-written, no dependencies (D3, decided).**
  - **Transport:** plain `java.nio` sockets with one virtual thread per connection. The repository's Java 21
    language level already allows this, and the HTTP server likewise uses only the JDK's `com.sun.net.httpserver`.
  - **TLS:** the JDK's `SSLEngine`. **SCRAM-SHA-256:** `javax.crypto` (HMAC-SHA-256, PBKDF2).
  - **Spec:** protocol 3.0, written from the Postgres manual's "Frontend/Backend Protocol" chapter, not from
    Crate's code.
  - **Scope:** one module serves the Legend handler (translate). When the server program resumes, it also serves
    the warehouse's W3 (DuckDB passthrough).

  Requirements:
  - real BackendKeyData and cancel;
  - transaction status;
  - TLS and SCRAM;
  - honoured result format codes;
  - real SQLSTATEs and no stack traces;
  - a bounded executor;
  - `numeric` for Decimal.

  The **catalog** is a set of `pg_catalog`/`information_schema` views generated from the `ModelContext` and
  **executed by DuckDB**, never a Docker Postgres. The model's metadata is seeded as rows and the catalog is a set
  of views over them. That is the shape of `docs/WORLD_MAP.md` §3's one narrow exception (queries over the
  platform's own system tables), and it keeps "the DATABASE executes". SET and SHOW keep real session state; a parameter
  that is not honoured is an error or a named register row, not a no-op.

  Probe suites must cover psql `\d`, pgjdbc metadata, DBeaver, Tableau, Power BI/ODBC and psycopg. Upstream tests
  only pgjdbc.
- **libpg_query, Postgres's own parser as a C library: a test oracle, not a runtime dependency.**

  **What it is.** pganalyze extracts the real Postgres grammar (`gram.y`, `scan.l`) into a library. It is
  BSD-3-Clause, tracks Postgres 18, and gives JSON parse trees with source locations, a scanner and
  fingerprinting. It builds on Windows.

  **Why not at runtime:**
  - Core runs on the JVM, in the browser through TeaVM, and as a GraalVM native image. A C library reaches only
    the JVM, through JNI or FFM, and needs a build per platform.
  - Its output is Postgres's raw parse tree, hundreds of node types, which would still need translating into our
    AST. It would save the grammar, the easy part, and not the translator.
  - The DuckDB C library fits the warehouse because the warehouse *is* DuckDB. Core is not Postgres.

  **Why as a test oracle**, in a test-only Bazel target like the reference lane (`tools/reference`):
  1. **Acceptance:** every corpus statement is accepted or rejected by both parsers alike.
  2. **Printer fidelity:** `pgquery(print(parse(sql))) == pgquery(sql)` with locations stripped. Postgres's own
     parser certifies that our printer prints the same query.
  3. **Fuzzing.**

  The *semantic* oracle is a real Postgres, run once to generate the parity fixtures (§1.5).

### 3.6 Classes as tables: the relational projection of the model

**The rule:** a table per class the data space can query, and *identity* decides how references between classes
appear. This is the standard object-to-relational projection (an ORM's entities, embeddables and element
collections). It is also exactly what dbt's `semantic_models` need, entities with primary and foreign keys, so
the same rule exports a Legend model as dbt semantic models and imports them back (§5).

1. **Identity comes from the model.** It is the class's `<<equality.Key>>` properties, which legend-lite already
   resolves (D91; `compiler/element/ClassCompiler.java:59`, `lowering/InstanceEquality.java`).
   - A class with keys is an **entity**; a class without keys is a **value**.
2. **Tables.** Each class with a root class mapping in the data space's mapping is one table: schema = its
   package, name = its class name. Postgres allows tables without a primary key, so a mapped value gets a keyless
   table.
3. **Columns:**
   - single-valued primitive and enum properties, inherited ones included;
   - multi-valued primitive properties as Postgres arrays (`text[]`, `int8[]`);
   - qualified properties without parameters as computed columns;
   - the key properties declared `PRIMARY KEY` in `pg_constraint`;
   - enumerations as Postgres `ENUM` types in `pg_type`.
4. **A to-one property to an entity** becomes a foreign-key column per key property of the target, named by its
   path (`"firm.id"`) and declared in `pg_constraint`. So `JOIN "Firm" f ON f.id = p."firm.id"` works, and BI
   tools draw the join themselves.
   - The column's values are computed through Legend's own property/association mapping. The key join is
     therefore correct even when the mapping's join is not key equality.
5. **A to-one property to a value** has the value's columns flattened into the owner as path columns
   (`"address.city"`), recursively.
   - A cycle among values cannot be flattened. It is a compile error naming the cycle, never a silent cut.
6. **A to-many property:**
   - **To an entity whose inverse end is to-one:** no column. The other table carries the FK (the ordinary
     one-to-many).
   - **A many-to-many association between entities:** a bridge table named after the association
     (`"Person_Firm"`), with both FKs.
   - **To values** (`addresses: Address[*]`): a child table `"Person.addresses"` with the owner's key and the
     value's flattened columns.
   - **From a keyless owner:** nothing can link the rows, so it is not exposed (a register row).
7. **Inheritance.** Every mapped class is its own table, with its inherited columns. A superclass table also
   returns its subclasses' rows, as Postgres table inheritance does without `ONLY` and as Legend's `getAll()`
   does.
8. **Milestoned classes** become table functions taking their temporal parameters:
   `"Person"(business_date => DATE '2024-01-01')`. Named arguments are native Postgres syntax.

**Why not embed everything:**
- Cycles (Person → Firm → employees → Person).
- Duplicated data, and very wide tables.
- BI tools model star schemas through keys.

**Why not foreign keys everywhere:**
- Values have no key.
- A synthesised surrogate key (a row number, a hash) is not stable across queries, so a join between two
  queries' results would silently mismatch.

**Alternative for values: Postgres composite types.** That means `CREATE TYPE "Address" AS (...)`, a column
`address "Address"` read as `(address).city`, and arrays of composites for to-many.
- **For:** structurally more "native Postgres".
- **Against:** most BI tools and ODBC drivers cannot read composite or array columns.
- **Proposal:** flattened path columns by default, and the composite form later as a per-connection option.

**Open sub-decisions:**
- **Identity source:** `<<equality.Key>>` only (proposed: one source of truth, in the model), or also a mapping's
  primary key when a class declares none. "Make a class" can propose keys from a table's primary key, so models
  get keys without a second rule.
- **Qualified properties with parameters:** table functions, or not exposed.

---

## 4. SQL as a modeling surface (brainstorm)

Postgres DDL already names most Legend relational concepts:

| Postgres DDL | Legend element | legend-lite today |
|---|---|---|
| `CREATE SCHEMA s` | package (and Database schema) | yes |
| `CREATE TABLE t (... NOT NULL, PRIMARY KEY, REFERENCES)` | `Database` table + `Join` per FK | yes (`DatabaseDefinition`; `CatalogModel` already builds Databases from catalog rows) |
| `CREATE TYPE e AS ENUM (...)` | `Enumeration` | yes |
| `CREATE DOMAIN d AS text CHECK (...)` | primitive extension with constraints | parse-only (`PrimitiveExtensionDefinition`) |
| `CREATE VIEW v AS SELECT ...` | `function v(): Relation<(...)>[1] { <translated> }` | yes, given §3 |
| `CREATE FUNCTION f(p int) RETURNS TABLE(...) LANGUAGE sql AS $$...$$` | parameterised Relation function (and a service) | yes, given §3 |
| `CREATE MATERIALIZED VIEW` | function + materialization (`Persistence`) | parse-only / F1 |
| `COMMENT ON ...` | `doc` tagged values | yes |
| `GRANT` | entitlements | out of scope (warehouse grants) |

**Where SQL runs out: the object layer.** Postgres can say none of the following without abuse:
- class inheritance (`INHERITS` is not it);
- to-many properties (arrays of composites are not it);
- qualified properties with parameters;
- cross-object constraints;
- M2M mappings; union, merge or operation mappings;
- milestoned navigation;
- graph fetch.

A SQL surface for classes needs a side channel, and dbt's YAML is the one people already write (§5).

**How it enters the compiler.** A reader turns DDL into `Protocol` records → `FromProtocol` → `ParsedModel`. In
practice that is `.sql` files in a project, or a built-in `###SQL` section (an overlay grammar would be opaque).

**Round trip.** Every model prints as Pure through the existing emitter. A SQL-authored model can therefore be
"ejected" to Pure, and a Pure model's stores and relation functions can be printed back as DDL. That makes SQL a
*view* of the one model, not a second model.

**Python is the same shape.** A Pythonic API (`Class`, `Database`, `relate`) builds the same records, through PMCD
JSON and the native library. All three non-Pure surfaces share one prerequisite: compiling `Protocol` records
that did not come from Pure text. In Java this is already possible (`Compiler.buildModel`). Over JSON it is not
(`ModelReader` → `FromProtocol` is not wired into compile).

---

## 5. A dbt-compatible front end (brainstorm)

### 5.1 dbt ↔ Legend

| dbt | Legend | legend-lite today |
|---|---|---|
| `source()` (`sources.yml`: database/schema/tables/columns) | `Database` tables | yes |
| model `.sql` (a SELECT) | Relation function, body translated by §3 | needs §3 |
| `ref('m')` | a call to function `m()` | yes (G½ inlines it) |
| `materialized: view` / `ephemeral` | the function itself, inlined (Legend's default) | yes |
| `materialized: table` | `write` → CTAS | **missing** (F1) |
| `materialized: incremental` (append, merge, delete+insert; `unique_key`) | `Persistence`: AppendOnly / NontemporalDelta | parse-only |
| `snapshot` (SCD2, `dbt_valid_from/to`) | Persistence UnitemporalDelta + **processing milestoning** | parse-only / milestoning on tables yes |
| `columns:` + `data_type` + `contract: {enforced: true}` | the function's declared `Relation<(...)>` return type | yes, and **always** enforced |
| `constraints` (not_null, unique, PK, FK, check) | multiplicity `[1]`, Database PK/Join, class constraints | partial |
| data tests (unique, not_null, accepted_values, relationships, singular) | constraints/assertions compiled to SQL (Track C5) | planned |
| unit tests (given/expect) | function tests with test data | deferred (`docs/DEFERRED_TEST_EXECUTION.md`) |
| seeds (CSV) | `Data` elements / `csv()` | parse-level |
| `semantic_models` (entities, dimensions, measures) | **Class + Association + `~func` mapping** ("Make a class") | mapping yes; promotion planned |
| metrics | qualified properties / DataCube measures | partial |
| exposures | services, data spaces, DataCube pages | yes |
| macros (Jinja) | Pure functions, typed rather than text-templated | conceptual mismatch (§5.3) |
| packages (`dbt_utils`) | Pure libraries | per-macro mapping |
| Mesh cross-project `ref`, groups, `access` | project dependencies (depot), package visibility | partial |
| `profiles.yml` targets | `Runtime` + `Connection` | yes |
| `vars` | function/service parameters | yes |

The fit is close because the two tools model the same thing: dbt *adds* a semantic layer after the SQL, while
Legend *starts* from one (`docs/BI_AND_ETL_PLAN_2026_09_29.md:25`, "for us the model is the semantic layer").

### 5.2 Compatibility levels (each one is useful alone)

1. **L1, read: ingest `manifest.json`.**
   - Real dbt (`dbt compile`, v1 or v2: both write manifest **v12**) does the Jinja and dependency work.
   - legend-lite reads `nodes[*].compiled_code`, `depends_on`, `columns`, `contract`, `semantic_models`, and
     produces a Legend model.
   - **Gives:** typed compilation of every model, column lineage, a semantic layer, DataCube on top, and
     execution on any legend-lite backend.
   - **Needs:** no Jinja and no YAML parser in legend-lite.
   - **Limit:** compiled SQL is in the project adapter's dialect, so this means dbt-postgres projects (and
     DuckDB-dialect ones as a near neighbour).
2. **L2, author: read dbt project files natively.** `dbt_project.yml`, `models/**/*.sql`, `*.yml`.
   - **A Jinja *subset*:** `ref`, `source`, `config`, `var`, `this`, `is_incremental()`, plus a mapped list of
     common `dbt_utils` macros.
   - **Anything else** is a positioned compile error (invariant 4).
   - **Mixing:** projects may hold `.pure` and `.sql` side by side, and the LSP underlines SQL.
   - **Needs:** a YAML reader.
3. **L3, run: materializations.**
   - A `build` walks the DAG and emits CTAS / INSERT…SELECT / MERGE per dialect, and runs tests as SQL assertion
     queries.
   - **Needs:** F1 writes, plus `Persistence` given meaning.
4. **L4, be a dbt target.** Real dbt with dbt-postgres runs against legend-lite's pgwire:
   - `CREATE VIEW` registers a function in a workspace;
   - `CREATE TABLE AS` materializes it;
   - dbt-postgres's `pg_namespace`/`information_schema` introspection and rename-swap dance must also work.

   This is the most drop-in level and has the longest compatibility tail. Defer it.

The reverse direction is cheap once §4's printer exists: **emit a dbt project from a Legend model** (stores →
sources, relation functions → models, classes → semantic models). It is an adoption path for Legend users who
deploy with dbt.

### 5.3 The honest frictions

- **Jinja is text templating; Legend is typed functions.** Macros that generate SQL text can only be supported by
  *running* them (L1, through dbt itself) or by mapping known ones. Arbitrary macros are out at L2. Most model
  files use only `ref`/`source`/`config`, so the subset covers the common case. Measure it on real projects
  before promising anything.
- **Hooks** (`pre_hook`/`post_hook`), `run-operation` and `grants` execute arbitrary SQL. Refuse them, or pass
  them through as named residue, never silently.
- **Dialect lock.** dbt models are written in one warehouse's SQL, so "Postgres dialect" must be the *declared*
  input. The win is that legend-lite then renders it for DuckDB, Postgres, Snowflake or H2: write once, run on
  several backends, which dbt cannot do.
- **dbt's semantics are the warehouse's.** A dbt user expects Postgres results. §3.4's rows apply in full, and
  the parity corpus is the evidence.

---

## 6. One model, many surfaces

```
 Pure text    ─(parser)──────────┐
 Python       ─(PMCD JSON)───────┤
 SQL DDL      ─(sql reader)──────┼─▶ Protocol records ─▶ FromProtocol ─▶ ParsedModel ─▶ D–F (the model)
 dbt project  ─(manifest/loader)─┘

 Pure query   ─(SpecParser)──────┐
 Postgres SQL ─(translator)──────┼─▶ protocol.spec.ValueSpecification ─▶ Compiler.query ─▶ G … K
 #SQL{}#      ─(translator)──────┤
 DataCube / pure-protocol JSON ──┘
```

**Invariant for every surface:** it produces the same records the Pure parser produces, and **prints back as
Pure**. Pure text stays the canonical, reviewable form. No surface carries semantics the others cannot express;
anything SQL or dbt cannot say is written in Pure, or in Python once that exists.

---

## 7. A phased plan (if the user starts it)

Sizes are rough Java line counts, for comparison with upstream (§1.1).

| Phase | Content | Gate | Size |
|---|---|---|---|
| **0. Decide** | §8's open decisions; register rows (§3.4); an `IN_FLIGHT` slot (the DataCube line owns "Make a class"; the database-owner line owns `sql/dialect`/`lowering`) | — | doc |
| **1. SQL over HTTP and islands** | parser + AST + printer; translator for `tb`/`func`/`csv`/CTE sources; function table; `/api/sql/v1/execution/*` + `getSchema`; the `#SQL{}#` Typer arm | 83 round-trip cases; the **libpg_query lane** (acceptance and printer fidelity); the transpile pairs (Relation half); **parity corpus ratchet** (DuckDB execution against committed Postgres oracle answers) | 8–11k |
| **2. Wire** | the hand-written pgwire module (later shared with the warehouse's W3); a DuckDB-executed catalog generated from the model; session statements | probe suites per client (psql, pgjdbc, DBeaver, Tableau, ODBC, psycopg) | 6–9k |
| **3. Legend sources** | `service`, data space as database, classes as tables (§3.6), associations as FKs, bind parameters | parity corpus re-run through classes | 2–4k |
| **4. SQL/dbt modeling, read** | DDL reader → `Protocol`; dbt **L1** (manifest ingest); YAML contracts → declared return types; `semantic_models` → "Make a class" (shared with DataCube) | sample dbt projects compile; Pure print-back round trip | 3–5k |
| **5. dbt author and run** | **L2** (YAML reader, Jinja subset, LSP); **L3** (needs BI-plan F1 writes, `Persistence`, tests as assertions) | `jaffle_shop`-class projects build on DuckDB and Postgres | 5–8k + F1 |
| **6. Optional** | **L4** (dbt-postgres against pgwire); Legend → dbt export | dbt-postgres's own integration tests | — |

**First slice:** phase 1 alone. It ships Postgres SQL against Legend models over HTTP and inside Pure, and it
proves the translator against the corpus's oracle cases (1,703 at the pin) before any wire or dbt work starts.

---

## 8. Decisions

**Decided by the user, 2026-10-11:**

- **D1. Identifiers.** The user's first answer was native Postgres. Asked for an unbiased view, I recommend
  Postgres's rule first, then a unique case-insensitive match (§3.4, register S43):
  - **Native Postgres:** zero deviation, but every camelCase Legend name needs quotes (`"firstName"`), and an alias
    `AS totalAmount` comes out as `totalamount`, which a Pure caller then cannot read as `$x.totalAmount`.
  - **Exact, case-sensitive (upstream):** rejects queries Postgres accepts (`select NAME from t` with a column
    `name`).
  - **Fully case-insensitive (DuckDB's rule):** forgiving, but a model with `name` and `Name` makes valid Postgres
    ambiguous.
  - **snake_case aliases:** two names for every element.
  - **The recommendation:** keeps every valid Postgres query's meaning, and adds only what Postgres would refuse.
    Its one visible difference is that output names keep their written case.

  Implemented in step 1, pending the user's ruling.
- **D2. A new package** (§3.1): it is `com.legend.protocol.sql`, which the architecture tests require.
- **D3. A hand-written wire protocol with no dependencies.** libpg_query only as a test oracle (§3.5).
- **Overall: as close as possible to native Postgres in everything** (§0 point 4, §3.4).

**Open:**

- **D4. Whether to start now.** Several sessions work in this repository at once. `docs/IN_FLIGHT.md` lists the
  active lines in priority order: the build rebuild, Studio, the database owner, DataCube with Python. Each line
  announces the files it will touch so that sessions do not collide. The question is whether this becomes a new
  line now, beside the others, or waits.
  - Phase 1 is mostly new files under `com.legend.protocol.sql`. Its touch points elsewhere are small:
    - the `Typer`'s `SqlIsland` arm, in `compiler/spec` (the Bazel line's hold on it ended when its reference-lane
      fix landed);
    - route registration in `server/LegendHttpServer`;
    - new natives (`intersect`, `except`) in `builtin/`.
  - **Proposal:** a new line, announced in `IN_FLIGHT`, starting with phase 1.
- **D5. dbt target level:** L1+L2 (proposed), or the L4 drop-in?
- **D6. Classes as tables:** §3.6 is the proposal, and its two sub-decisions (identity source; qualified
  properties with parameters) are open.

---

## 9. Step 1: `#SQL{...}#` queries (built 2026-10-11, on the branch)

**What works.** A Postgres `SELECT` over one `tb('pkg::Database[.schema].table')` source, as an island in any Pure
query:
- clauses: `WHERE`, `GROUP BY` (names, ordinals, aliases, expressions), `HAVING`, aggregates without `GROUP BY`,
  `ORDER BY` (names, ordinals, aliases, expressions, `NULLS FIRST/LAST`), `DISTINCT`, `LIMIT`, `OFFSET`,
  `FETCH FIRST`;
- expressions: comparisons, `AND/OR/NOT`, `IS [NOT] NULL/TRUE/FALSE/UNKNOWN`, `IS [NOT] DISTINCT FROM`, `[NOT] IN`,
  `[NOT] BETWEEN`, `[NOT] LIKE/ILIKE`, `CASE` (both forms), `+ - * / % ||`, `coalesce`, `upper`, `lower`, `length`,
  `trim`, `abs`, `ceil`, `floor`, `current_date`, `now()`;
- aggregates: `count`, `count(DISTINCT)`, `sum`, `avg`, `min`, `max`;
- dates: `DATE '…'` and `TIMESTAMP '…'`, and ISO strings compared with dates.

Everything else is refused by name and position.

**How.**
- `#SQL{}#` parses as before.
- The Typer's arm hands it to `compiler/spec/SqlIslands`. The seam types each FROM source for the translator
  (`protocol/sql/SqlToPure`), resolves the Relation expression it gets back, and the Typer types that once.
- The generated Pure reads like hand-written Pure, for example:
  `#>{s::DB.T}#->groupBy(~[dept], ~[n:r|1:y|$y->count()])->filter(r|$r.n > 1)->select(~[dept, n])`.

**Evidence.**
- **The cases:** 45 queries (`core/src/test/java/com/legend/testcases/SqlIslandCases.java`), each with the rows the
  pinned embedded Postgres 16.15 returned, recorded.
- **On DuckDB:** they pass through `#SQL{}#` (`SqlIslandIntegrationTest`, 51 tests).
- **On Postgres:** they pass both as plain SQL and as islands through the Postgres dialect
  (`SqlIslandPostgresTest`, 90 tests, its own target `//core:sql_island_postgres_test` in `//gates:core`).
- **Units:** the reader and the translator have 38 unit tests (`protocol/sql/SqlParserTest`, `SqlToPureTest`).
- **Regressions:** core's guardrail (88) and census (12) suites pass.

All of this first ran locally on `javac` and JUnit with Maven Central's jars, then under Bazel.

**Under Bazel** (later on 2026-10-11, Bazel 9.2.0 on a 4-core Linux container running as root):
- **The local gate.** `bazel test --lockfile_mode=error //gates:local` on this branch: 310 of 325 pass, every core
  target among them (`core_tests`, with the reader, the translator and the DuckDB end-to-end tests; the checks, the
  guards and the census).
- **What Bazel caught.** NullAway, which the `javac` run did not have, refused `SqlToPure`'s use of the nullable
  `getMessage()`. `SqlSyntaxException.reason()` now carries the message, never null.
- **The Postgres lane.** `//core:sql_island_postgres_test`, as Bazel built it (its JDK 25, its embedded Postgres
  16), passes 90 of 90 run as a non-root user. Inside `bazel test` it cannot start here: `initdb` refuses root
  (`EmbeddedPostgres`), as do `//core:postgres_arm_test` and `//datacube:verify_app_test`.
- **The other 12 failures are this container's.** Each fails the same way on the commit before this work (11283c8),
  and `main`'s CI is green on them:
  - A WebAssembly trap, "function signature mismatch": `//datacube:run_stress_test` (the JSON Lines sample's
    ingest), `verify_features_test` (the filter editor's typed values) and `//query:verify_test` (Objects mode's
    JSON). `verify_smoke_test` renders no rows for the same JSON Lines sample.
  - `//site:verify_test` waits 60 s for a selector, after its DataCube steps pass.
  - Tests that time out even alone: `//datacube:json_read_test`, `upload_test`, the three `typed_values` tests, and
    `//engine-client:model_data_test`.
  - `//datacube:verify_remote_test` times out fetching from `extensions.duckdb.org`. It was not rerun on the base;
    GATES.md records the same dependence.
- **Getting Bazel to fetch.** The session's GitHub access is scoped to this repository, so archive downloads from
  other GitHub repositories return 403, though git reads of them work. bats (registered by `bazel_lib`; nothing
  here uses it) came from `mirror.bazel.build`. The legend-engine 4.145.0 and legend-pure 5.99.0 source archives
  were rebuilt from their git tags with `git archive | gzip -n`, byte-identical to the pinned integrities, and given
  to Bazel through `--distdir`.

**Upstream's parity corpus, run** (2026-10-11; `testcases/SqlParity.java`, `//core:sql_parity_test` on DuckDB and
`//core:sql_parity_postgres_test` on Postgres 16, both in `//gates:core`):
- **What runs.** Every one of the 1,703 cases of legend-engine 4.145.0's `parity-tests`, by upstream's own rules:
  - the tables seeded as its `SchemaManager` seeds them, and its Postgres setup (`pgcrypto`, `test_seq`);
  - the FROM tables rewritten to `tb('e2e::DB.persons') AS persons`;
  - both results sorted unless the SQL says ORDER BY;
  - compared by its `ResultComparator`: names without regard to case, floats within a tolerance.
- **Postgres's answers, snapped.** Upstream asks a live Postgres (Testcontainers) on every run. Here Postgres 16's
  answers sit in `core/src/test/resources/sql-parity/postgres-answers.jsonl`, and the Postgres lane checks each one
  against Postgres itself on every run, so the file cannot drift.
- **Not snapped:** seven volatile cases: three measured from today (`NOW()`- and `CURRENT_DATE`-relative, and
  `AGE(x)` twice), `VERSION()`, `RANDOM()` and the client's port. The three from today were added on 2026-10-12,
  after the two `AGE` answers drifted a day after their snap (neema2/legend-lite#28's CI). A clock read compared
  with a fixed past date stays snapped.
- **The same answers on every platform.** A `bytea` is snapped as Postgres writes it (`\x` hex). A double from
  Postgres's float functions may differ from the snapped one by 1e-12 of the larger: each platform's C library computes
  them, and macOS and Windows differ from Linux in the last digit.
- **Compared unordered:** one case whose ORDER BY leaves tied rows' order to the database.
- **The ratchet.** `ledger.tsv` holds every case's status in each lane. A changed status fails the test, as
  upstream's YAML statuses do (REGRESSION, FIX DETECTED, STATUS MISMATCH). `-Dsql.parity.update` rewrites the
  ledger, `-Dsql.parity.snap` the answers.
- **The result, identical in both lanes:**

  | legend-lite | count | upstream legend-engine on the same cases (Relation path) |
  |---|---|---|
  | PASS: Postgres's rows | 491 | 439 PASS, 13 FAIL, 39 ERROR |
  | FAIL: other rows (a wrong answer) | **0** | — |
  | BUG: answers what Postgres rejects | **0** | — |
  | REFUSED: not supported yet, by name | 1,086 | 586 PASS, 23 FAIL, 477 ERROR |
  | BOTH_ERROR: Postgres rejects it, and so does legend-lite | 61 | the 61 upstream BUGs |
  | SKIP: upstream's 62, upstream's joined-model case, and two volatile | 65 | 62 SKIP, 1 PASS, 2 ERROR |

  Upstream passes 1,026, legend-lite 491, but upstream has 36 wrong answers and 61 accepted invalid queries;
  legend-lite has none. The 586 cases upstream passes and legend-lite refuses are the roadmap: joins, CTEs,
  subqueries, window functions and the function table.
- **What running it fixed.** On its first run the corpus found:
  - two wrong answers: a NULL `x IS TRUE` (a nullable boolean was tested with SQL `=`, so NULL, where Postgres says
    false), and a timestamp fraction past microseconds (Postgres rounds half to even);
  - one internal error: `TIMESTAMP '... 24:00:00'`, the next midnight in Postgres;
  - 43 valid queries refused as invalid. These are now translated (an untyped string compared with a number or a
    boolean takes its type; NULL is the unknown truth value in AND, OR and NOT) or refused by name
    (`EXTRACT(... FROM ...)`, `SUBSTRING(... FROM ...)`, `POSITION(... IN ...)`, `OVERLAY`, `TRIM(... FROM ...)` now
    parse; whole-row references; `jsonb - key`; `date - date`).

**Known differences from Postgres,** each refused or recorded:
- `avg` returns Pure's Float where Postgres returns numeric (`SqlIslandIntegrationTest.anAverageIsAFloat…`).
- Integer `/` is refused (S47).
- Non-ISO date strings are refused (S46).
- Duplicate output names are refused: a Pure relation needs distinct column names.
- `NULL` in an `IN` list and comparison with a `NULL` constant are refused.

### 9.1 Steps 2 to 5: joins, subqueries, windows, functions (built 2026-10-11, on the branch)

Each step lands with its own Postgres-judged cases in `core/src/test/resources/sql-parity/lite/`, beside upstream's
corpus. Their answers are snapped from Postgres 16 and held by the same ledger and lanes: `joins.yaml` (32 cases),
`subqueries.yaml` (53), `windows.yaml` (44) and `functions.yaml` (49). Every case either answers what Postgres
answers, is refused by name, or is an invalid query both reject.

**What the translator now reads:**
- **Several relations.**
  - Joins: INNER, LEFT, RIGHT, FULL and CROSS, with ON, USING and NATURAL; FULL coalesces the merged column.
  - FROM subqueries with column aliases, and non-recursive WITH.
  - `UNION [ALL]`, `INTERSECT`, `EXCEPT`.
  - The right side of a join renames its colliding columns to `<qualifier>_<name>`. Names resolve by Postgres's
    scope rules (ambiguous columns, a missing FROM-clause entry).
- **Subqueries in expressions,** correlated to any enclosing level. A subquery is one level down: its rows are
  `r1`, and an enclosing column is read from that level's row.
  - `EXISTS`: `exists(rel, s|true)`.
  - `[NOT] IN` and `op ANY|SOME|ALL`: two `exists` calls over the strict comparison, so Postgres's three-valued
    answer holds. ANY is FALSE over no rows, ALL is TRUE over no rows, and NOT IN with a NULL row is never TRUE.
  - A scalar subquery: `rel->map(s|$s.c)->toOne()`, nullable. It stays a SQL scalar subquery, so more than one row
    is the database's error, as it is Postgres's.
- **Window functions,** computed after WHERE (and after grouping and HAVING), before the select list, each as
  `extend(over(...), ~col:{p, w, r | ...})`.
  - Ranking: `row_number`, `rank`, `dense_rank`, `percent_rank`, `cume_dist`, `ntile`.
  - Value: `lag`/`lead` with an offset, `first_value`, `last_value`, `nth_value`.
  - The aggregates over a frame.
  - Frames: ROWS frames; RANGE frames with numeric offsets. Postgres's default frame (RANGE to the current row's
    last peer) is written as none, which is every database's default too.
  - Named windows.
- **The function table,** a catalog keyed by Postgres's names (`SqlToPure.FUNCTIONS`):
  - the math functions, `mod`, `round` (half away from zero, spelled with floor and ceiling);
  - `nullif`, `concat`, `greatest`/`least` (NULLs ignored), `num_nulls`;
  - the text functions with Pure equivalents, `md5`;
  - `extract`/`date_part` for the whole-number fields;
  - the `stddev` and `variance` families, grouped and over windows.
- **Casts** where one Pure function gives Postgres's answer. A string constant is read by the type's input rules.

**Refused by name, each because Pure's answer would differ:**
- **Rounding:** `round(x, n)` for n other than 0 (Pure's round is half to even, by its spec), a double's round and
  its cast to an integer (half to even in Postgres).
- **Errors Postgres raises:** casts to the 4-byte integer from numeric or text (Postgres's range error).
- **Fixed precision:** text to numeric (Pure's `parseDecimal` lowers to a fixed `DECIMAL(5, 2)`).
- **Pure's semantics differ:** `split_part` (0-based in Pure, and not lowered for Postgres). `strpos` and
  `position` (Pure's `indexOf` is 0-based by its spec, but legend-lite lowers it to raw `strpos`; PCT already pins
  this).
- **No Pure spelling yet:** `extract(second | epoch)`, `INTERSECT ALL`, `EXCEPT ALL`, an aggregate of only an
  enclosing query's columns, GROUPS frames, `EXCLUDE`, `lag`/`lead` defaults.

**Core lowering bugs the cases found,** each fixed with a regression test that fails without its fix
(`ConcatenateFlattenCheckerTest`, `CorrelatedRelationCheckerTest`):
- A concatenate whose branch sorts or limits rendered `... LIMIT 2 UNION ALL ...`, which no database accepts. It is
  now a subselect.
- `exists` over a limited relation merged its predicate below the LIMIT: a silent wrong answer. It is now isolated
  first.
- A join condition read an enclosing row's `$p.id` as the right side's own `id`. Enclosing rows now resolve
  through the enclosing scopes.
- A correlated `map->toOne` could not see the enclosing row. It now pushes it, as the `exists` and `size` arms do.
  The independent review found that this fix let a row's own column that a filter cannot fold in place (a window
  column) resolve to the enclosing row's column of the same name, and fail at the query's root. A row's own
  variable is now never looked up among the enclosing rows (`Lowerer.scopedResolver`).

**The ledger now,** identical in both lanes:

  | | upstream's 1,703 cases | legend-lite's own 177 | upstream legend-engine on its cases (Relation path) |
  |---|---|---|---|
  | PASS: Postgres's rows | 951 | 144 | 1,026 |
  | FAIL: a wrong answer | **0** | **0** | 36 |
  | BUG: answers what Postgres rejects | **0** | **0** | 61 |
  | ERROR: errs where Postgres answers | **0** | **0** | 518 |
  | REFUSED: not supported yet, by name | 623 | 18 | — |
  | BOTH_ERROR: Postgres rejects it, and so does legend-lite | 61 | 15 | — |
  | SKIP | 68 | 0 | 62 |

  Of the 951, upstream passes 801, errs on 133 and answers 17 wrongly. Of the 223 that upstream passes and
  legend-lite refuses, most need arrays, JSON operators, regular expressions (Postgres's dialect, not DuckDB's),
  intervals, LATERAL, `generate_series` and other functions in FROM, and queries without FROM.

**The Postgres lanes inside `bazel test`, in this root container.** Another session's wrapper
(`tools/postgres/as_unprivileged.sh`, commit 7c21e6d on `claude/great-wright-p6t5s1`, not yet on `main`) runs a test
as an unprivileged user. It needs `chmod o+x /root` once, then
`--strategy=TestRunner=standalone --run_under="tools/postgres/as_unprivileged.sh ubuntu"`. Through it,
`//core:sql_parity_postgres_test` (1,880 cases, the ledger held), `//core:sql_island_postgres_test` (90 of 90) and
`//core:postgres_arm_test` pass. The full local gate on this branch's head otherwise fails only the same 13
container-bound browser and timeout targets as on the base commit.

**Next steps, by size** (the semi-structured step came first, at the user's ask: JSON and Variant are what lite's
DuckDB users do; §9.2):
1. Intervals and date arithmetic.
2. Core: lower `indexOf` 0-based (then `strpos`), a half-away-from-zero `round(x, n)`, `parseDecimal`'s precision.
3. Regular expressions, judged per pattern feature.
4. jsonb's remaining operators (`@>`, `?`, `||`, `-`), `json_each`, `jsonb_typeof`, an array's length: each waits on
   a Pure spelling or a platform fix (§9.2's findings).

Then the HTTP routes (`/api/sql/v1/...`), then the wire.

### 9.2 Semi-structured: jsonb, LATERAL, set-returning functions (built 2026-10-12, on the branch)

The user's correction (2026-10-12): upstream passes 13 of its 133 JSON cases, but that is upstream's gap, not the
feature's worth. Lite's DuckDB users navigate JSON with Pure's `get`, and unnest it with `lateral` and `flatten`. So
the semi-structured step came before the cheap parity batch.

**What a Variant is in SQL: Postgres's `jsonb`** (register S49). A Legend model has one semi-structured type, so a
Variant column cannot be both. `jsonb` is the one whose meaning is Pure's: keys without order, no duplicates. `json`'s
operators and functions read a Variant as `jsonb`'s do.

**What reads now:**
- **Navigation:** `->` is `get` (a constant key's member; an integer's element from 0, negative from the end; past
  either end NULL); `->>` is its text (`to(@String)`: a string's own characters, NULL for jsonb's null).
- **Paths and subscripts** read by jsonb's PATH rule (Postgres's `jsonb_get_element`), not `->`'s: a text names an
  object's member, or, where it spells an integer (strtol's way: leading white space, a sign), an array's element; an
  integer subscript reads an array's element or an object's member named by its digits. `#>`, `#>>`,
  `json(b)_extract_path(_text)` and `x[...]` read each element as `coalesce(member, element)`: an object has no
  element and an array no member, so whichever the value is, one of the two reads it.
- **Constants and casts:** `'...'::jsonb`, `jsonb '...'` and `text::jsonb` parse with `fromJson`; `to_jsonb` of text,
  numbers and booleans is `toVariant`.
- **Set-returning functions,** in FROM, after a LATERAL, or as a whole select-list item:
  - `json(b)_array_elements(_text)` (`toMany(@Variant)`, jsonb's null an element), `json(b)_object_keys`;
  - `generate_series` of integers (`range`), `unnest(ARRAY[...])` and `unnest(string_to_array(...))` (`flatten`).
  A function in FROM reads the items before it, as in Postgres. Its alias is its value
  (`FROM jsonb_array_elements(a) e ... e ->> 'x'`).
- **LATERAL subqueries:** by comma, `CROSS JOIN` or `[INNER] JOIN ... ON`, each Pure's `lateral`.
- **A query without FROM:** one row (a TDS literal of one hidden column).

**The judge.** A `json` or `jsonb` cell is its JSON value: keys unordered, numbers by value. A JSON value equals only a
JSON value (a `jsonb` 42 is not the integer 42). The text of an object or array (`->>` of one) compares as JSON: the
same JSON, written compactly on DuckDB and with `jsonb`'s spaces on Postgres (register S49). Only where Postgres's
text is exactly jsonb's spelling of its value (`", "`, `": "`, keys shorter first): any other text compares as text.

**Refused by name, each where lite would answer otherwise than Postgres:**
- `jsonb`'s own order and equality: a `jsonb` value as a sort, group, DISTINCT, set-operation or window key, or
  compared.
- A `jsonb` value read out of a `jsonb` constant (`->`, `#>`, subscripts, `jsonb_array_elements`), and a path element
  that spells an integer read out of one even as text: see finding 3.
- A key starting with `$` or `/` (finding 4); a member key or a subscript text that is not a constant; a `jsonb`
  constant with a duplicate key (jsonb keeps the last, DuckDB reads the first).
- `json(b)_array_length`, `json_each`, `jsonb`'s `-`, `||`, `@>`, `?` and the rest of its operators; `LEFT JOIN
  LATERAL`; `WITH ORDINALITY`; an array element that may be NULL (a Pure collection holds none); a subquery without
  FROM (finding 6).

**Platform findings** (the database owner's; the first two fixed on the branch, in `sql/dialect/`):
1. **Fixed: DuckDB's `toMany(@Variant)` turned jsonb's null elements into SQL's NULL.** A cast to `JSON[]` does this,
   so a list read them as missing: an array's length dropped them, and `flatten` gave SQL's NULL. It now reads an
   array's elements with `json_extract(x, '$[*]')`, which keeps them. A value that is not an array is still Pure's
   `toMany` error (PCT `testToManyFromNonArray`).
2. **Fixed: Postgres's `toMany(@Variant)` and `to(@Map)` read a value of the wrong shape as an empty list or no map.**
   Postgres, Pure and DuckDB raise an error there. They now raise Postgres's own (`jsonb_array_elements`,
   `jsonb_object_keys`).
3. **Open: the literal-Variant fold.** `CarrierStrategies` folds the navigation of a literal Variant on the list-less
   dialects (Postgres, H2, the engine text). It re-emits a picked string, number or boolean as a plain SQL literal, so
   `fromJson('{"a":"x"}')->get('a')` is the text `x` on Postgres, not a Variant. The SQL front end refuses navigating a
   jsonb constant to a jsonb value.
4. **Open: DuckDB's arrow reads a key that starts with `$` or `/` as a path.** So Pure's `get('$ref')` misreads a
   member named `$ref`.
5. **Open: Postgres's `toOne` of a jsonb value.** Its empty-collection guard is text, so `CASE` cannot match it with
   the jsonb.
6. **Open: `toVariant` of a string constant on Postgres** is `to_jsonb('...')`, whose type Postgres cannot resolve.
7. **Open: a lateral over a correlated one-row relation** (a subquery without FROM reading the enclosing row) does not
   lower.
8. **Open: a JSON number on DuckDB is a double.** Every DuckDB extraction re-serializes it (yyjson reads a number as a
   double): `->>` of `1.50` is `1.5`, of `1e2` is `100.0`, of `12345678901234567.89` is `12345678901234568.0`, where
   Postgres's jsonb keeps numeric's spelling and digits (`1.50`, `100`). The text of a non-integer JSON number, and a
   number beyond a double's 17 digits, differ on DuckDB. Neither the corpus nor its data holds one; no Pure spelling
   recovers the token. For the user to decide: register it, or refuse `->>` where it may meet such a number.
9. **Residue, not refused:** a `bigint` column as an index (`j -> big_col`) is Postgres's error ("operator does not
   exist: jsonb -> bigint") and lite's answer (a model column's SQL width is not known to the translator); a duplicate
   key in a column's JSON text (jsonb keeps the last, DuckDB reads the first).

**The independent review (2026-10-12)** of the step found these, each fixed and pinned by a case in `lite/json.yaml`,
`lite/lateral.yaml` or `lite/functions.yaml`:
- subscripts and quoted path elements read as `->` (Postgres reads them by the path rule above);
- jsonb's null given to `jsonb_array_elements(_text)` or `jsonb_object_keys` gave no rows: Postgres's error ("cannot
  extract elements from a scalar"). It is now read as another scalar, so that Pure's `toMany` and `to(@Map)` raise;
- a set operation's ORDER BY and `JOIN ... USING` took a jsonb key;
- CASE and COALESCE (and GREATEST, LEAST) never resolved one type: an untyped constant now takes the others' type, read
  by its input rules (`coalesce(j -> 'tags', '[]')` is jsonb; `coalesce(age, '0')` an integer), numbers widen, and
  text with a number is Postgres's error ("COALESCE types integer and text cannot be matched");
- a function's alias was no value inside a subquery: a name is now a column at any level first, then a FROM item's
  (Postgres's order);
- a select list's set-returning function ran before WHERE, its alias was a column WHERE could read, and `*` listed it:
  it is now a lateral step after WHERE, its column one no query can name;
- `to_jsonb('x')` (an untyped constant: Postgres's error), a bigint index (`j -> 3000000000`), a `generate_series` step
  beyond bigint (crashed the translator).

**The ledger after this step and its review,** identical in both lanes:
- upstream's 1,703 cases: PASS 1,016 (951 before), REFUSED 558, BOTH_ERROR 61, SKIP 68. Upstream legend-engine passes
  1,026 of them, with 36 wrong answers and 61 accepted invalid queries;
- legend-lite's own 269 (177 before; `lite/json.yaml` 56, `lite/lateral.yaml` 30): PASS 205, REFUSED 34, BOTH_ERROR 30;
- 0 FAIL, 0 BUG, 0 ERROR.

### 9.3 The basics: filters, aggregates, functions and casts (built 2026-10-12, on the branch)

The user's order, after the semi-structured step: the basics first (filters, aggregations, joins, select lists,
functions), each working well, before regular expressions or the wire.

**A randomized check found most of this step's work.** A scratch generator writes type-correct Postgres queries over
the parity schema: filters, GROUP BY and HAVING, the four joins, subqueries, set operations, DISTINCT, windows, CTEs,
CASE and COALESCE, functions, casts, dates and intervals, and the aggregates and window functions below. Each query is
ordered by all its columns and run through both lanes against Postgres. Thirteen seeds ran, about 6,300 queries; the
generator grew with each feature. The last seeds found no wrong answer and no crash. Each finding is fixed and pinned
by a case in `lite/functions.yaml`, `lite/windows.yaml` or `lite/dates.yaml`, among them:
- `x BETWEEN low AND high` was one strict test of its three values, NULL as soon as a bound was. It is
  `x >= low AND x <= high` in three-valued logic, so a NULL bound leaves the other to decide FALSE.
- `x IN (col, 5)` was NULL for a row whose `col` is NULL, even where `x` is 5. With an element that may be NULL it is
  each `x = e` in three-valued OR.
- A CASE whose every result is NULL is text in Postgres: `ceil` of it is Postgres's error, not a NULL.
- `corr` and DuckDB's descending `percentile_disc` at 0 (below).

**What the translator now reads:**
- **Aggregates.**
  - `FILTER (WHERE ...)` on every aggregate: the value where the test holds, else none (`if(test, |value, |[])`).
  - `DISTINCT` in every aggregate.
  - `string_agg(text, 'sep' [ORDER BY col])`: Pure's joinStrings over the group (ordered: the group-lambda form).
  - `bool_and`, `bool_or`, `every`: Pure's `and()` and `or()` of the group.
  - `percentile_cont(p)` and `percentile_disc(p) WITHIN GROUP (ORDER BY x [DESC])`: Pure's percentile. The
    continuous one reads each value as a double, as Postgres does; DuckDB would interpolate a decimal at its own
    scale. The discrete one is an integer column's integer again (`->cast(@Integer)`), so that its division
    truncates and its text has no `.0`.
  - `corr`, `covar_pop`, `covar_samp`: Pure's over `rowMapper` pairs. Postgres's `corr` is NULL where a value's
    variance over the pairs is 0, and DuckDB's quotient is NaN there. So the pairs' variances are computed beside it,
    and the column, extended after the grouping, is NULL where either is 0.
  - `bool_and`, `bool_or`, `every`, `covar_pop` and `covar_samp` also as window functions. `corr` over a window is
    refused: its guard would need the variances as window columns first.
- **Window functions:** `lag(x, n, default)` and `lead(x, n, default)`. The default stands where the other row is
  outside the partition, never where that row's value is NULL. A computed column that is never NULL (the constant 1)
  tells which.
- **Integer division**, truncating toward zero and exact for every bigint. The remainder is taken off, a double
  estimate shrunk below the quotient, and the rest corrected in exact integer arithmetic.
- **NULL constants as Postgres types them.**
  - An operand takes the other operand's type, a function argument its preferred type: Pure's empty collection cast to
    that type (`CAST(NULL AS BIGINT)`). `NULL + NULL` is Postgres's error ("operator is not unique").
  - A comparison with NULL is NULL, so a WHERE keeps no row.
  - An IN list holding NULL is TRUE where the value is found and NULL otherwise, never FALSE.
- **Text.**
  - `concat` writes a value by its output function (t or f); `||` casts it (true, false) and needs text on one side.
  - `substring`/`substr`: Pure's relational substring.
  - `strpos` and `position`: the relational indexOf, 1-based, 0 when not found (measured equal to Postgres on both
    databases).
  - `left` and `right` with a negative count.
- **Numbers.**
  - `round(x, n)` and `trunc(x, n)` of a numeric, exactly: scaled, rounded half away from zero (or truncated), scaled
    back by a decimal constant. Pure's `round(Decimal, n)` breaks ties to even, and `divide(x, 1D, n)` goes through a
    double on DuckDB.
  - `ceil`, `floor`, `sign` and `round(x)` keep Postgres's types (numeric for a numeric, double precision otherwise);
    extract is numeric, date_part double precision.
  - `power` and `pow` of integers and doubles.
  - `round(x)` of a double: half to even, Postgres's rint and Pure's round of a Float (measured on both databases).
- **Dates.**
  - A date plus or minus an integer is a date (`adjust` by days); a date minus a date the days between them
    (`dateDiff`).
  - An interval constant (`INTERVAL '1 month 2 days'`, `INTERVAL '3' DAY`, `'1 week'::interval`), whole amounts with
    Postgres's units and abbreviations, added to or taken from a date or timestamp. Postgres adds its months first (a
    day past the month's end is the month's last day), then its days, then its time; so does the chain of `adjust`
    calls. A date is a timestamp at midnight first, as Postgres's `date + interval` is a timestamp.
  - `make_date`, `make_timestamp`.
  - `date_trunc` of a timestamp to a year, quarter, month, week (Monday), day, hour, minute or second: Pure's
    `firstDayOf...` and `first...Of...`; Pure's first day is a date, made a timestamp at its midnight.
  - Refused: an interval as a value, a fractional or `HH:MM:SS` interval, a timestamp minus a timestamp (an interval),
    `date_trunc` of a date (Postgres truncates a timestamp with time zone there).
- **Casts.**
  - `integer`, `int4` and `smallint` hold a value in their range, else Postgres's error, raised only on the row that
    overflows.
  - A numeric to an integer rounds half away from zero; a double half to even.
  - `varchar(n)` cuts the text to n characters.

**Inexact numerics: refused where they would show.** Postgres's numeric quotient is exact. Pure's divide is a Float,
so a quotient of numbers that are not both integers is a double here. So are an average or statistic of non-doubles
and `sqrt`, `exp`, `ln` and `log` of a numeric. The translator calls such a value INEXACT. It agrees with Postgres to
about 15 significant digits, but it may fall on either side of the exact value: `0.3 / 0.1` is 2.9999999999999996.
- Refused: what rounds it or spells it. That is `round`, `ceil`, `floor`, `trunc`, `round(x, n)`, a cast to an
  integer, and its text. `floor(0.3 / 0.1)` is 3 in Postgres and 2 from the double, a wrong answer the previous steps
  gave.
- Translated: a double (a column, a cast, a quotient with a double in it) is a double in Postgres too, so it rounds.
- Recorded, not refused: a comparison at the exact boundary may fall the other way (`0.3 / 0.1 = 3` is TRUE in
  Postgres, FALSE here). Refusing comparisons would refuse `HAVING avg(x) > 1000`.

**A numeric is at most 38 digits here.** Pure's Decimal is a 38-digit decimal on every database (DuckDB's widest);
Postgres's numeric is unbounded. A product beyond it (a 20-digit value squared) is the database's overflow error where
Postgres answers: an error, never a wrong answer. The randomized check met it once.

**Refused, with the reason measured:**
- Text values cast to numbers. DuckDB's cast reads `'1.5'` and `'1e3'` as integers and `'1_000'` and `'1e400'` as
  doubles, all of which Postgres rejects. A string constant is still read by Postgres's input rules.
- `mode()`: DuckDB breaks a tie otherwise than Postgres, which takes the first value in the order.

**Two fixes in `sql/dialect`,** each announced in IN_FLIGHT:
- `RootNumericTypes` (5cb0ac1). Under DISTINCT, an ORDER BY key that is a projection cast to its numeric type is
  ordered by the cast expression. Postgres requires that ("for SELECT DISTINCT, ORDER BY expressions must appear in
  select list"), and the cast changes no value.
- `QuantileOrder` (aa0c469, widened 6d5f0e1). DuckDB's descending discrete percentile read element `ceil(p * N)` of
  the sorted list: element 0 of a 1-based list at `p = 0`, so NULL. Pure's percentile and Postgres's `percentile_disc`
  both take the first value there; the index is now at least 1. The randomized check found it. The review then found
  the product computed exactly where Postgres's is float8; both orders now compute it as Postgres does.

**The independent review (2026-10-12)** of the step, by an agent that wrote none of it, traced these wrong answers.
Each was measured against Postgres and DuckDB, is fixed, and is pinned by a case in `lite/functions.yaml` or
`lite/dates.yaml`:
- An inexact numeric met by a double became a double, so it could round: `floor(0.3 / 0.1 * 1::float8)` is 3 in
  Postgres, 2 here. An inexact numeric now stays inexact through every operator and function, a double in it or not.
- A double constant's text was read as an exact decimal: `round('2.5'::float8)` is rint's 2, not 3. It is the double
  nearest the text, and COALESCE's one value is converted to a double, not relabelled one.
- A sum of integers is Postgres's bigint for int4 values and its numeric for int8 ones. The two divide differently
  (`sum(b) / 2` is 3.5 for bigints), and a model column's width is not known to the translator. Such a sum is now the
  kind WHOLE: dividing it by an integer is refused, and `sum(x)::bigint / n` or `sum(x)::numeric / n` says which.
- `round(int, n)` and `trunc(int, n)` gave the integer itself: they are the numeric with n places (`42.0`).
- `INTERVAL '1 day 1' MONTH` read the text and the field together; Postgres keeps only the field's part (a month).
  Only a single whole number takes a trailing field now; a field named twice (`'1 mon 1 month'`) is refused.
- `sign` of an inexact numeric was not refused.
- `percentile_disc`: DuckDB picks element `ceil(p * N)` in exact arithmetic, Postgres in float8, and `0.28 * 25` is
  7 in the one and 8 in the other. `QuantileOrder` (sql/dialect, announced in IN_FLIGHT 6d5f0e1) now computes the
  element as Postgres does, in both orders.
- Decimals merged into one value (CASE, COALESCE, GREATEST, LEAST, a lag default, a set operation's column) take one
  scale in DuckDB (`1.5` reads `1.50`), each its own in Postgres. Their numbers agree; their text is refused.

Recorded, not fixed:
- `corr` of a column whose values are equal: Postgres's running sums may leave a variance of about 1e-33 where DuckDB's
  is exactly 0, so Postgres answers with noise where this answers NULL.
- An inexact numeric's comparisons reach DISTINCT, GROUP BY, IN, BETWEEN and a window's ORDER BY too: at the exact
  boundary they may fall the other way, as `0.3 / 0.1 = 3` does.
- `make_date(0, 1, 1)` is Postgres's error and DuckDB's year 0; `-NULL` is Postgres's "not unique" error and a NULL
  here.

**The ledger after this step,** identical in both lanes:
- upstream's 1,703 cases: PASS 1,122 (1,016 before), REFUSED 452, BOTH_ERROR 61, SKIP 68. Upstream legend-engine
  passes 1,026 of them, with 36 wrong answers;
- legend-lite's own 381 (269 before; `lite/functions.yaml` 139, `lite/windows.yaml` 51, `lite/dates.yaml` 21, new):
  PASS 284, REFUSED 46, BOTH_ERROR 51;
- 0 FAIL, 0 BUG, 0 ERROR.

## 10. Sources

- **Upstream code:** `finos/legend-engine` `e60ab67a`, sparse checkout of `legend-engine-xts-sql/`,
  `legend-engine-xts-relationalStore/.../legend-engine-xt-relationalStore-postgresSql/`, and `docs/`.
  `finos/legend-pure` was searched for SQL front-end code and has none.
- **dbt v2** (Rust engine, static analysis, adapters, manifest v12):
  https://docs.getdbt.com/docs/dbt-versions/core-upgrade/upgrading-to-v2. Read 2026-10-11; check it again
  before relying on the adapter list.
- **dbt Fusion components and licenses** (2025-05-28; Level 2 SQL comprehension is proprietary; the authoring
  layer and artifact schemas are Apache 2.0): https://docs.getdbt.com/blog/dbt-fusion-engine-components
- **Manifest schemas:** https://schemas.getdbt.com/
