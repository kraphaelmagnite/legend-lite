// The page shell (page/cube-page.ts) as a person meets it, beyond what chart-tiles.test.ts and page-app.test.ts hold:
// OPEN IN GRID and its Update -- the chart's values as the editing grid's Column Properties, an Aggregation changed
// there carried back, an editing grid closed without Update throwing its changes away, and a DETACHED chart's Update
// replacing the query it keeps (docs/DATACUBE_ONE_GRID_EDIT_PATH_2026_10_17.md §3.5) -- then SHEETS (a tile dragged onto
// a tab, a sheet deleted with a grid whose charts are elsewhere, a page reopened without its deleted first sheet, each
// sheet's own layout undo), TITLES (a renamed tile moved and saved), a new grid's id beside a kept one, an export with
// an editing grid open, and a chart's click-to-filter SELECTION (its chip, cleared by it or by the grid's filter).
//
// Real grids (CubeApps over a fake engine: cube-fixture.ts), on a page of its own made the way DataCube's app makes
// one (page-app.test.ts), or on a cube alone (chart-tiles.test.ts). No test here needs the WebAssembly planner.

import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';
import { JSDOM } from 'jsdom';

import { CubeApp } from '../src/app.ts';
import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import { openCube, type CubeDocument, type FileSource } from '../src/cube-document.ts';
import type { ExportPage } from '../src/export-model.ts';
import { tiles as tilesOf } from '../src/layout/bands.ts';
import { PageApp, type GridMaker, type PageAppOptions } from '../src/page/page-app.ts';
import { pageToJson, readPage, type ChartView, type PageDocument } from '../src/page-document.ts';
import type { ChartMeasure } from '../src/chart-spec.ts';
import type { FilterNode } from '../src/snapshot.ts';
import { getInstanceByDom } from 'echarts/core';

import * as cube from './cube-fixture.ts';
import { GateEngine, SNAPSHOT, StubPlanner, settle } from './cube-fixture.ts';

/** jsdom has no canvas: a 2D context that draws nothing, so ECharts can mount (as chart-tiles.test.ts does). */
function inertCanvas(window: JSDOM['window']): void {
  const inert: ProxyHandler<object> = {
    get: (_t, key) => (key === 'canvas' ? window.document.createElement('canvas')
      : key === 'measureText' ? () => ({ width: 0 }) : () => undefined),
    set: () => true,
  };
  (window.HTMLCanvasElement.prototype as unknown as { getContext: () => unknown }).getContext = () => new Proxy({}, inert);
}

// -- a page of its own: DataCube's app ------------------------------------------------------------------------------

let dom: JSDOM;
let host: HTMLElement;
let engine: GateEngine;
let page: PageApp | undefined;
let unhandled: unknown[];
let savedRejectionListeners: NodeJS.UnhandledRejectionListener[] = [];

/** The page made by the test (each test makes one). */
const pg = (): PageApp => {
  assert.ok(page, 'the test made a page');
  return page;
};

function setUpPage(): void {
  dom = new JSDOM('<!doctype html><div id="p"></div>');
  const g = globalThis as unknown as Record<string, unknown>;
  g['window'] = dom.window;
  g['document'] = dom.window.document;
  g['requestAnimationFrame'] = (cb: FrameRequestCallback) => {
    cb(0);
    return 1;
  };
  g['cancelAnimationFrame'] = () => {};
  host = dom.window.document.getElementById('p') as unknown as HTMLElement;
  inertCanvas(dom.window);
  engine = new GateEngine();
  page = undefined;
  unhandled = [];
  savedRejectionListeners = process.listeners('unhandledRejection');
  process.removeAllListeners('unhandledRejection');
  process.on('unhandledRejection', (e) => unhandled.push(e));
}

async function tearDownPage(): Promise<void> {
  await settle();
  page?.dispose();
  page = undefined;
  process.removeAllListeners('unhandledRejection');
  for (const listener of savedRejectionListeners) process.on('unhandledRejection', listener);
  assert.deepEqual(unhandled, [], 'a promise the page started was rejected and nothing handled it');
}

/** A file a grid reads, as its saved cube names it. */
const file = (name: string): FileSource => ({
  _type: 'file', name, format: 'csv', size: 10, sha256: name.padEnd(64, '0').slice(0, 64),
  columns: SNAPSHOT.columns.map((c) => ({ name: c.name, type: c.type })),
});

/**
 * A grid over `name`, made as DataCube's app makes one (page-app.test.ts `over`): a saved cube opened over its source
 * as it is now (openCube), or, a copy, starting where its grid is.
 */
const over = (name: string, saved?: CubeDocument): GridMaker => (gridHost, spawned, start) => {
  const opened = saved ? openCube(saved, SNAPSHOT.source, SNAPSHOT.columns) : undefined;
  return new CubeApp(gridHost, start?.snapshot ?? opened?.snapshot ?? SNAPSHOT, {
    engine, planner: new StubPlanner(), compact: true, cubeSource: file(name), sourceLabel: name,
    configuration: start?.configuration ?? opened?.configuration ?? { ...DEFAULT_CONFIGURATION, reportTitle: name },
    ...(page ? { windowHost: page.root } : {}), ...spawned,
  });
};

function newPage(extra: Partial<PageAppOptions> = {}): PageApp {
  page = new PageApp({ host, title: 'Q3', empty: (slot) => { slot.textContent = 'Add a data source'; }, ...extra });
  return page;
}

/** A saved page reopened in a page of its own, each cube's source opened again by the host. */
function reopen(doc: PageDocument, extra: Partial<PageAppOptions> = {}): void {
  const back = readPage(pageToJson(doc));
  pg().dispose();
  newPage(extra);
  pg().restore(back, new Map(back.cubes.map((c) => [c.id, over(c.cube.source._type === 'file' ? c.cube.source.name : c.id, c.cube)])));
}

const tile = (id: string): HTMLElement => host.querySelector(`[data-tile="${id}"]`) as HTMLElement;
const label = (item: Element): string => item.querySelector(':scope > .dc-menu-label')?.textContent ?? '';
const menuItems = (): HTMLElement[] => [...dom.window.document.querySelectorAll<HTMLElement>('.dc-menu [role^="menuitem"]')];
const chartView = (id: string): ChartView | undefined =>
  pg().views().views.find((v): v is ChartView => v.kind === 'chart' && v.id === id);
const valuesOf = (id: string): readonly ChartMeasure[] | undefined => chartView(id)?.spec.y;
/** A chart's Dynamic / Frozen / Detached pill: what it says. */
const pill = (id: string): string => tile(id).querySelector('.dc-tile-actions .dc-titlebar-toggle')?.textContent ?? '';
const tabs = (): string[] => [...host.querySelectorAll<HTMLElement>('.dc-sheet-tab')].map((t) => t.querySelector('.dc-sheet-label')?.textContent ?? '');
const tabOf = (text: string): HTMLElement => [...host.querySelectorAll<HTMLElement>('.dc-sheet-tab')]
  .find((t) => (t.querySelector('.dc-sheet-label')?.textContent ?? '') === text)!;
