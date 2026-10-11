// adhoc-transactions.test.ts's screen tests, in jsdom: Ad Hoc on screen (B5a), Member Selection follows its dimension (B5c, P2-280). The others are in adhoc-transactions.test.ts, which
// runs in the headless lane too (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';

import { JSDOM } from 'jsdom';
import { AdHocMode } from '../src/adhoc/mode.ts';
import { buildCube } from '../src/adhoc/outline.ts';
import { AdHocSession, type Run } from '../src/adhoc/session.ts';
import { FormatterCache } from '../src/format.ts';
import { fakeRun, SNAPSHOT } from './adhoc-fixture.ts';
import { Gate } from './gate.ts';

/** The fixture's source, through a gate the test holds or fails. */
class Source extends Gate {
  readonly asked: string[] = [];
  readonly #inner = fakeRun(this.asked);
  readonly run: Run = async (snapshot, scope) => {
    await this.pass();
    return this.#inner(snapshot, scope);
  };
}

const cube = () => buildCube(SNAPSHOT, [{ name: 'Time', columns: ['year', 'quarter'] }]);

const tick = async (n = 20): Promise<void> => {
  for (let i = 0; i < n; i += 1) await new Promise((r) => setTimeout(r, 0));
};

describe('Ad Hoc on screen (B5a)', () => {
  let dom: JSDOM;
  let host: HTMLElement;
  let statuses: string[];
  let failures: unknown[];
  beforeEach(() => {
    dom = new JSDOM('<!doctype html><body><div id="m"></div></body>');
    const doc = dom.window.document as unknown as Document;
    host = doc.getElementById('m') as HTMLElement;
    const win = dom.window as unknown as { requestAnimationFrame: unknown };
    win.requestAnimationFrame = (cb: FrameRequestCallback) => { cb(0); return 1; };
    globalThis.requestAnimationFrame = win.requestAnimationFrame as typeof requestAnimationFrame;
    globalThis.cancelAnimationFrame = () => {};
    statuses = [];
    failures = [];
  });
  const mount = (src: Source): AdHocMode => new AdHocMode(host, new AdHocSession(cube(), src.run), {
    formatters: new FormatterCache(),
    rowHeight: 20,
    showWindow: () => {},
    startTask: () => () => {},
    status: (text) => statuses.push(text),
    reportFailure: (e) => failures.push(e),
    onExit: () => {},
  });

  it('Navigate Without Data: a step says it was not queried, and a real Refresh is not "(not refreshed)" (P2-268)', async () => {
    const src = new Source();
    const mode = mount(src);
    await mode.refresh();
    await mode.session.setOptions({ navigateWithoutData: true });
    // a double-click as a browser reports it, on the cell found again (the first click re-renders it)
    for (const detail of [1, 2]) {
      ([...host.querySelectorAll<HTMLElement>('.dc-cell')].find((c) => c.textContent?.trim() === 'Time')
        ?? assert.fail('no Time cell')).dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true, cancelable: true, detail }));
    }
    await tick();
    assert.equal(mode.session.grid.rows.length > 0 && mode.session.stale, true,
      `the zoom was taken without data (stale=${mode.session.stale}, statuses: ${statuses.join(' | ')})`);
    assert.match(statuses.at(-1) ?? '', /Navigating without data/, `after a deferred zoom: ${statuses.at(-1)}`);
    await mode.refresh();
    assert.doesNotMatch(statuses.at(-1) ?? '', /not refreshed/, `after a real Refresh: ${statuses.at(-1)}`);
  });

  it('navigating without data, the headers read as the grid on screen: no raw separators (P2-269, pinned: no longer reproduces)', async () => {
    const src = new Source();
    const mode = mount(src);
    const doc = dom.window.document;
    const fire = (el: Element, type: string): void => {
      el.dispatchEvent(new dom.window.MouseEvent(type, { bubbles: true, cancelable: true }));
    };
    const item = (label: string): HTMLElement => [...doc.querySelectorAll<HTMLElement>('[role="menuitem"]')]
      .find((m) => m.querySelector('.dc-menu-label, span')?.textContent === label || m.textContent?.startsWith(label))
      ?? assert.fail(`no menu entry ${label}`);
    const heads = (): string[] => [...host.querySelectorAll<HTMLElement>('.dc-th')].map((h) => h.textContent ?? '');
    const cellOf = (text: string): HTMLElement => [...host.querySelectorAll<HTMLElement>('.dc-cell')]
      .find((c) => c.textContent?.trim() === text) ?? assert.fail(`no cell ${text}`);
    await mode.refresh();
    fire(host.querySelector('.dc-adhoc-pov-chip') as HTMLElement, 'contextmenu');
    item('Move to Rows').click();
    await tick();
    fire(cellOf('Time'), 'contextmenu');
    item('Pivot to Columns').click();
    await tick();
    const top = [...host.querySelectorAll<HTMLElement>('.dc-th')].find((h) => h.textContent === 'Time')
      ?? assert.fail('no Time header');
    fire(top, 'dblclick');
    await tick();
    assert.ok(heads().includes('2021'), `Time is across, zoomed: ${heads().join(' | ')}`);
    await mode.session.setOptions({ navigateWithoutData: true });
    const year = [...host.querySelectorAll<HTMLElement>('.dc-th')].find((h) => h.textContent === '2021')
      ?? assert.fail('no 2021 header');
    // Time off the grid to the POV, without data: the grid now has one column dimension, the
    // view on screen (not refreshed) still two
    fire(year, 'contextmenu');
    item('Pivot to POV').click();
    await tick();
    assert.equal(mode.session.stale, true, 'the step was taken without data');
    assert.equal(heads().some((h) => h.includes('__|__')), false, `raw separators: ${heads().join(' | ')}`);
  });

  it("exiting stops the step in flight: nothing reaches the cube's status or opens an error after (P2-270)", async () => {
    const src = new Source();
    const mode = mount(src);
    await mode.refresh();
    src.hold = true;
    src.fail = 'the source said no';
    const step = mode.refresh();
    await tick();
    const said = statuses.length;
    mode.destroy();
    src.releaseAll();
    await step;
    await tick();
    assert.equal(statuses.length, said, `nothing said after exit: ${statuses.slice(said).join(' | ')}`);
    assert.deepEqual(failures, [], 'no error window for a mode that is gone');
  });
});

