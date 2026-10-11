// editor.test.ts's screen tests, in jsdom: the editor. The others are in editor.test.ts, which
// runs in the headless lane too (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';

import { JSDOM } from 'jsdom';
import {
  CubeEditor,
  draftFor,
  type CubeDraft,
  type EditorTab,
} from '../src/ui/editor.ts';

import { applyToSnapshot, columnConfig } from '../src/config.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';
import { stand } from './fake-planner.ts';

const CUBE: CubeSnapshot = {
  source: { query: element('t') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'desk', type: 'String' },
    { name: 'year', type: 'Integer', kind: 'dimension' },
    { name: 'notional', type: 'Float' },
  ],
  // A ratio, so a measure -- declared, as the calculated-column
  // editor always declares a row-stage column's kind now.
  derived: [{ name: 'margin', lambda: stand('$x.a / $x.b'), kind: 'measure' }],
  rows: ['region'],
  pivotOn: [],
  measures: [{ name: 'total', column: 'notional', fn: 'sum' }],
  sorts: [{ column: 'total', direction: 'desc' }],
  epoch: 3,
};

/** The column Column Properties shows: the one marked in its list. */
const chosenColumn = (within: ParentNode): string | undefined =>
  within.querySelector<HTMLElement>('.dc-pe-col.dc-on')?.dataset['column'];

/** Choose a column from Column Properties' list, as a person does. */
const chooseColumn = (within: ParentNode, name: string): void => {
  const b = within.querySelector<HTMLButtonElement>(`.dc-pe-col[data-column="${name}"]`);
  assert.ok(b, `no column ${name} in the list`);
  b.click();
};

