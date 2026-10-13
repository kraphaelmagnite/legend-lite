# The primitives the relational corpus needs, against what PCT runs on each database (2026-10-12)

Measured at c9d88f1 plus the per-test attribution, with the primitive census (`exec/PrimitiveCensus`,
`-Dlegend.diagnostics=primitives`; the recipe: `tools/census/README.md` section 3). A **primitive** is what a dialect
must spell, read off each plan before any dialect rewrites it: a function and its arity (past 3 folded), an
aggregate, a window function and its frame's shape, a cast (from -> to), a literal kind, a source, a join kind, a
clause. The corpus side is the whole DuckDB host pass (2,613 tests, 2 minutes); the database side is the five PCT
suites on each database (H2: the relation suite only), every lane green with the census on.

## What it says

**244 primitives** reach the database in a statement that succeeds in the corpus. On a database, a primitive is
`ok` when a PCT statement carrying it succeeded there, `FAIL` when it appeared only in failing statements, `--` when
no PCT statement carrying it reached the database (none was made, or the dialect refused it before sending).

**48 are never exercised by PCT on any database** -- every lane's gap starts with these:

- graph fetch and serialization: `expr.JsonObject`, `expr.JsonArray`, `expr.JsonArrayAgg`, `fn.JSON_MERGE_PATCH`
  (2, 3, >3), `fn.JSON_PRETTY`, `fn.STRUCT_INSERT`, `fn.TYPEOF`, `cast.?->JSON`, `cast.JSON->JSON`,
  `cast.JSON->TIMESTAMP`;
- checked graph fetch (constraints): `expr.CheckedOne`, `expr.CheckedDefects`, `expr.CheckedChildValue`;
- a relation's row order: `expr.RowOrder`;
- aggregates: `agg.BOOL_AND`, `agg.BOOL_OR`, `agg.COUNT.distinct`, `agg.QUANTILE_CONT`, `agg.QUANTILE_DISC.ordered`,
  `window.QUANTILE_DISC`;
- list functions: `fn.LIST_BOOL_AND`, `fn.LIST_BOOL_OR`, `fn.LIST_FLATTEN`, `fn.LIST_PRODUCT`, `fn.LIST_ZIP` (2, 3);
- dates: `fn.NOW`, `fn.TODAY`, `fn.DATE_TRUNC(2)`, `fn.ADD_INTERVAL_TEMPORAL`, `fn.STRPTIME`;
- text: `fn.CONCAT_JOIN` (2, 3, >3);
- casts: `cast.DATE->DATE`, `cast.DATE->VARCHAR`, `cast.TIMESTAMP->VARCHAR`, `cast.HUGEINT->DOUBLE`,
  `cast.VARCHAR->DECIMAL_TEXT`, `cast.DECIMAL_TEXT->VARCHAR`, `cast.TEMPORAL_TEXT->VARCHAR` (in PCT only inside
  statements that fail, on every database);