describe('Member Selection follows its dimension (B5c, P2-280)', () => {
  it('opened from the POV, then the dimension moves to Rows: opening it again gives the ROWS window, not the old one', async () => {
    const dom = new JSDOM('<!doctype html><body><div id="m"></div></body>');
    const doc = dom.window.document as unknown as Document;
    const host = doc.getElementById('m') as HTMLElement;
    const win = dom.window as unknown as { requestAnimationFrame: unknown };
    win.requestAnimationFrame = (cb: FrameRequestCallback) => { cb(0); return 1; };
    globalThis.requestAnimationFrame = win.requestAnimationFrame as typeof requestAnimationFrame;
    globalThis.cancelAnimationFrame = () => {};
    // windows as the app keeps them: by title, an open one raised rather than rebuilt
    const open = new Map<string, HTMLElement>();
    const statuses: string[] = [];
    const src = new Source();
    const mode = new AdHocMode(host, new AdHocSession(cube(), src.run), {
      formatters: new FormatterCache(),
      rowHeight: 20,
      showWindow: (title, build) => {
        if (open.has(title)) return;
        const el = doc.createElement('div');
        doc.body.append(el);
        open.set(title, el);
        build(el, () => { el.remove(); open.delete(title); });
      },
      startTask: () => () => {},
      status: (text) => statuses.push(text),
      reportFailure: () => {},
      onExit: () => {},
    });
    await mode.refresh();
    mode.openMemberSelection('region', 'pov');
    await tick();
    const kind = (): string | undefined => open.get('Member Selection: region')
      ?.querySelector<HTMLInputElement>('input[type="radio"], input[type="checkbox"]')?.type;
    assert.equal(kind(), 'radio', 'from the POV: one pick');
    await mode.session.povToAxis('region', 'rows');
    mode.openMemberSelection('region', 'axis');
    await tick();
    assert.equal(kind(), 'checkbox', 'on the rows: many picks, not the POV window raised');
  });
});
