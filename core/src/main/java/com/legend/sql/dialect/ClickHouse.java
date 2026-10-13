// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlAgg;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * The ClickHouse EXECUTION dialect (ClickHouse 25.8, its Memory engine; 2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md
 * §19). A sibling of {@link Trino} over {@link AnsiSqlRenderer}: it changes nothing the other dialects render. Every
 * spelling here was measured on the server before it was trusted.
 *
 * <p>ClickHouse has DuckDB's lists (typed {@code Array(T)}, {@code arrayMap}, {@code arrayFilter}, {@code arrayFold},
 * {@code arrayReduce}, a set-returning {@code arrayJoin}), QUALIFY, {@code SELECT * EXCEPT} and a native ASOF JOIN. What
 * differs, measured:
 * <ul>
 *   <li>its SQL semantics are SETTINGS, off by default: an outer join's missing side is its type's default (0, '')
 *       unless {@code join_use_nulls}, an aggregate over no row is 0 unless spelled {@code sumOrNull}, a bare UNION is
 *       refused unless {@code union_default_mode}. The JDBC driver speaks HTTP with no session (a
 *       {@code SET} does not outlive its statement), so every statement carries them, in its own SETTINGS clause
 *       ({@link #query}); a SETTINGS clause reaches every subquery and branch of its statement (measured);</li>
 *   <li>a value is NOT NULL unless its type is {@code Nullable(T)}: every cast names the nullable type, and a list is
 *       never NULL ({@code Nullable(Array)} is refused), so the empty collection is the empty array;</li>
 *   <li>a literal is typed by its magnitude (1 is UInt8, arithmetic widening and wrapping): an integer literal is an
 *       Int64 ({@code toInt64(1)}), a Float literal an exact Decimal (Pure's numeric charter; {@code 1.5} alone is a
 *       Float64);</li>
 *   <li>a string literal reads backslash escapes;</li>
 *   <li>{@code round} on a Float is half-even, Pure's (on a Decimal half away from zero: {@code roundBankers} there);
 *       {@code lag} and {@code lead} answer the type's default past the frame ({@code lagInFrame} of a nullable value
 *       over the whole partition answers NULL);</li>
 *   <li>no raise with a computed message ({@code throwIf} takes a constant one): a failed cast of the message's text
 *       raises it, the platform's U+001F markers around it, verbatim in the server's message.</li>
 * </ul>
 */
public final class ClickHouse extends AnsiSqlRenderer {

    /** The settings every statement carries: SQL's NULLs for an outer join's missing side, a bare UNION distinct, a cast
     *  keeping its value's NULL, the zone UTC (the platform's naive-UTC contract). An aggregate's NULL over no row is
     *  each aggregate's own {@code OrNull} form ({@link #aggregateName}): {@code aggregate_functions_null_for_empty}
     *  makes {@code groupArray} a NULLABLE array, which ClickHouse refuses (measured). And a CASE's branch runs only
     *  where its guard holds: the planner's merge of a subquery's expressions into its reader's drops that (a guarded
     *  raise read through an outer {@code if} raised on every row, measured on 25.8), so the merge is off. */
    static final String SETTINGS = "SETTINGS join_use_nulls = 1, union_default_mode = 'DISTINCT',"
            + " cast_keep_nullable = 1, session_timezone = 'UTC', query_plan_merge_expressions = 0";

    /** How deep the writer is in {@link #query}: the root query (depth 0 on entry) writes the SETTINGS clause. */
    private final int[] nesting = {0};

    public ClickHouse() {
        super(Lexicon.CLICKHOUSE, TypeNames.CLICKHOUSE, Spellings.CLICKHOUSE);
    }

    // ==================================================================
    // Session and structure
    // ==================================================================

    /** No session: the driver speaks HTTP, statement by statement; the settings ride each statement. */
    @Override
    public List<String> sessionSetup() {
        return List.of();
    }

    /** No native PIVOT: a static pivot is a grouped CASE aggregation, its values discovered first. */
    @Override
    public boolean needsStaticPivot() {
        return true;
    }

    /** Native lists (the carrier strategies stand aside); a static pivot as a grouped CASE aggregation; the substring
     *  start clamp; averages delivered as DOUBLE; a constant GROUP BY or ORDER BY key as a typed expression; the root's
     *  DECIMAL(p,s) and HUGEINT columns cast to their declared types (a quantile is a Float64 here). QUALIFY, the star
     *  exclusion and the as-of join are native. */
    @Override
    protected List<com.legend.sql.SqlRewriter> passes() {
        return List.of(new CarrierStrategies(CarrierStrategies.Caps.CLICKHOUSE), new PivotAsGroupBy(),
                new ExplosionMarkers.LateralUnnestAsMarker(), new ExplosionMarkers.StarOverExplosionUnwrapped(),
                new ArrayJoinColumnsNamed(), new CheckedDefectsToLists(), new SubstringClamp(),
                new AggregatesDeliverDouble(java.util.Set.of(SqlAgg.Fn.AVG)), new ConstantKeysAsExpressions(),
                new RootNumericTypes());
    }

    /**
     * A lateral explosion MARKER (ExplosionMarkers; ClickHouse has no LATERAL join) is an {@code ARRAY JOIN} of its
     * lists ({@code LEFT ARRAY JOIN} keeping an empty list's row, its columns NULL), each exploded column named
     * {@code "alias.column"}, which a reference {@code "alias"."column"} reads (measured; a star names them,
     * {@link ArrayJoinColumnsNamed}). Several lists are padded with
     * NULL to the longest: ARRAY JOIN aligns lists of one size only.
     */
    @Override
    protected SqlWriter source(SqlWriter writer, com.legend.sql.SqlSource src, int depth) {
        if (src instanceof com.legend.sql.SqlSource.Join j && j.right() instanceof com.legend.sql.SqlSource.Subselect sub
                && ExplosionMarkers.isMarker(sub.inner())
                && (j.kind() == com.legend.sql.SqlSource.Join.Kind.CROSS_LATERAL
                        || j.kind() == com.legend.sql.SqlSource.Join.Kind.LEFT_LATERAL)) {
            if (j.on() != null && !(j.on() instanceof SqlExpr.BoolLit b && b.value())) {
                throw new DialectCapability("a lateral explosion with a join condition reached ClickHouse");
            }
            List<SqlSelect.Projection> ps = ((SqlSelect) sub.inner()).projections();
            List<SqlExpr> lists = ps.stream().map(p -> ((SqlExpr.Call) p.expr()).args().get(0)).toList();
            source(writer, j.left(), depth);
            nl(writer, depth).append(j.kind() == com.legend.sql.SqlSource.Join.Kind.LEFT_LATERAL ? "LEFT ARRAY JOIN "
                    : "ARRAY JOIN ");
            SqlWriter.Piece n = w -> w.append("greatest(").join(lists, ", ",
                    (v, l) -> v.append("length(").piece(list(l)).append(")")).append(")");
            for (int i = 0; i < ps.size(); i++) {
                SqlExpr l = lists.get(i);
                writer.append(i > 0 ? ", " : "");
                if (lists.size() == 1) {
                    writer.piece(list(l));
                } else {
                    writer.append("arrayResize(arrayMap(\"__z\" -> toNullable(\"__z\"), ").piece(list(l))
                            .append("), ").piece(n).append(", NULL)");
                }
                writer.append(" AS ").append(aliasIdent(sub.alias() + "." + ExplosionMarkers.markerColumn(ps.get(i))));
            }
            return writer;
        }
        if (src instanceof com.legend.sql.SqlSource.Join j && (j.kind() == com.legend.sql.SqlSource.Join.Kind.CROSS_LATERAL
                || j.kind() == com.legend.sql.SqlSource.Join.Kind.LEFT_LATERAL)) {
            throw new DialectCapability("a LATERAL join reached ClickHouse, which has none (a source correlated with"
                    + " the one before it)");
        }
        return super.source(writer, src, depth);
    }

    /**
     * ClickHouse's {@code *} does not include an ARRAY JOIN's columns (measured), so a select over a lateral explosion
     * names them after its star, each labeled with its own name: an enclosing select reads {@code "_pct0"."key"}. (Within
     * the select an exploded column is {@code "t1.key"}, which {@code "t1"."key"} reads, {@link #source}: two
     * explosions in one select may name their columns alike.)
     */
    static final class ArrayJoinColumnsNamed extends com.legend.sql.SqlRewriter {
        @Override
        protected SqlQuery select(SqlSelect s) {
            java.util.Map<String, List<SqlSelect.Projection>> named = new java.util.LinkedHashMap<>();
            markerAliases(s.from(), named);
            if (named.isEmpty()) {
                return s;
            }
            named.replaceAll((alias, ps) -> ps.stream().map(p -> new SqlSelect.Projection(
                    SqlExpr.Column.derived(alias, ExplosionMarkers.markerColumn(p)), ExplosionMarkers.markerColumn(p),
                    p.out())).toList());
            List<SqlSelect.Projection> all = named.values().stream().flatMap(List::stream).toList();
            List<SqlSelect.Projection> ps = new ArrayList<>();
            if (s.projections().isEmpty()) {
                ps.add(new SqlSelect.Projection(new SqlExpr.Star(null), null, null));
                ps.addAll(all);
            }
            for (SqlSelect.Projection p : s.projections()) {
                if (p.expr() instanceof SqlExpr.Star st && st.table() != null && named.containsKey(st.table())) {
                    ps.addAll(named.get(st.table()));
                } else if (p.expr() instanceof SqlExpr.Star st && st.table() == null) {
                    ps.add(p);
                    ps.addAll(all);
                } else if (p.expr() instanceof SqlExpr.StarExcept se) {
                    throw new DialectCapability("a star exclusion over a lateral explosion reached ClickHouse: " + se);
                } else {
                    ps.add(p);
                }
            }
            return new SqlSelect(ps, s.distinct(), s.from(), s.where(), s.groupBy(), s.having(), s.qualify(),
                    s.orderBy(), s.limit(), s.offset(), s.outputs());
        }

        private static void markerAliases(SqlSource src, java.util.Map<String, List<SqlSelect.Projection>> into) {
            if (src instanceof SqlSource.Join j) {
                markerAliases(j.left(), into);
                if (j.right() instanceof SqlSource.Subselect sub && ExplosionMarkers.isMarker(sub.inner())
                        && (j.kind() == SqlSource.Join.Kind.CROSS_LATERAL
                                || j.kind() == SqlSource.Join.Kind.LEFT_LATERAL)) {
                    into.put(sub.alias(), ((SqlSelect) sub.inner()).projections());
                } else {
                    markerAliases(j.right(), into);
                }
            }
        }
    }

    /** The root statement closes with its SETTINGS clause (the writer's one statement). */
    @Override
    protected SqlWriter query(SqlWriter writer, SqlQuery q, int depth) {
        boolean root = nesting[0] == 0;
        nesting[0]++;
        try {
            super.query(writer, q, depth);
        } finally {
            nesting[0]--;
        }
        return root ? nl(writer, 0).append(SETTINGS) : writer;
    }

    @Override
    protected boolean supportsQualify() {
        return true;
    }

    @Override
    protected SqlWriter appendQualify(SqlWriter writer, SqlSelect s, int depth) {
        return nl(writer, depth).append("QUALIFY ").expr(java.util.Objects.requireNonNull(s.qualify(), "qualify"), 0);
    }

    @Override
    protected String starExceptKeyword() {
        return "EXCEPT";
    }

    /** An inline table: the {@code values} table function, its structure the columns' nullable types (ClickHouse has
     *  no {@code VALUES ... AS t(cols)}; a UNION ALL of selects would not keep the rows' order, measured). */
    @Override
    protected SqlWriter valuesSource(SqlWriter writer, com.legend.sql.SqlSource.Values v) {
        if (v.outputs().size() != v.columns().size()) {
            throw new DialectCapability("a VALUES source without a declared type per column reached ClickHouse");
        }
        StringBuilder structure = new StringBuilder();
        for (int i = 0; i < v.columns().size(); i++) {
            structure.append(i > 0 ? ", " : "").append('`').append(v.columns().get(i).replace("`", "\\`"))
                    .append("` ").append(castTypeName(valuesType(v, i)));
        }
        writer.append("values(").append(stringLit(structure.toString()));
        for (List<SqlExpr> row : v.rows()) {
            writer.append(", (").list(row).append(")");
        }
        return writer.append(") AS ").append(aliasIdent(v.alias()));
    }

    /** A VALUES column's type, declared here (DuckDB's VALUES types a column by its rows): a Decimal widened to its
     *  literals' largest scale (a Number column is labeled DECIMAL(38,0) and holds 101.101, which the declared type
     *  would truncate, measured). */
    private static SqlType valuesType(com.legend.sql.SqlSource.Values v, int column) {
        SqlType t = v.outputs().get(column).type();
        if (!(t instanceof SqlType.Decimal d)) {
            return t;
        }
        int scale = d.scale();
        for (List<SqlExpr> row : v.rows()) {
            if (typeOf(row.get(column)) instanceof SqlType.Decimal cell) {
                scale = Math.max(scale, cell.scale());
            }
        }
        return scale == d.scale() ? t : new SqlType.Decimal(38, scale);
    }

    @Override
    protected String rowOrderColumn() {
        throw new DialectCapability("row order reached ClickHouse, which has no row-order column");
    }

    /** ClickHouse's own as-of join: the ON's equalities and its one inequality, the right side's key on its right. */
    @Override
    protected String asOfJoinClause() {
        return "ASOF LEFT JOIN";
    }

    @Override
    protected SqlWriter pivotSource(SqlWriter writer, com.legend.sql.SqlSource.Pivot p, int depth) {
        throw new IllegalStateException("a pivot reached ClickHouse's writer: PivotAsGroupBy writes it as a grouped"
                + " aggregation (a dynamic pivot is discovered first)");
    }

    // ==================================================================
    // Lexical: identifiers, literals, types
    // ==================================================================

    /** Every identifier quoted (ClickHouse's are case-sensitive); a double-quoted declaration is its own spelling. */
    @Override
    protected String ident(String name) {
        if (name.length() > 1 && name.charAt(0) == '"' && name.endsWith("\"")
                && !name.substring(1, name.length() - 1).replace("\"\"", "").contains("\"")) {
            return name;
        }
        return delimited(name);
    }

    /** A backslash and a quote escaped by a backslash: a string literal reads backslash escapes (measured). */
    @Override
    protected String stringLit(String value) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\u0000' -> sb.append("\\0");
                default -> sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    @Override
    protected String dateLit(String iso) {
        return "toDate32('" + inRange(iso) + "')";
    }

    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile("^(-?\\d+)-");

    /** A date's text, refused outside ClickHouse's dates (Date32 and DateTime64 hold 1900 to 2299; a date past either
     *  end is clamped to it, silently, measured). */
    private static String inRange(String iso) {
        java.util.regex.Matcher m = YEAR.matcher(iso);
        if (!m.find()) {
            throw new IllegalStateException("a date literal with no year reached ClickHouse: " + iso);
        }
        int year = Integer.parseInt(m.group(1));
        if (year < 1900 || year > 2299) {
            throw new DialectCapability("the date " + iso + " reached ClickHouse, whose dates are 1900 to 2299 (it"
                    + " clamps a date past either end)");
        }
        return iso;
    }

    /** A naive UTC date-time of six digits (finer ones cut, as DuckDB's TIMESTAMP does); a {@code T} is a space. */
    @Override
    protected String timestampLit(String iso) {
        String t = iso.replaceFirst("T", " ");
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            int end = dot + 1;
            while (end < t.length() && Character.isDigit(t.charAt(end))) {
                end++;
            }
            if (end - dot - 1 > 6) {
                t = t.substring(0, dot + 7) + t.substring(end);
            }
        }
        return "toDateTime64('" + inRange(t) + "', 6, 'UTC')";
    }

    /** NUMERIC CHARTER Rule 1: a Float literal is an exact Decimal of its own digits ({@code 1.5} alone is a Float64
     *  here); NaN and the infinities are Float64. */
    @Override
    protected String floatLiteral(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        java.math.BigDecimal plain = com.legend.sql.SqlTyping.floatDecimal(v);
        return plain != null ? decimal(plain) : "toFloat64('" + v + "')";
    }

    /** {@code CAST('d' AS Decimal(p, s))} for its own precision and scale (at most 76 digits). */
    private static String decimal(java.math.BigDecimal d) {
        int scale = Math.max(d.scale(), 0);
        int precision = Math.max(d.precision(), scale + 1);
        if (precision > 76) {
            throw new DialectCapability("a decimal literal of " + precision + " digits reached ClickHouse, whose"
                    + " Decimal holds 76");
        }
        return "CAST('" + d.toPlainString() + "' AS Decimal(" + precision + ", " + scale + "))";
    }

    @Override
    protected String hugeIntLiteral(java.math.BigDecimal v) {
        return "toInt128('" + v.toPlainString() + "')";
    }

    @Override
    protected String arrayTypeName(String element) {
        return "Array(" + element + ")";
    }

    /** A map's key is never nullable ({@code Map(Nullable(String), ...)} is refused). */
    @Override
    protected String mapTypeName(String key, String value) {
        return "Map(" + notNullable(key) + ", " + value + ")";
    }

    @Override
    protected String structTypeName(List<java.util.Map.Entry<String, String>> fields) {
        StringBuilder sb = new StringBuilder("Tuple(");
        for (int i = 0; i < fields.size(); i++) {
            sb.append(i > 0 ? ", " : "").append(fields.get(i).getKey()).append(" ").append(fields.get(i).getValue());
        }
        return sb.append(")").toString();
    }

    private static String notNullable(String type) {
        return type.startsWith("Nullable(") && type.endsWith(")") ? type.substring(9, type.length() - 1) : type;
    }

    /** Alias-less projections label EXPLICITLY from the declared output: ClickHouse labels an expression by its
     *  text. */
    @Override
    protected @com.legend.base.Nullable String implicitLabel(SqlSelect.Projection p) {
        return p.out() != null ? aliasIdent(p.out().name()) : super.implicitLabel(p);
    }

    /** A projection whose declared slot is a Boolean is a Bool (a function's truth is a UInt8 here, measured); one
     *  that can only be NULL, or a raise, is its slot's nullable type. */
    @Override
    protected SqlWriter projection(SqlWriter writer, SqlSelect.Projection p) {
        if (p.out() != null && (p.out().type() == SqlType.Scalar.BOOLEAN && !(p.expr() instanceof SqlExpr.Column)
                || p.expr().type() instanceof com.legend.sql.TypeFact.Bottom || isRaise(p.expr())
                || p.expr() instanceof SqlExpr.NullLit && !(p.out().type() instanceof SqlType.Array))) {
            writer.append("CAST(").expr(p.expr(), 0).append(" AS ").append(castTypeName(p.out().type())).append(")");
            String label = p.alias() != null ? aliasIdent(p.alias()) : implicitLabel(p);
            return label == null ? writer : writer.append(" AS ").append(label);
        }
        return super.projection(writer, p);
    }

    private static boolean isRaise(SqlExpr e) {
        return e instanceof SqlExpr.Call c && c.fn() == SqlFn.ERROR;
    }

    /** A branch that IS the raise is cast to the CASE's own type (the raise is an Int64, which a CASE of another type
     *  refuses). */
    @Override
    protected SqlWriter caseExpr(SqlWriter writer, SqlExpr.Case c) {
        SqlType slot = typeOf(c);
        writer.append("CASE");
        for (SqlExpr.Case.When w : c.whens()) {
            writer.append(" WHEN ").expr(w.condition(), 0).append(" THEN ").piece(branch(w.then(), slot));
        }
        if (c.otherwise() != null) {
            writer.append(" ELSE ").piece(branch(c.otherwise(), slot));
        }
        return writer.append(" END");
    }

    private SqlWriter.Piece branch(SqlExpr e, @com.legend.base.Nullable SqlType slot) {
        if (isRaise(e) && slot instanceof SqlType.Array) {
            // a list-typed raise: the raise is the size of an empty list of the slot's type
            String type = castTypeName(slot);
            return w -> w.append("arrayResize(CAST([] AS " + type + "), ").expr(e, 0).append(")");
        }
        if (isRaise(e) && slot != null) {
            String type = castTypeName(slot);
            return w -> w.append("CAST(").expr(e, 0).append(" AS " + type + ")");
        }
        return w -> w.expr(e, 0);
    }

    @Override
    protected SqlWriter expr(SqlWriter writer, SqlExpr e, int parentPrec) {
        return switch (e) {
            // an Int64: a bare literal is typed by its magnitude (1 is UInt8)
            case SqlExpr.IntLit i -> writer.append("toInt64(" + i.value() + ")");
            // a quantified comparison over no rows is SQL's ALL true and ANY false; ClickHouse answers NULL there
            // when the subquery's column is Nullable (measured: 100 > ALL (an empty Nullable(Int64)) is NULL), so the
            // empty case is answered first
            case SqlExpr.Quantified q -> {
                writer.append("(CASE WHEN (SELECT count() FROM (");
                inline(writer, q.subquery());
                writer.append(") AS \"__qa\") = 0 THEN ").append(q.quantifier() == SqlExpr.Quantifier.ALL ? "true"
                        : "false").append(" ELSE ");
                super.expr(writer, q, 0);
                yield writer.append(" END)");
            }
            // EXISTS over a select of constants answers 0 where its filter keeps a row (ClickHouse 25.8, measured:
            // EXISTS (SELECT 1 FROM numbers(5) WHERE number > 3) is 0, with the column selected 1): the rows' own
            // columns are selected instead
            case SqlExpr.Exists ex when ex.subquery() instanceof SqlSelect q && q.groupBy().isEmpty()
                    && q.having() == null && !q.distinct() && !(q.from() instanceof com.legend.sql.SqlSource.Dual)
                    && !q.projections().isEmpty() && q.projections().stream().allMatch(p -> p.expr() instanceof SqlExpr.IntLit
                            || p.expr() instanceof SqlExpr.BoolLit) -> {
                writer.append("EXISTS (");
                inline(writer, new SqlSelect(List.of(), false, q.from(), q.where(), q.groupBy(), q.having(), q.qualify(),
                        q.orderBy(), q.limit(), q.offset(), List.of()));
                yield writer.append(")");
            }
            case SqlExpr.DecimalLit d when !(d.type() instanceof com.legend.sql.TypeFact.Typed t
                    && t.type() == SqlType.Scalar.HUGEINT) -> writer.append(decimal(d.value()));
            case SqlExpr.OrderedListAgg ola -> writer.piece(collected(w -> w.expr(ola.value(), 0),
                    List.of(new SortKey(w -> w.expr(ola.orderBy(), 0), true, false, typeOf(ola.orderBy())))));
            default -> super.expr(writer, e, parentPrec);
        };
    }

    // ==================================================================
    // Lists: native arrays with lambdas; never NULL
    // ==================================================================

    private static @com.legend.base.Nullable SqlType listElement(SqlExpr e) {
        return e.type() instanceof com.legend.sql.TypeFact.Typed t && t.type() instanceof SqlType.Array a
                ? a.element() : null;
    }

    private static @com.legend.base.Nullable SqlType typeOf(SqlExpr e) {
        return e.type() instanceof com.legend.sql.TypeFact.Typed t ? t.type() : null;
    }

    /** A list argument: the empty collection (a NULL) as the empty array -- of its element's type when known. */
    /** A printf template's conversions in order ({@code %%} none). */
    private static List<Character> conversions(String format) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < format.length(); i++) {
            if (format.charAt(i) != '%') {
                continue;
            }
            int j = i + 1;
            while (j < format.length() && "-+ #0123456789.".indexOf(format.charAt(j)) >= 0) {
                j++;
            }
            if (j < format.length() && format.charAt(j) != '%') {
                out.add(format.charAt(j));
            }
            i = j;
        }
        return out;
    }

    private SqlWriter.Piece list(SqlExpr xs) {
        if (xs instanceof SqlExpr.NullLit) {
            SqlType element = listElement(xs);
            return element == null ? w -> w.append(EMPTY_UNTYPED)
                    : w -> w.append("CAST([] AS " + arrayTypeName(castTypeName(element)) + ")");
        }
        return w -> w.expr(xs, 0);
    }

    /** {@code xs} less its NULL elements. */
    private SqlWriter.Piece present(SqlExpr xs) {
        return w -> w.append("arrayFilter(\"__x\" -> isNotNull(\"__x\"), ").piece(list(xs)).append(")");
    }

    /** A list of no element type (Pure's []): a bare [] is an Array(Nothing), into which no lambda inserts ("Cannot
     *  insert element into Nothing", measured); [NULL]'s type, Array(Nullable(Nothing)), takes one and is still of no
     *  element type. */
    private static final String EMPTY_UNTYPED = "CAST([] AS Array(Nullable(Nothing)))";

    @Override
    protected SqlWriter arrayLit(SqlWriter writer, List<SqlExpr> elements) {
        if (elements.isEmpty()) {
            return writer.append(EMPTY_UNTYPED);
        }
        return writer.append("[").join(elements, ", ", unified(elements)).append("]");
    }

    /**
     * Members written to share a type: a Float literal is a Decimal by its digits (the numeric charter's Rule 1) and a
     * computed Float a Float64, and ClickHouse has no supertype of the two ("There is no supertype for types Float64,
     * Decimal(3, 2)", measured), so where both meet, each Decimal member is its Float64.
     */
    private java.util.function.BiConsumer<SqlWriter, SqlExpr> unified(List<SqlExpr> members) {
        boolean dbl = members.stream().anyMatch(e -> typeOf(e) == SqlType.Scalar.DOUBLE);
        boolean dec = members.stream().anyMatch(e -> typeOf(e) instanceof SqlType.Decimal);
        if (!(dbl && dec)) {
            return (w, e) -> w.expr(e, 0);
        }
        return (w, e) -> {
            if (typeOf(e) instanceof SqlType.Decimal) {
                w.append("toFloat64(").expr(e, 0).append(")");
            } else {
                w.expr(e, 0);
            }
        };
    }

    @Override
    protected SqlWriter structLit(SqlWriter writer, SqlExpr.StructLit st) {
        writer.append("CAST(tuple(").join(st.fields(), ", ", (w, f) -> w.expr(f.value(), 0)).append(") AS Tuple(");
        return writer.join(st.fields(), ", ", (w, f) -> {
            SqlType t = typeOf(f.value());
            if (t == null) {
                throw new DialectCapability("a struct field of unknown type reached ClickHouse: a Tuple's fields are"
                        + " typed");
            }
            w.append(ident(f.name())).append(" ").append(castTypeName(t));
        }).append("))");
    }

    /** A field by name; a field NUMBER (a zip's pair, read by position) by its index. */
    @Override
    protected SqlWriter structGet(SqlWriter writer, SqlExpr.StructGet g) {
        String field = g.field().matches("[0-9]+") ? g.field() : stringLit(g.field());
        return writer.append("tupleElement(").expr(g.source(), 0).append(", ").append(field).append(")");
    }

    @Override
    protected SqlWriter lambda(SqlWriter writer, SqlExpr.Lambda l) {
        return writer.append(l.params().size() == 1 ? param(l.params().get(0))
                : "(" + String.join(", ", l.params().stream().map(this::param).toList()) + ")")
                .append(" -> ").expr(l.body(), 0);
    }

    private String param(String name) {
        return columnRef(SqlExpr.Column.derived(null, name));
    }

    /** A lambda's arrays: the list, and its 1-based positions when the lambda takes the index (DuckDB's). */
    private SqlWriter.Piece lambdaArrays(SqlExpr xs, SqlExpr.Lambda l) {
        if (l.params().size() > 1) {
            return w -> w.piece(list(xs)).append(", arrayEnumerate(").piece(list(xs)).append(")");
        }
        return list(xs);
    }

    @Override
    protected SqlWriter listCall(SqlWriter writer, SqlFn fnName, List<SqlExpr> args) {
        SqlExpr xs = args.get(0);
        return switch (fnName) {
            case LIST_FILTER -> writer.append("arrayFilter(").expr(args.get(1), 0).append(", ")
                    .piece(lambdaArrays(xs, (SqlExpr.Lambda) args.get(1))).append(")");
            case LIST_TRANSFORM -> writer.append("arrayMap(").expr(args.get(1), 0).append(", ")
                    .piece(lambdaArrays(xs, (SqlExpr.Lambda) args.get(1))).append(")");
            case LIST_CONCAT -> writer.append("arrayConcat(").join(args, ", ", (w, a) -> w.piece(list(a))).append(")");
            // 1-based, negative from the end, out of range NULL (DuckDB's list_extract; arrayElement answers the
            // type's default there). A Tuple is never NULL: out of range, a struct is its default, every field NULL
            case LIST_GET -> typeOf(xs) instanceof SqlType.Array arr && arr.element() instanceof SqlType.Struct
                    ? writer.append("arrayElement(").piece(list(xs)).append(", ").expr(args.get(1), 0).append(")")
                    : writer.append("(CASE WHEN ").expr(args.get(1), 0).append(" = 0 OR abs(")
                    .expr(args.get(1), 0).append(") > length(").piece(list(xs)).append(") THEN NULL ELSE arrayElement(")
                    .piece(list(xs)).append(", ").expr(args.get(1), 0).append(") END)");
            case LIST_POSITION -> writer.append("nullIf(toInt64(indexOf(").piece(list(xs)).append(", ")
                    .expr(args.get(1), 0).append(")), 0)");
            case LIST_DISTINCT -> writer.append("arrayDistinct(").piece(present(xs)).append(")");
            case LIST_APPEND -> writer.append("arrayPushBack(").piece(list(xs)).append(", ").expr(args.get(1), 0)
                    .append(")");
            case LIST_SUM -> writer.piece(reduced("sum", xs));
            case LIST_MIN -> writer.piece(reduced("min", xs));
            case LIST_MAX -> writer.piece(reduced("max", xs));
            case LIST_AVG -> writer.append("CAST(").piece(reduced("avg", xs)).append(" AS Nullable(Float64))");
            case LIST_MEDIAN -> writer.piece(reduced("quantileExactInclusive(0.5)", xs));
            case LIST_MODE -> writer.piece(modeOf(present(xs)));
            case LIST_BOOL_AND -> writer.append("if(empty(").piece(present(xs)).append("), NULL, arrayAll(\"__x\" -> \"__x\", ")
                    .piece(present(xs)).append("))");
            case LIST_BOOL_OR -> writer.append("if(empty(").piece(present(xs)).append("), NULL, arrayExists(\"__x\" -> \"__x\", ")
                    .piece(present(xs)).append("))");
            case LIST_PRODUCT -> writer.append("arrayProduct(arrayMap(\"__x\" -> coalesce(\"__x\", 1), ").piece(list(xs))
                    .append("))");
            case LIST_SORT -> writer.append("arraySort(").piece(list(xs)).append(")");
            case LIST_SORT_DESC -> writer.append("arrayReverseSort(").piece(list(xs)).append(")");
            case LIST_REVERSE -> writer.append("arrayReverse(").piece(list(xs)).append(")");
            case LIST_TAIL -> writer.append("arraySlice(").piece(list(xs)).append(", 2)");
            case LIST_INIT -> writer.append("arrayPopBack(").piece(list(xs)).append(")");
            // DuckDB's list_slice: 1-based, both ends inclusive
            case LIST_SLICE -> writer.append("arraySlice(").piece(list(xs)).append(", greatest(").expr(args.get(1), 0)
                    .append(", 1), greatest(").expr(args.get(2), 6).append(" - greatest(").expr(args.get(1), 0)
                    .append(", 1) + 1, 0))");
            // DuckDB's list_zip: pairs by position (a tuple read by field number, structGet); arrayZip wants lists of
            // one length, so each is cut to the shorter (truncate) or padded with NULL to the longer
            case LIST_ZIP -> {
                boolean truncate = args.size() > 2 && args.get(args.size() - 1) instanceof SqlExpr.BoolLit b
                        && b.value();
                List<SqlExpr> lists = args.size() > 2 && args.get(args.size() - 1) instanceof SqlExpr.BoolLit
                        ? args.subList(0, args.size() - 1) : args;
                SqlWriter.Piece n = w -> w.append(truncate ? "least(" : "greatest(").join(lists, ", ",
                        (v, l) -> v.append("length(").piece(list(l)).append(")")).append(")");
                yield writer.append("arrayZip(").join(lists, ", ", (w, l) -> w.append("arrayResize(arrayMap(\"__z\" ->")
                        .append(" toNullable(\"__z\"), ").piece(list(l)).append("), ").piece(n).append(", NULL)"))
                        .append(")");
            }
            // DuckDB's list_reduce: the first element is the accumulator's start, the lambda (acc, x) over the rest
            // the accumulator starts as the list's element type spells it: arrayFold refuses a step whose type is not
            // the start's (Array(Int64) against the step's Array(Nullable(Int64)), measured)
            case LIST_REDUCE -> {
                SqlType element = listElement(xs);
                writer.append("arrayFold(").expr(args.get(1), 0).append(", arraySlice(").piece(list(xs)).append(", 2), ");
                if (element == null) {
                    yield writer.append("arrayElement(").piece(list(xs)).append(", 1))");
                }
                yield writer.append("CAST(arrayElement(").piece(list(xs)).append(", 1) AS " + castTypeName(element) + "))");
            }
            case TYPEOF -> throw new DialectCapability("typeof reached ClickHouse: its type names are ClickHouse's");
            case RANGE_FN, REPEAT_VALUE -> throw new IllegalStateException(fnName + " is rendered by call");
            default -> throw new IllegalStateException("not a list call: " + fnName);
        };
    }

    /** An aggregate over a list's elements ({@code arrayReduce('fn', xs)}): NULLs skipped, NULL over no element. A
     *  quantile reads them as Float64 (an exact quantile takes no Decimal, measured). */
    private SqlWriter.Piece reduced(String fn, SqlExpr xs) {
        // fn, or fn(params): its OrNull form, NULL over no element
        int paren = fn.indexOf('(');
        String orNull = paren < 0 ? fn + "OrNull" : fn.substring(0, paren) + "OrNull" + fn.substring(paren);
        if (fn.startsWith("quantile")) {
            return w -> w.append("arrayReduce(" + stringLit(orNull) + ", arrayMap(\"__x\" -> toFloat64(\"__x\"), ")
                    .piece(list(xs)).append("))");
        }
        return w -> w.append("arrayReduce(" + stringLit(orNull) + ", ").piece(list(xs)).append(")");
    }

    /** The most frequent of the values (DuckDB's mode): by count, a tie by the smaller value; NULL over none. */
    private static SqlWriter.Piece modeOf(SqlWriter.Piece values) {
        return w -> w.append("arrayElement(arraySort(\"__v\" -> (-countEqual(").piece(values)
                .append(", \"__v\"), \"__v\"), arrayDistinct(").piece(values).append(")), 1)");
    }

    @Override
    protected SqlWriter listExists(SqlWriter writer, List<SqlExpr> args) {
        SqlExpr.Lambda l = (SqlExpr.Lambda) args.get(1);
        return writer.append("arrayExists(").expr(l, 0).append(", ").piece(lambdaArrays(args.get(0), l)).append(")");
    }

    @Override
    protected SqlWriter listForAll(SqlWriter writer, List<SqlExpr> args) {
        SqlExpr.Lambda l = (SqlExpr.Lambda) args.get(1);
        return writer.append("arrayAll(").expr(l, 0).append(", ").piece(lambdaArrays(args.get(0), l)).append(")");
    }

    @Override
    protected SqlWriter allDistinct(SqlWriter writer, List<SqlExpr> args) {
        return writer.append("(length(arrayDistinct(").piece(list(args.get(0)))
                .append(")) = length(").piece(list(args.get(0))).append("))");
    }

    @Override
    protected SqlWriter membership(SqlWriter writer, SqlExpr.Membership m) {
        return writer.append("has(").piece(list(m.collection())).append(", ").expr(m.needle(), 0).append(")");
    }

    @Override
    protected SqlWriter reduceCollection(SqlWriter writer, SqlExpr.ReduceCollection rc) {
        SqlWriter.Piece present = present(rc.collection());
        return switch (rc.reducer()) {
            // NULL over no present element, as string_agg over no row
            case STRING_AGG -> {
                if (rc.extras().size() != 1) {
                    throw new DialectCapability("a list's STRING_AGG of " + rc.extras().size() + " extras reached"
                            + " ClickHouse");
                }
                yield writer.append("if(empty(").piece(present).append("), NULL, arrayStringConcat(arrayMap(\"__x\" ->")
                        .append(" toString(\"__x\"), ").piece(present).append("), ").expr(rc.extras().get(0), 0)
                        .append("))");
            }
            case COUNT -> writer.append("toInt64(length(").piece(present).append("))");
            case MIN, MAX, SUM, AVG, BOOL_AND, BOOL_OR -> writer.piece(reduced(switch (rc.reducer()) {
                case MIN -> "min";
                case MAX -> "max";
                case SUM -> "sum";
                case AVG -> "avg";
                case BOOL_AND -> "min";
                case BOOL_OR -> "max";
                // the outer arm admits only the six above (Charter C2.4: an unmatched kind throws)
                default -> throw new IllegalStateException("reducer " + rc.reducer() + " reached the min/max arm");
            }, rc.collection()));
            case STDDEV_SAMP, STDDEV -> writer.piece(reduced("stddevSampStable", rc.collection()));
            case STDDEV_POP -> writer.piece(reduced("stddevPopStable", rc.collection()));
            case VAR_SAMP, VARIANCE -> writer.piece(reduced("varSampStable", rc.collection()));
            case VAR_POP -> writer.piece(reduced("varPopStable", rc.collection()));
            case MEDIAN -> writer.piece(reduced("quantileExactInclusive(0.5)", rc.collection()));
            // a parametric aggregate's level is a constant of its name
            case QUANTILE_CONT -> writer.piece(reduced("quantileExactInclusive(" + level(rc.extras()) + ")",
                    rc.collection()));
            case MODE -> writer.piece(modeOf(present));
            default -> throw new DialectCapability("a list reduced by " + rc.reducer() + " reached ClickHouse");
        };
    }

    /** A quantile's level, a literal number (an aggregate's parameter in ClickHouse is a constant). */
    private static String level(List<SqlExpr> extras) {
        if (extras.size() == 1) {
            SqlExpr q = extras.get(0);
            if (q instanceof SqlExpr.FloatLit f) {
                return String.valueOf(f.value());
            }
            if (q instanceof SqlExpr.DecimalLit d) {
                return d.value().toPlainString();
            }
            if (q instanceof SqlExpr.IntLit i) {
                return String.valueOf(i.value());
            }
        }
        throw new DialectCapability("a quantile whose level is no literal number reached ClickHouse");
    }

    /** One list exploded in a select list: {@code arrayJoin}, set-returning as DuckDB's UNNEST (several would cross,
     *  where DuckDB's align: refused by name). */
    @Override
    protected SqlWriter unnestProjection(SqlWriter writer, List<SqlExpr> args) {
        if (args.size() != 1) {
            throw new DialectCapability("an unnest of " + args.size() + " lists reached ClickHouse");
        }
        return writer.append("arrayJoin(").piece(list(args.get(0))).append(")");
    }

    /** Several lists exploded in one select align, as DuckDB's unnests do (arrayJoins would cross): one ARRAY JOIN of
     *  the lists zipped, each padded with NULL to the longest ({@code LIST_ZIP}'s form), every column its position of
     *  the tuple. Only a select of nothing but such explosions, over no source or one, and no other clause. */
    @Override
    protected SqlWriter select(SqlWriter writer, SqlSelect s, int depth) {
        long unnests = s.projections().stream().filter(p -> p.expr() instanceof SqlExpr.Call c
                && c.fn() == SqlFn.UNNEST).count();
        if (unnests > 1) {
            if (unnests != s.projections().size() || s.distinct() || s.where() != null || !s.groupBy().isEmpty()
                    || s.having() != null || s.qualify() != null || !s.orderBy().isEmpty() || s.limit() != null
                    || s.offset() != null) {
                throw new DialectCapability("several unnests in one select, beside other columns or clauses, reached"
                        + " ClickHouse: its arrayJoins cross, where DuckDB's unnests align");
            }
            List<SqlExpr> lists = new ArrayList<>();
            for (SqlSelect.Projection p : s.projections()) {
                List<SqlExpr> args = ((SqlExpr.Call) p.expr()).args();
                if (args.size() != 1) {
                    throw new DialectCapability("an unnest of " + args.size() + " lists reached ClickHouse");
                }
                lists.add(args.get(0));
            }
            writer.append("SELECT ");
            for (int i = 0; i < s.projections().size(); i++) {
                SqlSelect.Projection p = s.projections().get(i);
                String name = p.alias() != null ? p.alias() : p.outputName();
                writer.append(i > 0 ? ", " : "").append("tupleElement(\"__zj\", " + (i + 1) + ")");
                if (name != null) {
                    writer.append(" AS ").append(aliasIdent(name));
                }
            }
            nl(writer, depth).append("FROM ");
            if (s.from() instanceof SqlSource.Dual) {
                writer.append("system.one");
            } else {
                source(writer, s.from(), depth);
            }
            SqlWriter.Piece n = w -> w.append("greatest(").join(lists, ", ",
                    (v, l) -> v.append("length(").piece(list(l)).append(")")).append(")");
            return nl(writer, depth).append("ARRAY JOIN arrayZip(").join(lists, ", ", (w, l) -> w.append(
                    "arrayResize(arrayMap(\"__z\" -> toNullable(\"__z\"), ").piece(list(l)).append("), ").piece(n)
                    .append(", NULL)")).append(") AS \"__zj\"");
        }
        return super.select(writer, s, depth);
    }

    /** Pure's fold, {@code (element, acc)}, as {@code arrayFold((acc, x) -> ..., xs, init)}. */
    @Override
    protected SqlWriter foldCall(SqlWriter writer, SqlExpr.FoldCall f) {
        if (!(f.type() instanceof com.legend.sql.TypeFact.Typed t)) {
            throw new DialectCapability("a fold of unknown type reached ClickHouse");
        }
        String type = castTypeName(t.type());
        String elem = param(f.lambda().params().get(0));
        String acc = param(f.lambda().params().get(1));
        return writer.append("arrayFold((" + acc + ", " + elem + ") -> CAST(").expr(f.lambda().body(), 0)
                .append(" AS " + type + "), ").piece(list(f.source())).append(", CAST(").expr(f.init(), 0)
                .append(" AS " + type + "))");
    }

    @Override
    protected SqlWriter splitPartCall(SqlWriter writer, List<SqlExpr> a) {
        // Pure's splitPart: the non-empty tokens, 1-based, NULL past the end
        // splitByString takes no Nullable text (measured): NULL's tokens are the empty string's, none
        SqlWriter.Piece tokens = w -> w.append("arrayFilter(\"__t\" -> \"__t\" != '', splitByString(").expr(a.get(1), 0)
                .append(", ifNull(").expr(a.get(0), 0).append(", '')))");
        return writer.append("(CASE WHEN ").expr(a.get(2), 0).append(" < 1 OR ").expr(a.get(2), 0).append(" > length(")
                .piece(tokens).append(") THEN NULL ELSE arrayElement(").piece(tokens).append(", ").expr(a.get(2), 0)
                .append(") END)");
    }

    @Override
    protected SqlWriter structInsert(SqlWriter writer, List<SqlExpr> args) {
        // a Tuple of the struct's other fields and the new one, named (Trino's ROW, the same shape)
        if (!(typeOf(args.get(0)) instanceof SqlType.Struct struct) || !(args.get(1) instanceof SqlExpr.StringLit name)) {
            throw new DialectCapability("struct_insert over an untyped struct reached ClickHouse");
        }
        SqlType added = typeOf(args.get(2));
        if (added == null) {
            throw new DialectCapability("struct_insert of a value of unknown type reached ClickHouse");
        }
        List<SqlType.Struct.Field> kept = struct.fields().stream().filter(f -> !f.name().equals(name.value())).toList();
        List<SqlType.Struct.Field> all = new ArrayList<>(kept);
        all.add(new SqlType.Struct.Field(name.value(), added));
        writer.append("CAST(tuple(");
        for (SqlType.Struct.Field f : kept) {
            writer.append("tupleElement(").expr(args.get(0), 0).append(", ").append(stringLit(f.name())).append("), ");
        }
        return writer.expr(args.get(2), 0).append(") AS ").append(castTypeName(new SqlType.Struct(all))).append(")");
    }

    // ==================================================================
    // Variant: a JSON value's text (String)
    // ==================================================================

    @Override
    protected SqlWriter variantConstruct(SqlWriter writer, List<SqlExpr> a) {
        if (a.size() != 1) {
            throw new DialectCapability("toVariant of " + a.size() + " arguments reached ClickHouse");
        }
        // a JSON value is its own (DuckDB's to_json of JSON); a NULL stays SQL's NULL, as DuckDB's to_json
        if (typeOf(a.get(0)) == SqlType.Scalar.JSON) {
            return writer.expr(a.get(0), 0);
        }
        return writer.append("toJSONString(").expr(a.get(0), 0).append(")");
    }

    /** A key (a member, NULL when absent) or a 0-based index (-1 the last, past the end NULL) of a JSON value's text:
     *  the member's raw JSON ({@code JSONExtractRaw} answers '' for none, measured). */
    @Override
    protected SqlWriter variantGet(SqlWriter writer, List<SqlExpr> args) {
        SqlExpr key = args.get(1);
        if (key instanceof SqlExpr.StringLit root && "$".equals(root.value())) {
            return writer.expr(args.get(0), 0);
        }
        writer.append("nullIf(JSONExtractRaw(").expr(args.get(0), 0).append(", ");
        if (typeOf(key) == SqlType.Scalar.VARCHAR || key instanceof SqlExpr.StringLit) {
            writer.expr(key, 0);
        } else {
            // JSON's indexes are 1-based, a negative one from the end (measured)
            writer.append("if(").expr(key, 0).append(" >= 0, ").expr(key, 6).append(" + 1, ").expr(key, 0).append(")");
        }
        return writer.append("), '')");
    }

    @Override
    protected SqlWriter variantElements(SqlWriter writer, List<SqlExpr> args) {
        if (listElement(args.get(0)) != null) {
            return writer.expr(args.get(0), 0);
        }
        // from the JSON null where there is no value: over a Nullable it would be a Nullable(Array), refused (measured)
        return writer.append("JSONExtractArrayRaw(coalesce(").expr(args.get(0), 0).append(", 'null'))");
    }

    /** Every cast names the nullable type ({@code CAST(NULL AS Int64)} is refused); a Decimal's too. */
    @Override
    protected SqlWriter variantAwareCast(SqlWriter writer, SqlExpr.Cast c) {
        // an array is never NULL here ("Cannot convert NULL to a non-nullable type", measured): no list is the empty one
        if (c.value() instanceof SqlExpr.NullLit && c.target() instanceof SqlType.Array) {
            return writer.append("CAST([] AS " + castTypeName(c.target()) + ")");
        }
        // a zoned timestamp from text: ISO 8601's T, Z and offsets, which a cast reads as NULL (measured)
        if (c.target() == SqlType.Scalar.TIMESTAMPTZ && typeOf(c.value()) == SqlType.Scalar.VARCHAR) {
            return writer.append("parseDateTime64BestEffort(").expr(c.value(), 0).append(", 6, 'UTC')");
        }
        // text to a decimal rounds at the scale, as DuckDB's (a cast truncates: '123.126' is 123.12, measured)
        if (c.target() instanceof SqlType.Decimal d && typeOf(c.value()) == SqlType.Scalar.VARCHAR) {
            return writer.append("CAST(round(toDecimal128(").expr(c.value(), 0).append(", 18), " + d.scale()
                    + ") AS Nullable(Decimal(" + d.precision() + ", " + d.scale() + ")))");
        }
        if (c.target() instanceof SqlType.Decimal d) {
            return writer.append("CAST(").expr(c.value(), 0).append(" AS Nullable(Decimal(" + d.precision() + ", "
                    + d.scale() + ")))");
        }
        // (a Float literal is a Decimal by its digits, so before the Decimal's text below)
        if (c.target() == SqlType.Scalar.VARCHAR && c.value() instanceof SqlExpr.FloatLit f
                && com.legend.sql.SqlTyping.floatDecimal(f.value()) instanceof java.math.BigDecimal b) {
            // a Float literal's text: its shortest digits, one fraction digit at least (Pure's 3.0, 0.00000001)
            return writer.append("toDecimalString(").expr(c.value(), 0)
                    .append(", " + Math.max(b.stripTrailingZeros().scale(), 1) + ")");
        }
        // a Decimal's text at its scale (toString drops the trailing zeros: 3.0 is '3', measured): the value's own
        // scale, read from its type name (a constant), which the plan's may undercount (a Number column's DECIMAL(38,0)
        // holds 101.101: its sum printed '101.', measured)
        if (c.target() == SqlType.Scalar.VARCHAR && typeOf(c.value()) instanceof SqlType.Decimal d) {
            if (c.value() instanceof SqlExpr.FloatLit || c.value() instanceof SqlExpr.DecimalLit) {
                // a literal's scale is its own (one of an extreme magnitude is a Float64, whose type has none)
                return writer.append("toDecimalString(").expr(c.value(), 0).append(", " + d.scale() + ")");
            }
            return writer.append("toDecimalString(").expr(c.value(), 0).append(", toUInt8(extract(toTypeName(")
                    .expr(c.value(), 0).append("), ', (\\\\d+)\\\\)')))");
        }
        // a double's text: its shortest digits, and a fraction digit where it has none (DuckDB's -1.0 is ClickHouse's
        // '-1', measured), the value read once
        if (c.target() == SqlType.Scalar.VARCHAR && typeOf(c.value()) == SqlType.Scalar.DOUBLE) {
            return writer.append("replaceRegexpOne(toString(").expr(c.value(), 0).append("), '^(-?[0-9]+)$', '\\\\1.0')");
        }
        // a Variant's member as text: a JSON string unquoted, any other value its JSON; the whole value as text is its
        // JSON (DuckDB's cast, Trino's)
        if (typeOf(c.value()) == SqlType.Scalar.JSON && c.target() == SqlType.Scalar.VARCHAR) {
            if (!(c.value() instanceof SqlExpr.Call call && call.fn() == SqlFn.VARIANT_GET)) {
                return writer.expr(c.value(), 0);
            }
            return writer.append("if(JSONType(").expr(c.value(), 0).append(") = 'String', JSONExtractString(")
                    .expr(c.value(), 0).append("), ").expr(c.value(), 0).append(")");
        }
        // a Variant as a scalar: extracted to the type
        if (typeOf(c.value()) == SqlType.Scalar.JSON && (c.target() == SqlType.Scalar.BIGINT
                || c.target() == SqlType.Scalar.INTEGER || c.target() == SqlType.Scalar.DOUBLE
                || c.target() == SqlType.Scalar.BOOLEAN)) {
            return writer.append("JSONExtract(").expr(c.value(), 0).append(", ").append(stringLit(castTypeName(c.target())))
                    .append(")");
        }
        // a Variant (its JSON text) as a list or a map: extracted to the type, from the JSON null where there is no
        // value (a list or a map is never NULL here: extracting from a NULL would be a Nullable(Array), refused,
        // measured; the empty one is its NULL)
        if (typeOf(c.value()) == SqlType.Scalar.JSON
                && (c.target() instanceof SqlType.Array || c.target() instanceof SqlType.Map)) {
            return writer.append("JSONExtract(coalesce(").expr(c.value(), 0).append(", 'null'), ")
                    .append(stringLit(castTypeName(c.target()))).append(")");
        }
        return super.variantAwareCast(writer, c);
    }

    // ==================================================================
    // Scalar functions -- every SqlFn decided here
    // ==================================================================

    @Override
    protected SqlWriter call(SqlWriter writer, SqlExpr.Call c, int parentPrec) {
        List<SqlExpr> a = c.args();
        return switch (c.fn()) {
            // ---- portable: the base arm (or a Spellings.CLICKHOUSE row) is ClickHouse SQL
            case AND, OR, NOT, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
                 PLUS, MINUS, TIMES, NEGATE, IS_NULL, IS_NOT_NULL, IN, PARSE_INT, PI, CEILING, FLOOR, SIGN,
                 LPAD, RPAD, TODAY, DIVIDE, ROUND, HASH, BIT_AND, BIT_OR, BIT_XOR, BIT_SHIFT_LEFT,
                 BIT_SHIFT_RIGHT, XOR,
                 // Spellings.CLICKHOUSE rows
                 ACOS, ASIN, ATAN, ATAN2, CBRT, COALESCE, COS, COSH, DEGREES, LOG10,
                 POW, RADIANS, SIN, SINH, SQRT, TAN, LIST_FLATTEN, MAP_KEYS, MAP_VALUES,
                 STARTS_WITH, ENDS_WITH, LIST_REVERSE, JARO_WINKLER, CHR,
                 // the base's idiom points, overridden above
                 UNNEST, LIST_FILTER, LIST_TRANSFORM, LIST_CONCAT, LIST_GET, LIST_POSITION, STRUCT_INSERT,
                 PURE_SPLIT_PART, LIST_EXISTS, ALL_DISTINCT, LIST_FOR_ALL, TO_VARIANT, VARIANT_ELEMENTS, VARIANT_GET,
                 LIST_ZIP, LIST_DISTINCT, LIST_APPEND, LIST_SUM, LIST_MIN, LIST_MAX, LIST_AVG, LIST_MEDIAN, LIST_MODE,
                 LIST_SORT, LIST_SORT_DESC, LIST_TAIL, LIST_INIT, LIST_PRODUCT, LIST_REDUCE, LIST_SLICE,
                 LIST_BOOL_AND, LIST_BOOL_OR, TYPEOF, FLOAT_CANON -> super.call(writer, c, parentPrec);

            // an integer's abs is a UInt64 here: back to an Int64, raising where it does not fit (abs of Int64's least,
            // as DuckDB's overflow)
            case ABS -> typeOf(a.get(0)) == SqlType.Scalar.BIGINT || typeOf(a.get(0)) == SqlType.Scalar.INTEGER
                    ? writer.append("accurateCast(abs(").expr(a.get(0), 0).append("), 'Int64')")
                    : writer.append("abs(").expr(a.get(0), 0).append(")");
            // a raise: no function raises a computed message, so its text cast to an integer raises it, the
            // platform's markers around it, verbatim in the server's message
            // (materialize: a constant one would raise at analysis, in a branch not taken -- measured)
            case ERROR -> {
                writer.append("toInt64(materialize(concat(char(31), ");
                if (a.size() > 1) {
                    writer.expr(a.get(1), 0).append(", char(30), ");
                }
                // the server's error text quotes the message as a string literal, a quote in it escaped (\', measured):
                // each quote travels as the envelope's quote mark (RaisedErrors.QUOTE_MARK), as SQLite's
                yield writer.append("replaceAll(toString(").expr(a.get(0), 0).append("), '\\'', char(29)), char(31))))");
            }
            case IS_DISTINCT_FROM, NULL_SAFE_NOT_EQUAL -> writer.append("NOT coalesce(").expr(a.get(0), 4).append(" = ")
                    .expr(a.get(1), 4).append(", isNull(").expr(a.get(0), 0).append(") AND isNull(").expr(a.get(1), 0)
                    .append("))");
            case NULL_SAFE_EQUAL -> writer.append("coalesce(").expr(a.get(0), 4).append(" = ").expr(a.get(1), 4)
                    .append(", isNull(").expr(a.get(0), 0).append(") AND isNull(").expr(a.get(1), 0).append("))");
            // NULL-ignoring, as DuckDB's concat
            case CONCAT -> writer.append("concat(").join(flattenConcat(a), ", ", this::asText).append(")");
            case CONCAT_JOIN -> writer.append("concat(").join(a, ", ", this::asText).append(")");
            // the platform guards a zero divisor with its own raise; ClickHouse folds a constant operation in a branch
            // not taken and raises its own (measured), so each is its non-raising form, the guard's message the one
            // a zero divisor raises; unguarded, a zero divisor is NULL, as DuckDB's (the OrZero forms answer 0). Pure's
            // mod takes the divisor's sign: ((a rem b) + b) rem b
            // (moduloOrZero takes no Decimal, measured; a Decimal's is plain modulo, under the same guard)
            case REM -> writer.append("(CASE WHEN ").expr(a.get(1), 0).append(" = 0 THEN NULL ELSE ")
                    .append(modulo(a)).append("(").piece(operand(a, 0)).append(", ").piece(operand(a, 1)).append(") END)");
            case MOD -> writer.append("(CASE WHEN ").expr(a.get(1), 0).append(" = 0 THEN NULL ELSE ").append(modulo(a))
                    .append("(").append(modulo(a)).append("(").piece(operand(a, 0)).append(", ").piece(operand(a, 1))
                    .append(") + ").piece(operand(a, 1)).append(", ").piece(operand(a, 1)).append(") END)");
            case INT_DIVIDE -> writer.append("(CASE WHEN ").expr(a.get(1), 0).append(" = 0 THEN NULL ELSE intDivOrZero(")
                    .expr(a.get(0), 0).append(", ").expr(a.get(1), 0).append(") END)");
            case BOOL_TO_TEXT -> writer.append("toString(").expr(a.get(0), 0).append(")");
            case BIT_NOT -> writer.append("bitNot(toInt64(").expr(a.get(0), 0).append("))");
            case FLOOR_RAW -> writer.append("floor(").expr(a.get(0), 0).append(")");
            case COT -> writer.append("(1 / tan(").expr(a.get(0), 0).append("))");
            case ROUND_HALF_UP -> {
                writer.append("round(toDecimal128(").expr(a.get(0), 0).append(", 18)");
                if (a.size() > 1) {
                    writer.append(", ").expr(a.get(1), 0);
                }
                yield writer.append(")");
            }

            // exp and log are approximations here (exp(1) is 2.718281828460626, log(e()) 0.9999999987491066, measured);
            // pow and log2 are exact, DuckDB's bits
            case EXP -> writer.append("pow(e(), toFloat64(").expr(a.get(0), 0).append("))");
            case LN -> writer.append("(log2(toFloat64(").expr(a.get(0), 0).append(")) / log2(e()))");
            // tanh too (tanh(3.14) is 1.2e-8 off, measured): 1 - 2 / (e^2x + 1), +-1 where e^2x overflows
            case TANH -> writer.append("(1 - 2 / (pow(e(), 2 * toFloat64(").expr(a.get(0), 0).append(")) + 1))");
            // a Decimal and a Float64 have no supertype here: unified (arrayLit's rule)
            case GREATEST, LEAST -> writer.append(c.fn() == SqlFn.GREATEST ? "greatest(" : "least(")
                    .join(a, ", ", unified(a)).append(")");
            // ---- strings (UTF-8: the plain ones count bytes)
            // (an unsigned answer is an Int64: a CASE of UInt64 and Int64 has no common type, measured)
            case LENGTH -> writer.append("toInt64(lengthUTF8(").expr(a.get(0), 0).append("))");
            case LOWER -> writer.append("lowerUTF8(").expr(a.get(0), 0).append(")");
            case UPPER -> writer.append("upperUTF8(").expr(a.get(0), 0).append(")");
            case UC_FIRST -> writer.append("concat(upperUTF8(substringUTF8(").expr(a.get(0), 0)
                    .append(", 1, 1)), substringUTF8(").expr(a.get(0), 0).append(", 2))");
            case LC_FIRST -> writer.append("concat(lowerUTF8(substringUTF8(").expr(a.get(0), 0)
                    .append(", 1, 1)), substringUTF8(").expr(a.get(0), 0).append(", 2))");
            case SUBSTRING -> writer.append("substringUTF8(").list(a).append(")");
            case LEFT -> writer.append("leftUTF8(").list(a).append(")");
            case RIGHT -> writer.append("rightUTF8(").list(a).append(")");
            case STRPOS -> writer.append("toInt64(positionUTF8(").list(a).append("))");
            case REVERSE_STRING -> writer.append("reverseUTF8(").expr(a.get(0), 0).append(")");
            case REPLACE -> writer.append("replaceAll(").list(a).append(")");
            case LEVENSHTEIN -> writer.append("toInt64(editDistanceUTF8(").list(a).append("))");
            case REPEAT_STR -> writer.append("repeat(").expr(a.get(0), 0).append(", toUInt64(greatest(").expr(a.get(1), 0)
                    .append(", 0)))");
            case ASCII_CODE -> writer.append("toInt64(ascii(").expr(a.get(0), 0).append("))");
            // DuckDB's split_part: the empty string past the end
            // a function making a list takes no Nullable text (a list is never NULL, measured): the text past NULL,
            // its answer NULL's (split_part's NULL, a split's or a match list's empty list)
            case SPLIT_PART -> writer.append("if(isNull(").expr(a.get(0), 0).append("), NULL, coalesce(arrayElement(")
                    .append("splitByString(").expr(a.get(1), 0).append(", ifNull(").expr(a.get(0), 0).append(", '')), ")
                    .expr(a.get(2), 0).append("), ''))");
            case MATCHES -> writer.append("match(").expr(a.get(0), 0).append(", ").expr(a.get(1), 0).append(")");
            case REGEXP_FULL_MATCH -> writer.append("match(").expr(a.get(0), 0).append(", ").piece(anchored(a.get(1)))
                    .append(")");
            case REGEXP_EXTRACT -> {
                if (a.size() > 2 && !(a.get(2) instanceof SqlExpr.IntLit g && g.value() == 0)) {
                    yield writer.append("regexpExtract(").expr(a.get(0), 0).append(", ").expr(a.get(1), 0).append(", ")
                            .expr(a.get(2), 0).append(")");
                }
                yield writer.append("regexpExtract(").expr(a.get(0), 0).append(", ").expr(a.get(1), 0).append(", 0)");
            }
            // extractAll answers the first group where the pattern has one (measured): the pattern in one group of
            // its own is the whole match, DuckDB's group 0; a numbered group from every match's groups
            case REGEXP_EXTRACT_ALL -> {
                writer.append("if(isNull(").expr(a.get(0), 0).append("), [], ");
                SqlWriter.Piece text = w -> w.append("ifNull(").expr(a.get(0), 0).append(", '')");
                yield (a.size() > 2 && !(a.get(2) instanceof SqlExpr.IntLit g && g.value() == 0)
                        ? writer.append("arrayMap(\"__g\" -> arrayElement(\"__g\", ").expr(a.get(2), 0)
                                .append("), extractAllGroupsVertical(").piece(text).append(", ").expr(a.get(1), 0)
                                .append("))")
                        : writer.append("extractAll(").piece(text).append(", concat('(', ").expr(a.get(1), 0)
                                .append(", ')'))")).append(")");
            }
            // DuckDB replaces the first match unless 'g'; its \1 group references are ClickHouse's own
            case REGEXP_REPLACE -> {
                boolean global = a.size() > 3 && a.get(3) instanceof SqlExpr.StringLit o && o.value().contains("g");
                yield writer.append(global ? "replaceRegexpAll(" : "replaceRegexpOne(").list(a.subList(0, 3)).append(")");
            }
            case SPLIT -> writer.append("if(isNull(").expr(a.get(0), 0).append("), [], splitByString(").expr(a.get(1), 0)
                    .append(", ifNull(").expr(a.get(0), 0).append(", '')))");
            case TRIM, LTRIM, RTRIM -> {
                String side = c.fn() == SqlFn.TRIM ? "BOTH" : c.fn() == SqlFn.LTRIM ? "LEADING" : "TRAILING";
                if (a.size() == 1) {
                    yield writer.append("trim(" + side + " ' ' FROM ").expr(a.get(0), 0).append(")");
                }
                yield writer.append("trim(" + side + " ").expr(a.get(1), 0).append(" FROM ").expr(a.get(0), 0).append(")");
            }
            case ENCODE_BASE64 -> writer.append("base64Encode(").expr(a.get(0), 0).append(")");
            case DECODE_BASE64 -> writer.append("base64Decode(").expr(a.get(0), 0).append(")");
            case MD5 -> writer.append("lower(hex(MD5(").expr(a.get(0), 0).append(")))");
            case SHA1 -> writer.append("lower(hex(SHA1(").expr(a.get(0), 0).append(")))");
            case SHA256 -> writer.append("lower(hex(SHA256(").expr(a.get(0), 0).append(")))");
            case GUID -> writer.append("toString(generateUUIDv4())");
            // printf takes native numbers and strings (a Decimal is refused, measured)
            // printf takes a Float64 for %f and only a String for %s: a Float under %s is its Pure text
            case FORMAT -> {
                List<Character> conversions = a.get(0) instanceof SqlExpr.StringLit f ? conversions(f.value()) : null;
                writer.append("printf(").expr(a.get(0), 0);
                for (int i = 1; i < a.size(); i++) {
                    SqlExpr x = a.get(i);
                    boolean real = typeOf(x) instanceof SqlType.Decimal || x instanceof SqlExpr.FloatLit
                            || x instanceof SqlExpr.DecimalLit || typeOf(x) == SqlType.Scalar.DOUBLE;
                    writer.append(", ");
                    boolean asText = conversions != null && i - 1 < conversions.size() && conversions.get(i - 1) == 's';
                    if (real && asText) {
                        writer.expr(FloatCanonText.of(new SqlExpr.Cast(x, SqlType.Scalar.DOUBLE)), 0);
                    } else if (asText && (typeOf(x) == SqlType.Scalar.BOOLEAN || typeOf(x) == SqlType.Scalar.BIGINT
                            || typeOf(x) == SqlType.Scalar.INTEGER)) {
                        // %s takes only a String (a Bool or an Int64 is refused, measured): its text
                        writer.append("toString(").expr(x, 0).append(")");
                    } else if (real && typeOf(x) != SqlType.Scalar.DOUBLE) {
                        writer.append("toFloat64(").expr(x, 0).append(")");
                    } else {
                        writer.expr(x, 0);
                    }
                }
                yield writer.append(")");
            }
            case CURRENT_USER_FN -> writer.append("currentUser()");

            // ---- temporal (temporal(): call is held to 250 lines)
            case NOW, DATE_TRUNC_DAY, DATE_TRUNC, PARSE_DATE, EXTRACT, MAKE_DATE, MAKE_TIMESTAMP, ADD_INTERVAL,
                 ADD_INTERVAL_TEMPORAL, DATE_DIFF, TIME_BUCKET, TIMEZONE, EPOCH_SECONDS, EPOCH_MS,
                 FROM_EPOCH_SECONDS, FROM_EPOCH_MS, DAYNAME, MONTHNAME, STRFTIME, STRPTIME -> temporal(writer, c, a);
            // ---- lists whose first argument is no list
            case LIST_LENGTH -> writer.append("toInt64(length(").piece(list(a.get(0))).append("))");
            // DuckDB's range: [start, stop) by step, as ClickHouse's
            case RANGE_FN -> {
                if (a.size() == 1) {
                    yield writer.append("arrayMap(\"__g\" -> toInt64(\"__g\"), range(toInt64(").expr(a.get(0), 0)
                            .append(")))");
                }
                if (a.size() == 3) {
                    // a constant step is folded before its guard runs (range's own error over the guard's, measured)
                    yield writer.append("arrayMap(\"__g\" -> toInt64(\"__g\"), range(").expr(a.get(0), 0).append(", ")
                            .expr(a.get(1), 0).append(", materialize(").expr(a.get(2), 0).append(")))");
                }
                yield writer.append("arrayMap(\"__g\" -> toInt64(\"__g\"), range(").list(a).append("))");
            }
            case REPEAT_VALUE -> writer.append("arrayWithConstant(toUInt64(greatest(").expr(a.get(1), 0).append(", 0)), ")
                    .expr(a.get(0), 0).append(")");

            // ---- maps
            case MAP_EMPTY -> writer.append("map()");
            case MAP_FROM_LISTS -> writer.append("mapFromArrays(").list(a).append(")");
            // the entries are (key, value) tuples
            case MAP_FROM_ENTRIES -> writer.append("mapFromArrays(arrayMap(\"__e\" -> tupleElement(\"__e\", 1), ")
                    .expr(a.get(0), 0).append("), arrayMap(\"__e\" -> tupleElement(\"__e\", 2), ").expr(a.get(0), 0)
                    .append("))");
            case MAP_EXTRACT -> writer.append("if(mapContains(").expr(a.get(0), 0).append(", ").expr(a.get(1), 0)
                    .append("), [").expr(a.get(0), 0).append("[").expr(a.get(1), 0).append("]], [])");
            case MAP_CONCAT -> writer.append("mapUpdate(").list(a).append(")");

            // ---- variant
            // in DuckDB's json_type vocabulary, which the lowering compares against
            case JSON_TYPE -> writer.append("multiIf(isNull(").expr(a.get(0), 0).append("), NULL, JSONType(").expr(a.get(0), 0)
                    .append(") = 'Null', 'NULL', JSONType(").expr(a.get(0), 0).append(") = 'String', 'VARCHAR', JSONType(")
                    .expr(a.get(0), 0).append(") = 'Bool', 'BOOLEAN', JSONType(").expr(a.get(0), 0)
                    .append(") IN ('Int64', 'UInt64'), 'BIGINT', JSONType(").expr(a.get(0), 0)
                    .append(") = 'Array', 'ARRAY', JSONType(").expr(a.get(0), 0).append(") = 'Object', 'OBJECT', 'DOUBLE')");
            case JSON_ARRAY_LENGTH -> writer.append("JSONLength(").expr(a.get(0), 0).append(")");
            case JSON_PRETTY -> throw wall(c.fn(), "ClickHouse has no JSON pretty-printer");
            case JSON_MERGE_PATCH -> throw wall(c.fn(), "ClickHouse has no JSON merge patch");
        };
    }

    /**
     * DuckDB's time_bucket: a fixed unit binned from 1970-01-01 (a week from the Monday 1969-12-29) in epoch
     * microseconds, floored in integers; a month or a year by the month index since 1970-01, floored to the bucket's
     * size. {@code toStartOfInterval} counts unsigned units, wrapping before 1970 (1951 bucketed to 2087, measured).
     */
    private SqlWriter.Piece timeBucket(List<SqlExpr> a) {
        String unit = literal(a.get(0), "TIME_BUCKET");
        SqlWriter.Piece n = w -> w.append("toInt64(").expr(a.get(1), 0).append(")");
        Long micros = BUCKET_MICROS.get(unit);
        if (micros != null) {
            long origin = unit.endsWith("weeks") ? -3L * 86400000000L : 0L;
            SqlWriter.Piece x = w -> w.append("(toUnixTimestamp64Micro(toDateTime64(").expr(a.get(2), 0)
                    .append(", 6, 'UTC')) - (" + origin + "))");
            SqlWriter.Piece u = w -> w.append("(").piece(n).append(" * " + micros + ")");
            return w -> w.append("fromUnixTimestamp64Micro(").piece(x).append(" - ((").piece(x).append(" % ").piece(u)
                    .append(") + ").piece(u).append(") % ").piece(u).append(" + (" + origin + "), 'UTC')");
        }
        Integer months = BUCKET_MONTHS.get(unit);
        if (months == null) {
            throw new DialectCapability("a " + unit + " time bucket reached ClickHouse");
        }
        SqlWriter.Piece m = w -> w.append("((toYear(").expr(a.get(2), 0).append(") - 1970) * 12 + toMonth(")
                .expr(a.get(2), 0).append(") - 1)");
        SqlWriter.Piece size = w -> w.append("(").piece(n).append(" * " + months + ")");
        return w -> w.append("toDateTime64(addMonths(toDate32('1970-01-01'), ").piece(m).append(" - ((").piece(m)
                .append(" % ").piece(size).append(") + ").piece(size).append(") % ").piece(size).append("), 6, 'UTC')");
    }

    private static final java.util.Map<String, Long> BUCKET_MICROS = java.util.Map.of(
            "to_weeks", 7L * 86400000000L, "to_days", 86400000000L, "to_hours", 3600000000L,
            "to_minutes", 60000000L, "to_seconds", 1000000L, "to_milliseconds", 1000L, "to_microseconds", 1L);

    private static final java.util.Map<String, Integer> BUCKET_MONTHS = java.util.Map.of("to_months", 1, "to_years", 12);

    /** A modulo's operand: beside a Decimal, both are Decimal128s of the larger scale (an Int64 modulo a Decimal is
     *  wrong, 5 % 2.5 is 0.5; and Decimals of two scales too, 5 % 1.5 at scales 18 and 1 is 5e-18, measured). */
    private static SqlWriter.Piece operand(List<SqlExpr> a, int i) {
        Integer s0 = decimalScale(a.get(0));
        Integer s1 = decimalScale(a.get(1));
        if (s0 == null && s1 == null) {
            return w -> w.expr(a.get(i), 0);
        }
        int scale = Math.max(s0 == null ? 0 : s0, s1 == null ? 0 : s1);
        return w -> w.append("toDecimal128(").expr(a.get(i), 0).append(", " + scale + ")");
    }

    /** {@code e}'s scale where it is a Decimal (a Float literal is one by its digits), else null. */
    private static @com.legend.base.Nullable Integer decimalScale(SqlExpr e) {
        if (e instanceof SqlExpr.FloatLit f && com.legend.sql.SqlTyping.floatDecimal(f.value()) instanceof java.math.BigDecimal b) {
            return Math.max(b.stripTrailingZeros().scale(), 0);
        }
        return typeOf(e) instanceof SqlType.Decimal d ? d.scale() : null;
    }

    /** The modulo for these operands: moduloOrZero (no constant-folded raise), plain modulo over a Decimal. */
    private static String modulo(List<SqlExpr> a) {
        return typeOf(a.get(0)) instanceof SqlType.Decimal || typeOf(a.get(1)) instanceof SqlType.Decimal
                || a.get(0) instanceof SqlExpr.FloatLit || a.get(1) instanceof SqlExpr.FloatLit ? "modulo" : "moduloOrZero";
    }

    /** The temporal arms of {@link #call} (held to its 250 lines). */
    private SqlWriter temporal(SqlWriter writer, SqlExpr.Call c, List<SqlExpr> a) {
        return switch (c.fn()) {
            case NOW -> writer.append("now64(6, 'UTC')");
            case DATE_TRUNC_DAY -> writer.append("toDate32(").expr(a.get(0), 0).append(")");
            case DATE_TRUNC -> {
                String part = literal(a.get(0), "DATE_TRUNC");
                yield switch (part) {
                    case "year" -> writer.append("toDate32(toStartOfYear(").expr(a.get(1), 0).append("))");
                    case "quarter" -> writer.append("toDate32(toStartOfQuarter(").expr(a.get(1), 0).append("))");
                    case "month" -> writer.append("toDate32(toStartOfMonth(").expr(a.get(1), 0).append("))");
                    case "week" -> writer.append("toDate32(toMonday(").expr(a.get(1), 0).append("))");
                    case "day" -> writer.append("toDateTime64(toDate32(").expr(a.get(1), 0).append("), 6, 'UTC')");
                    case "hour" -> writer.append("toStartOfHour(").expr(a.get(1), 0).append(")");
                    case "minute" -> writer.append("toStartOfMinute(").expr(a.get(1), 0).append(")");
                    case "second" -> writer.append("toStartOfSecond(").expr(a.get(1), 0).append(")");
                    default -> throw new DialectCapability("date_trunc '" + part + "' has no ClickHouse spelling");
                };
            }
            case PARSE_DATE -> writer.append("parseDateTime64BestEffort(").expr(a.get(0), 0).append(", 6, 'UTC')");
            case EXTRACT -> writer.piece(extract(a));
            case MAKE_DATE -> writer.append("makeDate32(").list(a).append(")");
            case MAKE_TIMESTAMP -> {
                if (a.size() != 6) {
                    throw wall(c.fn(), "make_timestamp of " + a.size() + " arguments");
                }
                yield writer.append("addMicroseconds(toDateTime64(makeDateTime(").list(a.subList(0, 5))
                        .append(", 0, 'UTC'), 6, 'UTC'), toInt64(round(toFloat64(").expr(a.get(5), 0)
                        .append(") * 1000000)))");
            }
            case ADD_INTERVAL, ADD_INTERVAL_TEMPORAL -> {
                String fn = switch (literal(a.get(0), c.fn().name())) {
                    case "to_years" -> "addYears";
                    case "to_months" -> "addMonths";
                    case "to_weeks" -> "addWeeks";
                    case "to_days" -> "addDays";
                    case "to_hours" -> "addHours";
                    case "to_minutes" -> "addMinutes";
                    case "to_seconds" -> "addSeconds";
                    case "to_milliseconds" -> "addMilliseconds";
                    case "to_microseconds" -> "addMicroseconds";
                    default -> throw wall(c.fn(), "the unit " + a.get(0));
                };
                yield writer.append(fn + "(").expr(a.get(2), 0).append(", ").expr(a.get(1), 0).append(")");
            }
            // DuckDB's date_diff counts boundaries crossed, as ClickHouse's dateDiff does
            // the platform's week index counts days from an ancient Sunday (0001-01-07, Scalars.sundayIndex), before
            // Date32's first day (1900-01-01: an earlier date clamps to it, measured); a difference of indices is the
            // same from any Sunday a whole number of weeks away, and 1900-01-07 is one, within the range
            case DATE_DIFF -> {
                writer.append("dateDiff(").append(stringLit(literal(a.get(0), "DATE_DIFF"))).append(", ");
                if (a.get(1) instanceof SqlExpr.DateLit d && d.iso().equals("0001-01-07")) {
                    writer.append(dateLit("1900-01-07"));
                } else {
                    writer.expr(a.get(1), 0);
                }
                yield writer.append(", ").expr(a.get(2), 0).append(")");
            }
            case TIME_BUCKET -> writer.piece(timeBucket(a));
            // the wall clock in the zone at the instant (DuckDB's of a zoned value, Trino's), as a naive UTC value:
            // toTimeZone keeps the instant and changes only how it prints (measured)
            case TIMEZONE -> writer.append("addSeconds(toDateTime64(").expr(a.get(1), 0)
                    .append(", 6, 'UTC'), timeZoneOffset(toTimeZone(toDateTime64(").expr(a.get(1), 0).append(", 6, 'UTC'), ")
                    .expr(a.get(0), 0).append(")))");
            // (of a DateTime64: a Date32 is refused, measured)
            case EPOCH_SECONDS -> writer.append("(toFloat64(toUnixTimestamp64Micro(toDateTime64(").expr(a.get(0), 0)
                    .append(", 6, 'UTC'))) / 1000000)");
            case EPOCH_MS -> writer.append("toUnixTimestamp64Milli(toDateTime64(").expr(a.get(0), 0)
                    .append(", 6, 'UTC'))");
            case FROM_EPOCH_SECONDS -> writer.append("fromUnixTimestamp64Micro(toInt64(").expr(a.get(0), 0)
                    .append(" * 1000000), 'UTC')");
            case FROM_EPOCH_MS -> writer.append("fromUnixTimestamp64Milli(toInt64(").expr(a.get(0), 0)
                    .append("), 'UTC')");
            case DAYNAME -> writer.append("dateName('weekday', ").expr(a.get(0), 0).append(")");
            case MONTHNAME -> writer.append("dateName('month', ").expr(a.get(0), 0).append(")");
            case STRFTIME -> {
                if (!(a.get(1) instanceof SqlExpr.FormatLit)) {
                    throw wall(c.fn(), "a date format that is not a format literal: its codes are DuckDB's");
                }
                yield writer.piece(formatted(a.get(0), ((SqlExpr.FormatLit) a.get(1)).parts()));
            }
            case STRPTIME -> {
                if (!(a.get(1) instanceof SqlExpr.FormatLit)) {
                    throw wall(c.fn(), "a date format that is not a format literal: its codes are DuckDB's");
                }
                yield writer.append("toDateTime64(parseDateTime(").expr(a.get(0), 0).append(", ").expr(a.get(1), 0)
                        .append(", 'UTC'), 6, 'UTC')");
            }

            default -> throw new IllegalStateException("not a temporal call: " + c.fn());
        };
    }

    /** A CONCAT argument as text (ClickHouse's concat is NULL if any argument is: a NULL is ''). */
    private void asText(SqlWriter w, SqlExpr e) {
        w.append("coalesce(toString(").expr(e, 0).append("), '')");
    }

    private static DialectCapability wall(SqlFn fn, String why) {
        return new DialectCapability(fn + " reached ClickHouse: " + why);
    }

    private static String literal(SqlExpr e, String what) {
        if (e instanceof SqlExpr.StringLit s) {
            return s.value();
        }
        throw new DialectCapability(what + " with a non-literal part reached ClickHouse");
    }

    private SqlWriter.Piece anchored(SqlExpr p) {
        if (p instanceof SqlExpr.StringLit lit) {
            String s = stringLit("^(?:" + lit.value() + ")$");
            return w -> w.append(s);
        }
        return w -> w.append("concat('^(?:', ").expr(p, 0).append(", ')$')");
    }

    /** DuckDB's date_part names → ClickHouse's functions; DuckDB's dow is Sunday 0 (ClickHouse's toDayOfWeek is
     *  Monday 1 .. Sunday 7). */
    private SqlWriter.Piece extract(List<SqlExpr> a) {
        String part = literal(a.get(0), "EXTRACT");
        String fn = switch (part) {
            case "year" -> "toYear";
            case "quarter" -> "toQuarter";
            case "month" -> "toMonth";
            case "week" -> "toISOWeek";
            case "day" -> "toDayOfMonth";
            case "hour" -> "toHour";
            case "minute" -> "toMinute";
            case "second" -> "toSecond";
            case "doy" -> "toDayOfYear";
            case "isodow" -> "toDayOfWeek";
            case "dow" -> "dow";
            default -> throw new DialectCapability("date part '" + part + "' has no probed ClickHouse spelling");
        };
        if ("dow".equals(fn)) {
            return w -> w.append("toInt64(toDayOfWeek(").expr(a.get(1), 0).append(") % 7)");
        }
        return w -> w.append("toInt64(" + fn + "(").expr(a.get(1), 0).append("))");
    }

    // ==================================================================
    // Hooks of the base
    // ==================================================================

    /** Pure's hashCode is a signed 64-bit integer: cityHash64's bits, read signed. */
    @Override
    protected SqlWriter hashSigned(SqlWriter writer, List<SqlExpr> a) {
        // no value (an empty collection's NULL) still hashes: Pure's hashCode([]) is one
        return writer.append("coalesce(reinterpretAsInt64(cityHash64(").list(a).append(")), toInt64(0))");
    }

    /** Pure round is half-EVEN: ClickHouse's round of a Float is (measured); of a Decimal it is roundBankers. */
    @Override
    protected SqlWriter roundHalfEven(SqlWriter writer, List<SqlExpr> a) {
        boolean dbl = typeOf(a.get(0)) == SqlType.Scalar.DOUBLE;
        writer.append(dbl ? "round(" : "roundBankers(").expr(a.get(0), 0);
        if (a.size() > 1) {
            writer.append(", ").expr(a.get(1), 0);
        }
        return writer.append(")");
    }

    @Override
    protected SqlWriter bitOp(SqlWriter writer, SqlFn fnName, List<SqlExpr> a) {
        String fn = switch (fnName) {
            case BIT_AND -> "bitAnd";
            case BIT_OR -> "bitOr";
            case BIT_XOR -> "bitXor";
            case BIT_SHIFT_LEFT -> "bitShiftLeft";
            case BIT_SHIFT_RIGHT -> "bitShiftRight";
            default -> throw new IllegalStateException("not a bit op: " + fnName);
        };
        return writer.append(fn + "(toInt64(").expr(a.get(0), 0).append("), ").expr(a.get(1), 0).append(")");
    }

    // ==================================================================
    // Aggregates and windows
    // ==================================================================

    private record SortKey(SqlWriter.Piece key, boolean ascending, boolean nullsFirst,
            @com.legend.base.Nullable SqlType type) {
    }

    /** A key in descending order as a key arraySort orders ascending: a number negated; a date or a timestamp its
     *  number negated (negate refuses them, measured), a boolean its 0 or 1; a string its bytes negated, then 1 (arrays
     *  compare element by element, and a longer string sorts first: 'abc' before 'ab', measured). */
    private static String descending(String key, @com.legend.base.Nullable SqlType type) {
        return switch (type) {
            case SqlType.Scalar.BIGINT, SqlType.Scalar.INTEGER, SqlType.Scalar.DOUBLE, SqlType.Scalar.HUGEINT -> "-" + key;
            case SqlType.Decimal d -> "-" + key;
            case SqlType.Scalar.TIMESTAMP, SqlType.Scalar.TIMESTAMPTZ -> "-toUnixTimestamp64Micro(toDateTime64(" + key
                    + ", 6, 'UTC'))";
            case SqlType.Scalar.DATE -> "-toInt32(" + key + ")";
            case SqlType.Scalar.BOOLEAN -> "-toInt8(" + key + ")";
            case SqlType.Scalar.VARCHAR -> "arrayPushBack(arrayMap(\"__i\" -> -toInt16(reinterpretAsUInt8(substring(ifNull("
                    + key + ", ''), \"__i\", 1))), range(1, length(ifNull(" + key + ", '')) + 1)), 1)";
            case null -> throw new DialectCapability("a descending sort key of no type reached ClickHouse");
            case SqlType t -> throw new DialectCapability("a descending sort key of " + t + " reached ClickHouse");
        };
    }

    /** The values collected into a list in the keys' order, NULL values kept (groupArray drops them): each row's value
     *  in a tuple (a tuple is never NULL), sorted by the keys, then read back. */
    private SqlWriter.Piece collected(SqlWriter.Piece value, List<SortKey> keys) {
        return collected(value, keys, null);
    }

    private SqlWriter.Piece collected(SqlWriter.Piece value, List<SortKey> keys,
            SqlExpr.@com.legend.base.Nullable WindowCall win) {
        return w -> {
            w.append("arrayMap(\"__s\" -> tupleElement(\"__s\", 1), ");
            if (!keys.isEmpty()) {
                w.append("arraySort(\"__s\" -> (");
                for (int i = 0; i < keys.size(); i++) {
                    SortKey k = keys.get(i);
                    String key = "tupleElement(\"__s\", " + (i + 2) + ")";
                    // NULLs where the key says: isNull sorts false (0) before true (1)
                    w.append(i > 0 ? ", " : "").append(k.nullsFirst() ? "isNotNull(" + key + "), "
                            : "isNull(" + key + "), ");
                    w.append(k.ascending() ? key : descending(key, k.type()));
                }
                w.append("), ");
            }
            w.append("groupArray(tuple(").piece(value);
            for (SortKey k : keys) {
                w.append(", ").piece(k.key());
            }
            ov(w.append("))"), win);
            if (!keys.isEmpty()) {
                w.append(")");
            }
            w.append(")");
        };
    }

    private SortKey sortKeyOf(SqlSelect.SortKey k) {
        boolean nullsFirst = k.nullOrder() != null
                ? k.nullOrder() == SqlSelect.SortKey.NullOrder.NULLS_FIRST
                : !k.ascending();
        return new SortKey(w -> w.expr(k.expr(), 0), k.ascending(), nullsFirst, typeOf(k.expr()));
    }

    @Override
    protected SqlWriter reducer(SqlWriter writer, SqlAgg.Reducer r) {
        return reducerIn(writer, r, null);
    }

    /** {@code w}'s OVER clause after an aggregate call, when the reducer is windowed: a composite reducer windows each
     *  of its aggregates (ClickHouse refuses a window over its scalar wrapper: "Aggregate function with name 'if' does
     *  not exist", measured). */
    private SqlWriter ov(SqlWriter writer, SqlExpr.@com.legend.base.Nullable WindowCall w) {
        return w == null ? writer : over(writer, w);
    }

    /** A reducer, windowed over {@code win} when it is one. */
    private SqlWriter reducerIn(SqlWriter writer, SqlAgg.Reducer r, SqlExpr.@com.legend.base.Nullable WindowCall win) {
        return switch (r.fn()) {
            case COUNT -> {
                writer.append("toInt64(");
                super.reducer(writer, r);
                yield ov(writer, win).append(")");
            }
            // corr takes no Decimal beside an Int64 (measured): Float64 operands
            case CORR -> ov(writer.append("corrOrNull(toFloat64(").expr(r.args().get(0), 0).append("), toFloat64(")
                    .expr(r.args().get(1), 0).append("))"), win);
            case SUM, AVG, MIN, MAX, ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST, NTILE,
                 FIRST_VALUE, LAST_VALUE, NTH_VALUE -> {
                if (!r.orderBy().isEmpty()) {
                    throw new DialectCapability("an ordered " + r.fn() + " reached ClickHouse");
                }
                yield ov(super.reducer(writer, r), win);
            }
            case STDDEV_SAMP, STDDEV -> ov(plain(writer, "stddevSampStableOrNull", r), win);
            case STDDEV_POP -> ov(plain(writer, "stddevPopStableOrNull", r), win);
            case VAR_SAMP, VARIANCE -> ov(plain(writer, "varSampStableOrNull", r), win);
            case VAR_POP -> ov(plain(writer, "varPopStableOrNull", r), win);
            // one row's sample covariance is NaN here, DuckDB's NULL (measured)
            case COVAR_SAMP -> {
                writer.append("if(");
                ov(writer.append("count(").expr(r.args().get(0), 0).append(")"), win).append(" < 2, NULL, ");
                yield ov(plain(writer, "covarSampStableOrNull", r), win).append(")");
            }
            case COVAR_POP -> ov(plain(writer, "covarPopStableOrNull", r), win);
            case BOOL_AND -> ov(plain(writer, "minOrNull", r), win);
            case BOOL_OR -> ov(plain(writer, "maxOrNull", r), win);
            case ANY_VALUE -> ov(plain(writer, "anyOrNull", r), win);
            case LAG, LEAD -> throw new IllegalStateException(r.fn() + " is a window function: windowCall writes it");
            case STRING_AGG -> {
                if (r.args().size() != 2) {
                    throw new DialectCapability("a STRING_AGG of " + r.args().size() + " arguments reached ClickHouse");
                }
                SqlExpr v = r.args().get(0);
                SqlWriter.Piece values = r.distinct()
                        ? w -> ov(w.append("arrayDistinct(groupArray(").expr(v, 0).append(")"), win).append(")")
                        : r.orderBy().isEmpty()
                                ? w -> ov(w.append("groupArray(").expr(v, 0).append(")"), win)
                                : w -> w.append("arrayFilter(\"__x\" -> isNotNull(\"__x\"), ").piece(collected(
                                        x -> x.expr(v, 0), r.orderBy().stream().map(this::sortKeyOf).toList(), win))
                                        .append(")");
                yield writer.append("if(empty(").piece(values).append("), NULL, arrayStringConcat(").piece(values)
                        .append(", ").expr(r.args().get(1), 0).append("))");
            }
            case MEDIAN -> ov(writer.append("quantileExactInclusiveOrNull(0.5)(toFloat64(").expr(r.args().get(0), 0)
                    .append("))"), win);
            case QUANTILE_CONT, QUANTILE_DISC -> {
                if (r.args().size() != 2 || r.distinct() || r.orderBy().size() > 1) {
                    throw new DialectCapability(r.fn() + " of this shape reached ClickHouse");
                }
                boolean desc = !r.orderBy().isEmpty() && !r.orderBy().get(0).ascending();
                if (r.fn() == SqlAgg.Fn.QUANTILE_CONT) {
                    writer.append("quantileExactInclusiveOrNull(");
                    if (desc) {
                        writer.append("1 - ");
                    }
                    yield ov(writer.expr(r.args().get(1), 6).append(")(toFloat64(").expr(r.args().get(0), 0).append("))"),
                            win);
                }
                // DuckDB's quantile_disc: the sorted values' element at max(ceil(n q) - 1, 0), 0-based
                SqlWriter.Piece sorted = w -> ov(w.append("arraySort(groupArray(").expr(r.args().get(0), 0).append(")"),
                        win).append(")");
                SqlWriter.Piece q = desc ? w -> w.append("(1 - ").expr(r.args().get(1), 6).append(")")
                        : w -> w.expr(r.args().get(1), 6);
                yield writer.append("if(empty(").piece(sorted).append("), NULL, arrayElement(").piece(sorted)
                        .append(", toInt64(greatest(ceil(length(").piece(sorted).append(") * ").piece(q)
                        .append(") - 1, 0)) + 1))");
            }
            case MODE -> writer.piece(modeOf(w -> ov(w.append("groupArray(").expr(r.args().get(0), 0).append(")"), win)));
            case LIST -> {
                if (r.distinct() || r.args().size() != 1) {
                    throw new DialectCapability("a distinct LIST aggregate reached ClickHouse");
                }
                yield writer.piece(collected(w -> w.expr(r.args().get(0), 0),
                        r.orderBy().stream().map(this::sortKeyOf).toList(), win));
            }
            case ARG_MAX, ARG_MIN -> {
                if (r.args().size() != 2 || r.distinct() || !r.orderBy().isEmpty()) {
                    throw new DialectCapability(r.fn() + " of this shape reached ClickHouse");
                }
                yield ov(writer.append(r.fn() == SqlAgg.Fn.ARG_MAX ? "argMax(" : "argMin(").list(r.args()).append(")"),
                        win);
            }
            case WAVG, HASH_LIST, IS_DISTINCT_MARK, UNIQUE_VALUE_ONLY ->
                    throw new IllegalStateException("lowering marker " + r.fn() + " reached the renderer");
        };
    }

    /** An aggregate over no row is NULL in SQL, its type's default here unless the aggregate is its {@code OrNull}
     *  form. */
    @Override
    protected String aggregateName(SqlAgg.Fn fn) {
        return switch (fn) {
            case SUM -> "sumOrNull";
            case MIN -> "minOrNull";
            case MAX -> "maxOrNull";
            case AVG -> "avgOrNull";
            case CORR -> "corrOrNull";
            default -> super.aggregateName(fn).toLowerCase(java.util.Locale.ROOT);
        };
    }

    private SqlWriter plain(SqlWriter writer, String fn, SqlAgg.Reducer r) {
        if (!r.orderBy().isEmpty()) {
            throw new DialectCapability("an ordered " + r.fn() + " reached ClickHouse");
        }
        return writer.append(fn + "(").append(r.distinct() ? "DISTINCT " : "").list(r.args()).append(")");
    }

    private static boolean isInterval(SqlExpr.WindowCall.Frame.Bound b) {
        return b instanceof SqlExpr.WindowCall.Frame.Bound.IntervalPreceding
                || b instanceof SqlExpr.WindowCall.Frame.Bound.IntervalFollowing;
    }

    /**
     * A RANGE frame of interval offsets: ClickHouse takes only a number as a RANGE offset (in days over a Date, and
     * none at all over a DateTime64: "The RANGE OFFSET frame ... is not implemented", measured), so the window is
     * ordered by its key's epoch milliseconds and each offset is its milliseconds -- a unit of fixed length, a week
     * down to a millisecond; a month's or a year's has none, and a microsecond is finer than the key.
     */
    private static SqlExpr.WindowCall epochFramed(SqlExpr.WindowCall w, SqlExpr.WindowCall.Frame frame) {
        if (w.orderBy().size() != 1) {
            throw new DialectCapability("an interval RANGE frame over " + w.orderBy().size() + " order keys reached"
                    + " ClickHouse");
        }
        SqlSelect.SortKey k = w.orderBy().get(0);
        SqlSelect.SortKey epoch = new SqlSelect.SortKey(SqlExpr.Call.of(SqlFn.EPOCH_MS, k.expr()), k.ascending(),
                k.nullOrder(), k.outputName());
        return new SqlExpr.WindowCall(w.fn(), w.partitionBy(), List.of(epoch), new SqlExpr.WindowCall.Frame(
                SqlExpr.WindowCall.Frame.Kind.RANGE, millis(frame.from()), millis(frame.to())), w.type());
    }

    /**
     * A RANGE frame of offsets reaches a NULL order key from any value (measured: {@code ORDER BY o RANGE BETWEEN 1
     * PRECEDING AND 1 FOLLOWING} over 1, 1, NULL sums all three on every row), where the standard's NULL is a peer of
     * NULLs only. So the key is ordered with NULL as its type's extreme on the NULLs' side: a value's frame reaches the
     * NULLs only from within its offset of that extreme (Int64's bound, or an infinity), a NULL's frame is its NULL
     * peers, and an unbounded side still reaches them.
     */
    private static SqlExpr.WindowCall nullsAtEdge(SqlExpr.WindowCall w) {
        if (w.orderBy().size() != 1) {
            return w;   // ClickHouse refuses a RANGE frame of offsets over several keys itself
        }
        SqlSelect.SortKey k = w.orderBy().get(0);
        boolean high = k.ascending() == (k.nullOrder() != SqlSelect.SortKey.NullOrder.NULLS_FIRST);
        SqlExpr edge = switch (typeOf(k.expr())) {
            case SqlType.Scalar.BIGINT, SqlType.Scalar.INTEGER -> new SqlExpr.IntLit(high ? Long.MAX_VALUE : Long.MIN_VALUE);
            // an infinity is no Float literal (a literal is a Decimal of its digits): the text cast
            case SqlType.Scalar.DOUBLE -> new SqlExpr.Cast(new SqlExpr.StringLit(high ? "inf" : "-inf"),
                    SqlType.Scalar.DOUBLE);
            case SqlType t -> throw new DialectCapability("a RANGE frame of offsets over a " + t + " key reached"
                    + " ClickHouse, whose frame reaches a NULL key from any value");
            case null -> throw new DialectCapability("a RANGE frame of offsets over an untyped key reached ClickHouse");
        };
        SqlSelect.SortKey keyed = new SqlSelect.SortKey(SqlExpr.Call.of(SqlFn.COALESCE, k.expr(), edge), k.ascending(),
                k.nullOrder(), k.outputName());
        return new SqlExpr.WindowCall(w.fn(), w.partitionBy(), List.of(keyed), w.frame(), w.type());
    }

    private static boolean isOffset(SqlExpr.WindowCall.Frame.Bound b) {
        return b instanceof SqlExpr.WindowCall.Frame.Bound.Preceding
                || b instanceof SqlExpr.WindowCall.Frame.Bound.Following;
    }

    private static SqlExpr.WindowCall.Frame.Bound millis(SqlExpr.WindowCall.Frame.Bound b) {
        return switch (b) {
            case SqlExpr.WindowCall.Frame.Bound.IntervalPreceding p ->
                    new SqlExpr.WindowCall.Frame.Bound.Preceding(Math.multiplyExact(p.n(), unitMillis(p.unit())));
            case SqlExpr.WindowCall.Frame.Bound.IntervalFollowing f ->
                    new SqlExpr.WindowCall.Frame.Bound.Following(Math.multiplyExact(f.n(), unitMillis(f.unit())));
            case SqlExpr.WindowCall.Frame.Bound.UnboundedPreceding u -> b;
            case SqlExpr.WindowCall.Frame.Bound.Preceding p -> b;
            case SqlExpr.WindowCall.Frame.Bound.CurrentRow c -> b;
            case SqlExpr.WindowCall.Frame.Bound.Following f -> b;
            case SqlExpr.WindowCall.Frame.Bound.UnboundedFollowing u -> b;
        };
    }

    private static long unitMillis(String unit) {
        String u = unit.toUpperCase(java.util.Locale.ROOT);
        Long ms = UNIT_MILLIS.get(u.endsWith("S") ? u.substring(0, u.length() - 1) : u);
        if (ms == null) {
            throw new DialectCapability("a " + unit + " RANGE frame offset reached ClickHouse: no fixed length in the"
                    + " key's milliseconds");
        }
        return ms;
    }

    private static final java.util.Map<String, Long> UNIT_MILLIS = java.util.Map.of("WEEK", 604800000L, "DAY",
            86400000L, "HOUR", 3600000L, "MINUTE", 60000L, "SECOND", 1000L, "MILLISECOND", 1L);

    /** {@code lag} and {@code lead} as {@code lagInFrame} and {@code leadInFrame} of the nullable value over the whole
     *  partition (they answer the type's default past it otherwise); an average is a DOUBLE. */
    @Override
    protected SqlWriter windowCall(SqlWriter writer, SqlExpr.WindowCall w) {
        SqlExpr.WindowCall.Frame frame = w.frame();
        if (frame != null && frame.kind() == SqlExpr.WindowCall.Frame.Kind.RANGE
                && (isInterval(frame.from()) || isInterval(frame.to()))) {
            w = epochFramed(w, frame);
        }
        if (w.frame() instanceof SqlExpr.WindowCall.Frame f && f.kind() == SqlExpr.WindowCall.Frame.Kind.RANGE
                && (isOffset(f.from()) || isOffset(f.to()))) {
            w = nullsAtEdge(w);
        }
        if (w.fn() instanceof SqlAgg.ValueFn v && (v.fn() == SqlAgg.Fn.LAG || v.fn() == SqlAgg.Fn.LEAD)) {
            writer.append(v.fn() == SqlAgg.Fn.LAG ? "lagInFrame(toNullable(" : "leadInFrame(toNullable(")
                    .expr(v.args().get(0), 0).append(")");
            for (SqlExpr e : v.args().subList(1, v.args().size())) {
                writer.append(", ").expr(e, 0);
            }
            writer.append(") OVER (");
            if (!w.partitionBy().isEmpty()) {
                writer.append("PARTITION BY ").list(w.partitionBy());
            }
            if (!w.orderBy().isEmpty()) {
                writer.append(w.partitionBy().isEmpty() ? "" : " ").append("ORDER BY ").join(w.orderBy(), ", ",
                        this::sortKey);
            }
            return writer.append(" ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)");
        }
        // cume_dist (ClickHouse has none): the rows up to the current one's value over the partition's rows
        if (w.fn() instanceof SqlAgg.RankingFn r && r.fn() == SqlAgg.Fn.CUME_DIST) {
            writer.append("(toFloat64(count() OVER (");
            if (!w.partitionBy().isEmpty()) {
                writer.append("PARTITION BY ").list(w.partitionBy()).append(" ");
            }
            writer.append("ORDER BY ").join(w.orderBy(), ", ", this::sortKey)
                    .append(" RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)) / count() OVER (");
            if (!w.partitionBy().isEmpty()) {
                writer.append("PARTITION BY ").list(w.partitionBy());
            }
            return writer.append("))");
        }
        boolean cast = w.fn() instanceof SqlAgg.Reducer r && r.fn() == SqlAgg.Fn.AVG;
        // a count or a rank is unsigned here: an Int64
        boolean signed = w.fn() instanceof SqlAgg.RankingFn rf && rf.fn() != SqlAgg.Fn.PERCENT_RANK
                || w.fn() instanceof SqlAgg.Reducer rc && rc.fn() == SqlAgg.Fn.COUNT;
        if (cast) {
            writer.append("toFloat64(");
        }
        if (signed) {
            writer.append("toInt64(");
        }
        switch (w.fn()) {
            case SqlAgg.Reducer r -> reducerIn(writer, r, w);
            // ntile's bucket count is unsigned (an Int64 is refused, measured)
            case SqlAgg.RankingFn r -> {
                if (r.fn() == SqlAgg.Fn.NTILE) {
                    writer.append("ntile(toUInt64(").list(r.args()).append("))");
                } else {
                    writer.append(aggregateName(r.fn())).append("(").list(r.args()).append(")");
                }
            }
            // a value function reads the nullable value: past the frame it is NULL, not the type's default
            case SqlAgg.ValueFn v -> {
                writer.append(aggregateName(v.fn())).append("(toNullable(").expr(v.args().get(0), 0).append(")");
                for (SqlExpr e : v.args().subList(1, v.args().size())) {
                    writer.append(", ").expr(e, 0);
                }
                writer.append(")");
            }
        }
        if (!(w.fn() instanceof SqlAgg.Reducer)) {
            over(writer, w);
        }
        if (signed) {
            writer.append(")");
        }
        return cast ? writer.append(")") : writer;
    }

    // ==================================================================
    // JSON constructors
    // ==================================================================

    /** A value's JSON text: a JSON value its own text, any other value toJSONString's (which answers SQL NULL for a
     *  NULL, measured: JSON's null here). */
    private SqlWriter.Piece json(SqlExpr x) {
        if (x instanceof SqlExpr.NullLit) {
            return w -> w.append("'null'");
        }
        if (typeOf(x) == SqlType.Scalar.JSON) {
            return w -> w.append("coalesce(").expr(x, 0).append(", 'null')");
        }
        return w -> w.append("coalesce(toJSONString(").expr(x, 0).append("), 'null')");
    }

    /** A JSON object's text: each key's JSON, a colon, its value's (ClickHouse has no JSON constructor; a value is a
     *  JSON value's text here). */
    @Override
    protected SqlWriter jsonObject(SqlWriter writer, SqlExpr.JsonObject j) {
        List<SqlExpr> kv = j.kv();
        writer.append("concat('{'");
        for (int i = 0; i < kv.size(); i += 2) {
            writer.append(i > 0 ? ", ','" : "").append(", ").piece(json(kv.get(i))).append(", ':', ")
                    .piece(json(kv.get(i + 1)));
        }
        return writer.append(", '}')");
    }

    @Override
    protected SqlWriter jsonArray(SqlWriter writer, SqlExpr.JsonArray j) {
        writer.append("concat('['");
        for (int i = 0; i < j.elements().size(); i++) {
            writer.append(i > 0 ? ", ','" : "").append(", ").piece(json(j.elements().get(i)));
        }
        return writer.append(", ']')");
    }

    /** The group's values' JSON as an array, in the keys' order (an empty group's is {@code []}). */
    @Override
    protected SqlWriter jsonArrayAgg(SqlWriter writer, SqlExpr.JsonArrayAgg j) {
        SqlWriter.Piece values = j.orderKeys().isEmpty()
                ? w -> w.append("groupArray(").piece(json(j.value())).append(")")
                : collected(json(j.value()), j.orderKeys().stream().map(k -> new SortKey(w -> w.expr(k.expr(), 0),
                        !k.desc(), false, typeOf(k.expr()))).toList());
        return writer.append("concat('[', arrayStringConcat(").piece(values).append(", ','), ']')");
    }

    // ==================================================================
    // Date format codes (formatDateTime: MySQL's letters)
    // ==================================================================

    @Override
    protected String formatText(SqlExpr.FormatLit fl) {
        StringBuilder out = new StringBuilder();
        for (com.legend.sql.DateFmt p : fl.parts()) {
            out.append(switch (p) {
                case com.legend.sql.DateFmt.Text t -> t.s().replace("%", "%%");
                case com.legend.sql.DateFmt.Part part -> switch (part) {
                    case YEAR4 -> "%Y";
                    case MONTH2 -> "%m";
                    case DAY2 -> "%d";
                    case HOUR2 -> "%H";
                    case MIN2 -> "%i";
                    case SEC2 -> "%S";
                    case SUBSEC_MICRO -> "%f";
                    case SUBSEC_NANO -> "%f000";
                    case SUBSEC_MIN -> throw new DialectCapability("a millisecond format reached ClickHouse outside a"
                            + " formatted date (formatted())");
                    case MONTH_ABBREV -> "%b";
                    case MONTH_NAME -> "%M";
                    case WEEKDAY_NAME -> "%W";
                    case HOUR12 -> "%I";
                    case HOUR12_NOPAD -> "%l";
                    case AMPM -> "%p";
                };
            });
        }
        return out.toString();
    }

    /**
     * A date formatted: formatDateTime's letters, Joda's for a format with an unpadded 12-hour clock (formatDateTime's
     * {@code %l} pads, measured). Neither has milliseconds (Joda's {@code SSS} drops the fraction's leading zeros: .001
     * prints 100, measured), so a millisecond field is the first three digits of the microseconds, the format around
     * it formatted on its own.
     */
    private SqlWriter.Piece formatted(SqlExpr value, List<com.legend.sql.DateFmt> parts) {
        List<SqlWriter.Piece> pieces = new ArrayList<>();
        List<com.legend.sql.DateFmt> run = new ArrayList<>();
        for (com.legend.sql.DateFmt p : parts) {
            if (p == com.legend.sql.DateFmt.Part.SUBSEC_MIN) {
                if (!run.isEmpty()) {
                    pieces.add(formattedRun(value, List.copyOf(run)));
                    run.clear();
                }
                pieces.add(w -> w.append("substring(formatDateTime(").expr(value, 0).append(", '%f'), 1, 3)"));
            } else {
                run.add(p);
            }
        }
        if (!run.isEmpty()) {
            pieces.add(formattedRun(value, List.copyOf(run)));
        }
        return pieces.size() == 1 ? pieces.get(0) : w -> w.append("concat(").join(pieces, ", ", SqlWriter::piece)
                .append(")");
    }

    private SqlWriter.Piece formattedRun(SqlExpr value, List<com.legend.sql.DateFmt> parts) {
        SqlExpr.FormatLit fl = new SqlExpr.FormatLit(parts);
        if (parts.contains(com.legend.sql.DateFmt.Part.HOUR12_NOPAD)) {
            return w -> w.append("formatDateTimeInJodaSyntax(").expr(value, 0).append(", ").append(stringLit(jodaText(fl)))
                    .append(")");
        }
        return w -> w.append("formatDateTime(").expr(value, 0).append(", ").append(stringLit(formatText(fl))).append(")");
    }

    /** A format in Joda's letters (formatDateTimeInJodaSyntax), its text quoted. */
    private static String jodaText(SqlExpr.FormatLit fl) {
        StringBuilder out = new StringBuilder();
        for (com.legend.sql.DateFmt p : fl.parts()) {
            out.append(switch (p) {
                case com.legend.sql.DateFmt.Text t -> "'" + t.s().replace("'", "''") + "'";
                case com.legend.sql.DateFmt.Part part -> switch (part) {
                    case YEAR4 -> "yyyy";
                    case MONTH2 -> "MM";
                    case DAY2 -> "dd";
                    case HOUR2 -> "HH";
                    case MIN2 -> "mm";
                    case SEC2 -> "ss";
                    case SUBSEC_MICRO -> "SSSSSS";
                    case SUBSEC_NANO, SUBSEC_MIN -> throw new IllegalStateException("a " + part + " field reached a Joda"
                            + " format: formatted() writes it");
                    case MONTH_ABBREV -> "MMM";
                    case MONTH_NAME -> "MMMM";
                    case WEEKDAY_NAME -> "EEEE";
                    case HOUR12 -> "hh";
                    case HOUR12_NOPAD -> "h";
                    case AMPM -> "a";
                };
            });
        }
        return out.toString();
    }

    // ==================================================================
    // Session tables and DDL
    // ==================================================================

    /** No session (the driver's HTTP has none), so no table only one session sees. */
    @Override
    public List<String> sessionTable(com.legend.sql.SqlDdl.CreateTable table,
            com.legend.sql.SqlDml.@com.legend.base.Nullable InsertValues rows) {
        throw new DialectCapability("a session's own table reached ClickHouse, which has no session over its JDBC"
                + " driver: " + table.table());
    }

    // ==================================================================
    // Values: what the driver hands back
    // ==================================================================

    /** A Tuple arrives as an Object[] of its fields in order: the platform's map of its fields, by the struct's
     *  declared names. */
    @Override
    public @com.legend.base.Nullable Object normalize(@com.legend.base.Nullable Object jdbcValue,
            @com.legend.base.Nullable SqlType type) {
        return tupled(jdbcValue, type);
    }

    /** A Tuple (the driver's Object[]) as the platform's map of the struct's fields, a nested one too (a zip of pairs
     *  is a pair of pairs), and a list's elements so. */
    private static @com.legend.base.Nullable Object tupled(@com.legend.base.Nullable Object v,
            @com.legend.base.Nullable SqlType type) {
        if (v instanceof Object[] fields && type instanceof SqlType.Struct st && fields.length == st.fields().size()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < fields.length; i++) {
                m.put(st.fields().get(i).name(), tupled(fields[i], st.fields().get(i).type()));
            }
            return m;
        }
        if (v instanceof List<?> l && type instanceof SqlType.Array at) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) {
                out.add(tupled(e, at.element()));
            }
            return out;
        }
        return v;
    }
}
