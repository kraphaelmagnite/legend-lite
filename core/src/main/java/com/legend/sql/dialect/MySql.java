// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;

import java.util.List;

/**
 * The MySQL EXECUTION dialect (2026-10-11, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §14): {@link MariaDb}'s where the two
 * agree, its hooks spelled as MySQL 8.4 needs them. Every difference was measured on MySQL 8.4 (the pinned server,
 * tools/mysql) through MariaDB Connector/J:
 * <ul>
 *   <li>{@code JSON_ARRAYAGG} takes no ORDER BY or DISTINCT: an ordered array is GROUP_CONCAT's, each value wrapped
 *       in a one-element array and the wrapping read away ({@code $[*][0]});</li>
 *   <li>no {@code JSON_COMPACT}: a JSON value is normalized by its CAST to JSON (MySQL stores JSON parsed: an object's
 *       keys sorted, its text spaced);</li>
 *   <li>no negative JSON array index: {@code $[last-n]};</li>
 *   <li>regular expressions are ICU's: a replacement names a group {@code $n}, and REGEXP_REPLACE takes the
 *       occurrence to replace;</li>
 *   <li>a derived table and a recursive CTE may read an outer row, and LATERAL exists;</li>
 *   <li>the session: no {@code max_recursive_iterations} ({@code cte_max_recursion_depth}).</li>
 * </ul>
 */
public final class MySql extends MariaDb {

    public MySql() {
        super(Lexicon.MARIADB, TypeNames.MARIADB, Spellings.MARIADB);
    }

    @Override
    protected String product() {
        return "MySQL";
    }

    @Override
    public List<String> sessionSetup() {
        // NO_UNSIGNED_SUBTRACTION: YEAR() and the like are unsigned here, and their difference is a signed one
        // (measured: "BIGINT UNSIGNED value is out of range" without it)
        return List.of("SET SESSION sql_mode = 'ANSI_QUOTES,PIPES_AS_CONCAT,NO_BACKSLASH_ESCAPES,STRICT_ALL_TABLES,"
                + "NO_ENGINE_SUBSTITUTION,NO_UNSIGNED_SUBTRACTION'", "SET SESSION time_zone = '+00:00'", "SET NAMES utf8mb4 COLLATE utf8mb4_bin",
                "SET SESSION group_concat_max_len = 1073741824", "SET SESSION cte_max_recursion_depth = 100000000",
                "SET SESSION div_precision_increment = 30");
    }

    @Override
    protected SqlWriter.Piece compact(SqlWriter.Piece json) {
        return w -> w.append("CAST(").piece(json).append(" AS JSON)");
    }

    /** {@code JSON_ARRAYAGG(value)} where no order is asked; else GROUP_CONCAT's ordered (or distinct) text of each
     *  value's one-element array, read as JSON and unwrapped: NULL over no rows, as JSON_ARRAYAGG's. */
    @Override
    protected SqlWriter.Piece arrayAgg(boolean distinct, SqlWriter.Piece value, SqlWriter.@com.legend.base.Nullable Piece order,
            boolean firstOnly) {
        if (order == null && !distinct) {
            return w -> w.append("JSON_ARRAYAGG(").piece(value).append(")");
        }
        return w -> {
            w.append("JSON_EXTRACT(CAST(CONCAT('[', GROUP_CONCAT(").append(distinct ? "DISTINCT " : "")
                    .append("JSON_ARRAY(").piece(value).append(")");
            if (order != null) {
                w.append(" ORDER BY ").piece(order);
            }
            w.append(" SEPARATOR ','), ']') AS JSON), '$[*][0]')");
        };
    }

    @Override
    protected SqlWriter.Piece indexPath(SqlWriter.Piece index) {
        return w -> w.append("(CASE WHEN (").piece(index).append(") < 0 THEN CONCAT('$[last-', -(").piece(index)
                .append(") - 1, ']') ELSE CONCAT('$[', ").piece(index).append(", ']') END)");
    }

