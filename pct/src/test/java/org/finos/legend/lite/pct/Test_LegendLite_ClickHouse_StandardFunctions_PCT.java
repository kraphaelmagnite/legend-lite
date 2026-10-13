// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct;

import junit.framework.Test;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.pure.code.core.CoreStandardFunctionsCodeRepositoryProvider;
import org.finos.legend.pure.m3.pct.reports.config.PCTReportConfiguration;
import org.finos.legend.pure.m3.pct.reports.config.exclusion.ExclusionSpecification;
import org.finos.legend.pure.m3.pct.reports.model.Adapter;
import org.finos.legend.pure.m3.pct.shared.model.ReportScope;
import org.finos.legend.pure.runtime.java.interpreted.testHelper.PureTestBuilderInterpreted;
import static org.finos.legend.engine.test.shared.framework.PureTestHelperFramework.wrapSuite;

/**
 * The standard PCT suite on ClickHouse (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §19): the SAME tests as
 * {@link Test_LegendLite_StandardFunctions_PCT}, through the same adapter, on the embedded ClickHouse 25.8
 * ({@code LEGENDLITE_PCT_BACKEND=clickhouse}, pct/BUILD.bazel), with ClickHouse's OWN expected failures -- legend-engine's
 * shape, as {@link Test_LegendLite_Postgres_StandardFunctions_PCT}: every expected failure named and pinned by the text
 * it fails with, and a pinned test that starts passing fails the run, so a fix is recorded by deleting its row.
 */
public class Test_LegendLite_ClickHouse_StandardFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = CoreStandardFunctionsCodeRepositoryProvider.standardFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    // First measured 2026-10-12 (ClickHouse 25.8, the pinned server).
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // CLICKHOUSE'S OWN LIMIT: its dates are 1900 to 2299 (Date32, DateTime64; a date past either end is
            // clamped, silently, measured): the dialect refuses a literal outside them
            one("meta::pure::functions::boolean::tests::inequalities::between::testBetween_DateTime_Function_1__Boolean_1_", "\"the date 1032-01-01 00:32:34.000000 reached ClickHouse, whose dates are 1900 to 2299 (it clamps a date past either end)\""),
            one("meta::pure::functions::boolean::tests::inequalities::between::testBetween_Date_Function_1__Boolean_1_", "\"the date 1032-01-01 00:32:34.000000 reached ClickHouse, whose dates are 1900 to 2299 (it clamps a date past either end)\""),
            one("meta::pure::functions::boolean::tests::inequalities::between::testBetween_StrictDate_Function_1__Boolean_1_", "\"the date 1032-01-01 reached ClickHouse, whose dates are 1900 to 2299 (it clamps a date past either end)\""),
            // CLICKHOUSE'S OWN LIMIT: a quantile's level is a parameter, a constant (quantile(0.5)(x)): a level from
            // a column is refused
            one("meta::pure::functions::math::tests::percentile::testPercentile_Function_1__Boolean_1_", "\"a quantile whose level is no literal number reached ClickHouse\"")
    );

    public static Test suite() {
        return PctCensusGate.wrap("Standard", wrapSuite(
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
