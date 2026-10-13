# Audit: one `DUCKDB_PLATFORMS` mapping (branch `build/duckdb-platforms`, 2026-10-12)

The independent audit agent's report on commit 6dbaf0281 over main 47349d82a (the commit was then amended to
131dc799c with the should-fix and the first nit taken; the second nit is the intended attribution). Read-only; the
auditor ran no Bazel. Quoted as delivered, the worktree path removed.

---

Audit of 6dbaf0281 (over main 47349d82a), branch build/duckdb-platforms. I only read the code. I did not run Bazel,
as the brief asked.

VERDICT: ready after fixes. 0 blockers, 1 should-fix (two lines over 120 columns), 2 nits. Nothing changes behaviour.

BLOCKERS: none.

SHOULD-FIX
1. Two added lines are 122 columns, over the brief's 120-column limit. No guard enforces the limit, and many older
   lines in core/BUILD.bazel and base/BUILD.bazel are longer, so nothing breaks. Each is a one-line fix.
   - python/BUILD.bazel:15, the `load("//tools/platforms:defs.bzl", "COMPILER_LIBRARY_PLATFORMS", "DUCKDB_PLATFORMS",
     "compatible_with", "platform_select")` line. Fix: wrap it across lines, one symbol per line.
   - warehouse/defs.bzl:49, `# DUCKDB_PLATFORMS: //tools/platforms): one choice, which //warehouse:postgres_extension
     unpacks beside the native server.` Fix: rewrap it with line 48.

NITS
1. tools/platforms/defs.bzl:55 says the constant is "the one mapping behind every extension MODULE.bazel pins". That
   is true for what the build selects. But MODULE.bazel cannot load a .bzl file, so it still spells the five DuckDB
   names by hand in its own hash tables (MODULE.bazel:469-473 and :487-491). Nothing checks that those tables match
   DUCKDB_PLATFORMS. A few words would make the comment exact, e.g. "MODULE.bazel's pin tables must list the same
   names".
2. The commit message's Co-Authored-By trailer names "Claude Fable 5.1". Check that this is the attribution you intend.

THE FIVE CHECKS
1. Starlark is correct.
   - Load symbols are in ASCII order, upper case before lower case, as in the files' other loads: python/BUILD.bazel:15
     and warehouse/defs.bzl:16. warehouse/defs.bzl's own load statements were already out of order before this commit
     (@bazel_lib, //tools, @rules_java); that is not this change.
   - No `_DUCKDB_PLATFORMS` is left anywhere (git grep finds nothing).
   - warehouse/defs.bzl:50-53: the comprehension builds `{bazel: "@duckdb_postgres_extension_" + duckdb + "//file"}`,
     plain strings as before. Its five entries are exactly the former five in 47349d82a's warehouse/defs.bzl:
     macos_arm64 to osx_arm64, macos_x86_64 to osx_amd64, linux_x86_64 to linux_amd64, linux_aarch64 to linux_arm64,
     windows_x86_64 to windows_amd64. Only the key order differs, which does not matter in a select, and the no-match
     message sorts the names anyway (tools/platforms/defs.bzl:36).
   - python/BUILD.bazel: the dict body is identical to the old private `_DUCKDB_PLATFORMS`. The five java_run targets
     (python/BUILD.bazel:386-396) keep the same names, inputs, outputs and `compatible_with([bazel])`, and the selected
     values at :399 are still lists.
   - `platform_select` (tools/platforms/defs.bzl:24-40) takes any dict whose keys are in PLATFORMS. All five keys are,
     so the target_compatible_with covers the same five platforms. `compatible_with` (:42-46) is unchanged.
2. MODULE.bazel defines both repository sets for exactly the five names: `duckdb_postgres_extension_<name>` at
   MODULE.bazel:464-474 and `duckdb_httpfs_extension_<name>` at :482-492, for osx_arm64, osx_amd64, linux_amd64,
   linux_arm64 and windows_amd64. That matches DUCKDB_PLATFORMS' values one for one.
3. The comments are true.
   - The URL layout `extensions.duckdb.org/v1.5.5/<name>/` matches MODULE.bazel:467 and :485.
   - "Both macOS builds are osx_universal" is true: warehouse/BUILD.bazel:19-20. That table's other three names match
     DuckDB's, so it is right to keep it separate.
   - The python comment (python/BUILD.bazel:380-383) matches the java_run output folder
     `duckdb_extensions/v1.5.5/<duckdb>/` and engine.py:175.
   - The warehouse comment (warehouse/defs.bzl:48-49) is accurate apart from its length.
4. Other hand-spelled platform maps in *.bzl, BUILD.bazel and MODULE.bazel. Each is a different mapping and should stay
   as it is:
   - warehouse/BUILD.bazel:18-24, the JDBC jar's native library names: macOS is osx_universal.
   - tools/browser/defs.bzl:8-14, Chromium's names (linux64, mac_arm64, win64).
   - tools/js/BUILD.bazel:8-15, esbuild's names (linux_x64, darwin_*), which also include windows_aarch64.
   - tools/guards/BUILD.bazel:195-201, actionlint's names (darwin_*), and the shellcheck map after it.
   - MODULE.bazel:382 and :399-401, esbuild and actionlint pins; MODULE.bazel:513, a Chromium repository name.
   - MODULE.bazel:469-473 and :487-491 are DuckDB's names but cannot read the .bzl file (nit 1).
   Nothing else should be switched to DUCKDB_PLATFORMS.
5. The dated reason is there: tools/platforms/defs.bzl:56-57, "2026-10-12: the two hand-spelled copies made one."

The commit message's claims ("same five repositories ... on the same five platforms; nothing else changes", JDBC names
left alone) match the diff, which touches only these three files and changes only the mapping and comments.

May it land as is? Yes in substance. It is a faithful, behaviour-preserving refactor: the same five DuckDB names map
to the same platforms, select the same repositories and generate the same java_run targets with the same
compatibility. The new constant carries its dated reason, and the other platform maps are rightly left alone. Before
landing, rewrap the two 122-column lines (python/BUILD.bazel:15, warehouse/defs.bzl:49). Optionally note in the
constant's comment that MODULE.bazel's pin tables must list the same five names.

---

## Taken (131dc799c)

- Should-fix 1: the load in python/BUILD.bazel wrapped one symbol per line; the warehouse/defs.bzl comment rewrapped.
- Nit 1: the constant's comment now says MODULE.bazel cannot load the file, so its two pin tables spell the same five
  names by hand and must keep listing these.
- Nit 2: the attribution is the intended one.
