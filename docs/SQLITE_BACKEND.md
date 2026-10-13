> **Built, 2026-10-11:** the dialect is `core/.../sql/dialect/SQLite.java`, measured on the five PCT suites
> (`docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §12). The note below is the design's state when it was written.
>
> ## ⚠ NOT BUILT — status note, 2026-08-06
>
> This is a **feasibility design**. No such dialect exists in `core/`. The
> dialects that do exist are `DuckDb`, `H2`, `H2Modern`, and the
> `EngineStyleH2`/`DB2`/`Composite` chain, all under `sql/dialect/`. SQLite is
> not a class — it is `Lexicon.SQLITE` passed to `AnsiSqlRenderer`.
>
> The analysis is still usable as a starting point; just do not read it as
> describing shipped behaviour.

# SQLite as a real backend — design

> **Question asked:** add SQLite as a real execution backend, on the same terms `H2_BACKEND.md`
> asked for H2. This doc answers the capability half. There is no golden-text half: the engine's
> corpus has no SQLite goldens, so **row equality is the only contract** and §6 of the H2 doc has no
> analogue here.
>
> **Companions:** `H2_BACKEND.md` (the method, and the backend this one is measured against),
> `POSTGRES_BACKEND.md`, `MARIADB_BACKEND.md`, `BACKEND_PORTABILITY.md` (the cross-cutting
> conclusions — read that one for the architecture; this one is the SQLite evidence).

**Evidence standard.** The capability map was produced by **executing 2,400+ probe statements
against a real `org.xerial:sqlite-jdbc:3.47.1.0`** (SQLite 3.47.1), every probe also run against
DuckDB 1.5.0.0 as the reference value, through one shared harness so the outputs are comparable
cell-by-cell. **Value equality was checked, not just parse success** — most findings below are
statements that *execute fine* and return the *wrong answer*. Where documentation and execution
disagreed, execution won. Claims sourced any other way are marked.

---

## 1. Verdict

**Achievable, and materially easier than H2 — but it carries the worst silent-corruption surface of
any backend measured.**

- **238 of 267 constructs are reachable — 89%**, against H2's measured 80%. SQLite is the *weaker*
  engine and the *better* target, because its gaps are honest errors while H2's were structural.
- **H2's fatal wall does not exist here.** `H2_BACKEND.md` §4 D1 — no `LATERAL`, no correlated table
  function — is a fact about H2 alone. SQLite has **correlated row explosion** via `json_each`,
  verified in every position including inside a scalar subquery. The entire `LIST_*` family,
  `UNNEST`, `VARIANT_ELEMENTS` and `RANGE_FN` are reachable by rewrite. §3.
- **The real cost is that SQLite has no types**, only storage-class affinity. `CAST` cannot fail.
  This produces wrong rows *silently*, with no error, on the plainest possible paths. §4 is the
  operative section of this document and it is longer than the capability map on purpose.
- **The driver is not stock SQLite.** `sqlite-jdbc` 3.47.1.0 compiles in `SQLITE_ENABLE_MATH_FUNCTIONS`
  *and* registers `extension-functions.c` — 21 non-stock functions. That is a dependency on
  driver-private behaviour and mostly a trap. §5.
- **Graph fetch works, in one statement, byte-for-byte identical to DuckDB.** §6.
- **`Ddl` must emit `TEXT` for temporal columns.** One line; removes an entire corruption class. §4.1.

---

## 2. The capability map — 267 constructs

| Slice | A native | C rendering | B rewrite | D impossible | total |
|---|---:|---:|---:|---:|---:|
| Strings / regex / encoding | 13 | 3 | 14 | **11** | 41 |
| Math / bitwise / arithmetic / null | 49 | 3 | 6 | 2 | 60 |
| Temporal | **0** | 13 | 9 | 3 | 25 |
| Collections / maps / variant | 1 | 14 | 33 | 2 | 50 |
| Query shapes / types / aggregates | 42 | 22 | 16 | **11** | 91 |
| **Total** | **105** | **55** | **78** | **29** | **267** |
| | 39% | 21% | 29% | **11%** | |

**Reachable (A+C+B) = 238/267 = 89%.**

> **Denominator caveat.** 267 is this project's slicing, not H2_BACKEND.md's 256 (163 SqlFn + 29
> SqlExpr + 14 sources/joins + 14 types + 36 aggregates). The two partition the surface differently
> and overlap imperfectly. The percentages are comparable in spirit; the raw counts are not
> like-for-like. Do not subtract one from the other.

**Zero A rows in the entire temporal slice** — not one temporal construct survives at the DuckDB
spelling. That is the single most concentrated dialect cost.

**No aggregate is a D.** All 11 window functions, ROWS/RANGE/GROUPS, named windows and `EXCLUDE`
work. SQLite's window wall is the *frame*, not the function (§7).

---

## 3. Correlated explosion exists — H2's D1 does not transfer

`FROM t, json_each(t.arr)` accepts a **row-correlated argument**. Verified in every position:
comma-join, `CROSS JOIN`, `LEFT JOIN … ON 1=1`, inside a scalar subquery, and with the argument as an
arbitrary expression over one *or several* outer rows.

```sql
-- correlated json_each inside a scalar subquery — the exact shape H2 cannot express
SELECT (SELECT sum(value) FROM json_each(e.arr)) FROM (SELECT '[10,20,30]' AS arr) e;   --> 60
```

The load-bearing case for graph fetch is that **the outer row must survive an empty collection**:
inner join gives 3 rows, `LEFT JOIN json_each(...) ON 1=1` gives 6 — the three employees with empty
project lists are preserved. So `CROSS_LATERAL` and `LEFT_LATERAL` are **A/C, not D**.

The full round trip — build a carrier column by aggregation, correlate-explode, filter, re-aggregate
— returns the identical value on DuckDB, SQLite and Postgres.

**The only real constraints are value-level, never placement-level:** the argument must be valid
JSON, and `json_each(NULL)` safely yields zero rows. SQLite lacks only the `AS t(c1,c2)` column-alias
list.

**Consequence for sequencing:** the §4.1 collection-carrier redesign is **not a precondition for
SQLite**. It is H2's compatibility mode. See `BACKEND_PORTABILITY.md`.

---

## 4. The operative section — SQLite has no types, and `CAST` cannot fail

Every row below **executes without error** and returns a wrong value.

| Expression | DuckDB | **SQLite** | Why it matters |
|---|---|---|---|
| `CAST('2020-01-15' AS DATE)` | `LocalDate:2020-01-15` | **`Integer:2020`** | leading digits parsed, month+day **discarded** |
| `CAST('abc' AS INTEGER)` | error | **`0`** | no cast ever fails |
| `CAST('{"a":1}' AS JSON)` | JSON | **`0`** | |
| `CAST(1 AS DECIMAL)/CAST(3 AS DECIMAL)` | `0.333…` | **`0`** | every DECIMAL is an IEEE double; this one is integer division |
| `7/2` | `3.5` | **`3`** | integer division |
| `sal % 2` (sal=100.5) | `0.5` | **`0.0`** | `%` truncates float operands |
| `greatest(sal, 100)` where `sal IS NULL` | `100` | **`NULL`** | SQLite's `max`/`min` **propagate** NULL; these are the clamps on `SUBSTRING` |
| `round(1.005, 2)` | `1.01` | **`1.0`** | no exact decimal type |
| default NULL sort order | LAST/LAST | **FIRST/LAST** | `dense_rank` sums 9 vs 9 vs 11 across the three |

### 4.1 The one-line fix that removes a whole class

`DATE` / `TIMESTAMP` / `DATETIME` columns carry **NUMERIC affinity** in SQLite. Inserting `'2021'`
stores the integer 2021. Declaring the column `TEXT` instead makes ISO-8601 text sort, compare and
round-trip correctly — **including BC dates**, verified.

> **`Ddl` must emit `TEXT` for temporal columns on the SQLite target.** Not `DATE`. This is the
> highest value-per-character change in this document.

### 4.2 What this means for the contract

The project's tenet is *loud walls over wrong rows*. SQLite's design is the opposite: it never
raises, it coerces. So **the wall has to be built in the lowering**, because the backend will not
build one. Two consequences:

- `SqlDialect.normalize` is not optional on SQLite — it is load-bearing for correctness, not
  cosmetics. §8.
- Any construct whose correctness depends on the backend rejecting bad input must be re-checked
  here. `CAST` is the whole family.

---

## 5. The driver is not stock SQLite

`pragma_function_list` returned **170 functions, 21 of them non-stock** — `sqlite-jdbc` bundles
`extension-functions.c`. Also compiled in: `SQLITE_ENABLE_MATH_FUNCTIONS` (so `sin`/`ln`/`pow`/`sqrt`
are A, not D — only `cbrt` is absent), the full JSON1 surface, the 3.45+ `jsonb_*` family, native
`median()`, and in-aggregate `ORDER BY` (3.44+).

**The 21 extension functions are mostly traps.** Only `reverse` matches DuckDB semantically:

| | trap |
|---|---|
| `padl` | 2-arg, space-only, **never truncates** |
| `charindex` | **arguments reversed** vs `strpos` |
| `rightstr(s,-2)` | fails `SQLITE_NOMEM` |
| `replicate(s,-1)` | throws where `repeat` returns `''` |
| `sign`, `cot` | come from the **driver's** table, not SQLite core — stock `sqlite3` lacks both |

**Recommendation: build on none of them but `reverse`.** Pure-SQL fallbacks were verified for `sign`
and `cot`. Binding the dialect to a driver-private function table would make legend-lite's
correctness depend on which SQLite build the deployment happens to link — exactly the class of
hidden coupling `AGENTS.md` forbids.

The census also **proves absence**, which is the more valuable half: a `COUNT(*)` over
`{regexp, md5, sha1, sha256, base64, uuid, levenshtein, split_part, ascii, chr, lpad, left, right,
repeat, starts_with, greatest}` returned **0**, and `load_extension` fails. **SQLite has no regex
engine at all** — 5 of the 11 string D's.

---

## 6. Graph fetch — works, and is byte-identical to DuckDB

The nested object-graph envelope runs in **one statement**, three levels deep, using
`json_group_array`/`json_object`. Verified: nested JSON stays JSON rather than becoming a quoted
string; ordered aggregation with `DESC`; empty collection produces `[]` not NULL.

**SQLite's 3-level output is byte-for-byte identical to DuckDB's.** Both **keep** nulls by default —
the opposite of H2's `ABSENT ON NULL`, which needed an explicit override.

> **Emit `json_*`, never `jsonb_*`, for envelopes.** SQLite's `json` reads back as `String`; `jsonb`
> reads back as **`byte[]`** — the exact failure mode `H2_BACKEND.md` §3 recorded for H2.

---

## 7. Where SQLite actually stops

| | Gap | Note |
|---|---|---|
| **S1** | **No `LATERAL`** in any spelling | but `json_each` correlation covers the need (§3); ASOF is B via a correlated scalar subquery, value-verified |
| **S2** | **No `INTERVAL` token at all** | the window *frame* wall. B-form verified: `ORDER BY julianday(hired) RANGE BETWEEN 1 PRECEDING` |
| **S3** | **No regex engine** | 5 string D's; `load_extension` fails |
| **S4** | **No ARRAY type**, no composite/struct, no wide numerics | **10 of SQLite's 11 shape-D's are this one thing** |
| **S5** | No exact decimal | `Decimal` at scale ≥1 is a genuine D (`round(1.005,2)`) |
| **S6** | `INTERSECT ALL` / `EXCEPT ALL` | syntax error at `ALL`; plain forms are A |
| **S7** | `IGNORE NULLS`, aggregate `ORDER BY` inside `OVER`, `DISTINCT` in a windowed aggregate | refused identically by Postgres — the only D-class window rows on either |

`QUALIFY` is absent, and `QualifyToSubselect` already closes it. Nothing to do.

**Hard limits for the capability budget:** **64 tables per join**, **500 compound terms**.

### 7.1 Two soft D's worth reclaiming

SQLite supports **correlated recursive CTEs inside scalar subqueries** — the primitive H2 lacks.
That was used to build and verify **byte-exact base64 encode/decode** and a **full Levenshtein DP**
(6/6 matching DuckDB). Both are **B, not D**. Where a function is missing but the semantics are
computable, SQLite can usually get there; the D's above are the cases where the *type system*, not
the function set, is the obstacle.

---

## 8. `SqlDialect.normalize` — eight families

SQLite needs twice what H2 needed. This is the specification:

| `SqlType` | JDBC returns | canonical |
|---|---|---|
| `BOOLEAN` | **`Integer`** 1/0 (every predicate) | `Boolean` |
| `DATE`, `TIMESTAMP` | `String` | date/timestamp |
| `JSON` | `String` (but `jsonb` → `byte[]`) | JSON |
| `DECIMAL` | **`Double`** — scale is gone | `BigDecimal` |
| `HUGEINT` | `Double` / clamped `Long` | 128-bit |
| `COUNT(*)`, `SUM(int)` | `Integer` | `Long` |
| `LENGTH`, `STRPOS` | `Integer` (DuckDB: `Long`) | `Long` |
| per-row type instability | a column can return different Java types row to row | — |

That last row has no H2 analogue and is the reason normalize must be driven by the **declared SQL
type**, never by `instanceof` on the returned object.

---

## 9. Sequencing

1. **`Ddl` emits `TEXT` for temporal columns** (§4.1). One line, removes a corruption class.
2. **Author `Lexicon.SQLITE` / `TypeNames.SQLITE` / `Spellings.SQLITE`** — the T3.2 seam already
   exists and adding a row cannot break DuckDB.
3. **Populate `SqlDialect.normalize` for the eight families** (§8). On SQLite this is correctness,
   not cosmetics — do it *before* the first row-equality gate, or the gate is not an oracle.
4. **Render `ARRAY[…]` instead of `[…]`** — free portability win, also correct on Postgres.
5. **The JSON carrier for `SqlType.Array`** — see `BACKEND_PORTABILITY.md`. SQLite has no array type,
   so it forces the question the way H2 forced the correlated-explosion question.
6. **Reconcile dialect and connection** (H2_BACKEND.md H5.4) — a prerequisite for any second backend,
   not a SQLite-specific task.
7. **The declared-gap registry** (H2_BACKEND.md §9) — 29 honest D's need a machine-readable home.
8. **Portability sweep** `-Drcorpus.backend=sqlite`.

SQLite is already at **compile scope in `core/pom.xml`** — none of the §11 dependency work the H2
doc describes is needed here.

---

## 10. What NOT to do

- **Don't build on the driver's extension functions** (§5) except `reverse`. It couples correctness
  to which SQLite build is linked.
- **Don't declare temporal columns `DATE`.** NUMERIC affinity silently destroys the value (§4.1).
- **Don't emit `jsonb_*` in graph-fetch envelopes** — `byte[]` read-back, the H2 failure mode (§6).
- **Don't treat `CAST` as a validating operation.** It never fails; it coerces (§4).
- **Don't assume a missing function is a D.** Correlated recursive CTEs reclaimed base64 and
  Levenshtein (§7.1).
- **Don't port H2's collection-carrier conclusion here.** Different wall, different remedy (§3).