// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A corpus lane's workspaces on a SERVER database (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §20): one
 * long-lived server, a workspace per package session -- {@link DuckWorkspaces}' shape. On Postgres a workspace is a
 * DATABASE of its own (the corpus stores' declared schemas live inside it undisturbed, as in a DuckDB catalog),
 * created over one administrative connection and dropped when the session's connection closes.
 */
final class ServerWorkspaces {

    private static final AtomicInteger IDS = new AtomicInteger();

    /** The administrative connection (to the server's own {@code postgres} database): workspaces are made and
     *  dropped over it. */
    private static Connection admin;

    private ServerWorkspaces() {
    }

    /** A fresh Postgres database, connected. */
    static synchronized Connection postgres() throws SQLException {
        com.legend.testing.EmbeddedPostgres pg = com.legend.testing.EmbeddedPostgres.shared();
        if (admin == null) {
            admin = DriverManager.getConnection(pg.jdbcUrl("postgres"));
        }
        String ws = "ws_" + IDS.getAndIncrement();
        try (Statement st = admin.createStatement()) {
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
            st.execute("CREATE DATABASE " + ws);
        }
        Connection conn = DriverManager.getConnection(pg.jdbcUrl(ws));
        return closeDrops(conn, ws);
    }

    /** The session's connection, whose close drops its database too. */
    private static Connection closeDrops(Connection inner, String ws) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("close".equals(method.getName())) {
                try {
                    inner.close();
                } finally {
                    drop(ws);
                }
                return null;
            }
            try {
                return method.invoke(inner, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(ServerWorkspaces.class.getClassLoader(),
                new Class<?>[] {Connection.class}, h);
    }

    private static synchronized void drop(String ws) {
        try (Statement st = admin.createStatement()) {
            com.legend.exec.StatementOrigin.count(com.legend.exec.StatementOrigin.SESSION);
            st.execute("DROP DATABASE IF EXISTS " + ws + " WITH (FORCE)");
        } catch (SQLException e) {
            // a failed drop must not mask the test's own outcome; the server is this run's alone
            System.err.println("[corpus2] workspace " + ws + " not dropped: " + e.getMessage());
        }
    }
}
