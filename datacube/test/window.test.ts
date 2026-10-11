// The dialogs were not dialogs: a block appended after the grid, so
// opening one pushed the page down and showed it BELOW the data it
// was about. These are the arithmetic and the wiring of the floating
// window that replaced it -- DataCube's own figures, from
// DataCubeLayoutService.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  WINDOW_HEIGHT,
  WINDOW_MIN_HEIGHT,
  WINDOW_MIN_WIDTH,
  WINDOW_OFFSET,
  WINDOW_WIDTH,
  fitWindow,
  resize,
} from '../src/ui/window.ts';

describe('fitting a window to its container', () => {
  it('centres it at the default size when there is room', () => {
    const spec = fitWindow({ width: 1400, height: 900 });
    assert.equal(spec.width, WINDOW_WIDTH);
    assert.equal(spec.height, WINDOW_HEIGHT);
    assert.equal(spec.x, (1400 - WINDOW_WIDTH) / 2);
    assert.equal(spec.y, (900 - WINDOW_HEIGHT) / 2);
  });

  it('SHRINKS rather than overflowing a small container', () => {
    // A window wider than the window is not a window. Upstream takes
    // the container less a margin on each side.
    const spec = fitWindow({ width: 600, height: 500 });
    assert.equal(spec.width, 600 - WINDOW_OFFSET * 2);
    assert.equal(spec.height, 500 - WINDOW_OFFSET * 2);
    assert.equal(spec.x, WINDOW_OFFSET);
  });

  it('never shrinks below the minimum, however small the container', () => {
    // A 40px window cannot be grabbed to make it bigger again, so the
    // floor wins over the fit.
    const spec = fitWindow({ width: 200, height: 150 });
    assert.equal(spec.width, WINDOW_MIN_WIDTH);
    assert.equal(spec.height, WINDOW_MIN_HEIGHT);
  });

  it('reads a NEGATIVE coordinate from the far edge', () => {
    // How a window asks to sit bottom-right without being told how
    // big the container is.
    const spec = fitWindow({ width: 1000, height: 800 }, {
      center: false, x: -20, y: -20, width: 400, height: 300,
    });
    assert.equal(spec.x, 1000 - 20 - 400);
    assert.equal(spec.y, 800 - 20 - 300);
  });
});

describe('resizing from an edge', () => {
  const from = { x: 100, y: 100, width: 400, height: 300 };

  it('grows to the east without moving', () => {
    assert.deepEqual(resize(from, 'e', 50, 0),
      { x: 100, y: 100, width: 450, height: 300 });
  });

  it('MOVES as it shrinks from the west', () => {
    // The right edge stays put, so x follows the width it did not
    // get. Getting this wrong makes the window jump sideways.
    assert.deepEqual(resize(from, 'w', 50, 0),
      { x: 150, y: 100, width: 350, height: 300 });
  });

  it('stops the dragged edge at the minimum, not the opposite one', () => {
    // Dragging west past the floor must leave the RIGHT edge where it
    // was: x + width has to stay at 500.
    const out = resize(from, 'w', 1000, 0, 300, 300);
    assert.equal(out.width, 300);
    assert.equal(out.x + out.width, 500);
  });

  it('takes both axes from a corner', () => {
    assert.deepEqual(resize(from, 'se', 25, 40),
      { x: 100, y: 100, width: 425, height: 340 });
  });

  it('moves x and y together from the north-west corner', () => {
    // Explicit minimums: the fixture is 300 tall and the DEFAULT
    // floor is 300, so with the defaults it cannot shrink at all and
    // the test would be measuring the floor rather than the corner.
    const out = resize(from, 'nw', 20, 30, 100, 100);
    assert.deepEqual(out, { x: 120, y: 130, width: 380, height: 270 });
  });

  it('cannot shrink below the floor even from a corner', () => {
    const out = resize(from, 'nw', 1000, 1000);
    assert.equal(out.width, WINDOW_MIN_WIDTH);
    assert.equal(out.height, WINDOW_MIN_HEIGHT);
    // And the far corner stays where it was.
    assert.equal(out.x + out.width, 500);
    assert.equal(out.y + out.height, 400);
  });
});

