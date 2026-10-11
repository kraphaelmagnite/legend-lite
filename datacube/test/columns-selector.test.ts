import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  availableOf,
  filterColumns,
  indexForY,
  insertAt,
  without,
  type SelectorColumn,
} from '../src/ui/columns-selector.ts';

const ALL: SelectorColumn[] = [
  { name: 'region' },
  { name: 'country' },
  { name: 'city' },
  { name: 'desk' },
  { name: 'notional' },
];

describe('insertAt', () => {
  it('reorders within a list, accounting for the removal', () => {
    // The drop index was measured against the list BEFORE the item
    // was pulled out, so moving down by one has to shift back past
    // itself or it becomes a no-op.
    assert.deepEqual(insertAt(['a', 'b', 'c'], ['a'], 2), ['b', 'a', 'c']);
    assert.deepEqual(insertAt(['a', 'b', 'c'], ['a'], 3), ['b', 'c', 'a']);
    assert.deepEqual(insertAt(['a', 'b', 'c'], ['c'], 0), ['c', 'a', 'b']);
  });

  it('moves a block, keeping its internal order', () => {
    assert.deepEqual(insertAt(['a', 'b', 'c', 'd'], ['a', 'b'], 4), [
      'c',
      'd',
      'a',
      'b',
    ]);
  });

  it('inserts from outside the list', () => {
    assert.deepEqual(insertAt(['a', 'b'], ['x'], 1), ['a', 'x', 'b']);
  });

  it('clamps an index past either end', () => {
    assert.deepEqual(insertAt(['a'], ['x'], 99), ['a', 'x']);
    assert.deepEqual(insertAt(['a'], ['x'], -3), ['x', 'a']);
  });
});

describe('indexForY', () => {
  const rows = [
    { top: 0, height: 20 },
    { top: 20, height: 20 },
    { top: 40, height: 20 },
  ];

  it('picks the gap by the row MIDPOINT', () => {
    assert.equal(indexForY(rows, 5), 0);
    assert.equal(indexForY(rows, 15), 1);
    assert.equal(indexForY(rows, 25), 1);
    assert.equal(indexForY(rows, 35), 2);
  });

  it('makes the position after the last row reachable', () => {
    // Nearest-row would clamp this to 2, and for a pivot list the
    // end is the position most often wanted.
    assert.equal(indexForY(rows, 55), 3);
  });

  it('an empty list is index zero', () => {
    assert.equal(indexForY([], 100), 0);
  });
});

describe('search and derivation', () => {
  it('matches case-insensitively on a substring', () => {
    assert.deepEqual(filterColumns(['region', 'Desk'], 'E'), [
      'region',
      'Desk',
    ]);
    assert.deepEqual(filterColumns(['region', 'desk'], '  '), [
      'region',
      'desk',
    ]);
  });

  it('available is everything unselected, in SOURCE order', () => {
    // Not in the order they were removed: the available pane mirrors
    // the model, and a user looking for a column expects it where it
    // has always been.
    assert.deepEqual(availableOf({ all: ALL, selected: ['city', 'region'] }), [
      'country',
      'desk',
      'notional',
    ]);
  });

  it('without drops every named column', () => {
    assert.deepEqual(without(['a', 'b', 'c'], ['b', 'z']), ['a', 'c']);
  });
});

