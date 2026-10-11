# Audit of the duplicate-mapping fix (2026-10-11)

An independent read-only audit (the `auditor` agent) of the change set at e19df30ce (the first WIP on branch
`build/dup-mapping`; parent 37a3edb2c, the IN_FLIGHT announcement on main 23df2d588), before the judges' round on the
final tree. The brief: the report (a model holding two mappings of one name crashing `compileModel` with an index
error from the include ordering), the two product edits, the test, the ledger row, and questions A to G (the cycle
check's invariant; a legitimate second registration of a mapping; the test's assertions; the ledger row's truth and
anchor; the engine-parity claim; the same bug class elsewhere; hygiene). The auditor ran no build or test.
**Verdict: ready after fixes** (0 blockers, 5 should-fix, 3 nits). The fixes are the second WIP (c7d11a5c3), squashed
into the landing commit and re-judged (the GATES entry "A duplicate mapping is refused").

| # | Finding (the auditor's, condensed; file:line at e19df30ce) | Fixed |
|---|---|---|
| A | The invariant holds: `byFqn`, `edges` and `pending` are filled one name at a time from the same list, `ready` is seeded from `pending`'s keys and fed from `edges`' keys, no name is emitted twice; so the ordered names are always among the graph's and a difference names a member (`StoreSubstitutionRewrite.java:249-321`). A real cycle and a self-include report as before | — |
| B | No legitimate second registration of a DSL mapping: Phase E's output carries none (its products and the boot layer's one mapping, `meta::lite::metamodel::MetamodelMapping`, are `MappingDefinition`, plain `intern`); `retainLegacySurface` is plain `intern`; re-adding the same object is skipped by `indexed()`; `ModelBuilder.from` has one caller; the module parse drops same-kind duplicates before indexing | — |
| C | The test's strict path exercises the fix (Phase E runs before `ModelIntegrity`, so without the count fix the index error would still throw); the module-path comment is honest (`walls().isEmpty()` is right: the second copy is dropped at parse) | — |
| E | Engine parity supported by the repository's upstream notes: `WORLD_H.md:495` ("Duplicated element", `PureModelContextDataValidator`), `COMPILER_STAGE_AUDIT_2026_08.md:230`, `STAMP_DISCIPLINE_PROGRAM.md` D6b. The engine refuses in a validator before compiling; lite refused after Phase E (S3) | — |
| F | No other instance of the bug class (the one include ordering; no other count of a parsed list against a by-name map). A latent false cycle: two include spellings of one mapping (`include w::A`, `include A` from package `w`) resolve to one name, counted twice and satisfied once, so the mapping is refused as a cycle | PARK-26, anchored on the resolved names' count |
| S1 | `ModelBuilder.java:203` cited "F-L2 in `projects/FINDINGS.md`", which does not exist | cites PARK-25 |
| S2 | "A duplicate mapping is refused" covered the mapping DSL's mappings only: a clean-sheet (function-form) mapping is never ingested at parse (`indexed()` answers true for it) and its product registers by plain `intern`; the module parse keys by kind, so a DSL and a clean-sheet mapping of one name both survive it | the wording narrowed to the DSL's mappings; clean-sheet mappings added to PARK-25 |
| S3 | The refusal was not always "Duplicated element": Phase E ran before `ModelIntegrity`, so a second copy including itself threw "mapping include cycle: [w::M]", a loser listing an include twice "Duplicated mapping include", a loser's own shape its `MappingPrePass` error; the comment "its refusal is ModelIntegrity's" overstated it | duplicates refused BEFORE Phase E (`ModelIntegrity.refuseDuplicates`, called by `Compiler.normalizeLayer` through `PureModelContext.refuseDuplicates`; the engine's order, D6b's "element-identity first"); the gate's check keeps the arm for a graph element shadowing a boot FQN; the self-include case pinned in the test |
| S4 | The new cross-kind behaviour (`Class w::M` + `Mapping w::M`, newly refused; the module parse keeps both) was untested | a strict case and a module case in the test (`walls.get("w::M")` is "Duplicated element 'w::M'") |
| S5 | PARK-25 cited a GATES entry that did not exist at the tip | the entry lands with the commit |
| N1 | `ModelBuilder.java:511`: "its normalized product below" — the arm is above | "above" |
| N2 | `IncludeRulesTest.java:132`: the assertion message built the module twice | held in a local |
| N3 | `ParkedWorkLedgerTest.java` not named in the IN_FLIGHT entry | named in the landed line |
