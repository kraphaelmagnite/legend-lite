# Where the tab's compile of a 200-class page goes: the boot, or the page (2026-10-11)

Promised to the Compiler Rewrite and Plan Gen / Exec Split lines (after the Compiler Rewrite line's phase split,
e56d802c4, `docs/build-inventory/program/evidence/phase6/HOMEWORK_2026_10_11.md`). That split put a 200-class page's
compile at about 13 ms on a warm JVM, against the tab's 3,937 ms (`docs/DATACUBE_MODEL_DESIGN_2026_10_10.md` §11). The
caution: the two were not set up the same, and the tab's clock might hold the boot layer. Two numbers decide it, from
`datacube/test/model-scale.measure.ts`'s own setup: warmUp of an empty model (the boot alone), and warmUp at n = 200.

Run by hand on macOS arm64, a quiet machine (load about 2). The tab's module is `//wasm:planner_dir` under Bazel's
Node (`--experimental-wasm-exnref`). The native library is legend-lite's wheel, both built from this checkout on
2026-10-11. Model texts are `classModel(n)`, copied verbatim from the measure.

## Results

**The tab's module** (`boot-split.ts`, `wasm-node.jsonl`), ms:

| what | ms |
|---|---|
| a cold module: load, boot, an empty model held | 3,575 |
| the same module: an empty model held again (5 runs) | 1 |
| a cold module: load, boot, classModel(2) held (the measure's base) | 3,429 |
| warm: an empty model held (median of 3) | 1 |
| warm: classModel(1) | 9 |
| warm: classModel(10) | 131 |
| warm: classModel(50) | 762 |
| warm: classModel(200) | 3,975 |
| warm: an empty model held, after 200 | 1 |

**The native library** (`native.py`, `native.txt`; the same Java, GraalVM-built: `lite_hold_model` holds a model as
`Boundary.holdModel` does, through `HeldModels.hold`, then `Compiler.compileModel`), ms, median of 5:

| what | ms |
|---|---|
| the first call: boot, an empty model held | 101 |
| an empty model held again | 1 |
| classModel(1) | 0.7 |
| classModel(10) | 1.6 |
| classModel(50) | 6.0 |
| classModel(200) | 30.0 |

## What it says

- **Page-bound, not boot-bound.** On a warm module an empty model is held in 1 ms. The boot layer is held across
  `withModel` already, so the 3,975 ms at n = 200 is the page's model alone.
- **The same Java, built two ways, differs by about 130x on this compile:** 3,975 ms against 30 ms at n = 200, about
  130x at 50 too, and 80x at 10. The tab's per-class cost grows with the model (13 ms a class at 10, 15 at 50, 20 at 200).
  The native library's does not (0.12-0.16 ms a class). For scale, the module's header comment puts a plan at about
  4x the JVM's.
- **The tab's time is in a few functions** (`prof.ts`, `hold-200.cpuprofile`, summarized by `top.py` in
  `hold-200-top.txt`). One profiled hold of classModel(200), 4,025 ms sampled. By self time: four functions take 85%
  (34%, 19%, 18%, 13%). Their names are stripped: `tools/teavm/TeaVmCompile.java` builds with `setObfuscated(true)`
  and no debug information. Naming them needs a build without obfuscation; that is the owner's step, not taken here.

## Regenerating

From the checkout, after `bazel build //wasm:planner_dir //python:wheel`, with `<esbuild>` and `<node>` Bazel's own
(`<output_base>/external/+http_archive+esbuild_darwin_arm64/bin/esbuild`,
`<output_base>/external/rules_nodejs++node+nodejs_darwin_arm64/bin/node`):

1. `<esbuild> boot-split.ts --bundle --format=esm --platform=node --external:fzstd --external:apache-arrow
   --outfile=<scratch>/boot-split.mjs`, then `<node> --experimental-wasm-exnref <scratch>/boot-split.mjs
   file://<bazel-bin>/wasm/planner_dir/`.
2. `texts.ts` bundled the same way and run with `<scratch>` writes the model texts. Then, in a venv with the wheel
   from `<bazel-bin>/python/`, `python -I native.py <scratch>`.
3. `prof.ts` like step 1, with the profile's path as a second argument; then `python3 -I top.py <profile> 12 24`.
