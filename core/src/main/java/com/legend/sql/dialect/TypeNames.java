package com.legend.sql.dialect;

import com.legend.sql.SqlType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Dialect TYPE SPELLINGS &mdash; pure DATA (remediation T3.2, extraction 2):
 * the scalar CAST names a backend uses, plus its composite-type
 * capabilities. Absence is LOUD at the read site &mdash; a scalar with no
 * spelling here is "this backend cannot cast to that type", never a
 * silent fallback. Composite RULES (DECIMAL(p,s), array suffix, MAP,
 * STRUCT field lists) are structural and live with the renderer; this
 * record only says which are supported and what the leaves are called.
 *
 * @param scalarNames   scalar &rarr; spelling; absent = unsupported (loud)
 * @param structSupport whether STRUCT(...) composite casts exist
 */
public record TypeNames(Map<SqlType.Scalar, String> scalarNames,
        boolean structSupport) {

    public TypeNames {
        scalarNames = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scalarNames));
    }

    /** ANSI baseline: no JSON, no STRUCT. */
    public static final TypeNames ANSI = new TypeNames(base(), false);

    /** DuckDB: bare DOUBLE, native JSON, STRUCT(...). */
    public static final TypeNames DUCKDB = new TypeNames(duck(), true);

    /** H2 2.1.214: standard names; JSON exists as a type but maps to
     * byte[] on read-back (codec row pending, H2_BACKEND.md step 11);
     * no STRUCT. HUGEINT has no H2 name — NUMERIC(38) (probed: cast
     * rounding and VARCHAR print parity with DuckDB HUGEINT on
     * 2.7/2.0/-3.9); JSON casts spell JSON (the R5a variant carrier). */
    public static final TypeNames H2 = new TypeNames(h2(), false);

    /** PostgreSQL 16+ (2026-10-01 W5.5/P1 Postgres dialect): DOUBLE PRECISION (a bare
     * {@code DOUBLE} is no Postgres type); HUGEINT as NUMERIC(38); JSON as JSONB, the
     * collection and variant carrier (POSTGRES_BACKEND.md §5); no STRUCT. */
    public static final TypeNames POSTGRES = new TypeNames(postgres(), false);

    /** Snowflake (2026-10-11): NUMBER(38,0) is every integer (INTEGER and BIGINT are its synonyms); DOUBLE is
     *  FLOAT; a timestamp is TIMESTAMP_NTZ (the platform's naive UTC) and a zoned one TIMESTAMP_LTZ (an instant, the
     *  session zone UTC); a variant is VARIANT; composite casts are the dialect's {@code arrayTypeName} and friends. */
    public static final TypeNames SNOWFLAKE = new TypeNames(snowflake(), true);

    private static Map<SqlType.Scalar, String> snowflake() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "BOOLEAN");
        m.put(SqlType.Scalar.INTEGER, "INTEGER");
        m.put(SqlType.Scalar.BIGINT, "BIGINT");
        m.put(SqlType.Scalar.HUGEINT, "NUMBER(38,0)");
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE");
        m.put(SqlType.Scalar.VARCHAR, "VARCHAR");
        m.put(SqlType.Scalar.LITERAL, "VARCHAR");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "VARCHAR");
        m.put(SqlType.Scalar.DATE, "DATE");
        m.put(SqlType.Scalar.TIMESTAMP, "TIMESTAMP_NTZ");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TIMESTAMP_LTZ");
        m.put(SqlType.Scalar.JSON, "VARIANT");
        return m;
    }

    /** BigQuery (GoogleSQL; 2026-10-11): INT64, FLOAT64, STRING, BOOL; HUGEINT as BIGNUMERIC (it holds every 128-bit
     *  integer); a naive TIMESTAMP is DATETIME, a zoned one TIMESTAMP; JSON; STRUCT natively. */
    public static final TypeNames BIGQUERY = new TypeNames(bigquery(), true);

    private static Map<SqlType.Scalar, String> bigquery() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "BOOL");
        m.put(SqlType.Scalar.INTEGER, "INT64");
        m.put(SqlType.Scalar.BIGINT, "INT64");
        m.put(SqlType.Scalar.HUGEINT, "BIGNUMERIC");
        m.put(SqlType.Scalar.DOUBLE, "FLOAT64");
        m.put(SqlType.Scalar.VARCHAR, "STRING");
        m.put(SqlType.Scalar.LITERAL, "STRING");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "STRING");
        m.put(SqlType.Scalar.DATE, "DATE");
        m.put(SqlType.Scalar.TIMESTAMP, "DATETIME");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TIMESTAMP");
        m.put(SqlType.Scalar.JSON, "JSON");
        return m;
    }

    /** Databricks SQL (2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md): INT and STRING (a bare VARCHAR needs a
     * length there); HUGEINT as DECIMAL(38,0); a zoned timestamp is the session-zone TIMESTAMP (the session is UTC);
     * JSON as VARIANT, the variant carrier; STRUCT natively ({@code STRUCT<a: T>}, the dialect's spelling). */
    public static final TypeNames DATABRICKS = new TypeNames(databricks(), true);

    private static Map<SqlType.Scalar, String> databricks() {
        Map<SqlType.Scalar, String> m = base();
        m.put(SqlType.Scalar.INTEGER, "INT");
        m.put(SqlType.Scalar.HUGEINT, "DECIMAL(38,0)");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TIMESTAMP");
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE");
        m.put(SqlType.Scalar.VARCHAR, "STRING");
        m.put(SqlType.Scalar.LITERAL, "STRING");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "STRING");
        m.put(SqlType.Scalar.JSON, "VARIANT");
        return m;
    }

    /** Trino (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §18): DOUBLE; HUGEINT as DECIMAL(38,0); a timestamp
     *  is TIMESTAMP(6) (a bare TIMESTAMP is TIMESTAMP(3), measured) and a zoned one TIMESTAMP(6) WITH TIME ZONE; JSON;
     *  STRUCT natively ({@code ROW(a T)}, the dialect's spelling). */
    public static final TypeNames TRINO = new TypeNames(trino(), true);

    private static Map<SqlType.Scalar, String> trino() {
        Map<SqlType.Scalar, String> m = base();
        m.put(SqlType.Scalar.HUGEINT, "DECIMAL(38,0)");
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE");
        m.put(SqlType.Scalar.TIMESTAMP, "TIMESTAMP(6)");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TIMESTAMP(6) WITH TIME ZONE");
        m.put(SqlType.Scalar.JSON, "JSON");
        return m;
    }

    /** ClickHouse (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19): every scalar NULLABLE (a ClickHouse value
     *  is NOT NULL unless its type says {@code Nullable(T)}, and {@code CAST(NULL AS Int64)} is refused); a timestamp
     *  is a DateTime64 of six digits in UTC, a date a Date32; HUGEINT as Int128; a Variant its JSON text; STRUCT as a
     *  named Tuple (the dialect's spelling). */
    public static final TypeNames CLICKHOUSE = new TypeNames(clickhouse(), true);

    private static Map<SqlType.Scalar, String> clickhouse() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "Nullable(Bool)");
        m.put(SqlType.Scalar.INTEGER, "Nullable(Int32)");
        m.put(SqlType.Scalar.BIGINT, "Nullable(Int64)");
        m.put(SqlType.Scalar.HUGEINT, "Nullable(Int128)");
        m.put(SqlType.Scalar.DOUBLE, "Nullable(Float64)");
        m.put(SqlType.Scalar.VARCHAR, "Nullable(String)");
        m.put(SqlType.Scalar.LITERAL, "Nullable(String)");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "Nullable(String)");
        m.put(SqlType.Scalar.DATE, "Nullable(Date32)");
        m.put(SqlType.Scalar.TIMESTAMP, "Nullable(DateTime64(6, 'UTC'))");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "Nullable(DateTime64(6, 'UTC'))");
        m.put(SqlType.Scalar.JSON, "Nullable(String)");
        return m;
    }

    /** SQLite (2026-10-11): a CAST target names an AFFINITY only (INTEGER, REAL, TEXT): every scalar's storage class.
     *  The dialect decides each cast itself (a DATE name is NUMERIC affinity there, measured); no STRUCT. */
    public static final TypeNames SQLITE = new TypeNames(sqlite(), false);

    private static Map<SqlType.Scalar, String> sqlite() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "INTEGER");
        m.put(SqlType.Scalar.INTEGER, "INTEGER");
        m.put(SqlType.Scalar.BIGINT, "INTEGER");
        m.put(SqlType.Scalar.HUGEINT, "INTEGER");
        m.put(SqlType.Scalar.DOUBLE, "REAL");
        m.put(SqlType.Scalar.VARCHAR, "TEXT");
        m.put(SqlType.Scalar.LITERAL, "TEXT");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "TEXT");
        m.put(SqlType.Scalar.DATE, "TEXT");
        m.put(SqlType.Scalar.TIMESTAMP, "TEXT");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TEXT");
        m.put(SqlType.Scalar.JSON, "TEXT");
        return m;
    }

    /** MariaDB (2026-10-11): the CAST targets MariaDB takes (SIGNED, DOUBLE, CHAR, DATE, DATETIME(6), DECIMAL);
     *  a boolean is an integer, a Variant its JSON text; no STRUCT. */
    public static final TypeNames MARIADB = new TypeNames(mariadb(), false);

    private static Map<SqlType.Scalar, String> mariadb() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "SIGNED");
        m.put(SqlType.Scalar.INTEGER, "SIGNED");
        m.put(SqlType.Scalar.BIGINT, "SIGNED");
        m.put(SqlType.Scalar.HUGEINT, "DECIMAL(38,0)");
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE");
        m.put(SqlType.Scalar.VARCHAR, "CHAR");
        m.put(SqlType.Scalar.LITERAL, "CHAR");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "CHAR");
        m.put(SqlType.Scalar.DATE, "DATE");
        m.put(SqlType.Scalar.TIMESTAMP, "DATETIME(6)");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "DATETIME(6)");
        m.put(SqlType.Scalar.JSON, "CHAR");
        return m;
    }

    private static Map<SqlType.Scalar, String> postgres() {
        Map<SqlType.Scalar, String> m = base();
        m.put(SqlType.Scalar.HUGEINT, "NUMERIC(38)");
        m.put(SqlType.Scalar.JSON, "JSONB");
        return m;
    }

    private static Map<SqlType.Scalar, String> h2() {
        Map<SqlType.Scalar, String> m = base();
        m.put(SqlType.Scalar.HUGEINT, "NUMERIC(38)");
        m.put(SqlType.Scalar.JSON, "JSON");
        return m;
    }

    private static Map<SqlType.Scalar, String> base() {
        Map<SqlType.Scalar, String> m = new EnumMap<>(SqlType.Scalar.class);
        m.put(SqlType.Scalar.BOOLEAN, "BOOLEAN");
        m.put(SqlType.Scalar.INTEGER, "INTEGER");
        m.put(SqlType.Scalar.BIGINT, "BIGINT");
        m.put(SqlType.Scalar.HUGEINT, "HUGEINT");
        m.put(SqlType.Scalar.TIMESTAMPTZ, "TIMESTAMPTZ");
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE PRECISION");
        m.put(SqlType.Scalar.VARCHAR, "VARCHAR");
        // the kind-faithful carriers are spelling TEXT on every backend
        m.put(SqlType.Scalar.LITERAL, "VARCHAR");
        m.put(SqlType.Scalar.TEMPORAL_TEXT, "VARCHAR");
        m.put(SqlType.Scalar.DATE, "DATE");
        m.put(SqlType.Scalar.TIMESTAMP, "TIMESTAMP");
        return m;
    }

    private static Map<SqlType.Scalar, String> duck() {
        Map<SqlType.Scalar, String> m = base();
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE");
        m.put(SqlType.Scalar.JSON, "JSON");
        return m;
    }
}
