// The model's routes through the tab's own module (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.6a): createClass,
// createAssociation and createDataSpace asked through pure/v1's routing, each class over its grid's own source --
// two grids' sources, each naming its Database `local::DB`, in one request -- and the project checked alone.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { accessor, from } from '../../pure-protocol/src/index.ts';
import { projectText } from '../src/page-model.ts';
import type { ClassAction, ModelAnswer } from '../src/model-writer.ts';
import { plannerFor } from './catalog-builder.ts';

/** Two grids' sources as the tab's table model writes each: each its own `local::DB`. */
const tab = (table: string): string => `
###Relational
Database local::DB ( Table ${table} )
###Connection
RelationalDatabaseConnection local::Conn { type: DuckDB; specification: DuckDB { }; auth: Test; }
###Runtime
Runtime local::RT { mappings: []; connections: [ local::DB: [ c1: local::Conn ] ]; }
`;
const TRADES = tab('trades ( trade_id INTEGER NOT NULL, customer_id INTEGER, region VARCHAR(10) )');
const CUSTOMERS = tab('customers ( id INTEGER NOT NULL, name VARCHAR(20) )');

const action = (model: string, table: string, name: string, columns: ClassAction['columns'] = {}): ClassAction => ({
  query: from(accessor('local::DB', table)).lambda(),
  source: { model, runtime: 'local::RT' },
  name,
  columns,
  where: { from: 'local::DB', as: 'Browser' },
});
const TRADE = action(TRADES, 'trades', 'Trade', { customer_id: { as: 'key' } });
const CUSTOMER = action(CUSTOMERS, 'customers', 'Customer');

const files = (...answers: ModelAnswer[]): string => {
  const byPath = new Map<string, string>();
  for (const a of answers) for (const c of a.changes) byPath.set(c.path, c.pureCode ?? '');
  return projectText([...byPath.values()].map((pureCode) => ({ pureCode })));
};

describe('the model\'s routes, asked of the tab\'s module', () => {
  // the tab's module, as it sits beside any plane's planner: a model of its own of nothing
  const writer = plannerFor('', 'local::RT');

  it('a class over its grid\'s source: every file new; made again over the project, nothing to change', async () => {
    const first = await writer.createClass('', 'trades', TRADE);
    assert.deepEqual(first.changes.map((c) => c.type), first.changes.map(() => 'CREATE'));
    assert.equal(first.classes[0]?.class, 'trades::domain::Trade');
    const again = await writer.createClass(files(first), 'trades', TRADE);
    assert.deepEqual(again.changes, []);
  });

  it('the whole page in one go: two grids\' sources, both `local::DB`, their association and a data space', async () => {
    const all = await writer.createDataSpace('', 'trades', { name: 'Trades', description: 'The desk\'s trades' },
      [TRADE, CUSTOMER],
      [{ ends: [{ class: 'trades::domain::Customer', on: 'id', unique: true }, { class: 'trades::domain::Trade', on: 'customerId', unique: false }] }]);
    const paths = all.changes.map((c) => c.path);
    for (const p of ['trades::domain::Trade', 'trades::domain::Customer', 'trades::domain::Customer_Trade', 'trades::Trades']) {
      assert.ok(paths.includes(p), `${p} in ${paths.join(', ')}`);
    }
    assert.ok(all.changes.every((c) => c.type === 'CREATE'));
    assert.equal(all.dataSpace, 'trades::Trades');
    assert.deepEqual(all.associations[0]?.ends.map((e) => `${e.name}${e.multiplicity}`), ['customer[0..1]', 'trades[*]']);
    // the store holds both tables, read from two sources each its own `local::DB`
    const store = all.changes.find((c) => c.path === 'trades::store::Browser')?.pureCode ?? '';
    assert.match(store, /Table trades[\s\S]*Table customers/);
  });

  it('suggested, never made: the pair a key\'s name and type match', async () => {
    const two = await writer.createClass(files(await writer.createClass('', 'trades', TRADE)), 'trades', CUSTOMER);
    assert.deepEqual(two.suggestions, [{ ends: [{ class: 'trades::domain::Customer', on: 'id' }, { class: 'trades::domain::Trade', on: 'customerId' }] }]);
  });

  it('refused by name, through the module: an end on a property its class has not', async () => {
    const two = files(await writer.createClass('', 'trades', TRADE));
    await assert.rejects(writer.createAssociation(two, 'trades',
      { ends: [{ class: 'trades::domain::Customer', on: 'id', unique: true }, { class: 'trades::domain::Trade', on: 'nope', unique: false }] },
      [CUSTOMER]), /trades::domain::Trade has no property or key 'nope'/);
  });
});
