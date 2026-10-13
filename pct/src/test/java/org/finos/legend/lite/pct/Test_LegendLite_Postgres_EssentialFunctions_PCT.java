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
 * The essential PCT suite on Postgres 16 (leg P2, docs/POSTGRES_DIALECT_HOMEWORK_2026_10_01.md Q5): the
 * SAME tests as {@link Test_LegendLite_EssentialFunctions_PCT}, through the same adapter, on this JVM's
 * embedded Postgres ({@code LEGENDLITE_PCT_BACKEND=postgres}, pct/BUILD.bazel), with Postgres's OWN
 * expected failures -- legend-engine's shape, as {@link Test_LegendLite_H2_RelationFunctions_PCT}: every
 * expected failure named and pinned by the text it fails with, and a pinned test that starts passing fails
 * the run, so a fix is recorded by deleting its row.
 *
 * <p>First measured 2026-10-02 on Postgres 16.15 (103 rows): the rows are grouped by why -- wrong answers
 * (dialect defects, fixed first), Postgres's own errors, the collections and Variant of leg P4, and
 * constructs the dialect refuses by name.
 */
public class Test_LegendLite_Postgres_EssentialFunctions_PCT extends PCTReportConfiguration {

    private static final ReportScope reportScope = PlatformCodeRepositoryProvider.essentialFunctions;
    private static final Adapter adapter = LegendLitePCTReportProvider.LegendLiteAdapter;
    private static final String platform = "interpreted";

