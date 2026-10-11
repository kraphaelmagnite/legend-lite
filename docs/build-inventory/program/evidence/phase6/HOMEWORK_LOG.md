# Phase 6 (L4) homework log — 2026-10-11

Brief: docs/build-inventory/program/PHASES_3B_6.md §6.10 (6-H1..H9), procedure C.4. Scratch under runs/homework/phase6/
(never committed); results that matter are copied to docs/build-inventory/program/evidence/phase6/ when written up.

## 6-H1 setup (12:15)
- H/ = the experiments scripts copied; et/pt = the pinned trees (@legend_engine_src, @legend_pure_src realpaths);
  graph.py + index.py -> repos.json, files.tsv, decl_repo.json (closure(core_relational) 27 repos, 1772 files;
  everything 144 repos, 3080 files).
- Variant (i), as it was (composition + rest, ownership filter): e8_extra.py on today's prelude -> 679 extra files,
  11,328 elements kept, 8.96 MB (left out: 508 already in the default world, 2995 platform namespace, 30 system
  metamodel, 178 platform-owned, 3 in the composition).
- Variant (ii), no ownership filter (the table owns): Hii/ -> 685 files, 11,506 elements, 9.05 MB (the 178
  platform-owned elements admitted).
- The runner's copy patched (e8_MinimalCorpus.patch applied cleanly at MinimalCorpus.java:447) and compiled with the
  remote JDK 25 against judge_host_duckdb's classpath (69 entries) -> H/probe/e8classes.
