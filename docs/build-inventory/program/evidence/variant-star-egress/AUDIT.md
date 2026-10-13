# Audit of the Variant star-egress landing (2026-10-12)

The independent auditor's report on the first cut of branch `core/variant-star-egress` (commit 6544e5c5d, since amended
to de1506d92 with every finding taken). Read-only; the auditor ran no Bazel; its DuckDB probes were its own. The
baselines the judges and the render census compare against were built from main cbd8f014f for the audit; the files
beside this one are the comparisons re-run after the rebase onto main 36b72ee13 (commit 2daa5bfa1, the code
byte-identical), against baselines built from that main (`judges_compare.txt`: every result file the same;
`render_census_compare.txt`: 41 of 54,454 entries differ, all in the core lane's own unit tests, listed).

## Verdict: ready after fixes. 1 blocker, 3 should-fix, 3 nits.

### Blocker

**B1. The pass misses root projection frames that carry a star projection, so "whatever the shape of the query" does
not hold.** The guard (`VariantEgress.java:35`) acts only when `s.projections().isEmpty()`. Plain `->extend`
(keepExisting), window extend and aggregate extend all build `SELECT t0.*, <expr> AS x` through
`Lowerer.starProjections` (`Lowerer.java:1466`, `2233`, `2258`; the helper at `2269-2275`); other star projections:
`ExistsJoinForm.java:168` (`Star(src.alias())`), `Lowerer.java:2116` (a join hosted on a left alias), `Pivots.java:61`
(`StarExcept`). A star projection renders no column reference, so the `columnRef` hook never fires and the stored
STRUCT leaves as stored (confirmed in DuckDB: `typeof(V)` over `SELECT t0.*, 1 AS x FROM N AS t0` is
`STRUCT(a INTEGER)`). DataCube's extended columns are exactly this shape. Fix within the ruled design: at the root,
spell a `Star` or `StarExcept` projection over a source with a stored Variant column into its columns
(`StoredReads.select`/`starOver`, `StoredReads.java:120-135`, is the precedent).

### Should-fix

**S1. A UNION or WITH at the root is passed over.** `VariantEgress.java:35` returns the query unchanged unless the root
is a `SqlSelect`. Concatenate lowers to a root `SqlUnion` of star-frame branches (`Lowerer.java:779-799`; confirmed:
`SELECT * ... UNION ALL SELECT * ...` keeps the STRUCT); `FrameCtes.attach` makes a `SqlWith` root in database-mode
judging. The repository's other root-only pass, `RootNumericTypes.java:35-44`, recurses into union branches and the
WITH body.

**S2. The join wrapper drops the frame's ORDER BY from the outermost statement.** `Sorts.java:31-47` puts `join->sort`
keys on the join's star frame; the wrapper (`VariantEgress.java:39-40`) adds no ORDER BY outside, and SQL guarantees
order only at the outermost level (`Render.jsonWireRows`, `Render.java:299-327`, lifts the inner ORDER BY onto its
wrapper). A guarantee issue, not an observed failure.

**S3. The test misses what the pass changes:** DISTINCT star frames, quoted column names, `join->sort`, concatenate,
extend, a star over a Subselect, Cte or Pivot.

### Nits

- `DuckDb.java:277-278`: the comment says "LAST", but `StoredReads` runs after it.
- `LowerRelationTest.java:797` is 129 columns and `:808` is 123.
- `IN_FLIGHT.md:764` (in cbd8f014f) is 133 columns.

### Verified correct

- Pass order and root-only: `write` (`AnsiSqlRenderer.java:81-88`) calls `rewriteRoot` once per pass, in order:
  FloatCanon, the dialect's passes with VariantEgress last, then `StoredReads`; VariantEgress overrides `rewriteRoot`
  without a deep walk. StoredReads cannot double the read (DuckDB does not read SEMISTRUCTURED, JSON, ARRAY or OBJECT
  as text: `readsAsText`, `AnsiSqlRenderer.java:159-168`).
- Join references read through the hook: `Column.of(alias, OutputCol)` keeps the output's origin and type
  (`SqlExpr.java:385-394`).
- Labels match `SELECT *`: DuckDB labels `t0.abc` by the catalog spelling, and `columnName` delimits `PHYSICAL_QUOTED`
  names (`AnsiSqlRenderer.java:1479-1483`); `CAST(VARCHAR AS JSON)` keeps whitespace, so DISTINCT over text storage is
  unchanged; `withProjections` keeps `distinct`.
- Other sources: Table, Subselect, Cte, Values, RawSql, SourceUrl, Pivot and VarSetPlaceholder all have an alias;
  `Dual.alias()` throws, but a Dual frame has no stored Variant output.
- Projection frames and stars over DERIVED outputs are left alone.
- Wire wrappers (`WireRender.asSelect`/`_wire0`, `jsonWireRows`, `csvWire`, `jsonWire`) make the root a non-star
  wrapper whose `Column.of(rowAlias, oc)` references already went through the hook: unchanged. The executor's type
  probe (`WireTypes.reconcile`) skips star frames: unaffected.
- Other dialects: none extends `DuckDb`; H2 builds on `AnsiSqlRenderer.passes()`; only DuckDB and the WASM planner
  change.
- The test's JSON-type check and the shared `CAST(V AS VARCHAR)` row comparison both fail without the fix for the
  VARCHAR and STRUCT storages; the join rows are deterministic.
- No pinned SQL breaks: no pin in core/src/test, spec, pct, datacube, wasm or parser-equivalence has a root `SELECT *`
  over a DuckDB Variant/JSON column.
- Guardrails (`CodeShapeGuardrailTest`): `withProjections`, not `withOutputs`/`outputsOf`; `Column.of`, not a raw
  constructor; no mutable fields; the same header as `StoredReads.java`; the core BUILD globs its sources.
- Docs: accurate except for the scope (B1, S1); within 120 columns; no local paths; the announcement lists every
  file the commit touches.

## Taken (de1506d92; rebased onto main 36b72ee13 as 2daa5bfa1, byte-identical)

B1 and S1: the pass spells every root-level star (`Stars.columns`) and recurses into a UNION's branches and a WITH's
body. S2: gone with the wrapper, a join's sides are listed in place. S3: the test runs ten shapes (bare, sort and
limit, filter, sort, distinct, select, extend, join, a sorted join, concatenate). The nits: the comment, the two test
lines, and the IN_FLIGHT line (rewrapped in the landed update).