/** The sheet a tile is drawn on. */
const sheetOf = (id: string): string | undefined => tile(id)?.closest<HTMLElement>('.dc-sheet')?.dataset['sheet'];
/** A tab clicked, as a pointer does. */
function clickTab(text: string): void {
  const tab = tabOf(text);
  tab.dispatchEvent(new dom.window.PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: 0 }));
  tab.dispatchEvent(new dom.window.PointerEvent('pointerup', { bubbles: true, button: 0, clientX: 0 }));
}
/** A tab's right-click menu entry, clicked. */
function tabMenu(text: string, entry: string): void {
  tabOf(text).dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  const item = menuItems().find((i) => label(i) === entry);
  assert.ok(item, `the ${text} tab's menu has ${entry}`);
  item.click();
}
/** The page's menu, opened: its entries by what a person reads. */
function pageMenu(): HTMLElement[] {
  dom.window.document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape' }));
  (host.querySelector('.dc-page-bar .dc-titlebar-menu') as HTMLElement).click();
  return menuItems();
}
/** An entry of a grid's own menu (its tile's header), by what it says. */
function gridMenuItem(gridTile: string, text: string): HTMLElement {
  dom.window.document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape' }));
  (tile(gridTile).querySelector('.dc-tile-menu') as HTMLElement).click();
  const item = menuItems().find((i) => label(i) === text);
  assert.ok(item, `${gridTile}'s menu has ${text}`);
  return item;
}
/** Insert > Visualization, from a grid's right-click menu: the new chart's id. */
async function chartOf(gridTile: string): Promise<string> {
  const charts = (): string[] => [...host.querySelectorAll<HTMLElement>('[data-tile^="chart-"]')].map((t) => t.dataset['tile']!);
  const before = new Set(charts());
  (tile(gridTile).querySelector('.dc-row .dc-cell') as HTMLElement)
    .dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  await settle();
  const item = [...dom.window.document.querySelectorAll<HTMLElement>('.dc-menu .dc-menu-item')]
    .find((el) => label(el) === 'Visualization' && label(el.parentElement!.closest('.dc-menu-item') as HTMLElement) === 'Insert');
  assert.ok(item, 'Insert > Visualization');
  item.click();
  await settle();
  const chart = charts().find((id) => !before.has(id));
  assert.ok(chart, 'a chart on the page');
  return chart;
}
/** A chart's pill clicked: Dynamic to Frozen, or back. */
async function togglePill(chart: string): Promise<void> {
  (tile(chart).querySelector('.dc-tile-actions .dc-titlebar-toggle') as HTMLElement).click();
  await settle();
}
/** A chart's right-click menu, opened on its body. */
function chartMenu(chart: string): HTMLElement[] {
  dom.window.document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape' }));
  (tile(chart).querySelector('.dc-chart-tile') as HTMLElement)
    .dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  return menuItems();
}
/** Open in grid, from the chart's right-click menu. */
async function openInGrid(chart: string): Promise<void> {
  const item = chartMenu(chart).find((i) => label(i) === 'Open in grid');
  assert.ok(item && item.getAttribute('aria-disabled') !== 'true', `${chart} offers Open in grid`);
  item.click();
  await settle();
}
/** The editing grid's Update button. */
const updateButton = (chart: string): HTMLButtonElement | undefined =>
  [...tile(`edit-${chart}`).querySelectorAll<HTMLButtonElement>('.dc-tile-actions > button')].find((x) => x.textContent?.startsWith('Update'));
