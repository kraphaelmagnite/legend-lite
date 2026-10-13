// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.SourceInfo;
import com.legend.protocol.spec.SqlIsland;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlTranslationException;

import java.util.function.Function;

/**
 * THE ONE PLACE a {@code #SQL{...}#} island becomes a Relation expression (docs/LEGEND_SQL_STUDY_2026_10_11.md §3).
 * The protocol layer's translator ({@link SqlToPure}) reads the Postgres SQL and builds the Pure a person would write;
 * this seam answers its one question, what a FROM source's columns are, by typing the source; it resolves the result's
 * names as the resolver resolves any query, and the Typer then types it once, like any query. A refusal becomes the
 * compile error its kind says, placed in the enclosing source.
 */
final class SqlIslands {

    /** The island's opening, {@code #SQL{}: the SQL text starts right after it. */
    private static final int OPENING = "#SQL{".length();

    private SqlIslands() {
    }

    /** The relation expression {@code island} means, names resolved; {@code typeSource} types a resolved source. */
    static ValueSpecification desugar(SqlIsland island, Function<ValueSpecification, TypedSpec> typeSource) {
        try {
            ValueSpecification pure = SqlToPure.translate(island.sql(),
                    (source, column) -> columns(typeSource.apply(NameResolver.resolveQuery(source)), island, column));
            return NameResolver.resolveQuery(pure);
        } catch (SqlTranslationException e) {
            String message = "#SQL{...}# at " + position(island, e.line(), e.column()) + ": " + e.getMessage();
            throw switch (e.kind()) {
                case SYNTAX -> new com.legend.error.IslandSyntaxException(message);
                case UNSUPPORTED -> new com.legend.error.NotImplementedException(message);
                case INVALID -> new TypeInferenceException(message);
            };
        }
    }

    /** A FROM source's columns, as the translator reads them: each column's name, its type's name, and whether it
     *  may hold no value. */
    private static void columns(TypedSpec source, SqlIsland island, SqlToPure.Column column) {
        Type.RelationType schema = Type.relationSchema(source.info().type());
        if (schema == null || !schema.dynamicColumns().isEmpty()) {
            throw new TypeInferenceException("#SQL{...}# at " + position(island, 1, 1) + ": a FROM source must be a"
                    + " relation with known columns, not " + source.info().type().typeName());
        }
        for (Type.Column c : schema.columns()) {
            boolean required = c.multiplicity() instanceof com.legend.compiler.element.type.Multiplicity.Bounded b
                    && b.lower() >= 1;
            column.column(c.name(), c.type().typeName(), !required);
        }
    }

    /** A position in the SQL text as a position in the enclosing source (the island's own when it has one). */
    private static String position(SqlIsland island, int line, int column) {
        SourceInfo pos = island.pos();
        if (pos == null) {
            return "line " + line + ", column " + column + " of the SQL";
        }
        return line == 1 ? "line " + pos.startLine() + ", column " + (pos.startColumn() + OPENING + column - 1)
                : "line " + (pos.startLine() + line - 1) + ", column " + column;
    }
}
