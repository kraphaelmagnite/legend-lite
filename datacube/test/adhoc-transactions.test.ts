// Leg B / B5a: Ad Hoc Analysis mode under the SAME transaction rules as the cube
// (docs/DATACUBE_LEG_B_STATE_OWNER_2026_09_28.md). The session committed a step before its query
// answered, re-placed answers of another grid, and ran queries Navigate Without Data defers.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { buildCube } from '../src/adhoc/outline.ts';
import { AdHocSession, type Run } from '../src/adhoc/session.ts';
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
const labels = (s: AdHocSession): string[] | undefined =>
  s.view?.table.columns[0]?.values.map((v) => String(v).trim());
const values = (s: AdHocSession): readonly unknown[] | undefined => s.view?.table.columns[1]?.values;
const tick = async (n = 20): Promise<void> => {
  for (let i = 0; i < n; i += 1) await new Promise((r) => setTimeout(r, 0));
};

describe('an Ad Hoc step is one transaction (B5a)', () => {
  it('a failed step is not committed: the grid, the POV and the history are as they were (P2-259)', async () => {
    const src = new Source();
    const s = new AdHocSession(cube(), src.run);
    await s.zoomIn('Time', []);
    const depth = s.canUndo;
    src.fail = 'the source said no';
    await assert.rejects(() => s.setPov('region', ['EMEA']));
    assert.deepEqual(s.grid.pov['region'], [], 'the POV did not move');
    assert.deepEqual(values(s), [75, 35, 40], 'the numbers on screen are the grid\'s');
    assert.equal(s.canUndo, depth);
    src.fail = null;
    await s.setPov('region', ['EMEA']);
    assert.deepEqual(values(s), [30, 30], 'a retry queries the new POV');
  });

  it('turning Navigate Without Data off re-queries a grid changed meanwhile, never re-places old answers (P2-260)', async () => {
    const src = new Source();
    const s = new AdHocSession(cube(), src.run);
    await s.zoomIn('Time', []);
    await s.setOptions({ navigateWithoutData: true });
    await s.setPov('region', ['EMEA']);
    await s.setOptions({ navigateWithoutData: false });
    assert.deepEqual([labels(s), values(s)], [['Time', '2021'], [30, 30]], 'EMEA\'s numbers under the EMEA POV');
  });

  it('an option changed while a step runs is not lost when it lands (P2-261)', async () => {
    const src = new Source();
    const s = new AdHocSession(cube(), src.run);
    await s.zoomIn('Time', []);
    await s.setPov('region', ['EMEA']);
    src.holdAfter = src.calls + 1; // the member lookup answers; the step's queries wait
    const zoom = s.zoomIn('Time', ['2021']);
    await tick();
    assert.ok(src.held.length > 0, 'the step is running');
    await s.setOptions({ suppressMissingRows: false });
    src.releaseAll();
    await zoom;
    await tick();
    assert.equal(s.grid.options.suppressMissingRows, false);
    assert.ok(labels(s)?.includes('2022'), `the landed view honours the option: ${labels(s)?.join(',')}`);
  });

  it('Undo and Redo with Navigate Without Data on run no query (P2-282)', async () => {
    const src = new Source();
    const s = new AdHocSession(cube(), src.run);
    await s.refresh();
    await s.setOptions({ navigateWithoutData: true });
    await s.zoomIn('Time', []);
    const asked = src.asked.length;
    await s.undo();
    await s.redo();
    assert.equal(src.asked.length, asked, 'no query');
  });
});

