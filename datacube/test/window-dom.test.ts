// window.test.ts's screen tests, in jsdom: a window in the DOM. The others are in window.test.ts, which
// runs in the headless lane too (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import assert from 'node:assert/strict';
import { beforeEach, describe, it } from 'node:test';

import { JSDOM } from 'jsdom';
import {
  makeWindow,
} from '../src/ui/window.ts';

describe('a window in the DOM', () => {
  let dom: JSDOM;
  let container: HTMLElement;
  let el: HTMLElement;
  let head: HTMLElement;

  beforeEach(() => {
    dom = new JSDOM('<!doctype html><body><div id="c"></div></body>');
    container = dom.window.document.getElementById('c') as HTMLElement;
    Object.defineProperty(container, 'getBoundingClientRect', {
      value: () => ({ width: 1000, height: 800, top: 0, left: 0, right: 1000,
        bottom: 800, x: 0, y: 0, toJSON: () => ({}) }),
      configurable: true,
    });
    el = dom.window.document.createElement('div');
    head = dom.window.document.createElement('div');
    el.append(head);
    container.append(el);
  });

  it('positions and sizes the element', () => {
    const spec = makeWindow(el, head, container);
    assert.equal(el.style.left, `${spec.x}px`);
    assert.equal(el.style.top, `${spec.y}px`);
    assert.equal(el.style.width, `${spec.width}px`);
    assert.equal(el.style.height, `${spec.height}px`);
    assert.ok(el.classList.contains('dc-window'));
  });

  it('adds a grip for all EIGHT directions', () => {
    makeWindow(el, head, container);
    const grips = [...el.querySelectorAll('.dc-window-grip')]
      .map((g) => [...g.classList].find((c) => /^dc-window-[nsew]{1,2}$/
        .test(c)));
    assert.deepEqual(grips.sort(), [
      'dc-window-e', 'dc-window-n', 'dc-window-ne', 'dc-window-nw',
      'dc-window-s', 'dc-window-se', 'dc-window-sw', 'dc-window-w',
    ]);
  });

  it('marks the title bar as the handle', () => {
    makeWindow(el, head, container);
    assert.ok(head.classList.contains('dc-window-handle'));
  });

  it('restores a remembered spec instead of centring again', () => {
    // Reopening a dialog should find it where it was left; one that
    // jumps back to the middle is one you move again every time.
    const spec = { x: 11, y: 22, width: 333, height: 444 };
    const out = makeWindow(el, head, container, { spec });
    assert.deepEqual(out, spec);
    assert.equal(el.style.left, '11px');
    assert.equal(el.style.width, '333px');
  });
});
