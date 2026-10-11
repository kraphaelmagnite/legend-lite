// share-link.test.ts's pins of the frozen dictionaries, by node:crypto's digest. The others are in share-link.test.ts,
// which runs in the headless lane too (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { describe, it } from 'node:test';

import { LINK_DICTIONARY_P1 } from '../src/share/link-p1.ts';

/** Each shipped dictionary version's hash: a version is frozen the moment links can be made with it. */
const PINNED: Record<string, string> = {
  p1: '2201c3d451efb867fd77d850a1fd351c35dbcdb90593ac3b6fcf920bb9708bd4',
};

describe('the p1 dictionary is FROZEN', () => {
  it('its bytes never change: every link made with it must open forever', () => {
    const hash = createHash('sha256').update(LINK_DICTIONARY_P1).digest('hex');
    // A change here breaks every p1 link ever shared. Never re-pin: make the next version instead
    // (bazel run //datacube:cut_link_dictionary).
    assert.equal(hash, PINNED.p1);
  });

  it('every version in the tree is pinned, and the next one is not cut yet', () => {
    // datacube/BUILD.bazel passes the versions it finds (src/share/link-*.ts) and _NEXT_LINK_VERSION
    const versions = (process.env.LINK_VERSIONS ?? '').split(' ').filter((v) => v !== '');
    assert.ok(versions.includes('p1'), `LINK_VERSIONS is ${JSON.stringify(process.env.LINK_VERSIONS)}`);
    for (const v of versions) {
      assert.ok(v in PINNED, `src/share/link-${v}.ts is cut: pin its hash in PINNED here, and move`
        + ' _NEXT_LINK_VERSION on in datacube/BUILD.bazel');
    }
    const next = process.env.NEXT_LINK_VERSION ?? '';
    assert.ok(next !== '' && !versions.includes(next), `${next} is shipped: move _NEXT_LINK_VERSION on in datacube/BUILD.bazel`);
  });
});

