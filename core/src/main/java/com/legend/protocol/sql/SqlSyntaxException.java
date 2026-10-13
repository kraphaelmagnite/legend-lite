// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

/**
 * A Postgres SQL text that does not parse, or that uses syntax this reader does not accept yet
 * ({@link #unsupported()}). It carries the 1-based line and column in the SQL text it was given, so the caller (the
 * {@code #SQL{...}#} island's translator, later the wire server) can place it in its own source.
 */
public final class SqlSyntaxException extends RuntimeException {

    private final String reason;
    private final int line;
    private final int column;
    private final boolean unsupported;

    public SqlSyntaxException(String message, int line, int column, boolean unsupported) {
        super(message);
        this.reason = message;
        this.line = line;
        this.column = column;
        this.unsupported = unsupported;
    }

    /** Text that is not Postgres SQL. */
    public SqlSyntaxException(String message, SqlAst.Pos pos) {
        this(message, pos.line(), pos.column(), false);
    }

    /** Postgres SQL this reader does not accept yet: the input may be fine, the feature is not built. */
    public static SqlSyntaxException unsupported(String message, SqlAst.Pos pos) {
        return new SqlSyntaxException(message, pos.line(), pos.column(), true);
    }

    /** The message, never null (unlike {@link #getMessage()}'s declared type). */
    public String reason() {
        return reason;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    /** True when the text is Postgres SQL this reader does not accept yet; false when it is not SQL at all. */
    public boolean unsupported() {
        return unsupported;
    }
}
