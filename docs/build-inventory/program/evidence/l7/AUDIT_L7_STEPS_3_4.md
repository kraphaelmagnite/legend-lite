# Audit of L7 "resolve once", steps 3 and 4 (2026-10-10)

An independent read-only audit (the `auditor` agent) of the steps 3-4 change set at 7a0934a92 (three WIP commits on
main b1a9529f0), before the judges' round on the final tree. The brief: the design note (§6, §11, §12, §13), `Calls`,
`NameResolver.decide`/`builtCallNames`, `BareNames.referents`, the deletions, the converted checkers and sites, the
tests and the guards; questions A to H (the builder's contract, the deletions' reach, what the old tier walk reached
that the record does not, the checkers' arities, the guards, the tests, hygiene, the memo tables). The auditor ran no
build or test; it recounted the identity guard's shapes with a copy of the guard's scanner at the base and the tip.
**Verdict: ready after fixes** (0 blockers with one condition, 7 should-fix, 6 notes). Each item's answer is in the
"Fixed" column; the fixes are in the landing commit and re-judged (the GATES entry "L7 step 3").

| # | Finding (the auditor's, condensed; file:line at 7a0934a92) | Fixed |
|---|---|---|
| Condition | PARK-5's row is deleted and DEBTS' "Closed" section cites the GATES entry "L7 step 3", which did not exist at the tip; PARK-5's acceptance (typing time, the corpus passes, PCT, the reference lane) is what the judges measure | the GATES entry lands with the commit, the measurement in it |
| S1 | A wrong-arity call to a name only the bare-name rule reaches now reports "unknown function" (the record is at the call's arity; the old tier walk returned every arity and the kernel reported the arity) | `Overloads`: when the catalog has the name at another arity, the arity message as before |
| S2 | A normalizer-built call no longer resolves under its owner's section imports (the second pass kept it, pre-recorded under the sectionless scope); §12's "a built call is what the same call parsed would be" holds only in that scope; `among`'s empty row relies on the second pass | stated in the design's §12 with the date; `among`'s javadoc says the empty row is deliberate |
| S3 | MINT_BY_NAME went blind: its pattern `new AppliedFunction\(\s*"` no longer sees the 134 literal names that moved to `Calls.*("`; 142 → 18 claimed more than happened | the pattern counts the builder's shapes too; re-pinned at the measured 152 with the reason (the literal stays until step 5 spells it by the declaration) |
| S4 | The two memo tables can grow without bound (every undeclared name typed into a long-lived process; user full names through `like`); the registration's "never keyed by anything but those inputs" was untrue | `BareNames.referents` keeps no empty answer; `builtCallNames` keeps only an answer that found or recorded something (`CallNames.found`) |
| S5 | `EvalChecker:84` built the pointer call with `exact` on a spelling the resolver may leave bare | built with `platform` (a full name answers itself); and the resolver's pointer arm now qualifies a bare signature-id reference by its base (`qualifyIdByBase`), which the one census body that had changed (`isEval`, a reference to `eval`'s seven-argument overload the catalog lacks) needed |
| S6 | The design note had no §13 at the tip (written after the WIP commit); §12 said `decide` was "unchanged" | §13 is in the commit; §12 corrected |
| S7 | No test of `BareNames.referents` (the prelude branch), of `renamed`, `among`, `infixRunAmong`; `likeKeepsOrReanswers` used an arity where the join adds nothing | `BareNamesTest.theRuleNamesPreludeFunctionsAtTheirArity`; `CallsTest.renamedAndAmong`; `like` checked against `builtCallNames` at the new arity and on `from` 2 → 3 |
| N1 | The ArchUnit rule covered five packages; the record's static factories (`infixRun`, `list`, `tableReference`) were not forbidden | the rule covers every package but the parser, the protocol readers and the test runners, and forbids the three factories too |
| N2 | `like` at a changed arity keeps the original's arity-filtered names beside the new arity's | noted in §12; the kernel filters by arity |
| N3 | `decide` emits `bareTier` probe events only on the first computation per key | noted; the candidate-row comparison reads CANDIDATES rows only |
| N4 | Dead probe code: `DecisionProbe.onBareCall`, `Shadow.onBareCall` | deleted |
| N5 | `asPlainCall` inserted between `asGrouped`'s javadoc and `asGrouped`; the old history note on the new pin's line; START_HERE "Next: L7 step 3"; the `catalog` helper copied into two tests | the first three fixed (the state document with the landing); the helper stays in both tests (eight lines, test-only) |
| N6 | Checked and found correct: every converted checker reads the record of the call it checks; the arities (`eval` with the function argument, `tableToTDS` 1, `tableReference` 3); PARK-6's re-anchor; thread safety; every product typing entry resolves first (`Compiler`, `StatementExecutor`, `TypedQuery`, `AggregationAwareRouting`), so the "resolve first" test edits hide no product path | — |

After the fixes the identity guard grew where the new resolver code resolves by string, and the pins moved with the
reason: NAME_CUTTING 106 → 107 and SIGNATURE_ID_CUTTING 1 → 2 (the bare id's base resolution moved from the typer's
tier walk into the resolver's pointer arm, once); NAME_AFFIX_TEST and NAME_COMPARE_REVERSED unchanged (the first
cut of the fixes had added one of each; both were removed by restructuring, not re-pinned).
