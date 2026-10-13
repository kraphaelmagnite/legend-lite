// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import java.util.List;

/**
 * DuckDB's host operations ({@link HostOperations}): a project's own database is an in-memory database ATTACHed to
 * the host's -- a catalog of its own, holding the model's schemas as declared -- and a connection points at it with
 * USE, which is that connection's alone (ATTACH is the instance's). DETACH drops it with everything in it. The corpus's
 * workspaces are the same three statements (spec's {@code DuckWorkspaces}, the session-topology ruling of 2026-08-14),
 * and DuckDB-WASM reads them as DuckDB does (measured 2026-10-13: DuckDB 1.5.5 and DuckDB-WASM's 1.5.4).
 */
final class DuckDbHostOperations implements HostOperations {

    static final DuckDbHostOperations INSTANCE = new DuckDbHostOperations();

    private DuckDbHostOperations() {
    }

    @Override
    public List<String> openDatabase(String name) {
        return List.of("ATTACH ':memory:' AS " + quoted(name));
    }

    @Override
    public List<String> useDatabase(String name) {
        return List.of("USE " + quoted(name));
    }

    @Override
    public List<String> closeDatabase(String name) {
        return List.of("DETACH DATABASE IF EXISTS " + quoted(name));
    }

    /** A catalog's name as DuckDB reads it, quoted: the name is ours, but never trusted to need none. */
    private static String quoted(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