- The six passes built with --remote_download_all (the memory's first trap).
- 12:3x: variant (i) running: EXTRA_CP=H/probe/e8classes EXTRA_JVM=-Dexperiment.extraSources=H/e8_extra.files
  LABEL=e8_today e6_lanes.py -> H/e6_lanes/e8_today/<pass>/, log H/e6_lanes_e8_today.log (the script forces -Xmx4g;
  6-H2 needs a copy keeping each pass's own -Xmx).

## 6-H1 variant (i) results so far (12:45) and 6-H7
- Each pass runs in 34-52 s outside Bazel (host duckdb 46 s, database duckdb 52 s, host h2 34 s) at -Xmx4g.
- DuckDB host and database passes: the ONLY difference from the Bazel baseline is one added ledger row:
  meta::pure::executionPlan::tests::datetime::testPlanWithLocalH2ConnectionWithSQL assert 0 (assertEquals) FAIL;
  verdicts otherwise identical (PASS 5804 / FAIL 43 host; 5806 / 43 database); no verdict changed.
- 6-H7 answered: in the baseline the test fails at TYPING, "unknown function
  meta::protocols::pure::vX_X_X::transformation::fromPureGraph::executionPlan::transformPlan" (the protocol
  translators are not in the composition), so no assert runs; with the real manifest loaded, transformPlan exists,
  the body types, the plan runs against the H2-connection test data and the first assert fails on an empty result
  (expected ['a', 'b'], actual []): a host-decided assert reached. It does so on today's prelude.

## 6-H1 variant (i), the H2 database pass (12:55)
- judge_database_h2 verdict=1 is the REGISTER check alone ("host-compared register != committed: NEW 1"): the one
  newly reached test (testPlanWithLocalH2ConnectionWithSQL) is decided outside a verdict row and is not registered.
  Its ledger: the same one added row as DuckDB (PASS 5406 / FAIL 62 / UNJUDGED 5 unchanged otherwise).

## 6-H1 variant (ii), no ownership filter: the DuckDB host pass (12:55) -- 6-H3 answered
- judge_host_duckdb: 120 assert rows LOST (5847 -> 5728 rows; PASS 5804 -> 5685; no verdict changed among the rows
  that remain; the one added row as in (i)). 60 tests lose every assert, i.e. fail before their first assert:
    46  "type: project expects ~[...] column specifications"  (e.g. testSQLCommentsInPlan,
        testFilterEqualsWithOptionalParameter_*, testToSqlGeneration*: the legacy-shape project([p|$p.name], ['name'])
        refused once upstream's own meta::pure::tds::project is admitted -- the platform's relation form answers and
        refuses the old shape instead of routing to TdsLegacy or running upstream's body)
    10  "type: cannot access 'name' on String"  (e.g. relationalTDSTypeForColumnsAndQuoting: a lambda typed against
        the wrong overload)
     2  the same project refusal inside a function body (TypeInferenceException)
     1  accepted-divergence witness missing (testExtendDigest_Relational), 1 a bare lambda outside a call (zScoreTest)
  => 6-H3: with tds.pure's functions loaded whole, the legacy TDS calls do NOT still go to TdsLegacy; the platform's
     form refuses them. The TDS rows by id (PARK-11) have to come before Phase 6 admits the ownership-filtered
     elements: an ordering question for the user, as the brief foresaw.
- judge_database_duckdb ran 0 s (it reads the host verdict, which failed).

## Ledger diffs (13:10): (ii) complete, (iii) 38 host duckdb, 6-H4
-- (ii) host h2: rows 5422 -> 5306; verdicts {'PASS': 5290, 'FAIL': 132} -> {'PASS': 5173, 'FAIL': 133}; tests losing every row 57, gaining 1, verdict changes 0
     46  type: project expects ~[…] column specifications  (e.g. testSQLCommentsInPlan)
      9  type: cannot access '…' on String  (e.g. relationalTDSTypeForColumnsAndQuoting)
      2  TypeInferenceException: in function '…': project expects ~[…] column  (e.g. testToSqlGenerationFirstDayOfQuarter_DB2)
-- (ii) host warehouse: rows 5847 -> 5728; verdicts {'PASS': 5804, 'FAIL': 43} -> {'PASS': 5685, 'FAIL': 43}; tests losing every row 60, gaining 1, verdict changes 0
     46  type: project expects ~[…] column specifications  (e.g. testSQLCommentsInPlan)
     10  type: cannot access '…' on String  (e.g. relationalTDSTypeForColumnsAndQuoting)
      2  TypeInferenceException: in function '…': project expects ~[…] column  (e.g. testToSqlGenerationFirstDayOfQuarter_DB2)
      1  accepted-divergence WITNESS MISSING ('…') — the divergence changed, re-decide :: type: a name-l  (e.g. testExtendDigest_Relational)
      1  type: a bare lambda has no type outside a call position (lambdas type against their call's sign  (e.g. zScoreTest)
-- (iii) 38 host duckdb, unknown types unmasked: rows 5847 -> 5692; verdicts {'PASS': 5804, 'FAIL': 43} -> {'PASS': 5649, 'FAIL': 43}; tests losing every row 80, gaining 1, verdict changes 0
     46  type: project expects ~[…] column specifications  (e.g. testSQLCommentsInPlan)
     11  type: Unknown type: 'Node' is not a known primitive, class, or enum  (e.g. testConvertColumnName)
     10  type: cannot access 'name' on String  (e.g. relationalTDSTypeForColumnsAndQuoting)
      2  type: unknown class 'FunctionCall' in ^FunctionCall(…)  (e.g. testConvertJoinStrings)
      2  TypeInferenceException: in function 'meta::relational::tests::functions::sqlstring::testToSqlGenerationFirstDa  (e.g. testToSqlGenerationFirstDayOfQuarter_DB2)
      1  accepted-divergence WITNESS MISSING ('9e103ea06a6999b4c5a86cf25d68b083') — the divergence changed, re-decide :  (e.g. testExtendDigest_Relational)
      1  type: a bare lambda has no type outside a call position (lambdas type against their call's signature)  (e.g. zScoreTest)
      1  type: unknown class 'AliasedRelation' in ^AliasedRelation(…)  (e.g. testConvertAlias)
      1  type: unknown class 'LogicalBinaryExpression' in ^LogicalBinaryExpression(…)  (e.g. testConvertDynaFnToLogicalExpression)
      1  type: unknown class 'InListExpression' in ^InListExpression(…)  (e.g. testConvertLiteralList)
      1  type: unknown class 'Query' in ^Query(…)  (e.g. testConvertSelectSQLQueryWithCTE)
      1  type: unknown class 'ExtendedQuerySpecification' in ^ExtendedQuerySpecification(…)  (e.g. testConvertSubqueryPredicates)
      1  type: unknown class 'TableFunction' in ^TableFunction(…)  (e.g. testConvertTabularFunction)
      1  type: unknown class 'TableSubquery' in ^TableSubquery(…)  (e.g. testConvertUnion)
- 6-H4 (scanRelations.pure admitted, otherwise variant (i)): the DuckDB host pass has NO lost row and NO verdict change,
  only the one gained row of (i); the 49 lineage tests keep their verdicts with the engine's implementation loaded
  (batch 134's finding no longer holds after 3b/L7). Whether the exclusion stays as a design statement (the user's
  ruling 2026-08-28: the reference checkout is spec, never runtime) is the user's call.

## Variant (iii) corrected (13:30)
- The first (iii) runs were wrong: e8_extra treated the whole relationalStore GENERATION folder as the runner's
  composition, but the runner's corpus tree is the deeper core-pure `core_relational/relational` folder
  (UpstreamFiles.RELATIONAL), so the postgres SQL model's metamodel.pure (a SHAPE file in the real runner) was neither
  loaded by the runner copy nor written as a manifest file: 20 testConvert* tests failed with "Unknown type: 'Node'"
  (meta::external::query::sql::metamodel, declared by core_external_store_relational_postgres_sql_model, a repository
  INSIDE the 38). 6-H6 reads from this: the SHAPE file is a corpus need (the testConvert* tests), met by the manifest
  itself once the manifest is the composition. The scripts now use the exact tree; (iii) 38 and 27 re-run.

## 6-H1 variant (i) complete (13:35)
- All six passes: identical to the Bazel baseline but for the ONE added ledger row (testPlanWithLocalH2ConnectionWithSQL
  assert 0 FAIL) in every ledger; no verdict changed; the only verdict=1 is the H2 database pass's REGISTER check
  (that newly reached test is host-compared and unregistered). Pass times at -Xmx4g, this machine, other passes
  running beside: host duckdb 46 s, database duckdb 52 s, host h2 34 s, database h2 87 s, host warehouse 276 s,
  database warehouse 233 s (the warehouse passes start the native server).
- 6-H8 (PCT channel B with 3b's fixes) launched: bazel test //pct:pct_channel_b --cache_test_results=no.

## 6-H8 and 6-H4 (13:45)
- 6-H8: PCT channel B afresh (--cache_test_results=no), today's tree (3b's fixes, L7, the two landings of the day):
  5 of 5 suites pass (essential 41.6 s, grammar 30.2 s, relation 46.9 s, standard 37.3 s, unclassified 17.3 s); the
  discovery and wall counts are exact pins (PctRatchets), so a moved count would have failed a suite: none moved.
- 6-H4 (scanRelations.pure admitted): DuckDB host and database passes and the H2 host pass verdict=0 with only the
  one added row; the H2 database pass verdict=1 is the register check again (NEW 1). Warehouse passes pending.
- (iii) 38: the corpus repositories had collapsed to 27 with the exact-tree prefix (the postgres SQL model's repository
  is a corpus repository only by the generation-folder rule); the 38 script now takes the corpus repositories by the
  generation folder and the composition by the exact tree; both (iii) runs restarted.

## 6-H1 variant (iii) corrected, 38 repositories, DuckDB host pass (16:20) -- 6-H6 answered
- The manifest alone (883 extra files, 13,278 elements; no SHAPE_FILES, no LIBRARY_FILES, no M2M / graph-fetch
  folder lists; no ownership filter): rows 5847 -> 5728, PASS 5804 -> 5685, FAIL 43 unchanged, no verdict changed;
  60 tests lose every row and 1 gains one -- EXACTLY variant (ii)'s set (the symmetric difference is empty).
- So the composition's lists are redundant once the manifest is the composition (6-H6: the SHAPE file's classes
  come from their own repository, inside the 38), and the one thing standing between today's tree and the manifest
  world is the ownership filter's 178 platform-owned functions (the legacy TDS forms above all: PARK-11 / 6-H3).

## 6-H5 visibility (16:30), from one shadow-probe pass of (iii) 38 (LL_SHADOW on judge_host_duckdb)
- The probe's CANDIDATES rows carry the name and the candidate ids, not the calling file (CONTEXT unattributed), so
  the count is over the whole world's distinct candidate sets, an upper bound for any caller: 1446 rows; with a
  candidate declared OUTSIDE closure(core_relational) (27 repositories, by decl_repo.json): 0; mixed inside/outside
  sets, where a visibility rule could change a pick: 0. 568 candidate ids have no upstream declaring repository
  (platform-declared: the prelude's and the natives', e.g. meta::pure::functions::lang::if). The 11 repositories the
  38-world adds beyond the 27 contribute no candidate to any call the corpus compiles, so upstream's visibility rule
  changes no pick here; the rows are runs/homework/phase6/shadow/manifest38_host_duckdb.tsv (2475 lines: CANDIDATES
  1446, PICK 632, LIFTED 159, RESOLVER-TIER 117, FORM 61).

## 6-H4 complete (17:00)
- scanRelations.pure admitted (the runner's ENGINE_IMPLEMENTATION_FILES emptied; the manifest otherwise as (i)): all
  six passes keep every row and every verdict of the baseline; the one gained row is (i)'s; the lineage /
  scanRelations tests (counted below per pass) keep their verdicts with the engine's implementation loaded. Batch
  134's finding (the 49 tests inline the engine's scanRelations and wall on openVariableValues) no longer holds after
  3b and L7: the resolver's one table picks the platform's by id. The exclusion can go; whether it stays as the
  design statement of the 2026-08-28 ruling (the reference checkout is spec, never runtime) is the user's call.

## 6-H1 variant (iii) complete, 38 and 27 repositories (17:05) -- 6-O1's evidence
- Both manifest-only worlds give EXACTLY variant (ii)'s outcome on every host pass (DuckDB 60 tests lost, H2 57,
  warehouse 60; symmetric difference with (ii) empty; no verdict changed; the one gained row). 27 repositories
  (closure(core_relational), 779 manifest files, 11,346 elements) cover what the corpus needs as well as 38 (883
  files, 13,278 elements): 6-O1's option (a) loses nothing. Pass times (this machine, other passes beside): (iii) 38
  host duckdb 246 s, host h2 169 s, host warehouse 290 s; (iii) 27 host duckdb 74 s, host h2 42 s, host warehouse 117 s.

## 6-H2 heap and time at the real flags (13:07; quiet machine; each pass at its own -Xmx, -Xlog:gc*)
- (i) times: host duckdb 45 s, database duckdb 51 s, host h2 31 s, database h2 72 s, host warehouse 116 s, database
  warehouse 114 s. Peak heap before a collection (committed): host duckdb 533 (563) MB, database duckdb 532 (562),
  host h2 892 (1236), database h2 4095 (4096) MB with 432 pauses -- the H2 database pass fills its whole heap --,
  host warehouse 558 (583), database warehouse 536 (566).
- (ii) host passes: 534 / 876 / 553 MB (duckdb / h2 / warehouse), times 51 / 36 / 110 s; (iii) 27 host passes:
  538 / 862 / 561 MB, 52 / 36 / 112 s: the manifest world costs no more heap than today's composition, and the
  passes run in the same time (the database passes stop at the failed host verdict in (ii) and (iii)).
- The passes' own -Xmx (from the Bazel actions): runs/homework/phase6/xmx_per_pass.txt.
