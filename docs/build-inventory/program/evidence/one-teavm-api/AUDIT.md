# Audit: one `//tools/teavm:api` (branch `build/one-teavm-api`, 2026-10-13)

The independent audit agent's report on commit 482019f7c over main 24e15866f (the commit was then amended to
248e1f5ab with the two wording nits taken into the files and the two into its message; the six files' targets and
labels unchanged). Read-only; the auditor ran no Bazel. Quoted as delivered.

---

VERDICT: ready. 0 blockers, 0 should-fix, 4 optional nits. The commit can land as it is.

Scope: commit 482019f7c, the only commit in 24e15866f..482019f7c. It changes 6 files (+34/-53): query-store/BUILD.bazel,
sdlc-server/BUILD.bazel, tools/deps/pools.bzl, tools/teavm/BUILD.bazel, warehouse/BUILD.bazel and wasm/BUILD.bazel. I
ran no Bazel and edited nothing.

BLOCKERS
None.

SHOULD-FIX
None.

NITS (each a one-line fix, all optional)
N1. tools/teavm/BUILD.bazel:23 says "One library for every module teavm_wasm builds". There are six teavm_wasm modules.
    One of them, //warehouse:sqlapi_wasm (warehouse/BUILD.bazel:465-473, entry :sqlapi_wasm_entry at :418-423,
    deps = [":sqlapi"]), does not use the library and does not need to. Suggested wording: "One library for every
    module whose Java names them".
N2. tools/deps/pools.bzl:32 adds "the annotations from //tools/teavm:api" inside a parenthetical dated 2026-10-04 (the
    Studio line). The added fact dates from 2026-10-13. Adding ", 2026-10-13" there would keep the dated trail exact.
    The roster itself is unchanged.
N3. The commit message calls the four copies "identical". They were functionally identical, but //wasm:teavm_api (old
    wasm/BUILD.bazel:23-30) lacked `nullaway = False`, so it carried the NullAway plugin and javacopts. With no sources
    that has no effect: no compile action, and `plugins` and `javacopts` do not pass to dependents. The new library
    sets `nullaway = False`. Mentioning this is optional.
N4. "so the annotations reach no package the roster does not name" (commit message) holds for direct dependencies,
    which is what visibility controls. A transitive dependent still gets the jars on its full compile classpath, for
    example //parser-equivalence through //wasm:tab, with strict deps blocking direct use. That was equally true
    before. The claim is accurate if read as "no package outside the roster can name them".

CHECK 1: Starlark correct, removals complete
- No `teavm_api` is left in any file outside docs/. `git grep teavm_api 482019f7c` outside docs/ returns nothing; that
  covers BUILD, .bzl, the workflows, AGENTS.md and the guards.
- Order of each dependent's deps follows buildifier (":" labels, then "//" labels alphabetically, then "@"). Each
  comment line sits directly above the item it belongs to:
  - wasm/BUILD.bazel:54-58: ":boundary", "//core:plan_side", "//tools/teavm:api"
  - wasm:193: `deps = ["//tools/teavm:api"]`
  - sdlc-server:78-84: ":rules", "//depot-server:rules", "//json", "//sdlc-server/grammar", "//tools/teavm:api"
  - query-store:18-23: "//core:saved_queries", "//json", "//tools/teavm:api"
  - warehouse:432-436: ":sqlapi", "//tools/teavm:api"
