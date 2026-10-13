// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlUnion;
import com.legend.sql.SqlWith;

import java.util.ArrayList;
import java.util.List;

/**
 * A stored Variant column leaving the STATEMENT whole is read as JSON whatever the shape of the query
 * (docs/VARIANT_STORAGE_CENSUS_2026_09_27.md, D2, and its 2026-10-12 addendum). The read is
 * {@link DuckDb#columnRef}'s {@code CAST(col AS JSON)}, which fires on a stored Variant column REFERENCE; a star
 * renders none -- a star frame ({@code SELECT *}: a bare source, {@code limit}, {@code filter} and {@code sort} keep
 * one to the statement's end) and a star projection ({@code SELECT t0.*, x}: {@code extend}) alike -- so a native
 * STRUCT left the query as stored (the DataCube line's finding, 2026-10-12). At the statement's root, every star
 * standing for a source that holds a stored Variant column is spelled into that source's columns ({@link Stars},
 * as {@link StoredReads} spells a star over a read table): the Variant ones read through the hook
 * ({@code CAST(t0.V AS JSON) AS V}), every other column a bare reference, a join's sides each in place. A UNION's
 * branches and a WITH's body are roots too (as {@link RootNumericTypes} reads them). Root only, only then: a star
 * below the root feeds a reference the hook already reads, and no other SQL changes (the lean-SQL tenet).
 */
final class VariantEgress extends SqlRewriter {

    @Override
    public SqlQuery rewriteRoot(SqlQuery q) {
        return switch (q) {
            case SqlSelect s -> spelled(s);
            case SqlUnion u -> {
                List<SqlQuery> branches = new ArrayList<>(u.branches().size());
                u.branches().forEach(b -> branches.add(rewriteRoot(b)));
                yield new SqlUnion(branches, u.all(), u.outputs());
            }
            case SqlWith w -> SqlWith.prepend(w.ctes(), rewriteRoot(w.body()), w.recursive());
        };
    }

    /** {@code s} with each star over a stored Variant column spelled into columns; {@code s} itself when none. */
    private static SqlSelect spelled(SqlSelect s) {
        List<SqlSource> leaves = Stars.sources(s.from());
        if (s.projections().isEmpty()) {
            return holdsStoredVariant(leaves, null)
                    ? s.withProjections(Stars.columns(leaves, null, List.of(), e -> e)) : s;
        }
        List<SqlSelect.Projection> ps = new ArrayList<>(s.projections().size());
        boolean any = false;
        for (SqlSelect.Projection p : s.projections()) {
            String table;
            List<String> except;
            if (p.expr() instanceof SqlExpr.Star st) {
                table = st.table();
                except = List.of();
            } else if (p.expr() instanceof SqlExpr.StarExcept se) {
                table = se.table();
                except = se.except();
            } else {
                ps.add(p);
                continue;
            }
            if (holdsStoredVariant(leaves, table)) {
                ps.addAll(Stars.columns(leaves, table, except, e -> e));
                any = true;
            } else {
                ps.add(p);
            }
        }
        return any ? s.withProjections(ps) : s;
    }

    /** Whether the sources a star stands for ({@code table}'s, or every one) deliver a stored Variant column. */
    private static boolean holdsStoredVariant(List<SqlSource> leaves, @com.legend.base.Nullable String table) {
        for (SqlSource l : leaves) {
            if (l instanceof SqlSource.Dual || table != null && !table.equals(l.alias())) {
                continue;
            }
            if (l.outputs().stream().anyMatch(DuckDb::storedVariant)) {
                return true;
            }
        }
        return false;
    }
}
