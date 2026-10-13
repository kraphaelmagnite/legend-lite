package com.legend.sql.dialect;

import com.legend.sql.SqlFn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remediation T3.2 &mdash; the classification ledger that replaces the
 * switch's compile-time exhaustiveness after the pure spellings moved to
 * DATA: every {@code SqlFn} is either a {@link Spellings} row or a CODED
 * rendering rule. A new SqlFn fails here until classified &mdash; never a
 * silent fall-through (the writer's default is a loud wall).
 */
class SpellingsTest {

    /** Fns rendered by CODE (infix, shape logic, composites, idiom points). */
    private static final Set<SqlFn> CODED = Set.of(
            SqlFn.PARSE_DATE,
            // day-grained truncation delivers a DATE (§8.3a carrier
            // burn, dialect-owned): the base arm casts where this
            // backend's date_trunc returns TIMESTAMP; engine-text
            // renderers spell their engines verbatim — an idiom
            // point, not a data row
            SqlFn.DATE_TRUNC,
            // interleaved joinStrings unit — function-form concat in the
            // base renderer, infix chain in DB2; never flatten-spliced
            SqlFn.CONCAT_JOIN,
            // signed-64 conformance wrap (pure hashCode is Integer[1];
            // DuckDB hash() is UBIGINT) — the hashSigned dialect arm
            SqlFn.HASH,
            // struct_insert(s, name := v): named-argument syntax, coded per dialect
            SqlFn.STRUCT_INSERT,
            SqlFn.BOOL_TO_TEXT,
            // a Float's literal spelling: a decomposition over the double's text, coded per dialect (2026-10-11)
            SqlFn.FLOAT_CANON,
            SqlFn.ADD_INTERVAL,
            SqlFn.ADD_INTERVAL_TEMPORAL,
            SqlFn.AND,
            SqlFn.BIT_AND,
            SqlFn.BIT_NOT,
            SqlFn.BIT_OR,
            SqlFn.BIT_SHIFT_LEFT,
            SqlFn.BIT_SHIFT_RIGHT,
            SqlFn.BIT_XOR,
            SqlFn.CEILING,
            SqlFn.CONCAT,
            SqlFn.CURRENT_USER_FN,
            SqlFn.DATE_TRUNC_DAY,
            SqlFn.DECODE_BASE64,
            SqlFn.DIVIDE,
            SqlFn.ENCODE_BASE64,
            SqlFn.EQUAL,
            SqlFn.FLOOR,
            SqlFn.FROM_EPOCH_MS,
            SqlFn.GREATER,
            SqlFn.GREATER_EQUAL,
            SqlFn.GUID,
            SqlFn.IN,
            SqlFn.INT_DIVIDE,
            SqlFn.IS_DISTINCT_FROM,
            SqlFn.NULL_SAFE_EQUAL,
            SqlFn.NULL_SAFE_NOT_EQUAL,
            SqlFn.IS_NOT_NULL,
            SqlFn.IS_NULL,
            SqlFn.JSON_MERGE_PATCH,
            SqlFn.LC_FIRST,
            SqlFn.LESS,
            SqlFn.LESS_EQUAL,
            // D6: 1-arg collection isDistinct — dialect-coded
            // expansion (DuckDb len(list_distinct)=len; base walls)
            SqlFn.ALL_DISTINCT,
            SqlFn.LIST_APPEND,
            SqlFn.LIST_AVG,
            SqlFn.LIST_BOOL_AND,
            SqlFn.LIST_BOOL_OR,
            SqlFn.LIST_CONCAT,
            SqlFn.LIST_DISTINCT,
            SqlFn.LIST_EXISTS,
            SqlFn.LIST_FILTER,
            SqlFn.PURE_SPLIT_PART,  // splitPartCall: dialect idiom point (batch 139)
            SqlFn.LIST_FOR_ALL,
            SqlFn.LIST_GET,
            SqlFn.LIST_INIT,
            SqlFn.LIST_MAX,
            SqlFn.LIST_MEDIAN,
            SqlFn.LIST_MIN,
            SqlFn.LIST_MODE,
            SqlFn.LIST_POSITION,
            SqlFn.LIST_PRODUCT,
            SqlFn.LIST_REDUCE,
            SqlFn.LIST_REVERSE,
            SqlFn.LIST_SLICE,
            SqlFn.LIST_SORT,
            SqlFn.LIST_SORT_DESC,
            SqlFn.LIST_SUM,
            SqlFn.LIST_TAIL,
            SqlFn.LIST_TRANSFORM,
            SqlFn.LIST_ZIP,
            SqlFn.LPAD,
            SqlFn.MAKE_TIMESTAMP,
            SqlFn.MAP_EMPTY,
            SqlFn.MINUS,
            SqlFn.MOD,
            SqlFn.NEGATE,
            SqlFn.NOT,
            SqlFn.NOT_EQUAL,
            SqlFn.NOW,
            SqlFn.OR,
            SqlFn.PI,
            SqlFn.PLUS,
            SqlFn.RANGE_FN, SqlFn.REPEAT_VALUE,
            SqlFn.REM,
            SqlFn.ROUND,
            SqlFn.ROUND_HALF_UP,
            SqlFn.RPAD,
            SqlFn.SIGN,
            SqlFn.TIMES,
            SqlFn.TIME_BUCKET,
            SqlFn.TODAY,
            SqlFn.TO_VARIANT,
            SqlFn.TYPEOF,
            SqlFn.UC_FIRST,
            SqlFn.UNNEST,
            SqlFn.VARIANT_ELEMENTS,
            SqlFn.VARIANT_GET,
            SqlFn.XOR, SqlFn.PARSE_INT);

    @Test
    @DisplayName("every SqlFn is classified: spelling DATA or coded rule")
    void everySqlFnClassified() {
        for (SqlFn f : SqlFn.values()) {
            assertTrue(Spellings.DUCKDB.fnNames().containsKey(f)
                            || CODED.contains(f),
                    f + " is unclassified — add a Spellings row or a coded"
                    + " rule (and list it in CODED)");
            assertFalse(Spellings.DUCKDB.fnNames().containsKey(f)
                            && CODED.contains(f),
                    f + " is BOTH data and coded — pick one");
        }
    }
}
