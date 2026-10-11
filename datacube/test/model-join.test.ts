// The Pure a page's model is made of (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §3.3, §4.2, §11's first proof): two
// classes, each over its own store and relation function, with its mapping (upstream's newest form, a class mapped to a
// relation function, `~func`), related by an Association that a ModelJoin maps, in a page mapping that includes both;
// the runtime binds both stores to the tab's one connection. Class queries walking the relationship -- a to-one end as a
// column and in a filter, a to-many end in a filter (`exists`), as a column (a row per trade) and as an aggregate -- are
// planned by legend-lite's own compiler (the WASM module the tab loads) and run on DuckDB, rows checked.

import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { openDatabase } from '#test-host';

import { DuckDbEngine } from '../../engine-client/src/duckdb.ts';
import { plannerFor } from './catalog-builder.ts';

const MODEL = `
###Relational
Database files::trades::Store ( Table trades ( tradeId INTEGER PRIMARY KEY, customerId INTEGER, notional DOUBLE ) )
Database files::customers::Store ( Table customers ( id INTEGER PRIMARY KEY, name VARCHAR(64), region VARCHAR(16) ) )

###Pure
function files::trades::read(): meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1]
{
  #>{files::trades::Store.trades}#
}

function files::customers::read(): meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1]
{
  #>{files::customers::Store.customers}#
}

Class files::trades::Trade
{
  tradeId: Integer[1];
  customerId: Integer[0..1];
  notional: Float[0..1];
}

Class files::customers::Customer
{
  id: Integer[1];
  name: String[0..1];
  region: String[0..1];
}

Association page::TradeCustomer
{
  customer: files::customers::Customer[0..1];
  trades: files::trades::Trade[*];
}

###Mapping
import meta::pure::metamodel::relation::*;
import meta::pure::metamodel::type::*;

Mapping files::trades::Mapping
(
  *files::trades::Trade[trade]: Relation
  {
    ~func files::trades::read():Relation<Any>[1]
    tradeId: tradeId,
    customerId: customerId,
    notional: notional
  }
)

Mapping files::customers::Mapping
(
  *files::customers::Customer[customer]: Relation
  {
    ~func files::customers::read():Relation<Any>[1]
    id: id,
    name: name,
    region: region
  }
)

Mapping page::Mapping
(
  include files::trades::Mapping
  include files::customers::Mapping

  page::TradeCustomer: ModelJoin
  {
    {customer: files::customers::Customer[1], trades: files::trades::Trade[1] | $customer.id == $trades.customerId}
  }
)

###Connection
RelationalDatabaseConnection page::Connection
{
  store: files::trades::Store;
  type: DuckDB;
  specification: DuckDB { };
  auth: Test;
}

###Runtime
Runtime page::Runtime
{
  mappings: [ page::Mapping ];
  connections:
  [
    files::trades::Store: [ trades: page::Connection ],
    files::customers::Store: [ customers: page::Connection ]
  ];
}
`;

const SEED = [
  'CREATE TABLE customers (id INTEGER PRIMARY KEY, name VARCHAR, region VARCHAR)',
  // Gamma has no trades
  "INSERT INTO customers VALUES (1, 'Acme', 'EMEA'), (2, 'Beta', 'AMER'), (3, 'Gamma', 'EMEA')",
  'CREATE TABLE trades (tradeId INTEGER PRIMARY KEY, customerId INTEGER, notional DOUBLE)',
  // trade 13 has no customer
  'INSERT INTO trades VALUES (10, 1, 100.0), (11, 1, 250.0), (12, 2, 50.0), (13, NULL, 75.0)',
];

let engine: DuckDbEngine;
const planner = plannerFor(MODEL, 'page::Runtime', { mapping: 'page::Mapping' });

before(async () => {
  const db = await openDatabase();
  engine = new DuckDbEngine(db.connection);
  for (const sql of SEED) await engine.run(sql, 0);
});

after(async () => {
  await engine?.close();
});

/** The query's SQL, as the compiler plans it, and its rows, as DuckDB answers. */
async function run(query: string): Promise<{ sql: string; rows: unknown[][] }> {
  const plan = await planner.plan(await planner.parse(`|${query}`));
  const table = await engine.run(plan.sql, 0);
  return { sql: plan.sql, rows: Array.from({ length: table.rowCount }, (_, i) => table.columns.map((c) => c.values[i] ?? null)) };
}

describe('a page\'s model: two sources as classes over relation functions, related by a ModelJoin', () => {
  it('each class reads its own source, as its relation function does', async () => {
    const { rows } = await run('files::customers::Customer.all()->project(~[id: x|$x.id, name: x|$x.name])->sort(~id->ascending())');
    assert.deepEqual(rows, [[1, 'Acme'], [2, 'Beta'], [3, 'Gamma']]);
  });

  it('a to-one end as a column: each trade with its customer\'s region, none for a trade with no customer', async () => {
    const { rows, sql } = await run(
      'files::trades::Trade.all()->project(~[trade: x|$x.tradeId, region: x|$x.customer.region])->sort(~trade->ascending())');
    assert.deepEqual(rows, [[10, 'EMEA'], [11, 'EMEA'], [12, 'AMER'], [13, null]]);
    assert.match(sql, /LEFT (OUTER )?JOIN/i, 'a to-one end is a left join');
  });

  it('a to-one end in a filter: the trades of EMEA\'s customers', async () => {
    const { rows } = await run(
      "files::trades::Trade.all()->filter(t|$t.customer.region == 'EMEA')->project(~[trade: x|$x.tradeId])->sort(~trade->ascending())");
    assert.deepEqual(rows, [[10], [11]]);
  });

  it('a to-many end in a filter: the customers with a trade over 60, each once however many it has', async () => {
    // Acme's two trades both pass: Acme once (the compiler writes the semi-join as a join to the distinct keys)
    const { rows } = await run(
      'files::customers::Customer.all()->filter(c|$c.trades->exists(t|$t.notional > 60))->project(~[name: x|$x.name])');
    assert.deepEqual(rows, [['Acme']]);
  });

  it('a to-many end as a column: a row per trade, the customer\'s own values repeated, one for a customer with none', async () => {
    const { rows } = await run(
      'files::customers::Customer.all()->project(~[name: x|$x.name, notional: x|$x.trades.notional])'
      + '->sort([~name->ascending(), ~notional->ascending()])');
    assert.deepEqual(rows, [['Acme', 100], ['Acme', 250], ['Beta', 50], ['Gamma', null]]);
  });

  it('a to-many end as an aggregate: one row per customer', async () => {
    const { rows } = await run(
      'files::customers::Customer.all()->project(~[name: x|$x.name, notional: x|$x.trades.notional])'
      + '->groupBy(~name, ~[count: x|$x.notional: y|$y->count(), total: x|$x.notional: y|$y->sum()])->sort(~name->ascending())');
    assert.deepEqual(rows.map(([name, count, total]) => [name, Number(count), total]),
      [['Acme', 2, 350], ['Beta', 1, 50], ['Gamma', 0, null]]);
  });
});
