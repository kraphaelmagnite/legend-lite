// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.code.core.RelationCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The relation PCT suite on ClickHouse (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19): the SAME tests as
 * {@link Test_LegendLite_RelationFunctions_PCT}, through the same adapter, on the embedded ClickHouse 25.8
 * ({@code LEGENDLITE_PCT_BACKEND=clickhouse}, pct/BUILD.bazel), with ClickHouse's OWN expected failures -- legend-engine's
 * shape, as {@link Test_LegendLite_Postgres_RelationFunctions_PCT}: every expected failure named and pinned by the text
 * it fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_ClickHouse_RelationFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = RelationCodeRepositoryProvider.relationFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-12 (ClickHouse 25.8, the pinned server).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // THE PLATFORM'S OWN (each pinned on the DuckDB lane too, by the same cause): name resolution, lowering
            // and harness limits, the 0-based indexOf, error positions and messages
            one("meta::pure::functions::relation::tests::composition::testVariantArrayColumn_joinStrings_Function_1__Boolean_1_", "\"\nexpected: '#TDS\n   id,payload,joined\n   1,\\'[1,2,3]\\',1,2,3\n   2,\\'[4,5,6]\\',4,5,6\n   3,\\'[7,8,9]\\',7,8,9\n   4,\\'null\\',\n#'\nactual:   '#TDS\n   id,payload,joined\n   1,\\'[1,2,3]\\',1,2,3\n   2,\\'[4,5,6]\\',4,5,6\n   3,\\'[7,8,9]\\',7,8,9\n   4,\\'null\\',null\n#'\""),
            // CLICKHOUSE'S OWN LIMIT: a Tuple's fields are typed and a Tuple is never NULL (measured): an unset
            // property is a NULL of no type, and an empty untyped list (Nullable(Nothing)) has no Tuple to zip or
            // project into
            one("meta::pure::functions::relation::tests::composition::testCoalesceInPreFilter_Function_1__Boolean_1_", "\"a struct field of unknown type reached ClickHouse: a Tuple's fields are typed\""),
            one("meta::pure::functions::relation::tests::project::testSimpleProjectWithEmpty_Function_1__Boolean_1_", "\"Code: 43. DB::Exception: First argument for function tupleElement must be tuple or array of tuple. Actual Nullable(Nothing)"),
            // CLICKHOUSE'S OWN LIMIT: a list is never NULL (Nullable(Array) is refused, measured): a JSON null read
            // as a list is the empty list, which toVariant writes as [] where Pure writes null
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_distinct_removeDuplicates_Function_1__Boolean_1_", "\"\nexpected: '#TDS\n   id,payload,distinct,removeDuplicates\n   1,\\'[1,2,3]\\',\\'[1,2,3]\\',\\'[1,2,3]\\'\n   2,\\'[1,2,3,4,4,3,2,3,4,1,2]\\',\\'[1,2,3,4]\\',\\'[1,2,3,4]\\'\n   3,\\'null\\',\\'null\\',\\'null\\'\n#'\nactual:   '#TDS\n   id,payload,distinct,removeDuplicates\n   1,\\'[1,2,3]\\',\\'[1,2,3]\\',\\'[1,2,3]\\'\n   2,\\'[1,2,3,4,4,3,2,3,4,1,2]\\',\\'[1,2,3,4]\\',\\'[1,2,3,4]\\'\n   3,\\'null\\',\\'[]\\',\\'[]\\'\n#'\""),
            one("meta::pure::functions::relation::tests::composition::testVariantColumn_slice_Function_1__Boolean_1_", "\"\nexpected: '#TDS\n   id,payload,fromRow,toRow,sliced\n   0,\\'null\\',1,2,\\'null\\'\n   1,\\'[1,2,3,4,5,6]\\',2,3,\\'[3]\\'\n   2,\\'[1,2,3,4,5,6]\\',0,4,\\'[1,2,3,4]\\'\n   3,\\'[1,2,3,4,5,6]\\',-3,-1,\\'[]\\'\n#'\nactual:   '#TDS\n   id,payload,fromRow,toRow,sliced\n   0,\\'null\\',1,2,\\'[]\\'\n   1,\\'[1,2,3,4,5,6]\\',2,3,\\'[3]\\'\n   2,\\'[1,2,3,4,5,6]\\',0,4,\\'[1,2,3,4]\\'\n   3,\\'[1,2,3,4,5,6]\\',-3,-1,\\'[]\\'\n#'\""),
            // CLICKHOUSE'S OWN LIMIT: a join's ON needs an equality between its two sides (an as-of join one besides
            // its inequality; a condition on one side alone is refused, and an outer join's condition reading both
            // sides outside an equality is refused under join_use_nulls, which SQL's NULLs for its missing side
            // need), a correlated subquery is refused as IN's argument, and there is no LATERAL join (measured)
            one("meta::pure::functions::relation::tests::asOfJoin::testSimpleAsOfJoin_Function_1__Boolean_1_", "\"Code: 62. DB::Exception: ASOF join needs at least one equi-join column. (SYNTAX_ERROR)"),
            one("meta::pure::functions::relation::tests::asOfJoin::testSimpleAsOfJoin_MultipleExpressions_Function_1__Boolean_1_", "\"Code: 62. DB::Exception: ASOF join needs at least one equi-join column. (SYNTAX_ERROR)"),
            one("meta::pure::functions::relation::tests::composition::TestJoin_CurrentUserId_Function_1__Boolean_1_", "\"Code: 403. DB::Exception: Cannot determine join keys in JOIN ON expression"),
            one("meta::pure::functions::relation::tests::composition::testNestedJoinArithmeticComparisonExpression_Function_1__Boolean_1_", "\"Code: 403. DB::Exception: LEFT JOIN"),
            one("meta::pure::functions::relation::tests::in::testIn_CorrelatedSubQuery_Function_1__Boolean_1_", "\"Code: 48. DB::Exception: Correlated subqueries are not supported as IN function arguments yet"),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoinAreInnerJoins_Function_1__Boolean_1_", "\"a LATERAL join reached ClickHouse, which has none (a source correlated with the one before it)\""),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoin_Chained_Function_1__Boolean_1_", "\"a LATERAL join reached ClickHouse, which has none (a source correlated with the one before it)\""),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoin_CorrelatedColumnNameAlsoOnInner_Function_1__Boolean_1_", "\"a LATERAL join reached ClickHouse, which has none (a source correlated with the one before it)\""),
            one("meta::pure::functions::relation::tests::lateral::testLateralJoin_Function_1__Boolean_1_", "\"a LATERAL join reached ClickHouse, which has none (a source correlated with the one before it)\""),
            // CLICKHOUSE'S OWN LIMIT: a RANGE frame's offsets are non-negative integers (measured): a Float offset is
            // refused
            one("meta::pure::functions::relation::tests::over::testRange_WithNumbers_CurrentRow_NFollowing_WithoutPartition_WithSingleOrderBy_Function_1__Boolean_1_", "\"Code: 36. DB::Exception: Frame end offset for 'RANGE' frame must be a nonnegative 32-bit integer, '2.1' of type 'Float64' given. (BAD_ARGUMENTS)"),
            one("meta::pure::functions::relation::tests::over::testRange_WithNumbers_NFollowing_NFollowing_WithoutPartition_WithSingleOrderBy_Function_1__Boolean_1_", "\"Code: 36. DB::Exception: Frame start offset for 'RANGE' frame must be a nonnegative 32-bit integer, '0.5' of type 'Float64' given. (BAD_ARGUMENTS)"),
            one("meta::pure::functions::relation::tests::reduce::testRange_WithNumbers_CurrentRow_NFollowing_WithoutPartition_WithSingleOrderBy_Function_1__Boolean_1_", "\"Code: 36. DB::Exception: Frame end offset for 'RANGE' frame must be a nonnegative 32-bit integer, '2.1' of type 'Float64' given. (BAD_ARGUMENTS)"),
            one("meta::pure::functions::relation::tests::reduce::testRange_WithNumbers_NFollowing_NFollowing_WithoutPartition_WithSingleOrderBy_Function_1__Boolean_1_", "\"Code: 36. DB::Exception: Frame start offset for 'RANGE' frame must be a nonnegative 32-bit integer, '0.5' of type 'Float64' given. (BAD_ARGUMENTS)")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Relation", wrapSuite(
                () -> true,
                () -> PureTestBuilderInterpreted.buildPCTTestSuite(reportScope, expectedFailures, adapter),
                () -> false,
                Lists.mutable.empty()));
    }

    @Override
    public MutableList<ExclusionSpecification> expectedFailures() {
        return expectedFailures;
    }

    @Override
    public ReportScope getReportScope() {
        return reportScope;
    }

    @Override
    public Adapter getAdapter() {
        return adapter;
    }

    @Override
    public String getPlatform() {
        return platform;
    }
}
