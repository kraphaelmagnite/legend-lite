// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres reader ({@link SqlParser}, {@link SqlLexer}): Postgres's lexical rules and operator precedence, every
 * clause it accepts, and the refusals by name and position of what it does not accept yet.
 */
class SqlParserTest {

    private static SqlAst.Select select(String sql) {
        return (SqlAst.Select) SqlParser.parse(sql);
    }

    private static SqlAst.Expr where(String condition) {
        return java.util.Objects.requireNonNull(select("select 1 from t where " + condition).where());
    }

    private static SqlAst.Expr item(String expr) {
        return ((SqlAst.SelectExpr) select("select " + expr + " from t").items().get(0)).expr();
    }

    // ------------------------------------------------------------------ clauses

    @Test
    void everyClauseOfASelect() {
        SqlAst.Select s = select("SELECT DISTINCT a, b AS c, d e FROM tb('p::DB.T') AS x WHERE a > 1"
                + " GROUP BY a, 2 HAVING count(*) > 1 ORDER BY a DESC NULLS LAST, 2 LIMIT 10 OFFSET 5;");
        assertTrue(s.distinct());
        assertEquals(3, s.items().size());
        assertEquals("c", java.util.Objects.requireNonNull(((SqlAst.SelectExpr) s.items().get(1)).alias()).text());
        assertEquals("e", java.util.Objects.requireNonNull(((SqlAst.SelectExpr) s.items().get(2)).alias()).text());
        SqlAst.TableFunction from = assertInstanceOf(SqlAst.TableFunction.class, s.from().get(0));
        assertEquals("tb", from.name().get(0).text());
        assertEquals("x", java.util.Objects.requireNonNull(from.alias()).text());
        assertEquals(2, s.groupBy().size());
        assertInstanceOf(SqlAst.Binary.class, s.having());
        assertTrue(s.orderBy().get(0).descending());
        assertEquals(Boolean.FALSE, s.orderBy().get(0).nullsFirst());
        assertNull(s.orderBy().get(1).nullsFirst());
        assertEquals(BigInteger.TEN, ((SqlAst.IntegerLit) java.util.Objects.requireNonNull(s.limit())).value());
        assertEquals(BigInteger.valueOf(5), ((SqlAst.IntegerLit) java.util.Objects.requireNonNull(s.offset())).value());
    }

    @Test
    void offsetBeforeLimitAndFetchFirst() {
        SqlAst.Select s = select("select a from t offset 2 rows limit 3");
        assertEquals(BigInteger.valueOf(3), ((SqlAst.IntegerLit) java.util.Objects.requireNonNull(s.limit())).value());
        SqlAst.Select f = select("select a from t fetch first 4 rows only");
        assertEquals(BigInteger.valueOf(4), ((SqlAst.IntegerLit) java.util.Objects.requireNonNull(f.limit())).value());
        SqlAst.Select one = select("select a from t fetch next row only");
        assertEquals(BigInteger.ONE, ((SqlAst.IntegerLit) java.util.Objects.requireNonNull(one.limit())).value());
        assertNull(select("select a from t limit all").limit());
    }

    @Test
    void starsAndQualifiedStars() {
        SqlAst.Select s = select("select *, t.* from t");
        assertNull(((SqlAst.AllColumns) s.items().get(0)).qualifier());
        assertEquals("t", java.util.Objects.requireNonNull(((SqlAst.AllColumns) s.items().get(1)).qualifier()).text());
    }

    @Test
    void namedArgumentsToATableFunction() {
        SqlAst.TableFunction f = (SqlAst.TableFunction) select("select a from service('/x', p => 1)").from().get(0);
        assertNull(f.args().get(0).name());
        assertEquals("p", java.util.Objects.requireNonNull(f.args().get(1).name()).text());
    }

    // ------------------------------------------------------------------ precedence

    @Test
    void andBindsTighterThanOrAndNotTighterThanAnd() {
        SqlAst.Binary or = (SqlAst.Binary) where("a = 1 or b = 2 and not c = 3");
        assertEquals(SqlAst.BinaryOp.OR, or.op());
        SqlAst.Binary and = (SqlAst.Binary) or.right();
        assertEquals(SqlAst.BinaryOp.AND, and.op());
        SqlAst.Unary not = (SqlAst.Unary) and.right();
        assertEquals(SqlAst.UnaryOp.NOT, not.op());
        assertEquals(SqlAst.BinaryOp.EQ, ((SqlAst.Binary) not.operand()).op());
    }

