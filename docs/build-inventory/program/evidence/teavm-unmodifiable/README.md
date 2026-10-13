# The tab's 4 s compile of a 200-class page: TeaVM's unmodifiable wrappers (2026-10-11)

The DataCube + Python line measured (`docs/datacube-model-homework-2026-10-11/boot-split/`, on main since 8c8b92d54) that the
tab's planner module holds a 200-class page in 3,975 ms where the same Java takes 30 ms in the native library and
about 13 ms on a warm JVM (`../phase6/HOMEWORK_2026_10_11.md`), that the cost is the page's model and not the boot, and
that 85 % of it sits in four functions whose names the obfuscated build strips. This folder names them, finds the cause
and measures the fix. The design that follows is `../../TEAVM_UNMODIFIABLE_DESIGN_2026_10_11.md`. Run by hand on macOS
arm64, outside any Bazel target; nothing here is a Bazel target. The fix landed as `third_party/teavm_classlib`'s `TCollections.java`
(`docs/GATES.md`, 2026-10-11, "TeaVM's unmodifiable set and map wrappers look up instead of scan").

## What was run, in order

1. **A named build.** `tools/teavm/TeaVmCompile.java` with `setObfuscated(false)` for one throwaway build of
   `//wasm:planner_dir` (the edit reverted after it). The module grows from 5.1 MB to 8.8 MB (the name section); its
   timings are the obfuscated build's (`before_named_node.jsonl` against the DataCube line's
   `before_obfuscated_node_datacube.jsonl`: 4,239 ms against 3,975 ms at 200 classes, the same shape at 1, 10 and 50).
2. **The profile.** The DataCube line's `harness/prof.ts` (one profiled hold of `classModel(200)`, 200 µs sampling)
   on the named module, summarized by `harness/top.py`: `hold_200_named_top.txt`. By self time, 91 % is five
   functions: `LinkedHashMapIterator.makeNext` 30.6 %, `AbstractCollection.contains` 30.4 %,
   `LinkedHashMapIterator.hasNext` 12.6 %, `String.equals` 11.3 %, `KeyIterator.next` 5.8 %. By inclusive time:
   `Compiler.compileModel` 98 %, `normalizeLayer` 94 %, `NameResolver.resolveCallCandidates` 91 %,
   `AbstractCollection.contains` 89 %, `ModelNormalizer.resolveSynthesized` 87 %, `callTierMatches` and `addKnown` 55 %.
   A linear `contains`, with `String.equals` over a LinkedHashMap's iterator, under the resolver's membership test.
