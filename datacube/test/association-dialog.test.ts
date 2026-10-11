// Create an Association… and Create a Data Space…'s windows (src/ui/association-dialog.ts, data-space-dialog.ts): the
// association's ends from the suggested pair, how many preset from the facts and asked when they are unknown, the
// route's names shown; the data space's grids -- a class already, one to make -- and a suggestion chosen, its facts in.

import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import type { AssociationAction, Joinable, ModelAnswer, Suggestion } from '../src/model-writer.ts';
import { associationDialog } from '../src/ui/association-dialog.ts';
import { dataSpaceDialog, type DataSpaceChoice } from '../src/ui/data-space-dialog.ts';

let doc: Document;
const settle = async (): Promise<void> => {
  for (let i = 0; i < 10; i += 1) await new Promise((r) => setTimeout(r, 0));
};
/** Long enough for a window's 250 ms wait after a change. */
const debounce = (): Promise<void> => new Promise((r) => setTimeout(r, 300));
beforeEach(() => {
  doc = new JSDOM('<!doctype html><body></body>').window.document;
});

const JOINABLE: readonly Joinable[] = [
  { class: 'trades::domain::Trade', function: 'trades::function::TradeFn', keys: [
    { name: 'tradeId', type: 'Integer', shown: true, column: 'trade_id' },
    { name: 'customerId', type: 'Integer', shown: false, column: 'customer_id' }] },
  { class: 'trades::domain::Customer', function: 'trades::function::CustomerFn', keys: [
    { name: 'id', type: 'Integer', shown: true, column: 'id' }] },
];
const PAIR: Suggestion = { ends: [{ class: 'trades::domain::Customer', on: 'id' }, { class: 'trades::domain::Trade', on: 'customerId' }] };
const answer = (more: Partial<ModelAnswer> = {}): ModelAnswer => ({
  changes: [{ type: 'CREATE', path: 'trades::domain::Customer_Trade', pureCode: 'Association trades::domain::Customer_Trade {}' }],
  classes: [], associations: [{ association: 'trades::domain::Customer_Trade', ends: [
    { class: 'trades::domain::Customer', on: 'id', name: 'customer', multiplicity: '[0..1]' },
    { class: 'trades::domain::Trade', on: 'customerId', name: 'trades', multiplicity: '[*]' }] }],
  suggestions: [], joinable: JOINABLE, notes: [], ...more,
});
const created = { className: 'trades::domain::Customer_Trade', where: 'the project datacube:trades', notes: [] };

describe('the Create an Association window', () => {
  it('starts from the suggested pair, how many from the facts, and asks the route with it', async () => {
    const asked: AssociationAction[] = [];
    void associationDialog(doc, {
      project: 'datacube:trades', joinable: JOINABLE, suggestions: [PAIR],
      // Customer.id unique, Trade.customerId not
      unique: async (cls) => cls === 'trades::domain::Customer',
      preview: async (a) => { asked.push(a); return answer(); },
      create: async () => created,
    });
    await settle();
    await debounce();
    await settle();
    assert.deepEqual(asked.at(-1), { ends: [
      { class: 'trades::domain::Customer', on: 'id', multiplicity: '[0..1]' },
      { class: 'trades::domain::Trade', on: 'customerId', multiplicity: '[*]' }] });
    const names = [...doc.querySelectorAll<HTMLInputElement>('.dc-association-name')].map((n) => n.placeholder);
    assert.deepEqual(names, ['customer', 'trades'], 'the route\'s names, until a person names them');
    assert.match(doc.querySelector('.dc-association-sentence')?.textContent ?? '', /Each Trade has/);
    doc.querySelector<HTMLButtonElement>('.dc-association .dc-primary')!.click();
    await settle();
    assert.match(doc.querySelector('.dc-association .dc-picker-status')?.textContent ?? '', /^Created trades::domain::Customer_Trade/);
  });

  it('a class whose rows are not in this tab: how many is asked, and nothing is written until it is said', async () => {
    let previews = 0;
    void associationDialog(doc, {
      project: 'datacube:trades', joinable: JOINABLE, suggestions: [PAIR],
      unique: async () => undefined,
      preview: async () => { previews += 1; return answer(); },
      create: async () => created,
    });
    await settle();
    await debounce();
    await settle();
    assert.equal(previews, 0);
    assert.ok(doc.querySelector<HTMLButtonElement>('.dc-association .dc-primary')!.disabled);
    assert.match(doc.querySelector('.dc-association-why')?.textContent ?? '', /not in this tab: say how many/);
    for (const m of doc.querySelectorAll<HTMLSelectElement>('.dc-association-many')) {
      m.value = '[*]';
      m.dispatchEvent(new doc.defaultView!.Event('change'));
    }
    await debounce();
    await settle();
    assert.equal(previews, 1);
  });
});

describe('the Create a Data Space window', () => {
  it('the page\'s grids, a class already and one to make; a suggestion chosen goes in with its facts', async () => {
    const asked: DataSpaceChoice[] = [];
    void dataSpaceDialog(doc, {
      project: { name: 'datacube:trades', isNew: false }, name: 'Trades',
      grids: [{ id: 'g1', label: 'trades.csv', cls: 'trades::domain::Trade', className: 'Trade' },
        { id: 'g2', label: 'customers.csv', className: 'Customer' }],
      unique: async (cls) => cls === 'trades::domain::Customer',
      preview: async (c) => { asked.push(c); return answer({ suggestions: c.associations.length === 0 ? [PAIR] : [] }); },
      create: async () => created,
    });
    await settle();
    assert.deepEqual(asked.at(-1)?.made, [{ id: 'g2', className: 'Customer' }], 'the grid not yet a class, made one');
    const box = doc.querySelector<HTMLInputElement>('.dc-association-sentence input[type=checkbox]');
    assert.ok(box, 'the suggestion offered');
    await settle();
    box.checked = true;
    box.dispatchEvent(new doc.defaultView!.Event('change'));
    await debounce();
    await settle();
    assert.deepEqual(asked.at(-1)?.associations, [{ ends: [
      { class: 'trades::domain::Customer', on: 'id', multiplicity: '[0..1]' },
      { class: 'trades::domain::Trade', on: 'customerId', multiplicity: '[*]' }] }]);
    assert.ok(doc.querySelector('.dc-association-sentence input[type=checkbox]'), 'kept while chosen, though no longer suggested');
  });
});
