// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legend.testing.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Snap's preflight count on a real Postgres (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5): the one host operation
 * Postgres's host operations write, run on the Postgres 16 the tests start, answers the rows of the planner's query --
 * the count a warehouse whose catalog is Postgres is asked before a copy. Its subquery carries the alias Postgres
 * before 16 requires (16 accepts it without).
 */
class HostOperationsPostgresTest {

    @Test
    void aPlannersQueryCountedOnPostgres() throws SQLException {
        String query = "SELECT x FROM generate_series(1, 7) AS g(x) WHERE x % 2 = 1";
        List<String> statements = HostOperations.of(new Postgres()).countOf(query);
        assertEquals(1, statements.size(), statements.toString());
        try (Connection pg = DriverManager.getConnection(EmbeddedPostgres.shared().jdbcUrl("postgres"));
                Statement s = pg.createStatement();
                ResultSet r = s.executeQuery(statements.get(0))) {
            assertTrue(r.next(), "a count answers one row");
            assertEquals(4L, r.getLong("n"));
        }
    }
}
