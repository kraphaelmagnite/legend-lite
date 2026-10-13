# Dialect-leak census: how to classify each hit

Repo: /home/user/legend-lite. It is READ-ONLY for this job: do not edit, build, or run git commands that change anything.
Write only under `<scratch>/leak/classified/`.

## Why we are doing this

legend-lite compiles Pure into SQL and runs it on many databases: DuckDB, H2, Postgres, SQLite, MariaDB, MySQL, Trino,
ClickHouse, Snowflake, Databricks and BigQuery. Each database has a SQL dialect class in
`core/src/main/java/com/legend/sql/dialect/`. The SQL IR (MIR) in `core/src/main/java/com/legend/sql/` is meant to be
database-neutral data.

The user wants two things:

1. Everything that is specific to a database's SQL lives in that database's dialect. That covers SQL text, function
   names, type spellings, raw-SQL rewriting, error-message knowledge and identifier rules.
2. Everything about connecting to a database lives behind its own per-database interface. That covers JDBC URLs, drivers,
   sessions, workspaces (one schema or database per test package), bulk loading, and the product names drivers report.

So we need to find every place where database-specific knowledge sits somewhere else: code that branches on which
database it is, or that composes SQL for a particular database.

The two existing per-database registries are:

- `core/src/main/java/com/legend/database/Databases.java`: the plan side. It maps a `DatabaseType` to its dialect plus a
  few facts: bulk rows, IN-list temp tables and server versions.
- `core/src/main/java/com/legend/exec/Sessions.java`: the execution side. It holds the JDBC product name, the URL built
  from a connection spec, and whether the database runs in-process.

Both are SEAMS (see the classes below). Still record how they are shaped, because the plan may turn their per-concern
switches into per-database objects.

## Input

`parts/<PART>.tsv` has these columns:

| Column | Meaning |
|---|---|
| `id` | Unique row id |
| `zone` | The area of the repo |
| `module` | The module |
| `file` | Repo-relative path |
| `line` | Line number |
| `category` | Which scanner pattern fired |
| `match` | The matched text |
| `source` | The source line, trimmed |

The scanner categories are:

- **Code patterns:** dialect-class, dialect-class-ref, database-type, driver-import, jdbc-call, raw-sql-rewrite,
  session-setup, product-name-code, jdbc-types, driver-object, dialect-query.
- **String-literal patterns:** sql-text-head, sql-text-body, product-name-string, jdbc-url, type-spelling, sql-fragment,
  sqlstate-or-vendor-msg.

Comments were excluded.

## Output (both files are required)

### 1. `classified/<PART>.tsv`

The header is `id<TAB>class<TAB>group<TAB>note`. Write exactly ONE row for EVERY input id, with no sampling.

- `class` is one of the classes below.
- `group` is a short, stable label for the construct the hit belongs to, for example
  `Sessions.jdbcProduct switch`, `CsvSeed raw rewrite call` or `golden SQL in FooTest`.
- `note` is at most 120 characters.

You MAY assign classes in bulk with a script, for example "every sql-fragment hit inside an `assertEquals` string in a
*DialectTest is TEST-GOLDEN". But first READ the code at each distinct construct, so that every rule is backed by
reading and never guessed from a file name. When you finish, check that the row count equals the input's (minus its
header) and that the set of ids is identical.

### 2. `classified/<PART>.md`

This file has four parts:

- A count table: class × number of hits × number of groups.
- **Every** group classed LEAK-* or TEST-LEAK, listed individually. For each one give:
  - `file:line` or a line range;
  - what the code does;
  - why it is a leak;
  - where it belongs: a named method on the database's `SqlDialect`, or a method on a per-database connector or
    workspace interface. Propose a name.
  - the rough size of the fix, in lines;
  - any coupling, such as who calls it or which guard files pin it (look in `core/src/test/java/com/legend/*Test.java`
    for ledgers and pins that name the file).
- A summary of SEAM, DIALECT-SHARED and SINGLE-BACKEND-APP groups, one line each.
- A section **"misses"**: database-specific behaviour in these files (or right next to them) that the scanner did NOT
  flag, for example a switch over a capability that is really one database's identity in disguise, or a URL prefix test.
  Give file:line for each.

## Classes

### Leaks