async function update(chart: string): Promise<void> {
  const button = updateButton(chart);
  assert.ok(button, `the editing grid of ${chart} has its Update`);
  button.click();
  await settle();
}
/** A tile taken off by its own x. */
async function removeTile(id: string): Promise<void> {
  tile(id).querySelector<HTMLButtonElement>('.dc-tile-remove')!.click();
  await settle();
}
/** The row groups a grid's tile shows. */
const rowChips = (id: string): string[] => [...tile(id).querySelectorAll<HTMLElement>('.dc-zone-rows .dc-chip')].map((c) => c.dataset['column'] ?? '');
async function ungroup(id: string, column: string): Promise<void> {
  (tile(id).querySelector(`.dc-zone-rows .dc-chip[data-column="${column}"] .dc-chip-remove`) as HTMLElement).click();
  await settle();
}
/** A grid's Column Properties for `column`, opened from its header in tile `at`, as a person opens it. */
async function columnProperties(at: string, column: string): Promise<HTMLElement> {
  const th = tile(at).querySelector<HTMLElement>(`.dc-th[data-column="${column}"]`);
  assert.ok(th, `${at} shows a ${column} header`);
  th.dispatchEvent(new dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
  await settle();
  const item = menuItems().find((i) => label(i) === 'Properties...');
  assert.ok(item, 'the header offers Properties...');
  item.click();
  await settle();
  const win = dom.window.document.querySelector<HTMLElement>('[data-window="Properties"]');
  assert.ok(win, 'the Properties window');
  assert.equal(win.querySelector('.dc-editor-tab[aria-selected="true"]')?.textContent, 'Column Properties');
  assert.equal(win.querySelector<HTMLElement>('.dc-pe-col.dc-on')?.dataset['column'], column);
  return win;
}
const aggregationField = (win: HTMLElement): HTMLSelectElement =>
  [...win.querySelectorAll('.dc-field')].find((f) => f.querySelector('.dc-field-label')?.textContent === 'Aggregation:')!
    .querySelector('select') as HTMLSelectElement;
const footerButton = (win: HTMLElement, text: string): HTMLButtonElement =>
  [...win.querySelectorAll<HTMLButtonElement>('.dc-editor-footer button')].find((b) => b.textContent === text)!;
/** What a grid's Column Properties says a column's Aggregation is ('' for its kind's default); closed again by Cancel. */
async function aggregationShown(at: string, column: string): Promise<string> {
  const win = await columnProperties(at, column);
  const shown = aggregationField(win).value;
  footerButton(win, 'Cancel').click();
  await settle();
  return shown;
}
/** A column's Aggregation set in a grid's Column Properties, and OK. */
async function setAggregation(at: string, column: string, fn: string): Promise<void> {
  const win = await columnProperties(at, column);
  const select = aggregationField(win);
  select.value = fn;
  assert.equal(select.value, fn, `Column Properties offers ${fn}`);
  select.dispatchEvent(new dom.window.Event('change'));
  footerButton(win, 'OK').click();
  await settle();
  assert.equal(dom.window.document.querySelector('[data-window="Properties"]'), null, 'OK closed the window');
}
/** A page of two grids, `a` over trades and `b` over orders, beside it. */
async function twoGrids(extra: Partial<PageAppOptions> = {}): Promise<{ a: string; b: string }> {
  newPage(extra);
  const a = pg().addGrid(over('trades.csv'));
  const b = pg().addGrid(over('orders.csv'), { near: a });
  await settle();
  return { a, b };
}
/** Charts of grid `b`, frozen, then `b` removed: each chart DETACHED, its grid kept off the board for it. */
async function detached(b: string, n = 1): Promise<string[]> {
  const charts: string[] = [];
  for (let i = 0; i < n; i += 1) charts.push(await chartOf(b));
  for (const chart of charts) await togglePill(chart);
  await removeTile(b);
  for (const chart of charts) assert.equal(pill(chart), 'Detached');
  return charts;
}

/**
 * A chart's mark clicked, as ECharts tells the chart of a click (jsdom draws nothing to click on): series `series`, its
 * `index`th category. The fixture's rows draw EMEA and AMER across, desk A and B as the series.
 */
function clickMark(chartTile: HTMLElement, series: number, index: number): void {
  const drawn = getInstanceByDom(chartTile.querySelector('.dc-chartpanel-chart') as HTMLElement);
  assert.ok(drawn, 'the chart is drawn');
  (drawn as unknown as { trigger(name: string, params: unknown): void }).trigger('click', { seriesIndex: series, dataIndex: index });
}

/** A chart redraws a moment after what it reads changes (ui/chart-panel.ts debounces, 150 ms): that moment passed. */
async function drawn(): Promise<void> {
  await new Promise((r) => setTimeout(r, 200));
  await settle();
}

const SUM: ChartMeasure = { column: 'notional', fn: 'sum' };
const MAX: ChartMeasure = { column: 'notional', fn: 'max' };

describe('Open in grid on a DETACHED chart: Update replaces the query it keeps', () => {
  beforeEach(setUpPage);
  afterEach(tearDownPage);

  it('an untouched Update leaves its values as they were -- its grid\'s Aggregation had moved on -- and so does a second round', async () => {
    const { a, b } = await twoGrids();
    const chart = await chartOf(b);
    await togglePill(chart);
    // the grid's Aggregation of notional moves on after the freeze: the frozen chart keeps its sum, and the grid kept
    // for it once it is detached carries the average
    await setAggregation(b, 'notional', 'average');
    assert.equal(await aggregationShown(b, 'notional'), 'average');
    assert.deepEqual(valuesOf(chart), [SUM]);
    await removeTile(b);
    assert.equal(pill(chart), 'Detached');
    const before = chartView(chart)!.spec;
    await openInGrid(chart);
    // the editing grid's Column Properties say the chart's sum, not the kept grid's average
    assert.equal(await aggregationShown(`edit-${chart}`, 'notional'), 'sum');
    assert.deepEqual(rowChips(`edit-${chart}`), ['region', 'desk'], 'grouped as the chart is');
    await update(chart);
    assert.equal(tile(`edit-${chart}`), null, 'the editing grid closed');
    assert.deepEqual(chartView(chart)!.spec, before, 'the chart as it was: its values, its grouping, frozen');
    assert.equal(pill(chart), 'Detached');
    // a query of its own, kept off the board in place of its grid's (no chart reads that one now)
    const own = `${chart}-query`;
    assert.deepEqual(pg().cubes, [a, own]);
    assert.equal(chartView(chart)!.cube, own);
    // a second round: the kept query it reads now is opened again, and Update keeps the same values
    await openInGrid(chart);
    assert.equal(await aggregationShown(`edit-${chart}`, 'notional'), 'sum', 'the second editing grid says the sum too');
    await update(chart);
    assert.deepEqual(chartView(chart)!.spec, before, 'unchanged by a second untouched round');
    assert.deepEqual(pg().cubes, [a, own], 'the query it replaced is gone: one kept grid, not two');
  });

  it('two values of one column (a sum and a max) both come back, round after round, carried by one calculated copy', async () => {
    const { a, b } = await twoGrids();
    const chart = await chartOf(b);
    await togglePill(chart);
    // a chart of two values of one column, as a saved page has it (the panel's form makes one too)
    const doc = pg().document('Q3')!;
    reopen({ ...doc, views: doc.views.map((v) => (v.kind === 'chart' ? { ...v, spec: { ...v.spec, y: [SUM, MAX] } } : v)) });
    await settle();
    assert.deepEqual(valuesOf(chart), [SUM, MAX]);
    await removeTile(b);
    const own = `${chart}-query`;
    for (const round of [1, 2, 3]) {
      await openInGrid(chart);
      await update(chart);
      assert.deepEqual(valuesOf(chart), [SUM, MAX], `round ${round}: both values as they were`);
      // the max is carried by a calculated copy of notional: made once, not once more each round
      assert.deepEqual(pg().grid(own)!.snapshot.derived.map((d) => d.name), ['max_notional'], `round ${round}`);
    }
    assert.deepEqual(pg().cubes, [a, own]);
  });

  it('an Aggregation changed in the editing grid\'s Column Properties reaches the chart and the query it keeps', async () => {
    const { a, b } = await twoGrids();
    const [chart] = await detached(b);
    await openInGrid(chart!);
    await setAggregation(`edit-${chart}`, 'notional', 'max');
    await update(chart!);
    assert.deepEqual(valuesOf(chart!), [MAX], 'the chart takes the max');
    assert.equal(chartView(chart!)!.spec.frozen, true);
    // its kept query says the max as well: opened again, the editing grid starts on it, and an untouched Update keeps it
    await openInGrid(chart!);
    assert.equal(await aggregationShown(`edit-${chart}`, 'notional'), 'max');
    await update(chart!);
    assert.deepEqual(valuesOf(chart!), [MAX]);
    assert.deepEqual(pg().cubes, [a, `${chart}-query`]);
    // saved and reopened, it still reads its own query, detached -- and Update replaces that one as before
    reopen(pg().document('Q3')!);
    await settle();
    assert.equal(pill(chart!), 'Detached');
    assert.deepEqual(valuesOf(chart!), [MAX]);
    assert.deepEqual(pg().cubes, [a, `${chart}-query`]);
    await openInGrid(chart!);
    assert.equal(await aggregationShown(`edit-${chart}`, 'notional'), 'max');
    await update(chart!);
    assert.deepEqual(valuesOf(chart!), [MAX]);
    assert.deepEqual(pg().cubes, [a, `${chart}-query`]);
  });

  it('two charts detached from one grid: Update gives one a query of its own; the other keeps the grid\'s, until it goes', async () => {
    const { a, b } = await twoGrids();
    const [x, y] = await detached(b, 2);
    await openInGrid(x!);
    await ungroup(`edit-${x}`, 'desk');
    await update(x!);
    assert.equal(chartView(x!)!.spec.split, undefined, 'regrouped as its editing grid was');
    assert.equal(chartView(y!)!.spec.split, 'desk', 'the other is as it was');
    assert.equal(chartView(y!)!.cube, b, 'and still reads the removed grid\'s query');
    assert.deepEqual(pg().cubes, [a, b, `${x}-query`]);
    // opened again, its editing grid groups by its column across alone: it has no split now
    await openInGrid(x!);
    assert.deepEqual(rowChips(`edit-${x}`), ['region']);
    await removeTile(`edit-${x}`);
    // the removed grid's last chart gone, the grid kept for it goes too; the other's own query stays
    await removeTile(y!);
    assert.deepEqual(pg().cubes, [a, `${x}-query`]);
  });

  it('a chart whose grid is removed while its editing grid is open: Update still writes it back, into a query of its own', async () => {
    const { a, b } = await twoGrids();
    const chart = await chartOf(b);
    await togglePill(chart);
    await openInGrid(chart);
    await ungroup(`edit-${chart}`, 'desk');
    await removeTile(b);
    assert.equal(pill(chart), 'Detached');
    assert.ok(tile(`edit-${chart}`), 'its editing grid is still open');
    await update(chart);
    assert.equal(chartView(chart)!.spec.split, undefined);
    assert.deepEqual(pg().cubes, [a, `${chart}-query`], 'its query its own; the removed grid no chart reads, gone');
  });
});

describe('Open in grid on a frozen chart: Update, and closing without it', () => {
  beforeEach(setUpPage);
  afterEach(tearDownPage);

  it('an Aggregation changed in the editing grid\'s Column Properties reaches the chart on Update; its grid is untouched', async () => {
    let changes = 0;
    newPage({ onChange: () => { changes += 1; } });
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    await togglePill(chart);
    const title = chartView(chart)!.title;
    await openInGrid(chart);
    assert.equal(tile(`edit-${chart}`).querySelector('.dc-tile-title')?.textContent, `Editing ${title}`);
    assert.equal(updateButton(chart)?.textContent, `Update ${title}`);
    await setAggregation(`edit-${chart}`, 'notional', 'max');
    const told = changes;
    await update(chart);
    assert.deepEqual(valuesOf(chart), [MAX]);
    assert.equal(chartView(chart)!.spec.frozen, true, 'still frozen');
    assert.ok(changes > told, 'the page is told it changed');
    // the chart's grid never moved: its Column Properties still say notional's default
    assert.equal(await aggregationShown(a, 'notional'), '');
    assert.deepEqual(rowChips(a), ['region', 'desk']);
  });

  it('closing the editing grid without Update leaves the chart as it was, whatever was changed in it', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    await togglePill(chart);
    const before = JSON.stringify(chartView(chart)!.spec);
    await openInGrid(chart);
    await setAggregation(`edit-${chart}`, 'notional', 'max');
    await ungroup(`edit-${chart}`, 'desk');
    await removeTile(`edit-${chart}`);
    assert.equal(tile(`edit-${chart}`), null);
    assert.equal(JSON.stringify(chartView(chart)!.spec), before, 'neither the Aggregation nor the grouping reached it');
    // opened again, a fresh editing grid from the chart: not the one thrown away
    await openInGrid(chart);
    assert.deepEqual(rowChips(`edit-${chart}`), ['region', 'desk']);
    assert.equal(await aggregationShown(`edit-${chart}`, 'notional'), '');
  });

  it('following the grid again closes the editing grid; removing a chart closes its editing grid with it', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const x = await chartOf(a);
    const y = await chartOf(a);
    await togglePill(x);
    await togglePill(y);
    await openInGrid(x);
    await openInGrid(y);
    // Follow the grid: an open editing grid has nothing left to edit
    await togglePill(x);
    assert.equal(pill(x), 'Dynamic');
    assert.equal(tile(`edit-${x}`), null, 'its editing grid closed');
    assert.ok(tile(`edit-${y}`), 'the other chart\'s is still open');
    await removeTile(y);
    assert.equal(tile(`edit-${y}`), null, 'the chart\'s editing grid went with it');
    assert.deepEqual(pg().views().views.map((v) => v.id), [a, x]);
  });

  it('Open in grid again, while the editing grid is open, shows that one with its changes rather than a second', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    await togglePill(chart);
    await openInGrid(chart);
    await ungroup(`edit-${chart}`, 'desk');
    await openInGrid(chart);
    assert.equal(host.querySelectorAll('[data-tile^="edit-"]').length, 1, 'one editing grid');
    assert.deepEqual(rowChips(`edit-${chart}`), ['region'], 'its change kept');
    // the chart moved to another sheet, its editing grid left where it was: Open in grid from there shows that sheet
    const first = pg().shownSheet;
    (host.querySelector('.dc-sheet-add') as HTMLElement).click();
    await settle();
    clickTab('trades.csv');
    await settle();
    chartMenu(chart).find((i) => label(i) === 'Sheet 2')!.click();
    await settle();
    clickTab('Sheet 2');
    await settle();
    assert.notEqual(pg().shownSheet, first);
    await openInGrid(chart);
    assert.equal(pg().shownSheet, first, 'the editing grid\'s sheet shown');
    assert.equal(sheetOf(`edit-${chart}`), first);
    assert.deepEqual(rowChips(`edit-${chart}`), ['region'], 'still the one with its change');
  });

  it('an export of the page leaves an open editing grid out: a moment\'s work, not the page', async () => {
    const exports = new Map<string, () => ExportPage | undefined>();
    // a maker that keeps each grid's export, as its CubeApp would call it
    const keep: GridMaker = (gridHost, spawned, start) => {
      if (spawned.id !== undefined && spawned.exportPage) exports.set(spawned.id, spawned.exportPage);
      return over('trades.csv')(gridHost, spawned, start);
    };
    newPage();
    const a = pg().addGrid(keep);
    await settle();
    assert.equal(exports.get(a)?.(), undefined, 'no chart on its sheet: the grid exports alone');
    const chart = await chartOf(a);
    await togglePill(chart);
    await openInGrid(chart);
    const exported = exports.get(a)?.();
    assert.ok(exported, 'a page export, with its chart');
    assert.deepEqual(exported.tiles.map((t) => [t.id, t.kind]).sort(), [[a, 'grid'], [chart, 'chart']].sort());
  });
});

