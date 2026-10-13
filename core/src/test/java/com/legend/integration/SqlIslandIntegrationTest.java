// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.error.LegendCompileException;
import com.legend.server.QueryService;
import com.legend.testcases.SqlIslandCases;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code #SQL{...}#} end to end on DuckDB (docs/LEGEND_SQL_STUDY_2026_10_11.md §3): every case of
 * {@link SqlIslandCases} returns the rows Postgres returns for it; the island composes with Pure; names resolve by
 * the study's rule; and a refusal is the compile error its kind says, placed in the source.
 */
class SqlIslandIntegrationTest {

    private static final String MODEL =
            SqlIslandCases.model("type: DuckDB;\n specification: DuckDB { };\n auth: Test;");

    private static Connection duckdb;

    @BeforeAll
    static void seed() throws SQLException {
        duckdb = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = duckdb.createStatement()) {
            s.execute(SqlIslandCases.DUCKDB_TABLE);
            s.execute("INSERT INTO EMP VALUES " + SqlIslandCases.ROWS);
        }
    }

    @AfterAll
    static void close() throws SQLException {
        duckdb.close();
    }

    private static com.legend.exec.ExecutionResult run(String query) throws SQLException {
        return new QueryService().execute(MODEL, query, SqlIslandCases.RUNTIME, duckdb);
    }

    private static List<List<String>> rows(com.legend.exec.ExecutionResult result) {
        List<List<String>> rows = new ArrayList<>();
        for (var row : result.rows()) {
            List<String> cells = new ArrayList<>();
            for (Object value : row.values()) {
                cells.add(SqlIslandCases.cell(value));
            }
            rows.add(cells);
        }
        return rows;
    }

    static Stream<Arguments> cases() {
        return SqlIslandCases.CASES.stream().map(c -> Arguments.of(c.sql(), c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void returnsWhatPostgresReturns(String sql, SqlIslandCases.Case c) throws SQLException {
        com.legend.exec.ExecutionResult result = run(c.island());
        assertEquals(c.columns(), result.columns().stream().map(col -> col.name()).toList(), sql);
        List<List<String>> actual = rows(result);
        if (c.ordered()) {
            assertEquals(c.rows(), actual, sql);
        } else {
            assertEquals(sorted(c.rows()), sorted(actual), sql);
        }
    }

    private static List<String> sorted(List<List<String>> rows) {
        return rows.stream().map(Object::toString).sorted().toList();
    }

    @Test
    void anUnquotedNameMatchesTheModelsNameIgnoringCase() throws SQLException {
        // Postgres would fold FIRSTNAME to firstname and find nothing; the study's rule then matches firstName
        com.legend.exec.ExecutionResult result =
                run("#SQL{select FIRSTNAME, last_name from tb('store::HR.EMP') where id = 1}#");
        assertEquals(List.of("firstName", "LAST_NAME"), result.columns().stream().map(col -> col.name()).toList());
        assertEquals(List.of(List.of("Ann", "Lee")), rows(result));
    }

    @Test
    void theIslandComposesWithPure() throws SQLException {
        com.legend.exec.ExecutionResult result = run("#SQL{select dept, count(*) as n from tb('store::HR.EMP')"
                + " where dept is not null group by dept}#->filter(x|$x.n > 2)->select(~[dept])");
        assertEquals(List.of(List.of("eng")), rows(result));
    }

    @Test
    void aSyntaxErrorIsAParseErrorAtItsPlaceInTheSource() {
        LegendCompileException e = assertThrows(LegendCompileException.class,
                () -> run("#SQL{select ID from tb('store::HR.EMP') where}#"));
        assertEquals(LegendCompileException.Phase.PARSE, e.phase());
        assertEquals("#SQL{...}# at line 1, column 46: syntax error at end of input", e.getMessage());
    }

    @Test
    void aQueryPostgresRejectsIsATypeError() {
        LegendCompileException e = assertThrows(LegendCompileException.class,
                () -> run("#SQL{select dept, age from tb('store::HR.EMP') group by dept}#"));
        assertEquals(LegendCompileException.Phase.TYPE, e.phase());
        assertTrue(e.getMessage().endsWith("column \"age\" must appear in the GROUP BY clause or be used in an"
                + " aggregate function"), e.getMessage());
    }

    @Test
    void sqlNotTranslatedYetIsNotImplemented() {
        com.legend.error.NotImplementedException e = assertThrows(com.legend.error.NotImplementedException.class,
                () -> run("#SQL{select dept, mode() within group (order by age) from tb('store::HR.EMP')"
                        + " group by dept}#"));
        assertEquals("#SQL{...}# at line 1, column 19: WITHIN GROUP is not supported yet", e.getMessage());
    }

    @Test
    void anAverageIsAFloatWherePostgresGivesANumeric() throws SQLException {
        // A KNOWN DIFFERENCE (docs/LEGEND_SQL_STUDY_2026_10_11.md §3.4): Pure's average is a Float, so avg over
        // integers is 33.333333333333336 here where Postgres's numeric gives 33.3333333333333333
        com.legend.exec.ExecutionResult result = run("#SQL{select avg(age) as a from tb('store::HR.EMP')"
                + " where dept = 'eng'}#");
        assertEquals(List.of(List.of("33.333333333333336")), rows(result));
    }
}
