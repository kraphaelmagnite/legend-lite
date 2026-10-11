// Create a Class…'s window (src/ui/class-dialog.ts): its columns drawn from the route's answer, a choice asked of the
// route again, the files shown -- and a create the window's last word: a preview still in flight when it lands draws
// nothing over "Created", and none is asked after it (the audit's S1).

import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import { classDialog, type ClassChoice, type ClassDialogOptions, type ClassPreview } from '../src/ui/class-dialog.ts';

let doc: Document;
const settle = async (): Promise<void> => {
  for (let i = 0; i < 10; i += 1) await new Promise((r) => setTimeout(r, 0));
};
/** Long enough for the window's 250 ms wait after a change. */
const debounce = (): Promise<void> => new Promise((r) => setTimeout(r, 300));

beforeEach(() => {
  doc = new JSDOM('<!doctype html><body></body>').window.document;
});

const answer = (path: string): ClassPreview => ({
  changes: [{ type: 'CREATE', path, pureCode: `Class ${path} {}` }],
  properties: [
    { column: 'trade_id', as: 'property', property: 'tradeId', type: 'Integer', multiplicity: '[1]' },
    { column: 'region', as: 'property', property: 'region', type: 'String', multiplicity: '[0..1]' },
  ],
  notes: [],
});

const base = (more: Partial<ClassDialogOptions> = {}): ClassDialogOptions => ({
  sourceName: 'trades.csv', className: 'Trade', package: 'trades', project: { name: 'trades', isNew: true },
  preview: async () => answer('trades::domain::Trade'),
  create: async (c) => ({ className: `${c.package}::domain::${c.className}`, where: 'the project datacube:trades', notes: [] }),
  ...more,
});

describe('the Create a Class window', () => {
  it('draws the columns from the route, and asks it again as a choice changes', async () => {
    const asked: ClassChoice[] = [];
    void classDialog(doc, base({ preview: async (c) => { asked.push(c); return answer('trades::domain::Trade'); } }));
    await settle();
    assert.deepEqual([...doc.querySelectorAll('.dc-class-column')].map((t) => t.textContent), ['trade_id', 'region']);
    const role = doc.querySelectorAll<HTMLSelectElement>('.dc-class-role')[1]!;
    role.value = 'omit';
    role.dispatchEvent(new doc.defaultView!.Event('change'));
    await debounce();
    await settle();
    assert.deepEqual(asked.at(-1)?.columns, { trade_id: {}, region: { as: 'omit' } });
    assert.equal(doc.querySelectorAll('.dc-class-file').length, 1);
  });

  it('a create is its last word: a preview in flight answers no one, and none is asked after', async () => {
    let previews = 0;
    let release: (() => void) | undefined;
    const done = classDialog(doc, base({
      preview: async () => {
        previews += 1;
        // the first draws the window; the second hangs until the create has landed
        if (previews === 2) await new Promise<void>((r) => { release = r; });
        return answer(`trades::domain::Preview${previews}`);
      },
    }));
    await settle();
    // a change: its preview asked after the wait, and held
    const name = doc.querySelector<HTMLInputElement>('.dc-class-name input')!;
    name.value = 'Trades';
    name.dispatchEvent(new doc.defaultView!.Event('input'));
    await debounce();
    await settle();
    assert.equal(previews, 2);
    doc.querySelector<HTMLButtonElement>('.dc-class .dc-primary')!.click();
    await settle();
    assert.match(doc.querySelector('.dc-class .dc-picker-status')!.textContent ?? '', /^Created trades::domain::Trades/);
    // the held preview lands after the create: it draws nothing
    release?.();
    await settle();
    assert.match(doc.querySelector('.dc-class .dc-picker-status')!.textContent ?? '', /^Created/, 'still Created');
    assert.equal([...doc.querySelectorAll('.dc-class-path')].map((p) => p.textContent).join(), 'trades::domain::Preview1');
    // a change typed while the window says Created asks nothing: its inputs are off
    assert.ok(name.disabled);
    await debounce();
    assert.equal(previews, 2);
    doc.querySelector<HTMLButtonElement>('.dc-class .dc-primary')!.click();
    assert.equal((await done)?.className, 'trades::domain::Trades');
  });

  it('a create refused is said in the window, which stays open to try again', async () => {
    let tries = 0;
    void classDialog(doc, base({ create: async () => { tries += 1; throw new Error('Expected revision 3'); } }));
    await settle();
    doc.querySelector<HTMLButtonElement>('.dc-class .dc-primary')!.click();
    await settle();
    assert.equal(doc.querySelector('.dc-class .dc-picker-status')!.textContent, 'Expected revision 3');
    assert.ok(doc.querySelector('.dc-class'), 'the window stays');
    doc.querySelector<HTMLButtonElement>('.dc-class .dc-primary')!.click();
    await settle();
    assert.equal(tries, 2);
  });

  it('the page\'s package, kept after its first class', async () => {
    void classDialog(doc, base({ project: { name: 'datacube:trades', isNew: false } }));
    await settle();
    assert.ok(doc.querySelector<HTMLInputElement>('.dc-class-package input')!.disabled);
    assert.equal(doc.querySelector('.dc-class-project'), null, 'no project to name');
  });
});