describe('a chart\'s right-click menu: Options..., and Open in grid offered only where it opens a grid', () => {
  beforeEach(setUpPage);
  afterEach(tearDownPage);

  it('Options... shows the chart\'s form, and again hides it', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    const form = (): HTMLElement => tile(chart).querySelector('.dc-chartpanel-form') as HTMLElement;
    assert.equal(form().hidden, true, 'a chart on the page starts without its form');
    chartMenu(chart).find((i) => label(i) === 'Options...')!.click();
    assert.equal(form().hidden, false);
    chartMenu(chart).find((i) => label(i) === 'Options...')!.click();
    assert.equal(form().hidden, true);
  });

  it('Options... opens a pane beside the chart, not over it: the divider between them moves', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    const panel = tile(chart).querySelector('.dc-chartpanel') as HTMLElement;
    const form = panel.querySelector('.dc-chartpanel-form') as HTMLElement;
    const sash = panel.querySelector('.dc-chartpanel-sash[role="separator"]') as HTMLElement;
    assert.deepEqual([...panel.children].map((c) => c.className),
      ['dc-chartpanel-form', 'dc-chartpanel-sash', 'dc-chartpanel-main'], 'the options, the divider, the chart: in a row');
    assert.equal(sash.hidden, true, 'no divider without the options');
    chartMenu(chart).find((i) => label(i) === 'Options...')!.click();
    assert.equal(sash.hidden, false);
    // jsdom lays nothing out: a 600px panel, its options 220px wide
    const width = (el: HTMLElement, px: () => number): void => {
      el.getBoundingClientRect = () => ({ width: px() }) as DOMRect;
    };
    width(panel, () => 600);
    width(sash, () => 5);
    width(form, () => parseFloat(form.style.flexBasis) || 220);
    const key = (k: string): void => {
      sash.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
    };
    key('ArrowRight');
    assert.equal(form.style.flexBasis, '236px');
    key('End');
    assert.equal(form.style.flexBasis, '435px', 'never past the chart\'s last 160px');
    key('Home');
    assert.equal(form.style.flexBasis, '160px');
    sash.dispatchEvent(new dom.window.PointerEvent('pointerdown', { bubbles: true, button: 0, clientX: 300 }));
    sash.dispatchEvent(new dom.window.PointerEvent('pointermove', { bubbles: true, clientX: 350 }));
    assert.equal(form.style.flexBasis, '210px', 'dragged 50px right');
    sash.dispatchEvent(new dom.window.PointerEvent('pointerup', { bubbles: true, clientX: 350 }));
    sash.dispatchEvent(new dom.window.PointerEvent('pointermove', { bubbles: true, clientX: 500 }));
    assert.equal(form.style.flexBasis, '210px', 'let go, it stays');
    chartMenu(chart).find((i) => label(i) === 'Options...')!.click();
    assert.equal(sash.hidden, true);
  });

  it('a frozen KPI with no column across: Open in grid is not offered when it would open nothing', async () => {
    // A KPI may have no column across (its trend: chart-spec.ts chartProblems). Open in grid groups the editing grid by
    // the chart's column across (#openEditor returns without one), so the menu should not offer it -- as it does not
    // for a gauge, which never has one.
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    await togglePill(chart);
    const doc = pg().document('Q3')!;
    reopen({ ...doc, views: doc.views.map((v) => {
      if (v.kind !== 'chart') return v;
      const { x: _x, split: _split, ...kpi } = v.spec;
      return { ...v, spec: { ...kpi, mark: 'kpi' as const } };
    }) });
    await settle();
    assert.equal(chartView(chart)!.spec.mark, 'kpi');
    assert.equal(chartView(chart)!.spec.x, undefined);
    assert.equal(pill(chart), 'Frozen');
    const item = chartMenu(chart).find((i) => label(i) === 'Open in grid');
    assert.ok(item, 'the entry is in the menu');
    const offered = item.getAttribute('aria-disabled') !== 'true';
    item.click();
    await settle();
    const opened = tile(`edit-${chart}`) !== null;
    // offered exactly when it opens one (either way out of the mismatch: the entry disabled, or an editing grid)
    assert.equal(offered, opened, `offered: ${String(offered)}, but clicked it opened ${opened ? 'an' : 'no'} editing grid`);
  });
});

