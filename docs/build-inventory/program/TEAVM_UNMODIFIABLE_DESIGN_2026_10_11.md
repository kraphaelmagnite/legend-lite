# The tab's planner, 12x faster to boot and 58x on a 200-class page: TeaVM's unmodifiable wrappers (design, 2026-10-11)

**Status: ruled 2026-10-11 (§5, option 1: the port now; the TeaVM 0.16.0 bump a landing of its own); landing on
branch `build/teavm-unmodifiable`.** Evidence: `evidence/teavm-unmodifiable/`. Raised by the
DataCube + Python line's measurement of the tab's compile (`docs/datacube-model-homework-2026-10-11/boot-split/`, on
main since 8c8b92d54), after the Compiler Rewrite line's phase split (`evidence/phase6/HOMEWORK_2026_10_11.md`).

## 0. In one paragraph

The tab's planner module (TeaVM's WebAssembly build of the same Java the native library and the JVM run) takes 3.6 s to
boot and 4 s to hold a 200-class page; natively the two take 0.1 s and 30 ms. The time is not the compiler's: it is
TeaVM 0.15.0's class library, whose `Collections.unmodifiableSet` and `unmodifiableMap` answer `contains`, `get` and
`containsKey` by scanning the whole collection where the JDK's wrappers ask the hash table underneath. The compiler
wraps its universe of known names that way and asks it for every candidate of every bare name, so a page's cost grew
with its own size. TeaVM fixed the two wrappers on 2026-10-01, and its 0.16.0 release of 2026-10-02 carries the fix;
the repository pins 0.15.0. With that fix ported onto 0.15.0, the boot is 299 ms and the page 73 ms, measured on the
production build with the DataCube line's own harness. The port is one file in the package that exists for this,
`third_party/teavm_classlib`, and it goes at the TeaVM bump.

## 1. The cause

- Profile of one 200-class hold on an unobfuscated build (`evidence/teavm-unmodifiable/hold_200_named_top.txt`):
  89 % of the time is under `AbstractCollection.contains`, reached from `NameResolver.addKnown` through
  `ModelNormalizer.resolveSynthesized`; the self time is the iterator and `String.equals`.
- TeaVM 0.15.0's `TCollections` (its sources jar, the SHA-1 the package's README records): the wrappers are
  anonymous classes overriding `iterator`, `size` and `remove` (the map: `entrySet` and `remove`), nothing else, so
  every lookup falls to `TAbstractCollection`'s and `TAbstractMap`'s linear scans.
- The compiler is right as written: `resolveSynthesized` builds its universe once and wraps it unmodifiable
  (a `LinkedHashSet`: hash lookups on the JVM and in GraalVM's image). 148 more calls, in 79 files of `core/src/main`, wrap sets and maps
  the same way (the platform's registries, the prelude's universe, `Implementation`'s positions), which is where the
  boot's 3.6 s went. `unmodifiableCollection`, which TeaVM has not fixed, core uses nowhere.
- Upstream: TeaVM master `5971683cf1` (2026-10-01, "classlib: fix O(n) performance in Collections.unmodifiableMap")
  makes the set and the map delegate; 0.16.0 (2026-10-02) carries it. The repository pins 0.15.0 (MODULE.bazel,
  `maven_teavm`); L8, the program's bump, is the upstream Legend release's, not TeaVM's.

## 2. The fix (one answer)

**Correct the class library, not the compiler.** `third_party/teavm_classlib` is "TeaVM's class library corrected where
lite needs it exact": its jar goes first on every module's class path, each file replaces TeaVM's class whole, carries
a notice of what changed, and goes when a TeaVM release carries the fix. `TCollections.java` joins its four files:
0.15.0's file with TeaVM's own change ported, the set and map wrappers delegating their lookups to the wrapped instance
as the JDK's do (`evidence/teavm-unmodifiable/TCollections_0.15.0_port.diff` is the measured port; it also gave the
collection wrapper the same, which is inert for us and not kept, so that the file is exactly upstream's change and goes
whole at the bump).

Not the alternatives:
- Unwrapping the compiler's hot set (`Set.copyOf`, or the bare `HashSet`) would dodge the library in one call site,
  leave the 148 others slow, and make the next `unmodifiableSet` the next 4 s. The compiler stays mainstream-shaped;
  the platform's library is held to the JDK's contract, as the number conversions already are.
- Bumping TeaVM to 0.16.0 now is the right end state but a landing of its own: the four corrected number files are
  re-ported onto 0.16.0's sources (the package's rule), the conformance ledger re-judged, the module's differentials
  and every tab's tests rerun on a new WebAssembly backend. It is not planned, measured or sized; the one-file port is,
  and it is written to disappear into that bump.

## 3. How it is held

- **On the JVM** (`//third_party/teavm_classlib:tests`, in the `core` lane; the file is plain Java): a counting set and a
  counting map, each answering its lookups directly and counting the iterators (the set) or the entry sets (the map)
  it hands out, wrapped by `unmodifiableSet` and `unmodifiableMap`: `contains`, `containsAll`, `isEmpty`, `get`,
  `containsKey`, `containsValue` and `size` answer
  right and open no iterator; `add`, `remove`, `put` and the iterator's `remove` still refuse.
- **In the module**: `//wasm:conformance_test`, `//wasm:differential_test` and `//wasm:round_trip_test`, DataCube's
  tests and browser harnesses, Studio's and Query's (every tab loads this module): the lanes `datacube`,
  `parser_equivalence` and `ui`. One full CI run, as for a toolchain change, since the module is in several lanes.
- **The number**: the DataCube line reruns `//datacube:model_scale_measure` (manual, in `heavy`) on the landed module,
  and the harness in `evidence/teavm-unmodifiable/harness/` stands as the before-and-after. No timing gate: CI's
  runners vary too much for one; the measure is the receipt.

## 4. What lands, and its cost

- `third_party/teavm_classlib/src/org/teavm/classlib/java/util/TCollections.java` (0.15.0's file plus the port, with
  the package's notice header), a README row naming `5971683cf1` and 0.16.0, the JVM test above, a `docs/GATES.md`
  entry, the IN_FLIGHT line first (the module every line's tab loads changes).
- Risk: delegation only, so no answer changes except where the module now agrees with the JDK, whose wrappers
  delegate (a wrapped sorted set or map whose comparator disagrees with `equals`, an identity-keyed set, a null key
  into a sorted map; `core/src/main` wraps none such); the module grows by the new methods (a few hundred bytes:
  5,093,390 to 5,093,779, the same for the measured port and the landed file, since the collection wrapper's methods
  are unreachable from the planner and dropped). The file replaces TeaVM's whole, so a TeaVM bump re-ports or deletes it: at 0.16.0 it
  is deleted, since that release is the fix.
- Not chased now: the 2.5 % in `Class.getEnumConstants` (TeaVM's `EnumSet` asks for the constants on every lookup),
  and the module's remaining 2.4x over the native library, the ratio the module's header gives for a plan.

## 5. What the user rules on

1. **Land the one-file port now** (recommended): every tab boots in 0.3 s instead of 3.6 s and holds a 200-class page
   in 73 ms instead of 4 s, this week, and the file goes at the TeaVM bump.
2. Or **bump TeaVM to 0.16.0 instead**, as its own landing (§2's second alternative: re-port the four number files,
   re-judge the conformance ledger and the module), keeping today's module until it lands.
