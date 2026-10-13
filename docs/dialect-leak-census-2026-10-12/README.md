# The dialect-leak census (2026-10-12): the homework behind the plan

**Start here if you have no context.** The plan is `docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md`. It covers the
verdict, the target shape, eight phases, decisions D1–D8, and an appendix of every leak group. This folder holds the
work behind that plan:

- how the census was taken, so it can be re-run;
- the rules each site was judged by;
- the nine reviewers' reports, with every finding, proposal and "miss" in full.

The plan summarises those reports. When a phase is implemented, read the slice reports it touches: they name the
exact sites, where each site belongs, the size of the fix, and which guard files pin it.

## What was asked (the user, 2026-10-12)

> "we need a comprehensive sweep of any code that leaks database dialect and fix all of them before we move on --
> everything sql related should be hidden in the sql dialect, and like you said anything connection/workspace related
> should also have its own interface as well -- I don't know what sqltypescensus is but also probably needs to be
> hidden behind sql dialect -- let's first do a comprehensive sweep/census to find every leak not sampling/guessing --
> then we write a plan first and look at it together -- this should also cover any "raw" sql stuff too"

Earlier, the same session: *"For running different backends I really hope we are not hacking but instead pushing all
the difference through each "dialect" so we are capturing all diffs in one place."*

## Where it stands

**The census and the plan are done; the plan has not been implemented.** The code the census audited landed on main
in neema2/legend-lite#30 (merge c03ac6b). Five of the plan's items were done inside that PR, prompted by its CI and
its independent review:

- **`DialectBoundaryTest` (part of Phase 0).** It now names all eleven dialects (`Databases.java` 7 → 11) and every
  driver package in core. Its `DatabaseType` and product-name censuses still cover only H2, DuckDB and Postgres; that
  is the rest of Phase 0.
- **The timestamp re-fetch.** `Executor.fetch` no longer silently keeps a `Timestamp` for every driver. Snowflake and
  Databricks declare `SqlDialect.refetchesTimestampAsJavaTime() == false`. That is a driver fact on the dialect; Phase
  5 moves it to the connector.
- **ClickHouse's `default -> "max"`.** It was `BOOL_OR`'s arm in disguise. It is now named, and the default throws.
- **Postgres folding.** Folding of a store's bare names on Postgres was tried and reverted. It became decision D8.
- **AGENTS.md and `core/README.md`.** Both now list SQLite as a dialect class.

**Line numbers drift.** The census was taken at commit 7afb645. The slice reports cite lines at that commit, so
re-run the scanner before acting on one.

## How the census was taken

1. **Two scanner passes over every tracked source file:** `.java .ts .tsx .js .mjs .py .kt`. Each file is lexed into
   code, comments and string literals, and comments are skipped. The scanners are `scanner/scan.py` and
   `scanner/scan2.py`.
   - **Pass 1, code patterns:**
     - dialect class names and references;
     - `DatabaseType.X`;
     - driver packages;
     - JDBC calls (`getDatabaseProductName`, `unwrap`, `setAutoCommit`, `duplicate()`);
     - raw-SQL entry points (`RawSqlBoundary`, `h2ToDuckDb`, `adaptRaw`, `rawTarget`, ...);
     - session-setup entry points;
     - product names (DuckDB, Postgres, H2, Snowflake, ... MongoDB, Elastic).
   - **Pass 1, string patterns:** SQL statement heads and bodies, product names, `jdbc:` URLs and type spellings.
   - **Pass 2 (the patterns pass 1 could miss):**
     - `java.sql.Types` and result-set metadata calls;
     - driver objects (`PGobject`, `DuckDB*`, ...);
     - `dialect.getClass()` and `dialect.name()` checks;
     - SQL fragments (`COALESCE`, `ATTACH`, `information_schema`, `pg_*`, `duckdb_*`, `LATERAL`, ...);
     - vendor error text and SQLStates.
2. **The result: 27,956 candidate sites in 1,007 files**, split into nine slices by area.
3. **Every site was classified by a reviewer who read the code.** No site was sampled, and each slice's output was
   checked to hold exactly one row per site. Bulk rules were allowed, but only after reading the construct they
   covered.
   - The classes and the judgment rules are in `GUIDE.md`. The key test: *if a 12th database were added tomorrow,
     would this line need editing?*
   - Each reviewer also hunted for **misses**: leaks no pattern could see.