describe('sheets: tiles dragged onto tabs, sheets deleted and reopened, each its own layout undo', () => {
  beforeEach(setUpPage);
  afterEach(tearDownPage);

  /** Each tab at its own place along the bar (jsdom lays nothing out): tab i from x = 100 + 100i, 80 wide, 20 high. */
  function placeTabs(): void {
    [...host.querySelectorAll<HTMLElement>('.dc-sheet-tab')].forEach((tab, i) => {
      const left = 100 + 100 * i;
      tab.getBoundingClientRect = () => ({ left, right: left + 80, top: 0, bottom: 20, x: left, y: 0, width: 80, height: 20, toJSON: () => ({}) });
    });
  }
  const pointer = (el: Element, type: string, x: number, y: number): void => {
    el.dispatchEvent(new dom.window.PointerEvent(type, { pointerId: 1, clientX: x, clientY: y, button: 0, bubbles: true }));
  };
  const marked = (): string[] => [...host.querySelectorAll<HTMLElement>('.dc-sheet-tab-target')].map((t) => t.dataset['sheet'] ?? '');

  it('a tile dragged by its title bar onto another sheet\'s tab moves there; onto its own sheet\'s tab, nothing moves', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    (host.querySelector('.dc-sheet-add') as HTMLElement).click();
    await settle();
    const [first, second] = pg().sheets;
    clickTab('trades.csv');
    await settle();
    placeTabs();
    const head = tile(chart).querySelector<HTMLElement>('.dc-tile-head')!;
    pointer(head, 'pointerdown', 600, 300);
    pointer(head, 'pointermove', 240, 10);
    assert.deepEqual(marked(), [second], 'the tab under the pointer is marked while the tile is over it');
    pointer(head, 'pointerup', 240, 10);
    await settle();
    assert.deepEqual(marked(), [], 'and unmarked once it is let go');
    assert.equal(sheetOf(chart), second, 'the chart is on the second sheet');
    assert.equal(pg().shownSheet, first, 'the sheet shown stays shown');
    assert.equal(host.querySelector(`.dc-sheet[data-sheet="${first}"] .dc-board-live`)?.textContent,
      `${chartView(chart)!.title} moved to Sheet 2.`, 'the board says where it went');
    // the grid onto its own sheet's tab: no tab marked, nothing moved
    placeTabs();
    const gridHead = tile(a).querySelector<HTMLElement>('.dc-tile-head')!;
    pointer(gridHead, 'pointerdown', 600, 300);
    pointer(gridHead, 'pointermove', 140, 10);
    assert.deepEqual(marked(), []);
    pointer(gridHead, 'pointerup', 140, 10);
    await settle();
    assert.equal(sheetOf(a), first);
    assert.deepEqual(pg().views().sheets.map((s) => tilesOf(s.layout)), [[a], [chart]]);
  });

  it('a sheet deleted with its grid: that grid\'s following chart elsewhere goes, its frozen one stays, detached; the next sheet is shown', async () => {
    const { a, b } = await twoGrids({ confirm: () => true });
    const following = await chartOf(b);
    const frozen = await chartOf(b);
    await togglePill(frozen);
    (host.querySelector('.dc-sheet-add') as HTMLElement).click();
    await settle();
    clickTab('trades.csv');
    await settle();
    // grid b onto the second sheet, which then goes first: b's charts stay on the other sheet with grid a
    gridMenuItem(b, 'Sheet 2').click();
    await settle();
    tabMenu('orders.csv', 'Move Left');
    await settle();
    assert.deepEqual(tabs(), ['orders.csv', 'trades.csv']);
    clickTab('orders.csv');
    await settle();
    tabMenu('orders.csv', 'Delete');
    await settle();
    assert.deepEqual(tabs(), ['trades.csv'], 'the sheet is gone');
    assert.equal(pg().shownSheet, sheetOf(a), 'the sheet after it is shown');
    assert.deepEqual(pg().grids, [a], 'its grid went with it');
    assert.equal(tile(following), null, 'the chart following that grid went too, from the other sheet');
    assert.equal(pill(frozen), 'Detached', 'the frozen one stays, reading its own copy of the grid\'s query');
    assert.deepEqual(pg().cubes, [a, b], 'the grid kept off the board for it');
  });

  it('Move to Sheet > New Sheet puts the tile on a sheet of its own; a sheet\'s name cleared, it is named after what it shows again', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    const first = pg().shownSheet;
    const item = chartMenu(chart).find((i) => label(i) === 'New Sheet');
    assert.ok(item, 'the chart\'s Move to Sheet > New Sheet');
    item.click();
    await settle();
    assert.equal(pg().sheets.length, 2);
    assert.equal(sheetOf(chart), pg().sheets[1], 'the chart on the new sheet');
    assert.equal(pg().shownSheet, first, 'the sheet shown stays shown');
    assert.deepEqual(tabs(), ['trades.csv', 'Sheet 2']);
    // the new sheet named, then its name cleared: its tab says its place again
    const renameTab = (from: string, to: string): void => {
      tabOf(from).dispatchEvent(new dom.window.MouseEvent('dblclick', { bubbles: true }));
      const field = host.querySelector<HTMLInputElement>('.dc-sheet-tab input');
      assert.ok(field, 'the tab\'s name field');
      field.value = to;
      field.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter' }));
    };
    renameTab('Sheet 2', 'Charts');
    await settle();
    assert.deepEqual(tabs(), ['trades.csv', 'Charts']);
    assert.equal(pg().views().sheets[1]!.name, 'Charts');
    renameTab('Charts', '   ');
    await settle();
    assert.deepEqual(tabs(), ['trades.csv', 'Sheet 2']);
    assert.equal(pg().views().sheets[1]!.name, undefined, 'no name of its own saved');
  });

  it('ready waits for every grid\'s first view -- a grid kept off the board too -- and for a refused one as well', async () => {
    const { b } = await twoGrids();
    await detached(b);
    const doc = pg().document('Q3')!;
    engine.gate.hold = true;
    reopen(doc);
    let ready = false;
    void pg().ready().then(() => { ready = true; });
    await settle();
    assert.equal(ready, false, 'its grids\' first queries are held');
    assert.ok(engine.gate.held.length >= 2, 'the grid on the board and the one kept off it both asked');
    // the first query of each refused: the page has still opened
    engine.gate.fail = 'the file is gone';
    engine.gate.releaseAll();
    await settle();
    assert.equal(ready, true);
  });

  it('a page whose first sheet was deleted reopens with its own sheets alone, on its first', async () => {
    newPage({ confirm: () => true });
    pg().addGrid(over('trades.csv'));
    await settle();
    (host.querySelector('.dc-sheet-add') as HTMLElement).click();
    const b = pg().addGrid(over('orders.csv'));
    await settle();
    const second = pg().shownSheet;
    tabMenu('trades.csv', 'Delete');
    await settle();
    assert.deepEqual(pg().sheets, [second]);
    reopen(pg().document('Q3')!, { confirm: () => true });
    await settle();
    // the board a page starts with has a first sheet of its own: no such sheet is left behind, empty, in the tabs
    assert.deepEqual(pg().sheets, [second]);
    assert.deepEqual(tabs(), ['orders.csv']);
    assert.equal(pg().shownSheet, second);
    assert.deepEqual(pg().grids, [b]);
    assert.equal(sheetOf(b), second);
  });

  it('each sheet has its own layout undo: another sheet\'s arrangement is not undone from this one', async () => {
    newPage();
    const a = pg().addGrid(over('trades.csv'));
    await settle();
    const chart = await chartOf(a);
    const entry = (text: string): HTMLElement => {
      const item = pageMenu().find((i) => label(i) === text);
      assert.ok(item, `the page's menu offers ${text}`);
      return item;
    };
    const bands = (): string[][] => pg().views().sheets[0]!.layout.bands.map((band) => tilesOf({ fit: false, bands: [band] }));
    const sideBySide = bands();
    entry('Arrange…').click();
    await settle();
    const stacked = host.querySelector<HTMLButtonElement>('.dc-layout-picker [data-preset="stacked"]');
    assert.ok(stacked, 'the layouts');
    stacked.click();
    await settle();
    assert.deepEqual(bands(), [[a], [chart]]);
    assert.notEqual(entry('Undo Layout').getAttribute('aria-disabled'), 'true');
    (host.querySelector('.dc-sheet-add') as HTMLElement).click();
    await settle();
    assert.equal(entry('Undo Layout').getAttribute('aria-disabled'), 'true', 'the new sheet has nothing to undo');
    clickTab('trades.csv');
    await settle();
    entry('Undo Layout').click();
    await settle();
    assert.deepEqual(bands(), sideBySide, 'its own arrangement undone, on its own sheet');
    entry('Redo Layout').click();
    await settle();
    assert.deepEqual(bands(), [[a], [chart]]);
  });
});