- shapes: `fn.AND(>3)`, `fn.IN(2)` (a one-value IN), `select.orderBy.desc` with no NULLS clause, a DISTINCT `union`,
  `source.RawSql` (a model's raw SQL).

**Each database's own, past those 48:**

| database | more gaps | which |
|---|---|---|
| DuckDB, MariaDB, MySQL, Databricks, Snowflake, BigQuery | 0 | |
| Postgres | 4 | `agg.LIST`, `fn.FORMAT(2)`, `fn.FORMAT(3)` never reach it; `fn.JSON_TYPE` only in failing statements |
| SQLite | 3 | `agg.LIST`, `fn.MD5`; `cast.BIGINT->HUGEINT` only in failing statements |
| Trino, ClickHouse | 1 | `source.Table` (no session's own table: the system database's tests are pinned) |
| H2 | 104 | it runs the relation suite only |

## Closing them: the covering subset

Many of the 48 come only from what a model makes -- mappings, stores, graph fetch, constraints -- so no PCT-style
expression over a literal relation reaches them. The corpus does: **77 corpus tests carry all 244 primitives**
(131 carry each twice; `primitive_cover.py`). Run on a database, those 77 exercise everything the corpus needs
there. That needs the corpus harness to run on any database, the same harness the corpus on the clouds needs
(`docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §20).

The 77, greedy order (each with the primitives it adds):

```
# 77 tests carry every primitive 1x
meta::pure::tds::tests::extensions::rowValueDifferenceTest	+58
meta::relational::graphFetch::tests::simple::testObjectReferenceInUsingResultReferences	+23
meta::relational::tests::mapping::dataType::testSimpleTypeMappingProject	+13
meta::relational::graphFetch::tests::subType::testSubTypeAtRootLevelWithInheritanceMapping	+11
meta::relational::validation::showcase::standalone::validateAllConstraintsWithInnerJoinInFilter	+9
meta::relational::tests::tds::groupBy::simpleGroupByAverage	+8
meta::pure::executionPlan::tests::datetime::testPlanForDateTimeVariableESTTimeZone	+7
meta::relational::tests::functions::pureToSqlQuery::calendarAggregations::testAnnualizedDateRange	+6
meta::pure::graphFetch::tests::XStoreUnion::inMemoryAndRelational::testSimpleUnionOnMultipleSetsCrossStore	+5
meta::relational::graphFetch::tests::simple::testCheckedWithCircularConstraints	+5
meta::relational::tests::query::filter::exists::testExistsWithAttributesFromLeftInAndCondition	+4
meta::relational::tests::projection::filter::dates::recent::testPreviousDayOfWeek	+3
meta::relational::tests::aggregationAware::testRewrite::objectGroupBy::testRewriteWithTDSJoin	+3
meta::relational::tests::typeInference::testSQLNullTypeInference	+3
meta::pure::tds::tests::extensions::testExtendDigest_InMemory	+3
meta::relational::tests::groupBy::testGroupByPercentile	+3
meta::relational::tests::milestoning::snapshot::testDateTimeVariableMilestoningParamPropagation	+3
meta::relational::functions::toPostgresModel::tests::testConvertTable	+3
meta::relational::tests::projection::function::date::testDateDiffZeroToOne	+3
meta::relational::tests::query::paginate::testPaginated	+3
meta::relational::tests::tds::tdsRestrictDistinct::testRestrictDistinct_NoOptimization_WindowColumns	+2
meta::relational::tests::mapping::sqlFunction::round::testFilter	+2
meta::relational::tests::mapping::sqlFunction::isNumeric::testProject	+2
meta::relational::tests::mapping::sqlFunction::rem::testFilter	+2
meta::relational::tests::query::function::testJoinStringFunction	+2
meta::relational::tests::mapping::sqlFunction::parseDecimal::testToSQLStringParseDecimalSQLCompilationinH2	+2
meta::relational::tests::tds::tdsWindow::testPercentileWindowFunction	+2
meta::relational::graphFetch::tests::simple::testRelationalGraphFetchWithAlloySerializationConfig	+2
meta::relational::tests::mapping::propertyfunc::withjoin::testSimplePropertyFuncProjectWithJoin	+2
meta::relational::tests::mapping::embedded::advanced::testGroupBy	+2
meta::relational::tests::mapping::dates::datetime::testQuery	+2
meta::relational::tests::mapping::sqlFunction::stringToFloat::testProject	+1
meta::relational::tests::projection::filter::dates::firstDay::testFirstDayOfYear	+1
meta::relational::tests::mapping::in::testJoinWithPrefixInClauseContainingNumberLiterals	+1
meta::relational::tests::mapping::sqlFunction::stddev::sample::testProject	+1
meta::relational::tests::mapping::sqlFunction::stddev::population::testProject	+1
meta::relational::tests::functions::fetchDbMetaData::testFetchDbPrimaryKeysMetaData	+1
meta::relational::tests::tds::tdsExtend::testExtendsWithInClause	+1
meta::relational::tests::mapping::sqlFunction::sin::testFilter	+1
meta::relational::tests::mapping::sqlFunction::log::testFilter	+1
meta::relational::tests::mapping::sqlFunction::floor::testProject	+1
meta::relational::tests::mapping::sqlFunction::ceiling::testProject	+1
meta::relational::tests::mapping::sqlFunction::atan2::testFilter	+1
meta::relational::validation::tests::milestoning::testAggregationOnRootClass	+1
meta::relational::tests::mapping::sqlFunction::tan::testProject	+1
meta::relational::tests::mapping::sqlFunction::pow::testFilter	+1
meta::relational::tests::mapping::sqlFunction::exp::testProject	+1
meta::relational::tests::mapping::sqlFunction::cos::testProject	+1
meta::relational::tests::mapping::sqlFunction::atan::testProject	+1
meta::relational::tests::tds::tdsUnion::testSimpleUnion	+1
meta::relational::tests::mapping::sqlFunction::acos::testFilter	+1
meta::relational::tests::tds::tdsFilter::testFilterMultipleExpressions2	+1
meta::relational::tests::mapping::sqlFunction::rtrim::testFilter	+1
meta::relational::tests::mapping::sqlFunction::right::testFilter	+1
meta::relational::tests::mapping::sqlFunction::ltrim::testFilter	+1
meta::relational::tests::mapping::sqlFunction::left::testFilter	+1
meta::relational::tests::mapping::sqlFunction::asin::testFilter	+1
meta::relational::tests::tds::tdsWindow::testDistinctAfterWindowFunction	+1
meta::relational::graphFetch::tests::milestoning::testDatePropagationFromMilestonedRootToMilestonedProperty_Checked	+1
meta::relational::tests::tds::tdsJoin::testFullOuterJoinSimple	+1
meta::relational::tests::query::function::testFilterUsingMatchesFunction	+1
meta::relational::tests::query::function::distinct::testCollectionDistinctFunction	+1
meta::relational::graphFetch::tests::resultSourcing::testSourcingJsonResultToQueryWithPureDateAsParam	+1
meta::relational::tests::query::function::log::testFilterUsingLog10Function	+1
meta::relational::tests::mapping::modelJoin::advanced::testChainedTwoHops	+1
meta::relational::tests::mapping::sqlFunction::parseDate::testToSQLStringParseDateForH2	+1
meta::relational::tests::mapping::relation::testMappingWithWindowColumn	+1
meta::relational::tests::query::function::sign::testFilterUsingSignFunction	+1
meta::relational::tests::mapping::union::specialUnion::testGroupBy	+1
meta::relational::tests::query::function::divide::testDivideFunctionPrecision	+1
meta::relational::tests::query::function::cos::testFilterUsingCotFunction	+1
meta::relational::tests::groupBy::testVarianceSample	+1
meta::relational::tests::groupBy::testVariancePopulation	+1
meta::relational::tests::mapping::sqlFunction::sqrt::testFilter	+1
meta::relational::tests::query::function::round::testFilterUsingRoundFunctionWithScale	+1
meta::relational::tests::tds::groupBy::simpleGroupByOr	+1
meta::relational::tests::tds::groupBy::simpleGroupByAnd	+1
```

## The map

| primitive | corpus ok | duckdb | postgres | sqlite | mariadb | mysql | trino | clickhouse | databricks | snowflake | bigquery | h2 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `agg.AVG` | 88 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.BOOL_AND` | 5 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `agg.BOOL_OR` | 5 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `agg.COUNT` | 615 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `agg.COUNT.distinct` | 45 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `agg.LIST` | 505 | ok | -- | -- | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.MAX` | 252 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.MIN` | 42 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.QUANTILE_CONT` | 7 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `agg.QUANTILE_DISC.ordered` | 6 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `agg.STDDEV_POP` | 5 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.STDDEV_SAMP` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.STRING_AGG` | 353 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `agg.STRING_AGG.ordered` | 261 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `agg.SUM` | 538 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `agg.VAR_POP` | 2 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `agg.VAR_SAMP` | 2 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.?->JSON` | 16 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.?->VARCHAR` | 66 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.Array->Array` | 112 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.BIGINT->BIGINT` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.BIGINT->DOUBLE` | 56 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.BIGINT->Decimal` | 8 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.BIGINT->HUGEINT` | 8 | ok | ok | FAIL | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.BIGINT->VARCHAR` | 1560 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.BOOLEAN->VARCHAR` | 136 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.DATE->DATE` | 46 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.DATE->VARCHAR` | 105 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.DECIMAL_TEXT->VARCHAR` | 7 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.DOUBLE->BIGINT` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.DOUBLE->DOUBLE` | 11 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.DOUBLE->Decimal` | 39 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.DOUBLE->HUGEINT` | 39 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.DOUBLE->VARCHAR` | 102 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.Decimal->DOUBLE` | 114 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.Decimal->Decimal` | 10 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.Decimal->HUGEINT` | 10 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.Decimal->VARCHAR` | 73 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.HUGEINT->DOUBLE` | 20 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.HUGEINT->VARCHAR` | 94 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.JSON->Array` | 1 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.JSON->BIGINT` | 18 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.JSON->JSON` | 2 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.JSON->TIMESTAMP` | 2 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.JSON->VARCHAR` | 1546 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.LITERAL->VARCHAR` | 110 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.TEMPORAL_TEXT->VARCHAR` | 108 | FAIL | FAIL | FAIL | FAIL | FAIL | FAIL | FAIL | FAIL | FAIL | FAIL | -- |
| `cast.TIMESTAMP->DATE` | 72 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.TIMESTAMP->TIMESTAMP` | 21 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.TIMESTAMP->VARCHAR` | 9 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.VARCHAR->BIGINT` | 1 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.VARCHAR->BOOLEAN` | 39 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.VARCHAR->DECIMAL_TEXT` | 7 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `cast.VARCHAR->DOUBLE` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.VARCHAR->Decimal` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.VARCHAR->JSON` | 255 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `cast.VARCHAR->TEMPORAL_TEXT` | 118 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `cast.VARCHAR->VARCHAR` | 4904 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.ArrayLit` | 1738 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.BoolLit` | 1021 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.Case` | 2359 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.CheckedChildValue` | 5 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.CheckedDefects` | 5 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.CheckedOne` | 10 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.Column` | 9290 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.CompactList` | 1705 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `expr.DateLit` | 1074 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `expr.DecimalLit` | 11 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `expr.Exists` | 178 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.FloatLit` | 385 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.FormatLit` | 235 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.Group` | 111 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.IntLit` | 3697 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.JsonArray` | 26 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.JsonArrayAgg` | 1239 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.JsonObject` | 1341 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.Lambda` | 312 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `expr.Membership` | 251 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.NullLit` | 1928 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.ReduceCollection` | 204 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `expr.RowOrder` | 140 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `expr.ScalarSubquery` | 1529 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.Star` | 156 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.StringLit` | 7015 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `expr.StructGet` | 99 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | FAIL |
| `expr.StructLit` | 101 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | FAIL |
| `expr.TimestampLit` | 113 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.ABS(1)` | 69 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.ACOS(1)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.ADD_INTERVAL(3)` | 59 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.ADD_INTERVAL_TEMPORAL(3)` | 37 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.AND(2)` | 2027 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.AND(3)` | 844 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.AND(>3)` | 274 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.ASIN(1)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.ATAN(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.ATAN2(2)` | 6 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.BOOL_TO_TEXT(1)` | 25 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.CEILING(1)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.COALESCE(2)` | 1863 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.CONCAT(2)` | 4010 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.CONCAT(3)` | 606 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.CONCAT(>3)` | 243 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.CONCAT_JOIN(2)` | 2 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.CONCAT_JOIN(3)` | 2 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.CONCAT_JOIN(>3)` | 13 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.COS(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.COT(1)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.DATE_DIFF(3)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.DATE_TRUNC(2)` | 7 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.DATE_TRUNC_DAY(1)` | 21 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.DECODE_BASE64(1)` | 5 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.DIVIDE(2)` | 109 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.ENCODE_BASE64(1)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.ENDS_WITH(2)` | 181 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.EPOCH_MS(1)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.EQUAL(2)` | 5643 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.ERROR(1)` | 148 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.ERROR(2)` | 56 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.EXP(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.EXTRACT(2)` | 40 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.FLOAT_CANON(1)` | 422 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.FLOOR(1)` | 12 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.FLOOR_RAW(1)` | 57 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.FORMAT(2)` | 24 | ok | -- | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.FORMAT(3)` | 24 | ok | -- | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.GREATER(2)` | 1945 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.GREATER_EQUAL(2)` | 231 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.IN(2)` | 45 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.IN(3)` | 211 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.IN(>3)` | 39 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.INT_DIVIDE(2)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.IS_NOT_NULL(1)` | 1487 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.IS_NULL(1)` | 1470 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.JSON_MERGE_PATCH(2)` | 3 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.JSON_MERGE_PATCH(3)` | 1 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.JSON_MERGE_PATCH(>3)` | 1 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.JSON_PRETTY(1)` | 4 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.JSON_TYPE(1)` | 257 | ok | FAIL | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LEFT(2)` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LENGTH(1)` | 124 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LESS(2)` | 314 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.LESS_EQUAL(2)` | 975 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.LIST_BOOL_AND(1)` | 24 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.LIST_BOOL_OR(1)` | 4 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.LIST_CONCAT(2)` | 36 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_FILTER(2)` | 13 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_FLATTEN(1)` | 97 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.LIST_GET(2)` | 147 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_LENGTH(1)` | 83 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.LIST_MAX(1)` | 46 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_MIN(1)` | 46 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_POSITION(2)` | 6 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_PRODUCT(1)` | 7 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.LIST_SLICE(3)` | 1 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_SORT(1)` | 85 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_SUM(1)` | 2 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_TRANSFORM(2)` | 299 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LIST_ZIP(2)` | 4 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.LIST_ZIP(3)` | 4 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.LN(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LOG10(1)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LOWER(1)` | 37 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.LPAD(3)` | 6 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.LTRIM(1)` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.MD5(1)` | 6 | ok | ok | -- | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.MINUS(2)` | 168 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.MOD(2)` | 17 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.NOT(1)` | 226 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.NOT_EQUAL(2)` | 10 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.NOW(0)` | 17 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.NULL_SAFE_EQUAL(2)` | 20 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.NULL_SAFE_NOT_EQUAL(2)` | 132 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.OR(2)` | 1225 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.PARSE_DATE(1)` | 21 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.PARSE_INT(1)` | 12 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.PLUS(2)` | 146 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.POW(2)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.RANGE_FN(1)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.RANGE_FN(2)` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REGEXP_EXTRACT(3)` | 2 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REGEXP_FULL_MATCH(2)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REGEXP_REPLACE(3)` | 143 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REGEXP_REPLACE(>3)` | 31 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REM(2)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REPEAT_STR(2)` | 5 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.REPLACE(3)` | 3626 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.RIGHT(2)` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.ROUND(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.ROUND(2)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.RTRIM(1)` | 4 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.RTRIM(2)` | 79 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.SIGN(1)` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.SIN(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.SPLIT(2)` | 10 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.SQRT(1)` | 9 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.STARTS_WITH(2)` | 190 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.STRFTIME(2)` | 231 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.STRPOS(2)` | 953 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.STRPTIME(2)` | 7 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.STRUCT_INSERT(3)` | 91 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.SUBSTRING(2)` | 38 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.SUBSTRING(3)` | 45 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.TAN(1)` | 7 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.TIMES(2)` | 173 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.TODAY(0)` | 16 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.TO_VARIANT(1)` | 271 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.TRIM(1)` | 33 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.TYPEOF(1)` | 109 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `fn.UNNEST(1)` | 1515 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.UPPER(1)` | 30 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `fn.VARIANT_ELEMENTS(1)` | 28 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `fn.VARIANT_GET(2)` | 312 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select` | 9394 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.distinct` | 780 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.groupBy` | 1260 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.having` | 154 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.limit` | 640 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.offset` | 236 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.orderBy.asc` | 2894 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `select.orderBy.asc.NULLS_LAST` | 153 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.orderBy.desc` | 207 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `select.orderBy.desc.NULLS_FIRST` | 1 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.star` | 1468 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `select.where` | 4355 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.Dual` | 3909 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.RawSql` | 36 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `source.Subselect` | 7444 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.Table` | 6546 | ok | ok | ok | ok | ok | -- | -- | ok | ok | ok | -- |
| `source.Values` | 391 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.join.CROSS_LATERAL` | 1 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | FAIL |
| `source.join.FULL` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.join.INNER` | 181 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.join.LEFT` | 4484 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `source.join.LEFT_LATERAL` | 15 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | FAIL |
| `source.join.RIGHT` | 40 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `union` | 8 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `union.all` | 1155 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `window.COUNT` | 8 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `window.MAX` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `window.QUANTILE_CONT` | 6 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | -- |
| `window.QUANTILE_DISC` | 4 | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- | -- |
| `window.RANK` | 6 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| `window.SUM` | 3 | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |
