package com.legend.sql.dialect;

import com.legend.sql.SqlFn;

import java.util.EnumMap;
import java.util.Map;

/**
 * Dialect FUNCTION SPELLINGS &mdash; pure DATA (remediation T3.2): the
 * plain {@code name(args)} function names a backend uses, extracted from
 * what were 77 hardcoded switch arms with no override hook. Only
 * PURE spellings live here &mdash; an arm with any shape logic (arity
 * dispatch, wrapping casts, composite expansions) stays code. Named
 * honestly: today's row is DUCKDB's vocabulary (the audit: "AnsiSqlRenderer
 * is a DuckDB renderer with an ANSI name"); a genuine ANSI row is authored
 * when a backend needs different names, and adding it cannot break DuckDB.
 */
public record Spellings(Map<SqlFn, String> fnNames) {

    public Spellings {
        fnNames = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fnNames));
    }

    public static final Spellings DUCKDB = new Spellings(build());


    /** H2 2.1.214 EXECUTION spellings — same-arity same-order renames
     * only (arg-order changes are shape logic and stay coded arms in the
     * {@code H2} dialect). Every row here is in the 2.1.214 function
     * catalog; DuckDB names H2 lacks entirely (levenshtein,
     * jaro_winkler, split, base64, regexp_extract_all, ...) are ABSENT
     * so they fail loud and graduate into the declared-gap registry —
     * never the engine's CREATE ALIAS UDF route. */
    public static final Spellings H2 = new Spellings(h2());

    private static Map<SqlFn, String> h2() {
        Map<SqlFn, String> m = build();
        // every row below EXECUTED on real h2-2.1.214 (RunScript probes,
        // 2026-07-31); SPLIT_PART and FORMAT were probed ABSENT and are
        // deliberately not mapped (loud wall -> declared-gap registry)
        m.put(SqlFn.MATCHES, "regexp_like");       // REGEXP_LIKE(input, regexp)
        m.put(SqlFn.STRFTIME, "formatdatetime");   // FORMATDATETIME(ts, fmt)
        m.put(SqlFn.STRPTIME, "parsedatetime");    // PARSEDATETIME(s, fmt)
        return m;
    }

    /** PostgreSQL 16+ EXECUTION spellings (2026-10-01 W5.5/P1 Postgres dialect): ONLY the
     * DuckDB rows whose name exists in Postgres with the same arguments and the same
     * meaning. Never inherited: {@code regexp_matches} for MATCHES (set-returning on
     * Postgres — a projection of it DELETES rows, POSTGRES_BACKEND.md §4.1) and the
     * DuckDB-only names (date_diff, strftime, string_split, regexp_full_match, len,
     * epoch, epoch_ms, …). Everything absent here is a coded arm or a wall in the
     * {@code Postgres} dialect, decided there by an exhaustive switch. */
    public static final Spellings POSTGRES = new Spellings(postgres());

    private static Map<SqlFn, String> postgres() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ASCII_CODE, SqlFn.ATAN, SqlFn.ATAN2,
                SqlFn.CBRT, SqlFn.CHR, SqlFn.COALESCE, SqlFn.COS, SqlFn.COSH, SqlFn.COT,
                SqlFn.DEGREES, SqlFn.FLOOR_RAW, SqlFn.GREATEST, SqlFn.LEAST,
                SqlFn.LEFT, SqlFn.LOWER, SqlFn.LTRIM, SqlFn.MD5,
                SqlFn.POW, SqlFn.RADIANS, SqlFn.REPEAT_STR,
                SqlFn.REPLACE, SqlFn.REVERSE_STRING, SqlFn.RIGHT, SqlFn.RTRIM, SqlFn.SIN,
                SqlFn.SINH, SqlFn.SPLIT_PART, SqlFn.STARTS_WITH, SqlFn.STRPOS,
                SqlFn.SUBSTRING, SqlFn.TAN, SqlFn.TANH, SqlFn.TIMEZONE, SqlFn.TRIM,
                SqlFn.UPPER}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        return m;
    }

    /** Databricks SQL EXECUTION spellings (2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md), each measured on the
     * account: ONLY a name that exists there with DuckDB's arguments and meaning. Never inherited: {@code concat}
     * (NULL-propagating there), {@code split} and {@code regexp_extract} (a regex, and group 1 by default), the
     * DuckDB-only names, and the trims ({@code rtrim(s, c)} reads its arguments the other way round there).
     * Everything absent here is a coded arm or a wall in the {@code Databricks} dialect. */
    public static final Spellings DATABRICKS = new Spellings(databricks());

    private static Map<SqlFn, String> databricks() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ACOS, SqlFn.ASCII_CODE, SqlFn.ASIN, SqlFn.ATAN, SqlFn.ATAN2,
                SqlFn.CBRT, SqlFn.CHR, SqlFn.COALESCE, SqlFn.COS, SqlFn.COSH, SqlFn.COT, SqlFn.DEGREES, SqlFn.EXP,
                SqlFn.GREATEST, SqlFn.LEAST, SqlFn.LEFT, SqlFn.LENGTH, SqlFn.LEVENSHTEIN, SqlFn.LIST_FLATTEN, SqlFn.LN,
                SqlFn.LOG10, SqlFn.LOWER, SqlFn.MAKE_DATE, SqlFn.MAP_KEYS, SqlFn.MAP_VALUES, SqlFn.MD5,
                SqlFn.POW, SqlFn.RADIANS, SqlFn.REPEAT_STR, SqlFn.REPLACE, SqlFn.REVERSE_STRING, SqlFn.RIGHT,
                SqlFn.SHA1, SqlFn.SIN, SqlFn.SINH, SqlFn.SPLIT_PART, SqlFn.SQRT, SqlFn.SUBSTRING,
                SqlFn.TAN, SqlFn.TANH, SqlFn.UPPER}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        m.put(SqlFn.ERROR, "raise_error");
        m.put(SqlFn.STARTS_WITH, "startswith");
        m.put(SqlFn.ENDS_WITH, "endswith");
        m.put(SqlFn.STRPOS, "instr");
        m.put(SqlFn.MAP_FROM_LISTS, "map_from_arrays");
        m.put(SqlFn.MAP_FROM_ENTRIES, "map_from_entries");
        m.put(SqlFn.LIST_REVERSE, "reverse");
        return m;
    }

    /** Trino EXECUTION spellings (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §18), each measured on Trino 483:
     *  ONLY a name that exists there with DuckDB's arguments and meaning. Never inherited: {@code concat}
     *  (NULL-propagating there), {@code left} and {@code right} and {@code cot} (absent), the hashes (they take and
     *  answer binary), {@code split_part} (NULL past the end) and the DuckDB-only names. Everything absent here is a
     *  coded arm or a wall in the {@code Trino} dialect. */
    public static final Spellings TRINO = new Spellings(trino());

    private static Map<SqlFn, String> trino() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ACOS, SqlFn.ASIN, SqlFn.ATAN, SqlFn.ATAN2, SqlFn.CBRT, SqlFn.CHR,
                SqlFn.COALESCE, SqlFn.COS, SqlFn.COSH, SqlFn.DEGREES, SqlFn.EXP, SqlFn.GREATEST, SqlFn.LEAST,
                SqlFn.LENGTH, SqlFn.LIST_FLATTEN, SqlFn.LN, SqlFn.LOG10, SqlFn.LOWER, SqlFn.MAP_KEYS, SqlFn.MAP_VALUES,
                SqlFn.POW, SqlFn.RADIANS, SqlFn.REPLACE, SqlFn.REVERSE_STRING, SqlFn.SIN, SqlFn.SINH, SqlFn.SQRT,
                SqlFn.SUBSTRING, SqlFn.TAN, SqlFn.TANH, SqlFn.UPPER, SqlFn.STARTS_WITH, SqlFn.STRPOS,
                SqlFn.MAP_FROM_ENTRIES}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        m.put(SqlFn.LEVENSHTEIN, "levenshtein_distance");
        m.put(SqlFn.LIST_REVERSE, "reverse");
        return m;
    }

    /** ClickHouse EXECUTION spellings (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19), each measured on
     *  ClickHouse 25.8: ONLY a name that exists there with DuckDB's arguments and meaning. Never inherited: the string
     *  functions that count bytes (length, lower, substring and the rest: their UTF-8 forms are coded arms), concat
     *  (NULL-propagating there) and the DuckDB-only names. */
    public static final Spellings CLICKHOUSE = new Spellings(clickhouse());

    private static Map<SqlFn, String> clickhouse() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        // (ABS is ClickHouse's own arm: an integer's abs is a UInt64 there)
        for (SqlFn f : new SqlFn[] {SqlFn.ACOS, SqlFn.ASIN, SqlFn.ATAN, SqlFn.ATAN2, SqlFn.CBRT,
                SqlFn.COALESCE, SqlFn.COS, SqlFn.COSH, SqlFn.DEGREES, SqlFn.EXP, SqlFn.GREATEST, SqlFn.LEAST, SqlFn.LN,
                SqlFn.LOG10, SqlFn.RADIANS, SqlFn.SIN, SqlFn.SINH, SqlFn.SQRT, SqlFn.TAN, SqlFn.TANH}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        m.put(SqlFn.POW, "pow");
        m.put(SqlFn.LEVENSHTEIN, "editDistanceUTF8");
        m.put(SqlFn.LIST_FLATTEN, "arrayFlatten");
        m.put(SqlFn.MAP_KEYS, "mapKeys");
        m.put(SqlFn.MAP_VALUES, "mapValues");
        m.put(SqlFn.STARTS_WITH, "startsWith");
        m.put(SqlFn.ENDS_WITH, "endsWith");
        m.put(SqlFn.LIST_REVERSE, "arrayReverse");
        m.put(SqlFn.JARO_WINKLER, "jaroWinklerSimilarity");
        m.put(SqlFn.CHR, "char");
        return m;
    }

    /** BigQuery EXECUTION spellings (GoogleSQL; 2026-10-11): ONLY a name that exists on BigQuery with DuckDB's
     *  arguments and meaning, each measured on the account. */
    public static final Spellings BIGQUERY = new Spellings(bigquery());

    private static Map<SqlFn, String> bigquery() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ACOS, SqlFn.ASIN, SqlFn.ATAN, SqlFn.ATAN2, SqlFn.CBRT, SqlFn.CHR,
                SqlFn.COALESCE, SqlFn.COS, SqlFn.COSH, SqlFn.COT, SqlFn.SIN, SqlFn.SINH, SqlFn.TAN, SqlFn.TANH,
                SqlFn.LOWER, SqlFn.UPPER, SqlFn.LTRIM, SqlFn.RTRIM, SqlFn.TRIM, SqlFn.REPLACE, SqlFn.STARTS_WITH,
                SqlFn.ENDS_WITH, SqlFn.STRPOS, SqlFn.SPLIT}) {
            m.put(f, f.name());
        }
        m.put(SqlFn.ERROR, "ERROR");
        m.put(SqlFn.ASCII_CODE, "ASCII");
        m.put(SqlFn.REVERSE_STRING, "REVERSE");
        m.put(SqlFn.REPEAT_STR, "REPEAT");
        m.put(SqlFn.LEVENSHTEIN, "EDIT_DISTANCE");
        m.put(SqlFn.FLOOR_RAW, "FLOOR");
        m.put(SqlFn.MATCHES, "REGEXP_CONTAINS");
        m.put(SqlFn.SUBSTRING, "SUBSTR");
        m.put(SqlFn.LIST_REVERSE, "ARRAY_REVERSE");
        m.put(SqlFn.LIST_LENGTH, "ARRAY_LENGTH");
        return m;
    }

    /** Snowflake EXECUTION spellings (2026-10-11): ONLY a name that exists on Snowflake with DuckDB's arguments and meaning, each
     *  measured. Never inherited: concat (NULL-propagating here), greatest/least (NULL if any argument is: the
     *  _IGNORE_NULLS forms are DuckDB's), log10 (no such function), strpos (CHARINDEX swaps the arguments). */
    public static final Spellings SNOWFLAKE = new Spellings(snowflake());

    private static Map<SqlFn, String> snowflake() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ATAN, SqlFn.ATAN2, SqlFn.CBRT, SqlFn.CHR, SqlFn.COALESCE,
                SqlFn.COS, SqlFn.COSH, SqlFn.COT, SqlFn.DEGREES, SqlFn.EXP, SqlFn.FLOOR_RAW, SqlFn.LEFT, SqlFn.LENGTH,
                SqlFn.LN, SqlFn.LOWER, SqlFn.LTRIM, SqlFn.MD5, SqlFn.RADIANS, SqlFn.REPEAT_STR, SqlFn.REPLACE,
                SqlFn.REVERSE_STRING, SqlFn.RIGHT, SqlFn.RTRIM, SqlFn.SHA1, SqlFn.SIN, SqlFn.SINH, SqlFn.SPLIT_PART,
                SqlFn.SQRT, SqlFn.SUBSTRING, SqlFn.TAN, SqlFn.TANH, SqlFn.TRIM, SqlFn.UPPER}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        m.put(SqlFn.ASCII_CODE, "ascii");
        m.put(SqlFn.POW, "power");
        m.put(SqlFn.GREATEST, "greatest_ignore_nulls");
        m.put(SqlFn.LEAST, "least_ignore_nulls");
        m.put(SqlFn.LEVENSHTEIN, "editdistance");
        m.put(SqlFn.STARTS_WITH, "startswith");
        m.put(SqlFn.ENDS_WITH, "endswith");
        m.put(SqlFn.MAKE_DATE, "date_from_parts");
        m.put(SqlFn.LIST_LENGTH, "array_size");
        m.put(SqlFn.LIST_REVERSE, "array_reverse");
        m.put(SqlFn.MAP_KEYS, "object_keys");
        m.put(SqlFn.ENCODE_BASE64, "base64_encode");
        m.put(SqlFn.DECODE_BASE64, "base64_decode_string");
        m.put(SqlFn.JSON_ARRAY_LENGTH, "array_size");
        return m;
    }

    /** SQLite EXECUTION spellings (3.47.1, the product's driver build, 2026-10-11): ONLY a name that exists there with
     *  DuckDB's arguments and meaning, each measured -- the math functions, the driver's statistics extension
     *  ({@code cot}, {@code reverse}) and the core text functions. */
    public static final Spellings SQLITE = new Spellings(sqlite());

    private static Map<SqlFn, String> sqlite() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ATAN, SqlFn.ATAN2, SqlFn.COS, SqlFn.COSH,
                SqlFn.COT, SqlFn.SIN, SqlFn.SINH, SqlFn.TAN, SqlFn.TANH, SqlFn.DEGREES, SqlFn.RADIANS, SqlFn.EXP,
                SqlFn.LN, SqlFn.LOG10, SqlFn.SQRT, SqlFn.LOWER, SqlFn.UPPER, SqlFn.LTRIM, SqlFn.RTRIM, SqlFn.TRIM,
                SqlFn.REPLACE, SqlFn.REVERSE_STRING, SqlFn.FLOOR_RAW, SqlFn.SUBSTRING, SqlFn.JSON_ARRAY_LENGTH}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        m.put(SqlFn.POW, "pow");
        m.put(SqlFn.CHR, "char");
        m.put(SqlFn.STRPOS, "instr");
        m.put(SqlFn.LIST_LENGTH, "json_array_length");
        return m;
    }

    /** MariaDB EXECUTION spellings (11.4, 2026-10-11): ONLY a name that exists there with DuckDB's arguments and
     *  meaning, each measured. */
    public static final Spellings MARIADB = new Spellings(mariadb());

    private static Map<SqlFn, String> mariadb() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        for (SqlFn f : new SqlFn[] {SqlFn.ABS, SqlFn.ATAN, SqlFn.ATAN2, SqlFn.COS, SqlFn.COT, SqlFn.SIN, SqlFn.TAN,
                SqlFn.DEGREES, SqlFn.RADIANS, SqlFn.EXP, SqlFn.LN, SqlFn.LOG10, SqlFn.SQRT, SqlFn.LOWER, SqlFn.UPPER,
                SqlFn.REPLACE, SqlFn.REVERSE_STRING, SqlFn.FLOOR_RAW}) {
            m.put(f, DUCKDB.fnNames().get(f));
        }
        m.put(SqlFn.POW, "POW");
        m.put(SqlFn.STRPOS, "INSTR");
        m.put(SqlFn.LIST_LENGTH, "JSON_LENGTH");
        m.put(SqlFn.JSON_ARRAY_LENGTH, "JSON_LENGTH");
        return m;
    }

    private static Map<SqlFn, String> build() {
        Map<SqlFn, String> m = new EnumMap<>(SqlFn.class);
        m.put(SqlFn.ABS, "abs");
        m.put(SqlFn.ACOS, "acos");
        m.put(SqlFn.ASCII_CODE, "ascii");
        m.put(SqlFn.ASIN, "asin");
        m.put(SqlFn.ATAN, "atan");
        m.put(SqlFn.ATAN2, "atan2");
        m.put(SqlFn.CBRT, "cbrt");
        m.put(SqlFn.CHR, "chr");
        m.put(SqlFn.COALESCE, "coalesce");
        m.put(SqlFn.COS, "cos");
        m.put(SqlFn.COSH, "cosh");
        m.put(SqlFn.COT, "cot");
        m.put(SqlFn.DATE_DIFF, "date_diff");
        // (DATE_TRUNC left the data map 2026-09-01: day-grained
        // truncations deliver a DATE — AnsiSqlRenderer's own arm
        // casts per-backend; a data row would bypass it)
        m.put(SqlFn.DAYNAME, "dayname");
        m.put(SqlFn.DEGREES, "degrees");
        m.put(SqlFn.ENDS_WITH, "ends_with");
        m.put(SqlFn.EPOCH_MS, "epoch_ms");
        m.put(SqlFn.EPOCH_SECONDS, "epoch");
        m.put(SqlFn.ERROR, "error");
        m.put(SqlFn.EXP, "exp");
        m.put(SqlFn.EXTRACT, "date_part");
        m.put(SqlFn.FLOOR_RAW, "floor");
        m.put(SqlFn.FORMAT, "printf");
        m.put(SqlFn.FROM_EPOCH_SECONDS, "to_timestamp");
        m.put(SqlFn.GREATEST, "greatest");
        // HASH is NOT a spelling row: pure hashCode(...):Integer[1] is
        // SIGNED 64-bit and DuckDB's hash() is UBIGINT — the conform
        // cast is shape logic, so it lives in the hashSigned arm
        // (H2 2.1.214 has no hash() at all; the old row was phantom)
        m.put(SqlFn.JARO_WINKLER, "jaro_winkler_similarity");
        m.put(SqlFn.JSON_TYPE, "json_type");
        m.put(SqlFn.JSON_ARRAY_LENGTH, "json_array_length");
        m.put(SqlFn.JSON_PRETTY, "json_pretty");
        m.put(SqlFn.LEAST, "least");
        m.put(SqlFn.LEFT, "left");
        m.put(SqlFn.LENGTH, "length");
        m.put(SqlFn.LEVENSHTEIN, "levenshtein");
        m.put(SqlFn.LIST_FLATTEN, "flatten");
        m.put(SqlFn.LIST_LENGTH, "len");
        m.put(SqlFn.LN, "ln");
        m.put(SqlFn.LOG10, "log10");
        m.put(SqlFn.LOWER, "lower");
        m.put(SqlFn.LTRIM, "ltrim");
        m.put(SqlFn.MAKE_DATE, "make_date");
        m.put(SqlFn.MAP_CONCAT, "map_concat");
        m.put(SqlFn.MAP_EXTRACT, "map_extract");
        m.put(SqlFn.MAP_FROM_ENTRIES, "map_from_entries");
        m.put(SqlFn.MAP_FROM_LISTS, "map");
        m.put(SqlFn.MAP_KEYS, "map_keys");
        m.put(SqlFn.MAP_VALUES, "map_values");
        m.put(SqlFn.MATCHES, "regexp_matches");
        m.put(SqlFn.MD5, "md5");
        m.put(SqlFn.MONTHNAME, "monthname");
        m.put(SqlFn.POW, "power");
        m.put(SqlFn.RADIANS, "radians");
        m.put(SqlFn.REGEXP_EXTRACT, "regexp_extract");
        m.put(SqlFn.REGEXP_EXTRACT_ALL, "regexp_extract_all");
        m.put(SqlFn.REGEXP_FULL_MATCH, "regexp_full_match");
        m.put(SqlFn.REGEXP_REPLACE, "regexp_replace");
        m.put(SqlFn.REPEAT_STR, "repeat");
        m.put(SqlFn.REPLACE, "replace");
        m.put(SqlFn.REVERSE_STRING, "reverse");
        m.put(SqlFn.RIGHT, "right");
        m.put(SqlFn.RTRIM, "rtrim");
        m.put(SqlFn.SHA1, "sha1");
        m.put(SqlFn.SHA256, "sha256");
        m.put(SqlFn.SIN, "sin");
        m.put(SqlFn.SINH, "sinh");
        m.put(SqlFn.SPLIT, "string_split");
        m.put(SqlFn.SPLIT_PART, "split_part");
        m.put(SqlFn.SQRT, "sqrt");
        m.put(SqlFn.STARTS_WITH, "starts_with");
        m.put(SqlFn.STRFTIME, "strftime");
        m.put(SqlFn.STRPOS, "strpos");
        m.put(SqlFn.STRPTIME, "strptime");
        m.put(SqlFn.SUBSTRING, "substr");
        m.put(SqlFn.TAN, "tan");
        m.put(SqlFn.TANH, "tanh");
        m.put(SqlFn.TIMEZONE, "timezone");
        m.put(SqlFn.TRIM, "trim");
        m.put(SqlFn.UPPER, "upper");
        return m;
    }
}