describe('titles and ids: renamed tiles moved and saved, a new grid beside a kept one', () => {
  beforeEach(setUpPage);
  afterEach(tearDownPage);

  /** A tile renamed in place: its title bar double-clicked, a name typed, Enter. */
  function rename(id: string, name: string): void {
    tile(id).querySelector('.dc-tile-head')!.dispatchEvent(new dom.window.MouseEvent('dblclick', { bubbles: true }));
    const field = tile(id).querySelector<HTMLInputElement>('.dc-tile-rename');
    assert.ok(field, `${id}'s rename field`);
    field.value = name;
    field.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter' }));
  }

  it('a renamed chart keeps its name on another sheet and in Open in grid; a renamed grid\'s name is saved, an unrenamed one\'s is not', async () => {
    const { a, b } = await twoGrids();
    const chart = await chartOf(a);
    rename(chart, 'Desk mix');
    rename(a, 'Trades');
    await settle();
    (host.querySelector('.dc-sheet-add') as HTMLElement).click();
    await settle();
    // the sheet is named after its first grid's source, not its tile's title
    clickTab('trades.csv');
    await settle();
    const item = chartMenu(chart).find((i) => label(i) === 'Sheet 2');
    assert.ok(item, 'the chart\'s Move to Sheet > Sheet 2');
    item.click();
    await settle();
    assert.equal(pg().views().sheets.find((s) => tilesOf(s.layout).includes(chart))?.id, sheetOf(chart));
    assert.equal(chartView(chart)!.title, 'Desk mix', 'moved under the name it was given');
    await togglePill(chart);
    clickTab('Sheet 2');
    await settle();
    await openInGrid(chart);
    assert.equal(updateButton(chart)?.textContent, 'Update Desk mix');
    await removeTile(`edit-${chart}`);
    // saved: the renamed grid's title written, the other's (as it was made) left out
    const doc = pg().document('Q3')!;
    const gridViews = doc.views.filter((v) => v.kind === 'grid');
    assert.deepEqual(gridViews.map((v) => [v.id, v.title]), [[a, 'Trades'], [b, undefined]]);
    reopen(doc);
    await settle();
    assert.equal(pg().views().views.find((v) => v.id === a)?.title, 'Trades', 'reopened under its name');
    assert.equal(tile(a).querySelector('.dc-tile-title')?.textContent, 'Trades');
    assert.equal(chartView(chart)!.title, 'Desk mix');
    assert.deepEqual(tabs(), ['trades.csv', 'Sheet 2']);
  });

  it('a new grid never takes the id of a grid kept off the board for a detached chart', async () => {
    newPage();
    const g1 = pg().addGrid(over('trades.csv'));
    const g2 = pg().addGrid(over('trades.csv'), { near: g1 });
    const g3 = pg().addGrid(over('trades.csv'), { near: g2 });
    await settle();
    assert.deepEqual([g1, g2, g3], ['grid-1', 'grid-2', 'grid-3']);
    const [chart] = await detached(g3);
    await removeTile(g2);
    reopen(pg().document('Q3')!);
    await settle();
    assert.deepEqual(pg().grids, [g1]);
    assert.deepEqual(pg().cubes, [g1, g3], 'grid-3 kept for its chart');
    // Copy of Grid, twice: grid-2 is free again; grid-3 is the kept grid's, so the next is grid-4
    gridMenuItem(g1, 'Copy of Grid').click();
    await settle();
    gridMenuItem(g1, 'Copy of Grid').click();
    await settle();
    assert.deepEqual(pg().grids.slice().sort(), ['grid-1', 'grid-2', 'grid-4']);
    assert.equal(pill(chart!), 'Detached');
    assert.equal(chartView(chart!)!.cube, g3, 'the detached chart still reads its kept grid');
  });
});