    @Test
    void multiplicationBindsTighterThanAdditionAndUnaryMinusTighterStill() {
        SqlAst.Binary plus = (SqlAst.Binary) item("a + b * -c");
        assertEquals(SqlAst.BinaryOp.PLUS, plus.op());
        SqlAst.Binary times = (SqlAst.Binary) plus.right();
        assertEquals(SqlAst.BinaryOp.TIMES, times.op());
        assertEquals(SqlAst.UnaryOp.MINUS, ((SqlAst.Unary) times.right()).op());
    }

    @Test
    void concatenationBindsLooserThanAddition() {
        SqlAst.Binary concat = (SqlAst.Binary) item("a || b + 1");
        assertEquals(SqlAst.BinaryOp.CONCAT, concat.op());
        assertEquals(SqlAst.BinaryOp.PLUS, ((SqlAst.Binary) concat.right()).op());
    }

    @Test
    void powerIsLeftAssociativeAndLooserThanASign() {
        // Postgres: - 2 ^ 2 is 4, 2 ^ 3 ^ 2 is 64
        SqlAst.Binary signed = (SqlAst.Binary) item("- 2 ^ 2");
        assertEquals(SqlAst.BinaryOp.POWER, signed.op());
        assertEquals(SqlAst.UnaryOp.MINUS, ((SqlAst.Unary) signed.left()).op());
        SqlAst.Binary chain = (SqlAst.Binary) item("2 ^ 3 ^ 2");
        assertEquals(SqlAst.BinaryOp.POWER, ((SqlAst.Binary) chain.left()).op());
        assertEquals(SqlAst.BinaryOp.POWER, ((SqlAst.Binary) ((SqlAst.Binary) item("a * b ^ c")).right()).op());
    }

    @Test
    void everyOtherOperatorSharesPostgresOpLevel() {
        // looser than + -, tighter than LIKE and =, left-associative with ||
        SqlAst.Operator regex = (SqlAst.Operator) item("a + b ~ c");
        assertEquals("~", regex.operator());
        assertEquals(SqlAst.BinaryOp.PLUS, ((SqlAst.Binary) java.util.Objects.requireNonNull(regex.left())).op());
        SqlAst.Operator afterConcat = (SqlAst.Operator) item("a || b ~ c");
        assertEquals(SqlAst.BinaryOp.CONCAT,
                ((SqlAst.Binary) java.util.Objects.requireNonNull(afterConcat.left())).op());
        SqlAst.Binary eq = (SqlAst.Binary) where("j ->> 'k' = 'v'");
        assertEquals("->>", ((SqlAst.Operator) eq.left()).operator());
        assertInstanceOf(SqlAst.Operator.class, ((SqlAst.Like) where("a ~ b like c")).operand());
        // a prefix operator takes all that binds tighter than its level
        SqlAst.Operator prefix = (SqlAst.Operator) item("@ a * 2 + 1");
        assertNull(prefix.left());
        assertEquals(SqlAst.BinaryOp.PLUS, ((SqlAst.Binary) prefix.right()).op());
    }

    @Test
    void operatorsAreReadByPostgresRule() {
        // =- has no character outside SQL's operators, so it is = then -
        SqlAst.Binary eq = (SqlAst.Binary) item("a=-1");
        assertEquals(SqlAst.BinaryOp.EQ, eq.op());
        assertEquals(SqlAst.UnaryOp.MINUS, ((SqlAst.Unary) eq.right()).op());
        assertEquals("@-", ((SqlAst.Operator) item("a @- 1")).operator());
        // an operator stops where a comment starts
        assertEquals(SqlAst.BinaryOp.LT, ((SqlAst.Binary) item("1 </*c*/ 2")).op());
        assertInstanceOf(SqlAst.IntegerLit.class, ((SqlAst.SelectExpr) select("select 2--1").items().get(0)).expr());
    }

    @Test
    void betweenTakesItsOwnAnd() {
        SqlAst.Binary and = (SqlAst.Binary) where("a between 1 and 2 and b");
        assertEquals(SqlAst.BinaryOp.AND, and.op());
        SqlAst.Between between = (SqlAst.Between) and.left();
        assertEquals(BigInteger.TWO, ((SqlAst.IntegerLit) between.high()).value());
        assertTrue(((SqlAst.Between) where("a not between 1 and 2")).negated());
    }