3. **The cause**, read from TeaVM 0.15.0's class library (`teavm-classlib-0.15.0.jar`, and its sources jar, SHA-1
   `219bbc2b52db85becfab06140f042c6925a53258`): `TCollections.unmodifiableSet` returns an anonymous `TAbstractSet`
   that overrides `iterator`, `size` and `remove` only, so its `contains` is `TAbstractCollection.contains`, the
   iterate-and-equals scan; `unmodifiableCollection` the same; `unmodifiableMap` returns a `TAbstractMap` with
   `entrySet` and `remove` only, so its `get` and `containsKey` scan the entries. The JDK's wrappers delegate every
   lookup to the wrapped collection. On the compile path, `ModelNormalizer.resolveSynthesized` builds its universe
   (the platform's names and the model's) as `Collections.unmodifiableSet(new LinkedHashSet<>(...))`, and
   `NameResolver.addKnown` asks that set `contains(candidate)` for every candidate package of every bare name of
   every synthesized body (the wildcards, the own package, the core import group): names × packages × the universe,
   and the universe grows with the model, which is why the cost per class grew with the page (13, 15, 20 ms a class).
   The same wrappers hold the prelude's universe and the platform's registries, which is where the boot's time went.
   Upstream: TeaVM master commit 5971683cf1 (2026-10-01, "classlib: fix O(n) performance in
   Collections.unmodifiableMap") makes the set and map wrappers delegate, and TeaVM 0.16.0 (released 2026-10-02)
   carries it; the collection wrapper is still 0.15.0's there too (core uses that one nowhere).
4. **The fix, measured.** `TCollections_0.15.0_port.diff`: 0.15.0's file with the three wrappers delegating
   (`contains`, `containsAll`, `isEmpty` for the set and the collection; `get`, `containsKey`, `containsValue`,
   `size`, `isEmpty` for the map), written by `port_tcollections.py` over the sources jar's file. Placed as a
   throwaway in `third_party/teavm_classlib` (the package that corrects TeaVM's class library, first on the
   compiler's class path), the planner built in its production configuration (obfuscated), and the DataCube line's
   `harness/boot-split.ts` run on it: `after_fixed_node.jsonl`. The file was then removed; the tree is main's.

## Results (ms; a warm module's hold is the median of 3)

| what | before (production build, the DataCube line's) | before (named build) | after the fix | the native library |
|---|---|---|---|---|
| a cold module: load, boot, an empty model held | 3,575 | 3,618 | **299** | 101 |
| a cold module: load, boot, classModel(2) held | 3,429 | 3,569 | **155** | -- |
| warm: classModel(1) | 9 | 9 | 2 | 0.7 |
| warm: classModel(10) | 131 | 140 | 4 | 1.6 |
| warm: classModel(50) | 762 | 802 | 17 | 6.0 |
| warm: classModel(200) | 3,975 | 4,239 | **73** | 30.0 |

The like-for-like comparison is the first and third columns, both the production (obfuscated) build: the named
build is a little slower before. The landed file (the set and the map delegation only) builds the same
5,093,779-byte module as the measured port, which also had the collection wrapper's: `unmodifiableCollection` is
unreachable from the planner and its methods are dropped (the planner built by `//wasm:differential_test` on the
landing branch, 2026-10-11).

The native column is the DataCube line's (`boot-split/native.txt`). After the fix the module is about 2.4x the native
library on this compile, the ratio the module's header comment gives for a plan; the per-class cost no longer grows.
What is left in the profile beside the wrappers (`Class.getEnumConstants` 2.5 %: TeaVM's `EnumSet` asks for the
constants on every lookup) was not chased.

## Regenerating

From the checkout, with `<node>` and `<esbuild>` Bazel's own (`<output_base>/external/rules_nodejs++node+nodejs_<platform>/bin/node`,
`<output_base>/external/+http_archive+esbuild_<platform>/bin/esbuild`):

1. Copy `harness/` to a folder three levels below the checkout root (its scripts import
   `../../../datacube/src/wasm-planner.ts`), and bundle each script: `<esbuild> <script>.ts --bundle --format=esm
   --platform=node --external:fzstd --external:apache-arrow --outfile=<script>.mjs` (both packages load lazily and
   never on this path).
2. The named module: `setObfuscated(false)` in `tools/teavm/TeaVmCompile.java`, `bazel build --remote_download_all
   //wasm:planner_dir`, copy `bazel-bin/wasm/planner_dir` aside, revert the edit.
3. `<node> --experimental-wasm-exnref prof.mjs file://<the copy>/ <profile>.cpuprofile`, then `python3 -I top.py
   <profile>.cpuprofile 30 70`.
4. The fix: `python3 -I port_tcollections.py <sources jar's TCollections.java>
   third_party/teavm_classlib/src/org/teavm/classlib/java/util/TCollections.java`, build `//wasm:planner_dir`, copy it
   aside, and `<node> --experimental-wasm-exnref boot-split.mjs file://<the copy>/`.

## The DataCube line's receipt (2026-10-11, on the landed module)

`//datacube:model_scale_measure` (`datacube/test/model-scale.measure.ts`; manual, in `//gates:heavy`), rerun by the
DataCube + Python line on main `39218e803` with `--nocache_test_results`, macOS arm64, the tab's module under Node. Its
clock is not the boot split's above: each compile is on a module already warm and is the median over three model texts
a line apart (each text's first compile), and each plan the median of seven over the model held; the boot split holds
one model text and takes the median of three holds. The before numbers are the DataCube design's
(`docs/DATACUBE_MODEL_DESIGN_2026_10_10.md` §11, "after compile-once", 2026-10-10), ms:

| classes | model text | compile once, before | compile once, after |
|---|---|---|---|
| 1 | 1 KB | 9 | 47 |
| 10 | 7 KB | 125 | 31 |
| 50 | 36 KB | 664 | 69 |
| 200 | 146 KB | 3,937 | **321** |

Plans over the model held, ms, at 1/10/50/200 classes, before then after: a table read 5/5/5/9, then 1/2/1/2; a class
read 7/6/6/12, then 3/2/3/9; a class with one end walked --/7/7/14, then --/3/5/12.

Read as the DataCube line sent it: the 200-class compile is 12x faster on this clock (321 ms against the boot split's
73 ms is a first compile of each text against repeated holds of one, not the same clock); the 1-class compile reads
higher than before (47 against 9), one run of a small number, into which that line reads nothing. The GATES entry of
the landing names this receipt as the number the landing is held to.