// -- a cube alone: its own grid, and a chart's click-to-filter selection --------------------------------------------

/** A cube alone (cube-fixture.ts), its board the page the helpers above read. */
async function setUpCube(): Promise<void> {
  await cube.setUp();
  inertCanvas(cube.dom.window);
  dom = cube.dom;
  host = cube.root;
}

describe('Open in grid on a detached chart of a cube alone: its kept query made by its grid\'s own spawn', () => {
  beforeEach(setUpCube);
  afterEach(cube.tearDown);

  /** What the chart draws, as it says it to a screen reader: its values by name, its grouping. */
  const drawing = async (chart: string): Promise<string> => {
    await drawn();
    return tile(chart).querySelector('.dc-chartpanel-chart')?.getAttribute('aria-label') ?? '';
  };

  it('an untouched Update keeps what it plots, round after round; an Aggregation changed in the editing grid reaches it', async () => {
    // a cube alone saves its own grid's charts only, so the chart is read as a person reads it: by what it draws
    await cube.app.newGrid();
    await settle();
    const added = host.querySelector<HTMLElement>('[data-tile^="grid-"]')!.dataset['tile']!;
    const chart = await chartOf(added);
    await togglePill(chart);
    await setAggregation(added, 'notional', 'average');
    await removeTile(added);
    assert.equal(pill(chart), 'Detached');
    const was = await drawing(chart);
    assert.match(was, /^Bar chart of sum_notional by region, split by desk/);
    for (const round of [1, 2]) {
      await openInGrid(chart);
      assert.equal(await aggregationShown(`edit-${chart}`, 'notional'), 'sum', `round ${round}: the chart's sum, not its grid's average`);
      await update(chart);
      assert.equal(await drawing(chart), was, `round ${round}: as it was`);
      assert.equal(pill(chart), 'Detached');
    }
    await openInGrid(chart);
    await setAggregation(`edit-${chart}`, 'notional', 'max');
    await update(chart);
    assert.match(await drawing(chart), /^Bar chart of max_notional by region, split by desk/);
  });
});

