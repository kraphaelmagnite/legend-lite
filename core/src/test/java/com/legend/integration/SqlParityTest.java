// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.integration;

import com.legend.server.QueryService;
import com.legend.testcases.SqlParity;
import com.legend.testing.Runfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upstream's Postgres parity corpus on DuckDB ({@link SqlParity}): every case of legend-engine's
 * {@code parity-tests} as a {@code #SQL{...}#} island over the corpus's tables in DuckDB, judged against Postgres 16's
 * snapped answers by upstream's rules, and held to the ledger's {@code duckdb} column. An island on DuckDB must
 * answer what Postgres answers; a case that does not is pinned FAIL in the ledger until it does.
 *
 * <p>Its own target ({@code //core:sql_parity_test}): it reads the pinned legend-engine tree. Run with
 * {@code --jvm_flag=-Dsql.parity.update=<the ledger's path in the workspace>} to write the ledger's column instead.
 */
class SqlParityTest {

    @Test
    void everyUpstreamCaseHasItsLedgerStatusOnDuckDB() throws Exception {
        Path dir = Runfile.property("sql.parity.schema").getParent();
        List<SqlParity.Case> cases = new java.util.ArrayList<>(SqlParity.cases(dir));
        cases.addAll(SqlParity.cases(Runfile.property("sql.parity.lite").getParent(), "lite/"));
        List<SqlParity.Table> tables = SqlParity.schema(dir);
        Set<String> names = SqlParity.tableNames(tables);
        Map<String, SqlParity.Answer> answers = SqlParity.readAnswers(Runfile.property("sql.parity.answers"));
        String model = SqlParity.model(tables, "type: DuckDB;\n    specification: DuckDB { };\n    auth: Test;");
        Map<String, SqlParity.Outcome> outcomes = new LinkedHashMap<>();
        try (Connection duckdb = DriverManager.getConnection("jdbc:duckdb:")) {
            SqlParity.seed(duckdb, tables, false);
            QueryService service = new QueryService();
            for (SqlParity.Case c : cases) {
                outcomes.put(SqlParity.key(c), SqlParity.outcome(c, answers.get(SqlParity.key(c)), names,
                        island -> service.execute(model, island, SqlParity.RUNTIME, duckdb)));
            }
        }
        String update = System.getProperty("sql.parity.update");
        List<String> problems = SqlParity.ratchet(cases, outcomes, Runfile.property("sql.parity.ledger"), "duckdb",
                update == null ? null : Path.of(update));
        assertTrue(problems.isEmpty(), problems.size() + " cases differ from the ledger:\n"
                + String.join("\n", problems));
    }

    @Test
    void aTextCellComparesAsJsonOnlyWherePostgresWroteItAsJsonb() {
        // the independent review of PR neema2/legend-lite#32: jsonb's spelling (", ", ": ", keys shorter first) proves
        // the text is a jsonb value's; any other text is compared as text
        assertTrue(SqlParity.cellEquals("{\"b\": [1, 2], \"aa\": true}", "{\"aa\":true,\"b\":[1,2]}"));
        assertFalse(SqlParity.cellEquals("[1,2]", "[1, 2]"));
        assertFalse(SqlParity.cellEquals("{\"aa\": true, \"b\": 1}", "{\"aa\":true,\"b\":1}"));
    }

    @Test
    void aSnappedAnswerAgreesWithPostgresOnAnotherPlatformInItsLastDigitsOnly() {
        // cbrt(-0.1) from Postgres on Linux and on Windows: their C libraries differ in the last place
        // (2026-10-12, PR neema2/legend-lite#28's CI)
        assertTrue(SqlParity.agrees(oneCell(-0.46415888336127786), oneCell(-0.464158883361278)));
        assertFalse(SqlParity.agrees(oneCell(-0.46415888336127786), oneCell(-0.4641588833)));
        assertFalse(SqlParity.agrees(oneCell(1.0), oneCell(null)));
        assertFalse(SqlParity.agrees(oneCell(1.0), oneCell("1.0")));
    }

    private static SqlParity.Answer oneCell(Object value) {
        return new SqlParity.Rows(new SqlParity.Grid(List.of("result"), List.of(Collections.singletonList(value))));
    }
}