    // Pinned by a STABLE part of the message (the runner matches by containment): no per-run id.
    private static final MutableList<ExclusionSpecification> expectedFailures = Lists.mutable.with(
            // Float is a double (numeric charter Rule 2) -- deliberate, on every dialect
            one("meta::pure::functions::collection::tests::indexof::testIndexOfOneElement_Function_1__Boolean_1_", "expected: 0\nactual:   1"),
            one("meta::pure::functions::math::tests::abs::testBigFloatAbs_Function_1__Boolean_1_", "expected: 123456789123456789.99\nactual:   123456789123456780.0"),
            one("meta::pure::functions::string::tests::indexOf::testFromIndex_Function_1__Boolean_1_", "expected: 1\nactual:   2"),
            one("meta::pure::functions::string::tests::indexOf::testSimple_Function_1__Boolean_1_", "expected: 4\nactual:   5"),
            // Postgres raises differently (its own error, or a different position or wording)
            one("meta::pure::functions::collection::tests::at::testAtError_Function_1__Boolean_1_", "Execution error column mismatch. Actual: 23 where expected: 37"),
            one("meta::pure::functions::date::tests::testAdjustByDaysBigNumber_Function_1__Boolean_1_", "ERROR: interval out of range"),
            one("meta::pure::functions::date::tests::testAdjustByHoursBigNumber_Function_1__Boolean_1_", "ERROR: interval out of range"),
            one("meta::pure::functions::date::tests::testAdjustByMinutesBigNumber_Function_1__Boolean_1_", "ERROR: timestamp out of range"),
            one("meta::pure::functions::date::tests::testAdjustByMonthsBigNumber_Function_1__Boolean_1_", "ERROR: interval out of range"),
            one("meta::pure::functions::date::tests::testAdjustByWeeksBigNumber_Function_1__Boolean_1_", "ERROR: interval out of range"),
            one("meta::pure::functions::date::tests::testDayOfMonthError_Function_1__Boolean_1_", "Execution error column mismatch. Actual: 23 where expected: 36"),
            one("meta::pure::functions::date::tests::testHourError_Function_1__Boolean_1_", "Execution error column mismatch. Actual: 23 where expected: 36"),
            one("meta::pure::functions::date::tests::testMinuteError_Function_1__Boolean_1_", "Execution error column mismatch. Actual: 23 where expected: 36"),
            one("meta::pure::functions::date::tests::testNewDateError_Function_1__Boolean_1_", "Execution error column mismatch. Actual: 23 where expected: 29"),
            one("meta::pure::functions::date::tests::testSecondError_Function_1__Boolean_1_", "Execution error column mismatch. Actual: 23 where expected: 36"),
            one("meta::pure::functions::math::tests::trigonometry::testArcCosineError_Function_1__Boolean_1_", "Execution error message mismatch.\nThe actual message was \"Infinite or NaN"),
            one("meta::pure::functions::math::tests::trigonometry::testArcSineError_Function_1__Boolean_1_", "Execution error message mismatch.\nThe actual message was \"Infinite or NaN"),
            one("meta::pure::functions::string::tests::toString::testPairCollectionToString_Function_1__Boolean_1_", "ERROR: could not determine polymorphic type because input has type unknown"),
            // collections over the jsonb carrier: leg P4
            one("meta::pure::functions::collection::tests::drop::testDropNegativeOnEmptyList_Function_1__Boolean_1_", "UNNEST over Bottom[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::fold::testFoldCollectionAccumulator_Function_1__Boolean_1_", "a fold into a Typed[type=Array[element=BIGINT], nullable=false] reached Postgres: a list accumulator is the jsonb carrier (leg P4)"),
            one("meta::pure::functions::collection::tests::fold::testFoldEmptyListAndEmptyIdentity_Function_1__Boolean_1_", "LIST_CONCAT over Bottom[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::forall::testforAllOnEmptySet_Function_1__Boolean_1_", "exists/forAll over Bottom[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::get::testGet_Function_1__Boolean_1_", "MAP_EXTRACT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::head::testHeadOnEmptySet_Function_1__Boolean_1_", "LIST_GET over Bottom[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::keys::testKeys_Function_1__Boolean_1_", "a cast to Map[key=VARCHAR, value=BIGINT] reached Postgres before the jsonb collection carrier (leg P4)"),
            one("meta::pure::functions::collection::tests::put::testPut_addsEntry_Function_1__Boolean_1_", "MAP_CONCAT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::put::testPut_emptyMap_Function_1__Boolean_1_", "MAP_CONCAT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::put::testPut_overridesEntry_Function_1__Boolean_1_", "MAP_CONCAT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::putAll::testPutAll_emptyInputMap_Function_1__Boolean_1_", "MAP_CONCAT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::putAll::testPutAll_emptyPutEntries_Function_1__Boolean_1_", "MAP_CONCAT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::putAll::testPutAll_overridesExistingAndAddNew_Function_1__Boolean_1_", "MAP_CONCAT reached Postgres: collections over the jsonb carrier are leg P4"),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesPrimitiveNonStandardFunction_Function_1__Boolean_1_", "UNNEST over Unknown[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesPrimitiveStandardFunctionExplicit_Function_1__Boolean_1_", "UNNEST over Unknown[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::sort::testMixedSortNoComparator_Function_1__Boolean_1_", "UNNEST over Typed[type=VARCHAR, nullable=true] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::take::testTakeNegativeOnEmptyList_Function_1__Boolean_1_", "UNNEST over Bottom[] reached Postgres: a list of unknown element type"),
            one("meta::pure::functions::collection::tests::values::testValues_Function_1__Boolean_1_", "a cast to Map[key=VARCHAR, value=BIGINT] reached Postgres before the jsonb collection carrier (leg P4)"),
            // refused by name: a construct the Postgres dialect does not spell yet
            one("meta::pure::functions::collection::tests::concatenate::testConcatenateTypeInference_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=ClassType[fqn=meta::pure::functions::collection::tests::model::CO_GeographicEntity])"),
            one("meta::pure::functions::collection::tests::find::testFindInstance_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=ClassType[fqn=meta::pure::functions::collection::tests::model::CO_Person])"),
            one("meta::pure::functions::collection::tests::find::testFindUsingVarForFunction_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=ClassType[fqn=meta::pure::functions::collection::tests::model::CO_Person])"),
            one("meta::pure::functions::collection::tests::fold::testFoldEmptyListAndNonEmptyIdentity_Function_1__Boolean_1_", "a cast of Bottom[] to Array[element=BIGINT] reached Postgres"),
            one("meta::pure::functions::collection::tests::fold::testFoldFiltering_Function_1__Boolean_1_", "'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name"),
            one("meta::pure::functions::collection::tests::fold::testFoldToMany_Function_1__Boolean_1_", "'otherNames' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name"),
            one("meta::pure::functions::collection::tests::fold::testFold_Function_1__Boolean_1_", "'lastName' is not a known class, mapping, runtime, connection, or database — user elements in a query need a fully qualified name"),
            one("meta::pure::functions::collection::tests::head::testHeadComplex_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=ClassType[fqn=meta::pure::functions::collection::tests::model::CO_Firm])"),
            one("meta::pure::functions::collection::tests::removeDuplicates::testRemoveDuplicatesEmptyListExplicit_Function_1__Boolean_1_", "a cast of Bottom[] to Array[element=VARCHAR] reached Postgres"),
            one("meta::pure::functions::collection::tests::zip::testZipBothListsAreOfPairs_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments=[GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments="),
            one("meta::pure::functions::collection::tests::zip::testZipBothListsSameLength_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments=[INTEGER, STRING], multArguments=[]])"),
            one("meta::pure::functions::collection::tests::zip::testZipFirstListLonger_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments=[INTEGER, STRING], multArguments=[]])"),
            one("meta::pure::functions::collection::tests::zip::testZipFirstListsIsOfPairs_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments=[GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments="),
            one("meta::pure::functions::collection::tests::zip::testZipSecondListLonger_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments=[INTEGER, STRING], multArguments=[]])"),
            one("meta::pure::functions::collection::tests::zip::testZipSecondListsIsOfPairs_Function_1__Boolean_1_", "no typed conversion for org.postgresql.util.PGobject (type=GenericType[rawFqn=meta::pure::functions::collection::Pair, arguments=[INTEGER, GenericType[rawFqn=meta::pure::functions::collection::Pair, a"),
            one("meta::pure::functions::lang::tests::match::testMatchWithMixedReturnType_Function_1__Boolean_1_", "scalar lowering not yet implemented for TypedDeactivate"),
            one("meta::pure::functions::string::tests::format::testFormatBoolean_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatDate_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatFloatWithRounding_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatFloatWithTruncation_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatFloatWithZeroPadding_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatFloat_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatIntegerWithZeroPadding_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatInteger_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatList_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatPair_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatRepr_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testFormatString_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::format::testSimpleFormatDate_Function_1__Boolean_1_", "FORMAT reached Postgres: Postgres' format() has no %d/%f"),
            one("meta::pure::functions::string::tests::joinStrings::testJoinStringsNoStrings_Function_1__Boolean_1_", "a cast of Bottom[] to Array[element=VARCHAR] reached Postgres"),
            one("meta::pure::functions::string::tests::toString::testComplexClassToString_Function_1__Boolean_1_", "a field '__id' of unknown type reached Postgres")
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
