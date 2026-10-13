package com.legend.protocol.spec;

import java.util.Objects;

/**
 * A {@code #SQL{ select ... }#} expression island (the sql-expression
 * extension). Wire shape (ZTailProbe "sql-island"): a {@code classInstance}
 * of type {@code SQL} whose value is {@code {"sql": <content>}}; the span
 * covers the whole literal.
 *
 * <p>The SQL is Postgres SQL. The typer translates it into the Pure Relation
 * expression it means ({@code com.legend.protocol.sql.SqlToPure}) and types
 * that, so the island runs through the same typed pipeline as any query
 * ({@code compiler/spec/SqlIslands}).
 */
public record SqlIsland(String sql,
        @com.legend.base.Nullable com.legend.protocol.SourceInfo pos)
        implements ValueSpecification {

    public SqlIsland {
        Objects.requireNonNull(sql, "sql");
    }
}
