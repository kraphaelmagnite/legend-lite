// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import java.util.List;

/**
 * THE OPERATIONS A HOST RUNS ON ITS OWN DATABASE, outside any query (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5, with
 * the dialect program's decision D5): first, a project's own database -- a place inside the host's database where a
 * model's schemas and tables are made exactly as the model declares them, filled, queried on a connection of its own,
 * and dropped as one. What a project's database is, is the platform's ({@code com.legend.setup.ProjectData}); how a
 * database makes one is this file's, per database. DuckDB's are built ({@link DuckDbHostOperations}); every other
 * database refuses each by name ({@link DialectCapability}) until its own are written -- Postgres would make a
 * database, and one whose namespace cannot hold a model's own schemas unrenamed (ClickHouse's two levels, SQLite's
 * none) refuses for good.
 *
 * <p>Kept out of the dialects' own files while the dialect program restructures them (docs/IN_FLIGHT.md, its
 * section): it folds these into each dialect when it consolidates, and this lookup by dialect with them.
 */
public interface HostOperations {

    /** The statements that make the database {@code name}, empty. */
    List<String> openDatabase(String name);

    /**
     * The statements that point a connection of its own at {@code name}: run on it, its names resolve there -- a
     * model's {@code schema.table} as declared, and a table of the default schema by its name alone -- while every
     * other connection's stay where they were.
     */
    List<String> useDatabase(String name);

    /** The statements that drop {@code name} with everything in it; nothing is refused when it is gone already. */
    List<String> closeDatabase(String name);

    /**
     * The statements of a named operation (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5), in order -- none when it has
     * nothing to do (a secret with no credentials) -- or a {@link DialectCapability} naming it and the database.
     */
    List<String> statements(HostOperation operation);

    /**
     * A planner's query counted (Snap's preflight; docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5b): one row, one column
     * {@code n}. {@code plannedQuery} is a plan's SQL, written by the planner for this database -- never a host's text:
     * no host operation carries SQL.
     */
    List<String> countOf(String plannedQuery);

    /**
     * A relation made by a copy (Snap's, an upload's or a frame's rebuild; D5b): {@code target} made from the planner's
     * query or from a relation of this database, the one there replaced, each of the catalog's conversions
     * ({@link CatalogModel.Database#conversions}) applied in its column's place.
     */
    List<String> copyOf(HostOperation.Kind kind, HostOperation.Name target, CopyFrom from,
            List<CatalogModel.Conversion> conversions);

    /** Where a copy reads its rows: a planner's query (a plan's SQL), or a relation of the database. */
    sealed interface CopyFrom {
        /** A plan's SQL, the planner's own. */
        record PlannedQuery(String sql) implements CopyFrom {
        }

        /** A relation of the database. */
        record Relation(HostOperation.Name name) implements CopyFrom {
        }
    }

    /** The host operations of {@code dialect}'s database: DuckDB's, Postgres's count (a warehouse's catalog), or each
     *  refused, naming the database. */
    static HostOperations of(SqlDialect dialect) {
        if (dialect instanceof DuckDb) {
            return DuckDbHostOperations.INSTANCE;
        }
        if (dialect instanceof Postgres) {
            return PostgresHostOperations.INSTANCE;
        }
        return new Refused(dialect.getClass().getSimpleName());
    }

    /** {@code operation} refused on {@code database}: not written for it. */
    static DialectCapability refusal(HostOperation operation, String database) {
        return refusal(operation.getClass().getSimpleName(), database);
    }

    /** The operation {@code what} refused on {@code database}: not written for it. */
    static DialectCapability refusal(String what, String database) {
        return new DialectCapability("a host's " + what + " on " + database
                + " is not built (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5)");
    }

    /** A database whose host operations are not written yet: each refused, by name. */
    record Refused(String database) implements HostOperations {

        @Override
        public List<String> openDatabase(String name) {
            throw refused();
        }

        @Override
        public List<String> useDatabase(String name) {
            throw refused();
        }

        @Override
        public List<String> closeDatabase(String name) {
            throw refused();
        }

        @Override
        public List<String> statements(HostOperation operation) {
            throw HostOperations.refusal(operation, database);
        }

        @Override
        public List<String> countOf(String plannedQuery) {
            throw HostOperations.refusal("Count", database);
        }

        @Override
        public List<String> copyOf(HostOperation.Kind kind, HostOperation.Name target, CopyFrom from,
                List<CatalogModel.Conversion> conversions) {
            throw HostOperations.refusal("Copy", database);
        }

        private DialectCapability refused() {
            return new DialectCapability("a project's own database on " + database + " is not built: only DuckDB "
                    + "makes one so far (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5)");
        }
    }
}