To re-run: `python3 -I scanner/scan.py <repo root> <out dir>`, then `scanner/scan2.py` with the same arguments. They
write `hits.tsv` and `hits2.tsv`, one row per site with the columns zone, module, file, line, category, match and
source. At 7afb645 they give 21,608 + 6,348 = 27,956 hits in 1,007 files (re-checked 2026-10-13).

Then build each slice's input by hand, as the census did; that step was not scripted, and its working files were not
kept. `GUIDE.md` expects `parts/<PART>.tsv`: the rows of one slice's files (the slices are the nine reports below,
by zone and module), with an `id` column added, unique per row. Classify each part against `GUIDE.md`. The per-site
tables of 2026-10-12 are not kept; the reports below carry every finding.

## The nine slice reports (`slices/`)

| report | what it covered | what it found |
|---|---|---|
| `A1_core_exec.md` | `exec/`, `setup/`, `database/`, `lowering/`, `resolver/`, `builtin/`, `executionplan/`, root classes | 13 leak groups. `SqlTypeCensus`'s dialect-class-name branches and driver type tables. `Executor.pureOfSqlType` misses Postgres `int8`, ClickHouse `Nullable(..)` and BigQuery `INT64`. A raw `CREATE SCHEMA`. The raw-H2 adaptation. The `@legend_raise` query. Lowering compares DuckDB `json_type` names. `DynaFn`'s `Dialect` enum is the Legend language, read only by one error message. |
| `A2_core_front.md` | `model/`, `parser/`, `protocol/`, `lexer/`, `normalizer/`, `plan/`, `compiler/`, `server/`, `testdatagen/` | The front end is clean. The leaks are `TestDataGenerator` (about 450 lines of hand SQL), `CatalogGrids` (catalog SQL composed in the type checker), `PlanText`/`InProtocol` engine text, an `"H2"` default in `ContextReading`, and `CreateClass`'s `"duckDB"` branch. |
| `B_other_product.md` | warehouse, datacube, engine-client, python, wasm, query, studio, ... | The warehouse is **mixed-engine**: DuckDB plus attached Postgres catalogs. Its Postgres SQL, libpq DSN, error matching and engine name are leaks. wasm `Boundary` hard-wires `new DuckDb()`. The TypeScript modules choose a dialect from data. The tab-only DuckDB SQL is the engine's own. |
| `C1_spec_pct_testing.md` | spec/ (the corpus), pct/, testing/ | The corpus has **no backend interface**: a `BACKEND` string switch with a `default -> DuckDB` arm, `DuckWorkspaces` and `ServerWorkspaces` called directly, H2 fixture-aside SQL inline, and the referee routed by product name. PCT already has one per-database abstraction (`PctBackend.Lane`). The report also has a method-by-method table for a `CorpusBackend`. |
| `C2a_core_test_rest.md` | core/src/test outside integration and resolver | 8 single-file test leaks. The guards' blind spots: `DialectBoundaryTest`, and the driver rules in `ArchitectureTest`, `JdbcSurfaceCensusTest` and `TenetRatchetTest`. Many tests hand-list their backends, because there is no `Databases.implemented()`. |
| `C2b_core_test_integration_resolver.md` | the ~4,000 behavioural tests | 12 test-leak groups. About 228 inline `jdbc:duckdb:` opens across 69 files. `AbstractDatabaseTest`'s 27 subclasses (its SQLite subclass declares `specification: DuckDB {}`). 26 resolver `sqlOf()` copies. `CorpusDifferentialTest` renders every runtime with DuckDb. It proposes `TestBackend`. |
| `C3_other_tests.md` | tests of the other modules | No leaks. It records the facts other tests pin (Postgres catalog type names in `infer.test.ts`; the DuckDB workspace steps copied in `WarehouseJdbcTest`), which must move together. |
| `D_tooling.md` | experiments/, scripts/, tools/, docs code, parser-equivalence | Live leaks: `RenderCensus` (a hand-listed dialect set that has drifted) and `differential.py` (DuckDB seed SQL). The rest is dead code (`experiments/` is bazelignored; the probe harnesses under `docs/`). |
| `E_dialect.md` | `sql/dialect/` and the IR (`sql/`) | Each database's facts sit in 4 to 9 files (`Spellings`, `TypeNames`, `Lexicon`, `CarrierStrategies.Caps`, the base renderer, `RawSqlBoundary`, ...). About 25 DuckDB arms are baked into the base renderer. The IR carries SQL (`Join.Kind.sql`, `"to_days"`, DuckDB type-name compares). `CatalogRules` is the pattern to copy. Its section 3 is a fact-by-fact table of where each row should move. |

