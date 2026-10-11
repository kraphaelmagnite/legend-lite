import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  placeColumn,
} from '../src/ui/pivot-panel.ts';

describe('placeColumn', () => {
  it('appends a new column', () => {
    assert.deepEqual(placeColumn(['a'], 'b', 1), ['a', 'b']);
  });

  it('reorders, accounting for the chip leaving its old place', () => {
    // The index was measured against the list as DISPLAYED, so
    // moving right by one is otherwise a no-op.
    assert.deepEqual(placeColumn(['a', 'b', 'c'], 'a', 2), ['b', 'a', 'c']);
    assert.deepEqual(placeColumn(['a', 'b', 'c'], 'c', 0), ['c', 'a', 'b']);
  });

  it('removes on a null index', () => {
    assert.deepEqual(placeColumn(['a', 'b'], 'a', null), ['b']);
  });

  it('returns the SAME array when nothing moved', () => {
    // A drop that lands where the chip already was must not re-run
    // the query.
    const list = ['a', 'b'];
    assert.equal(placeColumn(list, 'a', 0), list);
    assert.equal(placeColumn(list, 'c', null), list);
  });

  it('clamps past the end', () => {
    assert.deepEqual(placeColumn(['a'], 'b', 99), ['a', 'b']);
  });
});

