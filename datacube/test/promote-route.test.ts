// Create a Class…'s route through the tab's own module (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.6): the planner
// asks legend-lite's promoteRelationToModel through pure/v1's routing, the model it sends the project's files and the
// planner's own model together -- whatever section the project ends in and however the planner's model begins -- and
// a project that defines what the planner's model defines is refused by name.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { accessor, from } from '../../pure-protocol/src/index.ts';
import { projectText } from '../src/page-model.ts';
import type { PromoteRequest } from '../src/promote.ts';
import { plannerFor } from './catalog-builder.ts';

/** A source as the tab's table model writes it, led -- as a model may be -- by a plain element of no section header. */
const TAB = `function local::note(): Integer[1] { 1 }
###Relational
Database local::DB ( Table trades ( trade_id INTEGER NOT NULL, region VARCHAR(10) ) )
###Connection
RelationalDatabaseConnection local::Conn { type: DuckDB; specification: DuckDB { }; auth: Test; }
###Runtime
Runtime local::RT { mappings: []; connections: [ local::DB: [ c1: local::Conn ] ]; }
`;

const REQUEST: PromoteRequest = {
  package: 'trades', class: 'Trade',
  where: { from: 'local::DB', runtime: 'local::RT', as: 'Browser',
    samples: { trades: { columns: ['trade_id', 'region'], rows: [['1', 'EMEA'], ['2', null]] } } },
};

describe('promoteRelationToModel, asked of the tab\'s module', () => {
  const planner = plannerFor(TAB, 'local::RT');
  const query = from(accessor('local::DB', 'trades')).lambda();

  it('a first class: every file new, the planner\'s model read whatever section the project ends in', async () => {
    const first = await planner.promoteRelationToModel(query, '', REQUEST);
    assert.deepEqual(first.changes.map((c) => c.type), first.changes.map(() => 'CREATE'));
    assert.equal(first.class, 'trades::domain::Trade');
    // the project's last file is its Data element (###Data): the planner's model, led by a plain element, follows it
    const project = projectText(first.changes.map((c) => ({ pureCode: c.pureCode ?? '' })));
    assert.match(project, /###Data[\s\S]*$/);
    const again = await planner.promoteRelationToModel(query, project, REQUEST);
    assert.deepEqual(again.changes, [], 'made again over the project as written: nothing to change');
  });

  it('a project that defines what the planner\'s model defines is refused by name', async () => {
    await assert.rejects(planner.promoteRelationToModel(query, 'Class local::Extra { a: Integer[1]; }\n###Pure\nfunction local::note(): Integer[1] { 2 }', REQUEST),
      /local::note/);
  });
});
