import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { DEFAULT_CONFIGURATION } from '../src/config.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';
import { element } from '../../pure-protocol/src/index.ts';

const SNAPSHOT: CubeSnapshot = {
  source: { query: element('trades') },
  columns: [
    { name: 'region', type: 'String' },
    { name: 'desk', type: 'String' },
    { name: 'notional', type: 'Float' },
  ],
  derived: [],
  rows: ['region'],
  pivotOn: [],
  measures: [{ name: 'total', column: 'notional', fn: 'sum' }],
  sorts: [],
  epoch: 1,
};

describe('applying the editor', () => {
  it("keeps the Dimensions tab's hierarchies on the configuration", async () => {
    const { mergeDraft } = await import('../src/app.ts');
    const base = { snapshot: SNAPSHOT, config: DEFAULT_CONFIGURATION, dimensions: [] };
    const geo = [{ name: 'Geography', columns: ['region', 'desk'] }];
    const merged = mergeDraft(base, base, { ...base, dimensions: geo });
    assert.deepEqual(merged.config.dimensions, geo);
    // Untouched, nothing is written: the host's stay in charge.
    assert.equal(mergeDraft(base, base, base).config.dimensions, undefined);
  });
});