- tools/teavm/BUILD.bazel:1 reads `load("//tools/java:defs.bzl", "legend_java_binary", "legend_java_library")`. That
  is alphabetical (buildifier's order) and the order the BUILD file uses the symbols (binary at :9, library at :27).
  core, datacube, parser-equivalence, sdlc-server and spec use the same load line.
- Attribute order in the new library is buildifier's: name, neverlink, nullaway, visibility, exports. The visibility
  list is sorted. The library comes before `guards_package()` (G0/G16 see it).
- Edited and moved comments:
  - wasm: the removed comment moved to tools/teavm:21-26, adapted from "the tab's adapter" to "a module's Java". It is
    true: @JSExport comes from jso, and ResourceSupplier is used by the META-INF/services resources of //wasm:tab (:50)
    and //sdlc-server:page_boundary (:77).
  - warehouse: the comment block now ends at "...a round trip to it." (warehouse:424) and leads straight into
    sqlapi_page_entry. True.
  - query-store and sdlc-server: the removed one-liners leave no orphaned comments. The query-store B5 header now sits
    directly above page_entry; sdlc-server's page_boundary had no comment of its own before.
  - pools.bzl:32 "its :page target, as //wasm's": //sdlc-server:page exists (sdlc-server:89). True (date nit N2).
  - tools/teavm:21-26 new block: true apart from N1.

CHECK 2: Same jars on the same classpaths
- legend_java_library (tools/java/defs.bzl:22-41) calls `check_pool_use(name, deps, runtime_deps, exports)`, then
  passes `java_library(...)` with `javacopts = (NULLAWAY_OPTS if nullaway else []) + LEGEND_JAVACOPTS + javacopts` and
  `plugins = ([NULLAWAY_PLUGIN] if nullaway else []) + plugins`. With no srcs there is no Javac or Turbine action, so
  `nullaway = False` only removes an unused plugin edge.
- The new target is the same shape as each old copy: a neverlink java_library with exports only. Its JavaInfo is
  unchanged:
  - the exported compile jars (rules_jvm_external's CreateCompileJar copies) are on each direct dependent's compile
    classpath and count as direct for strict deps;
  - the runtime jars are empty because of neverlink.
- Moving across packages changes nothing in the providers. Strict deps treats exports of a direct dep the same in any
  package; only the label in a strict-deps error message changes. There is no ijar/hjar of its own (no srcs).
- TeaVM compile (tools/teavm/defs.bzl:20-35): inputs are `transitive_runtime_jars` of deps plus the classlib fixes.
  The neverlink library contributes nothing there, before or after, so the five modules' TeaVM inputs are unchanged.
- //wasm:conformance_jvm (java_run, wasm:196-203) runs on the JVM's runtime classpath, which excludes neverlink as
  before.
- The jars' own visibility is fine. MODULE.bazel:219-235 is `strict_visibility = True`, and teavm-core and teavm-jso
  are both declared artifacts (:221-228), so //tools/teavm may name them. It already named teavm-core at :16.
- The guard tools/guards/CompileOnlyTest.java SKIPS["wasm"] (line ~81) requires the //:wasm walk to stop at
  //tools/nullaway:nullaway. That still holds without the old wasm copy's plugin edge: //json (nullaway on) is in
  //wasm:planner's closure via :boundary. The new library adds no actions to the report.

CHECK 3: Visibility and pools
- Visibility (tools/teavm:31-36) is exactly the four dependent packages: //query-store, //sdlc-server, //warehouse and
  //wasm. Each old copy was private (legend_java_library's default), so any target in those four packages could depend
  on it. The set of permitted dependents is the same.
- The roster at pools.bzl:38 is unchanged. All four packages still need it, because their teavm_wasm targets name
  @maven_teavm classlib and jso directly: query-store:35-36, sdlc-server:102-103, warehouse:449-450 and :471-472,
  wasm:70-71 and :211-212.
- check_pool_use: the new library's `exports` are checked (defs.bzl:33 passes `kwargs.get("exports", [])`), and
  "tools/teavm" is a listed maven_teavm user, so it passes. The dependents' "//tools/teavm:api" is not a Maven label,
  so check_pool_use ignores it (pools.bzl:88-90). Visibility is the control there, as the new comment says.

CHECK 4: Line widths
The longest added line in the diff is 117 columns. None exceeds 120.

CHECK 5: Dated reason and commit-message claims
- 2026-10-13 is in tools/teavm/BUILD.bazel:23 and at the end of the commit message, citing area7_report.md wiring
  cleanup 7 (area7_report.md:140).
- These claims match the diff: the four packages, the five dependents (//wasm:tab, //wasm:conformance,
  //sdlc-server:page_boundary, //query-store:page_entry, //warehouse:sqlapi_page_entry), and the five modules
  (//wasm:planner, //wasm:conformance_module, //sdlc-server:page, //query-store:page, //warehouse:sqlapi_page), each
  fed by one of those dependents.
- I could not verify these without Bazel: "the library, its five dependents and the five modules build", and the lane
  list "core, checks, parser_equivalence, warehouse, datacube, ui, sdlc".
- Wording: see N3 and N4.

OTHER QUESTIONS
- Guards and checked-in files: nothing in tools/guards or tools/deps reads these target names.
  - The only TeaVM mentions in tools/guards are CompileOnlyTest's rule-kind/mnemonic pairs ("jvm_import
    CreateCompileJar", "_teavm_wasm TeaVM"); neither names a target.
  - guards_package's checks (G0, G16, A19, A28) are satisfied: the new target is a legend_java_library.
  - No new package, so :repository_files is unaffected.
  - No checked-in generated file lists these labels.
- Docs naming the old targets are point-in-time reports; leave them as history.
  - area7_report.md is headed "Repo: runs/bazel-exec (branch bazel/exec)" with the queries it ran.
  - inventory.tsv is generated by build_inventory.py from a one-time graph dump. No test diffs it, and it already
    lacks the later query-store and warehouse copies.
  - G5_dossiers.md is pinned to "HEAD 669b39ad1" and dated 2026-10-05.
  - No living tracker row covers the teavm_api part of cleanup 7. PHASE_8.md Gen-5's "area7 C7" covers only the
    make-manual part.
  - Pre-existing and outside this change: the "//wasm:boundary names TeaVM's" text in CompileOnlyTest.java (native
    tier) and the "boundary class's compile-time path" text in MODULE.bazel:217 have both been stale since :tab took
    over the annotations.
- core/: no file under core/ is touched (0 of the 6 files).
- Linux and Windows: no risk. The change is label wiring only: no paths, actions or tools.

May it land as is?
Yes. This is a pure wiring tidy-up that keeps behaviour the same. The one new neverlink, exports-only library has the
same JavaInfo as each removed copy, so every dependent compiles against the same two jars, no runtime classpath and no
TeaVM compile input changes, and visibility matches the old per-package reach. The pool roster and the guards are
untouched and still hold. No `teavm_api` reference is left outside point-in-time docs, and no core/ file is touched.
The four nits are optional wording improvements. Only the build and lane claims in the commit message need the running
gate to confirm them.

---

## Taken (248e1f5ab)

- N1: the new library's comment says "every module whose Java names them" (//warehouse:sqlapi_wasm's entry names no
  annotation and does not depend on it).
- N2: pools.bzl's sdlc-server comment dates the shared library ("since 2026-10-13").
- N3, N4: the commit message says the four copies were alike but for wasm's NullAway switch (no sources, so it
  changed nothing), and that no package outside the roster can name the annotations.
- The two stale texts the auditor noted outside this change (CompileOnlyTest.java's "//wasm:boundary names TeaVM's",
  MODULE.bazel:217's "boundary class's compile-time path") are left for a change of their own.
