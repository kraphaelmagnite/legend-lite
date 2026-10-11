# Audit of the `~func` descriptor refusal (2026-10-11)

An independent read-only audit (the `auditor` agent) of the one WIP commit on branch `build/func-descriptor`
(edd637328 on main 9a6873d07, the IN_FLIGHT announcement), before the judges. The brief: the site (both doors, the
`~src` form, the server), the signature scan against the descriptor grammar (nested generics, function types, the
arrow, multiplicities, spaces in JSON, unit types, type variables, an empty list, no signature), the dialect decision,
the tests, the fixtures and other texts spelling the long form, hygiene. The auditor ran no build or test and had no
upstream sources in its worktree, so the engine's behaviour is read from the commit's comments and the engine
fixtures (`parser-equivalence/src/test/resources/engine-grammar-fixtures.jsonl`). **Verdict: ready after fixes**
(0 blockers, 5 should-fix, 6 notes). Each item's answer is in the "Fixed" column; the fixes are in the landing commit.

| # | Finding (the auditor's, condensed; file:line at edd637328) | Fixed |
|---|---|---|
| S1 | The announcement says `ModelException`; the code throws the model door's `UnsupportedMappingShape` (a positioned `ParseException` from text, `Unrepresentable` then `NotImplementedException` from JSON), and refuses function types ("Unsupported type") beyond the announced package path | both differences named in the IN_FLIGHT landed line and the GATES entry |
| S2 | The JSON door untested (the test compiles text only) | `RelationFunctionDescriptorTest.jsonDoor`: the text's protocol JSON (`PmcdParser.parseDocument`) read back (`ModelReader.read`) and compiled, refused with the engine's phrase; the channel's "not implemented" wording noted in GATES |
| S3 | A space before the `:` in a JSON-carried descriptor (`f() :R[1]`) skipped the return-type check; the javadoc's "as the parser joins it" true for text only | whitespace outside quotes removed before the scan (the engine's lexer drops it, so the type is named as the engine names it); a direct test with spaces; the javadoc says which door spells what |
| S4 | The comment's reasoning reaches further than the fix: the engine resolves by the full id (`f__Relation_1_`), lite by the name alone, so a signature naming another return type or multiplicity, or none, compiles here and fails there; the `~src f():R[1]` tail is dropped at parse; the activator's descriptor is parse-only | PARK-28 in `docs/PARKED_WORK_LEDGER.md`, anchored on the signature cut in `MappingFromProtocol.java` and pinned in `ParkedWorkLedgerTest` |
| S5 | The commit subject starts with "WIP:" | squashed into the landing commit |
| N1 | The `contains` assertions pinned the incidental "(a function descriptor" wording; no SPDX header | the assertions name the engine's phrase with the type, and the descriptor quote, separately; the header added |
| N2 | A unit type and a type variable untested as accepted spellings | both compile in `simpleNamesCompile` |
| N3 | `docs/SESSION_HANDOFF_2026_09_02.md:490` told authors to write the package-path form | corrected with the date |
| N4 | `@param` missing for `descriptor` and `where` | added |
| N5 | The text door's `ParseException` is positioned after the element (`pos = endOut[0]` precedes the transform); the descriptor's own line:col is in the message only; existing behaviour of every refusal raised this way | noted here; not changed |
| N6 | The engine reports this case as a COMPILATION error ("Can't find the packageable element") at the class-mapping span; lite's message names the rule instead, so a future engine-fixture comparison of the case sees a different message | the choice recorded in the GATES entry |

After the fixes the identity guard's NAME_AFFIX_TEST moved 51 → 52 with the reason: the refusal tests a descriptor
TYPE's spelling for a package path (`contains("::")`), the engine's `validateType` rule on text that is never resolved
into a declaration, not an element name's affix.
