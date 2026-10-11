# DataCube and the Pure model: shape data, make classes, relate them, build dashboards on them (2026-10-10)

A design for the user's agreement. Revision 3: **two modes** (the user, 2026-10-10: "datacube should not run models/
modelled queries it should CREATE models ... then there really is another mode of making nice dashboards FROM those
models"). Revision 2's "every source a class" and "one way of working" are withdrawn (§12). It replaces what DataCube has
or planned for filters across tiles: page filters and a chart's clicks reaching grids by **column name** (batch 1 of
docs/DATACUBE_CHARTS_DESIGN_2026_10_09.md: built on its branch, not landed), and the dashboards design's **links**
(docs/DATACUBE_DASHBOARDS_DESIGN_2026_09_28.md §5.3, a format of DataCube's own).

## 0. In one paragraph

Three layers, each with its own join. **Prep** is data engineering, Alteryx- or Power Query-style: grids over raw
sources -- files, tables, frames -- read as relations, as DataCube reads them today, shaped with relation operations
(filter, calculated columns, join, union, group and aggregate, choose and rename columns, remove duplicates); its joins
are **relation joins, to make one class** (a Trade spread over three tables joined into one shape). When a shape is
what a person wants -- "that is my Trade" -- **Create a Class…** turns it into the model: the pipeline as a relation
function, a class whose properties are its columns, and a mapping of the class to the function (upstream's own
`promoteRelationToModel`: `~func`). **The model** is the classes made -- several of them -- and the relationships between
them: **ModelJoins, between classes** (a trade's `customer`, a customer's `trades`), as Associations. Tables one to one
with what they mean need no relation join at all: a class of each, related in the model. **Dashboards** are made from
the model -- or a project's: pages of grids, charts and KPIs, and the filters that reach across tiles -- page filters, a
chart's clicks -- by a class's property, through the relationships. **Nothing reaches across tiles without a model**,
and nothing is published without a released one (§7).

## 1. What binds this design (the user's words, 2026-10-10)

- "For 'linked' we should use Pure semantic models I think not make up our own for datacube".
- "associations must be uniquely named always like property names": a route between classes is stated by name.
- "Definitely modelJoins as default 100%"; a Database Join only as a fallback.
- "everything you do in relation space - join filter aggregate etc then you say oh that's exactly how I want my trade
  class to look like please make a trade class out of all these operations I just did that like data engineering /
  altryx mode - then there really is another mode of making nice dashboards FROM those models - it really IS two
  different modes".
- "the rule is no cross linking no whole page filter etc without model and MAYBE no sharing or no 'publishing' to prod
  without models".
- "those are RELATION joins to create a single class not between semantic entities - then you build a model and then
  between models do model joins ... two different layers of things if your tables are just one to one then no relation
  joins at all but if you need to join 3 tables to create the Trades class that's relational".
- "we can write class/mapping (or mapping function)/store out as real pure at the end so people can really do their
  visual modeling/ETL in Datacube and get the classes + the MAPPINGS as real artifacts".
- docs/MODEL_HOME_2026_09_28.md: sources point to models, never copy them; a saved cube pins its model version, with an
  easy upgrade; upstream's APIs and shapes exactly.

## 2. What exists (read 2026-10-10)

- **The same split, elsewhere**: Power BI's Power Query (merge, filter, group, add columns; each query's output a table
  of the model) beside its model and reports; Tableau Prep's flows beside Tableau Desktop; dbt's models beside a
  semantic layer (Looker's views and explores).
- **Upstream's promotion** (legend-engine's REPL, `PromoteRelationToModel` and `RelationClassMappingGenerator`): the last
  relation expression run becomes a function (`<pkg>::function::<Name>Fn`, its return the relation's own type), a
  class (`<pkg>::domain::<Name>Class`, each column a property: `[ /@~<>.]` made `_`, quotes dropped, `makeCamelCase`;
  its type and multiplicity the column's) and a mapping (`<pkg>::mapping::<Name>Mapping`, a Relation class mapping of
  the class to the function, each property its column). Studio's "generate models from a database"
  (`POST /api/pure/v1/relational/generateModelsFromDatabaseSpecification`, core_relational's
  autogeneration/relationalToPure.pure) writes the older form: tables mapped directly, Joins as Associations.
- **Upstream's newest mapping forms** (its relation emit test models, e.g. `relation-modelJoin/mapping/
  modelJoinMapping.pure`): a class mapped to a relation function (`Relation { ~func f():Relation<Any>[1] id: ID, ... }`,
  local properties `+firmId: Integer[1]: FIRM_ID`); an association mapped by a ModelJoin, one typed condition whose two
  parameters are its ends (`{employer: Firm[1], employees: Employee[1] | $employer.id == $employees.firmId}`).
- **The compiler** (ours): Associations compiled as properties of both classes; a class query walking one becomes SQL
  (a to-one end a LEFT JOIN, a to-many end in a filter a semi-join); `~func` mappings and ModelJoins parsed, a ModelJoin
  turned into navigation when its Association exists (normalizer/AssociationSynthesis.java, MappingNormalizer.java
  `synthesizeModelJoinMapping`); a query on one connection (CrossStoreGuard.java). A ModelJoin naming an Association that
  does not exist is refused as "kind ModelJoin not supported" (integration/LegendLiteGapTest.java): wording to correct.
- **Proven for this design's model** (datacube/test/model-join.test.ts, `//datacube:model_join_test`): two classes over
  relation functions, a ModelJoin in a page mapping including both, one connection bound to both stores; a to-one end as
  a column and in a filter, a to-many end in a filter (each once), as a column (a row per trade) and as an aggregate --
  planned by the tab's own compiler, run on DuckDB, rows checked.
- **Legend Query** (ours, query/) walks the class graph: its explorer (query/src/model/graph.ts), its filter along
  association paths, an `exists` at a to-many step (query/src/builder/build.ts).
- **Studio** (studio/README.md) keeps a project as one `.pure` file per element, with workspaces, reviews and released
  versions, its SDLC and Depot in the page or on a server; a review's commit merges its workspace and closes it.
- **DataCube today** is prep's grid already, but for joins and unions: a grid over one source, read as a relation,
  filtered, with calculated columns, grouped, aggregated, pivoted. Each grid has its own model and planner
  (datacube/demo/boot.ts, "a page's grids share no model").

## 3. Prep: shape data, make classes

### 3.1 Sources stay relations

A file, a remote file, a warehouse table, a Python frame, a snapped table: read as today, `#>{<pkg>::DB.<table>}#`,
one model each (the table model the compiler writes: wasm Boundary `tableModel`). No class is made for a source.

### 3.2 What a prep grid does

- Today's: filter, calculated columns, group and aggregate, pivot, sort.
- New: **combining** with another source or another grid's result -- every join legend-lite's relation API has (§3.4)
  -- **choose and rename columns** (`select`, `rename`), **remove duplicates** (`distinct`).
- **A relation join makes one class's rows**, from the tables that hold them (a trade's header, economics and status
  joined into one Trade). It never relates two things a person means apart: a customer's region joined onto each trade
  would be a copy of it inside Trade, not a relationship -- that is the model's (§4).