    @Test
    void isBindsLooserThanComparison() {
        SqlAst.IsNull isNull = (SqlAst.IsNull) where("a = b is not null");
        assertTrue(isNull.negated());
        assertEquals(SqlAst.BinaryOp.EQ, ((SqlAst.Binary) isNull.operand()).op());
        SqlAst.IsTruth truth = (SqlAst.IsTruth) where("a > 1 is not true");
        assertEquals(SqlAst.Truth.TRUE, truth.truth());
        assertTrue(truth.negated());
        SqlAst.IsDistinct distinct = (SqlAst.IsDistinct) where("a is distinct from b + 1");
        assertFalse(distinct.negated());
        assertInstanceOf(SqlAst.Binary.class, distinct.right());
        assertTrue(((SqlAst.IsNull) where("a notnull")).negated());
    }

    @Test
    void comparisonsDoNotAssociate() {
        assertThrows(SqlSyntaxException.class, () -> where("a < b < c"));
    }

    @Test
    void inLikeAndCase() {
        SqlAst.InList in = (SqlAst.InList) where("a not in (1, 2)");
        assertTrue(in.negated());
        assertEquals(2, in.values().size());
        SqlAst.Like like = (SqlAst.Like) where("a ilike 'x!%%' escape '!'");
        assertTrue(like.caseInsensitive());
        assertInstanceOf(SqlAst.StringLit.class, like.escape());
        SqlAst.Case simple = (SqlAst.Case) item("case a when 1 then 'one' else 'many' end");
        assertInstanceOf(SqlAst.ColumnRef.class, simple.operand());
        SqlAst.Case searched = (SqlAst.Case) item("case when a > 1 then 2 end");
        assertNull(searched.operand());
        assertNull(searched.otherwise());
    }

    @Test
    void castsInBothSpellings() {
        SqlAst.Cast cast = (SqlAst.Cast) item("cast(a as numeric(10, 2))");
        assertEquals("numeric", cast.type().name());
        assertEquals(List.of(10, 2), cast.type().modifiers());
        assertEquals("double precision", ((SqlAst.Cast) item("a::double precision")).type().name());
        assertEquals("timestamp with time zone", ((SqlAst.Cast) item("a::timestamp with time zone")).type().name());
    }

    // ------------------------------------------------------------------ lexical structure

    @Test
    void constants() {
        assertEquals(new BigDecimal("1.5E+3"), ((SqlAst.NumericLit) item("1.5e3")).value());
        assertEquals(new BigDecimal("0.5"), ((SqlAst.NumericLit) item(".5")).value());
        assertEquals(new BigInteger("99999999999999999999"),
                ((SqlAst.IntegerLit) item("99999999999999999999")).value());
        assertEquals("it's", ((SqlAst.StringLit) item("'it''s'")).value());
        assertEquals("a 'b' $c", ((SqlAst.StringLit) item("$q$a 'b' $c$q$")).value());
        assertEquals("x", ((SqlAst.StringLit) item("$$x$$")).value());
        SqlAst.TypedLit date = (SqlAst.TypedLit) item("DATE '2024-01-31'");
        assertEquals("date", date.type().name());
        assertEquals("2024-01-31", date.value());
        assertEquals("timestamp without time zone",
                ((SqlAst.TypedLit) item("timestamp without time zone '2024-01-31 10:00:00'")).type().name());
        assertEquals(1, ((SqlAst.Param) item("$1")).index());
    }