describe('a chart\'s selection, on a cube alone: its chip, cleared by it or by the grid\'s filter', () => {
  beforeEach(setUpCube);
  afterEach(cube.tearDown);

  const EMEA: FilterNode = { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' };
  const LARGE: FilterNode = { kind: 'condition', column: 'notional', operator: 'greaterThanEqual', value: 500 };
  const NO_DESK: FilterNode = { kind: 'condition', column: 'desk', operator: 'isEmpty' };
  const chip = (id: string): HTMLButtonElement => tile(id).querySelector('.dc-tile-actions .dc-tile-chip') as HTMLButtonElement;
  const selection = (id: string): readonly FilterNode[] | undefined =>
    cube.app.pageViews().views.find((v): v is ChartView => v.kind === 'chart' && v.id === id)?.selection;
  /** The cube's filter set to `filter`, as one change. */
  async function filterTo(filter: FilterNode | undefined): Promise<void> {
    await cube.app.change((s) => {
      const { filter: _drop, ...rest } = s.snapshot;
      return { ...s, snapshot: filter === undefined ? rest : { ...rest, filter } };
    });
    await settle();
  }
  /** A cube whose filter holds `conditions`, and a saved chart that put them there, put back. */
  async function restoredWith(conditions: readonly FilterNode[]): Promise<string> {
    await cube.app.openChart();
    await settle();
    const views = cube.app.pageViews();
    const chart = views.views.find((v): v is ChartView => v.kind === 'chart')!;
    const config = cube.app.configuration;
    await cube.remount({ configuration: config });
    await filterTo(conditions.length === 1 ? conditions[0] : { kind: 'and', children: conditions });
    await cube.app.restoreViews({ ...views, views: views.views.map((v) => (v.id === chart.id ? { ...chart, selection: conditions } : v)) });
    await settle();
    return chart.id;
  }

  it('a click on a mark filters the grid to it; another mark replaces it; the same mark again takes it off', async () => {
    await cube.app.openChart();
    await settle();
    const chart = cube.app.pageViews().views.find((v) => v.kind === 'chart')!.id;
    const A_EMEA: FilterNode = { kind: 'and', children: [EMEA, { kind: 'condition', column: 'desk', operator: 'equal', value: 'A' }] };
    const B_AMER: FilterNode = { kind: 'and', children: [
      { kind: 'condition', column: 'region', operator: 'equal', value: 'AMER' },
      { kind: 'condition', column: 'desk', operator: 'equal', value: 'B' },
    ] };
    clickMark(tile(chart), 0, 0);
    await settle();
    assert.deepEqual(cube.app.snapshot.filter, A_EMEA, 'the mark\'s region and desk, ANDed on');
    assert.equal(chip(chart).textContent, 'region: EMEA, desk: A \u00d7');
    clickMark(tile(chart), 1, 1);
    await settle();
    assert.deepEqual(cube.app.snapshot.filter, B_AMER, 'replaced, not piled on: two clicks never filter to nothing');
    assert.equal(chip(chart).textContent, 'region: AMER, desk: B \u00d7');
    clickMark(tile(chart), 1, 1);
    await settle();
    assert.equal(cube.app.snapshot.filter, undefined, 'the same mark again: taken off');
    assert.equal(chip(chart).hidden, true);
    // each click one change: undone, the filter is as the click before left it -- and the chart claims no conditions
    // the filter does not hold
    await cube.app.undo();
    await settle();
    assert.deepEqual(cube.app.snapshot.filter, B_AMER);
    await cube.app.undo();
    await settle();
    assert.deepEqual(cube.app.snapshot.filter, A_EMEA);
    assert.equal(chip(chart).hidden, true, 'the undone selection is not the chart\'s to take off');
  });

  it('a chart removed while it filters its grid takes its conditions off the filter', async () => {
    const DESK_A: FilterNode = { kind: 'condition', column: 'desk', operator: 'equal', value: 'A' };
    const chart = await restoredWith([EMEA]);
    await filterTo({ kind: 'and', children: [EMEA, DESK_A] });
    tile(chart).querySelector<HTMLButtonElement>('.dc-tile-remove')!.click();
    await settle();
    assert.equal(tile(chart), null);
    assert.deepEqual(cube.app.snapshot.filter, DESK_A, 'its own condition gone; the other stays');
  });

  it('a restored selection is said in its chip, by the columns\' names; the chip takes it off the grid\'s filter', async () => {
    await cube.app.applyConfiguration({ columns: { region: { displayName: 'Region' } } });
    const chart = await restoredWith([EMEA, LARGE, NO_DESK]);
    assert.equal(chip(chart).hidden, false);
    assert.equal(chip(chart).textContent, 'Region: EMEA, notional ≥ 500, desk: (empty) ×');
    assert.equal(chip(chart).getAttribute('aria-label'), 'Clear the filter to Region: EMEA, notional ≥ 500, desk: (empty)');
    assert.deepEqual(selection(chart), [EMEA, LARGE, NO_DESK]);
    chip(chart).click();
    await settle();
    assert.equal(cube.app.snapshot.filter, undefined, 'its conditions taken off the cube\'s filter');
    assert.equal(chip(chart).hidden, true);
    assert.equal(selection(chart), undefined, 'and no longer saved with the chart');
  });

  it('the chip takes off only its own conditions: another condition on the filter stays', async () => {
    const DESK_A: FilterNode = { kind: 'condition', column: 'desk', operator: 'equal', value: 'A' };
    const chart = await restoredWith([EMEA]);
    await filterTo({ kind: 'and', children: [EMEA, DESK_A] });
    assert.equal(chip(chart).hidden, false, 'its condition is still on the filter: still its selection');
    chip(chart).click();
    await settle();
    assert.deepEqual(cube.app.snapshot.filter, DESK_A);
  });

  it('a selection taken off the grid\'s filter some other way is no longer the chart\'s', async () => {
    const chart = await restoredWith([EMEA]);
    assert.deepEqual(selection(chart), [EMEA]);
    await filterTo(undefined);
    assert.equal(chip(chart).hidden, true);
    assert.equal(selection(chart), undefined);
    // the chip has nothing to take off now: clicking it changes nothing
    const filter: FilterNode = { kind: 'condition', column: 'desk', operator: 'equal', value: 'B' };
    await filterTo(filter);
    chip(chart).click();
    await settle();
    assert.deepEqual(cube.app.snapshot.filter, filter);
  });
});

describe('a frozen chart on a page of its own loses its selection with its grid', () => {
  beforeEach(setUpPage);
  afterEach(tearDownPage);

  it('its grid removed, a detached chart has no grid\'s filter to keep its selection on: the chip goes', async () => {
    const EMEA: FilterNode = { kind: 'condition', column: 'region', operator: 'equal', value: 'EMEA' };
    const { a, b } = await twoGrids();
    const chart = await chartOf(b);
    await togglePill(chart);
    const doc = pg().document('Q3')!;
    // saved with b filtered to EMEA by the chart's last click
    reopen({
      ...doc,
      cubes: doc.cubes.map((c) => (c.id === b ? { ...c, cube: { ...c.cube, query: { ...c.cube.query, filter: EMEA } } } : c)),
      views: doc.views.map((v) => (v.id === chart ? { ...v, selection: [EMEA] } : v)),
    });
    await settle();
    const chip = (): HTMLButtonElement => tile(chart).querySelector('.dc-tile-actions .dc-tile-chip') as HTMLButtonElement;
    assert.equal(chip().hidden, false);
    assert.equal(chip().textContent, 'region: EMEA ×');
    await removeTile(b);
    assert.equal(pill(chart), 'Detached');
    assert.equal(chip().hidden, true, 'no selection on a grid that is gone');
    assert.equal(chartView(chart)!.selection, undefined);
    assert.deepEqual(pg().grids, [a]);
    // a click on its marks has no grid's filter to go on: nothing is filtered, no grid on the page changes
    await drawn();
    assert.match(tile(chart).querySelector('.dc-chartpanel-chart')?.getAttribute('aria-label') ?? '', /^Bar chart .* by region, split by desk: 2 rows/,
      'drawn from its kept query: its marks are there to click');
    clickMark(tile(chart), 0, 0);
    await settle();
    assert.equal(chip().hidden, true);
    assert.equal(pg().grid(a)!.snapshot.filter, undefined);
    assert.deepEqual(pg().grid(b)!.snapshot.filter, EMEA, 'the kept grid\'s query as it was');
  });
});