- Each grid is a pipeline: its source and its steps, in order, its query the relation they make.
- **Charts and KPIs are previews** of a grid: drawn from it, never reaching another tile. No page filters, no
  cross-filtering: in prep a filter is a step of a pipeline.

### 3.3 Create a Class

- **From a grid**: its pipeline as a relation function, a class of its columns, a mapping of the class to the function
  -- upstream's promotion (§2), with its rules for names (`makeCamelCase`, made total: a character a name cannot hold
  made `_`, a leading digit led by `_`, a property two columns would share numbered), each property typed by the
  compiler's own reading of the pipeline (a precise type as its declared parent).
- **Its names**: the class named by the person ("Trade", in a package they choose or the page's); the function and the
  mapping named after it. Each property's name and documentation editable before and after.
- **Read live, or materialized**: live by default -- the class's mapping runs the pipeline where its sources are.
  Materialized on request -- the rows computed into a table (Snap's work), and the class mapped to that table: for a
  dashboard that must be fast, or a source the published place cannot reach (§7).
- **Where it goes**: the page's model (§6). A class made so is a dashboard's source at once (§5).
- **Again**: a grid over a class's rows can be shaped further and made into another class, so classes build on
  classes (`Trade` from `RawTrade`).

### 3.4 Combining in prep: every join legend-lite's relation API has

The user, 2026-10-10: "need to allow more than just equi-join! We should support all the join operators that lite
supports including things like as of join too / lateral / flatten". Each is the compiler's own function, written by the
dialog and lowered by it (builtin/Pure.java's signatures, lowering/Lowerer.java):

- **Join** -- `join(rel1, rel2, JoinKind, {a, b | condition})`, and its prefix form for names both sides have: matching
  rows only (INNER), all of mine (LEFT), all of theirs (RIGHT), everything (FULL), drawn as four small diagrams. Its
  **condition is any condition over both rows**: equal keys the common case (rows of "my column = their column", "+" for
  a key of several), and every other comparison -- `<`, `<=`, `>`, `>=`, `!=`, a range (`$t.date >= $p.start &&
  $t.date < $p.end`), text (`contains`, `startsWith`) -- built as the filter editor builds a condition, its columns from
  both sides; or, for anything the builder does not say, the condition written in Pure, typed by the compiler.
- **Every pair** (a cross join) -- a join whose condition is `true`, its row count said before it runs.
- **As-of join** -- `asOfJoin(rel1, rel2, match)` and `asOfJoin(rel1, rel2, match, join)` (with
  `meta::legend::lite::asOfJoinWithPrefix` for shared names): each of my rows with the nearest row of theirs by the
  match -- the last price at or before my trade's time (`$t.time >= $p.time`) -- among those the join condition keeps
  (the same symbol). The dialog asks the two time columns, which way is near (at or before, at or after), and the keys.
- **For each row, rows from it** -- `lateral(rel, {row | relation})`: a relation made from each row joined back to it.
  Its common case has its own action, **Expand**: a column holding a list or a JSON array made rows, one per element
  (`variant::flatten`, `lateral(x | $x.items->flatten(~item))`), offered from that column's menu, the element's fields
  then taken as calculated columns; any other per-row relation written in Pure.
- **Keep rows that have a match, or that have none** (semi- and anti-joins) -- a filter by `exists` over the other side
  (`relation::exists`, `isEmpty`): my columns only, each row once.
- **Append rows** (union) -- `concatenate`: columns matched by name and type, a column one side lacks left empty, said in
  the dialog.

**The dialog** -- "Join…" from a prep grid's menu, or one grid's tile dropped on another (the dialog filled in): the other
side (another source on the page, another grid's result, a source opened from the dialog -- joining a grid's result is
how a join comes after an aggregate), the form above and its condition, the columns brought over (a checklist; a name
both sides have given a prefix, editable), and **what it will do, before it is applied**: how many of my rows find a
match and how many do not (and which), and whether rows multiply -- a key repeated on their side ("3 customer ids appear
twice: those trades would repeat, and totals count them twice"), shown with a way to see them, then to go ahead or to
aggregate that side first. Each figure is a query the compiler plans: matched, unmatched, the most matches of one row
(for a range or an as-of join, on a sample, and said so).

**What it becomes** -- the grid's source: `trades->join(customers, JoinKind.LEFT, {t, c | $t.customer_id == $c.id})`,
everything DataCube already does over it, as now; order made by joining other grids' results, not by a list of steps.
The grid's saved source records the join -- its two sides (a source, or another grid on the page by its id), its form,
its condition (as the builder's rows when it can be read back as them, else as its Pure), its kind and prefix. Make a
class from a joined grid writes one relation function holding the join. Both sides are read in one place (§4.3's rule:
the tab's DuckDB, Python's, or one warehouse), else refused in words, with what would make it work.

**Python** -- `grid.join(other, on=[('customer_id', 'id')], how='left', keep=['name', 'region'])`,
`grid.join(other, condition='{t, p | $t.date >= $p.start && $t.date < $p.end}')`,
`grid.as_of_join(prices, match=('time', '>=', 'time'), on=[('symbol', 'symbol')])`, `grid.expand('items')`,
`grid.append(other)`.

## 4. The model: classes, and the relationships between them

### 4.1 Classes

The classes made in prep (§3.3) -- as many as a person makes -- and a project's own. The model view shows them as boxes
with their properties; a box's rows a click away; a name renamed with a click.

### 4.2 Relationships: ModelJoins, between classes

Drawn between two classes in the model view: the property on each side (`Trade.customerId` to `Customer.id`), both ends
named (they default to the other class's name), how many on each side (from the keys when there are keys, asked when
not). Written as an Association and a ModelJoin:

```pure
Association page::TradeCustomer
{
  customer: page::Customer[0..1];
  trades: page::Trade[*];
}

page::TradeCustomer: ModelJoin
{
  {customer: page::Customer[1], trades: page::Trade[1] | $customer.id == $trades.customerId}
}
```

A project's own Associations are drawn and walked the same way. Suggestions -- a warehouse's foreign keys, a key column
of one class whose name and type match another's -- are offered to confirm, never applied silently.

### 4.3 One connection

Related classes are read in one place: the tab's DuckDB, Python's DuckDB, or one warehouse. A relationship that would
need two is refused in words, with what would make it work ("Orders is read live from the warehouse and Customers is a
file in this tab: materialize Customers into the warehouse, or snap Orders"). docs/XSTORE_LEG.md is the path to more.

## 5. Dashboards: pages on the model

### 5.1 Tiles over classes

A dashboard grid reads a class -- one made in prep, or a project's, or a saved query's -- as its properties projected:
`Trade.all()->project(~[...])`, then everything DataCube does over columns, as now. Its column chooser shows the class
as a tree, as Legend Query's explorer does: its properties, and through each Association end the related class's
(`customer` > `region`). A to-many end is offered marked as a column (a row per trade: the column and the status bar
say a customer's values repeat, and totals count them again) and as an aggregate (count, sum of trades' notional).

### 5.2 Filters across tiles: by a class's property

The same rule for a page filter, a chart's click, and a grid's own filter editor:

- **What a filter names**: a class, a property, what it lets through -- and, made on a related column, the end it came
  through.
- **Which tiles it reaches**: a tile over the same class directly; a tile over a related class through the named end
  that leads there (one: taken; several, a trade's `buyer` and `seller`: chosen once by name, shown in its words,
  "Page: buyer's region: EMEA"); a tile over an unrelated class not at all, its box saying so ("not on Orders: not
  related to Customer") with a way to relate them.
- **Which way**: the easy default from the one side to the many (a customer filter narrows trades); both ways
  (customers with any trade in EMEA) a power user's choice, per page and per filter -- the page's, never the model's.
- **Where it goes**: into the tile's class query, before its columns are taken (`Trade.all()->filter(t |
  $t.customer.region == 'EMEA')`), never into the tile's own saved filter.
- **A calculated column** reaches its own tile only.

## 6. Where the model lives (decided): the model home DataCube already connects to

The user, 2026-10-10: "didn't we build this indexDb model store ... it's sdlc 'protocol' but managed locally then
promoting to real sdlc is same?" -- yes: the model lives where Studio keeps every project.

- **Untouched sources: nothing kept** (§3.1).
- **From the first class made: a project in the model home** -- the one DataCube already connects to
  (depot-client/src/model-home.ts `connectModelHome`): the page's own SDLC and Depot (level 0, the SDLC's rules compiled
  to WebAssembly, the records in this origin's IndexedDB, so every app the origin serves sees the same projects), or a
  server's. Made quietly, named after the page; the page's edits in a workspace of it, each element its `.pure` file, as
  Studio keeps them. **The page points to it** -- the project, and the exact saved revision it is pinned to (revisions
  are kept, merged or not) -- and holds no copy: the model-home ruling ("sources point to models, never copy them")
  kept everywhere. Several pages point to one project: a prep page and the dashboards built on its classes.
- **Promote** ("Create a project for this", as revision 3 said it): the same project on a server's SDLC -- the same
  protocol, another address (`connectModelHome`'s configuration), its revisions going with it; a released version then
  replaces the pin. Studio opens the project in either place: in the page's own model home, the origin's IndexedDB is
  Studio's too.
- **A page that travels** -- a draft shared, a page file, a notebook -- carries a **bundle**: the page document and its
  project's files at the pinned revision, in the directory form Studio's store already writes (`project.json` and
  `<pkg>/<Name>.pure`, the Studio design's S5). Opened, the bundle's project is imported into the receiver's model home
  and the page re-pinned to the imported revision -- a new commit with the same tree, checked: **a pin must survive as
  the same content**, its revision id kept where the backend can keep it. The bundle is the transport, never a second
  home. Export and import of a project as that directory, and promoting a page's project to a server's SDLC (its history
  as the same git objects where the backend takes history, else its head as one new commit and the page re-pinned), are
  the Studio line's, built in the SDLC's rules (and so in Python's native library too) -- export and import with step 2,
  promote by step 6 (the Studio line, 2026-10-10).
- **Real artifacts**: "Export as Pure" writes the project's files -- stores, relation functions, classes, mappings,
  associations and ModelJoins -- the files Studio opens and the engine runs.

## 7. Sharing and publishing (decided)

- **A draft is shared freely**: a prep page or a dashboard sent to a colleague, marked a draft, as a bundle (§6): the
  page and its project at its pinned revision, imported into the colleague's model home when opened. Collaboration on
  data work, as a workbook is sent.
- **Publishing requires a model, released**: a dashboard put where others rely on it -- a shared folder, embedded,
  scheduled -- has every tile over classes of a **released** model version (pinned), reviewed through Studio (review,
  commit, release); its data where the published place can read it (no file held only in a tab: materialize it into
  the warehouse); prep pages are not published, the classes they made are.

## 8. Python

- Prep on frames: `ll.Page`'s grids are relational already (filter, calculate, group); joins and unions as they come to
  the grid; `grid.make_class('Trade')` writes the same function, class and mapping into the page's model.
- Dashboards over classes: `page.grid(page.cls('Trade'))`; `page.relate(...)` writes an Association and its ModelJoin;
  `page.filter('Customer.region', [...])` names a class's property.
- Python has no IndexedDB: its page's model travels as the page does, **a bundle by default** (§6) -- the notebook's
  page saved as one file, page and project; `page.pure()` gives the project's elements' text. **A project folder** beside
  the notebook, in Studio's layout (`project.json` and one `.pure` file per element), when asked for: kept in git with
  the notebook, served as a project by Studio's server (`sdlc-server --repo`).

## 9. Compile once, plan many (agreed with the Plan Gen / Exec Split line)

Every plan the tab makes compiled the whole model again (wasm/src/main/java/planner/Boundary.java: `plan`, `planJson`,
`planSql` each call `Compiler.compileModel(model)`), and so does every `pure/v1` request with the model as text. A
dashboard's model grows with its classes; measured (§11), each class added 15-19 ms to every plan.

### 9.1 The shape

- **One table of compiled models, keyed by the model's content** (the same text the same entry, an edited model a new
  one -- the rule the plan runner's sessions follow). As built: `com.legend.cache.HeldStore<T>` -- in the cache package,
  keyed by a content `Hash` of the text, as core's Invariant 3 asks of every content-keyed store, the text hashed once
  when held, never per plan -- and `com.legend.HeldModels` over it; `hold(model) -> handle`, compiled the first time its
  text is held; `release(handle)`; entries counted by holders, so the table is bounded by the planners alive. No static
  state in core: each host that holds models owns its instance (the wasm Boundary, for the module and Python's
  library).
- **Lite's own entries take a handle**: plan, plan of a protocol tree, result type; and room for an execution plan's
  entry, which takes the plan and its values and answers the statement and the values to bind (the hole and type rules
  stay `exec.PlanRunner`'s, never written again in TypeScript).
- **`pure/v1` unchanged**: the model as text in the body; whether a request whose text is a held entry's uses it is a
  later change, on a measurement (§10).
- **A handle no longer held** is refused by name; the tab's planner holds again when its module or worker is new.

### 9.2 Reviewed (2026-10-10)

- **A `PureModelContext` is safe to hold and plan many queries against** (the Compiler Rewrite line): the model
  immutable, typing caches filled on first use, derived facts for the graph's lifetime, nothing written per query; a
  plan's runtime and connection through `ctx.withExecutionOverlay(runtime, connection)`, a view sharing every cache.
  Conditions: **one thread at a time per context** (its caches are plain maps: each entry its own lock), and **a context
  belongs to its model text**.
- **The text as the key** (the Plan Gen / Exec Split line): the strongest content key, nothing trusted to a digest; two
  passes over the text, well under a millisecond. Built instead as its content `Hash` -- core's rule for every
  content-keyed store (Invariant 3, ArchitectureTest's funnel into `com.legend.cache`) -- once per hold, not per plan;
  the Plan Gen / Exec Split line's code review agreed (2026-10-10), and asked for two changes, made: each hold its own
  handle (a holder releasing twice takes nothing from another), and a model compiled outside the store's lock.
- Approved in shape by the Plan Gen / Exec Split line.

## 10. Where the code lives: none of it in the browser module

The tab's module and Python's native library are adapters: each export a one-line call into the compiler
(docs/PROTOCOL_PROGRAM_2026_10_05.md invariant 5: what a server answers, the tab asks through `pure/v1`). So:

- **Where a model is kept compiled is the process that plans it, never its home**: a compiled model is a live object of
  one compiler build, not something the SDLC or Depot stores (upstream keeps them apart too).
- **The held models' table** lives in core's planning side, beside `Compiler.compileModel`: the hosts that hold a model
  while a planner over it lives -- the tab and Python -- share one implementation, each owning its instance. **The server does not** (the Studio line, 2026-10-10):
  a request has no holder, so a table there would be a cache across requests that nothing has measured a need for; its
  leg 8 compiles once per request instead and hands the compiled model down (`Runner.run(ModelContext, ...)`,
  `Execution.executeWire(ModelContext, ...)`, agreed with the Plan Gen / Exec Split line).
- **A released version, kept compiled on a server** -- for dashboards published on a server (§7) -- keyed by the
  version (project and version, immutable), as upstream's engine keeps a compiled model per release pointer and as
  docs/MODEL_HOME_2026_09_28.md D-F says: with step 6, and only on a measurement.
- **Compile less, before compiling less often**: a tile over one class need not compile two hundred. The tenet
  (docs/TENETS.md: total knowledge, demand-driven work) asks for what a query reaches to be typed and resolved, not
  every class's mapping. Where a class's 15-19 ms goes is the Compiler Rewrite line's to measure (§11); if it is eager
  work a query never reaches, making it demand-driven shortens every compile -- the first open in the tab, a server's
  request, Python's -- and may leave the server nothing to keep. **It is** (the Compiler Rewrite line, 2026-10-10):
  typing and element compiling are on demand already; parse, name resolution and above all normalization are not -- the
  MappingNormalizer walks every mapping, synthesizing each class mapping's lifted function and every ModelJoin and
  resolving them again as text -- so a tile over one class pays normalization for all of them. Normalizing a class
  mapping on first use is a design change in the normalizer's index, not a cache; after L7, the user's to schedule, its
  input the phase split of `//core:compile_latency` over this design's model.
- **`pure/v1` reusing a held model** is its own change, after leg 8, and only on a measurement (the tab's
  `lambdaRelationType` per call, its model held against compiled): if it is needed, one lookup in the one dispatcher
  (`PureV1Api.route`), before it compiles, keyed by the model's text -- the tab and Python gaining, the server, holding
  nothing, unchanged; written up with the Studio line, the Plan Gen / Exec Split line seeing it (its step 4 works there).
- **Create a Class…'s writer** is a route of legend-lite's server, in core, named after upstream's REPL command
  (`/api/lite/v1/.../promoteRelationToModel`): a relation expression, a class name and a package in; the function, the
  class and the mapping out (by §3.3's rules). The tab and Python ask it through `pure/v1`'s routing, as they ask every
  compiler question. Upstream's `generateModelsFromDatabaseSpecification`, exactly, is a later item, for Studio.
- Built in core -- announced in docs/IN_FLIGHT.md, after the Compiler Rewrite line's L7, its shape sent to both lines
  first; the module's and Python's exports one-line calls into it.

## 11. Measured, and proven

- **The model proven** (§2): `//datacube:model_join_test`.
- **A dashboard's model as it grows** (datacube/test/model-scale.measure.ts, `bazel test //datacube:model_scale_measure
  --test_output=all`, manual, in //gates:heavy), before compile-once, each plan compiling the whole model, ms:

  | classes | model text | table read | class read | class, one end walked |
  |---|---|---|---|---|
  | 1 | 1 KB | 43 | 54 | -- |
  | 10 | 7 KB | 44 | 163 | 163 |
  | 50 | 36 KB | 54 | 721 | 727 |
  | 200 | 146 KB | 78 | 3,812 | 3,925 |

  **After compile-once** (2026-10-10, the tab's module under node; each compile on a module already warm, the median of
  three model texts a line apart; each plan the median of seven over the model held), ms:

  | classes | compile, once | table read | class read | class, one end walked |
  |---|---|---|---|---|
  | 1 | 9 | 5 | 7 | -- |
  | 10 | 125 | 5 | 6 | 7 |
  | 50 | 664 | 5 | 6 | 7 |
  | 200 | 3,937 | 9 | 12 | 14 |

  A plan no longer grows with the model: it is the query's alone. The compile is paid once per model text -- when the
  page opens and when its model is edited -- and grows with the classes, 4 s at 200; where its time goes (parse, resolve,
  normalize, element compile) the Compiler Rewrite line measures after its L7.

  **The boot or the page** (2026-10-11, `docs/datacube-model-homework-2026-10-11/boot-split/`): the page. On a warm
  module an empty model is held in 1 ms, so the boot layer is not in the clock. The same Java through Python's native
  library (GraalVM) holds the 200-class page in 30 ms, against the module's 3,975 ms. So the 4 s comes from how the
  module (TeaVM) runs this compile, about 130x, not from the compile's own work (the JVM: about 13 ms, e56d802c4). Four
  functions take 85% of a profiled hold; naming them needs an unobfuscated build, the owners' step (the Compiler Rewrite
  and Plan Gen / Exec Split lines). **Named the same day by the Compiler Rewrite line**
  (docs/build-inventory/program/TEAVM_UNMODIFIABLE_DESIGN_2026_10_11.md, on its branch): TeaVM 0.15.0's unmodifiable set
  and map scan the whole collection on every lookup, and the compiler's name resolution asks them for every candidate
  package of every name. With upstream's fix (TeaVM 0.16.0) ported, measured on this harness: the boot 3,618 to 299 ms,
  a 200-class page 4,239 to 73 ms. Not landed; the user rules between the port and the TeaVM bump. After it lands,
  `//datacube:model_scale_measure` reruns on the module as the receipt.

## 12. Withdrawn from revision 2

- **Every source a class, made silently** (revision 2's §3.1, step M1): sources stay relations (§3.1); a class is made on
  purpose (§3.3). Revision 2's writer (a table's class beside its Database) becomes Create a Class…'s writer in core
  (§10), its rules kept: upstream's names made total, each property typed by the compiler's reading of the relation.
- **One way of working** (revision 2's §3.8 and decision 8): two modes, for two jobs -- shaping data, and analysing it
  on a model (with the model, between them, a layer of its own: §4). What revision 2 warned of (every filter with two
  meanings) does not arise: filters across tiles exist in one mode only.
- **The model kept in the page's document** (revision 3's §6): the model home instead (§6); a page carries its model
  only as a bundle, when it travels.
- **Columns of a source read back under the source's names**: there is no class of a source to read back; a class made
  from a grid has its own properties, named and editable.

## 13. The plan (agreed 2026-10-10: "let's do it")

Each step lands on its own -- an audit, the local gate, CI, the user's OK -- with Python beside it (§8).

| # | Step | What a person gets |
|---|---|---|
| 0 | **The KPI**, landing first | The KPI tile; this design on main |
| 1 | **Compile once, everywhere it holds** (§9, §10) | The held models' table in core's planning side; the browser module's and Python's native library's exports one-line calls into it; Python holding each frame's model and each page's. The server compiles once per request (the Studio line's leg 8), sharing no table; `pure/v1` reusing a held model later, on a measurement |
| 2 | **Create a Class, an Association, a Data Space** (§15, layers 1 to 2) | A route of legend-lite's server, in core, named after upstream's `promoteRelationToModel`; the dialog (the class's name, its properties' names and documentation); the page's project in the model home, made quietly, the page pinned to its revision (§6); a bundle for a page that travels; a dashboard grid reading a class made; `grid.make_class('Trade')` |
| 3 | **Dashboards on classes** (§5, one class) | Dashboard pages over classes; page filters and a chart's clicks by a class's property -- batch 1's page filters and cross-filtering, rebuilt, landing here; prep pages with previews only |
| 4 | **Combining in prep** (§3.2, §3.4, layer 1) | The join dialog in every form (any condition, as-of, lateral and Expand, with or without a match, append), its match summary; choose and rename, remove duplicates |
| 5 | **The model view** (§4, layer 2) | Classes as boxes, associations as their lines (made by step 2's Create an Association…); related properties walked in the column chooser (§5.1); filters through named ends, both ways a page's choice (§5.2); the one-connection refusal (§4.3) |
| 6 | **Promote and publish** (§6, §7, layer 3 for users) | The page's project promoted to a server's SDLC (its revisions with it); export as Pure; publishing on a released model, its data where the published place can read it (materialize when needed); a released version kept compiled on the server if measured to need it (§10); Studio opening the model |
| 7 | **Measures in the model** (docs/BI_AND_ETL_PLAN_2026_09_29.md B4) | A measure and a dimension declared on a class, offered by name |

Why this order: steps 2 and 3 give the whole loop -- shape, make a class, build a dashboard on it -- early, with one
class; prep's largest gap, combining, next; then relationships, which make dashboards of several classes; publishing
last, on all of it.

Coordination: steps 1 and 2 put code in core -- their files announced in docs/IN_FLIGHT.md first, checked against the
Compiler Rewrite line's L7 step 3; the server's compile is the Studio line's leg 8 (answered: no shared table); step 6
is built on Studio's SDLC and Depot, its shape brought to that line before it is built. Where a large model's compile goes (4 s at 200
classes, §11) the Compiler Rewrite line measures; whether a large model needs more than compile-once follows from it.

## 14. Decisions

Decided (2026-10-10): ModelJoin as the default, a Database Join a fallback; upstream's `~func` and ModelJoin as the forms
DataCube writes, legend-lite's normalizer turning them into its one internal function form (the clean-sheet surface stays
for models written by hand); the model in a project of the model home DataCube connects to -- the page's own (IndexedDB)
until promoted to a server's -- the page pointing to it, pinned to the exact saved revision, a bundle when it travels,
Python's a bundle by default and a project folder on request; filters one side to the many by default, both ways a page's choice, never the model's; a to-many end as a
marked column and as an aggregate; batch 1 split; two modes, nothing across tiles
without a model; relation joins make one class's rows, ModelJoins relate classes.

Decided too ("Yes yes yes yes to all!"): the publishing rules (§7) -- drafts shared freely, publishing on a released
model with data the published place can read; no dashboard over a raw source without Create a Class… (one click, names
from the source); a class read live by default, materialized on request; the steps in §13's order; every join form
legend-lite has in prep (§3.4).

## 15. Step 2, Create a Class, an Association, a Data Space, as it will be built

The user, 2026-10-10: name the actions after Legend's own elements -- **Create a Class…**, **Create an Association…**,
**Create a Data Space…** (as Studio's New menu names them) -- and **create everything** a class needs to run, its
connection and runtime among them; several classes from several grids, related simply: property equality, or a key the
class does not show (a foreign key).

### 15.1 What a person does

- **Create a Class…** (a prep grid's menu): the class's name (from the source's name by default, `trades.csv` a
  `Trade`) and package (the page's by default); each column **a property** (shown on the class: its name by
  upstream's rules, §3.3, editable; its type and multiplicity the compiler's; its documentation), **a key only** (kept in
  the mapping as a local property, `+customerId: Integer[0..1]: customer_id`, for associations, not shown on the class --
  a foreign key), or **left out**; its primary key suggested (a column found unique and never empty); an enumeration
  suggested for a text column of few values (EMEA, AMER, APAC), never made unasked; the Pure it will write, shown;
  **Create**.
- **What a grid makes**: its rows as it shows them at its finest level -- a flat grid's rows (its filter, its calculated
  columns, the columns it shows), or a grouped grid's groups (one row per group, its measures among the properties). A
  pivot is refused in words ("a class has fixed properties: take the pivot off first").
- **Create an Association…** (between two classes on the page): the property or key on each side, equal
  (`$customer.id == $trades.customerId`); both ends named (by default the other class's name: `customer`, `trades`); how
  many on each side worked out from the data -- each trade has at most one customer when `Customer.id` is unique, and
  `[0..1]` when `customer_id` can be empty -- shown to confirm or change. Written as an Association mapped by a
  ModelJoin (§4.2). **Suggested** associations -- a key column whose name and type match another class's primary key,
  `customer_id` and `Customer.id` -- are offered here, never made unasked.
- **Create a Data Space…**: the page's model as one front door, as Legend Query lists models -- its classes (chosen),
  their associations (reviewed), a description; the mapping that includes the classes' mappings and the associations',
  the runtime, the data space's default execution context. Its dialog is also the one-go path: grids not yet classes made
  classes in it, suggested associations offered with them.
- **Then**: the classes are in the page's model at once; "Add a data source" offers the page's classes and its data
  spaces; a grid over a class reads the rows its prep grid showed.
- **Later, with publishing** (step 6): **Create a Service…**, a class's query as a service with its test.

### 15.2 What it writes

With a class, everything it needs to run, in upstream's promotion's layout (legend-engine's REPL
`PromoteRelationToModel`), the person's name kept as given:

- **The class** `<pkg>::domain::Trade`; **its relation function** `<pkg>::function::TradeFn` (the grid's query, its
  return the relation's own type); **its mapping** `<pkg>::mapping::TradeMapping` (`Relation { ~func ... }`, each
  property its column, each key only its local property).
- **One store for the place the rows are**, `<pkg>::store::Browser` (the user, 2026-10-10: "one store for whole
  tab"): ONE Database for the tab's DuckDB, a table per source, each table added with the first class over its source
  (the store's file changes then). Not one per source: lite plans a query over two stores only when one connection
  serves both, and a connection names its store, so a store per source would refuse every query across an
  association; one Database per database is also what the rows are (Studio's "generate from database" writes one).
  A table's name the source's own, kept: the tab's table takes that name when the page opens (a fresh name per open,
  `trades_2`, as today, would break the mapping on reopen); a second source of the same name gets its `_2` once, when
  its table is first written.
- **Its connection and runtime**, for where its rows are now: `<pkg>::connection::Browser` (the tab's DuckDB, in
  memory: `path: ':memory:'`) and `<pkg>::runtime::Browser`, binding the store to it (a warehouse table's, the
  warehouse's). DataCube plans with the project's own runtime, so what DataCube runs is what Studio runs; a published
  version adds the runtime of where it is published (step 6), beside it.
- **The model's mapping**, `<pkg>::mapping::Model`: it includes each class's mapping and holds the associations; the
  runtime names it alone (lite's planner refuses a runtime naming a mapping and one that includes it), and so does a
  data space.
- **Sample rows**, `<pkg>::data::<Table>Sample`: a `Data` element holding the source's first 100 rows (relational CSV
  data) -- the project runs where the file is not (DataCube's and Query's tab fill a project's tables from its Data
  elements when it is opened by name, and Studio's `TabTables` does: plan A2).
- **A mapping test: later** (the user, 2026-10-10: "do the data even if mapping test not there yet"): the class's
  query over the sample rows, its expected rows the class's over them -- written when lite has a mapping-test runner,
  which it has not (`docs/DEFERRED_TEST_EXECUTION.md` is its charter; Studio's tests, A4, are parked).
- With an association, the Association and its ModelJoin, in the model's mapping; with a data space, the data space.

### 15.3 Where it goes: the page's model home

- **The model home DataCube connects to** (§6): the in-page SDLC by default. A project made quietly at the first class,
  named after the page, with a workspace of DataCube's; each class made is one save (one revision); the page document
  gains `model` -- the project, the workspace, the revision it is pinned to (the page document's next version).
- **One origin**: the model home is per origin and per browser profile; DataCube and Studio see each other's projects
  when served together (the site), not from two origins (the separate demos on their own ports).
- **Storage that lasts**: at the first class made, DataCube asks the browser for persistent storage
  (`navigator.storage.persist()`); refused, the page says so -- its model lives in this browser's storage, which the
  browser or a person may clear.
- **A save refused as stale** (the SDLC's revision lock: Studio saved the project since): DataCube reads the project
  again and makes its change again when it still applies (a new class's files), else says what changed.
- **A page exported as a file, its model held only here**: refused, in words, until the Studio line's export (S23) lets
  the file carry its model; a page whose model is on a server's SDLC exports as it does today, pointing to it.

### 15.4 Reading a class

A grid over a class plans `<pkg>::domain::Trade.all()->project(~[...])`, its columns the properties, against the
project's elements and the page's runtime, through the mapping -- a planner holding that model (step 1). A class made
again (renamed, a property added) is a new revision; the page re-pins to it, and its grids plan against the model held
anew. **A class made again is its own relation's** (as built, 2026-10-10, the audit's C2): a class of the same name
over other tables is another class and is refused by name, never written over; a sample of the same name holding
another table's rows likewise.

### 15.5 Python

- `grid.create_class('Trade', package=..., keys=['customer_id'])`, `page.create_association(trade, 'customerId',
  customer, 'id', ends=('customer', 'trades'))`, `page.create_data_space(...)`: the same routes, through the native
  library.
- **Python's page is its model's home** -- a notebook has no IndexedDB and needs no SDLC: the page document carries
  the model's files (`model.files`, Studio's directory form: `<pkg>/domain/Trade.pure`, ...); Python's engine serves them
  as model text, which the widget's DataCube plans with. A class made in the widget's DataCube is written into Python's
  page through the page channel, not into the notebook's browser storage.
- `page.grid(page.cls('Trade'))` reads a class; "create a project" (into an SDLC) is later, with promote (step 6).
- **Ordered after DataCube headless (the user, 2026-10-11).** These calls come after
  `docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md`'s steps 0-2: DataCube's own code runs in Python, so
  `grid.create_class` makes its class from the grid as DataCube means it (its filters and calculated columns), not only
  from frames and written relations.

### 15.6 The route

- **`POST /api/lite/v1/relational/promoteRelationToModel`** on legend-lite's server, in core, named after upstream's
  REPL command, in `PureV1Api.route` beside `/api/lite/v1/compilation/compile` (agreed with the Studio line,
  2026-10-10). In: **the model as a model context** (`{"_type":"text", ...}` -- read as the compile route reads it, so
  when its leg 8 makes every route take `{"_type":"data", ...}` too, this one does), the relation (protocol JSON), the
  class's name, its package, and each property's name and documentation when the person changed them. Out: the elements
  to write in **the SDLC's own change shape, `{type: CREATE|MODIFY, path, pureCode}`** (as built, 2026-10-10: a
  second class modifies the store and the model's mapping, so the route says which; DataCube hands the changes to
  `performPureChanges` unchanged), `path` the element's path (`pkg::domain::Trade`; a function's its signature's name,
  `TradeFn__Relation_1_`, as the grammar names it; the SDLC maps it to its file, `Sdlc.filePathOf`, so the engine's
  route knows nothing of the SDLC's layout -- the Studio design's S1) -- every file of §15.2 the class needs that the
  model does not hold as written. Names by §3.3's rules, types by the compiler's reading of the relation; refused by
  name, in the server's own kinds (400 COMPILATION), what cannot be a class: a pivot's dynamic columns, a column of no
  type a property can hold (left out, its type is the relation's still), a relation reading a store its place does not
  write, a class's or a sample's name taken by another table's, a name Pure reads as a word of its own (`let`, `true`:
  upstream's printer writes it unquoted, so its file would not read back). The model DataCube sends is the project's
  files and the source's model, the latter under a section header of its own (the Plan Gen line's review).
- **Asked through `pure/v1`'s routing**, as the tab and Python ask every compiler question
  (PROTOCOL_PROGRAM invariant 5): `pureV1OrError` in the tab, `lite_pure_v1` in Python; no export of its own. Built on
  this line's branch; the Studio line edits `PureV1Api` in its leg 8 step 3 too, and whichever lands second rebases.

### 15.6a The routes, as they go on (the user, 2026-10-11)

Agreed after Create a Class landed (0cee56761): **the routes are named after Legend's actions, and each is atomic**.

- **Three routes, one writer**: `POST /api/lite/v1/model/createClass`, `/api/lite/v1/model/createAssociation`,
  `/api/lite/v1/model/createDataSpace` (`promoteRelationToModel` renamed -- the name was the REPL command's, and
  nothing here is in parity with upstream, which has no HTTP route that does this; the `relational` segment dropped:
  an association and a data space are not relational). One writer in core applies a list of actions to the project in
  memory, in order, checks the whole result ONCE -- it compiles, each class and each association's navigation plans --
  and answers the net changes (`{type: CREATE|MODIFY, path, pureCode}`, one element's file each); each route is a door
  into it: `createClass` one class; `createAssociation` an association and any classes it needs; `createDataSpace` a
  data space and any classes and associations it needs. All or nothing: a refusal anywhere writes nothing. DataCube
  saves the changes as one revision, over the revision it read (a stale save asked again over the fresh project).
- **Upstream has each piece and not the whole** (the user's question, 2026-10-11): the relation's columns
  (`lambdaRelationType`), reading and printing elements (`grammarToJson/model`, `jsonToGrammar/model`), the check
  (`compilation/compile`); `generateModelsFromDatabaseSpecification` makes a class per table of a Database (classic
  table mappings), not a class of a grid's shaped rows. Kept as one Java path, not decomposed into the engine's pieces
  and the rest: one answer, no drift between two ways of making it; checked against legend-engine instead (its own
  compile of the files written). Asked of the tab's module on every plane -- the module beside a server's planner
  already writes a table's model there (`demo/planners.ts`) -- so Create a Class… is offered wherever DataCube plans.
- **The request**: `{"project": {"_type":"text","code": the project's files}, "classes": [...], "associations": [...],
  "dataSpace": {...}}` (`createClass` takes `class`, one). The project is the model the changes are made to; **each
  class carries its own source** (`"source": {"_type":"text", ...}`, the grid's planner's model: a page's grids each
  read their own tab model, each naming its Database `local::DB`, so no one model can hold them) with its relation,
  names, columns and `where` (§15.6). The check compiles the project's files and the changes ALONE -- no source -- so
  a file reading what the project does not hold is refused by construction.
- **An association**: `{"ends": [{"class", "on", "name"?, "multiplicity"?}, {...}], "name"?}` -- each end a class of
  the project (or one made in the same request), the property or key it joins on (`on`: a property of the class, or a
  key only -- its mapping's local property), the name the OTHER class reaches it by, and how many of it one of the
  other has: Pure's own Association shape (`customer: Customer[0..1]; trades: Trade[*];`). Written: the Association,
  `<pkg>::domain::<A>_<B>`, and its ModelJoin in `<pkg>::mapping::Model` (`{customer: Customer[1], trades: Trade[1] |
  $customer.id == $trades.customerId}`). Its ends named by default after the other class (`customer`, `trades` --
  plural when many); how many from the data: an end is `[0..1]` when its `on` is unique over its class's rows, `[*]`
  when not (the facts DataCube reads in the tab -- `"unique": true|false` beside `on` -- else asked of the person).
  Refused: ends of two packages, an `on` neither a property nor a key, two `on`s of different types, an end's name a
  property its class has already.
- **Suggestions** (offered, never made unasked): every answer lists the pairs a key's name and type match another
  class's property by (`customer_id`, `Customer.id`): the Association window offers them, the Data Space window as
  checkboxes.
- **A data space**: `{"name", "title"?, "description"?, "classes"?}` -- `<pkg>::<Name>`, its one execution context the
  place's (`Browser`: the model's mapping, the runtime that names it), its classes the ones named (all the model's
  classes when none are).
- **Not written over** (the audit of the second landing, 2026-10-11): the writer replaces only what it made. A class
  or class mapping with no grid's function behind it, an association the model's mapping does not join, and a data
  space over another mapping (each written in Studio, say) are refused by name; so is an element of another kind at
  the path, and a path that is also a package of the project (`trades::domain` beside `trades::domain::Trade`:
  legend-engine refuses that project). The same association asked with its ends the other way round is itself made
  again: nothing changes.
- **Where in DataCube**: the page's menu, a Model group -- Create an Association…, Create a Data Space… (the whole
  page in one go: its grids not yet classes made classes in the same window, suggested associations as checkboxes);
  a grid's menu also offers Create an Association…, that grid's class one end.

### 15.7 What judges it

- The routes (core): names, types, multiplicities, the layout, the store written once, key-only columns as local
  properties, the runtime and connection, the sample rows and the mapping test (passing), an association's ends and
  multiplicities from the data, a data space; the refusals.
- The browser (Chromium): two prep grids made classes and associated; a data space over them; a grid over a class
  reads the same rows as its prep grid; the page reopened reads
  them again; Studio, served on the same origin, opens the project and finds the class; a stale save made again; a page
  file export refused while its model is only here.
- Python: `create_class`, `create_association`, `create_data_space`, the page document carrying the model's files, the
  widget reading a class.
- **As built in step 2's first landing (Create a Class…, 2026-10-10)**: the route's tests (core) for a class -- the
  sample proved by lite's own ServiceTestRunner loading it and the class reading it, the mapping test waiting for a
  runner (§15.2); the browser: two grids made classes, the second reading the first's files back as changed, the page
  file export refused, the share link saying the classes stay here, the page saved, reloaded and reopened with each
  grid's table under its kept name; a stale save made again once, its workspace made again after a commit, in unit tests
  against a stand-in SDLC; Studio opening the project and Query reading the class at HEAD tried by hand on the one-origin
  site (a site-level check is the Studio line's, `site/verify.mjs`, offered). **Not in this landing**: associations and
  data spaces (§15.1's next two), a grid over a class (step 3's dashboards read classes), Python (§15.5); a page reopened
  in a tab where another grid holds a kept table's name reads it under a fresh one, and says so.
- **As built in step 2's second landing (Create an Association…, Create a Data Space…, the one-go routes,
  2026-10-11)**: the routes' tests (core): an association in Pure's own shape, its ModelJoin in the model mapping,
  walked both ways through a key-only column; the suggestions; the whole page in one request, all or nothing. **The
  engine's own check**: `ModelWriterUpstreamTest` pins the files one request writes (two classes with their sample
  rows, their association, the data space) with legend-engine 4.145.0's answers to them, recorded by
  `docs/datacube-model-homework-2026-10-11/record-upstream.sh`: the project compiled, and each class's query and both
  walks planned. It caught one difference: a `~func` names its function by descriptor, and a descriptor's types take
  no package (legend-pure's `FunctionDescriptor`). The engine looks `TradeFn():meta::pure::...::Relation<...>[1]` up as
  a plain path and finds nothing; lite's compiler took it. The Compiler Rewrite line's landing right after this one makes
  lite refuse it as the engine does (agreed 2026-10-11: it lands second, since main's writer still writes that form).
  The writer now writes
  `TradeFn():Relation<Any>[1]`, the form of upstream's own tests and Studio's. The browser: two grids made classes,
  the association from the page's Model menu, its how-many read from the rows in the tab, the data space; and the
  whole page in one go in a fresh tab.

### 15.8 Not in step 2

Dashboard pages and filters across tiles (step 3); relation joins in prep (step 4); the model view, walking related
properties in the column chooser, filters through an association's ends (step 5); Create a Service…, promote, bundles,
publishing (step 6, and the Studio line's S23).
