// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.m3.PlatformCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The essential PCT suite on Trino (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §18): the SAME tests as
 * {@link Test_LegendLite_EssentialFunctions_PCT}, through the same adapter, on the embedded Trino 483
 * ({@code LEGENDLITE_PCT_BACKEND=trino}, pct/BUILD.bazel), with Trino's OWN expected failures -- legend-engine's
 * shape, as {@link Test_LegendLite_Postgres_EssentialFunctions_PCT}: every expected failure named and pinned by the text
 * it fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_Trino_EssentialFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = PlatformCodeRepositoryProvider.essentialFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-12 (Trino 483, the pinned server).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // INSTANCE IDENTITY (as on DuckDB): the asserts want the interpreter's ORIGINAL instances back, or eq/==
            // by reference; the harness inlines captured instances BY VALUE, so identity is erased before the wire
            one("meta::pure::functions::collection::tests::find::testFindInstance_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::find::testFindUsingVarForFunction_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            one("meta::pure::functions::collection::tests::head::testHeadComplex_Function_1__Boolean_1_", "instanceOf meta::pure::functions::collection::tests::model::CO_Person"),
            // THE PLATFORM'S OWN (each pinned on the DuckDB lane too, by the same cause): name resolution, lowering
            // and harness limits, the 0-based indexOf, error positions and messages
            one("meta::pure::functions::collection::tests::at::testAtError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 37\""),
            one("meta::pure::functions::collection::tests::fold::testFoldFiltering_Function_1__Boolean_1_", "\"'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::fold::testFoldToMany_Function_1__Boolean_1_", "\"'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::fold::testFold_Function_1__Boolean_1_", "\"'lastName' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name\""),
            one("meta::pure::functions::collection::tests::indexof::testIndexOfOneElement_Function_1__Boolean_1_", "\"\nexpected: 0\nactual:   1\""),
            one("meta::pure::functions::date::tests::testAdjustByDaysBigNumber_Function_1__Boolean_1_", "Value 12345695772 exceeds MAX_INT for type date\""),
            one("meta::pure::functions::date::tests::testAdjustByHoursBigNumber_Function_1__Boolean_1_", "long overflow\""),
            one("meta::pure::functions::date::tests::testAdjustByMonthsBigNumber_Function_1__Boolean_1_", "Magnitude of add amount is too large: 9600000000\""),
            one("meta::pure::functions::date::tests::testAdjustByWeeksBigNumber_Function_1__Boolean_1_", "Value 86419769244 exceeds MAX_INT for type date\""),
            one("meta::pure::functions::date::tests::testDayOfMonthError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testHourError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testMinuteError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::date::tests::testNewDateError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 29\""),
            one("meta::pure::functions::date::tests::testSecondError_Function_1__Boolean_1_", "\"Execution error column mismatch. Actual: 23 where expected: 36\""),
            one("meta::pure::functions::lang::tests::match::testMatchWithMixedReturnType_Function_1__Boolean_1_", "\"scalar lowering not yet implemented for TypedDeactivate\""),
            one("meta::pure::functions::math::tests::abs::testBigFloatAbs_Function_1__Boolean_1_", "\"\nexpected: 123456789123456789.99\nactual:   123456789123456780.0\""),
            one("meta::pure::functions::math::tests::trigonometry::testArcCosineError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Infinite or NaN\"\nwhere the expected message was:\"Unable to compute acos of 2.0\"\""),
            one("meta::pure::functions::math::tests::trigonometry::testArcSineError_Function_1__Boolean_1_", "\"Execution error message mismatch.\nThe actual message was \"Infinite or NaN\"\nwhere the expected message was:\"Unable to compute asin of 2.0\"\""),
            one("meta::pure::functions::string::tests::indexOf::testFromIndex_Function_1__Boolean_1_", "\"\nexpected: 1\nactual:   2\""),
            one("meta::pure::functions::string::tests::indexOf::testSimple_Function_1__Boolean_1_", "\"\nexpected: 4\nactual:   5\""),
            one("meta::pure::functions::string::tests::toString::testComplexClassToString_Function_1__Boolean_1_", "line 1:33: Column reference '__id' is invalid\""),
            // TRINO'S OWN LIMIT: a ROW's fields are typed, and an unset property is a NULL of no type (no sibling
            // types it); were it typed, a ROW holding a NULL compares NULL and contains refuses one (measured), where
            // Pure's equality of two such instances is true
            one("meta::pure::functions::collection::tests::contains::testContainsNonPrimitive_Function_1__Boolean_1_", "\"a struct field of unknown type reached Trino: a ROW's fields are typed\"")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Essential", wrapSuite(
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