    @Test
    void postgres16IntegersAndDigitSeparators() {
        assertEquals(BigInteger.valueOf(31), ((SqlAst.IntegerLit) item("0x1F")).value());
        assertEquals(BigInteger.valueOf(15), ((SqlAst.IntegerLit) item("0o17")).value());
        assertEquals(BigInteger.valueOf(5), ((SqlAst.IntegerLit) item("0b101")).value());
        assertEquals(BigInteger.valueOf(31), ((SqlAst.IntegerLit) item("0x_1F")).value());
        assertEquals(BigInteger.valueOf(1_000_000), ((SqlAst.IntegerLit) item("1_000_000")).value());
        assertEquals(new BigDecimal("1000.0001"), ((SqlAst.NumericLit) item("1_000.000_1")).value());
        // Postgres's messages, the whole constant quoted
        assertEquals("invalid hexadecimal integer at or near \"0x\"",
                assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select 0x")).getMessage());
        for (String junk : List.of("1__0", "1_", "0o19", "123abc", "1e+")) {
            assertEquals("trailing junk after numeric literal at or near \"" + junk + "\"",
                    assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select " + junk)).getMessage(), junk);
        }
    }

    @Test
    void anyWordButTheAsLabelOnesIsABareAlias() {
        // Postgres 14+: select 1 select names its column "select"; day, filter, over ... need AS
        SqlAst.SelectExpr reserved = (SqlAst.SelectExpr) select("select 1 select").items().get(0);
        assertEquals("select", java.util.Objects.requireNonNull(reserved.alias()).text());
        assertEquals("desc", java.util.Objects.requireNonNull(
                ((SqlAst.SelectExpr) select("select a desc from t").items().get(0)).alias()).text());
        assertEquals("syntax error at or near \"day\"",
                assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select 1 day")).getMessage());
    }

    @Test
    void anEmptySelectList() {
        assertTrue(select("select from t").items().isEmpty());
        assertTrue(select("select").items().isEmpty());
        // but not after DISTINCT
        assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select distinct from t"));
    }

    @Test
    void identifiersKeepTheirCaseAndSayWhetherTheyWereQuoted() {
        SqlAst.ColumnRef unquoted = (SqlAst.ColumnRef) item("FirstName");
        assertEquals("FirstName", unquoted.parts().get(0).text());
        assertFalse(unquoted.parts().get(0).quoted());
        SqlAst.ColumnRef quoted = (SqlAst.ColumnRef) item("\"we\"\"ird\"");
        assertEquals("we\"ird", quoted.parts().get(0).text());
        assertTrue(quoted.parts().get(0).quoted());
        SqlAst.ColumnRef qualified = (SqlAst.ColumnRef) item("t.\"select\"");
        assertEquals(List.of("t", "select"), qualified.parts().stream().map(SqlAst.Ident::text).toList());
        // a non-reserved key word is a name
        assertEquals("name", ((SqlAst.ColumnRef) item("name")).parts().get(0).text());
    }

    @Test
    void keyWordsMatchInAnyCase() {
        SqlAst.Select s = select("SeLeCt a FrOm t WhErE a IS nOt NuLl");
        assertInstanceOf(SqlAst.IsNull.class, s.where());
    }

    @Test
    void commentsOfBothFormsNestedOnes() {
        SqlAst.Select s = select("select /* a /* nested */ comment */ a -- trailing\n from t");
        assertEquals(1, s.items().size());
    }

    @Test
    void valueFunctionsWithoutParentheses() {
        SqlAst.FunctionCall f = (SqlAst.FunctionCall) item("current_date");
        assertEquals("current_date", f.name().get(0).text());
        assertTrue(f.args().isEmpty());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void errorsSayWhere() {
        SqlSyntaxException e = assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select a\nfrom t where"));
        assertEquals(2, e.line());
        assertEquals(13, e.column());
        assertEquals("syntax error at end of input", e.getMessage());
        assertFalse(e.unsupported());
        SqlSyntaxException near = assertThrows(SqlSyntaxException.class,
                () -> SqlParser.parse("select a from t t2 t3"));
        assertEquals("syntax error at or near \"t3\"", near.getMessage());
        assertEquals(20, near.column());
    }

    @Test
    void lexicalErrors() {
        assertEquals("unterminated string constant",
                assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select 'abc")).getMessage());
        assertEquals("zero-length delimited identifier",
                assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select \"\" from t")).getMessage());
        assertTrue(assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select 123abc")).getMessage()
                .startsWith("trailing junk after numeric literal"));
    }

    @Test
    void onlyOneQueryStatement() {
        assertTrue(assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("insert into t values (1)"))
                .getMessage().startsWith("only queries are supported"));
        assertEquals("only one statement is supported",
                assertThrows(SqlSyntaxException.class, () -> SqlParser.parse("select 1; select 2")).getMessage());
    }

    @Test
    void notYetSupportedFormsAreRefusedByName() {
        List<String> forms = List.of(
                "select a from unnest(x) with ordinality", "WITH ORDINALITY is not supported yet",
                "values (1)", "VALUES lists are not supported yet",
                "select a = any(array[1]) from t", "ANY, SOME and ALL over an array are not supported yet",
                "select E'x'", "escape string constants (E'...') are not supported yet",
                "select a[1:2] from t", "array slices are not supported yet",
                "select a from t group by rollup (a)", "ROLLUP, CUBE and GROUPING SETS are not supported yet");
        for (int i = 0; i < forms.size(); i += 2) {
            String sql = forms.get(i);
            SqlSyntaxException e = assertThrows(SqlSyntaxException.class, () -> SqlParser.parse(sql), sql);
            assertEquals(forms.get(i + 1), e.getMessage(), sql);
            assertTrue(e.unsupported(), sql);
        }
    }
}