| Class | What it is |
|---|---|
| **LEAK-SQL** | SQL text, syntax, keyword or function spelling for some database, composed OUTSIDE `sql/dialect/` on a path that serves (or should serve) several databases. Example: a `"CREATE SCHEMA " + name` string in `exec/` code that runs on more than one backend. |
| **LEAK-ID** | A branch on WHICH database it is, outside the two SEAM registries. This covers a `DatabaseType` switch or compare, `instanceof SomeDialect`, a dialect class name, a product-name string compare, a driver-class check or a JDBC-URL prefix test. It should become a capability or method on the dialect or the connector. |
| **LEAK-CONN** | Per-database connection, session, workspace, driver or JDBC handling (URLs, `DriverManager`, `CREATE DATABASE` for a workspace, `ATTACH`, driver object unwrapping) outside a per-database connector interface. |
| **LEAK-RAW** | Rewriting or adapting raw SQL text (for example H2 text into another database's text: `RawSqlBoundary`, `h2ToDuckDb`, `adaptRaw`) outside the dialect class of the target database. Note: if this logic is in `sql/dialect/` but in a SHARED file that switches on the target, mark it DIALECT-SHARED and say so. |
| **LEAK-TYPE** | A per-database table of type names outside the dialect, for example `exec/SqlTypeCensus.java`. Say what the table is FOR. |
| **LEAK-ERR** | Matching vendor error text or SQLStates outside the dialect or connector. |
| **TEST-LEAK** | Test or harness code (spec/, pct/, testing/, core/src/test) that branches on the backend, or opens backend-specific connections or workspaces inline instead of through a per-backend interface. Examples: a `switch (BACKEND)` over "duckdb"/"h2"/"postgres", or a `CREATE DATABASE ws_N` inline in a corpus class. |

### Legitimate, but listed

| Class | What it is |
|---|---|
| **SEAM** | The one designated per-database registry (`Databases.java`, `Sessions.java`), or an equally designated selection point. Say whether it is a per-concern switch or a per-database object. |
| **DIALECT-INTERNAL** | Inside a dialect class: SQL for its own database. Fine. |
| **DIALECT-SHARED** | Inside `sql/dialect/`, but a shared file (Spellings, TypeNames, Lexicon, AnsiSqlRenderer, RawSqlBoundary, CarrierStrategies, SqlDialect, ...) that holds per-database facts or branches on a database's identity, instead of each dialect class owning its own facts. Note which database and which fact. |

### Not leaks

| Class | What it is |
|---|---|
| **LANG** | The Legend/Pure language or protocol: store-DSL types in `###Relational` (`VARCHAR(200)`), connection-grammar keywords and specs (`DuckDB`, `Snowflake` connection blocks), protocol JSON names, the `DatabaseType` enum declaration, DynaFn's per-database availability list (it mirrors legend-engine's support table), Pure metamodel type names. Not SQL we generate. |
| **SINGLE-BACKEND-APP** | A module whose engine is ONE database by design: `warehouse` (a DuckDB server), `datacube` and `engine-client` (DuckDB-wasm in the browser), python frames on DuckDB. SQL written for that one engine. Note whether it bypasses a dialect that could have served it. |
| **TEST-GOLDEN** | A test asserting one dialect's rendered SQL, or SQL fixtures for the specific database that test exercises. |
| **DIAG** | An error, log or javadoc-in-string message naming a product, with no behaviour attached. |
| **FP** | A false positive: English words ("select a column", "show", "use", "set"), unrelated identifiers, or "oracle" meaning a referee. |

## Judgment notes

- **The question is always:** if we add a 12th database tomorrow, would this line need editing? If yes, and it is not a
  dialect class, a connector or a SEAM registry, it is a leak.
- **H2 as the referee:** H2 is the SQL-text replay referee (`Databases.REPLAY_ORACLE`, `exec/SqlReplayOracle`). Code
  that is H2-specific because it is the referee is still database-specific; class it, and say "referee" in the note.
- **DuckDB as the platform:** DuckDB is the platform's own in-process engine (`Databases.PLATFORM`). DuckDB-specific
  code on the platform path (system database, model-data execution) is still a leak if it composes SQL outside the
  DuckDb dialect.
- **When unsure:** say so in the note, and pick the leak class rather than a benign one.

## Report back (your final message)

Keep it under 60 lines:

- the count table;
- the leak groups, one line each: `class | group | file:line | where it belongs`;
- the misses.

Everything else goes in the files.
