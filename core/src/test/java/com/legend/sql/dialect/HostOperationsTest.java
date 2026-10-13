// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.setup.ProjectData;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project's own database (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5): {@link ProjectData}'s statements, run on a
 * DuckDB, make a database of the project's own inside it, its schemas as the model declares them and its Data rows in
 * them, beside a person's table of the same name and never touching it; and every database without the operations
 * refuses by name. In the dialect package's tests: they name DuckDB by design.
 */
class HostOperationsTest {

    private static final String MODEL = """
            ###Relational
            Database demo::Store
            (
               Schema firm
               (
                  Table Person (id INTEGER PRIMARY KEY, name VARCHAR(50))
               )
               Table Code (code VARCHAR(10))
            )

            ###Data
            Data demo::Rows
            {
              Relational
              #{
                firm.Person:
                  'id,name\\n' +
                  '1,Ada\\n' +
                  '2,Grace\\n';
                default.Code:
                  'code\\n' +
                  'X1\\n';
              }#
            }
            """;

    @Test
    void duckDbsStatements() {
        HostOperations duck = HostOperations.of(new DuckDb());
        assertEquals(List.of("ATTACH ':memory:' AS \"p\"\"1\""), duck.openDatabase("p\"1"));
        assertEquals(List.of("USE \"p\"\"1\""), duck.useDatabase("p\"1"));
        assertEquals(List.of("DETACH DATABASE IF EXISTS \"p\"\"1\""), duck.closeDatabase("p\"1"));
    }

    @Test
    void aProjectsRowsInADatabaseOfTheirOwn() throws SQLException {
        ProjectData project = ProjectData.of(com.legend.Compiler.compileModel(MODEL), new DuckDb());
        assertTrue(project.name().matches("__project_[0-9a-f]{16}"), project.name());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            // a person's own table, of the very name the project declares, in the host's own database
            st.execute("CREATE SCHEMA firm");
            st.execute("CREATE TABLE firm.Person (mine VARCHAR)");
            st.execute("INSERT INTO firm.Person VALUES ('kept')");
            for (String sql : project.open()) {
                st.execute(sql);
            }
            for (String sql : project.use()) {
                st.execute(sql);
            }
            for (String sql : project.fill()) {
                st.execute(sql);
            }
            assertEquals(List.of("1 Ada", "2 Grace"), rows(st, "SELECT id || ' ' || name FROM firm.Person ORDER BY id"));
            assertEquals(List.of("X1"), rows(st, "SELECT code FROM Code"));
            // back on the host's own database: the person's table as it was
            st.execute("USE memory");
            assertEquals(List.of("kept"), rows(st, "SELECT mine FROM firm.Person"));
            for (String sql : project.close()) {
                st.execute(sql);
            }
            assertFalse(rows(st, "SELECT database_name FROM duckdb_databases()").contains(project.name()));
            // dropped twice: nothing refused
            for (String sql : project.close()) {
                st.execute(sql);
            }
        }
    }

    /** Named by what it holds (Plan Gen's review, 2026-10-13): never a caller's label, so a SNAPSHOT republished under
     *  the same coordinates with new rows is never answered with the old. */
    @Test
    void namedByWhatItHolds() {
        String name = ProjectData.of(com.legend.Compiler.compileModel(MODEL), new DuckDb()).name();
        // the same Databases and rows, another class beside them: the same database, no refill
        String withAClass = MODEL.replace("###Data", "###Pure\nClass demo::Other { x: Integer[1]; }\n\n###Data");
        assertEquals(name, ProjectData.of(com.legend.Compiler.compileModel(withAClass), new DuckDb()).name());
        // an edited row, or an edited table, is another
        assertNotEquals(name, ProjectData.of(com.legend.Compiler.compileModel(MODEL.replace("2,Grace", "3,Edsger")),
                new DuckDb()).name());
        assertNotEquals(name, ProjectData.of(com.legend.Compiler.compileModel(MODEL.replace("VARCHAR(50)", "VARCHAR(60)")),
                new DuckDb()).name());
    }

    @Test
    void everyOtherDatabaseRefusesByName() {
        for (SqlDialect d : List.of(new Postgres(), new H2(), new Snowflake(), new SQLite(), new ClickHouse())) {
            HostOperations host = HostOperations.of(d);
            String name = d.getClass().getSimpleName();
            for (Runnable op : List.<Runnable>of(() -> host.openDatabase("p"), () -> host.useDatabase("p"),
                    () -> host.closeDatabase("p"))) {
                DialectCapability refused = assertThrows(DialectCapability.class, op::run, name);
                assertTrue(refused.getMessage().contains(" on " + name + " is not built"), refused.getMessage());
            }
            assertThrows(DialectCapability.class,
                    () -> ProjectData.of(com.legend.Compiler.compileModel(MODEL), d), name);
        }
    }

    @Test
    void aDataElementFillingNoDeclaredTableIsRefused() {
        String model = MODEL.replace("default.Code:", "default.Missing:");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ProjectData.of(com.legend.Compiler.compileModel(model), new DuckDb()));
        assertEquals("the Data element demo::Rows fills default.Missing, which no Database of the model declares",
                refused.getMessage());
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
