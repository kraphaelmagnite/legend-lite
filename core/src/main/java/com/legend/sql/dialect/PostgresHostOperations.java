// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import java.util.List;

/**
 * Postgres's host operations ({@link HostOperations}): the one a host needs of a Postgres database today, a planner's
 * query counted ({@link #countOf}) -- Snap's preflight on a warehouse's catalog that is Postgres
 * (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5, D5b), written as Postgres reads it, its subquery aliased as Postgres
 * before 16 requires. Every other is refused by name.
 */
final class PostgresHostOperations implements HostOperations {

    static final PostgresHostOperations INSTANCE = new PostgresHostOperations();

    private PostgresHostOperations() {
    }

    @Override
    public List<String> openDatabase(String name) {
        throw new DialectCapability("a project's own database on Postgres is not built: only DuckDB makes one so far "
                + "(docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5)");
    }

    @Override
    public List<String> useDatabase(String name) {
        return openDatabase(name);
    }

    @Override
    public List<String> closeDatabase(String name) {
        return openDatabase(name);
    }

    @Override
    public List<String> statements(HostOperation operation) {
        throw HostOperations.refusal(operation, "Postgres");
    }

    @Override
    public List<String> countOf(String plannedQuery) {
        return List.of("SELECT count(*) AS n FROM (" + plannedQuery + ") AS counted");
    }

    @Override
    public List<String> copyOf(HostOperation.Kind kind, HostOperation.Name target, CopyFrom from,
            List<CatalogModel.Conversion> conversions) {
        throw HostOperations.refusal("Copy", "Postgres");
    }
}