## The census in numbers

| class | sites | groups | meaning |
|---|---:|---:|---|
| LEAK-SQL / ID / TYPE / RAW / CONN / ERR | 441 | 39 | database-specific knowledge outside the dialect, connector or registry |
| DIALECT-SHARED | 247 | 53 | inside `sql/dialect/`, but a database's fact sits in a shared file |
| TEST-LEAK | 1,002 | 45 | 36 groups live, 9 in dead code |
| SEAM | 709 | 51 | the designated registries and guard registers |
| DIALECT-INTERNAL | 3,585 | 64 | fine |
| LANG | 5,693 | 147 | fine |
| SINGLE-BACKEND-APP | 3,759 | 69 | fine |
| TEST-GOLDEN | 9,694 | 329 | fine |
| DIAG and FP | 2,826 | 204 | fine |

## The base-renderer question

The user asked: *"do we need a base renderer at all? Should every database just have its own siloed dialect that
does everything?"*

**Measured at c03ac6b.** `AnsiSqlRenderer` has 99 protected or public methods. Counted over the eleven concrete
dialects (DuckDb, H2, Postgres, SQLite, MariaDb, Trino, ClickHouse, Snowflake, Databricks, BigQuery, EngineStyleH2):

| overridden by N of 11 | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| methods | 18 | 14 | 9 | 2 | 2 | 5 | 4 | 10 | 9 | 18 | 4 | 4 |

Four methods are overridden by every dialect, so their base default serves no one: `call`, `passes`,
`roundHalfEven` and `variantAwareCast`. Forty-nine are overridden by six or more. Many overrides handle a few cases
and delegate the rest, so these counts overstate how much is replaced.

The counting rule, to re-derive it:
- **A method** is any `public` or `protected` method declared in `AnsiSqlRenderer`.
- **An override** is an `@Override` method of the same name declared in the dialect's own class.
- **Family children are not counted**, because they inherit from a relative, not from the base: MySql, H2Modern,
  EngineStyleDB2 and EngineStyleComposite.

**The answer given, a proposed design not yet confirmed by the user:**

- **Keep a base, but restrict it to grammar.** Grammar is statement shape, joins, `CASE`, precedence, parameter
  placement and window-frame syntax: the same on all eleven databases, and most of the base's 1,678 lines.
- **Make every vocabulary point abstract.** Function names, types, JSON and list constructs, date functions and
  quoting get no default. javac then forces each dialect to choose; a new dialect's compile errors list everything it
  must decide.
- **Shared spellings are helpers a dialect calls by name**, not inherited defaults.
- **Real families keep inheriting:** MySql from MariaDb, H2Modern from H2, the EngineStyle chain, and SingleStore
  from MySql.
- **The rule:** if any of our databases spells something differently, it is vocabulary; if all agree and it is
  standard SQL, it is grammar.

Fully siloed dialects would copy the grammar eleven times, more than 10,000 duplicated lines. This design gets the
silo's guarantee, that nothing is inherited by accident, without the copies. It turns decision D1 into compile
errors, and it belongs in Phase 1 (1b).

## Limits of the method (what a re-run should know)

- **The scanners scan themselves.** Run on a head that has this folder, they also count their own patterns:
  `scanner/scan.py` (64 hits) and `scanner/scan2.py` (118) at ff5ec11. Drop `docs/dialect-leak-census-2026-10-12/`
  from the hits before classifying.

- **Not read by the scanner:** comments; regex literals in TypeScript; BUILD and `.bzl` files (the per-database
  driver-jar lists live there, and the lane lists in `spec/BUILD.bazel` and `core/BUILD.bazel`); YAML, JSON and
  Markdown. The reviewers found what they could of these by hand (their "misses").
- **A capability flag can be one database's identity in disguise.** The reviewers judged each one. Examples:
  `rawH2IsNative()` is true only for H2; `Caps.correlatedExplode` is false only for H2 and never read; a
  `Sessions.Opening` arm means a specific database.
- **The IR's semantics follow DuckDB in places:** its typing rules, LIST_SLICE, strftime codes, TYPEOF names, and
  H2's scan order. Whether that is a leak is decision D3. The census lists them as misses, not as leak sites.
