// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.setup;

import com.legend.compiler.element.ModelContext;
import com.legend.model.DataDefinition;
import com.legend.model.DatabaseDefinition;
import com.legend.protocol.Protocol;
import com.legend.sql.dialect.HostOperations;
import com.legend.sql.dialect.SqlDialect;

import java.util.ArrayList;
import java.util.List;

/**
 * A PROJECT'S OWN DATABASE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5): where a project version's {@code ###Data} rows
 * live on a host -- a database of their own inside the host's database (the tab's DuckDB, Python's, the server's), its
 * schemas and tables made exactly as the model declares them, so the planner's SQL runs there unchanged, and never
 * beside a person's own tables. A host makes it ({@link #open}), points a connection of its own at it ({@link #use}),
 * fills it ({@link #fill}), queries there, and drops it when idle ({@link #close}); it is rebuilt from the model the
 * next time, so dropping early is never wrong. The concept is the platform's: how a database makes one is its own
 * ({@link HostOperations}), and the rows are written by that database's dialect, as the server seeds its own
 * ({@link CsvSeed}). A database without the operations refuses by name.
 *
 * @param name  the database's name: from the identity it is kept by, so two versions never share one
 * @param open  the statements that make it, empty
 * @param use   the statements that point a connection of its own at it
 * @param fill  the statements, run on that connection, that make every table a relational Data element fills and put
 *              its rows in: in the model's order, each table the planner's seed for it ({@link CsvSeed#sqls})
 * @param close the statements that drop it with everything in it
 */
public record ProjectData(String name, List<String> open, List<String> use, List<String> fill, List<String> close) {

    /** The prefix of every project's database: no store schema spells it, so none can capture a model's name. */
    public static final String PREFIX = "__project_";

    public ProjectData {
        open = List.copyOf(open);
        use = List.copyOf(use);
        fill = List.copyOf(fill);
        close = List.copyOf(close);
    }

    /**
     * The project's database for the compiled model {@code ctx} on {@code dialect}'s database, kept by {@code identity}
     * (a project version's coordinates; or, with none, the model's text -- Studio's workspace, whose text changes).
     */
    public static ProjectData of(ModelContext ctx, String identity, SqlDialect dialect) {
        HostOperations host = HostOperations.of(dialect);
        String name = PREFIX + com.legend.cache.Hash.ofUtf8(identity).hex().substring(0, 16);
        return new ProjectData(name, host.openDatabase(name), host.useDatabase(name), fill(ctx, dialect),
                host.closeDatabase(name));
    }

    /** Every table a relational Data element fills, made and filled by {@code dialect}'s statements, in order. */
    private static List<String> fill(ModelContext ctx, SqlDialect dialect) {
        List<String> out = new ArrayList<>();
        for (DataDefinition data : ctx.dataElements().toList()) {
            // only rows a relational store holds: a model store's or an external format's data is not a table's
            if (!(data.body().value() instanceof Protocol.PRelationalCsvData csv)) {
                continue;
            }
            for (Protocol.PRelationalCsvTable t : csv.tables()) {
                if (t.values() == null) {
                    throw new IllegalArgumentException("the Data element " + data.qualifiedName() + " fills "
                            + t.schema() + "." + t.table() + " with no rows, not even a header");
                }
                String database = declaring(ctx, data.qualifiedName(), t.schema(), t.table());
                out.addAll(CsvSeed.sqls(t.schema() + "\n" + t.table() + "\n" + t.values(), database, ctx, dialect));
            }
        }
        return out;
    }

    /**
     * The one Database of the model that itself declares {@code schema.table} ({@code default}: a table of its default
     * schema). None, or more than one, is refused: which one would be a guess -- a test names its store, and a
     * project's own database has no test to ask.
     */
    private static String declaring(ModelContext ctx, String element, String schema, String table) {
        List<String> declaring = new ArrayList<>();
        for (DatabaseDefinition db : ctx.databases().toList()) {
            List<DatabaseDefinition.TableDefinition> tables = "default".equals(schema) ? db.defaultSchemaTables()
                    : db.schemas().stream().filter(s -> s.name().equals(schema))
                            .flatMap(s -> s.tables().stream()).toList();
            if (tables.stream().anyMatch(d -> d.name().equals(table))) {
                declaring.add(db.qualifiedName());
            }
        }
        if (declaring.size() != 1) {
            throw new IllegalArgumentException("the Data element " + element + " fills " + schema + "." + table
                    + (declaring.isEmpty() ? ", which no Database of the model declares"
                            : ", which more than one Database declares " + declaring));
        }
        return declaring.get(0);
    }
}