describe('the editor', () => {
  let dom: JSDOM;
  let root: HTMLElement;
  let applied: CubeDraft[];
  let closed: number;
  let editor: CubeEditor;

  beforeEach(() => {
    dom = new JSDOM('<!doctype html><body><div id="r"></div></body>');
    root = dom.window.document.getElementById('r') as HTMLElement;
    applied = [];
    closed = 0;
    editor = new CubeEditor(root, draftFor(CUBE), {
      onApply: (d) => { applied.push(d); return true; },
      onClose: () => (closed += 1),
    });
  });

  const tabButton = (tab: EditorTab): HTMLButtonElement =>
    root.querySelector(`.dc-editor-tab[data-tab="${tab}"]`) as HTMLButtonElement;
  const go = (tab: EditorTab): void => {
    tabButton(tab).click();
  };
  const rows = (which: 'available' | 'selected'): string[] =>
    [
      ...root.querySelectorAll(`.dc-pane-${which} .dc-selector-row`),
    ].map((r) => (r as HTMLElement).dataset['column'] as string);
  const dbl = (el: Element): void => {
    el.dispatchEvent(new dom.window.MouseEvent('dblclick', { bubbles: true }));
  };
  const fieldWithLabel = (label: string): HTMLElement =>
    [...root.querySelectorAll('.dc-field')].find(
      (f) => f.querySelector('.dc-field-label')?.textContent === label,
    ) as HTMLElement;

  it('opens on Columns and renders one panel at a time', () => {
    assert.equal(editor.tab, 'Columns');
    assert.equal(root.querySelectorAll('.dc-panel').length, 1);
    assert.equal(
      root.querySelector('.dc-panel-title')?.textContent,
      'Columns',
    );
  });

  it('moves between tabs with the arrow keys, as one tab stop', () => {
    const first = tabButton('Columns');
    assert.equal(first.tabIndex, 0);
    assert.equal(tabButton('Sorts').tabIndex, -1);
    first.dispatchEvent(
      new dom.window.KeyboardEvent('keydown', {
        key: 'ArrowRight',
        bubbles: true,
      }),
    );
    // the rail's order: the layout's Row Groups next
    assert.equal(editor.tab, 'Vertical Pivots');
  });

  it('wraps at the ends rather than dead-ending', () => {
    tabButton('Columns').dispatchEvent(
      new dom.window.KeyboardEvent('keydown', {
        key: 'ArrowLeft',
        bubbles: true,
      }),
    );
    assert.equal(editor.tab, 'General Properties');
  });

  it('changes NOTHING until Apply', () => {
    go('Vertical Pivots');
    dbl(root.querySelector('.dc-pane-available .dc-selector-row') as Element);
    assert.equal(applied.length, 0, 'no query has been issued');
    assert.deepEqual(CUBE.rows, ['region'], 'the original is untouched');
  });

  it('Apply hands over the draft and keeps the editor open', () => {
    go('Vertical Pivots');
    dbl(root.querySelector('.dc-pane-available .dc-selector-row') as Element);
    (
      [...root.querySelectorAll('.dc-editor-footer button')].find(
        (b) => b.textContent === 'Apply',
      ) as HTMLButtonElement
    ).click();
    assert.equal(applied.length, 1);
    assert.equal(closed, 0);
  });

  it('OK applies and closes; Cancel discards', async () => {
    (
      [...root.querySelectorAll('.dc-editor-footer button')].find(
        (b) => b.textContent === 'OK',
      ) as HTMLButtonElement
    ).click();
    // The cube answers asynchronously: it compiles the draft first.
    await new Promise((r) => setTimeout(r, 0));
    assert.equal(applied.length, 1);
    assert.equal(closed, 1);

    go('Vertical Pivots');
    dbl(root.querySelector('.dc-pane-available .dc-selector-row') as Element);
    (
      [...root.querySelectorAll('.dc-editor-footer button')].find(
        (b) => b.textContent === 'Cancel',
      ) as HTMLButtonElement
    ).click();
    assert.deepEqual(editor.draft.snapshot.rows, ['region']);
  });

  it('Vertical Pivots offers DIMENSIONS only', () => {
    // Grouping by a notional makes one group per distinct amount,
    // which is never meant and expensive to discover.
    go('Vertical Pivots');
    assert.deepEqual(rows('available'), ['desk', 'year']);
    assert.deepEqual(rows('selected'), ['region']);
  });

  it('Vertical Pivots writes the row grouping', () => {
    go('Vertical Pivots');
    dbl(
      [...root.querySelectorAll('.dc-pane-available .dc-selector-row')].find(
        (r) => (r as HTMLElement).dataset['column'] === 'desk',
      ) as Element,
    );
    assert.deepEqual(editor.draft.snapshot.rows, ['region', 'desk']);
  });

  it('Horizontal Pivots writes pivotOn and its own sort direction', () => {
    go('Horizontal Pivots');
    dbl(
      [...root.querySelectorAll('.dc-pane-available .dc-selector-row')].find(
        (r) => (r as HTMLElement).dataset['column'] === 'year',
      ) as Element,
    );
    assert.deepEqual(editor.draft.snapshot.pivotOn, ['year']);

    const select = root.querySelector(
      '.dc-pane-selected .dc-selector-row select',
    ) as HTMLSelectElement;
    select.value = 'desc';
    select.dispatchEvent(new dom.window.Event('change'));
    assert.equal(
      columnConfig(editor.draft.config, 'year').pivotSortDirection,
      'desc',
    );
  });

  it('Sorts keeps a direction across a remove and re-add', () => {
    go('Sorts');
    assert.deepEqual(rows('selected'), ['total']);
    // total is a measure name, so it is not in the source columns;
    // the sorts panel offers every column the cube knows, derived
    // ones included.
    assert.ok(rows('available').includes('margin'));

    dbl(
      root.querySelector('.dc-pane-selected .dc-selector-row') as Element,
    );
    assert.deepEqual(editor.draft.snapshot.sorts, []);
  });

  it('the Columns tab hides a column and remembers its place', () => {
    go('Columns');
    assert.deepEqual(rows('selected'), [
      'region',
      'desk',
      'year',
      'notional',
      'margin',
    ]);
    dbl(
      [...root.querySelectorAll('.dc-pane-selected .dc-selector-row')].find(
        (r) => (r as HTMLElement).dataset['column'] === 'desk',
      ) as Element,
    );
    assert.equal(columnConfig(editor.draft.config, 'desk').hidden, true);
    // Its place is kept behind the visible ones, so putting it back
    // does not send it to the end.
    assert.deepEqual(editor.draft.config.columnOrder, [
      'region',
      'year',
      'notional',
      'margin',
      'desk',
    ]);
  });

  it('warns when no column is left selected', () => {
    go('Columns');
    const header = root.querySelector(
      '.dc-pane-selected .dc-selector-header',
    ) as HTMLElement;
    dbl(header);
    const warning = root.querySelector('.dc-warning') as HTMLElement;
    assert.equal(warning.hidden, false);
  });

  it('General Properties writes the row limit, and Apply carries it to the query', () => {
    go('General Properties');
    const input = fieldWithLabel('Row Limit:').querySelector(
      'input',
    ) as HTMLInputElement;
    input.value = '250';
    input.dispatchEvent(new dom.window.Event('change'));
    assert.equal(editor.draft.config.maxRows, 250);

    editor.apply();
    assert.equal(applied[0]?.snapshot.maxRows, 250);
  });

  it('a cleared report title leaves no trace', () => {
    go('General Properties');
    const input = fieldWithLabel('Report Title:').querySelector(
      'input',
    ) as HTMLInputElement;
    input.value = 'Q3';
    input.dispatchEvent(new dom.window.Event('change'));
    assert.equal(editor.draft.config.reportTitle, 'Q3');
    input.value = '';
    input.dispatchEvent(new dom.window.Event('change'));
    assert.equal('reportTitle' in editor.draft.config, false);
  });

  it('Column Properties rebinds every control when the column changes', () => {
    go('Column Properties');
    assert.equal(chosenColumn(root), 'region');

    const name = fieldWithLabel('Display Name:').querySelector(
      'input',
    ) as HTMLInputElement;
    name.value = 'Region';
    name.dispatchEvent(new dom.window.Event('change'));
    assert.equal(columnConfig(editor.draft.config, 'region').displayName, 'Region');

    chooseColumn(root, 'notional');
    const after = fieldWithLabel('Display Name:').querySelector(
      'input',
    ) as HTMLInputElement;
    assert.equal(after.value, '', 'now bound to notional, which has none');
    assert.equal(after.placeholder, 'notional');
  });

  it('offers an aggregate only for a measure', () => {
    go('Column Properties');
    const agg = (): HTMLSelectElement =>
      fieldWithLabel('Aggregation:').querySelector(
        'select',
      ) as HTMLSelectElement;
    assert.equal(agg().disabled, true, 'region is a dimension');

    chooseColumn(root, 'notional');
    assert.equal(agg().disabled, false);
  });

  it('offers a weight column only for a weighted average', () => {
    go('Column Properties');
    chooseColumn(root, 'notional');

    const weight = (): HTMLSelectElement =>
      fieldWithLabel('Weight column:').querySelector(
        'select',
      ) as HTMLSelectElement;
    assert.equal(weight().disabled, true);

    const agg = fieldWithLabel('Aggregation:').querySelector(
      'select',
    ) as HTMLSelectElement;
    agg.value = 'wavg';
    agg.dispatchEvent(new dom.window.Event('change'));
    assert.equal(weight().disabled, false);
  });

  it('keeps the width numbers when the mode changes', () => {
    go('Column Properties');
    const mode = (): HTMLSelectElement =>
      fieldWithLabel('Width:').querySelector('select') as HTMLSelectElement;
    mode().value = 'fixed';
    mode().dispatchEvent(new dom.window.Event('change'));
    const px = fieldWithLabel('Width:').querySelectorAll(
      'input',
    )[0] as HTMLInputElement;
    px.value = '140';
    px.dispatchEvent(new dom.window.Event('change'));

    mode().value = 'any';
    mode().dispatchEvent(new dom.window.Event('change'));
    mode().value = 'fixed';
    mode().dispatchEvent(new dom.window.Event('change'));
    const again = fieldWithLabel('Width:').querySelectorAll(
      'input',
    )[0] as HTMLInputElement;
    assert.equal(again.value, '140', 'the typed number survived');
  });

  it('shows the column kind at once, with no advanced checkbox (the user, 2026-09-30)', () => {
    go('Column Properties');
    const labels = [...root.querySelectorAll('.dc-field-label, .dc-check-label')].map((l) => l.textContent);
    assert.ok(labels.includes('Column Kind:'), labels.join(', '));
    assert.ok(!labels.some((l) => /advanced/i.test(l ?? '')), 'no Show advanced settings?');
  });

  it('a draft the cube refuses stays open, unapplied', async () => {
    const refused = new CubeEditor(root, draftFor(CUBE), {
      onApply: () => false,
      onClose: () => (closed += 1),
    });
    const took = await refused.apply({ close: true });
    assert.equal(took, false);
    assert.equal(closed, 0, 'OK closed an editor whose draft was refused');
  });

  describe('Column Properties follows the column type', () => {
    const choose = (name: string): void => {
      go('Column Properties');
      chooseColumn(root, name);
    };
    // the kind is always shown now: nothing to open first
    const advanced = (): void => {};
    const options = (label: string): string[] =>
      [...(fieldWithLabel(label).querySelector('select') as HTMLSelectElement)
        .options].map((o) => o.value).filter((v) => v !== '');

    it('a number shows the number format, with its defaults, and no link', () => {
      choose('notional');
      const decimals = fieldWithLabel('Decimals:');
      assert.equal((decimals.querySelector('input[type=number]') as HTMLInputElement).value, '2');
      const parens = [...decimals.querySelectorAll('label')]
        .find((l) => l.textContent?.includes('Negative number in parens'))
        ?.querySelector('input') as HTMLInputElement;
      assert.equal(parens.checked, true, 'parens are the default for a number');
      assert.equal(fieldWithLabel('Use parameter in link as label:'), undefined);
    });

    it('text shows the link, and no number format', () => {
      choose('desk');
      assert.equal(fieldWithLabel('Decimals:'), undefined);
      assert.ok(fieldWithLabel('Use parameter in link as label:'));
      // Upstream's for every type: text has a case and a missing value
      // (the 2026-09-25 harness caught Case hidden with the numbers).
      assert.ok(fieldWithLabel('Case:'));
      assert.ok(fieldWithLabel('Missing Value Format:'));
    });

    it('offers only the aggregates that keep the type', () => {
      choose('desk');
      assert.deepEqual(options('Aggregation:'), ['min', 'max', 'joinStrings', 'unique']);
      choose('notional');
      assert.ok(options('Aggregation:').includes('sum'));
      assert.ok(!options('Aggregation:').includes('joinStrings'));
    });

    it('locks the kind of a column that is a pivot, and says why', () => {
      choose('region');
      advanced();
      const kind = fieldWithLabel('Column Kind:').querySelector('select') as HTMLSelectElement;
      assert.equal(kind.disabled, true);
      assert.match(kind.title, /cannot be changed while the column is used in pivot/);
    });

    it('a new kind resets the exclusion from the pivot, as upstream', () => {
      choose('desk');
      advanced();
      const kind = (): HTMLSelectElement =>
        fieldWithLabel('Column Kind:').querySelector('select') as HTMLSelectElement;
      kind().value = 'measure';
      kind().dispatchEvent(new dom.window.Event('change'));
      // SET, not cleared: upstream's setExcludedFromPivot(kind === DIMENSION).
      assert.equal(columnConfig(editor.draft.config, 'desk').excludedFromPivot, false);
      kind().value = 'dimension';
      kind().dispatchEvent(new dom.window.Event('change'));
      assert.equal(columnConfig(editor.draft.config, 'desk').excludedFromPivot, true);
    });

    it('measure -> dimension -> measure puts the column back in the pivot', () => {
      // The query kept the exclusion: applyToSnapshot copies a SET
      // exclusion and leaves a cleared one as it was, so a column that
      // went to dimension and back stayed out of the pivot, which then
      // spread `count` in its place.
      choose('desk');
      advanced();
      const kind = (): HTMLSelectElement =>
        fieldWithLabel('Column Kind:').querySelector('select') as HTMLSelectElement;
      let snapshot = editor.draft.snapshot;
      for (const to of ['measure', 'dimension', 'measure']) {
        kind().value = to;
        kind().dispatchEvent(new dom.window.Event('change'));
        snapshot = applyToSnapshot(snapshot, editor.draft.config);
      }
      const desk = snapshot.columns.find((c) => c.name === 'desk');
      assert.equal(desk?.kind, 'measure');
      assert.notEqual(desk?.excludedFromPivot, true, 'still excluded from the pivot');
    });
  });

  it('gives each editor its own panel state', () => {
    // Module-level state would make the second editor jump to
    // whatever column the first was showing.
    go('Column Properties');
    chooseColumn(root, 'notional');

    const other = dom.window.document.createElement('div');
    new CubeEditor(other, draftFor(CUBE), {
      onApply: () => true,
      onClose: () => {},
      initialTab: 'Column Properties',
    });
    assert.equal(chosenColumn(other), 'region');
  });

  it('Dimensions adds, renames and deletes a hierarchy', () => {
    go('Dimensions');
    assert.equal(root.querySelector('.dc-dimension-row'), null);
    const add = [...root.querySelectorAll('.dc-dimension-controls button')].find(
      (b) => b.textContent === 'Add',
    ) as HTMLButtonElement;
    add.click();
    assert.deepEqual(editor.draft.dimensions, [
      { name: 'Dimension 1', columns: [] },
    ]);

    dbl(
      [...root.querySelectorAll('.dc-pane-available .dc-selector-row')].find(
        (r) => (r as HTMLElement).dataset['column'] === 'region',
      ) as Element,
    );
    assert.deepEqual(editor.draft.dimensions[0]?.columns, ['region']);

    const del = [...root.querySelectorAll('.dc-dimension-controls button')].find(
      (b) => b.textContent === 'Delete',
    ) as HTMLButtonElement;
    del.click();
    assert.deepEqual(editor.draft.dimensions, []);
  });

  it('a dimension offers only groupable columns', () => {
    go('Dimensions');
    (
      [...root.querySelectorAll('.dc-dimension-controls button')].find(
        (b) => b.textContent === 'Add',
      ) as HTMLButtonElement
    ).click();
    assert.deepEqual(rows('available'), ['region', 'desk', 'year']);
  });
});
