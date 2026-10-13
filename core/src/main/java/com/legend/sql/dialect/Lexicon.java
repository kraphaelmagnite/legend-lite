package com.legend.sql.dialect;

import java.util.Set;

/**
 * Dialect LEXICON &mdash; pure DATA, the first extraction of the
 * rewrite-then-render migration (remediation T3.2): what a backend quotes
 * and how, with no behavior attached. A backend that differs ONLY here is
 * not a dialect subclass &mdash; it is a {@code Lexicon} row handed to the
 * base renderer (SQLite was exactly that and its class is gone).
 *
 * @param quoteChar     the identifier quote character
 * @param reservedWords lower-cased words that force quoting of a
 *                      plain-looking identifier
 */
public record Lexicon(char quoteChar, Set<String> reservedWords) {

    public Lexicon {
        reservedWords = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(reservedWords));
    }

    /** DuckDB (the execution backend): every keyword DuckDB will not take as a table or column name (its
     *  {@code reserved} and {@code type_function} categories, {@code duckdb_keywords()}), held so by
     *  DuckDbKeywordsTest against the pinned DuckDB; a few more DML words besides. */
    public static final Lexicon DUCKDB = new Lexicon('"', Set.of(
            "all", "analyse", "analyze", "and", "anti", "any", "array", "as", "asc", "asof", "asymmetric", "at",
            "authorization", "between", "binary", "both", "by", "case", "cast", "check", "collate", "collation",
            "column", "columns", "concurrently", "constraint", "create", "cross", "default", "deferrable", "delete",
            "desc", "describe", "distinct", "do", "drop", "else", "end", "except", "exists", "false", "fetch", "for",
            "foreign", "freeze", "from", "full", "generated", "glob", "group", "having", "ilike", "in", "initially",
            "inner", "insert", "intersect", "into", "is", "isnull", "join", "lambda", "lateral", "leading", "left",
            "like", "limit", "map", "natural", "not", "notnull", "null", "offset", "on", "only", "or", "order",
            "outer", "overlaps", "pivot", "pivot_longer", "pivot_wider", "placing", "positional", "primary",
            "qualify", "references", "returning", "right", "select", "semi", "show", "similar", "some", "struct",
            "summarize", "symmetric", "table", "tablesample", "then", "to", "trailing", "true", "try_cast", "union",
            "unique", "unpack", "unpivot", "update", "using", "values", "variadic", "verbose", "when", "where",
            "window", "with"));

    /**
     * SQLite (<a href="https://sqlite.org/lang_keywords.html">lang_keywords</a>)
     * &mdash; the ANSI baseline rendering with SQLite's reserved-word list.
     * SQLite accepts the relational core the base renderer emits (SELECT/
     * JOIN/WHERE/GROUP BY/ORDER BY/LIMIT-OFFSET, TRUE/FALSE since 3.23).
     * KNOWN GAPS that fail LOUD at execution, pending SQLite-specific
     * passes: aliased VALUES sources (TDS literals &mdash; SQLite lacks
     * {@code (VALUES ...) AS t(c1, c2)}), typed DATE/TIMESTAMP literals,
     * and everything with no ANSI encoding at all (list lambdas, QUALIFY,
     * ASOF, native PIVOT) on the base renderer's loud defaults.
     */
    public static final Lexicon SQLITE = new Lexicon('"', Set.of(
            "abort", "action", "add", "after", "all", "alter", "analyze", "and", "as",
            "asc", "attach", "autoincrement", "before", "begin", "between", "by",
            "cascade", "case", "cast", "check", "collate", "column", "commit",
            "conflict", "constraint", "create", "cross", "current_date",
            "current_time", "current_timestamp", "database", "default", "deferrable",
            "deferred", "delete", "desc", "detach", "distinct", "drop", "each",
            "else", "end", "escape", "except", "exclusive", "exists", "explain",
            "fail", "for", "foreign", "from", "full", "glob", "group", "having",
            "if", "ignore", "immediate", "in", "index", "indexed", "initially",
            "inner", "insert", "instead", "intersect", "into", "is", "isnull",
            "join", "key", "left", "like", "limit", "match", "natural", "no", "not",
            "notnull", "null", "of", "offset", "on", "or", "order", "outer", "plan",
            "pragma", "primary", "query", "raise", "recursive", "references",
            "regexp", "reindex", "release", "rename", "replace", "restrict", "right",
            "rollback", "row", "savepoint", "select", "set", "table", "temp",
            "temporary", "then", "to", "transaction", "trigger", "union", "unique",
            "update", "using", "vacuum", "values", "view", "virtual", "when",
            "where", "with", "without"));

    /** MariaDB (2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13): double quotes under the session's
     *  ANSI_QUOTES; MariaDB's reserved words. The {@code MariaDb} dialect quotes EVERY identifier, so the list decides
     *  no spelling there. */
    public static final Lexicon MARIADB = new Lexicon('"', Set.of(
            "accessible", "add", "all", "alter", "analyze", "and", "as", "asc", "asensitive", "before", "between",
            "bigint", "binary", "blob", "both", "by", "call", "cascade", "case", "change", "char", "character", "check",
            "collate", "column", "condition", "constraint", "continue", "convert", "create", "cross", "current_date",
            "current_role", "current_time", "current_timestamp", "current_user", "cursor", "database", "databases",
            "day_hour", "day_microsecond", "day_minute", "day_second", "dec", "decimal", "declare", "default",
            "delayed", "delete", "desc", "describe", "deterministic", "distinct", "distinctrow", "div", "double",
            "drop", "dual", "each", "else", "elseif", "enclosed", "escaped", "except", "exists", "exit", "explain",
            "false", "fetch", "float", "for", "force", "foreign", "from", "fulltext", "general", "grant", "group",
            "having", "high_priority", "if", "ignore", "in", "index", "infile", "inner", "inout", "insensitive",
            "insert", "int", "integer", "intersect", "interval", "into", "is", "iterate", "join", "key", "keys",
            "kill", "leading", "leave", "left", "like", "limit", "lines", "load", "localtime", "localtimestamp",
            "lock", "long", "loop", "match", "minus", "mod", "modifies", "natural", "not", "null", "numeric", "offset",
            "on", "optimize", "option", "or", "order", "out", "outer", "over", "partition", "precision", "primary",
            "procedure", "range", "read", "reads", "real", "recursive", "references", "regexp", "release", "rename",
            "repeat", "replace", "require", "restrict", "return", "returning", "revoke", "right", "rlike", "row_number",
            "rows", "schema", "schemas", "select", "sensitive", "separator", "set", "show", "signal", "smallint",
            "spatial", "specific", "sql", "starting", "straight_join", "table", "terminated", "then", "to",
            "trailing", "trigger", "true", "undo", "union", "unique", "unlock", "unsigned", "update", "usage", "use",
            "using", "values", "varchar", "when", "where", "while", "window", "with", "write", "xor", "zerofill"));

    /** Engine-text lowering (H2/DB2 golden style): minimal quoting. */

    /** H2 2.1.214 reserved words — the ENGINE's own list
     * (h2Extension2_1_214.pure:180-192). This is the GOLDEN TEXT
     * channel's set ({@code Ddl.createTable ENGINE_TEXT}'s
     * column-name rule quotes exactly what the engine quotes); the
     * EXECUTION lexicon {@link #H2} derives from it. */
    public static final Lexicon H2_ENGINE_TEXT = new Lexicon('"', Set.of(
            "all", "and", "array", "as", "between", "case", "check",
            "constraint", "cross", "current_catalog", "current_date",
            "current_schema", "current_time", "current_timestamp",
            "current_user", "distinct", "except", "exists", "false", "fetch",
            "for", "foreign", "from", "full", "group", "having", "if", "in",
            "inner", "intersect", "interval", "is", "join", "left", "like",
            "limit", "localtime", "localtimestamp", "minus", "natural", "not",
            "null", "offset", "on", "or", "order", "primary", "qualify",
            "row", "rownum", "select", "table", "true", "union", "unique",
            "unknown", "using", "values", "where", "window", "with",
            "_rowid_", "both", "groups", "ilike", "leading", "over",
            "partition", "range", "regexp", "rows", "top", "trailing"));

    /** Stock H2 2.1.214 EXECUTION lexicon: the engine's list plus the
     * words the STOCK parser rejects in alias position (witnessed:
     * {@code AS right} &rarr; syntax error) that the engine's laxer
     * FORK omits. Execution-dialect-only — golden text channels use
     * {@link #H2_ENGINE_TEXT} or {@link #ENGINE_STYLE}. */
    public static final Lexicon H2 = new Lexicon('"',
            java.util.stream.Stream.concat(
                    H2_ENGINE_TEXT.reservedWords().stream(),
                    java.util.stream.Stream.of("right"))
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new), java.util.Collections::unmodifiableSet)));

    public static final Lexicon ENGINE_STYLE = new Lexicon('"', Set.of());

    /** Snowflake (2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md): double quotes; Snowflake's documented reserved keywords. The dialect quotes EVERY
     *  identifier, so the list never decides a spelling here. */
    public static final Lexicon SNOWFLAKE = new Lexicon('"', Set.of(
            "account", "all", "alter", "and", "any", "as", "between", "by", "case", "cast", "check", "column",
            "connect", "connection", "constraint", "create", "cross", "current", "current_date", "current_time",
            "current_timestamp", "current_user", "database", "delete", "distinct", "drop", "else", "exists", "false",
            "following", "for", "from", "full", "grant", "group", "gscluster", "having", "ilike", "in", "increment",
            "inner", "insert", "intersect", "into", "is", "issue", "join", "lateral", "left", "like", "localtime",
            "localtimestamp", "minus", "natural", "not", "null", "of", "on", "or", "order", "organization", "qualify",
            "regexp", "revoke", "right", "rlike", "row", "rows", "sample", "schema", "select", "set", "some", "start",
            "table", "tablesample", "then", "to", "trigger", "true", "try_cast", "union", "unique", "update", "using",
            "values", "view", "when", "whenever", "where", "window", "with"));

    /** Databricks SQL (2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md): backticks. The {@code Databricks}
     * dialect quotes EVERY identifier, so this list never decides a spelling there; it is Databricks' documented
     * reserved set (ANSI mode's reserved words) for any reader that asks. */
    public static final Lexicon DATABRICKS = new Lexicon('`', Set.of(
            "all", "alter", "and", "any", "array", "as", "at", "authorization", "between", "both", "by", "case",
            "cast", "check", "collate", "column", "commit", "constraint", "create", "cross", "cube", "current",
            "current_date", "current_time", "current_timestamp", "current_user", "delete", "describe", "distinct",
            "drop", "else", "end", "escape", "except", "exists", "external", "false", "fetch", "filter", "for",
            "foreign", "from", "full", "function", "global", "grant", "group", "grouping", "having", "in", "inner",
            "insert", "intersect", "interval", "into", "is", "join", "lateral", "leading", "left", "like", "local",
            "natural", "no", "not", "null", "of", "on", "only", "or", "order", "out", "outer", "overlaps",
            "partition", "position", "primary", "range", "references", "revoke", "right", "rollback", "rollup",
            "row", "rows", "select", "session_user", "set", "some", "start", "table", "tablesample", "then", "time",
            "to", "trailing", "true", "union", "unique", "unknown", "update", "user", "using", "values", "when",
            "where", "window", "with"));

    /** BigQuery (GoogleSQL; 2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md): backticks; GoogleSQL's reserved
     *  keywords. The {@code BigQuery} dialect quotes every identifier, so the list decides no spelling there. */
    public static final Lexicon BIGQUERY = new Lexicon('`', Set.of(
            "all", "and", "any", "array", "as", "asc", "assert_rows_modified", "at", "between", "by", "case", "cast",
            "collate", "contains", "create", "cross", "cube", "current", "default", "define", "desc", "distinct",
            "else", "end", "enum", "escape", "except", "exclude", "exists", "extract", "false", "fetch", "following",
            "for", "from", "full", "group", "grouping", "groups", "hash", "having", "if", "ignore", "in", "inner",
            "intersect", "interval", "into", "is", "join", "lateral", "left", "like", "limit", "lookup", "merge",
            "natural", "new", "no", "not", "null", "nulls", "of", "on", "or", "order", "outer", "over", "partition",
            "preceding", "proto", "qualify", "range", "recursive", "respect", "right", "rollup", "rows", "select",
            "set", "some", "struct", "tablesample", "then", "to", "treat", "true", "unbounded", "union", "unnest",
            "using", "when", "where", "window", "with", "within"));

    /** Trino (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §18): double quotes; Trino's documented reserved
     *  keywords. The {@code Trino} dialect quotes every identifier, so the list decides no spelling there. */
    public static final Lexicon TRINO = new Lexicon('"', Set.of(
            "alter", "and", "as", "auto", "between", "by", "case", "cast", "constraint", "create", "cross", "cube",
            "current_catalog", "current_date", "current_path", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "deallocate", "delete", "describe", "distinct", "drop", "else", "end",
            "escape", "except", "execute", "exists", "extract", "false", "for", "from", "full", "group", "grouping",
            "having", "in", "inner", "insert", "intersect", "into", "is", "join", "json_array", "json_exists",
            "json_object", "json_query", "json_table", "json_value", "left", "like", "listagg", "localtime",
            "localtimestamp", "natural", "normalize", "not", "null", "on", "or", "order", "outer", "prepare",
            "recursive", "right", "rollup", "select", "skip", "table", "then", "trim", "true", "uescape", "union",
            "unnest", "using", "values", "when", "where", "with"));

    /** ClickHouse (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19): double quotes (case-sensitive names). The
     *  {@code ClickHouse} dialect quotes every identifier, so this list decides no spelling there. */
    public static final Lexicon CLICKHOUSE = new Lexicon('"', Set.of(
            "all", "and", "any", "array", "as", "asc", "asof", "between", "by", "case", "cast", "cross", "desc",
            "distinct", "else", "end", "except", "exists", "false", "final", "format", "from", "full", "global",
            "group", "having", "in", "inner", "interval", "into", "is", "join", "left", "like", "limit", "not", "null",
            "offset", "on", "or", "order", "outer", "prewhere", "qualify", "right", "sample", "select", "settings",
            "then", "true", "union", "using", "when", "where", "window", "with"));

    /** PostgreSQL 16+ (2026-10-01 W5.5/P1 Postgres dialect). The {@code Postgres} dialect
     * QUOTES EVERY identifier (Postgres folds an unquoted name to lowercase, so a bare
     * {@code NAME} reads {@code name}), so this list never decides a spelling there; it
     * is the documented reserved set (SQL key words appendix, "reserved" in the
     * PostgreSQL column) for any reader that asks. */
    public static final Lexicon POSTGRES = new Lexicon('"', Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric",
            "authorization", "binary", "both", "case", "cast", "check", "collate", "collation",
            "column", "concurrently", "constraint", "create", "cross", "current_catalog",
            "current_date", "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct",
            "do", "else", "end", "except", "false", "fetch", "for", "foreign", "freeze", "from",
            "full", "grant", "group", "having", "ilike", "in", "initially", "inner", "intersect",
            "into", "is", "isnull", "join", "lateral", "leading", "left", "like", "limit",
            "localtime", "localtimestamp", "natural", "not", "notnull", "null", "offset", "on",
            "only", "or", "order", "outer", "overlaps", "placing", "primary", "references",
            "returning", "right", "select", "session_user", "similar", "some", "symmetric",
            "system_user", "table", "tablesample", "then", "to", "trailing", "true", "union",
            "unique", "user", "using", "variadic", "verbose", "when", "where", "window", "with"));
}
