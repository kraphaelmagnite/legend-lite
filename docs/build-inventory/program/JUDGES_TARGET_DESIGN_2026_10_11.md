# The two ceremony cuts promised with L7: a `//gates:judges` target, and the lane query in the local gate (design, 2026-10-11)

Promised with L7 (2026-10-10, to the user): "one `//gates:judges` target that runs every heavy lane and the six
corpus passes with the baseline comparison built in, so the judges are one command; and the lane query CI's product
job runs (`tests(//...) except lanes except heavy`) added to `//gates:local`, so a new manual target cannot cost a CI
run again." Neither landed with L7. This note says what Bazel can and cannot do for each, and recommends one answer
for each; the user rules on the two forks before code.

## 1. `//gates:judges`

**What a landing does today** (every landing since Phase 3): a script swaps `core/src` to main's version, builds the
six corpus passes (`//spec:judge_{host,database}_{duckdb,h2,warehouse}`), copies their outputs aside, restores the
tree, builds them again on the change, and compares the two sets line by line
(`evidence/phase3/compare_judges.py`); then the render census the same way (a probe applied to three source files;
`docs/execution-plan-boundary-2026-10-05/render-census/run.sh`), then `//gates:local`. About two hours, and the swap
has bitten three times (a commit during it, the branch's new files left on the main side twice; the memory's three
traps).

**What the passes write** (measured on main eabda91b8): per pass a ledger of one row per assert (`judge-host.tsv`
or `judge-database.tsv`, 5,400 to 5,850 rows, about 800 KB each, six of them: 4.8 MB), a one-line `verdict.txt`,
and the measured rosters and registers (`fail-roster`, `skipped-roster`, `unordered-register`,
`engine-order-register`, `database-engine-order-register`: 100 to 1,000 lines each). No file carries a timestamp or
a path (no comment lines at all), and two builds of one tree write identical files (every compare since Phase 3:
"22 result files identical").

**The Bazel-native form.** The baseline is a committed file and the comparison a diff test, the pattern the repository
already uses for the reference lane (`//spec:update_reference_lane` writes `reference-lane/core_relational.txt`,
331 KB, and `update_reference_lane_test` fails when the lane's output differs) and for the parser-equivalence
ratchets (`//parser-equivalence:update_ratchets`). Then `bazel test //gates:judges` is the six passes, their diff
tests and `//gates:heavy` in one command, with no swap: a change that moves a pass fails the diff test, and the
landing re-blesses the baseline with `bazel run //spec:update_judges` and says in the commit which rows moved and
why, as the reference lane and the ratchets are re-blessed today. The old script stays for the diagnosis of a moved
baseline against main's tree when the diff alone does not say why.

**The fork: what is committed.** Two choices, the user's call:

- (a) **The files themselves**: the six ledgers, verdicts, rosters and registers, about 5 MB, re-committed on every
  landing that moves a pass. A moved pass shows its moved rows in the diff. The cost: the repository grows by the
  moved files each time (the reference lane, 331 KB, has moved a handful of times since 2026-09; the ledgers move
  on most compiler landings that touch typing or lowering).
- (b) **Digests for the ledgers, files for the rest** (recommended): the verdicts, rosters and registers committed as
  files (small, and they are the summaries a reader wants), and for each 800 KB ledger a row in one committed table
  (`spec/src/test/resources/judges/ledgers.tsv`: pass, row count, SHA-256 of the sorted rows), written by a small
  `java_run` program in the repository over the passes' outputs. A moved ledger fails the diff test and names the
  pass and the count; the rows that moved are read with the existing compare script against main's outputs
  (rebuilt by the swap, or kept from the previous landing's `runs/`), which is what happens today anyway. The cost:
  one more small program, and a digest says that a ledger moved, not which rows, until the script is run.

Either way the render census stays a script: its probe edits three source files, which no Bazel action may do, and
its records are per-test outputs of the whole lane set (52,550 entries). Its ceremony is the swap, not the command.

**The audit and the judges for this landing:** a Bazel change, audited; the passes themselves do not change, so the
judges are the new diff tests green on main's tree (the committed baseline equals the passes' outputs) and the lane
query empty; one CI run.

## 2. The lane-membership query in `//gates:local`

The query is `tests(//...) except tests(//gates:lanes) except tests(//gates:heavy)`, run by hand before every
dispatch (the rule since the CI lane-membership gate, GATES "CI lane membership rule") and by CI's product job.

**What Bazel offers.** `genquery` runs a query at build time and could be a test's input, but its `scope` limits the
query to the transitive closure of named targets, and a test that is in no lane is, by definition, outside the
closure of `//gates:lanes` and `//gates:heavy`; there is no label that means "every target" (`//...` is a pattern,
not a label). So a genquery cannot find an unlaned test. A test that runs `bazel query` itself would be a nested
Bazel invocation, which the repository's rules forbid (no host programs in tests). No aspect or load-time check sees
other packages' targets. **The query cannot be a target of `//gates:local`.**

**The recommendation.** Keep the query where it works (CI's product job, already) and give the local gate one
command that is the test and the query together: a four-line `tools/gates/local.sh` (zsh/bash, no Bazel target) that
runs `bazel test --lockfile_mode=error //gates:local`, then the query, and exits non-zero when the query prints a
target, naming it. The rule's wording in START_HERE and GATES changes from "run the query" to "run the gate script".
It is not what was promised (a target), because a target is not possible; the promise's purpose (a new manual target
cannot cost a CI run) is met by the script locally and by CI's product job remotely.

**The alternative**, if a script is not wanted: leave the query as the hand-run step it is today and strike the
promise with this note as the reason.

## What the user rules on

1. Fork (a) or (b) for the judges' baseline. (b) recommended.
2. The lane query: the gate script, or the hand-run step kept and the promise struck. The script recommended.

Nothing is built until both are ruled; the program's next item meanwhile is L4 (Phase 6) homework.
