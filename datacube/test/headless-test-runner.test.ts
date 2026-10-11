// The headless lane's node:test stand-in (test/headless/node-test.ts) held to node:test itself: one suite
// (test/headless/runner-suite.ts) registered on the stand-in here and run, and the same suite run by Node's own
// node:test in a child Node -- this test's own binary, its own flags -- whose reporter says what each test came to.
// What ran, passed, failed and was skipped, and the order the hooks and tests ran in, must be the same. The mock
// timers are compared the same way, in this process.

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

import * as T from './headless/node-test.ts';
import { register } from './headless/runner-suite.ts';

const ours: string[] = [];
register(T as never, ours);
// the stand-in's own forms, beside the shared suite: a skipped suite (node:test reports it once, the stand-in each of
// its tests) and a skip by option
T.describe.skip('a skipped suite', () => {
  T.it('w', () => { ours.push('never: skipped suite'); });
});
T.it('options: skip', { skip: true }, () => { ours.push('never: options skip'); });

/** The suite on Node's own node:test: each test's path and outcome, and the order things ran in. */
function nodes(): { results: [string, string][]; order: string[] } {
  const entry = fileURLToPath(new URL('./headless/runner-entry.ts', import.meta.url));
  // a reporter is a module specifier, so its file: URL (on Windows a drive path reads as a URL scheme)
  const reporter = new URL('./headless/runner-reporter.mjs', import.meta.url).href;
  const ran = spawnSync(process.execPath, [...process.execArgv, `--test-reporter=${reporter}`, '--test-reporter-destination=stdout', entry],
    { encoding: 'utf8', timeout: 60_000 });
  const lines = ran.stdout.split('\n');
  const results = lines.filter((l) => l.startsWith('RESULT ')).map((l) => JSON.parse(l.slice('RESULT '.length)) as [string, string]);
  const order = lines.filter((l) => l.startsWith('ORDER ')).map((l) => JSON.parse(l.slice('ORDER '.length)) as string[])[0];
  assert.ok(results.length > 0 && order !== undefined, `Node's run said nothing: ${ran.stdout}\n${ran.stderr}`);
  return { results, order };
}

describe('the stand-in runs a suite as node:test runs it', () => {
  it("reports each test as Node's own node:test does, and runs the hooks in its order", async () => {
    const results = await T.run();
    const shared = results.filter((r) => !r.name.startsWith('a skipped suite') && r.name !== 'options: skip');
    const node = nodes();
    assert.deepEqual(shared.map((r) => [r.name, r.status]), node.results);
    assert.deepEqual(ours.filter((o) => !o.startsWith('never:')), node.order);
    assert.deepEqual(results.filter((r) => !shared.includes(r)).map((r) => [r.name, r.status]),
      [['a skipped suite > w', 'skip'], ['options: skip', 'skip']]);
    assert.ok(!ours.some((o) => o.startsWith('never:')), `nothing skipped ran: ${ours}`);
    assert.match(results.find((r) => r.name === 'a rejection')!.error!, /^TypeError: async/);
    assert.match(results.find((r) => r.name === 'an afterEach that fails > z')!.error!, /teardown/);
  });

  it("runs mock timers as node:test's own do", (t) => {
    // one scenario through Node's t.mock.timers and through the stand-in's: the same timers fire, in the same order
    const scenario = (timers: { enable(o?: { apis?: string[] }): void; tick(ms?: number): void }): string[] => {
      const order: string[] = [];
      timers.enable({ apis: ['setTimeout', 'setInterval'] });
      setTimeout(() => order.push('t10'), 10);
      setTimeout(() => order.push('t0'), 0);
      const cleared = setTimeout(() => order.push('cleared'), 5);
      clearTimeout(cleared);
      setTimeout(() => { order.push('t5'); setTimeout(() => order.push('t5+3'), 3); }, 5);
      let runs = 0;
      const every = setInterval(() => { runs += 1; order.push(`i7#${runs}`); }, 7);
      // cleared by another timer: Node's mock runs an interval cleared inside its own callback again (node-test.ts)
      setTimeout(() => clearInterval(every), 22);
      timers.tick(0); order.push('|0');
      timers.tick(9); order.push('|9');
      timers.tick(30); order.push('|39');
      return order;
    };
    const theirs = scenario(t.mock.timers as never);
    t.mock.timers.reset();
    const standIn = new T.TestContext('x');
    const ours = scenario(standIn.mock.timers);
    standIn.resetMocks();
    assert.deepEqual(ours, theirs);
    assert.throws(() => new T.TestContext('y').mock.timers.enable({ apis: ['Date'] }), /mock\.timers for Date is not in the headless lane's stand-in/);
  });

  it('refuses what it does not stand in for, by name', () => {
    assert.throws(() => T.it.only(), /\.only is not run by the headless lane/);
    assert.throws(() => (T.mock as unknown as { timers: unknown }).timers, /mock\.timers is not in the headless lane's stand-in/);
    assert.throws(() => (new T.TestContext('t').mock as unknown as { fn: unknown }).fn, /t\.mock\.fn is not in the headless lane's stand-in/);
    assert.throws(() => new T.TestContext('t').test(), /subtests/);
    assert.throws(() => T.it('x', { plan: 1 } as never, () => undefined), /option 'plan' is not in the headless lane's stand-in/);
  });
});