    /** {@code JSON_VALUE} takes only a literal path here and answers in another collation (utf8mb4_0900_bin, an
     *  "Illegal mix" with the session's) as VARCHAR(512) (measured): a value at a path is {@code JSON_EXTRACT}'s,
     *  unquoted, a JSON null NULL. */
    @Override
    protected SqlWriter.Piece jsonText(SqlWriter.Piece json, SqlWriter.Piece path) {
        return w -> w.append("(CASE WHEN JSON_TYPE(JSON_EXTRACT(").piece(json).append(", ").piece(path)
                .append(")) = 'NULL' THEN NULL ELSE JSON_UNQUOTE(JSON_EXTRACT(").piece(json).append(", ").piece(path)
                .append(")) END)");
    }

    @Override
    protected String jsonText(String json, String path) {
        return "(CASE WHEN JSON_TYPE(JSON_EXTRACT(" + json + ", " + path + ")) = 'NULL' THEN NULL ELSE JSON_UNQUOTE("
                + "JSON_EXTRACT(" + json + ", " + path + ")) END)";
    }

    /** A table function's argument may not assign a variable here ("Incorrect arguments to JSON_TABLE", measured): the
     *  two rows are a UNION's, its first assigning, evaluated only where reached (measured). */
    @Override
    protected SqlWriter.Piece twoRows(SqlWriter.Piece message) {
        return w -> w.append("(SELECT @legend_raise := ").piece(message).append(" UNION ALL SELECT 0)");
    }

