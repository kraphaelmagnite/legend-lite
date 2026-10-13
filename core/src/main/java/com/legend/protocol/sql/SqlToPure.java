// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import com.legend.protocol.TypeExpression;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CDate;
import com.legend.protocol.spec.CDecimal;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.TypeAnnotation;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import com.legend.values.PureDateLiteral;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Postgres query ({@link SqlParser}'s tree) to a Pure Relation expression: the {@code #SQL{...}#} island's meaning
 * (docs/LEGEND_SQL_STUDY_2026_10_11.md §3). It builds the protocol tree a person would write in Pure, before name
 * resolution, as the parser does; the compiler resolves and types it like any query. It never types anything itself:
 * what a FROM source's columns are, it asks the caller ({@link Sources}), and the compiler types the whole result once.
 *
 * <p>Postgres is the spec. Where a Postgres construct has a Pure spelling with the same meaning, it is translated;
 * where it has none yet, it is refused by name and position, never approximated (AGENTS.md invariant 4). The refusals
 * that are Postgres's own ("column must appear in the GROUP BY clause") carry Postgres's wording.
 *
 * <p><b>Names</b> (the study's D1, 2026-10-11): a quoted identifier matches exactly; an unquoted one matches the name
 * equal to its Postgres folding (lowercase) when there is one, else the one name equal to it ignoring case; two such
 * names are an ambiguity to quote. Every query Postgres accepts resolves as Postgres resolves it; a camelCase model
 * name resolves unquoted too. Output names keep the case they are written in.
 *
 * <p>Step 1 of the study's phase 1: one FROM source, {@code tb('pkg::Database.schema.table')}.
 */
public final class SqlToPure {

    /** What a FROM source's columns are: the caller types the source; the translator never types. */
    public interface Sources {
        /** Reports the columns of {@code source}, an unresolved relation expression, to {@code column}, in order. */
        void columns(ValueSpecification source, Column column);
    }

    /** One source column: its name as the model spells it, its Pure type's name ({@code Integer},
     *  {@code Decimal(10,2)}, {@code String}, {@code StrictDate}, an enumeration's path, ...), and whether it may hold
     *  no value (SQL's NULL; Pure's {@code [0..1]}). */
    @FunctionalInterface
    public interface Column {
        void column(String name, String type, boolean nullable);
    }

    /** The one table function this step reads: {@code tb('pkg::Database.schema.table')}, upstream's dynamic source. */
    private static final String TABLE_FUNCTION = "tb";

    private static final String NULL_ORDER = "meta::pure::functions::relation::NullOrder";
    private static final String JOIN_KIND = "meta::pure::functions::relation::JoinKind";
    private static final String SYNTHETIC = "_sql_";

    private final Sources sources;
    private final Env env;
    /** This query level's lambda parameters: a row, a group, a join's two sides, a subquery's row. Each level has
     *  its own, so a correlated subquery's lambdas can reach the enclosing query's row. */
    private final String row;
    private final String group;
    private final String leftRow;
    private final String rightRow;
    /** A subquery's row, in the {@code exists} and {@code map} that read an expression subquery. */
    private final String sub;
    /** A window column's partition and window parameters: {@code {p, w, r | ...}}. */
    private final String partition;
    private final String window;
    private int fresh;

    private SqlToPure(Sources sources, Env env) {
        this.sources = sources;
        this.env = env;
        String level = env.level() == 0 ? "" : Integer.toString(env.level());
        this.row = "r" + level;
        this.group = "y" + level;
        this.leftRow = "a" + level;
        this.rightRow = "b" + level;
        this.sub = "s" + level;
        this.partition = "p" + level;
        this.window = "w" + level;
    }

    /** What a query level sees beyond its own FROM: the WITH queries in force, the enclosing query's scope and the
     *  lambda parameter its row is in (for a correlated subquery), and how deep it is. */
    private record Env(Map<String, Rel> ctes, @com.legend.base.Nullable Scope outer,
            @com.legend.base.Nullable String outerRow, int level) {

        /** The same level with {@code more} WITH queries in force (a FROM subquery, a WITH query or body). */
        Env with(Map<String, Rel> more) {
            return new Env(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(more)), outer, outerRow, level);
        }
    }

    /** A translated query: its relation and its output columns. */
    private record Rel(ValueSpecification vs, List<OutCol> columns) {
    }

    /** An output column: the name the query shows, what is known of its type, whether it may be NULL. */
    private record OutCol(String name, Kind kind, boolean nullable) {
    }

    /** The Pure relation expression {@code sql} means; {@link SqlTranslationException} when it has none (its kind
     *  says whether the text is not SQL, the translation is not built yet, or Postgres rejects the query too). */
    public static ValueSpecification translate(String sql, Sources sources) {
        SqlAst.Statement statement;
        try {
            statement = SqlParser.parse(sql);
        } catch (SqlSyntaxException e) {
            throw new SqlTranslationException(e.unsupported() ? SqlTranslationException.Kind.UNSUPPORTED
                    : SqlTranslationException.Kind.SYNTAX, e.reason(), e.line(), e.column());
        }
        return new SqlToPure(sources, new Env(Map.of(), null, null, 0)).statement(statement).vs();
    }

    private Rel statement(SqlAst.Statement statement) {
        return switch (statement) {
            case SqlAst.Select s -> select(s);
            case SqlAst.SetOperation s -> setOperation(s);
            case SqlAst.With w -> with(w);
        };
    }

    // ------------------------------------------------------------------ kinds

    /**
     * What the translator knows of a value's type: enough to choose a Postgres meaning, nothing more. INEXACT is
     * Postgres's numeric computed here as Pure's Float: a quotient of numbers that are not both integers (Pure's
     * divide), an average or a statistic of numbers that are not doubles, a square root, exponential or logarithm of a
     * numeric. It agrees with Postgres's exact value to about 15 significant digits but may fall either side of it
     * ({@code 0.3 / 0.1} is 2.9999999999999996), so nothing that rounds it or spells it is translated. WHOLE is a
     * sum of integers: Postgres's bigint for a sum of int4 values, its numeric for a sum of int8 ones -- the same
     * whole number, but the one's division by an integer truncates and the other's does not, and the translator does
     * not know a column's width. Dividing it by an integer is refused.
     */
    enum Kind { INTEGER, WHOLE, FLOAT, DECIMAL, NUMBER, INEXACT, STRING, BOOLEAN, DATE, DATETIME, JSON, NULL, OTHER;

        boolean numeric() {
            return this == INTEGER || this == WHOLE || this == FLOAT || this == DECIMAL || this == NUMBER
                    || this == INEXACT;
        }
    }

    private static Kind kindOf(String pureType) {
        int paren = pureType.indexOf('(');
        String base = paren < 0 ? pureType : pureType.substring(0, paren);
        return switch (base) {
            case "Integer" -> Kind.INTEGER;
            case "Float" -> Kind.FLOAT;
            case "Decimal" -> Kind.DECIMAL;
            case "Number" -> Kind.NUMBER;
            case "String" -> Kind.STRING;
            case "Boolean" -> Kind.BOOLEAN;
            case "StrictDate" -> Kind.DATE;
            case "DateTime" -> Kind.DATETIME;
            // a Variant is Postgres's jsonb (register S49)
            case "Variant", VARIANT -> Kind.JSON;
            default -> Kind.OTHER;
        };
    }

    /** Postgres's integer division, truncating toward zero, exact for every bigint. With the remainder taken off
     *  ({@code rem} keeps the dividend's sign, as Postgres's {@code %} does) the quotient is a whole number. Pure's
     *  divide computes it in double precision, within 2^-52 of it; shrunk by 2^-50 and truncated, the estimate is never
     *  beyond the quotient, so {@code A - q0 * b} (exact integer arithmetic, never beyond A) is a small multiple of the
     *  divisor, divided once more and added. A divisor of zero is Postgres's error and rem's. */
    private static ValueSpecification integerQuotient(ValueSpecification a, ValueSpecification b) {
        ValueSpecification whole = AppliedFunction.infixRun("minus", List.of(a, call("rem", a, b)));
        ValueSpecification shrunk = AppliedFunction.infixRun("times", List.of(call("divide", whole, b),
                new com.legend.protocol.spec.CFloat(1.0 - Math.pow(2, -50))));
        ValueSpecification estimate = call("if", call("greaterThanEqual", shrunk, new CInteger(0L)),
                thunk(call("floor", shrunk)), thunk(call("ceiling", shrunk)));
        ValueSpecification error = AppliedFunction.infixRun("minus", List.of(whole,
                AppliedFunction.infixRun("times", List.of(estimate, b))));
        return AppliedFunction.infixRun("plus", List.of(estimate, call("round", call("divide", error, b))));
    }

    /** Postgres's numeric promotion for {@code + - *}: integer with integer stays integer; a float wins; else exact. */
    private static Kind arithmetic(Kind a, Kind b) {
        if (a == Kind.INTEGER && b == Kind.INTEGER) {
            return Kind.INTEGER;
        }
        // an inexact numeric stays inexact with a double in it: Postgres's double of the exact value is not this one
        if (a == Kind.INEXACT || b == Kind.INEXACT) {
            return Kind.INEXACT;
        }
        if (a == Kind.FLOAT || b == Kind.FLOAT) {
            return Kind.FLOAT;
        }
        if (a == Kind.NUMBER || b == Kind.NUMBER) {
            return Kind.NUMBER;
        }
        if ((a == Kind.WHOLE || b == Kind.WHOLE) && (a == Kind.INTEGER || a == Kind.WHOLE)
                && (b == Kind.INTEGER || b == Kind.WHOLE)) {
            return Kind.WHOLE;
        }
        return Kind.DECIMAL;
    }

    /**
     * A translated expression: its Pure spelling, what is known of its type, and whether it may be NULL. A BOOLEAN is
     * a CONDITION in Postgres's three-valued logic: {@code vs} holds exactly when it is TRUE and {@code whenFalse}
     * exactly when it is FALSE; when it is NULL, neither holds. Both are two-valued Pure ({@code Boolean[1]}), so
     * {@code NOT}, {@code AND} and {@code OR} keep Postgres's meaning (NOT NULL is NULL, not TRUE).
     */
    record Tx(ValueSpecification vs, Kind kind, boolean nullable,
            @com.legend.base.Nullable ValueSpecification whenFalse) {

        /** A value that is not a condition. */
        static Tx value(ValueSpecification vs, Kind kind, boolean nullable) {
            return new Tx(vs, kind, nullable, null);
        }

        /** A condition: when it is TRUE, when it is FALSE. */
        static Tx condition(ValueSpecification whenTrue, ValueSpecification whenFalse, boolean nullable) {
            return new Tx(whenTrue, Kind.BOOLEAN, nullable, whenFalse);
        }

        ValueSpecification falseWhen() {
            return java.util.Objects.requireNonNull(whenFalse, "a condition has its FALSE case");
        }
    }

    /** A column of the relation in hand: {@code name} is the column the relation holds it in, {@code label} the name
     *  the query sees it by, and {@code var} the lambda parameter that reaches it when it is not this level's row
     *  (a join side's, or an enclosing query's for a correlated reference). */
    private record Col(String name, Kind kind, boolean nullable, @com.legend.base.Nullable String var, String label) {

        static Col of(String name, Kind kind, boolean nullable) {
            return new Col(name, kind, nullable, null, name);
        }

        Col via(@com.legend.base.Nullable String parameter) {
            return new Col(name, kind, nullable, parameter, label);
        }

        Col asNullable() {
            return new Col(name, kind, true, var, label);
        }
    }

    // ------------------------------------------------------------------ names

    /** Postgres's folding of an unquoted identifier: ASCII letters to lower case, nothing else. */
    private static String fold(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    /** THE identifier rule (class comment): the name of {@code names} that {@code id} means, or null for none. */
    private static @com.legend.base.Nullable String resolve(SqlAst.Ident id, Iterable<String> names, String what) {
        if (id.quoted()) {
            for (String n : names) {
                if (n.equals(id.text())) {
                    return n;
                }
            }
            return null;
        }
        String folded = fold(id.text());
        List<String> loose = new ArrayList<>();
        for (String n : names) {
            if (n.equals(folded)) {
                return n;
            }
            if (fold(n).equals(folded) && !loose.contains(n)) {
                loose.add(n);
            }
        }
        if (loose.size() > 1) {
            throw SqlTranslationException.invalid(what + " reference \"" + id.text() + "\" is ambiguous: it matches "
                    + String.join(", ", loose.stream().map(n -> "\"" + n + "\"").toList())
                    + "; quote the name to choose one", id.pos());
        }
        return loose.isEmpty() ? null : loose.get(0);
    }

    /** Postgres's hint for a quoted name that differs only in case from a real one. */
    private static String hint(SqlAst.Ident name, List<String> names) {
        if (name.quoted()) {
            for (String n : names) {
                if (fold(n).equals(fold(name.text()))) {
                    return "; perhaps you meant \"" + n + "\"";
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ scopes

    /** Where a column reference finds its column. */
    private interface Scope {
        Col column(SqlAst.ColumnRef ref);

        /** The column {@code ref} names here or in an enclosing query, nearest first; null for none. */
        @com.legend.base.Nullable Col columnOrNull(SqlAst.ColumnRef ref);

        /** A one-value function's name read as its value, here or in an enclosing query, nearest first; null for
         *  none (Postgres reads a name as a FROM item's only after no query in scope has a column of it). */
        @com.legend.base.Nullable Col functionValue(SqlAst.ColumnRef ref);

        /** Whether a FROM item here or in an enclosing query is named {@code name}. */
        boolean hasTable(SqlAst.Ident name);

        /** The column an expression IS in a grouped scope (a group key or an aggregate); null otherwise. */
        @com.legend.base.Nullable Col grouped(SqlAst.Expr e);

        /** The clause an aggregate would be refused in, or null where aggregates have been collected. */
        @com.legend.base.Nullable String noAggregatesIn();
    }

    /** A FROM item's columns as the query names them: its qualifier (an alias, a table's or function's name; none
     *  for an unaliased subquery), and its columns. */
    private record Binding(SqlAst.@com.legend.base.Nullable Ident qualifier, List<BCol> columns, boolean scalar) {

        /** A binding whose name is no value (a table's: its whole row, not supported here). */
        Binding(SqlAst.@com.legend.base.Nullable Ident qualifier, List<BCol> columns) {
            this(qualifier, columns, false);
        }

        List<String> names(boolean bareOnly) {
            List<String> names = new ArrayList<>();
            for (BCol c : columns) {
                if (c.bare() || !bareOnly) {
                    names.add(c.col().label());
                }
            }
            return names;
        }

        Binding via(@com.legend.base.Nullable String parameter) {
            List<BCol> cols = new ArrayList<>();
            for (BCol c : columns) {
                cols.add(new BCol(c.col().via(parameter), c.bare()));
            }
            return new Binding(qualifier, cols, scalar);
        }

        Binding asNullable() {
            List<BCol> cols = new ArrayList<>();
            for (BCol c : columns) {
                cols.add(new BCol(c.col().asNullable(), c.bare()));
            }
            return new Binding(qualifier, cols, scalar);
        }
    }

    /** A binding's column; {@code bare} when an unqualified name reaches it (a USING join's two sides' columns are
     *  reached only qualified: the unqualified name is the merged column). */
    private record BCol(Col col, boolean bare) {
    }

    /** The FROM clause's relation, its bindings, and its columns in {@code SELECT *}'s order. */
    private record Source(ValueSpecification relation, List<Binding> bindings, List<Col> star) {
    }

    /** The FROM clause's columns, before any grouping; names it does not have are the enclosing query's. */
    private final class Input implements Scope {
        private final List<Binding> bindings;
        private final List<Col> star;
        private final String clause;

        Input(List<Binding> bindings, List<Col> star, String clause) {
            this.bindings = bindings;
            this.star = star;
            this.clause = clause;
        }

        Input in(String otherClause) {
            return new Input(bindings, star, otherClause);
        }

        /** The relation's columns, by the names it holds them under. */
        List<String> names() {
            List<String> names = new ArrayList<>();
            for (Binding b : bindings) {
                for (BCol c : b.columns()) {
                    names.add(c.col().name());
                }
            }
            return names;
        }

        /** True iff an unqualified {@code name} is a column here (Postgres reads a GROUP BY name as one first). */
        boolean has(SqlAst.Ident name) {
            for (Binding b : bindings) {
                if (resolve(name, b.names(true), "column") != null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Col column(SqlAst.ColumnRef ref) {
            Col found = columnOrNull(ref);
            if (found == null) {
                found = functionValue(ref);
            }
            return found != null ? found : notFound(ref);
        }

        @Override
        public @com.legend.base.Nullable Col columnOrNull(SqlAst.ColumnRef ref) {
            Col found = find(ref);
            if (found != null) {
                return found;
            }
            Scope outer = env.outer();
            if (outer == null) {
                return null;
            }
            // the enclosing query's column, read from its row's lambda parameter
            Col c = outer.columnOrNull(ref);
            return c == null || c.var() != null ? c : c.via(env.outerRow());
        }

        @Override
        public @com.legend.base.Nullable Col functionValue(SqlAst.ColumnRef ref) {
            if (ref.parts().size() != 1) {
                return null;
            }
            Binding named = binding(ref.parts().get(0));
            if (named != null) {
                // a function returning one value: its name is that value (FROM jsonb_array_elements(a) e ...
                // e ->> 'x'); a table's name is its whole row, refused by notFound
                return named.scalar() && named.columns().size() == 1 ? named.columns().get(0).col() : null;
            }
            Scope outer = env.outer();
            if (outer == null) {
                return null;
            }
            Col c = outer.functionValue(ref);
            return c == null || c.var() != null ? c : c.via(env.outerRow());
        }

        /** The column {@code ref} names here, or null for none (an enclosing query may have it). */
        @com.legend.base.Nullable Col find(SqlAst.ColumnRef ref) {
            List<SqlAst.Ident> parts = ref.parts();
            if (parts.size() > 2) {
                throw SqlTranslationException.unsupported("schema-qualified column references are not supported yet",
                        ref.pos());
            }
            SqlAst.Ident name = parts.get(parts.size() - 1);
            if (parts.size() == 2) {
                Binding b = binding(parts.get(0));
                if (b == null) {
                    return null;
                }
                String label = resolve(name, b.names(false), "column");
                if (label == null) {
                    throw SqlTranslationException.invalid("column " + parts.get(0).text() + "." + name.text()
                            + " does not exist" + hint(name, b.names(false)), name.pos());
                }
                return column(b, label, false);
            }
            Col match = null;
            for (Binding b : bindings) {
                String label = resolve(name, b.names(true), "column");
                if (label != null) {
                    if (match != null) {
                        throw SqlTranslationException.invalid("column reference \"" + name.text() + "\" is ambiguous",
                                name.pos());
                    }
                    match = column(b, label, true);
                }
            }
            return match;
        }

        private Col column(Binding b, String label, boolean bareOnly) {
            for (BCol c : b.columns()) {
                if (c.col().label().equals(label) && (c.bare() || !bareOnly)) {
                    return c.col();
                }
            }
            throw new IllegalStateException("resolved to a column not in the binding: " + label);
        }

        /** The binding a qualifier names, or null. */
        @com.legend.base.Nullable Binding binding(SqlAst.Ident qualifier) {
            Binding found = null;
            for (Binding b : bindings) {
                SqlAst.Ident q = b.qualifier();
                if (q != null && resolve(qualifier, List.of(q.quoted() ? q.text() : fold(q.text())), "table")
                        != null) {
                    if (found != null) {
                        throw SqlTranslationException.invalid("table reference \"" + qualifier.text()
                                + "\" is ambiguous", qualifier.pos());
                    }
                    found = b;
                }
            }
            return found;
        }

        @Override
        public boolean hasTable(SqlAst.Ident name) {
            return binding(name) != null || env.outer() != null && env.outer().hasTable(name);
        }

        private Col notFound(SqlAst.ColumnRef ref) {
            List<SqlAst.Ident> parts = ref.parts();
            SqlAst.Ident name = parts.get(parts.size() - 1);
            if (parts.size() == 2) {
                throw SqlTranslationException.invalid("missing FROM-clause entry for table \"" + parts.get(0).text()
                        + "\"", parts.get(0).pos());
            }
            Binding named = binding(name);
            if (named != null || env.outer() != null && env.outer().hasTable(name)) {
                // Postgres reads a FROM item's name as its whole row (row_to_json(persons))
                throw SqlTranslationException.unsupported("whole-row references (\"" + name.text() + "\" as a value)"
                        + " are not supported yet", name.pos());
            }
            List<String> all = new ArrayList<>();
            for (Binding b : bindings) {
                all.addAll(b.names(false));
            }
            throw SqlTranslationException.invalid("column \"" + name.text() + "\" does not exist" + hint(name, all),
                    name.pos());
        }

        @Override
        public @com.legend.base.Nullable Col grouped(SqlAst.Expr e) {
            return null;
        }

        @Override
        public @com.legend.base.Nullable String noAggregatesIn() {
            return clause;
        }
    }

    /** The grouped relation's columns: the group keys and the aggregates, by the canonical form of what they are. */
    private final class Grouped implements Scope {
        private final Input input;
        private final Map<String, Col> byCanon;
        private final Map<String, Col> keyBySource;

        Grouped(Input input, Map<String, Col> byCanon, Map<String, Col> keyBySource) {
            this.input = input;
            this.byCanon = byCanon;
            this.keyBySource = keyBySource;
        }

        @Override
        public Col column(SqlAst.ColumnRef ref) {
            return key(input.column(ref), ref);
        }

        @Override
        public @com.legend.base.Nullable Col columnOrNull(SqlAst.ColumnRef ref) {
            Col source = input.columnOrNull(ref);
            return source == null ? null : key(source, ref);
        }

        @Override
        public @com.legend.base.Nullable Col functionValue(SqlAst.ColumnRef ref) {
            Col source = input.functionValue(ref);
            return source == null ? null : key(source, ref);
        }

        @Override
        public boolean hasTable(SqlAst.Ident name) {
            return input.hasTable(name);
        }

        private Col key(Col source, SqlAst.ColumnRef ref) {
            if (source.var() != null) {
                // the enclosing query's column: one value per group, as Postgres has it
                return source;
            }
            Col key = keyBySource.get(source.name());
            if (key == null) {
                throw SqlTranslationException.invalid("column \"" + text(ref) + "\" must appear in the GROUP BY"
                        + " clause or be used in an aggregate function", ref.pos());
            }
            return key;
        }

        @Override
        public @com.legend.base.Nullable Col grouped(SqlAst.Expr e) {
            if (e instanceof SqlAst.ColumnRef) {
                return null;
            }
            return byCanon.get(canon(e, input));
        }

        @Override
        public @com.legend.base.Nullable String noAggregatesIn() {
            return null;
        }
    }

    private static String text(SqlAst.ColumnRef ref) {
        return String.join(".", ref.parts().stream().map(SqlAst.Ident::text).toList());
    }

    // ------------------------------------------------------------------ the query

    /**
     * A set-returning function as a whole select-list item ({@code SELECT jsonb_array_elements(a) FROM t}): Postgres
     * reads it after FROM and WHERE, one row per element, the other items repeated; here it is a LATERAL item after
     * the query's FROM, and the item its column. One such function per select list (two run in lockstep in Postgres),
     * none with grouping, aggregates, windows or DISTINCT; one inside an expression is refused.
     */
    private record SelectListSet(SqlAst.Select select, SqlAst.@com.legend.base.Nullable TableFunction function) {
    }

    private static SelectListSet selectListSetReturning(SqlAst.Select s) {
        int found = -1;
        for (int i = 0; i < s.items().size(); i++) {
            if (s.items().get(i) instanceof SqlAst.SelectExpr x) {
                if (x.expr() instanceof SqlAst.FunctionCall f && setReturning(f)) {
                    if (found >= 0) {
                        throw SqlTranslationException.unsupported("two set-returning functions in one select list are"
                                + " not supported yet", f.pos());
                    }
                    found = i;
                    for (SqlAst.Expr a : f.args()) {
                        noSetReturning(a);
                    }
                } else {
                    noSetReturning(x.expr());
                }
            }
        }
        if (found < 0) {
            return new SelectListSet(s, null);
        }
        SqlAst.SelectExpr item = (SqlAst.SelectExpr) s.items().get(found);
        SqlAst.FunctionCall f = (SqlAst.FunctionCall) item.expr();
        if (s.distinct() || !s.groupBy().isEmpty() || s.having() != null || hasAggregate(s) || !s.windows().isEmpty()
                || hasWindow(s)) {
            throw SqlTranslationException.unsupported("a set-returning function in a select list with grouping,"
                    + " aggregates, windows or DISTINCT is not supported yet", f.pos());
        }
        if (f.distinct() || f.star()) {
            throw SqlTranslationException.invalid(functionName(f.name().get(f.name().size() - 1)) + " is not an"
                    + " aggregate", f.pos());
        }
        String name = item.alias() != null ? item.alias().quoted() ? item.alias().text() : fold(item.alias().text())
                : functionName(f.name().get(f.name().size() - 1));
        // its column's name is no name the query can write: WHERE cannot read it, nor * list it
        SqlAst.Ident table = new SqlAst.Ident(SYNTHETIC + "set", true, f.pos());
        SqlAst.Ident column = new SqlAst.Ident(SYNTHETIC + "value", true, f.pos());
        List<SqlAst.Arg> args = new ArrayList<>();
        f.args().forEach(a -> args.add(new SqlAst.Arg(null, a)));
        List<SqlAst.SelectItem> items = new ArrayList<>(s.items());
        items.set(found, new SqlAst.SelectExpr(new SqlAst.ColumnRef(List.of(table, column), f.pos()),
                new SqlAst.Ident(name, true, f.pos()), item.pos()));
        return new SelectListSet(new SqlAst.Select(false, items, s.from(), s.where(), s.groupBy(), s.having(),
                s.windows(), s.orderBy(), s.limit(), s.offset(), s.pos()),
                new SqlAst.TableFunction(f.name(), args, table, List.of(column), f.pos()));
    }

    private static boolean setReturning(SqlAst.FunctionCall f) {
        return f.name().size() == 1 && SET_RETURNING.containsKey(functionName(f.name().get(0)));
    }

    private static void noSetReturning(SqlAst.Expr e) {
        if (e instanceof SqlAst.FunctionCall f && setReturning(f)) {
            throw SqlTranslationException.unsupported("a set-returning function inside an expression is not supported"
                    + " yet", f.pos());
        }
        children(e).forEach(SqlToPure::noSetReturning);
    }

    private static boolean hasWindow(SqlAst.Select s) {
        for (SqlAst.SelectItem i : s.items()) {
            if (i instanceof SqlAst.SelectExpr x && containsWindow(x.expr())) {
                return true;
            }
        }
        return false;
    }

    private Rel select(SqlAst.Select query) {
        SelectListSet set = selectListSetReturning(query);
        SqlAst.Select s = set.select();
        SqlAst.TableFunction setFunction = set.function();

        if (s.items().isEmpty()) {
            throw SqlTranslationException.unsupported("a select list with no columns is not supported yet", s.pos());
        }
        if (s.from().isEmpty() && env.outer() != null) {
            // legend-lite cannot yet lower a row of an enclosing query read by a one-row relation of its own
            throw SqlTranslationException.unsupported("a subquery without FROM is not supported yet", s.pos());
        }
        if (s.from().isEmpty() && s.items().stream().anyMatch(i -> i instanceof SqlAst.AllColumns)) {
            throw SqlTranslationException.invalid("with no tables specified, SELECT * is not valid", s.pos());
        }
        // no FROM: one row, of no columns the query can name
        String one = "\n" + SYNTHETIC + "row\n1\n";
        Source src = !s.from().isEmpty() ? null : setFunction != null ? from(setFunction)
                : new Source(com.legend.protocol.spec.TdsLiteral.of(one, "#TDS" + one + "#", null), List.of(), List.of());
        for (SqlAst.FromItem item : s.from()) {
            // a comma is a cross join, binding looser than JOIN (the ON of a JOIN sees its own two sides only); a
            // function or a LATERAL subquery reads the items before it
            if (src != null && lateral(item)) {
                src = lateralJoin(src, item, SqlAst.JoinType.CROSS, null, item.pos());
                continue;
            }
            Source next = from(item);
            src = src == null ? next : join(src, next, SqlAst.JoinType.CROSS, null, List.of(), false, item.pos());
        }
        Source source = java.util.Objects.requireNonNull(src);
        Input input = new Input(source.bindings(), source.star(), "WHERE");
        if (env.outer() != null) {
            noOuterAggregates(s, input);
        }
        ValueSpecification relation = source.relation();
        SqlAst.Expr where = s.where();
        if (where != null) {
            relation = call("filter", relation, lambda(row, condition(where, input, "WHERE")));
        }
        if (setFunction != null && !s.from().isEmpty()) {
            // a select list's set-returning function reads the rows WHERE keeps, as in Postgres; * lists FROM's
            Source with = lateralJoin(new Source(relation, source.bindings(), source.star()), setFunction,
                    SqlAst.JoinType.CROSS, null, setFunction.pos());
            input = new Input(with.bindings(), source.star(), "WHERE");
            relation = with.relation();
        }
        boolean aggregate = !s.groupBy().isEmpty() || hasAggregate(s);
        return aggregate ? grouped(s, input.in("GROUP BY"), relation) : plain(s, input.in("the select list"), relation);
    }

    // ------------------------------------------------------------------ FROM

    /** One FROM item: a table, a WITH query, a subquery, or a join. */
    private Source from(SqlAst.FromItem item) {
        return switch (item) {
            case SqlAst.TableName t -> {
                Rel cte = t.name().size() == 1 ? cte(t.name().get(0)) : null;
                if (cte == null) {
                    throw SqlTranslationException.unsupported("a table name needs a source function for now: write "
                            + TABLE_FUNCTION + "('pkg::Database.schema.table')", t.pos());
                }
                SqlAst.Ident alias = t.alias();
                yield bound(cte, alias != null ? alias : t.name().get(0), t.columnAliases(), t.pos());
            }
            case SqlAst.TableFunction f when !isSourceFunction(f) -> functionSource(f, new Input(List.of(), List.of(),
                    "FROM"));
            case SqlAst.TableFunction f -> {
                ValueSpecification relation = tableFunction(f);
                List<OutCol> columns = new ArrayList<>();
                sources.columns(relation, (name, type, nullable) -> columns.add(new OutCol(name, kindOf(type),
                        nullable)));
                SqlAst.Ident alias = f.alias();
                yield bound(new Rel(relation, columns), alias != null ? alias : f.name().get(f.name().size() - 1),
                        f.columnAliases(), f.pos());
            }
            case SqlAst.SubqueryFrom q -> {
                // not LATERAL: the subquery sees the WITH queries, never this query's other FROM items
                Rel rel = new SqlToPure(sources, env.with(env.ctes())).statement(q.query());
                yield bound(rel, q.alias(), q.columnAliases(), q.pos());
            }
            case SqlAst.Join j when lateral(j.right()) && j.using().isEmpty() && !j.natural() ->
                    lateralJoin(from(j.left()), j.right(), j.type(), j.on(), j.pos());
            case SqlAst.Join j -> join(from(j.left()), from(j.right()), j.type(), j.on(), j.using(), j.natural(),
                    j.pos());
        };
    }

    /** The WITH query a one-part table name names, or null. */
    private @com.legend.base.Nullable Rel cte(SqlAst.Ident name) {
        String found = resolve(name, env.ctes().keySet(), "table");
        return found == null ? null : env.ctes().get(found);
    }

    /** A relation as a FROM item: its columns renamed by the alias's column list, under its qualifier. */
    private Source bound(Rel rel, SqlAst.@com.legend.base.Nullable Ident qualifier, List<SqlAst.Ident> columnAliases,
            SqlAst.Pos pos) {
        Rel renamed = columnAliases.isEmpty() ? rel : aliased(rel, columnAliases, qualifier, pos);
        List<BCol> cols = new ArrayList<>();
        List<Col> star = new ArrayList<>();
        for (OutCol c : renamed.columns()) {
            Col col = Col.of(c.name(), c.kind(), c.nullable());
            cols.add(new BCol(col, true));
            star.add(col);
        }
        return new Source(renamed.vs(), List.of(new Binding(qualifier, cols)), star);
    }

    /** {@code alias(a, b)}: the first columns renamed, in order (Postgres lets fewer names than columns be given). */
    private Rel aliased(Rel rel, List<SqlAst.Ident> names, SqlAst.@com.legend.base.Nullable Ident qualifier,
            SqlAst.Pos pos) {
        if (names.size() > rel.columns().size()) {
            throw SqlTranslationException.invalid("table \"" + (qualifier == null ? "" : qualifier.text()) + "\" has "
                    + rel.columns().size() + " columns available but " + names.size() + " columns specified", pos);
        }
        List<String> to = new ArrayList<>();
        for (int i = 0; i < rel.columns().size(); i++) {
            to.add(i < names.size() ? names.get(i).quoted() ? names.get(i).text() : fold(names.get(i).text())
                    : rel.columns().get(i).name());
        }
        return renamed(rel, to, pos);
    }

    /** {@code rel} with its columns named {@code to}, in order, through fresh names where two would collide. */
    private Rel renamed(Rel rel, List<String> to, SqlAst.Pos pos) {
        Set<String> seen = new HashSet<>();
        for (String n : to) {
            if (!seen.add(n)) {
                throw SqlTranslationException.unsupported("the column name \"" + n + "\" appears twice; Pure relations"
                        + " need distinct column names", pos);
            }
        }
        Set<String> taken = new HashSet<>(to);
        rel.columns().forEach(c -> taken.add(c.name()));
        List<Integer> changed = new ArrayList<>();
        boolean swaps = false;
        List<OutCol> cols = new ArrayList<>();
        for (int i = 0; i < to.size(); i++) {
            OutCol c = rel.columns().get(i);
            String target = to.get(i);
            if (!c.name().equals(target)) {
                changed.add(i);
                swaps |= rel.columns().stream().anyMatch(o -> o.name().equals(target));
            }
            cols.add(new OutCol(to.get(i), c.kind(), c.nullable()));
        }
        // a new name that another column holds now: every change goes through a fresh name first
        ValueSpecification vs = rel.vs();
        List<String> via = new ArrayList<>();
        for (int i : changed) {
            String from = rel.columns().get(i).name();
            if (swaps) {
                String t = fresh(taken);
                vs = call("rename", vs, colSpec(from), colSpec(t));
                from = t;
            }
            via.add(from);
        }
        for (int k = 0; k < changed.size(); k++) {
            vs = call("rename", vs, colSpec(via.get(k)), colSpec(to.get(changed.get(k))));
        }
        return new Rel(vs, cols);
    }

    /** {@code tb('pkg::Database.schema.table')}: the table as {@code #>{pkg::Database.schema.table}#}. */
    private static ValueSpecification tableFunction(SqlAst.TableFunction f) {
        if (f.name().size() != 1 || !TABLE_FUNCTION.equals(functionName(f.name().get(0)))) {
            throw SqlTranslationException.unsupported("function \"" + f.name().get(f.name().size() - 1).text()
                    + "\" in FROM is not supported yet; the source function is " + TABLE_FUNCTION + "(...)", f.pos());
        }
        if (f.args().size() != 1 || f.args().get(0).name() != null
                || !(f.args().get(0).value() instanceof SqlAst.StringLit path)) {
            throw SqlTranslationException.invalid(TABLE_FUNCTION + "(...) takes one string: " + TABLE_FUNCTION
                    + "('pkg::Database.schema.table')", f.pos());
        }
        // the database's path holds no '.', so the first one ends it
        String p = path.value();
        int dot = p.indexOf('.');
        if (dot <= 0 || dot == p.length() - 1) {
            throw SqlTranslationException.invalid("a table is named 'pkg::Database.schema.table' or"
                    + " 'pkg::Database.table', not '" + p + "'", path.pos());
        }
        return AppliedFunction.tableReference(p.substring(0, dot), p.substring(dot + 1), null);
    }

    /** {@code right} with its columns renamed apart from {@code left}'s (a Pure relation's column names are
     *  distinct): a taken name becomes the qualifier's {@code t_name}, or a fresh one. */
    private Source apart(Source left, Source right) {
        Set<String> taken = new HashSet<>();
        left.bindings().forEach(b -> b.columns().forEach(c -> taken.add(c.col().name())));
        right.bindings().forEach(b -> b.columns().forEach(c -> taken.add(c.col().name())));
        Set<String> leftNames = new HashSet<>();
        left.bindings().forEach(b -> b.columns().forEach(c -> leftNames.add(c.col().name())));
        Map<String, String> renames = new LinkedHashMap<>();
        ValueSpecification rightRel = right.relation();
        for (Binding b : right.bindings()) {
            for (BCol c : b.columns()) {
                String name = c.col().name();
                if (leftNames.contains(name)) {
                    SqlAst.Ident q = b.qualifier();
                    String want = (q == null ? "t" : fold(q.text())) + "_" + name;
                    String to = taken.contains(want) ? fresh(taken) : want;
                    taken.add(to);
                    renames.put(name, to);
                    rightRel = call("rename", rightRel, colSpec(name), colSpec(to));
                }
            }
        }
        List<Binding> rights = new ArrayList<>();
        for (Binding b : right.bindings()) {
            List<BCol> cols = new ArrayList<>();
            for (BCol c : b.columns()) {
                Col col = c.col();
                String to = renames.get(col.name());
                cols.add(new BCol(to == null ? col : new Col(to, col.kind(), col.nullable(), col.var(), col.label()),
                        c.bare()));
            }
            rights.add(new Binding(b.qualifier(), cols, b.scalar()));
        }
        List<Col> rightStar = new ArrayList<>();
        for (Col c : right.star()) {
            String to = renames.get(c.name());
            rightStar.add(to == null ? c : new Col(to, c.kind(), c.nullable(), c.var(), c.label()));
        }
        return new Source(rightRel, rights, rightStar);
    }

    /**
     * {@code left, item} where the item reads the left's rows: a function in FROM (always lateral in Postgres) or
     * {@code LATERAL (subquery)}, by comma, {@code CROSS JOIN} or {@code [INNER] JOIN ... ON}. Pure's {@code lateral}:
     * the item's relation for each left row, that row's columns read through the lambda's parameter, the rows paired.
     */
    private Source lateralJoin(Source left, SqlAst.FromItem item, SqlAst.JoinType type,
            SqlAst.@com.legend.base.Nullable Expr on, SqlAst.Pos pos) {
        if (type != SqlAst.JoinType.CROSS && type != SqlAst.JoinType.INNER) {
            throw SqlTranslationException.unsupported(type + " JOIN LATERAL is not supported yet", pos);
        }
        Input outer = new Input(left.bindings(), left.star(), "a LATERAL item");
        Source right = switch (item) {
            case SqlAst.TableFunction f -> functionSource(f, outer);
            case SqlAst.SubqueryFrom q -> bound(new SqlToPure(sources, new Env(env.ctes(), outer, row, env.level() + 1))
                    .statement(q.query()), q.alias(), q.columnAliases(), q.pos());
            default -> throw new IllegalStateException("not a lateral item: " + item);
        };
        Source apart = apart(left, right);
        List<Binding> bindings = new ArrayList<>(left.bindings());
        bindings.addAll(apart.bindings());
        List<Col> star = new ArrayList<>(left.star());
        star.addAll(apart.star());
        ValueSpecification relation = call("lateral", left.relation(), lambda(row, apart.relation()));
        if (on != null) {
            relation = call("filter", relation, lambda(row, condition(on, new Input(bindings, star, "JOIN/ON"),
                    "JOIN/ON")));
        }
        return new Source(relation, bindings, star);
    }

    /** True iff {@code item} may read the FROM items before it: a function (not the source function) or a LATERAL
     *  subquery. */
    private static boolean lateral(SqlAst.FromItem item) {
        return item instanceof SqlAst.TableFunction f && !isSourceFunction(f)
                || item instanceof SqlAst.SubqueryFrom q && q.lateral();
    }

    private static boolean isSourceFunction(SqlAst.TableFunction f) {
        return f.name().size() == 1 && TABLE_FUNCTION.equals(functionName(f.name().get(0)));
    }

    /** A set-returning function in FROM, under its alias: its one column named by the column alias, else the table
     *  alias, else the function (Postgres's naming). */
    private Source functionSource(SqlAst.TableFunction f, Scope scope) {
        String fn = functionName(f.name().get(f.name().size() - 1));
        SqlAst.Ident alias = f.alias();
        // a function with an OUT parameter names its column by it (json_array_elements's value); one returning a
        // plain type by the table alias, else itself
        SetReturning kind = SET_RETURNING.get(fn);
        String column = kind != null && kind.outValue ? "value"
                : alias != null ? alias.quoted() ? alias.text() : fold(alias.text()) : fn;
        Rel rel = setReturning(f, fn, column, scope);
        Source source = bound(rel, alias != null ? alias : f.name().get(f.name().size() - 1), f.columnAliases(),
                f.pos());
        Binding b = source.bindings().get(0);
        return new Source(source.relation(), List.of(new Binding(b.qualifier(), b.columns(), true)), source.star());
    }

    /** A set-returning function ({@link #SET_RETURNING}) as Pure's {@code flatten} of a collection: one row per
     *  element. */
    private Rel setReturning(SqlAst.TableFunction f, String fn, String column, Scope scope) {
        if (f.name().size() != 1) {
            throw SqlTranslationException.unsupported("function \"" + f.name().get(f.name().size() - 1).text()
                    + "\" in FROM is not supported yet", f.pos());
        }
        List<SqlAst.Expr> argExprs = new ArrayList<>();
        for (SqlAst.Arg a : f.args()) {
            if (a.name() != null) {
                throw SqlTranslationException.unsupported("named arguments are not supported yet", f.pos());
            }
            argExprs.add(a.value());
        }
        SetReturning kind = SET_RETURNING.get(fn);
        if (kind == null) {
            throw SqlTranslationException.unsupported("function \"" + fn + "\" in FROM is not supported yet", f.pos());
        }
        return switch (kind) {
            case SERIES -> new Rel(call("flatten", series(argExprs, scope, f.pos()), colSpec(column)),
                    List.of(new OutCol(column, Kind.INTEGER, false)));
            case UNNEST -> {
                if (argExprs.size() != 1) {
                    throw SqlTranslationException.unsupported("unnest of " + argExprs.size() + " arrays is not"
                            + " supported yet", f.pos());
                }
                Tx elements = array(argExprs.get(0), scope);
                yield new Rel(call("flatten", elements.vs(), colSpec(column)),
                        List.of(new OutCol(column, elements.kind(), false)));
            }
            case ELEMENTS -> {
                // each element as jsonb (jsonb's null an element too); none for SQL's NULL; a value that is not an
                // array is Postgres's error, and Pure's toMany's
                ValueSpecification elements = jsonElements(f, fn, argExprs, scope);
                yield new Rel(call("flatten", elements, colSpec(column)),
                        List.of(new OutCol(column, Kind.JSON, true)));
            }
            case ELEMENTS_TEXT -> {
                // each element's text (->> of it: NULL for jsonb's null)
                String held = SYNTHETIC + "element";
                ValueSpecification rel = call("select", call("extend", call("flatten",
                        jsonElements(f, fn, argExprs, scope), colSpec(held)), new ColSpecArray(List.of(
                                new ColSpec(column, lambda(SYNTHETIC + "e", call("to", property(SYNTHETIC + "e", held),
                                        typeArg("String"))), null, null)))), new ColSpecArray(List.of(colSpec(column))));
                yield new Rel(rel, List.of(new OutCol(column, Kind.STRING, true)));
            }
            case OBJECT_KEYS -> {
                if (argExprs.size() != 1) {
                    throw SqlTranslationException.invalid("function " + fn + " takes one argument", f.pos());
                }
                constantNavigation(argExprs.get(0), f.pos());
                Tx x = jsonOperand(tx(argExprs.get(0), scope, row), fn, f.pos());
                // none for SQL's NULL; a value that is not an object is Postgres's error, and the Map read's
                // (to reads a Variant[0..1]: the value itself is never toOne'd, whose Postgres guard is text's)
                ValueSpecification keys = call("keys", call("toOne", call("to", scalarNull(x), new TypeAnnotation.Named(
                        new TypeExpression.Generic(MAP, List.of(new TypeExpression.NameRef("String"),
                                new TypeExpression.NameRef(VARIANT)))))));
                if (x.nullable()) {
                    keys = call("if", call("isEmpty", x.vs()), thunk(empty()), thunk(keys));
                }
                yield new Rel(call("flatten", keys, colSpec(column)),
                        List.of(new OutCol(column, Kind.STRING, false)));
            }
        };
    }

    /** What a set-returning function this step reads is: {@code generate_series}'s range, {@code unnest}'s array, a
     *  jsonb array's elements (as jsonb, or as text), a jsonb object's keys. {@code outValue}: its one column is its
     *  OUT parameter's, {@code value}, rather than named by its alias or itself. */
    private enum SetReturning {
        SERIES(false), UNNEST(false), ELEMENTS(true), ELEMENTS_TEXT(true), OBJECT_KEYS(false);

        final boolean outValue;

        SetReturning(boolean outValue) {
            this.outValue = outValue;
        }
    }

    /** The set-returning functions this step reads, in FROM or as a select-list item, by their Postgres names. */
    private static final Map<String, SetReturning> SET_RETURNING = Map.of(
            "generate_series", SetReturning.SERIES, "unnest", SetReturning.UNNEST,
            "json_array_elements", SetReturning.ELEMENTS, "jsonb_array_elements", SetReturning.ELEMENTS,
            "json_array_elements_text", SetReturning.ELEMENTS_TEXT, "jsonb_array_elements_text",
            SetReturning.ELEMENTS_TEXT, "json_object_keys", SetReturning.OBJECT_KEYS, "jsonb_object_keys",
            SetReturning.OBJECT_KEYS);

    private static final String MAP = "meta::pure::functions::collection::Map";

    /** A jsonb array's elements, Pure's toMany(@Variant). */
    private ValueSpecification jsonElements(SqlAst.TableFunction f, String fn, List<SqlAst.Expr> args, Scope scope) {
        if (args.size() != 1) {
            throw SqlTranslationException.invalid("function " + fn + " takes one argument", f.pos());
        }
        constantNavigation(args.get(0), f.pos());
        Tx x = jsonOperand(tx(args.get(0), scope, row), fn, f.pos());
        return call("toMany", scalarNull(x), typeArg(VARIANT));
    }

    /** A jsonb value with jsonb's null read as the scalar it is to Postgres: an array's elements and an object's keys
     *  are Postgres's error for any scalar ("cannot extract elements from a scalar"), where Pure reads a Variant's
     *  null as no value. The null becomes another scalar, so that Pure's toMany and to(@Map) raise their own errors
     *  for a value that is not an array or an object; SQL's NULL stays no value. */
    private static ValueSpecification scalarNull(Tx x) {
        ValueSpecification isJsonNull = call("and", call("isNotEmpty", x.vs()), call("isEmpty", call("to", x.vs(),
                typeArg("String"))));
        return call("if", isJsonNull, thunk(call(FROM_JSON, new CString("0"))), thunk(x.vs()));
    }

    /** {@code generate_series(start, stop [, step])} of integers, Pure's {@code range} (whose stop is exclusive): no
     *  rows when an argument is NULL. The step is a constant: zero is Postgres's error. */
    private ValueSpecification series(List<SqlAst.Expr> args, Scope scope, SqlAst.Pos pos) {
        if (args.size() != 2 && args.size() != 3) {
            throw SqlTranslationException.unsupported("generate_series of " + args.size() + " arguments is not"
                    + " supported yet", pos);
        }
        long step = 1;
        if (args.size() == 3) {
            SqlAst.Expr s = args.get(2);
            java.math.BigInteger k = s instanceof SqlAst.IntegerLit n ? n.value()
                    : s instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.MINUS
                            && u.operand() instanceof SqlAst.IntegerLit n ? n.value().negate() : null;
            if (k == null) {
                throw SqlTranslationException.unsupported("a generate_series step other than an integer constant is"
                        + " not supported yet", s.pos());
            }
            if (k.signum() == 0) {
                throw SqlTranslationException.invalid("step size cannot equal zero", s.pos());
            }
            if (k.bitLength() >= 64) {
                // Postgres reads it as numeric
                throw SqlTranslationException.unsupported("generate_series of values other than integers is not"
                        + " supported yet", s.pos());
            }
            step = k.longValueExact();
        }
        List<Tx> bounds = new ArrayList<>();
        for (SqlAst.Expr e : args.subList(0, 2)) {
            Tx t = tx(e, scope, row);
            if (t.kind() != Kind.INTEGER) {
                throw SqlTranslationException.unsupported("generate_series of values other than integers is not"
                        + " supported yet", e.pos());
            }
            bounds.add(t);
        }
        long by = step;
        Tx range = strict(Kind.INTEGER, bounds, v -> call("range", v.get(0),
                AppliedFunction.infixRun("plus", List.of(v.get(1), new CInteger(by > 0 ? 1L : -1L))),
                new CInteger(by)));
        return range.vs();
    }

    /** An array, as Pure's collection of its elements: {@code ARRAY[...]} of values that are never NULL (a Pure
     *  collection holds no NULL), or {@code string_to_array(text, 'delimiter')}. */
    private Tx array(SqlAst.Expr e, Scope scope) {
        if (e instanceof SqlAst.ArrayConstructor a) {
            if (a.elements().isEmpty()) {
                throw SqlTranslationException.unsupported("an empty ARRAY[] is not supported yet", a.pos());
            }
            List<ValueSpecification> values = new ArrayList<>();
            Kind kind = null;
            for (SqlAst.Expr x : a.elements()) {
                Tx t = tx(x, scope, row);
                if (t.nullable() || t.kind() == Kind.NULL) {
                    throw SqlTranslationException.unsupported("an array element that may be NULL is not supported yet"
                            + " (a Pure collection holds no NULL)", x.pos());
                }
                if (t.kind() == Kind.OTHER || t.kind() == Kind.BOOLEAN && t.whenFalse() != null) {
                    throw SqlTranslationException.unsupported("an array of this type is not supported yet", x.pos());
                }
                if (kind != null && kind != t.kind()) {
                    throw SqlTranslationException.unsupported("an array of " + kind.name().toLowerCase(Locale.ROOT)
                            + " and " + t.kind().name().toLowerCase(Locale.ROOT) + " values is not supported yet",
                            x.pos());
                }
                kind = t.kind();
                values.add(t.vs());
            }
            return Tx.value(new PureCollection(values), java.util.Objects.requireNonNull(kind), false);
        }
        if (e instanceof SqlAst.FunctionCall f && f.name().size() == 1
                && functionName(f.name().get(0)).equals("string_to_array")) {
            if (f.args().size() != 2) {
                throw SqlTranslationException.unsupported("string_to_array with a null string is not supported yet",
                        f.pos());
            }
            if (!(f.args().get(1) instanceof SqlAst.StringLit d) || d.value().isEmpty()) {
                throw SqlTranslationException.unsupported("a string_to_array delimiter other than a non-empty constant"
                        + " is not supported yet", f.args().get(1).pos());
            }
            Tx text = tx(f.args().get(0), scope, row);
            if (text.kind() != Kind.STRING) {
                throw SqlTranslationException.invalid("function string_to_array needs text", f.args().get(0).pos());
            }
            // an empty text is an empty array in Postgres
            return strict(Kind.STRING, List.of(text), v -> call("if", call("equal", v.get(0), new CString("")),
                    thunk(empty()), thunk(call("split", v.get(0), new CString(d.value())))));
        }
        throw SqlTranslationException.unsupported("unnest of an array other than ARRAY[...] or string_to_array(...) is"
                + " not supported yet", e.pos());
    }

    /** Two FROM items joined: the right side's columns renamed where the left has the name (a Pure relation's
     *  column names are distinct), then Pure's join, its condition over the two sides' rows. An outer join's
     *  other side may be NULL; a USING or NATURAL join's columns are merged, shown once, first. */
    private Source join(Source left, Source right, SqlAst.JoinType type, SqlAst.@com.legend.base.Nullable Expr on,
            List<SqlAst.Ident> using, boolean natural, SqlAst.Pos pos) {
        for (Binding l : left.bindings()) {
            for (Binding r : right.bindings()) {
                SqlAst.Ident lq = l.qualifier();
                SqlAst.Ident rq = r.qualifier();
                if (lq != null && rq != null && (lq.quoted() ? lq.text() : fold(lq.text())).equals(
                        rq.quoted() ? rq.text() : fold(rq.text()))) {
                    throw SqlTranslationException.invalid("table name \"" + rq.text() + "\" specified more than once",
                            rq.pos());
                }
            }
        }
        Source apart = apart(left, right);
        Set<String> taken = new HashSet<>();
        left.bindings().forEach(b -> b.columns().forEach(c -> taken.add(c.col().name())));
        apart.bindings().forEach(b -> b.columns().forEach(c -> taken.add(c.col().name())));
        ValueSpecification rightRel = apart.relation();
        List<Binding> rights = apart.bindings();
        List<Col> rightStar = apart.star();
        // USING and NATURAL: the columns the two sides share by name
        List<SqlAst.Ident> shared = new ArrayList<>(using);
        if (natural) {
            for (Col l : left.star()) {
                for (Col r : rightStar) {
                    if (l.label().equals(r.label())) {
                        shared.add(new SqlAst.Ident(l.label(), true, pos));
                    }
                }
            }
        }
        // the condition, over the two sides' rows
        List<Binding> conditionScope = new ArrayList<>();
        left.bindings().forEach(b -> conditionScope.add(b.via(leftRow)));
        rights.forEach(b -> conditionScope.add(b.via(rightRow)));
        Input sides = new Input(conditionScope, List.of(), "JOIN conditions");
        List<Col[]> merged = new ArrayList<>();
        ValueSpecification condition;
        if (!shared.isEmpty()) {
            List<ValueSpecification> tests = new ArrayList<>();
            for (SqlAst.Ident u : shared) {
                Col l = sharedColumn(left.bindings(), u, "left");
                Col r = sharedColumn(rights, u, "right");
                List<Tx> pair = List.of(columnValue(row, l.via(leftRow)), columnValue(row, r.via(rightRow)));
                notJsonKey(pair.get(0).kind(), "JOIN/USING", u.pos());
                notJsonKey(pair.get(1).kind(), "JOIN/USING", u.pos());
                if (pair.get(0).kind() != pair.get(1).kind() && !(pair.get(0).kind().numeric()
                        && pair.get(1).kind().numeric())) {
                    throw SqlTranslationException.invalid("JOIN/USING types " + l.kind().name().toLowerCase(Locale.ROOT)
                            + " and " + r.kind().name().toLowerCase(Locale.ROOT) + " cannot be matched", u.pos());
                }
                tests.add(strictTest(pair, v -> call("equal", v.get(0), v.get(1))).vs());
                merged.add(new Col[] {l, r});
            }
            condition = tests.get(0);
            for (int i = 1; i < tests.size(); i++) {
                condition = call("and", condition, tests.get(i));
            }
        } else if (on != null) {
            condition = condition(on, sides, "JOIN/ON");
        } else {
            condition = new CBoolean(true);
        }
        String kind = switch (type) {
            case INNER, CROSS -> "INNER";
            case LEFT -> "LEFT";
            case RIGHT -> "RIGHT";
            case FULL -> "FULL";
        };
        ValueSpecification relation = call("join", left.relation(), rightRel, new EnumValue(JOIN_KIND, kind),
                lambda2(leftRow, rightRow, condition));
        // an outer join's other side may hold no row
        boolean leftNullable = type == SqlAst.JoinType.RIGHT || type == SqlAst.JoinType.FULL;
        boolean rightNullable = type == SqlAst.JoinType.LEFT || type == SqlAst.JoinType.FULL;
        List<Binding> bindings = new ArrayList<>();
        Set<String> hidden = new HashSet<>();
        for (Col[] m : merged) {
            hidden.add(m[0].name());
            hidden.add(m[1].name());
        }
        for (Binding b : left.bindings()) {
            bindings.add(hide(leftNullable ? b.asNullable() : b, hidden));
        }
        for (Binding b : rights) {
            bindings.add(hide(rightNullable ? b.asNullable() : b, hidden));
        }
        List<Col> star = new ArrayList<>();
        if (!merged.isEmpty()) {
            List<BCol> mergedCols = new ArrayList<>();
            List<ColSpec> coalesced = new ArrayList<>();
            for (Col[] m : merged) {
                Col l = m[0];
                Col r = m[1];
                Col col;
                if (type == SqlAst.JoinType.FULL) {
                    String name = fresh(taken);
                    coalesced.add(new ColSpec(name, lambda(row, call("coalesce", property(row, l.name()),
                            property(row, r.name()))), null, null));
                    col = new Col(name, l.kind(), l.nullable() && r.nullable(), null, l.label());
                } else if (type == SqlAst.JoinType.RIGHT) {
                    col = new Col(r.name(), r.kind(), r.nullable(), null, l.label());
                } else {
                    col = new Col(l.name(), l.kind(), l.nullable(), null, l.label());
                }
                mergedCols.add(new BCol(col, true));
                star.add(col);
            }
            if (!coalesced.isEmpty()) {
                relation = call("extend", relation, new ColSpecArray(coalesced));
            }
            bindings.add(0, new Binding(null, mergedCols));
        }
        for (Col c : left.star()) {
            if (!hidden.contains(c.name())) {
                star.add(leftNullable ? c.asNullable() : c);
            }
        }
        for (Col c : rightStar) {
            if (!hidden.contains(c.name())) {
                star.add(rightNullable ? c.asNullable() : c);
            }
        }
        return new Source(relation, bindings, star);
    }

    /** A USING column on one side of a join: the one column there of that name. */
    private static Col sharedColumn(List<Binding> side, SqlAst.Ident name, String which) {
        Col found = null;
        for (Binding b : side) {
            String label = resolve(name, b.names(true), "column");
            if (label != null) {
                if (found != null) {
                    throw SqlTranslationException.invalid("common column name \"" + name.text() + "\" appears more"
                            + " than once in " + which + " table", name.pos());
                }
                for (BCol c : b.columns()) {
                    if (c.col().label().equals(label) && c.bare()) {
                        found = c.col();
                    }
                }
            }
        }
        if (found == null) {
            throw SqlTranslationException.invalid("column \"" + name.text() + "\" specified in USING clause does not"
                    + " exist in " + which + " table", name.pos());
        }
        return found;
    }

    /** A binding whose USING columns are reached only qualified. */
    private static Binding hide(Binding b, Set<String> hidden) {
        List<BCol> cols = new ArrayList<>();
        for (BCol c : b.columns()) {
            cols.add(new BCol(c.col(), c.bare() && !hidden.contains(c.col().name())));
        }
        return new Binding(b.qualifier(), cols, b.scalar());
    }

    // ------------------------------------------------------------------ set operations and WITH

    /** {@code UNION [ALL]}: Pure's concatenate (and distinct), the right side's columns named as the left's, in
     *  order. {@code INTERSECT} and {@code EXCEPT}: the left's rows that some right row equals (or none does), column
     *  by column with two NULLs equal (Pure's {@code ==}), once each. Then the result's sort and slice, by output name
     *  or position only (Postgres's rule). */
    private Rel setOperation(SqlAst.SetOperation s) {
        if (s.all() && s.op() != SqlAst.SetOp.UNION) {
            throw SqlTranslationException.unsupported(s.op() + " ALL is not supported yet", s.pos());
        }
        Rel left = statement(s.left());
        Rel right = statement(s.right());
        String op = s.op().name();
        if (left.columns().size() != right.columns().size()) {
            throw SqlTranslationException.invalid("each " + op + " query must have the same number of columns",
                    s.pos());
        }
        List<String> names = new ArrayList<>();
        List<OutCol> columns = new ArrayList<>();
        for (int i = 0; i < left.columns().size(); i++) {
            OutCol l = left.columns().get(i);
            OutCol r = right.columns().get(i);
            if (l.kind() != r.kind()) {
                if (l.kind() == Kind.NULL || r.kind() == Kind.NULL || l.kind().numeric() && r.kind().numeric()) {
                    throw SqlTranslationException.unsupported(op + " of " + l.kind().name().toLowerCase(Locale.ROOT)
                            + " and " + r.kind().name().toLowerCase(Locale.ROOT) + " columns is not supported yet",
                            s.pos());
                }
                throw SqlTranslationException.invalid(op + " types " + l.kind().name().toLowerCase(Locale.ROOT)
                        + " and " + r.kind().name().toLowerCase(Locale.ROOT) + " cannot be matched", s.pos());
            }
            if (!s.all()) {
                notJsonKey(l.kind(), s.op().name(), s.pos());
            }
            names.add(l.name());
            // INTERSECT's row is in both; EXCEPT's is the left's
            boolean nullable = s.op() == SqlAst.SetOp.UNION ? l.nullable() || r.nullable()
                    : s.op() == SqlAst.SetOp.INTERSECT ? l.nullable() && r.nullable() : l.nullable();
            // a decimal column merged from two queries takes one scale in the database (see common)
            columns.add(new OutCol(l.name(), l.kind() == Kind.DECIMAL ? Kind.NUMBER : l.kind(), nullable));
        }
        ValueSpecification vs = s.op() == SqlAst.SetOp.UNION ? union(s, left, right, names)
                : rowsFound(s.op() == SqlAst.SetOp.INTERSECT, left, right);
        List<ValueSpecification> sortInfos = new ArrayList<>();
        for (SqlAst.SortItem item : s.orderBy()) {
            String key = outputKey(item.expr(), names);
            notJsonKey(columns.get(names.indexOf(key)).kind(), "ORDER BY", item.expr().pos());
            boolean nullsFirst = item.nullsFirst() != null ? java.util.Objects.requireNonNull(item.nullsFirst())
                    : item.descending();
            sortInfos.add(call(item.descending() ? "descending" : "ascending", colSpec(key),
                    new EnumValue(NULL_ORDER, nullsFirst ? "FIRST" : "LAST")));
        }
        if (!sortInfos.isEmpty()) {
            vs = call("sort", vs, new PureCollection(sortInfos));
        }
        SqlAst.Expr offset = s.offset();
        if (offset != null) {
            vs = call("drop", vs, count(offset, "OFFSET"));
        }
        SqlAst.Expr limit = s.limit();
        if (limit != null) {
            vs = call("limit", vs, count(limit, "LIMIT"));
        }
        return new Rel(vs, columns);
    }

    private ValueSpecification union(SqlAst.SetOperation s, Rel left, Rel right, List<String> names) {
        boolean sameNames = true;
        for (int i = 0; i < names.size(); i++) {
            sameNames &= names.get(i).equals(right.columns().get(i).name());
        }
        Rel aligned = sameNames ? right : renamed(right, names, s.pos());
        ValueSpecification vs = call("concatenate", left.vs(), aligned.vs());
        return s.all() ? vs : call("distinct", vs);
    }

    /** The left's distinct rows that some row of the right equals ({@code found}), or that none does. */
    private ValueSpecification rowsFound(boolean found, Rel left, Rel right) {
        ValueSpecification match = null;
        for (int i = 0; i < left.columns().size(); i++) {
            ValueSpecification eq = call("equal", property(row, left.columns().get(i).name()),
                    property(sub, right.columns().get(i).name()));
            match = match == null ? eq : call("and", match, eq);
        }
        ValueSpecification in = call("exists", right.vs(), lambda(sub, java.util.Objects.requireNonNull(match)));
        return call("distinct", call("filter", left.vs(), lambda(row, found ? in : call("not", in))));
    }

    /** A set operation's ORDER BY key: an output column's name or position. */
    private static String outputKey(SqlAst.Expr e, List<String> names) {
        if (e instanceof SqlAst.IntegerLit n) {
            if (n.value().signum() <= 0 || n.value().compareTo(java.math.BigInteger.valueOf(names.size())) > 0) {
                throw SqlTranslationException.invalid("ORDER BY position " + n.value() + " is not in select list",
                        n.pos());
            }
            return names.get(n.value().intValue() - 1);
        }
        if (e instanceof SqlAst.ColumnRef ref && ref.parts().size() == 1) {
            String found = resolve(ref.parts().get(0), names, "column");
            if (found != null) {
                return found;
            }
            throw SqlTranslationException.invalid("column \"" + ref.parts().get(0).text() + "\" does not exist",
                    ref.pos());
        }
        throw SqlTranslationException.invalid("invalid UNION/INTERSECT/EXCEPT ORDER BY clause: only result column"
                + " names can be used", e.pos());
    }

    /** {@code WITH a AS (...), b AS (...) body}: each query translated once, in order, each seeing those before it;
     *  a reference to one is its relation. */
    private Rel with(SqlAst.With w) {
        if (w.recursive()) {
            throw SqlTranslationException.unsupported("WITH RECURSIVE is not supported yet", w.pos());
        }
        Map<String, Rel> ctes = new LinkedHashMap<>(env.ctes());
        for (SqlAst.Cte cte : w.ctes()) {
            String name = cte.name().quoted() ? cte.name().text() : fold(cte.name().text());
            Rel rel = new SqlToPure(sources, env.with(ctes)).statement(cte.query());
            if (!cte.columns().isEmpty()) {
                rel = aliased(rel, cte.columns(), cte.name(), cte.pos());
            }
            ctes.put(name, rel);
        }
        return new SqlToPure(sources, env.with(ctes)).statement(w.body());
    }

    /** A query with no grouping: filter, computed columns, sort, the select list, then the slice. */
    private Rel plain(SqlAst.Select s, Input in, ValueSpecification relation) {
        SqlAst.Expr having = s.having();
        if (having != null) {
            throw SqlTranslationException.unsupported("HAVING without GROUP BY or an aggregate is not supported yet",
                    having.pos());
        }
        Set<String> present = new HashSet<>(in.names());
        Windowed windowed = windows(s, in, relation, present);
        Scope scope = windowed.scope();
        List<Output> outputs = new ArrayList<>();
        Set<String> passed = new HashSet<>();
        for (SqlAst.SelectItem item : s.items()) {
            switch (item) {
                case SqlAst.AllColumns all -> {
                    SqlAst.Ident q = all.qualifier();
                    List<Col> cols = new ArrayList<>();
                    if (q == null) {
                        cols.addAll(in.star);
                    } else {
                        Binding b = in.binding(q);
                        if (b == null) {
                            throw SqlTranslationException.invalid("missing FROM-clause entry for table \"" + q.text()
                                    + "\"", q.pos());
                        }
                        b.columns().forEach(c -> cols.add(c.col()));
                    }
                    for (Col c : cols) {
                        outputs.add(passthrough(c, c.label(), passed, present, scope));
                    }
                }
                case SqlAst.SelectExpr x -> outputs.add(output(x, scope, passed, present));
            }
        }
        return assemble(s, scope, windowed.relation(), present, outputs);
    }

    /** A query with GROUP BY or aggregates: keys and aggregates by groupBy (or aggregate), then HAVING, then as
     *  plain. */
    private Rel grouped(SqlAst.Select s, Input in, ValueSpecification relation) {
        // the group keys: a column is a key as itself; any other expression is computed first
        Map<String, Col> byCanon = new LinkedHashMap<>();
        Map<String, Col> keyBySource = new HashMap<>();
        List<ColSpec> keys = new ArrayList<>();
        List<ColSpec> computedKeys = new ArrayList<>();
        Set<String> names = new HashSet<>(in.names());
        for (SqlAst.Expr g : s.groupBy()) {
            SqlAst.Expr key = groupKey(g, s, in);
            noAggregate(key, "GROUP BY");
            String canon = canon(key, in);
            if (byCanon.containsKey(canon)) {
                continue;
            }
            Col col;
            if (key instanceof SqlAst.ColumnRef ref) {
                Col source = in.column(ref);
                notJsonKey(source.kind(), "GROUP BY", key.pos());
                col = source;
                keyBySource.put(source.name(), source);
            } else {
                Tx t = tx(key, in, row);
                notJsonKey(t.kind(), "GROUP BY", key.pos());
                col = Col.of(fresh(names), t.kind(), t.nullable());
                computedKeys.add(new ColSpec(col.name(), lambda(row, valueOf(t)), null, null));
            }
            byCanon.put(canon, col);
            keys.add(new ColSpec(col.name(), null, null, null));
        }
        // the aggregates, wherever they are written
        List<ColSpec> aggregates = new ArrayList<>();
        List<ColSpec> finishes = new ArrayList<>();
        List<SqlAst.Expr> scanned = new ArrayList<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x) {
                scanned.add(x.expr());
            } else {
                throw SqlTranslationException.unsupported("\"*\" in a grouped query is not supported yet", item.pos());
            }
        }
        SqlAst.Expr having = s.having();
        if (having != null) {
            scanned.add(having);
        }
        for (SqlAst.SortItem o : s.orderBy()) {
            scanned.add(o.expr());
        }
        // an aggregate that IS a select item is computed under that item's name, so it needs no copy
        Map<String, String> preferred = new HashMap<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x && x.expr() instanceof SqlAst.FunctionCall f && isAggregate(f)) {
                SqlAst.Ident alias = x.alias();
                preferred.putIfAbsent(canon(f, in), alias != null ? alias.text() : defaultName(f));
            }
        }
        for (SqlAst.Expr e : scanned) {
            collectAggregates(e, in, byCanon, aggregates, finishes, names, preferred);
        }
        if (!computedKeys.isEmpty()) {
            relation = call("extend", relation, new ColSpecArray(computedKeys));
        }
        if (keys.isEmpty()) {
            relation = call("aggregate", relation, new ColSpecArray(aggregates));
        } else if (aggregates.isEmpty()) {
            relation = call("distinct", call("select", relation, new ColSpecArray(keys)));
        } else {
            relation = call("groupBy", relation, new ColSpecArray(keys), new ColSpecArray(aggregates));
        }
        if (!finishes.isEmpty()) {
            relation = call("extend", relation, new ColSpecArray(finishes));
        }
        Grouped g = new Grouped(in, byCanon, keyBySource);
        if (having != null) {
            relation = call("filter", relation, lambda(row, condition(having, g, "HAVING")));
        }
        Set<String> present = new HashSet<>();
        for (Col c : byCanon.values()) {
            present.add(c.name());
        }
        Windowed windowed = windows(s, g, relation, present);
        List<Output> outputs = new ArrayList<>();
        Set<String> passed = new HashSet<>();
        for (SqlAst.SelectItem item : s.items()) {
            outputs.add(output((SqlAst.SelectExpr) item, windowed.scope(), passed, present));
        }
        return assemble(s, windowed.scope(), windowed.relation(), present, outputs);
    }

    // ------------------------------------------------------------------ windows

    /** The relation with its window columns, and the scope in which each window call is its column. */
    private record Windowed(ValueSpecification relation, Scope scope) {
    }

    /** A scope in which each window call is its column; everything else is {@code base}'s. */
    private record WindowScope(Scope base, Map<SqlAst.Window, Col> calls) implements Scope {

        @Override
        public Col column(SqlAst.ColumnRef ref) {
            return base.column(ref);
        }

        @Override
        public @com.legend.base.Nullable Col columnOrNull(SqlAst.ColumnRef ref) {
            return base.columnOrNull(ref);
        }

        @Override
        public @com.legend.base.Nullable Col functionValue(SqlAst.ColumnRef ref) {
            return base.functionValue(ref);
        }

        @Override
        public boolean hasTable(SqlAst.Ident name) {
            return base.hasTable(name);
        }

        @Override
        public @com.legend.base.Nullable Col grouped(SqlAst.Expr e) {
            Col c = e instanceof SqlAst.Window w ? calls.get(w) : null;
            return c != null ? c : base.grouped(e);
        }

        @Override
        public @com.legend.base.Nullable String noAggregatesIn() {
            return base.noAggregatesIn();
        }
    }

    /** A window function's column: its {@code {p, w, r | ...}} (and an aggregate's reduce), its kind, and whether it
     *  may be NULL. */
    private record WindowColumn(LambdaFunction function, @com.legend.base.Nullable LambdaFunction reduce, Kind kind,
            boolean nullable) {

        ColSpec spec(String name) {
            return new ColSpec(name, function, reduce, null);
        }
    }

    /**
     * The select list's and ORDER BY's window calls, each a column of {@code relation}: computed after WHERE (and
     * after the grouping and HAVING), before the select list, in the order written. A partition key, a sort key or a
     * function's argument that is not a column is computed first, as a column of its own; then
     * {@code extend(over(...), ~column: {p, w, r | ...})}.
     */
    private Windowed windows(SqlAst.Select s, Scope scope, ValueSpecification relation, Set<String> present) {
        List<SqlAst.Window> calls = new ArrayList<>();
        Map<SqlAst.Window, String> named = new java.util.IdentityHashMap<>();
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x) {
                windowCalls(x.expr(), calls);
                if (x.expr() instanceof SqlAst.Window w) {
                    SqlAst.Ident alias = x.alias();
                    named.put(w, alias != null ? alias.text() : defaultName(w));
                }
            }
        }
        for (SqlAst.SortItem o : s.orderBy()) {
            windowCalls(o.expr(), calls);
        }
        if (calls.isEmpty()) {
            return new Windowed(relation, scope);
        }
        Map<SqlAst.Window, Col> columns = new java.util.IdentityHashMap<>();
        ValueSpecification rel = relation;
        for (SqlAst.Window w : calls) {
            SqlAst.WindowSpec spec = windowSpec(w.spec(), s.windows());
            List<ColSpec> computed = new ArrayList<>();
            List<String> partitionKeys = new ArrayList<>();
            for (SqlAst.Expr e : spec.partitionBy()) {
                Col key = windowKey(e, scope, present, computed);
                notJsonKey(key.kind(), "PARTITION BY", e.pos());
                partitionKeys.add(key.name());
            }
            List<ValueSpecification> sorts = new ArrayList<>();
            List<String> sortKeys = new ArrayList<>();
            Kind sortKind = null;
            for (SqlAst.SortItem o : spec.orderBy()) {
                Col key = windowKey(o.expr(), scope, present, computed);
                notJsonKey(key.kind(), "a window's ORDER BY", o.expr().pos());
                sortKind = sortKind == null ? key.kind() : sortKind;
                sortKeys.add(key.name());
                boolean nullsFirst = o.nullsFirst() != null ? java.util.Objects.requireNonNull(o.nullsFirst())
                        : o.descending();
                sorts.add(call(o.descending() ? "descending" : "ascending", colSpec(key.name()),
                        new EnumValue(NULL_ORDER, nullsFirst ? "FIRST" : "LAST")));
            }
            SqlAst.FunctionCall call = windowCall(w.call());
            SqlAst.Frame frame = spec.frame();
            String exclude = frame == null ? null : frame.exclude();
            if (frame != null && exclude != null && exclude.equals("CURRENT ROW")
                    && frame.unit() == SqlAst.FrameUnit.ROWS && !holdsCurrentRow(frame)) {
                // a ROWS frame without the current row: excluding it leaves the frame (a RANGE frame's offsets do
                // not say so: a row with a NULL key is in its NULL peers' frame whatever they are)
                frame = new SqlAst.Frame(frame.unit(), frame.start(), frame.end(), null, frame.pos());
                exclude = null;
            }
            Bounds bounds = frame == null ? null : frame(exclude == null ? frame : new SqlAst.Frame(frame.unit(),
                    frame.start(), frame.end(), null, frame.pos()), sorts.size(), sortKind);
            String want = named.get(w);
            if (frame != null && exclude != null) {
                Excluded x = excluded(call, frame, bounds, partitionKeys, sortKeys, sorts, scope, present, computed);
                if (!computed.isEmpty()) {
                    rel = call("extend", rel, new ColSpecArray(computed));
                }
                for (ValueSpecification[] step : x.windows()) {
                    rel = call("extend", rel, step[0], (ColSpec) step[1]);
                }
                String name = want != null && !present.contains(want) && !want.startsWith(SYNTHETIC) ? want
                        : fresh(present);
                present.add(name);
                rel = call("extend", rel, new ColSpec(name, lambda(row, x.value()), null, null));
                columns.put(w, Col.of(name, x.kind(), x.nullable()));
                continue;
            }
            WindowColumn column = windowColumn(call, scope, present, computed);
            if (!computed.isEmpty()) {
                rel = call("extend", rel, new ColSpecArray(computed));
            }
            String name = want != null && !present.contains(want) && !want.startsWith(SYNTHETIC) ? want
                    : fresh(present);
            present.add(name);
            rel = call("extend", rel, over(partitionKeys, sorts, bounds), column.spec(name));
            columns.put(w, Col.of(name, column.kind(), column.nullable()));
        }
        return new Windowed(rel, new WindowScope(scope, columns));
    }

    /** The window calls of {@code e}, outermost first; one inside another's arguments or window is Postgres's
     *  error. */
    private static void windowCalls(SqlAst.Expr e, List<SqlAst.Window> out) {
        if (e instanceof SqlAst.Window w) {
            for (SqlAst.Expr inner : children(w)) {
                if (containsWindow(inner)) {
                    throw SqlTranslationException.invalid("window function calls cannot be nested", inner.pos());
                }
            }
            out.add(w);
            return;
        }
        for (SqlAst.Expr child : children(e)) {
            windowCalls(child, out);
        }
    }

    private static boolean containsWindow(SqlAst.Expr e) {
        if (e instanceof SqlAst.Window) {
            return true;
        }
        for (SqlAst.Expr child : children(e)) {
            if (containsWindow(child)) {
                return true;
            }
        }
        return false;
    }

    /** A window with its named base's clauses: a reference may add an ORDER BY (when the base has none) and a frame
     *  (when the base has none), never a PARTITION BY (Postgres's rules). A named window builds only on one named
     *  before it. */
    private static SqlAst.WindowSpec windowSpec(SqlAst.WindowSpec spec, List<SqlAst.NamedWindow> windows) {
        SqlAst.Ident base = spec.base();
        if (base == null) {
            return spec;
        }
        SqlAst.WindowSpec named = null;
        for (int i = 0; i < windows.size() && named == null; i++) {
            SqlAst.Ident n = windows.get(i).name();
            if (resolve(base, List.of(n.quoted() ? n.text() : fold(n.text())), "window") != null) {
                named = windowSpec(windows.get(i).spec(), windows.subList(0, i));
            }
        }
        if (named == null) {
            throw SqlTranslationException.invalid("window \"" + base.text() + "\" does not exist", base.pos());
        }
        if (!spec.partitionBy().isEmpty()) {
            throw SqlTranslationException.invalid("cannot override PARTITION BY clause of window \"" + base.text()
                    + "\"", spec.pos());
        }
        if (!spec.orderBy().isEmpty() && !named.orderBy().isEmpty()) {
            throw SqlTranslationException.invalid("cannot override ORDER BY clause of window \"" + base.text() + "\"",
                    spec.pos());
        }
        boolean adds = !spec.orderBy().isEmpty() || spec.frame() != null;
        if (adds && named.frame() != null) {
            throw SqlTranslationException.invalid("cannot copy window \"" + base.text() + "\" because it has a frame"
                    + " clause", spec.pos());
        }
        return new SqlAst.WindowSpec(null, named.partitionBy(),
                spec.orderBy().isEmpty() ? named.orderBy() : spec.orderBy(),
                spec.frame() != null ? spec.frame() : named.frame(), spec.pos());
    }

    /** A partition key, a sort key or a window function's argument as a column of the relation: a column (or a group
     *  key, or an aggregate) as itself; any other expression computed first, under a fresh name, into
     *  {@code computed}. */
    private Col windowKey(SqlAst.Expr e, Scope scope, Set<String> present, List<ColSpec> computed) {
        Col g = scope.grouped(e);
        Col c = g != null ? g : e instanceof SqlAst.ColumnRef ref ? scope.column(ref) : null;
        if (c != null && c.var() == null) {
            return c;
        }
        Tx t = tx(e, scope, row);
        if (t.kind() == Kind.NULL) {
            throw SqlTranslationException.unsupported("a NULL constant in a window is not supported yet", e.pos());
        }
        String name = fresh(present);
        computed.add(new ColSpec(name, lambda(row, valueOf(t)), null, null));
        return Col.of(name, t.kind(), t.nullable());
    }

    /** {@code {p, w, r | body}}: a window column's lambda over the partition, the window and the row. */
    private LambdaFunction windowLambda(ValueSpecification body) {
        return new LambdaFunction(List.of(new Variable(partition), new Variable(window), new Variable(row)),
                List.of(body));
    }

    /**
     * A window call as Postgres reads it: DISTINCT and an ORDER BY inside the call are its errors over a window; an
     * aggregate's {@code FILTER (WHERE c)} is the aggregate of the rows c holds for, {@code agg(CASE WHEN c THEN x
     * END)} -- every aggregate here skips a NULL ({@code count(*)} counts the constant 1 where c holds).
     */
    private static SqlAst.FunctionCall windowCall(SqlAst.FunctionCall f) {
        if (f.name().size() != 1) {
            return f;
        }
        String fn = functionName(f.name().get(0));
        if (f.distinct()) {
            throw SqlTranslationException.invalid("DISTINCT is not implemented for window functions", f.pos());
        }
        if (!f.orderBy().isEmpty()) {
            throw SqlTranslationException.invalid("aggregate ORDER BY is not implemented for window functions",
                    f.pos());
        }
        SqlAst.Expr filter = f.filter();
        if (filter == null) {
            return f;
        }
        if (f.star() && !fn.equals("count")) {
            throw SqlTranslationException.invalid(fn + "(*) is not a function; only count(*) is", f.pos());
        }
        if (WINDOW_FUNCTIONS.contains(fn)) {
            throw SqlTranslationException.invalid("FILTER is not implemented for non-aggregate window functions",
                    f.pos());
        }
        if (containsAggregate(filter)) {
            throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
        }
        List<SqlAst.Expr> args = new ArrayList<>();
        if (f.star()) {
            args.add(new SqlAst.Case(null, List.of(new SqlAst.When(filter, new SqlAst.IntegerLit(
                    java.math.BigInteger.ONE, f.pos()))), null, f.pos()));
        } else {
            for (SqlAst.Expr a : f.args()) {
                args.add(new SqlAst.Case(null, List.of(new SqlAst.When(filter, a)), null, a.pos()));
            }
        }
        return new SqlAst.FunctionCall(f.name(), args, false, false, f.orderBy(), f.withinGroup(), null, f.pos());
    }

    /** Whether a frame's bounds hold the current row: it starts at or before it and ends at or after it (a RANGE
     *  frame's offsets are from the current row's value, which is 0 from itself; a row with a NULL key is in its NULL
     *  peers' frame whatever the bounds). */
    private static boolean holdsCurrentRow(SqlAst.Frame f) {
        return offset(f.start()) <= 0 && offset(f.end()) >= 0;
    }

    /** A bound's offset from the current row: PRECEDING negative, the unbounded ones the furthest. */
    private static long offset(SqlAst.FrameBound b) {
        return switch (b.kind()) {
            case UNBOUNDED_PRECEDING -> Long.MIN_VALUE;
            case UNBOUNDED_FOLLOWING -> Long.MAX_VALUE;
            case CURRENT_ROW -> 0;
            case PRECEDING, FOLLOWING -> {
                SqlAst.Expr e = java.util.Objects.requireNonNull(b.offset());
                long v = e instanceof SqlAst.IntegerLit n && n.value().bitLength() <= 31 ? n.value().longValue() : 0;
                yield b.kind() == SqlAst.BoundKind.PRECEDING ? -v : v;
            }
        };
    }

    /** A window aggregate over a frame with EXCLUDE: the windows its parts are computed in (each an over() and its
     *  column), then its value from them, its kind, and whether it may be NULL. */
    private record Excluded(List<ValueSpecification[]> windows, ValueSpecification value, Kind kind,
            boolean nullable) {
    }

    /** A min or max argument: Postgres has neither of a boolean (bool_and and bool_or are its), nor of jsonb. */
    private static void minMaxable(Kind kind, String fn, SqlAst.Pos pos) {
        notJsonKey(kind, fn, pos);
        if (kind == Kind.BOOLEAN) {
            throw SqlTranslationException.invalid("function " + fn + "(boolean) does not exist", pos);
        }
    }

    /** Zero in a numeric kind's Pure type. */
    private static ValueSpecification zero(Kind kind) {
        return switch (kind) {
            case FLOAT, INEXACT -> new com.legend.protocol.spec.CFloat(0.0);
            case DECIMAL, NUMBER -> new CDecimal(BigDecimal.ZERO);
            default -> new CInteger(0L);
        };
    }

    /** The aggregates EXCLUDE is read for. */
    private static final Set<String> EXCLUDABLE = Set.of("count", "sum", "avg", "min", "max");

    /**
     * {@code agg(x) OVER (... frame EXCLUDE ...)}: Pure's frames have no EXCLUDE, so the aggregate is computed from
     * windows that do not need it.
     * - count, sum, avg: the frame's count and sum, less what is excluded: EXCLUDE CURRENT ROW (of a ROWS or RANGE
     *   frame) the current row's own value; EXCLUDE GROUP (of a whole partition) the current row's peers', a window
     *   partitioned by the ORDER BY keys too; EXCLUDE TIES the peers' less the current row's own. A sum or an
     *   average is NULL where no value remains, as Postgres's is.
     * - min, max with EXCLUDE CURRENT ROW (of a whole partition): the partition's, unless the current row holds it
     *   alone; then the least (greatest) of the rest.
     * Others (a frame that is not the whole partition but for CURRENT ROW's sums, min and max of GROUP or TIES, other
     * aggregates) are not supported yet.
     */
    private Excluded excluded(SqlAst.FunctionCall f, SqlAst.Frame frame, @com.legend.base.Nullable Bounds bounds,
            List<String> partitionKeys, List<String> sortKeys, List<ValueSpecification> sorts, Scope scope,
            Set<String> present, List<ColSpec> computed) {
        String exclude = java.util.Objects.requireNonNull(frame.exclude());
        String fn = f.name().size() == 1 ? functionName(f.name().get(0)) : "";
        boolean whole = frame.start().kind() == SqlAst.BoundKind.UNBOUNDED_PRECEDING
                && frame.end().kind() == SqlAst.BoundKind.UNBOUNDED_FOLLOWING
                || frame.unit() == SqlAst.FrameUnit.RANGE && sortKeys.isEmpty() && frame.start().offset() == null
                && frame.end().offset() == null;
        boolean minMax = fn.equals("min") || fn.equals("max");
        boolean currentRow = exclude.equals("CURRENT ROW");
        if (!EXCLUDABLE.contains(fn) || !whole && !(currentRow && !minMax && (frame.unit() == SqlAst.FrameUnit.ROWS
                || frame.unit() == SqlAst.FrameUnit.RANGE && holdsCurrentRow(frame))) || minMax && !currentRow) {
            throw SqlTranslationException.unsupported("EXCLUDE " + exclude + " of " + (fn.isEmpty() ? "this function"
                    : fn + "(...)") + (whole ? "" : " over this frame") + " is not supported yet", frame.pos());
        }
        Col arg = null;
        if (f.star()) {
            if (!fn.equals("count")) {
                throw SqlTranslationException.invalid(fn + "(*) is not a function; only count(*) is", f.pos());
            }
        } else {
            windowArguments(f, 1, 1);
            arg = windowKey(f.args().get(0), scope, present, computed);
            if (minMax) {
                minMaxable(arg.kind(), fn, f.pos());
            } else if (!fn.equals("count") && !arg.kind().numeric()) {
                throw SqlTranslationException.invalid("function " + fn + "(" + postgresName(arg.kind())
                        + ") does not exist", f.pos());
            } else if (!fn.equals("count") && (arg.kind() == Kind.FLOAT || arg.kind() == Kind.INEXACT)) {
                // a frame's sum of doubles less the row's own loses the digits Postgres's sum of the rest keeps
                throw SqlTranslationException.unsupported("EXCLUDE " + exclude + " of " + fn + "(...) of double"
                        + " precision values is not supported yet", f.pos());
            }
        }
        ValueSpecification x = arg == null ? new CInteger(1L) : property(row, arg.name());
        boolean xNullable = arg != null && arg.nullable();
        ValueSpecification whole0 = over(partitionKeys, List.of(), null);
        List<ValueSpecification[]> windows = new ArrayList<>();
        if (minMax) {
            Col a = java.util.Objects.requireNonNull(arg);
            String best = fresh(present);
            windows.add(new ValueSpecification[] {whole0, new ColSpec(best, windowLambda(x), lambda(group,
                    call(fn, new Variable(group))), null)});
            ValueSpecification b = call("toOne", property(row, best));
            ValueSpecification one = xNullable ? call("toOne", x) : x;
            // the current row's value where it is the partition's best, and the rest's best beyond it
            String ties = fresh(present);
            ValueSpecification tie = call("if", call("equal", one, b), thunk(new CInteger(1L)), thunk(empty()));
            windows.add(new ValueSpecification[] {whole0, new ColSpec(ties, windowLambda(xNullable ? call("if",
                    call("isEmpty", x), thunk(empty()), thunk(tie)) : tie), lambda(group, call("count",
                    new Variable(group))), null)});
            String rest = fresh(present);
            ValueSpecification beyond = call("if", call(fn.equals("min") ? "greaterThan" : "lessThan", one, b),
                    thunk(one), thunk(empty()));
            windows.add(new ValueSpecification[] {whole0, new ColSpec(rest, windowLambda(xNullable ? call("if",
                    call("isEmpty", x), thunk(empty()), thunk(beyond)) : beyond), lambda(group, call(fn,
                    new Variable(group))), null)});
            ValueSpecification alone = call("and", call("equal", one, b), call("equal", property(row, ties),
                    new CInteger(1L)));
            if (xNullable) {
                alone = call("and", call("isNotEmpty", x), alone);
            }
            return new Excluded(windows, call("if", alone, thunk(property(row, rest)), thunk(property(row, best))),
                    a.kind(), true);
        }
        // the frame's (or the partition's) count and sum
        ValueSpecification frameOver = currentRow ? over(partitionKeys, sorts, bounds) : whole0;
        String count = fresh(present);
        windows.add(new ValueSpecification[] {frameOver, new ColSpec(count, windowLambda(x), lambda(group,
                call("count", new Variable(group))), null)});
        String sum = null;
        if (!fn.equals("count")) {
            sum = fresh(present);
            windows.add(new ValueSpecification[] {frameOver, new ColSpec(sum, windowLambda(x), lambda(group,
                    call("sum", new Variable(group))), null)});
        }
        // the current row's own value, and its count (1 where it holds one)
        ValueSpecification zero = zero(arg == null ? Kind.INTEGER : arg.kind());
        ValueSpecification own = xNullable ? call("if", call("isEmpty", x), thunk(zero), thunk(call("toOne", x))) : x;
        ValueSpecification ownCount = xNullable ? call("if", call("isEmpty", x), thunk(new CInteger(0L)),
                thunk(new CInteger(1L))) : new CInteger(1L);
        ValueSpecification remaining;
        ValueSpecification total = sum == null ? null : call("if", call("isEmpty", property(row, sum)),
                thunk(zero), thunk(call("toOne", property(row, sum))));
        if (currentRow) {
            remaining = AppliedFunction.infixRun("minus", List.of(property(row, count), ownCount));
            if (total != null) {
                total = AppliedFunction.infixRun("minus", List.of(total, own));
            }
        } else {
            List<String> peers = new ArrayList<>(partitionKeys);
            peers.addAll(sortKeys);
            ValueSpecification groupOver = over(peers, List.of(), null);
            String groupCount = fresh(present);
            windows.add(new ValueSpecification[] {groupOver, new ColSpec(groupCount, windowLambda(x), lambda(group,
                    call("count", new Variable(group))), null)});
            remaining = AppliedFunction.infixRun("minus", List.of(property(row, count), property(row, groupCount)));
            boolean ties = exclude.equals("TIES");
            if (ties) {
                remaining = AppliedFunction.infixRun("plus", List.of(remaining, ownCount));
            }
            if (total != null) {
                String groupSum = fresh(present);
                windows.add(new ValueSpecification[] {groupOver, new ColSpec(groupSum, windowLambda(x), lambda(group,
                        call("sum", new Variable(group))), null)});
                ValueSpecification peersSum = call("if", call("isEmpty", property(row, groupSum)), thunk(zero),
                        thunk(call("toOne", property(row, groupSum))));
                total = AppliedFunction.infixRun("minus", List.of(total, peersSum));
                if (ties) {
                    total = AppliedFunction.infixRun("plus", List.of(total, own));
                }
            }
        }
        if (fn.equals("count")) {
            return new Excluded(windows, remaining, Kind.INTEGER, false);
        }
        Kind argKind = java.util.Objects.requireNonNull(arg).kind();
        ValueSpecification none = call("equal", remaining, new CInteger(0L));
        ValueSpecification value = fn.equals("sum") ? java.util.Objects.requireNonNull(total)
                : call("divide", java.util.Objects.requireNonNull(total), remaining);
        return new Excluded(windows, call("if", none, thunk(empty()), thunk(value)),
                fn.equals("sum") ? sumKind(argKind) : statisticKind(argKind), true);
    }

    /** A window call's function: a ranking function, a value function (lag, lead, first_value, last_value, nth_value:
     *  the other row's column), or an aggregate over the window's frame. */
    private WindowColumn windowColumn(SqlAst.FunctionCall f, Scope scope, Set<String> present,
            List<ColSpec> computed) {
        if (f.name().size() != 1) {
            throw SqlTranslationException.unsupported("schema-qualified function names are not supported yet",
                    f.pos());
        }
        String fn = functionName(f.name().get(0));
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.unsupported("WITHIN GROUP in a window call is not supported yet", f.pos());
        }
        if (f.star() && !fn.equals("count")) {
            throw SqlTranslationException.invalid(fn + "(*) is not a function; only count(*) is", f.pos());
        }
        Variable p = new Variable(partition);
        Variable w = new Variable(window);
        Variable r = new Variable(row);
        if (BOOLEAN_AGGREGATES.contains(fn)) {
            windowArguments(f, 1, 1);
            Col arg = windowKey(f.args().get(0), scope, present, computed);
            if (arg.kind() != Kind.BOOLEAN) {
                throw SqlTranslationException.invalid("function " + fn + "(" + postgresName(arg.kind())
                        + ") does not exist", f.pos());
            }
            return new WindowColumn(windowLambda(property(row, arg.name())), lambda(group,
                    call(fn.equals("bool_or") ? "or" : "and", new Variable(group))), Kind.BOOLEAN, true);
        }
        if (fn.equals("corr")) {
            // Postgres's is NULL where a value's variance in the frame is 0; DuckDB's is NaN there (a grouped corr is
            // guarded by the pairs' variances; a window's would need them as window columns first)
            throw SqlTranslationException.unsupported("corr over a window is not supported yet", f.pos());
        }
        if (BIVARIATE.containsKey(fn)) {
            windowArguments(f, 2, 2);
            Col y = windowKey(f.args().get(0), scope, present, computed);
            Col x = windowKey(f.args().get(1), scope, present, computed);
            for (Col c : List.of(y, x)) {
                if (!c.kind().numeric()) {
                    throw SqlTranslationException.unsupported(fn + " of " + postgresName(c.kind()) + " values is not"
                            + " supported yet", f.pos());
                }
            }
            return new WindowColumn(windowLambda(call("rowMapper", property(row, y.name()), property(row,
                    x.name()))), lambda(group, call(java.util.Objects.requireNonNull(BIVARIATE.get(fn)),
                    new Variable(group))), Kind.FLOAT, true);
        }
        switch (fn) {
            case "row_number", "rank", "dense_rank", "percent_rank", "cume_dist" -> {
                windowArguments(f, 0, 0);
                ValueSpecification body = fn.equals("row_number") ? call("rowNumber", p, r)
                        : call(java.util.Objects.requireNonNull(RANKINGS.get(fn)), p, w, r);
                boolean fraction = fn.equals("percent_rank") || fn.equals("cume_dist");
                return new WindowColumn(windowLambda(body), null, fraction ? Kind.FLOAT : Kind.INTEGER, false);
            }
            case "ntile" -> {
                windowArguments(f, 1, 1);
                long n = windowCount(f.args().get(0), fn, 1);
                return new WindowColumn(windowLambda(call("ntile", p, r, new CInteger(n))), null, Kind.INTEGER, false);
            }
            case "lag", "lead" -> {
                windowArguments(f, 1, 3);
                Col arg = windowKey(f.args().get(0), scope, present, computed);
                long n = f.args().size() >= 2 ? windowCount(f.args().get(1), fn, 0) : 1L;
                ValueSpecification at = n == 1L ? call(fn, p, r) : call(fn, p, r, new CInteger(n));
                ValueSpecification other = new AppliedProperty(at, arg.name());
                if (f.args().size() < 3 || f.args().get(2) instanceof SqlAst.NullLit) {
                    return new WindowColumn(windowLambda(other), null, arg.kind(), true);
                }
                return lagDefault(f.args().get(2), arg, at, other, scope, present, computed);
            }
            case "first_value", "last_value" -> {
                windowArguments(f, 1, 1);
                Col arg = windowKey(f.args().get(0), scope, present, computed);
                ValueSpecification at = call(fn.equals("first_value") ? "first" : "last", p, w, r);
                return new WindowColumn(windowLambda(new AppliedProperty(at, arg.name())), null, arg.kind(), true);
            }
            case "nth_value" -> {
                windowArguments(f, 2, 2);
                Col arg = windowKey(f.args().get(0), scope, present, computed);
                long n = windowCount(f.args().get(1), fn, 1);
                ValueSpecification at = call("nth", p, w, r, new CInteger(n));
                return new WindowColumn(windowLambda(new AppliedProperty(at, arg.name())), null, arg.kind(), true);
            }
            case "count", "sum", "avg", "min", "max", "stddev", "stddev_samp", "stddev_pop", "variance", "var_samp",
                    "var_pop" -> {
                ValueSpecification value;
                Kind argKind;
                if (f.star()) {
                    value = new CInteger(1L);
                    argKind = Kind.INTEGER;
                } else {
                    windowArguments(f, 1, 1);
                    Col arg = windowKey(f.args().get(0), scope, present, computed);
                    value = property(row, arg.name());
                    argKind = arg.kind();
                }
                Variable values = new Variable(group);
                String statistic = STATISTICS.get(fn);
                Kind kind = switch (fn) {
                    case "count" -> Kind.INTEGER;
                    case "sum" -> sumKind(argKind);
                    case "min", "max" -> argKind;
                    default -> statisticKind(argKind);
                };
                if (fn.equals("min") || fn.equals("max")) {
                    minMaxable(argKind, fn, f.pos());
                }
                if (!fn.equals("count") && !fn.equals("min") && !fn.equals("max") && !argKind.numeric()) {
                    throw SqlTranslationException.invalid("function " + fn + "(" + argKind.name().toLowerCase(
                            Locale.ROOT) + ") does not exist", f.pos());
                }
                String reduce = statistic != null ? statistic : fn.equals("avg") ? "average" : fn;
                return new WindowColumn(windowLambda(value), lambda(group, call(reduce, values)), kind,
                        !fn.equals("count"));
            }
            default -> throw SqlTranslationException.unsupported("window function " + fn + "(...) is not supported"
                    + " yet", f.pos());
        }
    }

    /**
     * {@code lag(x, n, default)}, {@code lead(x, n, default)}: the default (the current row's) where the other row is
     * outside the partition -- not where that row's value is NULL, which stays NULL. A column that is never NULL (the
     * constant 1, computed first) tells which. The value and the default resolve to one type, as Postgres's
     * anycompatible does: an untyped string constant read as the value's type, numbers widening.
     */
    private WindowColumn lagDefault(SqlAst.Expr d, Col arg, ValueSpecification at, ValueSpecification other,
            Scope scope, Set<String> present, List<ColSpec> computed) {
        Tx dflt = d instanceof SqlAst.StringLit s && arg.kind() != Kind.STRING && readsStrings(arg.kind())
                ? fromString(s, arg.kind()) : tx(d, scope, row);
        Kind kind;
        if (dflt.kind() == arg.kind()) {
            kind = arg.kind();
        } else if (dflt.kind().numeric() && arg.kind().numeric()) {
            kind = arithmetic(arg.kind(), dflt.kind());
        } else {
            throw SqlTranslationException.unsupported("a default of " + postgresName(dflt.kind()) + " for "
                    + postgresName(arg.kind()) + " values is not supported yet", d.pos());
        }
        if (kind == Kind.DECIMAL) {
            // the value and the default take one scale in the database (see common)
            kind = Kind.NUMBER;
        }
        if (kind == Kind.FLOAT && dflt.kind() != Kind.FLOAT) {
            Tx read = dflt;
            dflt = strict(Kind.FLOAT, List.of(read), v -> call("toFloat", v.get(0)));
        }
        ValueSpecification value = other;
        if (kind == Kind.FLOAT && arg.kind() != Kind.FLOAT) {
            value = call("if", call("isEmpty", other), thunk(empty()), thunk(call("toFloat", call("toOne", other))));
        }
        String name = fresh(present);
        computed.add(new ColSpec(name, lambda(row, valueOf(dflt)), null, null));
        Col one = windowKey(new SqlAst.IntegerLit(java.math.BigInteger.ONE, d.pos()), scope, present, computed);
        ValueSpecification body = call("if", call("isEmpty", new AppliedProperty(at, one.name())),
                thunk(property(row, name)), thunk(value));
        return new WindowColumn(windowLambda(body), null, kind, arg.nullable() || dflt.nullable());
    }

    /** Postgres's ranking functions and Pure's. */
    private static final Map<String, String> RANKINGS = Map.of("rank", "rank", "dense_rank", "denseRank",
            "percent_rank", "percentRank", "cume_dist", "cumulativeDistribution");

    private static void windowArguments(SqlAst.FunctionCall f, int min, int max) {
        if (f.star() || f.args().size() < min || f.args().size() > max) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + " with "
                    + (f.star() ? "*" : f.args().size() + " arguments") + " is not supported yet", f.pos());
        }
    }

    /** A window function's count argument (ntile's buckets, lag's offset, nth_value's position): an integer constant
     *  of at least {@code min}. */
    private static long windowCount(SqlAst.Expr e, String fn, long min) {
        if (!(e instanceof SqlAst.IntegerLit n) || n.value().bitLength() > 31) {
            throw SqlTranslationException.unsupported(fn + "(...) with a computed count is not supported yet",
                    e.pos());
        }
        long v = n.value().longValue();
        if (v < min) {
            throw SqlTranslationException.unsupported(fn + "(...) with a count below " + min + " is not supported"
                    + " yet", e.pos());
        }
        return v;
    }

    /**
     * A frame as Pure's {@code rows(from, to)} or {@code _range(from, to)}: an offset signed (PRECEDING negative,
     * FOLLOWING positive, CURRENT ROW zero), UNBOUNDED as {@code unbounded()}. RANGE from the partition's start to the
     * current row's last peer is the default (none written); a whole-partition RANGE is the ROWS one.
     */
    private static @com.legend.base.Nullable Bounds frame(SqlAst.Frame f, int sortKeys,
            @com.legend.base.Nullable Kind sortKind) {
        if (f.unit() == SqlAst.FrameUnit.GROUPS) {
            throw SqlTranslationException.unsupported("GROUPS frames are not supported yet", f.pos());
        }
        String exclude = f.exclude();
        if (exclude != null) {
            throw SqlTranslationException.unsupported("EXCLUDE " + exclude + " is not supported yet", f.pos());
        }
        SqlAst.BoundKind start = f.start().kind();
        SqlAst.BoundKind end = f.end().kind();
        if (start == SqlAst.BoundKind.UNBOUNDED_FOLLOWING) {
            throw SqlTranslationException.invalid("frame start cannot be UNBOUNDED FOLLOWING", f.pos());
        }
        if (end == SqlAst.BoundKind.UNBOUNDED_PRECEDING) {
            throw SqlTranslationException.invalid("frame end cannot be UNBOUNDED PRECEDING", f.pos());
        }
        if (start == SqlAst.BoundKind.CURRENT_ROW && end == SqlAst.BoundKind.PRECEDING) {
            throw SqlTranslationException.invalid("frame starting from current row cannot have preceding rows",
                    f.pos());
        }
        if (start == SqlAst.BoundKind.FOLLOWING
                && (end == SqlAst.BoundKind.PRECEDING || end == SqlAst.BoundKind.CURRENT_ROW)) {
            throw SqlTranslationException.invalid("frame starting from following row cannot have preceding rows",
                    f.pos());
        }
        boolean offsets = f.start().offset() != null || f.end().offset() != null;
        if (f.unit() == SqlAst.FrameUnit.RANGE) {
            if (!offsets && start == SqlAst.BoundKind.UNBOUNDED_PRECEDING && end == SqlAst.BoundKind.CURRENT_ROW) {
                return null;
            }
            if (!offsets && (sortKeys == 0 || start == SqlAst.BoundKind.UNBOUNDED_PRECEDING
                    && end == SqlAst.BoundKind.UNBOUNDED_FOLLOWING)) {
                // without ORDER BY every row is the current row's peer: the frame is the whole partition
                return new Bounds(call("rows", call("unbounded"), call("unbounded")), false);
            }
            if (sortKeys != 1) {
                throw offsets ? SqlTranslationException.invalid("RANGE with offset PRECEDING/FOLLOWING requires"
                        + " exactly one ORDER BY column", f.pos())
                        : SqlTranslationException.unsupported("a RANGE frame from the current row over several sort"
                                + " keys is not supported yet", f.pos());
            }
            if (offsets && (sortKind == null || !sortKind.numeric())) {
                throw SqlTranslationException.unsupported("RANGE offsets over a non-numeric sort key are not supported"
                        + " yet", f.pos());
            }
        }
        ValueSpecification from = bound(f.start(), f.unit());
        ValueSpecification to = bound(f.end(), f.unit());
        if (from instanceof CInteger a && to instanceof CInteger b && a.value().longValue() > b.value().longValue()) {
            throw SqlTranslationException.unsupported("a frame that ends before it starts is not supported yet",
                    f.pos());
        }
        boolean range = f.unit() == SqlAst.FrameUnit.RANGE;
        return new Bounds(call(range ? "_range" : "rows", from, to), range);
    }

    /** A frame's Pure spelling, and whether it is a RANGE one (over() takes one sort key before it). */
    private record Bounds(ValueSpecification spec, boolean range) {
    }

    private static ValueSpecification bound(SqlAst.FrameBound b, SqlAst.FrameUnit unit) {
        return switch (b.kind()) {
            case UNBOUNDED_PRECEDING, UNBOUNDED_FOLLOWING -> call("unbounded");
            case CURRENT_ROW -> new CInteger(0L);
            case PRECEDING, FOLLOWING -> {
                SqlAst.Expr offset = java.util.Objects.requireNonNull(b.offset());
                if (!(offset instanceof SqlAst.IntegerLit n) || n.value().bitLength() > 31) {
                    throw SqlTranslationException.unsupported("a frame offset other than an integer constant is not"
                            + " supported yet", offset.pos());
                }
                long v = n.value().longValue();
                yield new CInteger(b.kind() == SqlAst.BoundKind.PRECEDING ? -v : v);
            }
        };
    }

    /** Pure's {@code over(...)} for the keys and the frame: the partition keys, the sort (one sort key, not a list,
     *  before a RANGE frame), the frame; with no partition keys, {@code over([sorts])} ({@code OVER ()} for none),
     *  or {@code over([], [sorts], rows(...))} with a ROWS frame. */
    private static ValueSpecification over(List<String> partitionKeys, List<ValueSpecification> sorts,
            @com.legend.base.Nullable Bounds frame) {
        boolean range = frame != null && frame.range();
        List<ValueSpecification> args = new ArrayList<>();
        if (!partitionKeys.isEmpty()) {
            List<ColSpec> keys = new ArrayList<>();
            partitionKeys.forEach(k -> keys.add(colSpec(k)));
            args.add(new ColSpecArray(keys));
        } else if (frame != null && !range) {
            args.add(new PureCollection(List.of()));
        }
        if (range) {
            args.add(sorts.get(0));
        } else if (!sorts.isEmpty() || partitionKeys.isEmpty()) {
            args.add(new PureCollection(sorts));
        }
        if (frame != null) {
            args.add(frame.spec());
        }
        return call("over", args);
    }


    /** A GROUP BY item's expression: an ordinal names a select item; a bare name is an input column first, then an
     *  output name (Postgres's order). */
    private SqlAst.Expr groupKey(SqlAst.Expr g, SqlAst.Select s, Input in) {
        if (g instanceof SqlAst.IntegerLit n) {
            return selectExpr(s, n, "GROUP BY");
        }
        if (g instanceof SqlAst.ColumnRef ref && ref.parts().size() == 1
                && !in.has(ref.parts().get(0))) {
            List<String> outputNames = new ArrayList<>();
            for (SqlAst.SelectItem item : s.items()) {
                if (item instanceof SqlAst.SelectExpr x && x.alias() != null) {
                    outputNames.add(java.util.Objects.requireNonNull(x.alias()).text());
                }
            }
            String found = resolve(ref.parts().get(0), outputNames, "column");
            if (found != null) {
                for (SqlAst.SelectItem item : s.items()) {
                    if (item instanceof SqlAst.SelectExpr x && x.alias() != null
                            && java.util.Objects.requireNonNull(x.alias()).text().equals(found)) {
                        return x.expr();
                    }
                }
            }
        }
        return g;
    }

    private static SqlAst.Expr selectExpr(SqlAst.Select s, SqlAst.IntegerLit n, String clause) {
        int i = n.value().intValue();
        if (n.value().signum() <= 0 || n.value().compareTo(java.math.BigInteger.valueOf(s.items().size())) > 0) {
            throw SqlTranslationException.invalid(clause + " position " + n.value() + " is not in select list",
                    n.pos());
        }
        SqlAst.SelectItem item = s.items().get(i - 1);
        if (item instanceof SqlAst.SelectExpr x) {
            return x.expr();
        }
        throw SqlTranslationException.unsupported(clause + " position " + i + " is \"*\", which is not supported"
                + " here yet", n.pos());
    }

    // ------------------------------------------------------------------ outputs and the tail

    /** An output column: the name the query shows, the column that holds it, and its value when it is computed. */
    private record Output(String name, String internal, @com.legend.base.Nullable ValueSpecification value,
            SqlAst.@com.legend.base.Nullable Expr expr, Kind kind, boolean nullable) {
    }

    private Output output(SqlAst.SelectExpr x, Scope scope, Set<String> passed, Set<String> present) {
        SqlAst.Ident alias = x.alias();
        Col g = scope.grouped(x.expr());
        Col col = g != null ? g : x.expr() instanceof SqlAst.ColumnRef ref ? scope.column(ref) : null;
        if (col != null && col.var() == null) {
            // a function's name read as its value shows as that name (Postgres's FigureColname), a column as itself
            String name = alias != null ? alias.text() : x.expr() instanceof SqlAst.ColumnRef ref
                    && scope.columnOrNull(ref) != null ? col.label() : defaultName(x.expr());
            Output o = passthrough(col, name, passed, present, scope);
            return new Output(o.name(), o.internal(), o.value(), x.expr(), o.kind(), o.nullable());
        }
        Tx t = tx(x.expr(), scope, row);
        String name = alias != null ? alias.text() : defaultName(x.expr());
        return new Output(name, internalFor(name, present), valueOf(t), x.expr(), t.kind(), t.nullable());
    }

    /** A column shown as itself once; shown again, or under another name, it is a computed copy. */
    private Output passthrough(Col c, String name, Set<String> passed, Set<String> present, Scope scope) {
        if (name.equals(c.name()) && passed.add(c.name())) {
            return new Output(name, c.name(), null, null, c.kind(), c.nullable());
        }
        return new Output(name, internalFor(name, present), property(row, c.name()), null, c.kind(), c.nullable());
    }

    /** The column a computed output is built in: its own name when that is free, else a fresh one (renamed last). */
    private String internalFor(String name, Set<String> present) {
        if (!present.contains(name) && !name.startsWith(SYNTHETIC)) {
            present.add(name);
            return name;
        }
        return fresh(present);
    }

    private String fresh(Set<String> taken) {
        String name;
        do {
            name = SYNTHETIC + (++fresh);
        } while (taken.contains(name));
        taken.add(name);
        return name;
    }

    /** Postgres's name for an unnamed select item (its FigureColname): a column's name, a function's name,
     *  {@code case} for CASE, else {@code ?column?}. */
    private static String defaultName(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.ColumnRef ref -> ref.parts().get(ref.parts().size() - 1).text();
            case SqlAst.FunctionCall f -> functionName(f.name().get(f.name().size() - 1));
            case SqlAst.Window w -> functionName(w.call().name().get(w.call().name().size() - 1));
            case SqlAst.Case c -> "case";
            // a subscripted value keeps its name (Postgres's FigureColname reads through the indirection)
            case SqlAst.Subscript sub -> defaultName(sub.base());
            case SqlAst.ArrayConstructor a -> "array";
            case SqlAst.Cast c -> defaultName(c.operand()).equals("?column?") ? c.type().name()
                    : defaultName(c.operand());
            default -> "?column?";
        };
    }

    /** The computed outputs, the sort, the select list, the renames and the slice, in Postgres's order. */
    private Rel assemble(SqlAst.Select s, Scope scope, ValueSpecification relation, Set<String> present,
            List<Output> outputs) {
        Set<String> shown = new HashSet<>();
        for (Output o : outputs) {
            if (!shown.add(o.name())) {
                throw SqlTranslationException.unsupported("the output column name \"" + o.name() + "\" appears twice;"
                        + " Pure relations need distinct column names, so give one an alias", s.pos());
            }
        }
        List<ColSpec> computed = new ArrayList<>();
        for (Output o : outputs) {
            ValueSpecification v = o.value();
            if (v != null) {
                computed.add(new ColSpec(o.internal(), lambda(row, v), null, null));
            }
        }
        // the sort keys: an ordinal or an output name means that output; anything else is computed for the sort
        List<ValueSpecification> sortInfos = new ArrayList<>();
        for (SqlAst.SortItem item : s.orderBy()) {
            String key = sortKey(item, s, scope, outputs, computed, present);
            boolean nullsFirst = item.nullsFirst() != null ? java.util.Objects.requireNonNull(item.nullsFirst())
                    : item.descending();
            sortInfos.add(call(item.descending() ? "descending" : "ascending", colSpec(key),
                    new EnumValue(NULL_ORDER, nullsFirst ? "FIRST" : "LAST")));
        }
        if (!computed.isEmpty()) {
            relation = call("extend", relation, new ColSpecArray(computed));
        }
        List<ColSpec> selection = new ArrayList<>();
        for (Output o : outputs) {
            selection.add(colSpec(o.internal()));
        }
        if (s.distinct()) {
            for (Output o : outputs) {
                notJsonKey(o.kind(), "DISTINCT", s.pos());
            }
            relation = call("distinct", call("select", relation, new ColSpecArray(selection)));
            if (!sortInfos.isEmpty()) {
                relation = call("sort", relation, new PureCollection(sortInfos));
            }
        } else {
            if (!sortInfos.isEmpty()) {
                relation = call("sort", relation, new PureCollection(sortInfos));
            }
            relation = call("select", relation, new ColSpecArray(selection));
        }
        for (Output o : outputs) {
            if (!o.internal().equals(o.name())) {
                relation = call("rename", relation, colSpec(o.internal()), colSpec(o.name()));
            }
        }
        SqlAst.Expr offset = s.offset();
        if (offset != null) {
            relation = call("drop", relation, count(offset, "OFFSET"));
        }
        SqlAst.Expr limit = s.limit();
        if (limit != null) {
            relation = call("limit", relation, count(limit, "LIMIT"));
        }
        List<OutCol> columns = new ArrayList<>();
        for (Output o : outputs) {
            columns.add(new OutCol(o.name(), o.kind(), o.nullable()));
        }
        return new Rel(relation, columns);
    }

    private String sortKey(SqlAst.SortItem item, SqlAst.Select s, Scope scope, List<Output> outputs,
            List<ColSpec> computed, Set<String> present) {
        SqlAst.Expr e = item.expr();
        if (e instanceof SqlAst.IntegerLit n) {
            // a position counts the select list as expanded: SELECT * ... ORDER BY 2 is the second column
            if (n.value().signum() <= 0 || n.value().compareTo(java.math.BigInteger.valueOf(outputs.size())) > 0) {
                throw SqlTranslationException.invalid("ORDER BY position " + n.value() + " is not in select list",
                        n.pos());
            }
            notJsonKey(outputs.get(n.value().intValue() - 1).kind(), "ORDER BY", n.pos());
            return outputs.get(n.value().intValue() - 1).internal();
        }
        if (e instanceof SqlAst.ColumnRef ref && ref.parts().size() == 1) {
            String found = resolve(ref.parts().get(0), outputs.stream().map(Output::name).toList(), "column");
            if (found != null) {
                for (Output o : outputs) {
                    if (o.name().equals(found)) {
                        notJsonKey(o.kind(), "ORDER BY", e.pos());
                        return o.internal();
                    }
                }
            }
        }
        if (s.distinct()) {
            Scope inputs = scope instanceof Grouped g ? g.input : scope;
            String canon = canon(e, inputScope(inputs));
            for (Output o : outputs) {
                SqlAst.Expr oe = o.expr();
                if (oe != null && canon(oe, inputScope(inputs)).equals(canon)) {
                    notJsonKey(o.kind(), "ORDER BY", e.pos());
                    return o.internal();
                }
            }
            throw SqlTranslationException.invalid("for SELECT DISTINCT, ORDER BY expressions must appear in select"
                    + " list", e.pos());
        }
        Col g = scope.grouped(e);
        if (g != null) {
            notJsonKey(g.kind(), "ORDER BY", e.pos());
            return g.name();
        }
        if (e instanceof SqlAst.ColumnRef ref) {
            notJsonKey(scope.column(ref).kind(), "ORDER BY", e.pos());
            return scope.column(ref).name();
        }
        Tx t = tx(e, scope, row);
        notJsonKey(t.kind(), "ORDER BY", e.pos());
        String name = fresh(present);
        computed.add(new ColSpec(name, lambda(row, valueOf(t)), null, null));
        return name;
    }

    private static Input inputScope(Scope scope) {
        if (scope instanceof Input i) {
            return i;
        }
        throw new IllegalStateException("not an input scope");
    }

    /** An integer constant as the Pure parser spells one: a long when it fits, else the big integer. */
    private static CInteger integer(java.math.BigInteger value) {
        return value.bitLength() < Long.SIZE ? new CInteger(value.longValue()) : new CInteger(value);
    }

    private static CInteger count(SqlAst.Expr e, String clause) {
        if (e instanceof SqlAst.IntegerLit n && n.value().signum() >= 0) {
            return integer(n.value());
        }
        throw SqlTranslationException.unsupported(clause + " takes a non-negative integer constant here", e.pos());
    }

    // ------------------------------------------------------------------ aggregates

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "stddev", "stddev_samp",
            "stddev_pop", "variance", "var_samp", "var_pop", "string_agg", "bool_and", "bool_or", "every",
            "percentile_cont", "percentile_disc", "corr", "covar_pop", "covar_samp", "regr_count", "regr_avgx",
            "regr_avgy", "regr_sxx", "regr_syy", "regr_sxy", "regr_slope", "regr_intercept", "regr_r2");

    /** Postgres's linear regressions of y on x: double precision, over the pairs that hold both values. */
    private static final Set<String> REGRESSIONS = Set.of("regr_count", "regr_avgx", "regr_avgy", "regr_sxx",
            "regr_syy", "regr_sxy", "regr_slope", "regr_intercept", "regr_r2");

    /** Postgres's ordered-set aggregates that Pure's percentile computes. */
    private static final Set<String> PERCENTILES = Set.of("percentile_cont", "percentile_disc");

    /** Postgres's aggregates of pairs and Pure's (over rowMapper pairs): double precision, a pair with a NULL
     *  skipped. */
    private static final Map<String, String> BIVARIATE = Map.of("corr", "corr", "covar_pop", "covarPopulation",
            "covar_samp", "covarSample");

    /** Postgres's boolean aggregates: Pure's and() (bool_and, every) or or() (bool_or) of the group. */
    private static final Set<String> BOOLEAN_AGGREGATES = Set.of("bool_and", "bool_or", "every");

    /** An aggregate column of the grouped relation: its map and reduce (with any it is computed from), its kind, and
     *  the column computed from them after the grouping when it is not one of them. */
    private record Aggregate(List<ColSpec> specs, Kind kind, @com.legend.base.Nullable ColSpec finish) {

        Aggregate(ColSpec spec, Kind kind) {
            this(List.of(spec), kind, null);
        }
    }

    /** Postgres's statistical aggregates and Pure's: sample (n - 1) unless named population. */
    private static final Map<String, String> STATISTICS = Map.of("stddev", "stdDevSample", "stddev_samp",
            "stdDevSample", "stddev_pop", "stdDevPopulation", "variance", "varianceSample", "var_samp",
            "varianceSample", "var_pop", "variancePopulation");

    /** Postgres's window functions that are not aggregates: they need an OVER clause. */
    private static final Set<String> WINDOW_FUNCTIONS = Set.of("row_number", "rank", "dense_rank", "percent_rank",
            "cume_dist", "ntile", "lag", "lead", "first_value", "last_value", "nth_value");

    /** A sum's kind: of integers a bigint or a numeric (WHOLE), of a sum of integers or of numerics a numeric, of
     *  doubles a double. */
    private static Kind sumKind(Kind arg) {
        return arg == Kind.INTEGER ? Kind.WHOLE : arg == Kind.DECIMAL || arg == Kind.WHOLE ? Kind.NUMBER : arg;
    }

    /** An average's or a statistic's kind: double precision of doubles; Postgres's numeric otherwise, a Float here. */
    private static Kind statisticKind(Kind arg) {
        return arg == Kind.FLOAT ? Kind.FLOAT : Kind.INEXACT;
    }

    private static boolean isAggregate(SqlAst.FunctionCall f) {
        return f.name().size() == 1 && AGGREGATES.contains(functionName(f.name().get(0)));
    }

    private static boolean hasAggregate(SqlAst.Select s) {
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x && containsAggregate(x.expr())) {
                return true;
            }
        }
        SqlAst.Expr having = s.having();
        if (having != null && containsAggregate(having)) {
            return true;
        }
        for (SqlAst.SortItem o : s.orderBy()) {
            if (containsAggregate(o.expr())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAggregate(SqlAst.Expr e) {
        if (e instanceof SqlAst.FunctionCall f && isAggregate(f)) {
            return true;
        }
        for (SqlAst.Expr child : children(e)) {
            if (containsAggregate(child)) {
                return true;
            }
        }
        return false;
    }

    private static void noAggregate(SqlAst.Expr e, String clause) {
        if (e instanceof SqlAst.FunctionCall f && isAggregate(f)) {
            throw SqlTranslationException.invalid("aggregate functions are not allowed in " + clause, e.pos());
        }
        for (SqlAst.Expr child : children(e)) {
            noAggregate(child, clause);
        }
    }

    /** Every aggregate call of {@code e}, once each: a column of the grouped relation with its map and reduce. */
    private void collectAggregates(SqlAst.Expr e, Input in, Map<String, Col> byCanon, List<ColSpec> aggregates,
            List<ColSpec> finishes, Set<String> names, Map<String, String> preferred) {
        if (e instanceof SqlAst.FunctionCall f && isAggregate(f)) {
            for (SqlAst.Expr arg : f.args()) {
                if (containsAggregate(arg)) {
                    throw SqlTranslationException.invalid("aggregate function calls cannot be nested", arg.pos());
                }
            }
            String canon = canon(f, in);
            if (byCanon.containsKey(canon)) {
                return;
            }
            String fn = functionName(f.name().get(0));
            String want = preferred.get(canon);
            String name = want != null && !names.contains(want) && !want.startsWith(SYNTHETIC) ? want : fresh(names);
            if (fn.equals("string_agg") || PERCENTILES.contains(fn) || BIVARIATE.containsKey(fn)
                    || REGRESSIONS.contains(fn)) {
                Aggregate aggregate = fn.equals("string_agg") ? new Aggregate(stringAgg(f, in, name), Kind.STRING)
                        : PERCENTILES.contains(fn) ? percentile(f, in, name)
                        : REGRESSIONS.contains(fn) ? regression(f, in, name, names) : bivariate(f, in, name, names);
                names.add(name);
                byCanon.put(canon, Col.of(name, aggregate.kind(), true));
                aggregates.addAll(aggregate.specs());
                if (aggregate.finish() != null) {
                    finishes.add(aggregate.finish());
                }
                return;
            }
            Tx arg;
            if (f.star()) {
                if (!fn.equals("count")) {
                    throw SqlTranslationException.invalid(fn + "(*) is not a function; only count(*) is", f.pos());
                }
                arg = Tx.value(new CInteger(1L), Kind.INTEGER, false);
            } else {
                if (f.args().size() != 1) {
                    throw SqlTranslationException.invalid(fn + " takes one argument here", f.pos());
                }
                arg = tx(f.args().get(0), in, row);
            }
            if (!f.withinGroup().isEmpty()) {
                throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only; "
                        + fn + " is not one", f.pos());
            }
            orderedKeys(f, in);
            if (f.distinct()) {
                // the distinct values of the group, each once (Postgres's DISTINCT inside an aggregate)
                notJsonKey(arg.kind(), fn + "(DISTINCT ...)", f.pos());
            }
            SqlAst.Expr filter = f.filter();
            if (filter != null) {
                if (containsAggregate(filter)) {
                    throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER",
                            filter.pos());
                }
                // the rows the condition holds for only: another row gives no value, as Postgres skips it
                Tx value = arg;
                ValueSpecification test = condition(filter, in, "FILTER");
                ValueSpecification kept = call("if", test, thunk(valueOf(value)), thunk(empty()));
                arg = value.kind() == Kind.BOOLEAN ? booleanValue(kept, true) : Tx.value(kept, value.kind(), true);
            }
            ValueSpecification values = new Variable(group);
            if (f.distinct()) {
                values = call("distinct", values);
            }
            Kind kind;
            ValueSpecification reduce;
            if (BOOLEAN_AGGREGATES.contains(fn)) {
                // Postgres's every is bool_and; NULLs are skipped, and no values is NULL
                if (arg.kind() != Kind.BOOLEAN) {
                    throw arg.kind() == Kind.NULL || arg.kind() == Kind.OTHER
                            ? SqlTranslationException.unsupported(fn + " of this value is not supported yet", f.pos())
                            : SqlTranslationException.invalid("function " + fn + "(" + postgresName(arg.kind())
                                    + ") does not exist", f.pos());
                }
                kind = Kind.BOOLEAN;
                reduce = call(fn.equals("bool_or") ? "or" : "and", values);
            } else {
                switch (fn) {
                    case "count" -> {
                        kind = Kind.INTEGER;
                        reduce = call("count", values);
                    }
                    case "sum" -> {
                        requireNumeric(arg, f);
                        kind = sumKind(arg.kind());
                        reduce = call("sum", values);
                    }
                    case "avg" -> {
                        requireNumeric(arg, f);
                        kind = statisticKind(arg.kind());
                        reduce = call("average", values);
                    }
                    case "min" -> {
                        minMaxable(arg.kind(), fn, f.pos());
                        kind = arg.kind();
                        reduce = call("min", values);
                    }
                    case "max" -> {
                        minMaxable(arg.kind(), fn, f.pos());
                        kind = arg.kind();
                        reduce = call("max", values);
                    }
                    default -> {
                        // stddev, variance and their sample and population forms
                        requireNumeric(arg, f);
                        kind = statisticKind(arg.kind());
                        reduce = call(java.util.Objects.requireNonNull(STATISTICS.get(fn), fn), values);
                    }
                }
            }
            // count is never NULL; every other aggregate is NULL over no values (Postgres)
            names.add(name);
            Col col = Col.of(name, kind, !fn.equals("count"));
            byCanon.put(canon, col);
            aggregates.add(new ColSpec(col.name(), lambda(row, valueOf(arg)), lambda(group, reduce), null));
            return;
        }
        for (SqlAst.Expr child : children(e)) {
            collectAggregates(child, in, byCanon, aggregates, finishes, names, preferred);
        }
    }

    /**
     * {@code percentile_cont(p)} or {@code percentile_disc(p) WITHIN GROUP (ORDER BY x [DESC]) [FILTER (WHERE ...)]}:
     * Pure's percentile over the group, NULLs skipped as Postgres skips them, {@code p} a constant fraction. The
     * continuous one interpolates between doubles, as Postgres does (each value read as one: DuckDB would interpolate
     * a decimal at the decimal's scale). The discrete one picks a value, which the lowering gives as a double: an
     * integer's is read back as its integer (Postgres's type, whose division truncates), a numeric's is inexact.
     */
    private Aggregate percentile(SqlAst.FunctionCall f, Input in, String name) {
        String fn = functionName(f.name().get(0));
        boolean continuous = fn.equals("percentile_cont");
        if (f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregate " + fn,
                    f.pos());
        }
        if (f.distinct()) {
            throw SqlTranslationException.invalid("cannot use DISTINCT with WITHIN GROUP", f.pos());
        }
        if (f.star() || f.args().size() != 1 || f.withinGroup().size() != 1 || !f.orderBy().isEmpty()) {
            throw SqlTranslationException.unsupported(fn + " of other than one fraction and one value is not"
                    + " supported yet", f.pos());
        }
        SqlAst.SortItem key = f.withinGroup().get(0);
        for (SqlAst.Expr a : List.of(f.args().get(0), key.expr())) {
            if (containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
        }
        SqlAst.Expr fraction = f.args().get(0);
        BigDecimal p = fraction instanceof SqlAst.NumericLit n ? n.value()
                : fraction instanceof SqlAst.IntegerLit n ? new BigDecimal(n.value()) : null;
        if (p == null) {
            throw SqlTranslationException.unsupported(fn + " of a fraction other than a constant is not supported"
                    + " yet", fraction.pos());
        }
        if (p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) {
            throw SqlTranslationException.invalid("percentile value " + p.toPlainString() + " is not between 0 and 1",
                    fraction.pos());
        }
        Tx x = tx(key.expr(), in, row);
        if (!x.kind().numeric()) {
            throw x.kind() == Kind.NULL || x.kind() == Kind.OTHER || !continuous
                    ? SqlTranslationException.unsupported(fn + " of " + postgresName(x.kind()) + " values is not"
                            + " supported yet", key.expr().pos())
                    : SqlTranslationException.invalid("function " + fn + "(numeric, " + postgresName(x.kind())
                            + ") does not exist", f.pos());
        }
        Tx value = continuous && x.kind() != Kind.FLOAT ? strict(Kind.FLOAT, List.of(x), v -> call("toFloat",
                v.get(0))) : x;
        ValueSpecification each = valueOf(value);
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            each = call("if", condition(filter, in, "FILTER"), thunk(each), thunk(empty()));
        }
        ValueSpecification reduce = call("percentile", new Variable(group),
                new com.legend.protocol.spec.CFloat(p.doubleValue()), new CBoolean(!key.descending()),
                new CBoolean(continuous));
        if (!continuous && (x.kind() == Kind.INTEGER || x.kind() == Kind.WHOLE)) {
            reduce = call("cast", reduce, typeArg("Integer"));
        }
        Kind kind = continuous ? (x.kind() == Kind.INEXACT ? Kind.INEXACT : Kind.FLOAT)
                : x.kind() == Kind.DECIMAL || x.kind() == Kind.NUMBER ? Kind.INEXACT : x.kind();
        return new Aggregate(new ColSpec(name, lambda(row, each), lambda(group, reduce), null), kind);
    }

    /** {@code corr(y, x)}, {@code covar_pop(y, x)}, {@code covar_samp(y, x)}: Pure's over the group's pairs
     *  ({@code rowMapper(y, x)}), a pair with a NULL skipped as Postgres skips it; double precision. */
    private Aggregate bivariate(SqlAst.FunctionCall f, Input in, String name, Set<String> names) {
        String fn = functionName(f.name().get(0));
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only; " + fn
                    + " is not one", f.pos());
        }
        if (f.distinct() || f.star() || f.args().size() != 2) {
            throw SqlTranslationException.unsupported(fn + " other than of two values is not supported yet",
                    f.pos());
        }
        orderedKeys(f, in);
        List<Tx> pair = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            if (containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
            Tx t = tx(a, in, row);
            if (!t.kind().numeric()) {
                throw SqlTranslationException.unsupported(fn + " of " + postgresName(t.kind()) + " values is not"
                        + " supported yet", a.pos());
            }
            pair.add(t);
        }
        ValueSpecification y = valueOf(pair.get(0));
        ValueSpecification x = valueOf(pair.get(1));
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            ValueSpecification test = condition(filter, in, "FILTER");
            y = call("if", test, thunk(y), thunk(empty()));
            x = call("if", test, thunk(x), thunk(empty()));
        }
        ValueSpecification reduce = call(java.util.Objects.requireNonNull(BIVARIATE.get(fn)), new Variable(group));
        if (!fn.equals("corr")) {
            return new Aggregate(new ColSpec(name, lambda(row, call("rowMapper", y, x)), lambda(group, reduce), null),
                    Kind.FLOAT);
        }
        // Postgres's corr is NULL where either value's variance over the pairs is 0 (one pair among them); a
        // database's quotient is NaN there (DuckDB's). The pairs' variances -- a value's covariance with itself, over
        // the pairs that hold both -- say where; the column is the correlation elsewhere.
        String raw = fresh(names);
        String vy = fresh(names);
        String vx = fresh(names);
        ValueSpecification yPaired = call("if", call("isEmpty", x), thunk(empty()), thunk(y));
        ValueSpecification xPaired = call("if", call("isEmpty", y), thunk(empty()), thunk(x));
        ValueSpecification flat = call("or", knownZero(vy), knownZero(vx));
        return new Aggregate(List.of(
                new ColSpec(raw, lambda(row, call("rowMapper", y, x)), lambda(group, reduce), null),
                new ColSpec(vy, lambda(row, call("rowMapper", y, yPaired)), lambda(group,
                        call("covarPopulation", new Variable(group))), null),
                new ColSpec(vx, lambda(row, call("rowMapper", x, xPaired)), lambda(group,
                        call("covarPopulation", new Variable(group))), null)),
                Kind.FLOAT, new ColSpec(name, lambda(row, call("if", flat, thunk(empty()), thunk(property(row, raw)))),
                        null, null));
    }

    /** An aggregate's {@code ORDER BY}, for one whose answer no order changes (all but string_agg): Postgres orders
     *  the values it reads, and reads the same answer from them in any order. Its keys are still checked as Postgres
     *  checks them; under DISTINCT each must be the argument. */
    private void orderedKeys(SqlAst.FunctionCall f, Input in) {
        for (SqlAst.SortItem o : f.orderBy()) {
            if (containsAggregate(o.expr())) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", o.expr().pos());
            }
            if (f.distinct() && (f.args().size() != 1 || !canon(o.expr(), in).equals(canon(f.args().get(0), in)))) {
                throw SqlTranslationException.invalid("in an aggregate with DISTINCT, ORDER BY expressions must"
                        + " appear in argument list", o.expr().pos());
            }
            tx(o.expr(), in, row);
        }
    }

    /**
     * Postgres's regressions of y on x ({@code regr_slope(y, x)} and the others), over the pairs that hold both
     * values, each value read as a double as Postgres reads it. They are computed from the pairs' count and averages
     * and their population variances and covariance (Pure's covarPopulation over rowMapper pairs), the column itself
     * after the grouping: Sxx is N times x's variance, Syy and Sxy alike; the slope Sxy / Sxx and the intercept
     * avg(y) - slope * avg(x), NULL where Sxx is 0; r2 Sxy^2 / (Sxx * Syy), 1 where only Syy is 0. As for corr, a
     * variance Postgres's running sums leave near 0 for equal values is exactly 0 here.
     */
    private Aggregate regression(SqlAst.FunctionCall f, Input in, String name, Set<String> names) {
        String fn = functionName(f.name().get(0));
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only; " + fn
                    + " is not one", f.pos());
        }
        if (f.star() || f.args().size() != 2) {
            throw SqlTranslationException.invalid("function " + fn + " takes two arguments", f.pos());
        }
        if (f.distinct()) {
            throw SqlTranslationException.unsupported(fn + "(DISTINCT ...) is not supported yet", f.pos());
        }
        orderedKeys(f, in);
        List<ValueSpecification> pair = new ArrayList<>();
        List<Boolean> nullable = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            if (containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
            Tx t = tx(a, in, row);
            if (!t.kind().numeric()) {
                throw SqlTranslationException.unsupported(fn + " of " + postgresName(t.kind()) + " values is not"
                        + " supported yet", a.pos());
            }
            ValueSpecification v = valueOf(t);
            // Postgres reads each value as a double precision
            pair.add(t.kind() == Kind.FLOAT || t.kind() == Kind.INEXACT ? v : t.nullable() ? call("if",
                    call("isEmpty", v), thunk(empty()), thunk(call("toFloat", call("toOne", v)))) : call("toFloat", v));
            nullable.add(t.nullable());
        }
        ValueSpecification y = pair.get(0);
        ValueSpecification x = pair.get(1);
        boolean maybeNull = nullable.get(0) || nullable.get(1);
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            ValueSpecification test = condition(filter, in, "FILTER");
            y = call("if", test, thunk(y), thunk(empty()));
            x = call("if", test, thunk(x), thunk(empty()));
            maybeNull = true;
        }
        // each value where its pair holds both
        ValueSpecification yPaired = maybeNull ? call("if", call("isEmpty", x), thunk(empty()), thunk(y)) : y;
        ValueSpecification xPaired = maybeNull ? call("if", call("isEmpty", y), thunk(empty()), thunk(x)) : x;
        Variable g = new Variable(group);
        switch (fn) {
            case "regr_count" -> {
                return new Aggregate(new ColSpec(name, lambda(row, xPaired), lambda(group, call("count", g)), null),
                        Kind.INTEGER);
            }
            case "regr_avgx", "regr_avgy" -> {
                return new Aggregate(new ColSpec(name, lambda(row, fn.equals("regr_avgx") ? xPaired : yPaired),
                        lambda(group, call("average", g)), null), Kind.FLOAT);
            }
            default -> {
            }
        }
        String n = fresh(names);
        String vx = fresh(names);
        String vy = fresh(names);
        String cxy = fresh(names);
        String ax = fresh(names);
        String ay = fresh(names);
        List<ColSpec> parts = List.of(
                new ColSpec(n, lambda(row, xPaired), lambda(group, call("count", g)), null),
                new ColSpec(vx, lambda(row, call("rowMapper", x, xPaired)), lambda(group, call("covarPopulation", g)),
                        null),
                new ColSpec(vy, lambda(row, call("rowMapper", y, yPaired)), lambda(group, call("covarPopulation", g)),
                        null),
                new ColSpec(cxy, lambda(row, call("rowMapper", y, x)), lambda(group, call("covarPopulation", g)),
                        null),
                new ColSpec(ax, lambda(row, xPaired), lambda(group, call("average", g)), null),
                new ColSpec(ay, lambda(row, yPaired), lambda(group, call("average", g)), null));
        ValueSpecification count = call("toFloat", property(row, n));
        ValueSpecification sxx = one(vx);
        ValueSpecification slope = call("divide", one(cxy), sxx);
        ValueSpecification flat = call("or", call("isEmpty", property(row, vx)), call("equal", sxx,
                new CInteger(0L)));
        ValueSpecification value = switch (fn) {
            case "regr_sxx" -> call("if", call("isEmpty", property(row, vx)), thunk(empty()), thunk(
                    AppliedFunction.infixRun("times", List.of(count, sxx))));
            case "regr_syy" -> call("if", call("isEmpty", property(row, vy)), thunk(empty()), thunk(
                    AppliedFunction.infixRun("times", List.of(count, one(vy)))));
            case "regr_sxy" -> call("if", call("isEmpty", property(row, cxy)), thunk(empty()), thunk(
                    AppliedFunction.infixRun("times", List.of(count, one(cxy)))));
            case "regr_slope" -> call("if", flat, thunk(empty()), thunk(slope));
            case "regr_intercept" -> call("if", flat, thunk(empty()), thunk(AppliedFunction.infixRun("minus",
                    List.of(one(ay), AppliedFunction.infixRun("times", List.of(slope, one(ax)))))));
            default -> call("if", flat, thunk(empty()), thunk(call("if", call("equal", one(vy), new CInteger(0L)),
                    thunk(call("toFloat", new CInteger(1L))), thunk(call("divide", AppliedFunction.infixRun("times",
                            List.of(one(cxy), one(cxy))), AppliedFunction.infixRun("times", List.of(sxx,
                            one(vy))))))));
        };
        return new Aggregate(parts, Kind.FLOAT, new ColSpec(name, lambda(row, value), null, null));
    }

    /** A grouped column's one value (where it holds one). */
    private ValueSpecification one(String column) {
        return call("toOne", property(row, column));
    }

    /** Whether a grouped column holds 0: known and equal to 0. */
    private ValueSpecification knownZero(String column) {
        ValueSpecification v = property(row, column);
        return call("and", call("isNotEmpty", v), call("equal", call("toOne", v), new CInteger(0L)));
    }

    /** {@code string_agg(text, 'sep' [ORDER BY col [DESC], ...]) [FILTER (WHERE ...)]}: Pure's joinStrings over the
     *  group, NULLs skipped as Postgres skips them; ordered, the group-lambda form
     *  ({@code g | $g->joinStrings(r | value, sep, ~k->ascending())}: string_agg(x, sep ORDER BY k)). A key is a
     *  column, in Postgres's default NULL placement (last ascending, first descending). */
    private ColSpec stringAgg(SqlAst.FunctionCall f, Input in, String name) {
        if (f.star() || f.args().size() != 2) {
            throw SqlTranslationException.invalid("function string_agg takes two arguments", f.pos());
        }
        if (f.distinct()) {
            throw SqlTranslationException.unsupported("string_agg(DISTINCT ...) is not supported yet", f.pos());
        }
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only;"
                    + " string_agg is not one", f.pos());
        }
        for (SqlAst.Expr a : f.args()) {
            if (containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
        }
        Tx value = tx(f.args().get(0), in, row);
        if (value.kind() != Kind.STRING) {
            throw value.kind() == Kind.OTHER || value.kind() == Kind.NULL
                    ? SqlTranslationException.unsupported("string_agg of this type is not supported yet", f.pos())
                    : SqlTranslationException.invalid("function string_agg(" + postgresName(value.kind())
                            + ", unknown) does not exist", f.pos());
        }
        if (!(f.args().get(1) instanceof SqlAst.StringLit sep)) {
            throw SqlTranslationException.unsupported("a string_agg delimiter other than a constant is not supported"
                    + " yet", f.args().get(1).pos());
        }
        ValueSpecification each = valueOf(value);
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            each = call("if", condition(filter, in, "FILTER"), thunk(each), thunk(empty()));
        }
        if (f.orderBy().isEmpty()) {
            return new ColSpec(name, lambda(row, each), lambda(group, call("joinStrings", new Variable(group),
                    new CString(sep.value()))), null);
        }
        List<ValueSpecification> keys = new ArrayList<>();
        for (SqlAst.SortItem o : f.orderBy()) {
            Col c = o.expr() instanceof SqlAst.ColumnRef ref ? in.columnOrNull(ref) : null;
            if (c == null || c.var() != null) {
                throw SqlTranslationException.unsupported("a string_agg ORDER BY key other than a column is not"
                        + " supported yet", o.expr().pos());
            }
            Boolean nullsFirst = o.nullsFirst();
            if (nullsFirst != null && nullsFirst != o.descending()) {
                throw SqlTranslationException.unsupported("a string_agg ORDER BY key with its NULLs placed otherwise"
                        + " than Postgres's default is not supported yet", o.pos());
            }
            keys.add(call(o.descending() ? "descending" : "ascending", colSpec(c.name())));
        }
        return new ColSpec(name, lambda(group, call("joinStrings", new Variable(group), lambda(row, each),
                new CString(sep.value()), keys.size() == 1 ? keys.get(0) : new PureCollection(keys))), null, null);
    }

    /** Refuses an aggregate of only an enclosing query's columns, in any clause: Postgres makes it an aggregate of
     *  that query, over its rows. */
    private static void noOuterAggregates(SqlAst.Select s, Input in) {
        List<SqlAst.Expr> all = new ArrayList<>(s.groupBy());
        for (SqlAst.SelectItem item : s.items()) {
            if (item instanceof SqlAst.SelectExpr x) {
                all.add(x.expr());
            }
        }
        s.orderBy().forEach(o -> all.add(o.expr()));
        SqlAst.Expr where = s.where();
        if (where != null) {
            all.add(where);
        }
        SqlAst.Expr having = s.having();
        if (having != null) {
            all.add(having);
        }
        for (SqlAst.Expr e : all) {
            outerAggregate(e, in);
        }
    }

    private static void outerAggregate(SqlAst.Expr e, Input in) {
        if (e instanceof SqlAst.FunctionCall f && isAggregate(f) && !f.star() && !f.args().isEmpty()
                && readsOnlyOuter(f.args().get(0), in)) {
            throw SqlTranslationException.unsupported("an aggregate of only an enclosing query's columns is not"
                    + " supported yet", f.pos());
        }
        for (SqlAst.Expr child : children(e)) {
            outerAggregate(child, in);
        }
    }

    /** True iff {@code e} reads columns and every one is an enclosing query's. */
    private static boolean readsOnlyOuter(SqlAst.Expr e, Input in) {
        List<SqlAst.ColumnRef> refs = new ArrayList<>();
        columnRefs(e, refs);
        for (SqlAst.ColumnRef ref : refs) {
            if (in.find(ref) != null) {
                return false;
            }
        }
        return !refs.isEmpty();
    }

    private static void columnRefs(SqlAst.Expr e, List<SqlAst.ColumnRef> out) {
        if (e instanceof SqlAst.ColumnRef ref) {
            out.add(ref);
        }
        for (SqlAst.Expr child : children(e)) {
            columnRefs(child, out);
        }
    }

    private static void requireNumeric(Tx arg, SqlAst.FunctionCall f) {
        if (!arg.kind().numeric()) {
            throw SqlTranslationException.invalid("function " + functionName(f.name().get(0)) + "(" + arg.kind()
                    .name().toLowerCase(Locale.ROOT) + ") does not exist", f.pos());
        }
    }

    // ------------------------------------------------------------------ expressions

    /** A WHERE, HAVING or WHEN condition: the rows where it is TRUE (NULL and FALSE alike are not TRUE). */
    private ValueSpecification condition(SqlAst.Expr e, Scope scope, String clause) {
        return truth(tx(e, scope, row), clause, e.pos()).vs();
    }

    /** A boolean operand ({@code clause}'s). A NULL constant is Postgres's unknown truth value: neither true nor
     *  false. */
    private static Tx truth(Tx t, String clause, SqlAst.Pos pos) {
        if (t.kind() == Kind.NULL) {
            return Tx.condition(new CBoolean(false), new CBoolean(false), true);
        }
        if (t.kind() != Kind.BOOLEAN) {
            throw SqlTranslationException.invalid("argument of " + clause + " must be type boolean", pos);
        }
        return t;
    }

    /** A column's value; a boolean column is a condition (TRUE when it holds true, FALSE when it holds false). */
    private static Tx columnValue(String row, Col c) {
        String var = c.var();
        ValueSpecification v = property(var != null ? var : row, c.name());
        return c.kind() == Kind.BOOLEAN ? booleanValue(v, c.nullable()) : Tx.value(v, c.kind(), c.nullable());
    }

    /** A boolean VALUE (a column, a CASE, a coalesce) as a condition. A nullable one's tests say "known and true",
     *  "known and false" outright: {@code $v == true} would be compared in SQL, NULL for a NULL, where the test must
     *  be false (Postgres: NULL IS TRUE is false). */
    private static Tx booleanValue(ValueSpecification v, boolean nullable) {
        if (!nullable) {
            return Tx.condition(v, call("not", v), false);
        }
        ValueSpecification known = call("isNotEmpty", v);
        return Tx.condition(call("and", known, call("toOne", v)), call("and", known, call("not", call("toOne", v))),
                true);
    }

    /** An expression's Pure value: a condition becomes true, false, or (when neither holds) no value. */
    private static ValueSpecification valueOf(Tx t) {
        if (t.kind() != Kind.BOOLEAN || !t.nullable()) {
            return t.vs();
        }
        return call("if", t.vs(), thunk(new CBoolean(true)),
                thunk(call("if", t.falseWhen(), thunk(new CBoolean(false)), thunk(empty()))));
    }

    /** Postgres's strict functions and operators: NULL when any argument is NULL, else {@code build} of the values. */
    private static Tx strict(Kind kind, List<Tx> args, java.util.function.Function<List<ValueSpecification>,
            ValueSpecification> build) {
        for (Tx a : args) {
            if (isNullConstant(a)) {
                // a NULL constant makes a strict operator or function NULL, of the type Postgres resolves it by
                return nullOf(kind);
            }
        }
        List<ValueSpecification> values = new ArrayList<>();
        ValueSpecification anyNull = null;
        for (Tx a : args) {
            ValueSpecification v = valueOf(a);
            if (a.nullable()) {
                anyNull = anyNull == null ? call("isEmpty", v) : call("or", anyNull, call("isEmpty", v));
                values.add(call("toOne", v));
            } else {
                values.add(v);
            }
        }
        ValueSpecification result = build.apply(values);
        if (anyNull == null) {
            return Tx.value(result, kind, false);
        }
        return Tx.value(call("if", anyNull, thunk(empty()), thunk(result)), kind, true);
    }

    /** A strict predicate (a comparison, IN, BETWEEN, LIKE): NULL when an operand is NULL, else {@code test}. */
    private static Tx strictTest(List<Tx> operands, java.util.function.Function<List<ValueSpecification>,
            ValueSpecification> test) {
        for (Tx a : operands) {
            if (isNullConstant(a)) {
                return nullOf(Kind.BOOLEAN);
            }
        }
        List<ValueSpecification> values = new ArrayList<>();
        ValueSpecification known = null;
        for (Tx a : operands) {
            ValueSpecification v = valueOf(a);
            if (a.nullable()) {
                known = known == null ? call("isNotEmpty", v) : call("and", known, call("isNotEmpty", v));
                values.add(call("toOne", v));
            } else {
                values.add(v);
            }
        }
        ValueSpecification t = test.apply(values);
        if (known == null) {
            return Tx.condition(t, call("not", t), false);
        }
        return Tx.condition(call("and", known, t), call("and", known, call("not", t)), true);
    }

    /** A NULL of a kind, as Postgres types a NULL constant by its context: Pure's empty collection cast to the kind's
     *  type (SQL's typed NULL); for a boolean, a condition that is neither TRUE nor FALSE. A NULL constant in no
     *  context of a type is text (Postgres's unknown resolves to text). */
    private static Tx nullOf(Kind kind) {
        if (kind == Kind.BOOLEAN) {
            return Tx.condition(new CBoolean(false), new CBoolean(false), true);
        }
        String type = switch (kind) {
            case INTEGER, WHOLE -> "Integer";
            case FLOAT, INEXACT -> "Float";
            case DECIMAL, NUMBER -> "Decimal";
            case STRING, NULL -> "String";
            case DATE -> "StrictDate";
            case DATETIME -> "DateTime";
            case JSON -> VARIANT;
            case BOOLEAN, OTHER -> null;
        };
        if (type == null) {
            return Tx.value(empty(), kind, true);
        }
        return Tx.value(call("cast", empty(), typeArg(type)), kind == Kind.NULL ? Kind.STRING : kind, true);
    }

    /** Whether a value is a NULL constant: untyped ({@code NULL}) or typed ({@link #nullOf}). */
    private static boolean isNullConstant(Tx t) {
        return t.kind() == Kind.NULL || t.whenFalse() == null && t.vs().equals(nullOf(t.kind()).vs());
    }

    /** An operator's operands, a NULL constant typed as the other operand (Postgres resolves an unknown by the other
     *  side); two NULL constants are Postgres's error. */
    private static List<Tx> typedOperands(Tx l, Tx r, String operator, SqlAst.Pos pos) {
        if (l.kind() == Kind.NULL && r.kind() == Kind.NULL) {
            throw SqlTranslationException.invalid("operator is not unique: unknown " + operator + " unknown", pos);
        }
        if (l.kind() == Kind.NULL) {
            return List.of(nullOf(r.kind()), r);
        }
        if (r.kind() == Kind.NULL) {
            return List.of(l, nullOf(l.kind()));
        }
        return List.of(l, r);
    }

    private static Tx not(Tx t) {
        return Tx.condition(t.falseWhen(), t.vs(), t.nullable());
    }

    private Tx tx(SqlAst.Expr e, Scope scope, String row) {
        Col g = scope.grouped(e);
        if (g != null) {
            return columnValue(row, g);
        }
        return switch (e) {
            case SqlAst.ColumnRef ref -> columnValue(row, scope.column(ref));
            case SqlAst.IntegerLit n -> Tx.value(integer(n.value()), Kind.INTEGER, false);
            case SqlAst.NumericLit n -> Tx.value(new CDecimal(n.value()), Kind.DECIMAL, false);
            case SqlAst.StringLit str -> Tx.value(new CString(str.value()), Kind.STRING, false);
            case SqlAst.BooleanLit b -> booleanValue(new CBoolean(b.value()), false);
            case SqlAst.NullLit n -> Tx.value(empty(), Kind.NULL, true);
            case SqlAst.TypedLit lit -> typedLiteral(lit);
            case SqlAst.Param p -> throw SqlTranslationException.unsupported("parameters ($" + p.index() + ") are not"
                    + " supported yet", p.pos());
            case SqlAst.Unary u -> unary(u, scope, row);
            case SqlAst.Binary b -> binary(b, scope, row);
            case SqlAst.Operator o when o.left() != null && JSON_OPERATORS.contains(o.operator()) ->
                    jsonOperator(o, scope, row);
            case SqlAst.Subscript sub -> subscript(sub, scope, row);
            case SqlAst.ArrayConstructor a -> throw SqlTranslationException.unsupported("arrays other than unnest's"
                    + " argument are not supported yet", a.pos());
            case SqlAst.Operator o -> throw SqlTranslationException.unsupported((REGEX_OPERATORS.contains(o.operator())
                    ? "regular expression operators (~, ~*, !~, !~*) are" : "the operator " + o.operator() + " is")
                    + " not supported yet", o.pos());
            case SqlAst.IsNull n -> {
                ValueSpecification v = valueOf(tx(n.operand(), scope, row));
                Tx isNull = Tx.condition(call("isEmpty", v), call("isNotEmpty", v), false);
                yield n.negated() ? not(isNull) : isNull;
            }
            case SqlAst.IsTruth t -> isTruth(t, scope, row);
            case SqlAst.IsDistinct d -> isDistinct(d, scope, row);
            case SqlAst.InList in -> inList(in, scope, row);
            case SqlAst.Between b -> between(b, scope, row);
            case SqlAst.Like l -> like(l, scope, row);
            case SqlAst.Case c -> caseExpr(c, scope, row);
            case SqlAst.FunctionCall f -> function(f, scope, row);
            case SqlAst.Cast c -> cast(c, scope, row);
            case SqlAst.ScalarSubquery q -> scalarSubquery(q, scope);
            case SqlAst.Exists q -> {
                // never NULL: a row or none
                ValueSpecification found = call("exists", subquery(q.query(), scope).vs(),
                        lambda(sub, new CBoolean(true)));
                yield Tx.condition(found, call("not", found), false);
            }
            case SqlAst.InSubquery q -> {
                // x IN (query) is x = ANY (query); NOT IN its negation (NULL stays NULL)
                Tx any = quantified(q.operand(), SqlAst.BinaryOp.EQ, false, q.query(), scope, row, q.pos());
                yield q.negated() ? not(any) : any;
            }
            case SqlAst.Quantified q -> quantified(q.left(), q.op(), q.all(), q.query(), scope, row, q.pos());
            case SqlAst.Window w -> {
                // the select list's and ORDER BY's are columns by now (windows); any other place is Postgres's error
                String clause = scope.noAggregatesIn();
                throw SqlTranslationException.invalid(clause != null && !clause.equals("the select list")
                        ? "window functions are not allowed in " + clause
                        : "window functions are not allowed here", w.pos());
            }
        };
    }

    private Tx unary(SqlAst.Unary u, Scope scope, String row) {
        Tx operand = tx(u.operand(), scope, row);
        return switch (u.op()) {
            case NOT -> not(truth(operand, "NOT", u.pos()));
            case MINUS -> {
                numericOperand(operand, u.operand());
                yield strict(operand.kind(), List.of(operand), v -> call("minus", v.get(0)));
            }
            case PLUS -> {
                numericOperand(operand, u.operand());
                yield operand;
            }
        };
    }

    private Tx binary(SqlAst.Binary b, Scope scope, String row) {
        switch (b.op()) {
            case AND, OR -> {
                Tx l = truth(tx(b.left(), scope, row), b.op().name(), b.pos());
                Tx r = truth(tx(b.right(), scope, row), b.op().name(), b.pos());
                boolean nullable = l.nullable() || r.nullable();
                // Kleene: AND is TRUE when both are, FALSE when either is; OR the other way round
                return b.op() == SqlAst.BinaryOp.AND
                        ? Tx.condition(call("and", l.vs(), r.vs()), call("or", l.falseWhen(), r.falseWhen()), nullable)
                        : Tx.condition(call("or", l.vs(), r.vs()), call("and", l.falseWhen(), r.falseWhen()), nullable);
            }
            case EQ, NE, LT, LE, GT, GE -> {
                List<Tx> sides = comparable(b.left(), b.right(), scope, row, b.pos());
                return compare(b.op(), sides.get(0), sides.get(1));
            }
            case PLUS, MINUS, TIMES -> {
                boolean additive = b.op() != SqlAst.BinaryOp.TIMES;
                if (additive && (intervalConstant(b.left()) != null || intervalConstant(b.right()) != null)) {
                    return intervalArithmetic(b, scope, row);
                }
                Tx lt = tx(b.left(), scope, row);
                Tx rt = tx(b.right(), scope, row);
                if (additive && (isDate(lt.kind()) || isDate(rt.kind()))) {
                    return dateArithmetic(b, lt, rt);
                }
                List<Tx> lr = typedOperands(lt, rt,
                        b.op() == SqlAst.BinaryOp.PLUS ? "+" : b.op() == SqlAst.BinaryOp.MINUS ? "-" : "*", b.pos());
                Tx l = lr.get(0);
                Tx r = lr.get(1);
                numericOperand(l, b.left());
                numericOperand(r, b.right());
                String fn = b.op() == SqlAst.BinaryOp.PLUS ? "plus" : b.op() == SqlAst.BinaryOp.MINUS ? "minus"
                        : "times";
                return strict(arithmetic(l.kind(), r.kind()), List.of(l, r),
                        v -> AppliedFunction.infixRun(fn, List.of(v.get(0), v.get(1))));
            }
            case DIVIDE -> {
                List<Tx> lr = typedOperands(tx(b.left(), scope, row), tx(b.right(), scope, row), "/", b.pos());
                Tx l = lr.get(0);
                Tx r = lr.get(1);
                numericOperand(l, b.left());
                numericOperand(r, b.right());
                if (l.kind() == Kind.WHOLE && (r.kind() == Kind.INTEGER || r.kind() == Kind.WHOLE)
                        || r.kind() == Kind.WHOLE && l.kind() == Kind.INTEGER) {
                    throw SqlTranslationException.unsupported("dividing a sum of integers by an integer is not supported"
                            + " yet: Postgres's sum of an integer column is a bigint or a numeric by the column's width,"
                            + " which divide differently (cast it: sum(x)::bigint / n or sum(x)::numeric / n)", b.pos());
                }
                if (l.kind() == Kind.INTEGER && r.kind() == Kind.INTEGER) {
                    return strict(Kind.INTEGER, List.of(l, r), v -> integerQuotient(v.get(0), v.get(1)));
                }
                // a double's quotient is a double, in Postgres too; a numeric's is exact there, a Float here
                boolean doubles = (l.kind() == Kind.FLOAT || r.kind() == Kind.FLOAT) && l.kind() != Kind.INEXACT
                        && r.kind() != Kind.INEXACT;
                return strict(doubles ? Kind.FLOAT : Kind.INEXACT, List.of(l, r), v -> call("divide", v.get(0),
                        v.get(1)));
            }
            case POWER -> {
                List<Tx> lr = typedOperands(tx(b.left(), scope, row), tx(b.right(), scope, row), "^", b.pos());
                Tx l = lr.get(0);
                Tx r = lr.get(1);
                numericOperand(l, b.left());
                numericOperand(r, b.right());
                // Postgres computes ^ in double precision but for a numeric operand, where it is exact; pow is a Float
                if (exactNumeric(l.kind()) || exactNumeric(r.kind())) {
                    return numericPower(b.left(), b.right(), List.of(l, r), "^", b.pos(), scope, row);
                }
                return strict(powerKind(l.kind(), r.kind()), List.of(l, r), v -> call("pow", v.get(0), v.get(1)));
            }
            case MODULO -> {
                List<Tx> lr = typedOperands(tx(b.left(), scope, row), tx(b.right(), scope, row), "%", b.pos());
                numericOperand(lr.get(0), b.left());
                numericOperand(lr.get(1), b.right());
                return remainder(lr.get(0), lr.get(1), "operator does not exist: " + postgresName(lr.get(0).kind())
                        + " % " + postgresName(lr.get(1).kind()), "%", b.pos());
            }
            case CONCAT -> {
                // a || b || c is one strict concatenation of all its operands: NULL when any is. Postgres's || reads a
                // value of another type as its text where the other side is text (text || anynonarray)
                List<SqlAst.Expr> exprs = concatenated(b);
                List<Tx> operands = new ArrayList<>();
                for (SqlAst.Expr operand : exprs) {
                    Tx t = tx(operand, scope, row);
                    if (t.kind() == Kind.JSON) {
                        throw SqlTranslationException.unsupported("|| of jsonb values (which merges them) is not"
                                + " supported yet", operand.pos());
                    }
                    operands.add(t);
                }
                Kind first = operands.get(0).kind();
                Kind second = operands.get(1).kind();
                if (first != Kind.STRING && first != Kind.NULL && second != Kind.STRING && second != Kind.NULL) {
                    throw SqlTranslationException.invalid("operator does not exist: " + postgresName(first) + " || "
                            + postgresName(second), b.pos());
                }
                List<Kind> kinds = new ArrayList<>();
                for (int i = 0; i < operands.size(); i++) {
                    Tx t = operands.get(i);
                    if (t.kind() != Kind.STRING && t.kind() != Kind.NULL) {
                        asText(t, exprs.get(i).pos(), "||");
                    }
                    kinds.add(t.kind());
                }
                return strict(Kind.STRING, operands, v -> {
                    List<ValueSpecification> texts = new ArrayList<>();
                    for (int i = 0; i < v.size(); i++) {
                        texts.add(kinds.get(i) == Kind.STRING ? v.get(i) : call("toString", v.get(i)));
                    }
                    return AppliedFunction.infixRun("plus", texts);
                });
            }
        }
        throw new IllegalStateException("unreachable: " + b.op());
    }

    /** {@code l op r} for a comparison {@code op}: NULL when either side is (Postgres's comparisons are strict). */
    private static Tx compare(SqlAst.BinaryOp op, Tx l, Tx r) {
        String fn = switch (op) {
            case EQ, NE -> "equal";
            case LT -> "lessThan";
            case LE -> "lessThanEqual";
            case GT -> "greaterThan";
            default -> "greaterThanEqual";
        };
        Tx test = strictTest(List.of(l, r), v -> call(fn, v.get(0), v.get(1)));
        return op == SqlAst.BinaryOp.NE ? not(test) : test;
    }

    /** A power's kind: of an inexact numeric, numeric in Postgres (so inexact here) unless a double is in it. */
    private static Kind powerKind(Kind a, Kind b) {
        // a sum of integers may be numeric, whose power Postgres computes exactly
        return a == Kind.INEXACT || b == Kind.INEXACT || a == Kind.WHOLE || b == Kind.WHOLE ? Kind.INEXACT
                : Kind.FLOAT;
    }

    private static boolean exactNumeric(Kind kind) {
        return kind == Kind.DECIMAL || kind == Kind.NUMBER;
    }

    /** The operands of a run of {@code ||}, left to right. */
    private static List<SqlAst.Expr> concatenated(SqlAst.Expr e) {
        if (e instanceof SqlAst.Binary b && b.op() == SqlAst.BinaryOp.CONCAT) {
            List<SqlAst.Expr> all = new ArrayList<>(concatenated(b.left()));
            all.addAll(concatenated(b.right()));
            return all;
        }
        return List.of(e);
    }

    private static void numericOperand(Tx t, SqlAst.Expr e) {
        if (t.kind() == Kind.NULL || isNullConstant(t) && t.kind().numeric()) {
            // an untyped or a numeric NULL constant: strict makes the result NULL of the operator's type (a NULL of
            // another type, a text one included, is no number)
            return;
        }
        // Postgres has these operators (an untyped constant takes the other operand's type; date - date, jsonb - key)
        if (e instanceof SqlAst.StringLit) {
            throw SqlTranslationException.unsupported("an untyped string constant in arithmetic is not supported yet",
                    e.pos());
        }
        if (isDate(t.kind())) {
            throw SqlTranslationException.unsupported("date and timestamp arithmetic is not supported yet", e.pos());
        }
        if (t.kind() == Kind.JSON) {
            throw SqlTranslationException.unsupported("jsonb's - (deleting a key or an element) is not supported yet",
                    e.pos());
        }
        if (t.kind() == Kind.OTHER) {
            throw SqlTranslationException.unsupported("arithmetic on values of this type is not supported yet",
                    e.pos());
        }
        if (!t.kind().numeric()) {
            throw SqlTranslationException.invalid("arithmetic needs numbers; this operand is not numeric", e.pos());
        }
    }

    /** The two sides of a comparison: a string constant compared with a date takes the date's type (Postgres reads
     *  an untyped constant by its context), in ISO 8601 spelling. */
    private List<Tx> comparable(SqlAst.Expr left, SqlAst.Expr right, Scope scope, String row, SqlAst.Pos pos) {
        if (left instanceof SqlAst.NullLit || right instanceof SqlAst.NullLit) {
            // NULL compared with anything is NULL in Postgres, never TRUE or FALSE: strictTest reads it so
            Tx l = left instanceof SqlAst.NullLit ? Tx.value(empty(), Kind.NULL, true) : tx(left, scope, row);
            Tx r = right instanceof SqlAst.NullLit ? Tx.value(empty(), Kind.NULL, true) : tx(right, scope, row);
            return List.of(l, r);
        }
        return comparableValues(left, right, scope, row, pos);
    }

    private List<Tx> comparableValues(SqlAst.Expr left, SqlAst.Expr right, Scope scope, String row, SqlAst.Pos pos) {
        Tx l = tx(left, scope, row);
        Tx r = tx(right, scope, row);
        if (left instanceof SqlAst.StringLit s && readsStrings(r.kind())) {
            l = fromString(s, r.kind());
        } else if (right instanceof SqlAst.StringLit s && readsStrings(l.kind())) {
            r = fromString(s, l.kind());
        }
        comparableKinds(l, r, pos);
        return List.of(l, r);
    }

    private static void comparableKinds(Tx l, Tx r, SqlAst.Pos pos) {
        notJsonKey(l.kind(), "a comparison", pos);
        notJsonKey(r.kind(), "a comparison", pos);
        boolean ok = l.kind() == r.kind() || l.kind().numeric() && r.kind().numeric()
                || isDate(l.kind()) && isDate(r.kind()) || l.kind() == Kind.OTHER || r.kind() == Kind.OTHER;
        if (!ok) {
            throw SqlTranslationException.invalid("cannot compare " + l.kind().name().toLowerCase(Locale.ROOT)
                    + " with " + r.kind().name().toLowerCase(Locale.ROOT), pos);
        }
    }

    private static boolean isDate(Kind k) {
        return k == Kind.DATE || k == Kind.DATETIME;
    }

    /** Values resolved to one type, and that type's kind. */
    private record Common(List<Tx> values, Kind kind) {
    }

    /** The results of a CASE, or the arguments of COALESCE, GREATEST or LEAST, as one type, by Postgres's resolution
     *  ("UNION, CASE, and Related Constructs"): an untyped string constant takes the others' type, read by that
     *  type's input rules (all untyped: text); the others must share a category, numbers widening to the widest;
     *  else Postgres's error. */
    private static Common common(String what, List<SqlAst.Expr> exprs, List<Tx> txs, SqlAst.Pos pos) {
        Kind target = null;
        boolean untyped = false;
        for (int i = 0; i < txs.size(); i++) {
            Kind k = txs.get(i).kind();
            if (exprs.get(i) instanceof SqlAst.StringLit) {
                untyped = true;
                continue;
            }
            if (k == Kind.NULL) {
                continue;
            }
            if (target == null || target == k) {
                target = k;
            } else if (target.numeric() && k.numeric()) {
                target = arithmetic(target, k);
            } else if (isDate(target) && isDate(k)) {
                // Postgres reads a date as a timestamp at midnight here
                throw SqlTranslationException.unsupported(what + " of date and timestamp values is not supported yet",
                        pos);
            } else {
                throw SqlTranslationException.invalid(what + " types " + postgresName(target) + " and "
                        + postgresName(k) + " cannot be matched", pos);
            }
        }
        if (target == null) {
            return new Common(txs, untyped ? Kind.STRING : Kind.NULL);
        }
        List<Tx> values = new ArrayList<>();
        int merged = 0;
        for (int i = 0; i < txs.size(); i++) {
            if (txs.get(i).kind() != Kind.NULL && !isNullConstant(txs.get(i))) {
                merged++;
            }
            if (exprs.get(i) instanceof SqlAst.StringLit s && target != Kind.STRING) {
                if (target == Kind.JSON) {
                    values.add(jsonConstant(s));
                } else if (readsStrings(target)) {
                    values.add(fromString(s, target));
                } else {
                    throw SqlTranslationException.unsupported(what + " of a string constant and a "
                            + postgresName(target) + " value is not supported yet", s.pos());
                }
            } else {
                values.add(txs.get(i));
            }
        }
        // decimals merged into one value take one scale in the database (DuckDB's CASE pads 1.5 to 1.50), each its
        // own in Postgres: the same number, but its text is refused (a numeric that is no column's)
        return new Common(values, target == Kind.DECIMAL && merged > 1 ? Kind.NUMBER : target);
    }

    /** A kind's Postgres type name, for Postgres's messages. */
    private static String postgresName(Kind k) {
        return switch (k) {
            case INTEGER -> "integer";
            case WHOLE -> "bigint";
            case FLOAT -> "double precision";
            case DECIMAL, NUMBER, INEXACT -> "numeric";
            case STRING -> "text";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "timestamp without time zone";
            case JSON -> "jsonb";
            case NULL -> "unknown";
            case OTHER -> "an enumeration";
        };
    }

    /** The kinds an untyped string constant compared with them is read as (Postgres: an "unknown" constant takes the
     *  other operand's type, read by that type's input rules). */
    private static boolean readsStrings(Kind k) {
        return isDate(k) || k == Kind.INTEGER || k == Kind.DECIMAL || k == Kind.FLOAT || k == Kind.INEXACT
                || k == Kind.BOOLEAN;
    }

    private static final Set<String> REGEX_OPERATORS = Set.of("~", "~*", "!~", "!~*");

    /** A string constant read as {@code kind}, by Postgres's input rules: dates and timestamps in ISO 8601, integers
     *  and decimals in their plain spellings (others, which Postgres may read too, are refused), booleans as
     *  Postgres's boolin reads them (any unambiguous prefix of true/false/yes/no, on/off, 1/0; nothing else). */
    private static Tx fromString(SqlAst.StringLit s, Kind kind) {
        String v = s.value();
        switch (kind) {
            case DATE, DATETIME -> {
                return dateFromString(s, kind);
            }
            case INTEGER -> {
                String n = plainNumber(v.strip(), false);
                if (n != null && new java.math.BigInteger(n).bitLength() < 64) {
                    return Tx.value(integer(new java.math.BigInteger(n)), Kind.INTEGER, false);
                }
            }
            case DECIMAL, FLOAT, INEXACT -> {
                String n = plainNumber(v.strip(), true);
                if (n != null) {
                    // a double precision constant is the double nearest its text (round('2.5'::float8) is rint's 2)
                    return kind == Kind.FLOAT ? Tx.value(call("toFloat", new CDecimal(new BigDecimal(n))), Kind.FLOAT,
                            false) : Tx.value(new CDecimal(new BigDecimal(n)), Kind.DECIMAL, false);
                }
            }
            case BOOLEAN -> {
                Boolean b = postgresBoolean(v.strip().toLowerCase(Locale.ROOT));
                if (b == null) {
                    throw SqlTranslationException.invalid("invalid input syntax for type boolean: \"" + v + "\"",
                            s.pos());
                }
                return booleanValue(new CBoolean(b), false);
            }
            default -> {
            }
        }
        throw SqlTranslationException.unsupported("the string '" + v + "' read as " + kind.name()
                .toLowerCase(Locale.ROOT) + " is not supported yet", s.pos());
    }

    /** {@code v} when it is a plain number: an optional sign and digits, and (when {@code decimal}) an optional point
     *  with digits on at least one side and an optional exponent; without its {@code +}. Null otherwise. */
    private static @com.legend.base.Nullable String plainNumber(String v, boolean decimal) {
        int i = v.startsWith("+") || v.startsWith("-") ? 1 : 0;
        int digits = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i)) && v.charAt(i) < 0x80) {
            i++;
            digits++;
        }
        if (decimal && i < v.length() && v.charAt(i) == '.') {
            i++;
            while (i < v.length() && Character.isDigit(v.charAt(i)) && v.charAt(i) < 0x80) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return null;
        }
        if (decimal && i < v.length() && (v.charAt(i) == 'e' || v.charAt(i) == 'E')) {
            int e = i + 1 < v.length() && (v.charAt(i + 1) == '+' || v.charAt(i + 1) == '-') ? i + 2 : i + 1;
            int start = e;
            while (e < v.length() && Character.isDigit(v.charAt(e)) && v.charAt(e) < 0x80) {
                e++;
            }
            if (e == start) {
                return null;
            }
            i = e;
        }
        if (i != v.length()) {
            return null;
        }
        return v.startsWith("+") ? v.substring(1) : v;
    }

    /** Postgres's parse_bool_with_len: true, false, or null for a spelling it refuses. */
    private static @com.legend.base.Nullable Boolean postgresBoolean(String v) {
        if (v.isEmpty()) {
            return null;
        }
        if ("true".startsWith(v) || "yes".startsWith(v) || v.equals("on") || v.equals("1")) {
            return Boolean.TRUE;
        }
        if ("false".startsWith(v) || "no".startsWith(v) || v.length() >= 2 && "off".startsWith(v)
                || v.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }

    // ------------------------------------------------------------------ subqueries in expressions

    /** A subquery in an expression: a query one level down, which sees {@code scope}'s columns beyond its own FROM
     *  (a correlated reference reads them from this level's row). */
    private Rel subquery(SqlAst.Statement query, Scope scope) {
        return new SqlToPure(sources, new Env(env.ctes(), scope, row, env.level() + 1)).statement(query);
    }

    /** {@code (query)} as a value: its one column in its one row, NULL when it has no row. The subquery stays a
     *  subquery in SQL, so more than one row is the database's error, as it is Postgres's ({@code toOne} spells the
     *  value's type; SQL answers NULL for no row, so the value is nullable). */
    private Tx scalarSubquery(SqlAst.ScalarSubquery q, Scope scope) {
        Rel rel = subquery(q.query(), scope);
        if (rel.columns().size() != 1) {
            throw SqlTranslationException.invalid("subquery must return only one column", q.pos());
        }
        OutCol c = rel.columns().get(0);
        ValueSpecification v = call("toOne", call("map", rel.vs(), lambda(sub, property(sub, c.name()))));
        return c.kind() == Kind.BOOLEAN ? booleanValue(v, true) : Tx.value(v, c.kind(), true);
    }

    /**
     * {@code x op ANY | ALL (query)} in three-valued logic, each subquery row's comparison strict: ANY is TRUE when
     * some row's comparison is TRUE and FALSE when every row's is FALSE (so FALSE over no rows); ALL is TRUE when every
     * row's is TRUE (so TRUE over no rows) and FALSE when some row's is FALSE; otherwise each is NULL.
     */
    private Tx quantified(SqlAst.Expr left, SqlAst.BinaryOp op, boolean all, SqlAst.Statement query,
            Scope scope, String row, SqlAst.Pos pos) {
        if (left instanceof SqlAst.NullLit) {
            throw SqlTranslationException.unsupported("a NULL constant compared with a subquery's rows is not supported"
                    + " yet", pos);
        }
        Rel rel = subquery(query, scope);
        if (rel.columns().size() != 1) {
            throw SqlTranslationException.invalid("subquery has too " + (rel.columns().size() > 1 ? "many" : "few")
                    + " columns", pos);
        }
        OutCol c = rel.columns().get(0);
        Tx each = columnValue(sub, Col.of(c.name(), c.kind(), c.nullable()));
        Tx value = left instanceof SqlAst.StringLit s && readsStrings(each.kind()) ? fromString(s, each.kind())
                : tx(left, scope, row);
        comparableKinds(value, each, pos);
        Tx test = compare(op, value, each);
        ValueSpecification some = call("exists", rel.vs(), lambda(sub, all ? test.falseWhen() : test.vs()));
        ValueSpecification every = call("not", call("exists", rel.vs(),
                lambda(sub, call("not", all ? test.vs() : test.falseWhen()))));
        return all ? Tx.condition(every, some, test.nullable()) : Tx.condition(some, every, test.nullable());
    }

    /** {@code x IS [NOT] TRUE | FALSE | UNKNOWN}: never NULL; UNKNOWN is a NULL condition (neither case holds). */
    private Tx isTruth(SqlAst.IsTruth t, Scope scope, String row) {
        Tx operand = truth(tx(t.operand(), scope, row), "IS " + t.truth(), t.pos());
        ValueSpecification holds = switch (t.truth()) {
            case TRUE -> operand.vs();
            case FALSE -> operand.falseWhen();
            case UNKNOWN -> call("and", call("not", operand.vs()), call("not", operand.falseWhen()));
        };
        Tx test = Tx.condition(holds, call("not", holds), false);
        return t.negated() ? not(test) : test;
    }

    /** {@code x IS [NOT] DISTINCT FROM y}: equality in which two NULLs are equal (Pure's {@code ==}); never NULL. */
    private Tx isDistinct(SqlAst.IsDistinct d, Scope scope, String row) {
        ValueSpecification same;
        if (d.left() instanceof SqlAst.NullLit || d.right() instanceof SqlAst.NullLit) {
            SqlAst.Expr other = d.left() instanceof SqlAst.NullLit ? d.right() : d.left();
            same = call("isEmpty", valueOf(tx(other, scope, row)));
        } else {
            List<Tx> sides = comparableValues(d.left(), d.right(), scope, row, d.pos());
            same = call("equal", valueOf(sides.get(0)), valueOf(sides.get(1)));
        }
        Tx notDistinct = Tx.condition(same, call("not", same), false);
        return d.negated() ? notDistinct : not(notDistinct);
    }

    private Tx inList(SqlAst.InList in, Scope scope, String row) {
        List<Tx> operands = new ArrayList<>();
        operands.add(tx(in.operand(), scope, row));
        boolean nullInList = false;
        for (SqlAst.Expr v : in.values()) {
            if (v instanceof SqlAst.NullLit) {
                nullInList = true;
                continue;
            }
            operands.add(comparable(in.operand(), v, scope, row, v.pos()).get(1));
        }
        if (operands.size() == 1) {
            // x IN (NULL): never TRUE, never FALSE
            return nullOf(Kind.BOOLEAN);
        }
        boolean nullableElement = false;
        for (Tx element : operands.subList(1, operands.size())) {
            nullableElement |= element.nullable();
        }
        Tx test;
        if (nullableElement) {
            // an element that may be NULL: x = e for each, in three-valued OR -- TRUE where one holds, FALSE where every
            // one fails, else NULL (x IN (col, 5) is TRUE for x = 5 whatever col is)
            ValueSpecification holds = null;
            ValueSpecification fails = null;
            for (Tx element : operands.subList(1, operands.size())) {
                Tx eq = compare(SqlAst.BinaryOp.EQ, operands.get(0), element);
                holds = holds == null ? eq.vs() : call("or", holds, eq.vs());
                fails = fails == null ? eq.falseWhen() : call("and", fails, eq.falseWhen());
            }
            test = Tx.condition(java.util.Objects.requireNonNull(holds), java.util.Objects.requireNonNull(fails), true);
        } else {
            test = strictTest(operands, v -> call("in", v.get(0), new PureCollection(v.subList(1, v.size()))));
        }
        if (nullInList) {
            // a NULL in the list: TRUE where the value is found, else NULL (it might equal the NULL), never FALSE
            test = Tx.condition(test.vs(), new CBoolean(false), true);
        }
        return in.negated() ? not(test) : test;
    }

    private Tx between(SqlAst.Between b, Scope scope, String row) {
        List<Tx> low = comparable(b.operand(), b.low(), scope, row, b.pos());
        List<Tx> high = comparable(b.operand(), b.high(), scope, row, b.pos());
        // x >= low AND x <= high, in three-valued logic: a NULL bound leaves the other to decide FALSE
        Tx l = compare(SqlAst.BinaryOp.GE, low.get(0), low.get(1));
        Tx h = compare(SqlAst.BinaryOp.LE, high.get(0), high.get(1));
        Tx test = Tx.condition(call("and", l.vs(), h.vs()), call("or", l.falseWhen(), h.falseWhen()),
                l.nullable() || h.nullable());
        return b.negated() ? not(test) : test;
    }

    /** {@code LIKE} by the shape of its constant pattern: no wildcard is equality; a trailing, leading or enclosing
     *  {@code %} alone is startsWith, endsWith or contains; anything else is the equivalent regular expression. */
    private Tx like(SqlAst.Like l, Scope scope, String row) {
        Tx operand = tx(l.operand(), scope, row);
        if (operand.kind() != Kind.STRING) {
            throw SqlTranslationException.invalid("LIKE needs a text value", l.operand().pos());
        }
        if (!(l.pattern() instanceof SqlAst.StringLit pattern)) {
            throw SqlTranslationException.unsupported("LIKE with a pattern that is not a constant is not supported"
                    + " yet", l.pattern().pos());
        }
        char escape = '\\';
        SqlAst.Expr esc = l.escape();
        if (esc != null) {
            if (!(esc instanceof SqlAst.StringLit e) || e.value().length() > 1) {
                throw SqlTranslationException.invalid("invalid escape string: it must be empty or one character",
                        esc.pos());
            }
            escape = e.value().isEmpty() ? 0 : e.value().charAt(0);
        }
        // the pattern as literal runs between wildcards: run, wildcard, run, ..., run
        List<String> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        String p = pattern.value();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (escape != 0 && c == escape) {
                if (i + 1 >= p.length()) {
                    throw SqlTranslationException.invalid("LIKE pattern must not end with escape character",
                            pattern.pos());
                }
                run.append(p.charAt(++i));
            } else if (c == '%' || c == '_') {
                parts.add(run.toString());
                parts.add(String.valueOf(c));
                run.setLength(0);
            } else {
                run.append(c);
            }
        }
        parts.add(run.toString());
        List<String> wild = new ArrayList<>();
        for (int i = 1; i < parts.size(); i += 2) {
            wild.add(parts.get(i));
        }
        boolean ci = l.caseInsensitive();
        boolean onlyPercent = !wild.contains("_");
        java.util.function.Function<List<ValueSpecification>, ValueSpecification> test;
        if (wild.isEmpty()) {
            test = v -> textTest("equal", v.get(0), parts.get(0), ci);
        } else if (onlyPercent && wild.size() == 1 && parts.get(2).isEmpty() && !parts.get(0).isEmpty()) {
            test = v -> textTest("startsWith", v.get(0), parts.get(0), ci);
        } else if (onlyPercent && wild.size() == 1 && parts.get(0).isEmpty() && !parts.get(2).isEmpty()) {
            test = v -> textTest("endsWith", v.get(0), parts.get(2), ci);
        } else if (onlyPercent && wild.size() == 2 && parts.get(0).isEmpty() && parts.get(4).isEmpty()
                && !parts.get(2).isEmpty()) {
            test = v -> textTest("contains", v.get(0), parts.get(2), ci);
        } else {
            StringBuilder regex = new StringBuilder(ci ? "(?i)^" : "^");
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                if (i % 2 == 1) {
                    regex.append(part.equals("%") ? ".*" : ".");
                } else {
                    for (int k = 0; k < part.length(); k++) {
                        char c = part.charAt(k);
                        if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) {
                            regex.append('\\');
                        }
                        regex.append(c);
                    }
                }
            }
            regex.append('$');
            String re = regex.toString();
            test = v -> call("matches", v.get(0), new CString(re));
        }
        Tx result = strictTest(List.of(operand), test);
        return l.negated() ? not(result) : result;
    }

    private static ValueSpecification textTest(String fn, ValueSpecification value, String text,
            boolean caseInsensitive) {
        return caseInsensitive ? call(fn, call("toLower", value), call("toLower", new CString(text)))
                : call(fn, value, new CString(text));
    }

    /** {@code CASE}: the first WHEN that is TRUE gives the result (a NULL WHEN is not TRUE); no ELSE is NULL. */
    private Tx caseExpr(SqlAst.Case c, Scope scope, String row) {
        SqlAst.Expr otherwise = c.otherwise();
        Tx last = otherwise == null ? Tx.value(empty(), Kind.NULL, true) : tx(otherwise, scope, row);
        List<SqlAst.When> whens = c.whens();
        List<ValueSpecification> tests = new ArrayList<>();
        List<Tx> thens = new ArrayList<>();
        for (int i = whens.size() - 1; i >= 0; i--) {
            SqlAst.When w = whens.get(i);
            SqlAst.Expr operand = c.operand();
            if (operand != null) {
                List<Tx> sides = comparable(operand, w.condition(), scope, row, w.condition().pos());
                tests.add(0, strictTest(sides, v -> call("equal", v.get(0), v.get(1))).vs());
            } else {
                tests.add(0, condition(w.condition(), scope, "CASE/WHEN"));
            }
            thens.add(0, tx(w.result(), scope, row));
        }
        // the results as one type, the ELSE's last
        List<SqlAst.Expr> exprs = new ArrayList<>();
        whens.forEach(w -> exprs.add(w.result()));
        exprs.add(otherwise != null ? otherwise : new SqlAst.NullLit(c.pos()));
        List<Tx> all = new ArrayList<>(thens);
        all.add(last);
        Common results = common("CASE", exprs, all, c.pos());
        List<Tx> values = results.values();
        Tx otherwiseValue = values.get(values.size() - 1);
        boolean nullable = otherwiseValue.nullable();
        ValueSpecification vs = valueOf(otherwiseValue);
        for (int i = values.size() - 2; i >= 0; i--) {
            Tx v = values.get(i);
            nullable |= v.nullable();
            vs = call("if", tests.get(i), thunk(valueOf(v)), thunk(vs));
        }
        Kind kind = results.kind();
        if (kind == Kind.NULL) {
            // every result a NULL: Postgres resolves the CASE as text (ceil of it is Postgres's error)
            return nullOf(Kind.NULL);
        }
        return kind == Kind.BOOLEAN ? booleanValue(vs, nullable) : Tx.value(vs, kind, nullable);
    }

    private Tx function(SqlAst.FunctionCall f, Scope scope, String row) {
        if (isAggregate(f)) {
            String clause = scope.noAggregatesIn();
            throw SqlTranslationException.invalid(clause != null ? "aggregate functions are not allowed in " + clause
                    : "aggregate function calls cannot be nested", f.pos());
        }
        if (f.name().size() != 1) {
            throw SqlTranslationException.unsupported("schema-qualified function names are not supported yet",
                    f.pos());
        }
        String fn = functionName(f.name().get(0));
        if (WINDOW_FUNCTIONS.contains(fn)) {
            throw SqlTranslationException.invalid("window function " + fn + " requires an OVER clause", f.pos());
        }
        if (f.distinct() || f.star()) {
            throw SqlTranslationException.invalid(fn + " is not an aggregate", f.pos());
        }
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.unsupported("WITHIN GROUP is not supported yet", f.pos());
        }
        if ((f.filter() != null || !f.orderBy().isEmpty()) && FUNCTIONS.containsKey(fn)) {
            throw SqlTranslationException.invalid((f.filter() != null ? "FILTER" : "ORDER BY") + " specified, but "
                    + fn + " is not an aggregate function", f.pos());
        }
        List<Tx> args = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            args.add(tx(a, scope, row));
        }
        Translation translation = FUNCTIONS.get(fn);
        if (translation == null) {
            throw SqlTranslationException.unsupported("function " + fn + "(...) is not supported yet", f.pos());
        }
        return translation.apply(this, f, args, scope, row);
    }

    /** A Postgres function's translation: from its call, its arguments' translations, and the scope it is in. */
    @FunctionalInterface
    private interface Translation {
        Tx apply(SqlToPure t, SqlAst.FunctionCall f, List<Tx> args, Scope scope, String row);
    }

    /** The catalog: Postgres's scalar functions this step reads, by their names in Postgres, each with its
     *  translation (a name it does not hold is refused as not supported yet). */
    private static final Map<String, Translation> FUNCTIONS = functions();

    private static Map<String, Translation> functions() {
        Map<String, Translation> m = new HashMap<>();
        m.put("upper", (t, f, a, s, r) -> text1(f, a, "toUpper", Kind.STRING));
        m.put("lower", (t, f, a, s, r) -> text1(f, a, "toLower", Kind.STRING));
        for (String length : List.of("length", "char_length", "character_length")) {
            m.put(length, (t, f, a, s, r) -> text1(f, a, "length", Kind.INTEGER));
        }
        // the spaces at an end (Postgres's default characters to trim, named or not): Pure's trims; other characters
        // to trim are not supported yet
        String[][] trims = {{"trim", "trim"}, {"btrim", "trim"}, {"ltrim", "ltrim"}, {"rtrim", "rtrim"}};
        for (String[] trim : trims) {
            String pure = trim[1];
            m.put(trim[0], (t, f, a, s, r) -> {
                if (a.size() == 2) {
                    if (!(f.args().get(1) instanceof SqlAst.StringLit chars && chars.value().equals(" "))) {
                        throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + " with characters to"
                                + " trim other than ' ' is not supported yet", f.args().get(1).pos());
                    }
                    return text1(f, a.subList(0, 1), pure, Kind.STRING);
                }
                return text1(f, a, pure, Kind.STRING);
            });
        }
        m.put("reverse", (t, f, a, s, r) -> text1(f, a, "reverseString", Kind.STRING));
        m.put("ascii", (t, f, a, s, r) -> text1(f, a, "ascii", Kind.INTEGER));
        m.put("abs", (t, f, a, s, r) -> numeric1(f, a, "abs"));
        m.put("ceil", (t, f, a, s, r) -> numeric1(f, a, "ceiling"));
        m.put("ceiling", (t, f, a, s, r) -> numeric1(f, a, "ceiling"));
        m.put("floor", (t, f, a, s, r) -> numeric1(f, a, "floor"));
        m.put("coalesce", (t, f, a, s, r) -> coalesce(f, a));
        m.put("current_date", (t, f, a, s, r) -> nullary(f, a, "today", Kind.DATE));
        for (String now : List.of("current_timestamp", "now", "localtimestamp")) {
            m.put(now, (t, f, a, s, r) -> nullary(f, a, "now", Kind.DATETIME));
        }
        // Postgres's functions of a double precision and Pure's (Postgres's log is base 10; log(b, x) below)
        String[][] floats = {{"sqrt", "sqrt"}, {"exp", "exp"}, {"ln", "log"}, {"log", "log10"}, {"cbrt", "cbrt"},
                {"sin", "sin"}, {"cos", "cos"}, {"tan", "tan"}, {"asin", "asin"}, {"acos", "acos"}, {"atan", "atan"},
                {"cot", "cot"}, {"sinh", "sinh"}, {"cosh", "cosh"}, {"tanh", "tanh"}, {"degrees", "toDegrees"},
                {"radians", "toRadians"}};
        for (String[] fl : floats) {
            String pure = fl[1];
            // sqrt, exp, ln and log have numeric forms in Postgres, which a numeric reads (an integer reads the
            // double precision one)
            boolean numericForm = Set.of("sqrt", "exp", "ln", "log").contains(fl[0]);
            m.put(fl[0], (t, f, a, s, r) -> {
                arity(f, a, 1);
                numericOperand(a.get(0), f.args().get(0));
                Kind k = a.get(0).kind();
                return strict(k == Kind.INEXACT || numericForm && (exactNumeric(k) || k == Kind.WHOLE) ? Kind.INEXACT
                        : Kind.FLOAT, a,
                        v -> call(pure, v.get(0)));
            });
        }
        Translation log10 = java.util.Objects.requireNonNull(m.get("log"));
        m.put("log", (t, f, a, s, r) -> a.size() == 2 ? logBase(f, a) : log10.apply(t, f, a, s, r));
        m.put("atan2", (t, f, a, s, r) -> {
            arity(f, a, 2);
            numericOperand(a.get(0), f.args().get(0));
            numericOperand(a.get(1), f.args().get(1));
            return strict(a.get(0).kind() == Kind.INEXACT || a.get(1).kind() == Kind.INEXACT ? Kind.INEXACT
                    : Kind.FLOAT, a, v -> call("atan2", v.get(0), v.get(1)));
        });
        m.put("pi", (t, f, a, s, r) -> nullary(f, a, "pi", Kind.FLOAT));
        m.put("sign", (t, f, a, s, r) -> {
            arity(f, a, 1);
            numericOperand(a.get(0), f.args().get(0));
            inexact(a.get(0), "sign", f.pos());
            return a.get(0).kind() == Kind.INTEGER ? strict(Kind.FLOAT, a, v -> call("toFloat", call("sign", v.get(0))))
                    : wholeNumber(a.get(0), v -> call("sign", v));
        });
        m.put("mod", (t, f, a, s, r) -> {
            arity(f, a, 2);
            numericOperand(a.get(0), f.args().get(0));
            numericOperand(a.get(1), f.args().get(1));
            return remainder(a.get(0), a.get(1), "function mod(" + postgresName(a.get(0).kind()) + ", "
                    + postgresName(a.get(1).kind()) + ") does not exist", "mod(...)", f.pos());
        });
        m.put("log10", (t, f, a, s, r) -> log10.apply(t, f, a, s, r));
        m.put("round", (t, f, a, s, r) -> round(f, a));
        m.put("div", (t, f, a, s, r) -> div(f, a));
        m.put("width_bucket", (t, f, a, s, r) -> widthBucket(f, a));
        // jsonb (a Variant, register S49): json's functions read it as jsonb's do
        for (String family : List.of("json", "jsonb")) {
            // an array's length is refused: Pure's toMany(@Variant) drops an array's null elements on DuckDB, and
            // reads a value that is not an array (Postgres's error) as an empty list on Postgres
            m.put(family + "_array_length", (t, f, a, s, r) -> {
                throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + "(...) is not supported"
                        + " yet", f.pos());
            });
            m.put(family + "_extract_path", (t, f, a, s, r) -> extractPath(f, a, false));
            m.put(family + "_extract_path_text", (t, f, a, s, r) -> extractPath(f, a, true));
            m.put("to_" + family, (t, f, a, s, r) -> toJson(f, a));
        }
        m.put("nullif", (t, f, a, s, r) -> t.nullif(f, s, r));
        m.put("concat", (t, f, a, s, r) -> concat(f, a));
        m.put("concat_ws", (t, f, a, s, r) -> concatWs(f, a));
        m.put("format", (t, f, a, s, r) -> format(f, a));
        m.put("overlay", (t, f, a, s, r) -> overlay(f, a));
        m.put("quote_literal", (t, f, a, s, r) -> quoteLiteral(f, a, false));
        m.put("quote_nullable", (t, f, a, s, r) -> quoteLiteral(f, a, true));
        m.put("translate", (t, f, a, s, r) -> translate(f, a));
        m.put("split_part", (t, f, a, s, r) -> splitPart(f, a));
        m.put("greatest", (t, f, a, s, r) -> extreme(f, a, true));
        m.put("least", (t, f, a, s, r) -> extreme(f, a, false));
        m.put("num_nulls", (t, f, a, s, r) -> countNulls(f, a, true));
        m.put("num_nonnulls", (t, f, a, s, r) -> countNulls(f, a, false));
        m.put("md5", (t, f, a, s, r) -> {
            kinds(f, a, Kind.STRING);
            return strict(Kind.STRING, a, v -> call("hash", v.get(0), new EnumValue(HASH_TYPE, "MD5")));
        });
        m.put("chr", (t, f, a, s, r) -> {
            kinds(f, a, Kind.INTEGER);
            return strict(Kind.STRING, a, v -> call("char", v.get(0)));
        });
        for (String side : List.of("left", "right")) {
            m.put(side, (t, f, a, s, r) -> {
                kinds(f, a, Kind.STRING, Kind.INTEGER);
                // a negative count leaves that many off the other end, in Postgres and in Pure's relational left and
                // right alike (measured on DuckDB and Postgres, 2026-10-12)
                return strict(Kind.STRING, a, v -> call(side, v.get(0), v.get(1)));
            });
        }
        for (String pad : List.of("lpad", "rpad")) {
            m.put(pad, (t, f, a, s, r) -> {
                if (a.size() == 3) {
                    kinds(f, a, Kind.STRING, Kind.INTEGER, Kind.STRING);
                } else {
                    kinds(f, a, Kind.STRING, Kind.INTEGER);
                }
                nonNegative(f, 1);
                return strict(Kind.STRING, a, v -> call(pad, v));
            });
        }
        m.put("replace", (t, f, a, s, r) -> {
            kinds(f, a, Kind.STRING, Kind.STRING, Kind.STRING);
            return strict(Kind.STRING, a, v -> call("replace", v));
        });
        m.put("repeat", (t, f, a, s, r) -> {
            kinds(f, a, Kind.STRING, Kind.INTEGER);
            nonNegative(f, 1);
            return strict(Kind.STRING, a, v -> call("repeatString", v));
        });
        for (String position : List.of("strpos", "position")) {
            m.put(position, (t, f, a, s, r) -> {
                // the RELATIONAL indexOf is strpos: 1-based, 0 when not found, 1 for '' -- legend-engine's relational
                // lowering (locate()), the acceptance surface; platform Pure's is 0-based, a divergence PCT ledgers
                // (Test_LegendLite_EssentialFunctions_PCT), and the parity corpus pins the relational one here
                kinds(f, a, Kind.STRING, Kind.STRING);
                return strict(Kind.INTEGER, a, v -> call("indexOf", v.get(0), v.get(1)));
            });
        }
        for (String sub : List.of("substring", "substr")) {
            m.put(sub, (t, f, a, s, r) -> substring(f, a));
        }
        m.put("trunc", (t, f, a, s, r) -> trunc(f, a));
        for (String power : List.of("power", "pow")) {
            m.put(power, (t, f, a, s, r) -> {
                arity(f, a, 2);
                List<Tx> lr = typedOperands(a.get(0), a.get(1), ",", f.pos());
                numericOperand(lr.get(0), f.args().get(0));
                numericOperand(lr.get(1), f.args().get(1));
                // power of a numeric is numeric in Postgres, exact; of integers and doubles, double precision
                if (exactNumeric(lr.get(0).kind()) || exactNumeric(lr.get(1).kind())) {
                    return t.numericPower(f.args().get(0), f.args().get(1), lr, functionName(f.name().get(0))
                            + "(...)", f.pos(), s, r);
                }
                return strict(powerKind(lr.get(0).kind(), lr.get(1).kind()), lr, v -> call("pow", v.get(0),
                        v.get(1)));
            });
        }
        m.put("make_date", (t, f, a, s, r) -> {
            kinds(f, a, Kind.INTEGER, Kind.INTEGER, Kind.INTEGER);
            return strict(Kind.DATE, a, v -> call("date", v.get(0), v.get(1), v.get(2)));
        });
        m.put("make_timestamp", (t, f, a, s, r) -> {
            arity(f, a, 6);
            for (int i = 0; i < 6; i++) {
                if (i < 5 ? a.get(i).kind() != Kind.INTEGER && a.get(i).kind() != Kind.NULL
                        : !a.get(i).kind().numeric() && a.get(i).kind() != Kind.NULL) {
                    throw SqlTranslationException.unsupported("make_timestamp of other than five integers and a"
                            + " number of seconds is not supported yet", f.args().get(i).pos());
                }
            }
            return strict(Kind.DATETIME, a, v -> call("date", v.get(0), v.get(1), v.get(2), v.get(3), v.get(4),
                    v.get(5)));
        });
        m.put("date_trunc", (t, f, a, s, r) -> dateTrunc(f, a));
        m.put("isfinite", (t, f, a, s, r) -> {
            arity(f, a, 1);
            if (isNullConstant(a.get(0))) {
                throw SqlTranslationException.invalid("function isfinite(unknown) is not unique", f.pos());
            }
            if (!isDate(a.get(0).kind())) {
                throw SqlTranslationException.invalid("function isfinite(" + postgresName(a.get(0).kind()) + ") does"
                        + " not exist", f.pos());
            }
            // no value here is infinite (Postgres's infinity constants are refused)
            return strictTest(a, v -> new CBoolean(true));
        });
        m.put("starts_with", (t, f, a, s, r) -> {
            kinds(f, a, Kind.STRING, Kind.STRING);
            return strictTest(a, v -> call("startsWith", v.get(0), v.get(1)));
        });
        m.put("extract", (t, f, a, s, r) -> datePart(f, a, true));
        m.put("date_part", (t, f, a, s, r) -> datePart(f, a, false));
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Tx text1(SqlAst.FunctionCall f, List<Tx> args, String pure, Kind result) {
        arity(f, args, 1);
        if (args.get(0).kind() == Kind.NULL) {
            // Postgres reads the NULL as text: the function's NULL
            return nullOf(result);
        }
        if (args.get(0).kind() != Kind.STRING) {
            throw SqlTranslationException.invalid("function " + functionName(f.name().get(0)) + " needs a text"
                    + " value", f.pos());
        }
        return strict(result, args, v -> call(pure, v.get(0)));
    }

    private static Tx numeric1(SqlAst.FunctionCall f, List<Tx> args, String pure) {
        arity(f, args, 1);
        numericOperand(args.get(0), f.args().get(0));
        if (args.get(0).kind() == Kind.NULL) {
            return nullOf(Kind.FLOAT);
        }
        if (pure.equals("abs")) {
            return strict(args.get(0).kind(), args, v -> call(pure, v.get(0)));
        }
        inexact(args.get(0), functionName(f.name().get(0)), f.pos());
        return wholeNumber(args.get(0), v -> call(pure, v));
    }

    /** A whole number Postgres computes in its argument's type, as ceil, floor, sign and round(x) do: numeric for a
     *  numeric, double precision for a double -- and for an integer, which Postgres reads as a double (the preferred
     *  numeric type). Its kind is that type, so that what reads it next (a division above all) reads what Postgres
     *  does: {@code ceil(x) / 4} divides exactly. A double is converted (toFloat); a numeric's whole number stays
     *  Pure's Integer, an exact number either way (a toDecimal of it is a 38-digit decimal of scale 0, which DuckDB's
     *  CASE then imposes on a branch's decimals). */
    private static Tx wholeNumber(Tx x, java.util.function.UnaryOperator<ValueSpecification> pure) {
        if (x.kind() == Kind.NULL) {
            return nullOf(Kind.FLOAT);
        }
        if (x.kind() == Kind.INTEGER || x.kind() == Kind.FLOAT) {
            return strict(Kind.FLOAT, List.of(x), v -> call("toFloat", x.kind() == Kind.INTEGER ? v.get(0)
                    : pure.apply(v.get(0))));
        }
        return strict(Kind.DECIMAL, List.of(x), v -> pure.apply(v.get(0)));
    }

    private static Tx nullary(SqlAst.FunctionCall f, List<Tx> args, String pure, Kind result) {
        arity(f, args, 0);
        return Tx.value(call(pure), result, false);
    }

    /** {@code coalesce(a, b, ...)}: the first value that is not NULL. Pure's takes two to four values, so a longer
     *  list nests from the right. */
    private static Tx coalesce(SqlAst.FunctionCall f, List<Tx> arguments) {
        if (arguments.size() < 2) {
            throw SqlTranslationException.unsupported("coalesce takes two or more arguments here", f.pos());
        }
        Common common = common("COALESCE", f.args(), arguments, f.pos());
        List<Tx> args = common.values();
        Kind kind = common.kind();
        if (kind == Kind.NULL) {
            return nullOf(Kind.STRING);
        }
        // the arguments after the first that is never NULL are never read (Postgres evaluates none of them): it is
        // the last, so the result is one value where Pure types it (by its last argument)
        for (int i = 0; i < args.size() - 1; i++) {
            if (!args.get(i).nullable()) {
                args = args.subList(0, i + 1);
                break;
            }
        }
        if (args.size() == 1) {
            Tx only = args.get(0);
            if (kind == Kind.FLOAT && only.kind() != Kind.FLOAT && only.kind() != Kind.INEXACT) {
                // the double of an exact number (coalesce(0.28, x::float8) is the double 0.28)
                return strict(Kind.FLOAT, List.of(only), v -> call("toFloat", v.get(0)));
            }
            return kind == Kind.BOOLEAN || only.kind() == kind ? only : Tx.value(valueOf(only), kind, only.nullable());
        }
        boolean nullable = true;
        for (Tx a : args) {
            nullable &= a.nullable();
        }
        ValueSpecification tail = valueOf(args.get(args.size() - 1));
        int i = args.size() - 1;
        while (i > 0) {
            int take = Math.min(3, i);
            List<ValueSpecification> group = new ArrayList<>();
            for (int k = i - take; k < i; k++) {
                group.add(valueOf(args.get(k)));
            }
            group.add(tail);
            tail = call("coalesce", group);
            i -= take;
        }
        return kind == Kind.BOOLEAN ? booleanValue(tail, nullable) : Tx.value(tail, kind, nullable);
    }

    private static final String HASH_TYPE = "meta::pure::functions::hash::HashType";

    private static final Set<String> TIME_FIELDS = Set.of("hour", "hours", "minute", "minutes");

    /** Postgres's 4-byte integer's names: a numeric or text value it cannot hold is Postgres's error. */
    private static final Set<String> NARROW_INTEGERS = Set.of("integer", "int", "int4");

    /** Postgres's 2-byte integer's names. */
    private static final Set<String> SMALL_INTEGERS = Set.of("smallint", "int2");

    /** The names of text with a length ({@code varchar(n)}): an explicit cast cuts a longer value to n characters. */
    private static final Set<String> VARCHAR_TYPES = Set.of("varchar", "character varying");

    /** The types a cast reads, as Postgres names them, and their kinds: real
     *  (single precision), char(n) (padded), time zones, intervals, json and arrays are not among them yet. */
    private static final Map<String, Kind> CAST_TYPES = Map.ofEntries(Map.entry("integer", Kind.INTEGER),
            Map.entry("int", Kind.INTEGER), Map.entry("int4", Kind.INTEGER), Map.entry("bigint", Kind.INTEGER),
            Map.entry("smallint", Kind.INTEGER), Map.entry("int2", Kind.INTEGER),
            Map.entry("int8", Kind.INTEGER), Map.entry("numeric", Kind.DECIMAL), Map.entry("decimal", Kind.DECIMAL),
            Map.entry("double precision", Kind.FLOAT), Map.entry("float8", Kind.FLOAT), Map.entry("float", Kind.FLOAT),
            Map.entry("text", Kind.STRING), Map.entry("varchar", Kind.STRING),
            Map.entry("character varying", Kind.STRING), Map.entry("boolean", Kind.BOOLEAN),
            Map.entry("bool", Kind.BOOLEAN), Map.entry("date", Kind.DATE), Map.entry("timestamp", Kind.DATETIME),
            Map.entry("timestamp without time zone", Kind.DATETIME), Map.entry("json", Kind.JSON),
            Map.entry("jsonb", Kind.JSON));

    /** The date fields that are whole numbers, and Pure's functions for them; Postgres's seconds and epochs carry
     *  fractions. */
    private static final Map<String, String> DATE_FIELDS = Map.ofEntries(Map.entry("year", "year"),
            Map.entry("years", "year"), Map.entry("quarter", "quarterNumber"), Map.entry("month", "monthNumber"),
            Map.entry("months", "monthNumber"), Map.entry("day", "dayOfMonth"), Map.entry("days", "dayOfMonth"),
            Map.entry("doy", "dayOfYear"), Map.entry("isodow", "dayOfWeekNumber"), Map.entry("hour", "hour"),
            Map.entry("hours", "hour"), Map.entry("minute", "minute"), Map.entry("minutes", "minute"));

    /** The date fields computed from Pure's: Postgres's day of the week from Sunday (0), from ISO's (Sunday 7); its
     *  ISO week, as Pure's weekOfYear is (measured); a year's decade (year / 10), century ((year + 99) / 100) and
     *  millennium ((year + 999) / 1000), Postgres's for years from 1; a day's epoch, its seconds since 1970-01-01. */
    private static final Map<String, java.util.function.UnaryOperator<ValueSpecification>> COMPUTED_FIELDS =
            computedFields();

    private static Map<String, java.util.function.UnaryOperator<ValueSpecification>> computedFields() {
        Map<String, java.util.function.UnaryOperator<ValueSpecification>> m = new LinkedHashMap<>();
        m.put("dow", v -> call("rem", call("dayOfWeekNumber", v), new CInteger(7L)));
        for (String week : List.of("week", "weeks")) {
            m.put(week, v -> call("weekOfYear", v));
        }
        for (String decade : List.of("decade", "decades")) {
            m.put(decade, v -> yearsDivided(v, 0, 10));
        }
        for (String century : List.of("century", "centuries")) {
            m.put(century, v -> yearsDivided(v, 99, 100));
        }
        for (String millennium : List.of("millennium", "millennia")) {
            m.put(millennium, v -> yearsDivided(v, 999, 1000));
        }
        m.put("epoch", v -> call("toEpochValue", v));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** {@code (year + offset) / by}, truncated (a year here is at least 1): exact, the quotient's double far from the
     *  next whole number. */
    private static ValueSpecification yearsDivided(ValueSpecification v, long offset, long by) {
        ValueSpecification year = offset == 0 ? call("year", v) : AppliedFunction.infixRun("plus", List.of(
                call("year", v), new CInteger(offset)));
        return call("floor", call("divide", year, new CInteger(by)));
    }

    /** The arguments' kinds, as Postgres resolves the function: a NULL constant makes it NULL (not supported here); a
     *  value of another kind has no such function. */
    private static void kinds(SqlAst.FunctionCall f, List<Tx> args, Kind... expected) {
        arity(f, args, expected.length);
        for (int i = 0; i < expected.length; i++) {
            Kind k = args.get(i).kind();
            if (k == Kind.NULL) {
                // Postgres reads the NULL as the argument's type: strict makes the function NULL
                continue;
            }
            if (k != expected[i]) {
                throw SqlTranslationException.invalid("function " + functionName(f.name().get(0)) + " needs "
                        + expected[i].name().toLowerCase(Locale.ROOT) + " argument " + (i + 1) + ", not "
                        + k.name().toLowerCase(Locale.ROOT), f.args().get(i).pos());
            }
        }
    }

    /** Argument {@code i} as an integer constant of at least 0: a negative one means something else in Postgres. */
    private static void nonNegative(SqlAst.FunctionCall f, int i) {
        constantAtLeast(f, i, 0);
    }

    private static void constantAtLeast(SqlAst.FunctionCall f, int i, int min) {
        if (!(f.args().get(i) instanceof SqlAst.IntegerLit n) || n.value().compareTo(
                java.math.BigInteger.valueOf(min)) < 0) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + "(...) with argument " + (i + 1)
                    + " other than an integer constant of at least " + min + " is not supported yet",
                    f.args().get(i).pos());
        }
    }

    /** {@code round(x)} (and {@code round(x, 0)}) of an exact number: Postgres's numeric round, half away from
     *  zero. Pure's round is half to even, so this is {@link #halfAwayFromZero}. A double precision rounds half to
     *  even in Postgres; {@code round(x, n)} for another n has no Pure spelling here yet: both refused. */
    /** {@code substring(s, start [, length])}, {@code substr}: the characters from {@code start} (1-based) on, {@code
     *  length} of them -- Pure's relational substring, SQL's substring(start, length). A start before 1 still counts
     *  its places in Postgres ({@code substring('hello', 0, 3)} is {@code 'he'}): constants are moved to start at 1.
     *  A negative length is Postgres's error. */
    private static Tx substring(SqlAst.FunctionCall f, List<Tx> a) {
        if (a.size() == 2 && f.args().get(1) instanceof SqlAst.StringLit) {
            throw SqlTranslationException.unsupported("substring(... FROM pattern) (a regular expression) is not"
                    + " supported yet", f.pos());
        }
        if (a.size() == 2) {
            kinds(f, a, Kind.STRING, Kind.INTEGER);
            return strict(Kind.STRING, a, v -> call("substring", v.get(0), v.get(1)));
        }
        kinds(f, a, Kind.STRING, Kind.INTEGER, Kind.INTEGER);
        for (Tx x : a) {
            if (isNullConstant(x)) {
                return nullOf(Kind.STRING);
            }
        }
        Long start = constantInteger(f.args().get(1));
        Long length = constantInteger(f.args().get(2));
        if (start == null || length == null) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + " with a start or a length other"
                    + " than a constant is not supported yet", f.pos());
        }
        if (length < 0) {
            throw SqlTranslationException.invalid("negative substring length not allowed", f.args().get(2).pos());
        }
        long from = Math.max(start, 1);
        long count = Math.max(start + length - from, 0);
        return strict(Kind.STRING, List.of(a.get(0)), v -> call("substring", v.get(0),
                integer(java.math.BigInteger.valueOf(from)), integer(java.math.BigInteger.valueOf(count))));
    }

    /** An integer constant ({@code 3}, {@code -3}), or null. */
    private static @com.legend.base.Nullable Long constantInteger(SqlAst.Expr e) {
        java.math.BigInteger k = e instanceof SqlAst.IntegerLit n ? n.value()
                : e instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.MINUS
                        && u.operand() instanceof SqlAst.IntegerLit n ? n.value().negate() : null;
        return k == null || k.bitLength() >= 63 ? null : k.longValue();
    }

    /** {@code x} to {@code places} decimal places (negative: to tens, hundreds...), exactly: the scaled value rounded
     *  half away from zero (Postgres's numeric round) or truncated (trunc), then scaled back by a decimal constant --
     *  decimal multiplication, exact on every database (a divide would go through a double). */
    private static ValueSpecification toPlaces(ValueSpecification x, long places, boolean halfAway) {
        BigDecimal up = BigDecimal.ONE.scaleByPowerOfTen((int) places);
        ValueSpecification scaled = places == 0 ? x : places > 0
                ? AppliedFunction.infixRun("times", List.of(x, integer(up.toBigIntegerExact())))
                : AppliedFunction.infixRun("times", List.of(x, new CDecimal(up)));
        ValueSpecification whole = halfAway ? halfAwayFromZero(scaled) : towardZero(scaled);
        BigDecimal down = BigDecimal.ONE.scaleByPowerOfTen((int) -places);
        return places == 0 ? whole : places > 0
                ? AppliedFunction.infixRun("times", List.of(whole, new CDecimal(down)))
                : AppliedFunction.infixRun("times", List.of(whole, integer(down.toBigIntegerExact())));
    }

    /** The whole number toward zero. */
    private static ValueSpecification towardZero(ValueSpecification x) {
        return call("if", call("greaterThanEqual", x, new CInteger(0L)), thunk(call("floor", x)),
                thunk(call("ceiling", x)));
    }

    /** {@code trunc(x [, places])}: toward zero -- numeric for a numeric (exact), double precision otherwise. */
    private static Tx trunc(SqlAst.FunctionCall f, List<Tx> args) {
        if (args.size() != 2) {
            arity(f, args, 1);
        }
        Tx x = args.get(0);
        numericOperand(x, f.args().get(0));
        if (args.size() == 1) {
            inexact(x, "trunc(x)", f.pos());
            if (x.kind() == Kind.NULL) {
                // trunc has numeric, double precision and macaddr forms: a NULL is no one of them
                throw SqlTranslationException.invalid("function trunc(unknown) is not unique", f.pos());
            }
            return wholeNumber(x, SqlToPure::towardZero);
        }
        return places(f, args, false);
    }

    /** round(x, n) or trunc(x, n): a numeric (an integer read as one) to a constant number of places, exactly. */
    private static Tx places(SqlAst.FunctionCall f, List<Tx> args, boolean halfAway) {
        Tx x = args.get(0);
        String fn = functionName(f.name().get(0));
        if (isNullConstant(x) || isNullConstant(args.get(1))) {
            return nullOf(Kind.DECIMAL);
        }
        if (x.kind() == Kind.FLOAT) {
            throw SqlTranslationException.invalid("function " + fn + "(double precision, "
                    + postgresName(args.get(1).kind()) + ") does not exist", f.pos());
        }
        inexact(x, fn + "(x, n)", f.pos());
        Long n = constantInteger(f.args().get(1));
        if (n == null || Math.abs(n) > 18) {
            throw SqlTranslationException.unsupported(fn + "(x, n) with n other than a constant (of at most 18) is not"
                    + " supported yet", f.args().get(1).pos());
        }
        long places = n;
        // an integer is read as Postgres's numeric first: n places of it (round(42, 1) is 42.0), and no overflow of
        // its own width in the scaling
        boolean whole = x.kind() == Kind.INTEGER || x.kind() == Kind.WHOLE;
        return strict(Kind.DECIMAL, List.of(x), v -> toPlaces(whole ? call("toDecimal", v.get(0)) : v.get(0), places,
                halfAway));
    }

    /** Refuses what rounds or spells an inexact numeric (see {@link Kind}): it may fall either side of Postgres's
     *  exact value, and a rounding or a spelling of it then differs from Postgres's. */
    private static void inexact(Tx x, String what, SqlAst.Pos pos) {
        if (x.kind() == Kind.INEXACT) {
            throw inexactRefused(what, pos);
        }
    }

    private static SqlTranslationException inexactRefused(String what, SqlAst.Pos pos) {
        return SqlTranslationException.unsupported(what + " of a quotient, average or root of numerics (exact in"
                + " Postgres, a double here) is not supported yet", pos);
    }

    private static Tx round(SqlAst.FunctionCall f, List<Tx> args) {
        if (args.size() == 2) {
            numericOperand(args.get(0), f.args().get(0));
            // Postgres's numeric round: half away from zero (Pure's round(Decimal, n) breaks ties to even)
            return places(f, args, true);
        }
        arity(f, args, 1);
        Tx x = args.get(0);
        numericOperand(x, f.args().get(0));
        inexact(x, "round(x)", f.pos());
        if (x.kind() == Kind.FLOAT) {
            // Postgres's round of a double precision is rint, half to even, as Pure's round of a Float is (measured)
            return strict(Kind.FLOAT, List.of(x), v -> call("toFloat", call("round", v.get(0))));
        }
        if (x.kind() == Kind.INTEGER) {
            // round(int) is round(double precision) in Postgres: the value itself, a double
            return strict(Kind.FLOAT, List.of(x), v -> call("toFloat", v.get(0)));
        }
        return wholeNumber(x, SqlToPure::halfAwayFromZero);
    }

    /** The nearest integer, a tie away from zero (Postgres's numeric rounding; Pure's round breaks ties to even). */
    private static ValueSpecification halfAwayFromZero(ValueSpecification x) {
        return call("if", call("greaterThanEqual", x, new CInteger(0L)),
                thunk(call("floor", AppliedFunction.infixRun("plus", List.of(x, new CDecimal(new BigDecimal("0.5")))))),
                thunk(call("ceiling", AppliedFunction.infixRun("minus",
                        List.of(x, new CDecimal(new BigDecimal("0.5")))))));
    }

    /** {@code nullif(a, b)}: NULL when {@code a = b} is TRUE, else {@code a}. */
    private Tx nullif(SqlAst.FunctionCall f, Scope scope, String row) {
        if (f.args().size() != 2) {
            throw SqlTranslationException.invalid("function nullif takes two arguments", f.pos());
        }
        List<Tx> sides = comparable(f.args().get(0), f.args().get(1), scope, row, f.pos());
        Tx a = sides.get(0);
        Tx equal = compare(SqlAst.BinaryOp.EQ, a, sides.get(1));
        ValueSpecification v = call("if", equal.vs(), thunk(empty()), thunk(valueOf(a)));
        return a.kind() == Kind.BOOLEAN ? booleanValue(v, true) : Tx.value(v, a.kind(), true);
    }

    /** {@code concat(a, b, ...)}: the values end to end, a NULL as nothing; never NULL. Text values only so far
     *  (Postgres writes any other value by its type's output rules). */
    private static Tx concat(SqlAst.FunctionCall f, List<Tx> args) {
        if (args.isEmpty()) {
            throw SqlTranslationException.invalid("function concat needs an argument", f.pos());
        }
        List<ValueSpecification> parts = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Tx a = args.get(i);
            if (a.kind() == Kind.NULL) {
                continue;
            }
            ValueSpecification v = asText(a, f.args().get(i).pos(), "concat(...)");
            parts.add(a.nullable() ? call("if", call("isEmpty", v), thunk(new CString("")), thunk(call("toOne", v)))
                    : v);
        }
        ValueSpecification all = parts.isEmpty() ? new CString("") : parts.size() == 1 ? parts.get(0)
                : AppliedFunction.infixRun("plus", parts);
        return Tx.value(all, Kind.STRING, false);
    }

    /** A value as concat writes it, by Postgres's output function: an integer's digits, t or f, a date's ISO spelling,
     *  a numeric's digits with its scale -- Pure's toString writes each so on every database (judged), the boolean
     *  spelled here. A double's text (Postgres's shortest that reads back), a timestamp's and others are not yet. */
    private static ValueSpecification asText(Tx a, SqlAst.Pos pos, String where) {
        return switch (a.kind()) {
            case STRING -> valueOf(a);
            // concat writes a boolean with its output function, t or f (|| casts it: true, false)
            case BOOLEAN -> {
                ValueSpecification v = valueOf(a);
                ValueSpecification tf = call("if", a.nullable() ? call("toOne", v) : v, thunk(new CString("t")),
                        thunk(new CString("f")));
                yield a.nullable() ? call("if", call("isEmpty", v), thunk(empty()), thunk(tf)) : tf;
            }
            case INTEGER, WHOLE, DATE, DECIMAL -> {
                ValueSpecification v = valueOf(a);
                yield a.nullable() ? call("if", call("isEmpty", v), thunk(empty()), thunk(call("toString",
                        call("toOne", v)))) : call("toString", v);
            }
            case INEXACT -> throw inexactRefused(where, pos);
            default -> throw SqlTranslationException.unsupported(where + " of " + postgresName(a.kind())
                    + " values is not supported yet", pos);
        };
    }

    /** {@code concat_ws(sep, a, b, ...)}: the values that are not NULL, each written as concat writes it, with {@code
     *  sep} between them; NULL only where {@code sep} is. Each value present is written after a separator, and the
     *  first separator taken off (after a first value that is never NULL, none is). */
    private static Tx concatWs(SqlAst.FunctionCall f, List<Tx> args) {
        if (args.size() < 2) {
            throw SqlTranslationException.invalid("function concat_ws needs a separator and a value", f.pos());
        }
        if (args.get(0).kind() != Kind.STRING && args.get(0).kind() != Kind.NULL) {
            throw SqlTranslationException.unsupported("concat_ws(...) with a separator other than text is not"
                    + " supported yet", f.args().get(0).pos());
        }
        List<ValueSpecification> texts = new ArrayList<>();
        List<Boolean> nullable = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            Tx a = args.get(i);
            if (isNullConstant(a)) {
                continue;
            }
            texts.add(asText(a, f.args().get(i).pos(), "concat_ws(...)"));
            nullable.add(a.nullable());
        }
        boolean leading = !texts.isEmpty() && !nullable.get(0);
        return strict(Kind.STRING, List.of(args.get(0)), v -> {
            ValueSpecification sep = v.get(0);
            List<ValueSpecification> parts = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                ValueSpecification text = texts.get(i);
                if (i == 0 && leading) {
                    parts.add(text);
                } else if (nullable.get(i)) {
                    parts.add(call("if", call("isEmpty", text), thunk(new CString("")), thunk(
                            AppliedFunction.infixRun("plus", List.of(sep, call("toOne", text))))));
                } else {
                    parts.add(AppliedFunction.infixRun("plus", List.of(sep, text)));
                }
            }
            if (parts.isEmpty()) {
                return new CString("");
            }
            ValueSpecification all = parts.size() == 1 ? parts.get(0) : AppliedFunction.infixRun("plus", parts);
            return leading ? all : call("substring", all, AppliedFunction.infixRun("plus", List.of(call("length", sep),
                    new CInteger(1L))));
        });
    }

    /** {@code format(fmt, a, b, ...)} of a constant format: its text, each {@code %s} the next value written as concat
     *  writes it (a NULL as nothing), {@code %%} a percent sign. {@code %I}, {@code %L}, positions and widths are not
     *  supported yet. */
    private static Tx format(SqlAst.FunctionCall f, List<Tx> args) {
        if (args.isEmpty()) {
            throw SqlTranslationException.invalid("function format() needs a format", f.pos());
        }
        if (isNullConstant(args.get(0))) {
            return nullOf(Kind.STRING);
        }
        if (!(f.args().get(0) instanceof SqlAst.StringLit fmt)) {
            throw SqlTranslationException.unsupported("format(...) of a format other than a constant is not supported"
                    + " yet", f.args().get(0).pos());
        }
        String text = fmt.value();
        List<ValueSpecification> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int next = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%') {
                literal.append(c);
                continue;
            }
            if (i + 1 == text.length()) {
                throw SqlTranslationException.invalid("unterminated format() type specifier", fmt.pos());
            }
            char spec = text.charAt(++i);
            if (spec == '%') {
                literal.append('%');
                continue;
            }
            if (spec != 's') {
                if (spec == 'I' || spec == 'L' || spec == '-' || spec == '*' || Character.isDigit(spec)) {
                    throw SqlTranslationException.unsupported("format(...) with %" + spec + " is not supported yet",
                            fmt.pos());
                }
                throw SqlTranslationException.invalid("unrecognized format() type specifier \"" + spec + "\"",
                        fmt.pos());
            }
            if (next >= args.size()) {
                throw SqlTranslationException.invalid("too few arguments for format()", f.pos());
            }
            Tx a = args.get(next);
            SqlAst.Pos pos = f.args().get(next).pos();
            next++;
            if (literal.length() > 0) {
                parts.add(new CString(literal.toString()));
                literal.setLength(0);
            }
            if (isNullConstant(a)) {
                continue;
            }
            ValueSpecification v = asText(a, pos, "format(...)");
            parts.add(a.nullable() ? call("if", call("isEmpty", v), thunk(new CString("")), thunk(call("toOne", v)))
                    : v);
        }
        if (literal.length() > 0 || parts.isEmpty()) {
            parts.add(new CString(literal.toString()));
        }
        return Tx.value(parts.size() == 1 ? parts.get(0) : AppliedFunction.infixRun("plus", parts), Kind.STRING,
                false);
    }

    /** {@code overlay(s PLACING r FROM n [FOR m])} of a constant n (and m): s with its m characters from the n-th
     *  (1-based) replaced by r, m being r's length when not given. Postgres's definition: {@code substring(s, 1, n - 1)
     *  || r || substring(s, n + m)}, the second from the first character when n + m is before it; n before 1 is
     *  Postgres's error. */
    private static Tx overlay(SqlAst.FunctionCall f, List<Tx> args) {
        if (args.size() == 4) {
            kinds(f, args, Kind.STRING, Kind.STRING, Kind.INTEGER, Kind.INTEGER);
        } else {
            kinds(f, args, Kind.STRING, Kind.STRING, Kind.INTEGER);
        }
        for (Tx a : args) {
            if (isNullConstant(a)) {
                return nullOf(Kind.STRING);
            }
        }
        Long n = constantInteger(f.args().get(2));
        Long m = args.size() == 4 ? constantInteger(f.args().get(3)) : null;
        if (n == null || args.size() == 4 && m == null || Math.abs(n) > Integer.MAX_VALUE
                || m != null && Math.abs(m) > Integer.MAX_VALUE) {
            throw SqlTranslationException.unsupported("overlay(...) with a start or a count other than a constant is"
                    + " not supported yet", f.pos());
        }
        if (n <= 0) {
            throw SqlTranslationException.invalid("negative substring length not allowed", f.args().get(2).pos());
        }
        return strict(Kind.STRING, args.subList(0, 2), v -> {
            ValueSpecification head = call("substring", v.get(0), new CInteger(1L), new CInteger(n - 1));
            ValueSpecification tail = m == null ? call("substring", v.get(0), AppliedFunction.infixRun("plus",
                    List.of(new CInteger(n), call("length", v.get(1))))) : call("substring", v.get(0),
                    new CInteger(Math.max(n + m, 1)));
            return AppliedFunction.infixRun("plus", List.of(head, v.get(1), tail));
        });
    }

    /** {@code quote_literal(x)}: x's text as a string constant -- in quotes, each quote doubled; with a backslash in
     *  it, Postgres's escape string ({@code E'...'}), each backslash doubled too. NULL for a NULL, which {@code
     *  quote_nullable} writes {@code NULL}. A value other than text is its text, as {@code x::text} writes it. */
    private static Tx quoteLiteral(SqlAst.FunctionCall f, List<Tx> args, boolean orNull) {
        arity(f, args, 1);
        Tx x = args.get(0);
        if (isNullConstant(x)) {
            return orNull ? Tx.value(new CString("NULL"), Kind.STRING, false) : nullOf(Kind.STRING);
        }
        if (x.kind() != Kind.STRING) {
            asText(x, f.args().get(0).pos(), functionName(f.name().get(0)) + "(...)");
        }
        Kind kind = x.kind();
        Tx quoted = strict(Kind.STRING, List.of(x), v -> {
            ValueSpecification text = kind == Kind.STRING ? v.get(0) : call("toString", v.get(0));
            ValueSpecification body = call("replace", call("replace", text, new CString("\\"), new CString("\\\\")),
                    new CString("'"), new CString("''"));
            return AppliedFunction.infixRun("plus", List.of(call("if", call("contains", text, new CString("\\")),
                    thunk(new CString("E'")), thunk(new CString("'"))), body, new CString("'")));
        });
        if (!orNull || !quoted.nullable()) {
            return quoted;
        }
        return Tx.value(call("if", call("isEmpty", quoted.vs()), thunk(new CString("NULL")), thunk(call("toOne",
                quoted.vs()))), Kind.STRING, false);
    }

    /** {@code translate(s, from, to)} of constant {@code from} and {@code to}: each character of s found in from (at
     *  its first place there) is the character at that place in to, or is taken out where to is shorter. Pure's
     *  replace, a character at a time, each replaced before any character that becomes it, so that no replacement is
     *  replaced again; characters that replace one another in a cycle ({@code translate(s, 'ab', 'ba')}) are not
     *  supported yet. */
    private static Tx translate(SqlAst.FunctionCall f, List<Tx> args) {
        kinds(f, args, Kind.STRING, Kind.STRING, Kind.STRING);
        for (Tx a : args) {
            if (isNullConstant(a)) {
                return nullOf(Kind.STRING);
            }
        }
        if (!(f.args().get(1) instanceof SqlAst.StringLit from) || !(f.args().get(2) instanceof SqlAst.StringLit to)) {
            throw SqlTranslationException.unsupported("translate(...) of characters other than constants is not"
                    + " supported yet", f.pos());
        }
        int[] fromChars = from.value().codePoints().toArray();
        int[] toChars = to.value().codePoints().toArray();
        Map<Integer, String> replacement = new LinkedHashMap<>();
        for (int i = 0; i < fromChars.length; i++) {
            replacement.putIfAbsent(fromChars[i], i < toChars.length ? character(toChars[i]) : "");
        }
        replacement.entrySet().removeIf(e -> e.getValue().equals(character(e.getKey())));
        List<Map.Entry<Integer, String>> order = new ArrayList<>();
        Set<Integer> pending = new java.util.LinkedHashSet<>(replacement.keySet());
        while (!pending.isEmpty()) {
            Map.Entry<Integer, String> ready = null;
            for (Map.Entry<Integer, String> e : replacement.entrySet()) {
                if (pending.contains(e.getKey()) && (e.getValue().isEmpty()
                        || !pending.contains(e.getValue().codePointAt(0)))) {
                    ready = e;
                    break;
                }
            }
            if (ready == null) {
                throw SqlTranslationException.unsupported("translate(...) whose characters replace one another in a"
                        + " cycle is not supported yet", f.args().get(1).pos());
            }
            order.add(ready);
            pending.remove(ready.getKey());
        }
        return strict(Kind.STRING, List.of(args.get(0)), v -> {
            ValueSpecification result = v.get(0);
            for (Map.Entry<Integer, String> e : order) {
                result = call("replace", result, new CString(character(e.getKey())),
                        new CString(e.getValue()));
            }
            return result;
        });
    }

    /** The one character {@code codePoint} as text ({@code Character.toString(int)} is not in TeaVM's class
     *  library, which compiles this package for the browser). */
    private static String character(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** {@code split_part(s, delimiter, n)} of a constant delimiter and a constant n from 1 to 5: the n-th field of s,
     *  fields separated by the delimiter ('' past the last). A field is the text before the delimiter's first place
     *  (Pure's relational indexOf, as strpos), the rest after it; an empty delimiter makes s one field. A field
     *  counted from the end (n below 0) is not supported yet; n = 0 is Postgres's error. */
    private static Tx splitPart(SqlAst.FunctionCall f, List<Tx> args) {
        kinds(f, args, Kind.STRING, Kind.STRING, Kind.INTEGER);
        for (Tx a : args) {
            if (isNullConstant(a)) {
                return nullOf(Kind.STRING);
            }
        }
        Long n = constantInteger(f.args().get(2));
        if (!(f.args().get(1) instanceof SqlAst.StringLit d) || n == null) {
            throw SqlTranslationException.unsupported("split_part(...) with a delimiter or a field other than a"
                    + " constant is not supported yet", f.pos());
        }
        if (n == 0) {
            throw SqlTranslationException.invalid("field position must not be zero", f.args().get(2).pos());
        }
        if (n < 0 || n > 5) {
            throw SqlTranslationException.unsupported("split_part(...) of a field other than the first to the fifth is"
                    + " not supported yet", f.args().get(2).pos());
        }
        String delimiter = d.value();
        return strict(Kind.STRING, List.of(args.get(0)), v -> delimiter.isEmpty() ? (n == 1 ? v.get(0)
                : new CString("")) : field(v.get(0), new CString(delimiter), n));
    }

    private static ValueSpecification field(ValueSpecification rest, CString delimiter, long n) {
        ValueSpecification at = call("indexOf", rest, delimiter);
        ValueSpecification none = call("equal", at, new CInteger(0L));
        if (n == 1) {
            return call("if", none, thunk(rest), thunk(call("substring", rest, new CInteger(1L),
                    AppliedFunction.infixRun("minus", List.of(at, new CInteger(1L))))));
        }
        // the delimiter's length in characters, as SQL's substring counts them (not UTF-16's units)
        ValueSpecification after = call("substring", rest, AppliedFunction.infixRun("plus", List.of(at,
                new CInteger(delimiter.value().codePointCount(0, delimiter.value().length())))));
        return call("if", none, thunk(new CString("")), thunk(field(after, delimiter, n - 1)));
    }

    /** A number constant ({@code 3}, {@code -2.5}), or null. */
    private static @com.legend.base.Nullable BigDecimal numberConstant(SqlAst.Expr e) {
        boolean negative = e instanceof SqlAst.Unary u && u.op() == SqlAst.UnaryOp.MINUS;
        SqlAst.Expr literal = e instanceof SqlAst.Unary u && negative ? u.operand() : e;
        BigDecimal v = literal instanceof SqlAst.IntegerLit n ? new BigDecimal(n.value())
                : literal instanceof SqlAst.NumericLit d ? d.value() : null;
        return v == null || !negative ? v : v.negate();
    }

    /** {@code a % b}, {@code mod(a, b)}: the remainder, with the dividend's sign -- Pure's rem, exact for integers and
     *  numerics alike (DuckDB's remainder of decimals too, measured), a numeric's scale the larger operand's in both. A
     *  double has none in Postgres. */
    private static Tx remainder(Tx l, Tx r, String noSuch, String what, SqlAst.Pos pos) {
        for (Tx x : List.of(l, r)) {
            if (x.kind() == Kind.FLOAT) {
                throw SqlTranslationException.invalid(noSuch, pos);
            }
            inexact(x, what, pos);
        }
        boolean integers = (l.kind() == Kind.INTEGER || l.kind() == Kind.WHOLE || l.kind() == Kind.NULL)
                && (r.kind() == Kind.INTEGER || r.kind() == Kind.WHOLE || r.kind() == Kind.NULL);
        Kind kind = !integers ? (l.kind() == Kind.NUMBER || r.kind() == Kind.NUMBER ? Kind.NUMBER : Kind.DECIMAL)
                : l.kind() == Kind.WHOLE || r.kind() == Kind.WHOLE ? Kind.WHOLE : Kind.INTEGER;
        return strict(kind, List.of(l, r), v -> call("rem", v.get(0), v.get(1)));
    }

    /** {@code div(a, b)}: the quotient truncated toward zero, Postgres's numeric of scale 0 (it has div of numerics
     *  only, an integer read as one). Exact, as integer division is ({@link #integerQuotient}): Pure's rem of decimals
     *  is exact on both databases, with the dividend's sign. */
    private static Tx div(SqlAst.FunctionCall f, List<Tx> args) {
        arity(f, args, 2);
        boolean decimal = false;
        for (int i = 0; i < 2; i++) {
            Tx x = args.get(i);
            numericOperand(x, f.args().get(i));
            if (x.kind() == Kind.FLOAT) {
                throw SqlTranslationException.invalid("function div(" + postgresName(args.get(0).kind()) + ", "
                        + postgresName(args.get(1).kind()) + ") does not exist", f.pos());
            }
            inexact(x, "div(...)", f.pos());
            decimal |= exactNumeric(x.kind());
        }
        boolean decimals = decimal;
        return strict(Kind.DECIMAL, args, v -> decimals ? integerQuotient(call("toDecimal", v.get(0)),
                call("toDecimal", v.get(1))) : integerQuotient(v.get(0), v.get(1)));
    }

    /** {@code log(b, x)}: the logarithm of x to the base b. Postgres has it for numerics only (exact there; an integer
     *  is read as one). Of the constant base 10 it is Pure's log10, as {@code log(x)} is (an inexact numeric, exact
     *  at 10's powers). Another base is refused: ln(x) / ln(b) misses the exact powers that compare equal in Postgres
     *  ({@code log(10, 1000)} by natural logarithms is 2.9999999999999996). */
    private static Tx logBase(SqlAst.FunctionCall f, List<Tx> args) {
        for (int i = 0; i < 2; i++) {
            numericOperand(args.get(i), f.args().get(i));
        }
        if (args.get(0).kind() == Kind.FLOAT || args.get(1).kind() == Kind.FLOAT) {
            throw SqlTranslationException.invalid("function log(" + postgresName(args.get(0).kind()) + ", "
                    + postgresName(args.get(1).kind()) + ") does not exist", f.pos());
        }
        SqlAst.Expr b = f.args().get(0);
        if (b instanceof SqlAst.Cast c && c.type().modifiers().isEmpty() && NUMERIC_TYPES.contains(c.type().name())) {
            b = c.operand();
        }
        BigDecimal base = numberConstant(b);
        if (base == null || base.compareTo(BigDecimal.TEN) != 0) {
            throw SqlTranslationException.unsupported("log(b, x) of a base other than the constant 10 is not supported"
                    + " yet: Postgres's is exact, and a quotient of natural logarithms misses the exact powers",
                    f.args().get(0).pos());
        }
        return strict(Kind.INEXACT, args.subList(1, 2), v -> call("log10", v.get(0)));
    }

    /** The names of Postgres's numeric type. */
    private static final Set<String> NUMERIC_TYPES = Set.of("numeric", "decimal");

    /** {@code power(x, n)} of numerics where x is an integer read as one ({@code x::numeric}, or an integer with a
     *  numeric exponent) and n a whole constant from 0 to 16: Postgres's numeric power, exact for a base of scale 0
     *  (it rounds to a scale no less than the base's). x times itself n times, in decimal arithmetic. Postgres writes
     *  it to 16 places or more, so its text is refused. A base with a fraction is not supported yet: Postgres rounds
     *  its power to about 16 significant digits. */
    private Tx numericPower(SqlAst.Expr base, SqlAst.Expr exponent, List<Tx> lr, String what, SqlAst.Pos pos,
            Scope scope, String row) {
        if (isNullConstant(lr.get(0)) || isNullConstant(lr.get(1))) {
            return nullOf(Kind.NUMBER);
        }
        // the integer itself: Pure's toDecimal of it is a decimal of scale 0 (of a decimal, of scale 18)
        Tx integer = lr.get(0).kind() == Kind.INTEGER ? lr.get(0) : base instanceof SqlAst.Cast c
                && c.type().modifiers().isEmpty() && NUMERIC_TYPES.contains(c.type().name()) ? tx(c.operand(), scope,
                row) : null;
        BigDecimal n = numberConstant(exponent);
        if (integer == null || integer.kind() != Kind.INTEGER || n == null || n.signum() < 0 || n.compareTo(BigDecimal.valueOf(16)) > 0
                || n.stripTrailingZeros().scale() > 0) {
            throw SqlTranslationException.unsupported(what + " of numeric values other than an integer to a whole"
                    + " power from 0 to 16 is not supported yet (Postgres rounds a numeric's power to about 16"
                    + " significant digits)", pos);
        }
        int times = n.intValue();
        return strict(Kind.NUMBER, List.of(integer), v -> {
            ValueSpecification x = call("toDecimal", v.get(0));
            return times == 0 ? new CDecimal(BigDecimal.ONE) : times == 1 ? x
                    : AppliedFunction.infixRun("times", java.util.Collections.nCopies(times, x));
        });
    }

    /** {@code width_bucket(x, low, high, count)} of constant bounds and a constant count up to 10,000: which of count
     *  equal buckets from low to high holds x (0 before them, count + 1 after). Postgres has it for doubles and for
     *  numerics, read by x's type (an integer with integer bounds reads the double one):
     *  - doubles: Postgres's own steps, {@code count * ((x - low) / (high - low))} truncated, at most count - 1, plus
     *    1 -- the same double operations on every database, the bounds the doubles nearest their text;
     *  - numerics: Postgres divides (x - low) * count by high - low to 16 places or more (rounded), and takes its
     *    floor plus 1. The floor here is exact, from the remainder; within 5e-17 below a bucket's edge (screened at
     *    1e-15 of a bucket), where Postgres's rounded quotient may reach the edge, it is an error. */
    private static Tx widthBucket(SqlAst.FunctionCall f, List<Tx> args) {
        arity(f, args, 4);
        BigDecimal low = numberConstant(f.args().get(1));
        BigDecimal high = numberConstant(f.args().get(2));
        Long count = constantInteger(f.args().get(3));
        if (low == null || high == null || count == null) {
            throw SqlTranslationException.unsupported("width_bucket(...) with bounds or a count other than constants"
                    + " is not supported yet", f.pos());
        }
        if (count <= 0) {
            throw SqlTranslationException.invalid("count must be greater than zero", f.args().get(3).pos());
        }
        if (count > 10000 || low.abs().compareTo(BigDecimal.TEN.pow(300)) > 0
                || high.abs().compareTo(BigDecimal.TEN.pow(300)) > 0) {
            throw SqlTranslationException.unsupported("width_bucket(...) of more than 10000 buckets, or of bounds"
                    + " beyond 1e300, is not supported yet", f.pos());
        }
        int order = low.compareTo(high);
        if (order == 0) {
            throw SqlTranslationException.invalid("lower bound cannot equal upper bound", f.pos());
        }
        Tx x = args.get(0);
        numericOperand(x, f.args().get(0));
        if (isNullConstant(x)) {
            return nullOf(Kind.INTEGER);
        }
        inexact(x, "width_bucket(...)", f.pos());
        boolean integerBounds = f.args().get(1) instanceof SqlAst.IntegerLit
                || f.args().get(1) instanceof SqlAst.Unary u1 && u1.operand() instanceof SqlAst.IntegerLit;
        integerBounds &= f.args().get(2) instanceof SqlAst.IntegerLit
                || f.args().get(2) instanceof SqlAst.Unary u2 && u2.operand() instanceof SqlAst.IntegerLit;
        if (x.kind() == Kind.WHOLE && integerBounds) {
            throw SqlTranslationException.unsupported("width_bucket(...) of a sum of integers and integer bounds is"
                    + " not supported yet: Postgres's sum is a bigint (read as a double) or a numeric by the column's"
                    + " width (cast it)", f.pos());
        }
        boolean doubles = x.kind() == Kind.FLOAT || x.kind() == Kind.INTEGER && integerBounds;
        boolean ascending = order < 0;
        return strict(Kind.INTEGER, List.of(x), v -> doubles
                ? floatBucket(x.kind() == Kind.FLOAT ? v.get(0) : call("toFloat", v.get(0)), low, high, count,
                        ascending)
                : numericBucket(v.get(0), low, high, count, ascending));
    }

    private static ValueSpecification floatBucket(ValueSpecification x, BigDecimal low, BigDecimal high, long count,
            boolean ascending) {
        ValueSpecification lo = call("toFloat", new CDecimal(low));
        ValueSpecification hi = call("toFloat", new CDecimal(high));
        ValueSpecification part = ascending
                ? call("divide", AppliedFunction.infixRun("minus", List.of(x, lo)),
                        AppliedFunction.infixRun("minus", List.of(hi, lo)))
                : call("divide", AppliedFunction.infixRun("minus", List.of(lo, x)),
                        AppliedFunction.infixRun("minus", List.of(lo, hi)));
        ValueSpecification bucket = call("floor", AppliedFunction.infixRun("times", List.of(
                call("toFloat", new CInteger(count)), part)));
        ValueSpecification inside = AppliedFunction.infixRun("plus", List.of(call("if", call("greaterThanEqual",
                bucket, new CInteger(count)), thunk(new CInteger(count - 1)), thunk(bucket)), new CInteger(1L)));
        return buckets(x, lo, hi, count, ascending, inside);
    }

    private static ValueSpecification numericBucket(ValueSpecification x, BigDecimal low, BigDecimal high, long count,
            boolean ascending) {
        ValueSpecification lo = new CDecimal(low);
        ValueSpecification hi = new CDecimal(high);
        ValueSpecification a = AppliedFunction.infixRun("times", List.of(ascending
                ? AppliedFunction.infixRun("minus", List.of(x, lo))
                : AppliedFunction.infixRun("minus", List.of(lo, x)), new CInteger(count)));
        ValueSpecification b = ascending ? AppliedFunction.infixRun("minus", List.of(hi, lo))
                : AppliedFunction.infixRun("minus", List.of(lo, hi));
        // a is at least 0 and below count * b: a - rem(a, b) is b times the floor exactly, a whole number below
        // 10,000 that the quotient's double rounds to
        ValueSpecification rest = call("rem", a, b);
        ValueSpecification floor = call("round", call("divide", AppliedFunction.infixRun("minus", List.of(a, rest)),
                b));
        // within 5e-17 of the next edge (as a part of b) Postgres's rounded quotient may reach it: the double of that
        // part, within 2^-52 of it, is screened at 1e-15 (a product with a 17-digit constant overflows DuckDB's
        // 18-digit decimal arithmetic)
        ValueSpecification near = call("lessThan", call("divide", AppliedFunction.infixRun("minus", List.of(b, rest)),
                b), new com.legend.protocol.spec.CFloat(1e-15));
        ValueSpecification inside = call("if", near, thunk(call("toOne", call("cast", empty(), typeArg("Integer")))),
                thunk(AppliedFunction.infixRun("plus", List.of(floor, new CInteger(1L)))));
        return buckets(x, lo, hi, count, ascending, inside);
    }

    /** A bucket: 0 before the first, count + 1 after the last, else {@code inside}. */
    private static ValueSpecification buckets(ValueSpecification x, ValueSpecification lo, ValueSpecification hi,
            long count, boolean ascending, ValueSpecification inside) {
        ValueSpecification before = call(ascending ? "lessThan" : "greaterThan", x, lo);
        ValueSpecification after = call(ascending ? "greaterThanEqual" : "lessThanEqual", x, hi);
        return call("if", before, thunk(new CInteger(0L)), thunk(call("if", after, thunk(new CInteger(count + 1)),
                thunk(inside))));
    }

    /** {@code greatest(...)}, {@code least(...)}: the largest (smallest) of the values, NULLs ignored; NULL only when
     *  every value is. */
    private static Tx extreme(SqlAst.FunctionCall f, List<Tx> arguments, boolean greatest) {
        List<Tx> args = common(greatest ? "GREATEST" : "LEAST", f.args(), arguments, f.pos()).values();
        Tx best = null;
        for (int i = 0; i < args.size(); i++) {
            Tx a = args.get(i);
            if (a.kind() == Kind.NULL) {
                continue;
            }
            if (a.kind() == Kind.BOOLEAN) {
                throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + "(...) of boolean values is"
                        + " not supported yet", f.args().get(i).pos());
            }
            if (best == null) {
                best = a;
                continue;
            }
            comparableKinds(best, a, f.pos());
            best = better(best, a, greatest);
        }
        if (best == null) {
            // every value NULL: Postgres reads them as text
            return nullOf(Kind.STRING);
        }
        return best;
    }

    private static Tx better(Tx l, Tx r, boolean greatest) {
        ValueSpecification lv = valueOf(l);
        ValueSpecification rv = valueOf(r);
        // where both hold a value, each is one value: the pick is never empty (its type says so)
        ValueSpecification lOne = l.nullable() ? call("toOne", lv) : lv;
        ValueSpecification rOne = r.nullable() ? call("toOne", rv) : rv;
        ValueSpecification v = call("if", call(greatest ? "greaterThanEqual" : "lessThanEqual", lOne, rOne),
                thunk(lOne), thunk(rOne));
        if (r.nullable()) {
            v = call("if", call("isEmpty", rv), thunk(lv), thunk(v));
        }
        if (l.nullable()) {
            v = call("if", call("isEmpty", lv), thunk(rv), thunk(v));
        }
        Kind kind = l.kind() == r.kind() ? l.kind() : l.kind().numeric() && r.kind().numeric()
                ? arithmetic(l.kind(), r.kind()) : l.kind();
        return Tx.value(v, kind, l.nullable() && r.nullable());
    }

    /** {@code num_nulls(...)}, {@code num_nonnulls(...)}: how many of the values are NULL (are not). */
    private static Tx countNulls(SqlAst.FunctionCall f, List<Tx> args, boolean nulls) {
        if (args.isEmpty()) {
            throw SqlTranslationException.invalid("function " + functionName(f.name().get(0)) + " needs an argument",
                    f.pos());
        }
        List<ValueSpecification> ones = new ArrayList<>();
        long fixed = 0;
        for (Tx a : args) {
            if (a.kind() == Kind.NULL || !a.nullable()) {
                fixed += (a.kind() == Kind.NULL) == nulls ? 1 : 0;
                continue;
            }
            ones.add(call("if", call(nulls ? "isEmpty" : "isNotEmpty", valueOf(a)), thunk(new CInteger(1L)),
                    thunk(new CInteger(0L))));
        }
        if (fixed != 0 || ones.isEmpty()) {
            ones.add(new CInteger(fixed));
        }
        return Tx.value(ones.size() == 1 ? ones.get(0) : AppliedFunction.infixRun("plus", ones), Kind.INTEGER, false);
    }

    /** {@code extract(field FROM x)}, {@code date_part('field', x)}: the whole-number fields of a date or timestamp
     *  (DATE_FIELDS); a time of day is a timestamp's only. */
    /** {@code extract(field FROM x)} ({@code numeric}, Postgres 14 on) and {@code date_part('field', x)} (double
     *  precision): a field's whole number. */
    private static Tx datePart(SqlAst.FunctionCall f, List<Tx> args, boolean numeric) {
        arity(f, args, 2);
        String fn = functionName(f.name().get(0));
        if (!(f.args().get(0) instanceof SqlAst.StringLit field)) {
            throw SqlTranslationException.unsupported(fn + "(...) of a computed field is not supported yet",
                    f.args().get(0).pos());
        }
        Tx x = args.get(1);
        String name = fold(field.value());
        String simple = DATE_FIELDS.get(name);
        java.util.function.UnaryOperator<ValueSpecification> computed = COMPUTED_FIELDS.get(name);
        java.util.function.UnaryOperator<ValueSpecification> pure = simple != null ? v -> call(simple, v) : computed;
        if (pure == null) {
            throw SqlTranslationException.unsupported(fn + "(" + name + " ...) is not supported yet", field.pos());
        }
        boolean timeOfDay = TIME_FIELDS.contains(name);
        // Pure's toEpochValue is whole seconds: a timestamp's fraction would be lost
        boolean dayOnly = name.equals("epoch");
        if (!isDate(x.kind()) || timeOfDay && x.kind() != Kind.DATETIME || dayOnly && x.kind() != Kind.DATE) {
            throw SqlTranslationException.unsupported(fn + "(" + name + " ...) of a " + x.kind().name().toLowerCase(
                    Locale.ROOT) + " value is not supported yet", f.args().get(1).pos());
        }
        // extract is numeric in Postgres (14 on), date_part double precision: a field's whole number as either
        return numeric ? strict(Kind.DECIMAL, List.of(x), v -> pure.apply(v.get(0)))
                : strict(Kind.FLOAT, List.of(x), v -> call("toFloat", pure.apply(v.get(0))));
    }

    /** Refuses another number of arguments as not supported: Postgres may have that overload (btrim(x, chars)). */
    private static void arity(SqlAst.FunctionCall f, List<Tx> args, int n) {
        if (args.size() != n) {
            throw SqlTranslationException.unsupported("function " + functionName(f.name().get(0)) + " with "
                    + args.size() + " argument" + (args.size() == 1 ? "" : "s") + " is not supported yet; it takes "
                    + n + " here", f.pos());
        }
    }

    /**
     * {@code CAST(x AS type)}, {@code x::type}: a string constant is read by the type's input rules (as a typed
     * constant is); a value converts where one Pure function gives Postgres's answer: a number to another number
     * (an exact one to an integer rounds half away from zero, a double precision half to even), to text; a
     * timestamp to its date; an integer to a boolean. An integer or smallint holds a value in its range, else it
     * is Postgres's error; {@code varchar(n)} cuts the text to n characters. Refused: what rounds, formats or reads
     * otherwise in Postgres (a double precision to a numeric, a double precision or a timestamp to text, text to a
     * number), another type modifier, the other types.
     */
    private Tx cast(SqlAst.Cast c, Scope scope, String row) {
        String type = c.type().name();
        Kind target = CAST_TYPES.get(type);
        if (target == null) {
            throw SqlTranslationException.unsupported("casts to " + type + " are not supported yet", c.pos());
        }
        if (!c.type().modifiers().isEmpty()) {
            if (VARCHAR_TYPES.contains(type) && c.type().modifiers().size() == 1 && c.type().modifiers().get(0) > 0) {
                // varchar(n): the value's text, cut to n characters (an explicit cast truncates in Postgres)
                long n = c.type().modifiers().get(0);
                Tx text = cast(new SqlAst.Cast(c.operand(), new SqlAst.TypeName(type, List.of(), c.type().pos()),
                        c.pos()), scope, row);
                return strict(Kind.STRING, List.of(text), v -> call("left", v.get(0), new CInteger(n)));
            }
            throw SqlTranslationException.unsupported("casts to " + type + " with a type modifier are not supported"
                    + " yet", c.pos());
        }
        // Postgres's integer (int4) and smallint (int2) are narrower than Pure's: a value they cannot hold is
        // Postgres's error ("integer out of range")
        long[] range = NARROW_INTEGERS.contains(type) ? new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE}
                : SMALL_INTEGERS.contains(type) ? new long[] {Short.MIN_VALUE, Short.MAX_VALUE} : null;
        if (c.operand() instanceof SqlAst.StringLit s && target == Kind.JSON) {
            return jsonConstant(s);
        }
        if (c.operand() instanceof SqlAst.StringLit s) {
            Tx read = target == Kind.STRING ? Tx.value(new CString(s.value()), Kind.STRING, false)
                    : fromString(s, target);
            if (range != null && read.vs() instanceof CInteger n && (n.value().longValue() > range[1]
                    || n.value().longValue() < range[0])) {
                throw SqlTranslationException.invalid("value \"" + s.value() + "\" is out of range for type "
                        + (SMALL_INTEGERS.contains(type) ? "smallint" : "integer"), s.pos());
            }
            return read;
        }
        Tx x = tx(c.operand(), scope, row);
        if (x.kind() == Kind.NULL) {
            return nullOf(target);
        }
        if (target == Kind.INTEGER && range != null && x.kind() == Kind.INTEGER && x.vs() instanceof CInteger n) {
            if (n.value().longValue() > range[1] || n.value().longValue() < range[0]) {
                throw SqlTranslationException.invalid((SMALL_INTEGERS.contains(type) ? "smallint" : "integer")
                        + " out of range", c.pos());
            }
            return x;
        }
        if (x.kind() == target || target == Kind.DECIMAL && x.kind() == Kind.NUMBER) {
            return range == null ? x : inRange(x, range, type);
        }
        if (x.kind() == Kind.WHOLE && target == Kind.INTEGER) {
            // a sum of integers as a bigint (or narrower): its width now known
            Tx whole = Tx.value(x.vs(), Kind.INTEGER, x.nullable());
            return range == null ? whole : inRange(whole, range, type);
        }
        if (x.kind() == Kind.INEXACT) {
            if (target == Kind.DECIMAL || target == Kind.FLOAT) {
                // already Postgres's numeric; as a double precision, Postgres's double of the exact value, which this
                // one may miss: inexact either way
                return x;
            }
            inexact(x, "a cast to " + type, c.pos());
        }
        String from = x.kind().name().toLowerCase(Locale.ROOT);
        String convert = switch (target) {
            case STRING -> x.kind() == Kind.INTEGER || x.kind() == Kind.WHOLE || x.kind() == Kind.DECIMAL
                    || x.kind() == Kind.BOOLEAN || x.kind() == Kind.DATE ? "toString" : null;
            // a numeric rounds half away from zero, a double half to even (Pure's round of a Float, measured). Text
            // is not read: a database's cast reads what Postgres's input rejects ('1.5' and '1e3' are 2 and 1000 on
            // DuckDB, errors in Postgres)
            case INTEGER -> x.kind() == Kind.DECIMAL || x.kind() == Kind.NUMBER ? "halfAwayFromZero"
                    : x.kind() == Kind.FLOAT ? "round" : null;
            // text to numeric: Pure's parseDecimal lowers to a fixed DECIMAL(5, 2), not Postgres's unbounded numeric
            case DECIMAL -> x.kind() == Kind.INTEGER || x.kind() == Kind.WHOLE ? "toDecimal" : null;
            // text likewise: DuckDB reads '1_000' as 1000 and '1e400' as Infinity, Postgres rejects both
            case FLOAT -> x.kind().numeric() ? "toFloat" : null;
            case DATE -> x.kind() == Kind.DATETIME ? "datePart" : null;
            case BOOLEAN -> x.kind() == Kind.INTEGER ? "notEqual" : null;
            // text parses into jsonb; a jsonb's text (with its strings quoted) is not Pure's to(@String)
            case JSON -> x.kind() == Kind.STRING ? FROM_JSON : null;
            default -> null;
        };
        if (convert == null) {
            throw SqlTranslationException.unsupported("casts from " + from + " to " + type + " are not supported yet",
                    c.pos());
        }
        if (target == Kind.BOOLEAN) {
            // Postgres: 0 is false, any other integer true
            return strictTest(List.of(x), v -> call("not", call("equal", v.get(0), new CInteger(0L))));
        }
        Tx converted = convert.equals("halfAwayFromZero") ? strict(target, List.of(x), v -> halfAwayFromZero(v.get(0)))
                : strict(target, List.of(x), v -> call(convert, v.get(0)));
        return range == null ? converted : inRange(converted, range, type);
    }

    /** An integer that a narrower Postgres integer must hold: past its range, Postgres's error -- an empty value
     *  read as one ({@code []->cast(@Integer)->toOne()} raises, typed as the integer it stands for). */
    private static Tx inRange(Tx x, long[] range, String type) {
        return strict(Kind.INTEGER, List.of(x), v -> call("if", call("or", call("lessThan", v.get(0),
                new CInteger(range[0])), call("greaterThan", v.get(0), new CInteger(range[1]))),
                thunk(call("toOne", call("cast", empty(), typeArg("Integer")))), thunk(v.get(0))));
    }

    // ------------------------------------------------------------------ dates

    private static final String DURATION_UNIT = "meta::pure::functions::date::DurationUnit";

    /** An interval constant's months, days and seconds, which Postgres adds in that order. */
    private record Interval(long months, long days, long seconds) {
    }

    /** The units an interval constant's text may name (Postgres's abbreviations too): their months, days, seconds. */
    private static final Map<String, long[]> INTERVAL_UNITS = intervalUnits();

    private static Map<String, long[]> intervalUnits() {
        // one array per field, shared by its spellings: a field named twice is Postgres's error
        Map<String, long[]> m = new LinkedHashMap<>();
        long[] year = {12, 0, 0};
        for (String y : List.of("year", "years", "yr", "yrs", "y")) {
            m.put(y, year);
        }
        long[] month = {1, 0, 0};
        for (String mo : List.of("month", "months", "mon", "mons")) {
            m.put(mo, month);
        }
        long[] week = {0, 7, 0};
        for (String w : List.of("week", "weeks", "w")) {
            m.put(w, week);
        }
        long[] day = {0, 1, 0};
        for (String d : List.of("day", "days", "d")) {
            m.put(d, day);
        }
        long[] hour = {0, 0, 3600};
        for (String h : List.of("hour", "hours", "hr", "hrs", "h")) {
            m.put(h, hour);
        }
        long[] minute = {0, 0, 60};
        for (String mi : List.of("minute", "minutes", "min", "mins", "m")) {
            m.put(mi, minute);
        }
        long[] second = {0, 0, 1};
        for (String sec : List.of("second", "seconds", "sec", "secs", "s")) {
            m.put(sec, second);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** An interval constant's text in Postgres's plain form: signed whole amounts, each with its unit. Null for
     *  another form (a fraction, {@code HH:MM:SS}, {@code ago}, a bare number): refused. */
    private static @com.legend.base.Nullable Interval interval(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (char c : (text.toLowerCase(Locale.ROOT) + " ").toCharArray()) {
            if (c != ' ') {
                word.append(c);
            } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (words.isEmpty() || words.size() % 2 != 0) {
            return null;
        }
        long months = 0;
        long days = 0;
        long seconds = 0;
        List<long[]> fields = new ArrayList<>();
        for (int i = 0; i < words.size(); i += 2) {
            String amount = plainNumber(words.get(i), false);
            long[] unit = INTERVAL_UNITS.get(words.get(i + 1));
            if (amount == null || unit == null || amount.length() > 9 || fields.contains(unit)) {
                return null;
            }
            fields.add(unit);
            long n = Long.parseLong(amount);
            months += n * unit[0];
            days += n * unit[1];
            seconds += n * unit[2];
        }
        return new Interval(months, days, seconds);
    }

    /** An interval constant ({@code INTERVAL '1 day'}, {@code '1 day'::interval}); null for another expression. */
    private static @com.legend.base.Nullable Interval intervalConstant(SqlAst.Expr e) {
        String text = e instanceof SqlAst.TypedLit t && t.type().name().equals("interval") ? t.value()
                : e instanceof SqlAst.Cast c && c.type().name().equals("interval") && c.type().modifiers().isEmpty()
                        && c.operand() instanceof SqlAst.StringLit s ? s.value() : null;
        if (text == null) {
            return null;
        }
        Interval i = interval(text);
        if (i == null) {
            throw SqlTranslationException.unsupported("the interval '" + text + "' is not supported yet: whole years,"
                    + " months, weeks, days, hours, minutes and seconds are", e.pos());
        }
        return i;
    }

    /**
     * A date or timestamp plus or minus an interval constant: a timestamp, its months added first (a day past the
     * month's end is its last day), then its days, then its seconds, as Postgres adds them. A date is a timestamp at
     * midnight here, as in Postgres.
     */
    private Tx intervalArithmetic(SqlAst.Binary b, Scope scope, String row) {
        boolean minus = b.op() == SqlAst.BinaryOp.MINUS;
        Interval li = intervalConstant(b.left());
        Interval ri = intervalConstant(b.right());
        if (li != null && (ri != null || minus)) {
            throw SqlTranslationException.unsupported("interval arithmetic other than a date or timestamp plus or"
                    + " minus an interval is not supported yet", b.pos());
        }
        Interval in = java.util.Objects.requireNonNull(li != null ? li : ri);
        Tx d = tx(li != null ? b.right() : b.left(), scope, row);
        if (d.kind() == Kind.NULL || isNullConstant(d)) {
            return nullOf(Kind.DATETIME);
        }
        if (!isDate(d.kind())) {
            throw SqlTranslationException.unsupported("an interval with a " + postgresName(d.kind()) + " value is not"
                    + " supported yet", b.pos());
        }
        long sign = minus ? -1 : 1;
        return strict(Kind.DATETIME, List.of(d), v -> {
            ValueSpecification x = v.get(0);
            if (d.kind() == Kind.DATE) {
                x = atMidnight(x);
            }
            if (in.months() != 0) {
                x = call("adjust", x, new CInteger(sign * in.months()), new EnumValue(DURATION_UNIT, "MONTHS"));
            }
            if (in.days() != 0) {
                x = call("adjust", x, new CInteger(sign * in.days()), new EnumValue(DURATION_UNIT, "DAYS"));
            }
            if (in.seconds() != 0) {
                x = call("adjust", x, new CInteger(sign * in.seconds()), new EnumValue(DURATION_UNIT, "SECONDS"));
            }
            return x;
        });
    }

    /** {@code +} and {@code -} with a date or a timestamp on a side: a date plus or minus an integer is a date, so many
     *  days on; a date minus a date the days between them. Refused: a timestamp minus a timestamp (an interval) and
     *  the rest. */
    private static Tx dateArithmetic(SqlAst.Binary b, Tx l, Tx r) {
        boolean minus = b.op() == SqlAst.BinaryOp.MINUS;
        if (l.kind() == Kind.DATE && r.kind() == Kind.DATE && minus) {
            // the days from the right date to the left one
            return strict(Kind.INTEGER, List.of(l, r), v -> call("dateDiff", v.get(1), v.get(0),
                    new EnumValue(DURATION_UNIT, "DAYS")));
        }
        Tx date = l.kind() == Kind.DATE ? l : r;
        Tx days = date == l ? r : l;
        if (date.kind() == Kind.DATE && days.kind() == Kind.INTEGER && !(minus && date == r)) {
            return strict(Kind.DATE, List.of(date, days), v -> call("adjust", v.get(0), minus ? call("minus", v.get(1))
                    : v.get(1), new EnumValue(DURATION_UNIT, "DAYS")));
        }
        throw SqlTranslationException.unsupported("this date and timestamp arithmetic is not supported yet (a date"
                + " plus or minus an integer, a date minus a date, and a date or timestamp plus or minus an interval"
                + " constant are)", b.pos());
    }

    /** Postgres's date_trunc fields and Pure's: the first moment of the year, quarter, month, week (Monday), day, hour,
     *  minute or second. */
    private static final Map<String, String> TRUNCATIONS = Map.of("year", "firstDayOfYear", "quarter",
            "firstDayOfQuarter", "month", "firstDayOfMonth", "week", "firstDayOfWeek", "day", "firstHourOfDay", "hour",
            "firstMinuteOfHour", "minute", "firstSecondOfMinute", "second", "firstMillisecondOfSecond");

    /** {@code date_trunc('field', timestamp)}: a timestamp. Of a date Postgres truncates a timestamp with time zone:
     *  refused. */
    private static Tx dateTrunc(SqlAst.FunctionCall f, List<Tx> args) {
        arity(f, args, 2);
        String pure = args.get(0).vs() instanceof CString c ? TRUNCATIONS.get(c.value().toLowerCase(Locale.ROOT))
                : null;
        if (pure == null) {
            throw SqlTranslationException.unsupported("date_trunc to a field other than a constant year, quarter,"
                    + " month, week, day, hour, minute or second is not supported yet", f.args().get(0).pos());
        }
        Tx x = args.get(1);
        if (x.kind() != Kind.DATETIME) {
            throw SqlTranslationException.unsupported("date_trunc of a " + postgresName(x.kind()) + " value is not"
                    + " supported yet", f.args().get(1).pos());
        }
        // Pure's first day of a year, quarter, month or week is a date: Postgres's is a timestamp at its midnight
        boolean day = pure.startsWith("firstDayOf");
        return strict(Kind.DATETIME, List.of(x), v -> day ? atMidnight(call(pure, v.get(0))) : call(pure, v.get(0)));
    }

    /** A date as the timestamp at its midnight (no time added: Pure's adjust of a date by 0 hours). */
    private static ValueSpecification atMidnight(ValueSpecification date) {
        return call("adjust", date, new CInteger(0L), new EnumValue(DURATION_UNIT, "HOURS"));
    }

    /** {@code DATE '...'} and {@code TIMESTAMP '...'} in ISO 8601 spelling: Pure date constants; {@code JSONB '...'}
     *  and {@code JSON '...'}: a Variant constant. */
    private static Tx typedLiteral(SqlAst.TypedLit lit) {
        if (JSON_TYPES.contains(lit.type().name())) {
            return jsonConstant(new SqlAst.StringLit(lit.value(), lit.pos()));
        }
        return switch (lit.type().name()) {
            case "date" -> dateFromString(new SqlAst.StringLit(lit.value(), lit.pos()), Kind.DATE);
            case "timestamp", "timestamp without time zone" ->
                    dateFromString(new SqlAst.StringLit(lit.value(), lit.pos()), Kind.DATETIME);
            default -> throw SqlTranslationException.unsupported("constants of type " + lit.type().name() + " are not"
                    + " supported yet", lit.pos());
        };
    }

    /** A string constant read as a date or a timestamp by Postgres's rules ({@link PostgresDateTime}): ISO 8601 and
     *  Postgres's other spellings (month names, month/day/year, digits run together, a year's day, a Julian day, AM and
     *  PM, epoch); a date drops a time of day. Its Pure constant spells the same day or instant, {@code
     *  yyyy-mm-ddThh:mm:ss[.ffffff]} for a timestamp (24:00:00 is the next midnight). */
    private static Tx dateFromString(SqlAst.StringLit s, Kind target) {
        PostgresDateTime.Value v = PostgresDateTime.read(s.value(), target == Kind.DATE ? "date" : "timestamp",
                s.pos());
        if (target == Kind.DATE) {
            String body = v.date().toString();
            return Tx.value(new CDate(PureDateLiteral.parse(body), body, null), Kind.DATE, false);
        }
        java.time.LocalDateTime t = v.date().atStartOfDay().plusNanos(v.micros() * 1000);
        if (t.getYear() > 9999) {
            throw SqlTranslationException.unsupported("'" + s.value() + "' as a timestamp is not supported yet (a"
                    + " timestamp outside the years 1 to 9999)", s.pos());
        }
        String body = String.format(Locale.ROOT, "%sT%02d:%02d:%02d", t.toLocalDate(), t.getHour(), t.getMinute(),
                t.getSecond());
        if (t.getNano() != 0) {
            body += "." + String.format(Locale.ROOT, "%06d", t.getNano() / 1000);
        }
        return Tx.value(new CDate(PureDateLiteral.parse(body), body, null), Kind.DATETIME, false);
    }

    // ------------------------------------------------------------------ jsonb

    /** Pure's semi-structured value: Postgres's jsonb here (register S49). */
    private static final String VARIANT = "meta::pure::metamodel::variant::Variant";
    private static final String FROM_JSON = "fromJson";

    /** jsonb's navigation operators: {@code ->} a member or an element, {@code ->>} its text, {@code #>} and
     *  {@code #>>} the same along a path. */
    private static final Set<String> JSON_OPERATORS = Set.of("->", "->>", "#>", "#>>");

    /** {@code '...'::jsonb}: the text parsed (text that is not JSON is Postgres's error, and fromJson's). */
    private static Tx jsonConstant(SqlAst.StringLit s) {
        if (duplicateKey(s.value())) {
            // jsonb keeps a duplicate key's last value, DuckDB's JSON reads its first
            throw SqlTranslationException.unsupported("a jsonb constant with a duplicate key is not supported yet",
                    s.pos());
        }
        // jsonb's 'null' is a value, not SQL's NULL; a database may hold it either way
        return Tx.value(call(FROM_JSON, new CString(s.value())), Kind.JSON, s.value().strip().equals("null"));
    }

    /** Whether JSON text has an object with a key twice (text that is not JSON: no; reading it is the error). */
    private static boolean duplicateKey(String json) {
        java.util.ArrayDeque<Set<String>> objects = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Boolean> isObject = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                int start = ++i;
                while (i < json.length() && json.charAt(i) != '"') {
                    i += json.charAt(i) == '\\' ? 2 : 1;
                }
                if (i >= json.length()) {
                    return false;
                }
                String raw = json.substring(start, i++);
                int next = i;
                while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
                    next++;
                }
                if (next < json.length() && json.charAt(next) == ':' && Boolean.TRUE.equals(isObject.peek())) {
                    String key = com.legend.json.Json.unescape(raw);
                    if (!java.util.Objects.requireNonNull(objects.peek()).add(key)) {
                        return true;
                    }
                }
                continue;
            }
            if (c == '{' || c == '[') {
                isObject.push(c == '{');
                objects.push(new HashSet<>());
            } else if ((c == '}' || c == ']') && !isObject.isEmpty()) {
                isObject.pop();
                objects.pop();
            }
            i++;
        }
        return false;
    }

    private Tx jsonOperator(SqlAst.Operator o, Scope scope, String row) {
        if (!o.operator().endsWith(">>")) {
            constantNavigation(java.util.Objects.requireNonNull(o.left()), o.pos());
        }
        Tx value = jsonOperand(tx(java.util.Objects.requireNonNull(o.left()), scope, row), o.operator(), o.pos());
        Tx got;
        if (o.operator().startsWith("#")) {
            if (!(o.right() instanceof SqlAst.StringLit path)) {
                throw SqlTranslationException.unsupported("a path for " + o.operator() + " other than a constant"
                        + " ('{a,b}') is not supported yet", o.right().pos());
            }
            got = value;
            boolean constant = isJsonConstant(java.util.Objects.requireNonNull(o.left()));
            for (String key : jsonPath(path)) {
                constantIndex(constant, key, path.pos());
                got = pathElement(got, key, path.pos());
            }
        } else {
            got = navigate(value, o.right(), tx(o.right(), scope, row), o.operator());
        }
        return o.operator().endsWith(">>") ? jsonText(got) : got;
    }

    /** {@code base[index]}: jsonb's subscript, a PATH element (Postgres's jsonb_get_element), not {@code ->}: an
     *  integer reads an array's element or an object's member named by its digits; a text reads an object's member or,
     *  if it is an integer's spelling, an array's element. */
    private Tx subscript(SqlAst.Subscript sub, Scope scope, String row) {
        constantNavigation(sub.base(), sub.pos());
        Tx base = tx(sub.base(), scope, row);
        if (base.kind() != Kind.JSON) {
            throw SqlTranslationException.unsupported("subscripts of values other than jsonb (arrays) are not"
                    + " supported yet", sub.pos());
        }
        SqlAst.Expr index = sub.index();
        if (index instanceof SqlAst.StringLit k) {
            return pathElement(base, k.value(), k.pos());
        }
        if (index instanceof SqlAst.IntegerLit n) {
            if (n.value().bitLength() >= 32) {
                throw SqlTranslationException.invalid("subscript type bigint is not supported", n.pos());
            }
            return pathElement(base, n.value().toString(), n.pos());
        }
        Tx key = tx(index, scope, row);
        if (key.kind() == Kind.INTEGER) {
            // an array's element, else an object's member named by the integer's digits
            Tx got = strict(Kind.JSON, List.of(key), k -> call("coalesce", call("get", base.vs(), k.get(0)),
                    call("get", base.vs(), call("toString", k.get(0)))));
            return Tx.value(got.vs(), Kind.JSON, true);
        }
        if (key.kind() == Kind.STRING) {
            throw SqlTranslationException.unsupported("a subscript text other than a constant is not supported yet",
                    index.pos());
        }
        if (key.kind() == Kind.NULL || key.kind() == Kind.OTHER) {
            throw SqlTranslationException.unsupported("a jsonb subscript of this type is not supported yet",
                    index.pos());
        }
        throw SqlTranslationException.invalid("subscript type " + postgresName(key.kind()) + " is not supported",
                index.pos());
    }

    /** One element of a jsonb path ({@code #>}, a subscript, {@code jsonb_extract_path}), as Postgres reads it:
     *  an object's member named by the text; an array's element if the text is an integer's spelling (strtol's:
     *  leading white space, a sign, digits, nothing after, within int4), else none; a scalar's, none. */
    private static Tx pathElement(Tx value, String element, SqlAst.Pos pos) {
        Integer index = pathIndex(element);
        Tx member = member(value, element, pos);
        if (index == null) {
            return member;
        }
        // an object has no element and an array no member: whichever the value is, one of the two reads it
        return Tx.value(call("coalesce", member.vs(), call("get", value.vs(), integer(java.math.BigInteger.valueOf(
                index)))), Kind.JSON, true);
    }

    /** A path element that spells an integer, read out of a jsonb CONSTANT, is refused: whichever of an element or a
     *  member it reads is chosen in the database, and legend-lite's Postgres lowering folds a literal Variant's
     *  navigation (constantNavigation's reason). */
    private static void constantIndex(boolean constant, String element, SqlAst.Pos pos) {
        if (constant && pathIndex(element) != null) {
            throw SqlTranslationException.unsupported("a path element that spells an integer, read out of a jsonb"
                    + " constant, is not supported yet", pos);
        }
    }

    /** The array index a path element's text spells, as Postgres's strtoint reads it, or null. */
    private static @com.legend.base.Nullable Integer pathIndex(String text) {
        int i = 0;
        while (i < text.length() && " \t\n\u000b\f\r".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        String number = text.substring(i);
        if (!isIntegerText(number)) {
            return null;
        }
        try {
            return Integer.parseInt(number.startsWith("+") ? number.substring(1) : number);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A jsonb value read out of a jsonb CONSTANT is refused: legend-lite's Postgres lowering folds the navigation of a
     *  literal Variant at compile time, and re-emits a picked string, number or boolean as a plain SQL literal, not a
     *  jsonb value (its text, read by ->>, is right). A column's, or any value's not a constant's, is not folded. */
    private static void constantNavigation(SqlAst.Expr base, SqlAst.Pos pos) {
        if (isJsonConstant(base)) {
            throw SqlTranslationException.unsupported("reading a jsonb value out of a jsonb constant is not supported"
                    + " yet (its text, with ->> or #>>, is)", pos);
        }
    }

    private static boolean isJsonConstant(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.Cast c -> JSON_TYPES.contains(c.type().name())
                    && (c.operand() instanceof SqlAst.StringLit || isJsonConstant(c.operand()));
            case SqlAst.TypedLit t -> JSON_TYPES.contains(t.type().name());
            case SqlAst.Operator o -> o.left() != null && (o.operator().equals("->") || o.operator().equals("#>"))
                    && isJsonConstant(java.util.Objects.requireNonNull(o.left()));
            case SqlAst.Subscript sub -> isJsonConstant(sub.base());
            // an untyped constant where jsonb is read (a function's argument) is a jsonb constant
            case SqlAst.StringLit l -> true;
            default -> false;
        };
    }

    private static final Set<String> JSON_TYPES = Set.of("json", "jsonb");

    private static Tx jsonOperand(Tx t, String operator, SqlAst.Pos pos) {
        if (t.kind() == Kind.JSON) {
            return t;
        }
        if (t.kind() == Kind.NULL || t.kind() == Kind.OTHER) {
            throw SqlTranslationException.unsupported("the operator " + operator + " of a value that is not jsonb is"
                    + " not supported yet", pos);
        }
        throw SqlTranslationException.invalid("operator does not exist: " + t.kind().name().toLowerCase(Locale.ROOT)
                + " " + operator + " unknown", pos);
    }

    /** jsonb's order and equality are its own (an object after an array, a longer object after a shorter one, keys
     *  by length): a Variant is ordered, grouped and compared by Pure's rules, so a jsonb value is no key here. */
    private static void notJsonKey(Kind kind, String what, SqlAst.Pos pos) {
        if (kind == Kind.JSON) {
            throw SqlTranslationException.unsupported(what + " of a jsonb value is not supported yet (jsonb orders and"
                    + " compares by its own rules)", pos);
        }
    }

    /** {@code value -> key}: a constant key's member (an object's, else NULL), or an integer's element (an array's,
     *  from 0; a negative one counts from the end; else NULL). */
    private static Tx navigate(Tx value, SqlAst.Expr keyExpr, Tx key, String operator) {
        if (keyExpr instanceof SqlAst.IntegerLit n && n.value().bitLength() >= 32) {
            throw SqlTranslationException.invalid("operator does not exist: jsonb " + operator + " bigint",
                    keyExpr.pos());
        }
        if (key.kind() == Kind.INTEGER) {
            Tx got = strict(Kind.JSON, List.of(key), k -> call("get", value.vs(), k.get(0)));
            return Tx.value(got.vs(), Kind.JSON, true);
        }
        if (key.kind() == Kind.STRING && keyExpr instanceof SqlAst.StringLit k) {
            return member(value, k.value(), k.pos());
        }
        if (key.kind() == Kind.STRING) {
            // DuckDB's arrow reads a key that starts with $ or / as a path: only a constant one is known not to
            throw SqlTranslationException.unsupported("a member key other than a constant is not supported yet",
                    keyExpr.pos());
        }
        if (key.kind() == Kind.NULL || key.kind() == Kind.OTHER) {
            throw SqlTranslationException.unsupported("a jsonb key of this type is not supported yet", keyExpr.pos());
        }
        throw SqlTranslationException.invalid("operator does not exist: jsonb " + operator + " "
                + key.kind().name().toLowerCase(Locale.ROOT), keyExpr.pos());
    }

    private static Tx member(Tx value, String key, SqlAst.Pos pos) {
        if (key.startsWith("$") || key.startsWith("/")) {
            throw SqlTranslationException.unsupported("a member key that starts with $ or / is read as a path by"
                    + " DuckDB; it is not supported yet", pos);
        }
        return Tx.value(call("get", value.vs(), new CString(key)), Kind.JSON, true);
    }

    /** {@code ->>}'s text: a string's own characters, a number's or a boolean's spelling; NULL for jsonb's null. */
    private static Tx jsonText(Tx got) {
        return Tx.value(call("to", got.vs(), typeArg("String")), Kind.STRING, true);
    }

    /** A text[] constant's elements, the keys of a jsonb path: {@code '{a,b,"c d"}'}. An element that is a number
     *  reads an array's element or an object's member, by the value it meets: it is not supported yet. */
    private static List<String> jsonPath(SqlAst.StringLit lit) {
        String text = lit.value().strip();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw SqlTranslationException.unsupported("a path other than a plain array constant ('{a,b}') is not"
                    + " supported yet", lit.pos());
        }
        String body = text.substring(1, text.length() - 1);
        List<String> keys = new ArrayList<>();
        if (body.strip().isEmpty()) {
            return keys;
        }
        int i = 0;
        while (true) {
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            StringBuilder key = new StringBuilder();
            if (i < body.length() && body.charAt(i) == '"') {
                i++;
                while (i < body.length() && body.charAt(i) != '"') {
                    if (body.charAt(i) == '\\' && i + 1 < body.length()) {
                        i++;
                    }
                    key.append(body.charAt(i++));
                }
                if (i >= body.length()) {
                    throw SqlTranslationException.unsupported("a path with an unclosed quote is not supported", lit.pos());
                }
                i++;
                while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
                    i++;
                }
            } else {
                int start = i;
                while (i < body.length() && body.charAt(i) != ',') {
                    char c = body.charAt(i);
                    if (c == '"' || c == '{' || c == '}' || c == '\\') {
                        throw SqlTranslationException.unsupported("a path element with " + c + " is not supported yet",
                                lit.pos());
                    }
                    i++;
                }
                String bare = body.substring(start, i).strip();
                if (bare.isEmpty() || bare.equalsIgnoreCase("null")) {
                    throw SqlTranslationException.unsupported("an empty or NULL path element is not supported yet",
                            lit.pos());
                }
                key.append(bare);
            }
            keys.add(key.toString());
            if (i >= body.length()) {
                return keys;
            }
            if (body.charAt(i) != ',') {
                throw SqlTranslationException.unsupported("a malformed path constant is not supported", lit.pos());
            }
            i++;
        }
    }

    /** Whether {@code s} is an integer's digits, signed or not: a path element jsonb reads by the value it meets. */
    private static boolean isIntegerText(String s) {
        int start = !s.isEmpty() && (s.charAt(0) == '+' || s.charAt(0) == '-') ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** {@code json_extract_path(x, VARIADIC keys)}: {@code #>}'s path, its keys constants; the _text form
     *  {@code #>>}'s. */
    private static Tx extractPath(SqlAst.FunctionCall f, List<Tx> args, boolean text) {
        if (args.size() < 2) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + " with no keys is not"
                    + " supported yet", f.pos());
        }
        if (!text) {
            constantNavigation(f.args().get(0), f.pos());
        }
        // an untyped constant is read as the function's json argument
        Tx got = f.args().get(0) instanceof SqlAst.StringLit lit ? jsonConstant(lit)
                : jsonOperand(args.get(0), functionName(f.name().get(0)), f.pos());
        boolean constant = isJsonConstant(f.args().get(0));
        for (int i = 1; i < args.size(); i++) {
            if (!(f.args().get(i) instanceof SqlAst.StringLit key)) {
                throw SqlTranslationException.unsupported("a path key other than a constant is not supported yet",
                        f.args().get(i).pos());
            }
            constantIndex(constant, key.value(), key.pos());
            got = pathElement(got, key.value(), key.pos());
        }
        return text ? jsonText(got) : got;
    }

    /** {@code to_jsonb(x)}: a text, number or boolean as jsonb's string, number or boolean. */
    private static Tx toJson(SqlAst.FunctionCall f, List<Tx> args) {
        arity(f, args, 1);
        if (f.args().get(0) instanceof SqlAst.StringLit) {
            throw SqlTranslationException.invalid("could not determine polymorphic type because input has type"
                    + " unknown", f.pos());
        }
        Kind k = args.get(0).kind();
        if (k == Kind.JSON) {
            return args.get(0);
        }
        if (k != Kind.STRING && k != Kind.INTEGER && k != Kind.BOOLEAN && k != Kind.DECIMAL && k != Kind.FLOAT) {
            throw SqlTranslationException.unsupported(functionName(f.name().get(0)) + "(...) of a "
                    + k.name().toLowerCase(Locale.ROOT) + " is not supported yet", f.pos());
        }
        if (args.get(0).vs() instanceof CString text) {
            // a text constant is a jsonb string constant (its JSON spelling, quoted and escaped): legend-lite's
            // Postgres lowering spells toVariant of a string constant with no type, which Postgres cannot resolve
            return Tx.value(call(FROM_JSON, new CString(com.legend.json.Json.toCompact(text.value()))), Kind.JSON,
                    false);
        }
        return strict(Kind.JSON, args, v -> call("toVariant", v.get(0)));
    }

    /** {@code @Name}: a type as a value, as {@code to(@String)} takes it. */
    private static ValueSpecification typeArg(String name) {
        return new TypeAnnotation.Named(new TypeExpression.NameRef(name));
    }

    // ------------------------------------------------------------------ canonical form

    /** An expression's structure, its column references resolved: two spellings of one expression ({@code name},
     *  {@code t.NAME}) have one canonical form. Postgres matches GROUP BY and select-list expressions this way. */
    private static String sorts(List<SqlAst.SortItem> items, Input in) {
        StringBuilder b = new StringBuilder("[");
        for (SqlAst.SortItem i : items) {
            b.append(canon(i.expr(), in)).append(i.descending() ? " desc" : "").append(" nulls ")
                    .append(i.nullsFirst()).append(';');
        }
        return b.append(']').toString();
    }

    private static String canon(SqlAst.Expr e, Input in) {
        return switch (e) {
            case SqlAst.ColumnRef ref -> {
                Col c = in.column(ref);
                yield "col(" + (c.var() == null ? "" : c.var() + ".") + c.name() + ")";
            }
            case SqlAst.IntegerLit n -> "int(" + n.value() + ")";
            case SqlAst.NumericLit n -> "num(" + n.value().toPlainString() + ")";
            case SqlAst.StringLit s -> "str(" + s.value().length() + ":" + s.value() + ")";
            case SqlAst.BooleanLit b -> "bool(" + b.value() + ")";
            case SqlAst.NullLit n -> "null";
            case SqlAst.TypedLit t -> "typed(" + t.type().name() + ":" + t.value() + ")";
            case SqlAst.Param p -> "param(" + p.index() + ")";
            case SqlAst.Unary u -> u.op() + "(" + canon(u.operand(), in) + ")";
            case SqlAst.Binary b -> b.op() + "(" + canon(b.left(), in) + "," + canon(b.right(), in) + ")";
            case SqlAst.Operator o -> {
                SqlAst.Expr left = o.left();
                yield "op(" + o.operator() + "," + (left == null ? "" : canon(left, in)) + "," + canon(o.right(), in)
                        + ")";
            }
            case SqlAst.IsNull n -> "isnull" + n.negated() + "(" + canon(n.operand(), in) + ")";
            case SqlAst.IsTruth t -> "is" + t.negated() + t.truth() + "(" + canon(t.operand(), in) + ")";
            case SqlAst.IsDistinct d -> "distinct" + d.negated() + "(" + canon(d.left(), in) + ","
                    + canon(d.right(), in) + ")";
            case SqlAst.InList l -> "in" + l.negated() + "(" + canon(l.operand(), in) + list(l.values(), in) + ")";
            case SqlAst.Between b -> "between" + b.negated() + "(" + canon(b.operand(), in) + ","
                    + canon(b.low(), in) + "," + canon(b.high(), in) + ")";
            case SqlAst.Like l -> "like" + l.negated() + l.caseInsensitive() + "(" + canon(l.operand(), in) + ","
                    + canon(l.pattern(), in) + "," + (l.escape() == null ? "" : canon(
                            java.util.Objects.requireNonNull(l.escape()), in)) + ")";
            case SqlAst.Case c -> {
                StringBuilder b = new StringBuilder("case(");
                SqlAst.Expr operand = c.operand();
                b.append(operand == null ? "" : canon(operand, in));
                for (SqlAst.When w : c.whens()) {
                    b.append(",when(").append(canon(w.condition(), in)).append(",").append(canon(w.result(), in))
                            .append(")");
                }
                SqlAst.Expr otherwise = c.otherwise();
                b.append(",else(").append(otherwise == null ? "" : canon(otherwise, in)).append("))");
                yield b.toString();
            }
            case SqlAst.FunctionCall f -> {
                SqlAst.Expr filter = f.filter();
                yield "fn(" + String.join(".", f.name().stream().map(SqlToPure::functionName).toList()) + ","
                        + f.distinct() + "," + f.star() + list(f.args(), in) + sorts(f.orderBy(), in) + "within"
                        + sorts(f.withinGroup(), in) + (filter == null ? "" : "filter(" + canon(filter, in) + ")")
                        + ")";
            }
            case SqlAst.Cast c -> "cast(" + c.type().name() + c.type().modifiers() + "," + canon(c.operand(), in)
                    + ")";
            // a subquery or a window call is the one it is: matched only to itself, by where it was written
            case SqlAst.ScalarSubquery q -> "subquery@" + q.pos().line() + ":" + q.pos().column();
            case SqlAst.Exists q -> "exists@" + q.pos().line() + ":" + q.pos().column();
            case SqlAst.InSubquery q -> "in@" + q.pos().line() + ":" + q.pos().column();
            case SqlAst.Quantified q -> "quantified@" + q.pos().line() + ":" + q.pos().column();
            case SqlAst.Window w -> "window@" + w.pos().line() + ":" + w.pos().column();
            case SqlAst.Subscript sub -> "subscript(" + canon(sub.base(), in) + "," + canon(sub.index(), in) + ")";
            case SqlAst.ArrayConstructor a -> "array(" + list(a.elements(), in) + ")";
        };
    }

    private static String list(List<SqlAst.Expr> values, Input in) {
        StringBuilder b = new StringBuilder();
        for (SqlAst.Expr v : values) {
            b.append(',').append(canon(v, in));
        }
        return b.toString();
    }

    private static List<SqlAst.Expr> children(SqlAst.Expr e) {
        return switch (e) {
            case SqlAst.ColumnRef r -> List.of();
            case SqlAst.IntegerLit n -> List.of();
            case SqlAst.NumericLit n -> List.of();
            case SqlAst.StringLit s -> List.of();
            case SqlAst.BooleanLit b -> List.of();
            case SqlAst.NullLit n -> List.of();
            case SqlAst.TypedLit t -> List.of();
            case SqlAst.Param p -> List.of();
            case SqlAst.Unary u -> List.of(u.operand());
            case SqlAst.Binary b -> List.of(b.left(), b.right());
            case SqlAst.Operator o -> {
                SqlAst.Expr left = o.left();
                yield left == null ? List.of(o.right()) : List.of(left, o.right());
            }
            case SqlAst.IsNull n -> List.of(n.operand());
            case SqlAst.IsTruth t -> List.of(t.operand());
            case SqlAst.IsDistinct d -> List.of(d.left(), d.right());
            case SqlAst.InList l -> {
                List<SqlAst.Expr> all = new ArrayList<>();
                all.add(l.operand());
                all.addAll(l.values());
                yield all;
            }
            case SqlAst.Between b -> List.of(b.operand(), b.low(), b.high());
            case SqlAst.Like l -> {
                List<SqlAst.Expr> all = new ArrayList<>(List.of(l.operand(), l.pattern()));
                SqlAst.Expr esc = l.escape();
                if (esc != null) {
                    all.add(esc);
                }
                yield all;
            }
            case SqlAst.Case c -> {
                List<SqlAst.Expr> all = new ArrayList<>();
                SqlAst.Expr operand = c.operand();
                if (operand != null) {
                    all.add(operand);
                }
                for (SqlAst.When w : c.whens()) {
                    all.add(w.condition());
                    all.add(w.result());
                }
                SqlAst.Expr otherwise = c.otherwise();
                if (otherwise != null) {
                    all.add(otherwise);
                }
                yield all;
            }
            case SqlAst.FunctionCall f -> {
                List<SqlAst.Expr> all = new ArrayList<>(f.args());
                f.orderBy().forEach(o -> all.add(o.expr()));
                f.withinGroup().forEach(o -> all.add(o.expr()));
                SqlAst.Expr filter = f.filter();
                if (filter != null) {
                    all.add(filter);
                }
                yield all;
            }
            case SqlAst.Cast c -> List.of(c.operand());
            // a subquery's insides are its own query's: its aggregates are not this one's
            case SqlAst.ScalarSubquery q -> List.of();
            case SqlAst.Exists q -> List.of();
            case SqlAst.InSubquery q -> List.of(q.operand());
            case SqlAst.Quantified q -> List.of(q.left());
            // a window call is no aggregate of this query's grouping, but what it reads may be
            case SqlAst.Window w -> {
                List<SqlAst.Expr> all = new ArrayList<>(w.call().args());
                all.addAll(w.spec().partitionBy());
                w.spec().orderBy().forEach(o -> all.add(o.expr()));
                yield all;
            }
            case SqlAst.Subscript sub -> List.of(sub.base(), sub.index());
            case SqlAst.ArrayConstructor a -> a.elements();
        };
    }

    // ------------------------------------------------------------------ building Pure

    /** A function name as Postgres reads it: folded when unquoted, exact when quoted. */
    private static String functionName(SqlAst.Ident id) {
        return id.quoted() ? id.text() : fold(id.text());
    }

    private static AppliedFunction call(String fn, ValueSpecification... args) {
        return call(fn, List.of(args));
    }

    private static AppliedFunction call(String fn, List<ValueSpecification> args) {
        return new AppliedFunction(fn, args);
    }

    private static ValueSpecification property(String row, String column) {
        return new AppliedProperty(new Variable(row), column);
    }

    private static LambdaFunction lambda(String parameter, ValueSpecification body) {
        return new LambdaFunction(List.of(new Variable(parameter)), List.of(body));
    }

    /** A two-parameter lambda: a join's condition over its two sides' rows. */
    private static LambdaFunction lambda2(String first, String second, ValueSpecification body) {
        return new LambdaFunction(List.of(new Variable(first), new Variable(second)), List.of(body));
    }

    private static LambdaFunction thunk(ValueSpecification body) {
        return new LambdaFunction(List.of(), List.of(body));
    }

    /** No value: Pure's {@code []}, SQL's NULL. */
    private static ValueSpecification empty() {
        return new PureCollection(List.of());
    }

    private static ColSpec colSpec(String name) {
        return new ColSpec(name, null, null, null);
    }
}