    /**
     * printf with no SFORMAT here: the format's literal pieces and each conversion written out, concatenated -- {@code
     * %s} the value's text, {@code %d} an integer padded to its width (zeros after a sign with the {@code 0} flag,
     * spaces before it, or after it with {@code -}), {@code %.Nf} a DECIMAL of scale N (6 by default), {@code %x} and
     * {@code %o} its digits in base 16 and 8; any other conversion is a wall.
     */
    @Override
    protected SqlWriter format(SqlWriter writer, List<SqlExpr> a) {
        String f = literal(a.get(0), "a format");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([-+ #0]*)([0-9]*)(?:\\.([0-9]+))?([a-zA-Z%])")
                .matcher(f);
        List<SqlWriter.Piece> parts = new java.util.ArrayList<>();
        int arg = 1;
        int last = 0;
        StringBuilder literal = new StringBuilder();
        while (m.find()) {
            literal.append(f, last, m.start());
            last = m.end();
            char conversion = m.group(4).charAt(0);
            if (conversion == '%') {
                literal.append('%');
                continue;
            }
            if (arg >= a.size()) {
                throw new DialectCapability("a format with more conversions than arguments reached MySQL");
            }
            String text = literal.toString();
            parts.add(w -> w.append(stringLit(text)));
            literal.setLength(0);
            parts.add(conversion(a.get(arg++), conversion, m.group(1), m.group(2), m.group(3)));
        }
        literal.append(f.substring(last));
        String tail = literal.toString();
        parts.add(w -> w.append(stringLit(tail)));
        return writer.append("CONCAT(").join(parts, ", ", SqlWriter::piece).append(")");
    }

    private SqlWriter.Piece conversion(SqlExpr x, char conversion, String flags, String width,
            @com.legend.base.Nullable String precision) {
        SqlWriter.Piece digits = switch (conversion) {
            case 's' -> asText(x);
            case 'd', 'i' -> w -> w.append("CAST(CAST(").expr(x, 0).append(" AS SIGNED) AS CHAR)");
            // a negative value that rounds to zero keeps its sign ('-0.00', as Java's and DuckDB's printf): a DECIMAL
            // zero has none (measured 2026-10-11, testFormatFloatWithRounding)
            case 'f', 'F' -> {
                String scale = precision == null ? "6" : precision;
                yield w -> w.append("CONCAT(CASE WHEN ").expr(x, 4).append(" < 0 AND CAST(").expr(x, 0)
                        .append(" AS DECIMAL(65," + scale + ")) = 0 THEN '-' ELSE '' END, CAST(CAST(").expr(x, 0)
                        .append(" AS DECIMAL(65," + scale + ")) AS CHAR))");
            }
            case 'x' -> w -> w.append("LOWER(CONV(").expr(x, 0).append(", 10, 16))");
            case 'X' -> w -> w.append("CONV(").expr(x, 0).append(", 10, 16)");
            case 'o' -> w -> w.append("CONV(").expr(x, 0).append(", 10, 8)");
            default -> throw new DialectCapability("printf conversion %" + conversion + " reached MySQL");
        };
        boolean plus = flags.contains("+") && (conversion == 'd' || conversion == 'i' || conversion == 'f');
        SqlWriter.Piece signed = !plus ? digits : w -> w.append("CONCAT(CASE WHEN ").expr(x, 4)
                .append(" >= 0 THEN '+' ELSE '' END, ").piece(digits).append(")");
        if (width.isEmpty()) {
            return signed;
        }
        String n = width;
        if (flags.contains("-")) {
            return w -> w.append("RPAD(").piece(signed).append(", GREATEST(" + n + ", CHAR_LENGTH(").piece(signed)
                    .append(")), ' ')");
        }
        if (flags.contains("0") && conversion != 's') {
            // the zeros after the sign: -5 as %05d is -0005
            return w -> w.append("(CASE WHEN LEFT(").piece(signed).append(", 1) IN ('-', '+') THEN CONCAT(LEFT(")
                    .piece(signed).append(", 1), LPAD(SUBSTRING(").piece(signed).append(", 2), GREATEST(" + n
                            + " - 1, CHAR_LENGTH(").piece(signed).append(") - 1), '0')) ELSE LPAD(").piece(signed)
                    .append(", GREATEST(" + n + ", CHAR_LENGTH(").piece(signed).append(")), '0') END)");
        }
        return w -> w.append("LPAD(").piece(signed).append(", GREATEST(" + n + ", CHAR_LENGTH(").piece(signed)
                .append(")), ' ')");
    }

    /** A grouped mode is counted too: a table function's argument may be no aggregate here ("Incorrect arguments to
     *  JSON_TABLE", measured), so MariaDB's array of the group's values cannot be read. */
    /** A bare integer past a signed BIGINT that fits an unsigned one is a BIGINT UNSIGNED here, and its arithmetic
     *  stays one (9223372036854775898 - 132 reads BIGINT, measured 2026-10-11 by the census): a HUGEINT literal is a
     *  DECIMAL, as MariaDB delivers it. */
    @Override
    protected String hugeIntLiteral(java.math.BigDecimal v) {
        return "CAST(" + v.toPlainString() + " AS DECIMAL(38,0))";
    }

    /** YEAR() answers in MySQL's own YEAR type here, which the driver reads as a date (measured 2026-10-11: testYear's
     *  "StrictDate cannot be cast to Integer", the census's BIGINT label over a YEAR column): an integer by its cast. */
    @Override
    protected SqlWriter.Piece yearOf(SqlExpr t) {
        return w -> w.append("CAST(YEAR(").expr(t, 0).append(") AS SIGNED)");
    }

    /** A JSON value's text puts a space after every ',' and ':' outside a string here, and only there (MySQL stores
     *  JSON parsed; measured 2026-10-11 by channel B: a Variant's text '[1, 2, 3]' where Pure writes '[1,2,3]'): a
     *  string-aware replace drops exactly those, as Postgres's (a string, with its escapes, is matched whole and kept).
     *  Only where a JSON value BECOMES text: inside JSON_ARRAY or JSON_OBJECT the value stays JSON. */
    @Override
    protected SqlWriter.Piece jsonAsText(SqlWriter.Piece json) {
        return w -> w.append("REGEXP_REPLACE(CAST(").piece(json).append(" AS CHAR), ")
                .append(stringLit("(\"(?:[^\"\\\\]|\\\\.)*\")|([,:]) ")).append(", '$1$2')");
    }

    @Override
    protected boolean groupedModesCounted() {
        return true;
    }

    @Override
    protected String groupRef(int n) {
        return "$" + n;
    }

    /** A JSON_TABLE BOOLEAN column is a BIT here (measured); an INT reads a JSON true as 1. */
    @Override
    protected String booleanColumn() {
        return "INT";
    }

    /** No PERCENTILE_CONT, PERCENTILE_DISC or MEDIAN here (measured): a whole partition's median or quantile is read
     *  from each row's rank (MariaDb.rankedQuantile, 2026-10-11); an ordered or framed one is a wall; the grouped ones
     *  are MariaDB's (a sorted array, indexed). */
    @Override
    protected boolean windowQuantilesNative() {
        return false;
    }

    @Override
    protected SqlWriter windowCall(SqlWriter writer, SqlExpr.WindowCall w) {
        if (w.fn() instanceof SqlAgg.Reducer r && (r.fn() == SqlAgg.Fn.MEDIAN || r.fn() == SqlAgg.Fn.QUANTILE_CONT
                || r.fn() == SqlAgg.Fn.QUANTILE_DISC) && (!w.orderBy().isEmpty() || w.frame() != null)) {
            throw new DialectCapability("a windowed " + r.fn() + " over an ordered or framed window reached MySQL,"
                    + " which has no PERCENTILE_CONT");
        }
        return super.windowCall(writer, w);
    }

    /** A table function's argument may hold no subquery here ("Incorrect arguments to JSON_TABLE", measured), and a
     *  list operation's is often one: the argument is bound first, as the one column of a LATERAL derived table (which
     *  may read the outer row, measured), and the table function reads that column. */
    @Override
    protected SqlWriter.Piece jsonTable(SqlWriter.Piece json, String spec, String alias) {
        String bound = ident(alias.replace("\"", "") + "__j");
        return w -> w.append("(LATERAL (SELECT ").piece(json).append(" AS \"j\") AS " + bound + " CROSS JOIN JSON_TABLE("
                + bound + ".\"j\", " + spec + ") AS " + alias + ")");
    }

    @Override
    protected boolean jsonIsBinary() {
        return true;
    }

    @Override
    protected boolean correlatesDerived() {
        return true;
    }

    /** DuckDB's regexp_replace(s, p, r[, options]): the first match unless the options say 'g' (ICU's REGEXP_REPLACE
     *  takes the occurrence: 1, or 0 for each); the replacement's {@code \N} is ICU's {@code $N}, and a literal
     *  {@code $} or backslash is escaped. */
    @Override
    protected SqlWriter.Piece regexpReplace(List<SqlExpr> a) {
        String p = literal(a.get(1), "a regexp_replace pattern");
        String r = literal(a.get(2), "a regexp_replace replacement");
        String options = a.size() > 3 ? literal(a.get(3), "regexp_replace options") : "";
        boolean global = false;
        String flags = "";
        for (char o : options.toCharArray()) {
            switch (o) {
                case 'g' -> global = true;
                case 'i' -> flags = "(?i)";
                case 'c' -> flags = "";
                default -> throw new DialectCapability("regexp_replace option '" + o + "' reached MySQL");
            }
        }
        StringBuilder icu = new StringBuilder();
        for (int i = 0; i < r.length(); i++) {
            char ch = r.charAt(i);
            if (ch == '\\' && i + 1 < r.length() && Character.isDigit(r.charAt(i + 1))) {
                icu.append('$').append(r.charAt(++i));
            } else if (ch == '\\' && i + 1 < r.length()) {
                icu.append('\\').append(r.charAt(++i));
            } else if (ch == '$' || ch == '\\') {
                icu.append('\\').append(ch);
            } else {
                icu.append(ch);
            }
        }
        String pat = flags + p;
        String occurrence = global ? "0" : "1";
        return w -> w.append("REGEXP_REPLACE(").expr(a.get(0), 0).append(", " + stringLit(pat) + ", "
                + stringLit(icu.toString()) + ", 1, " + occurrence + ")");
    }
}
