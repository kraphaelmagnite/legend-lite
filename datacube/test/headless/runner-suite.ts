// One suite, registered on a node:test API it is given: Node's own (runner-entry.ts, run in a child Node) or the
// headless lane's stand-in (node-test.ts). headless-test-runner.test.ts runs it both ways and compares what ran,
// passed, failed and was skipped, and the order the hooks and tests ran in.

/** What a node:test API offers the suite: the functions it registers with. */
export interface Api {
  describe(name: string, fn: () => unknown): unknown;
  it(name: string, fn?: (t: { skip(): void; diagnostic(m: string): void; name: string }, done?: (e?: unknown) => void) => unknown): unknown;
  before(fn: () => unknown): unknown;
  after(fn: () => unknown): unknown;
  beforeEach(fn: () => unknown): unknown;
  afterEach(fn: () => unknown): unknown;
}

export function register(api: Api, order: string[]): void {
  const push = (s: string) => (): void => { order.push(s); };
  api.before(push('root before'));
  api.after(push('root after'));
  api.describe('hooks', () => {
    api.before(push('hooks before'));
    api.after(push('hooks after'));
    api.beforeEach(push('outer beforeEach'));
    api.afterEach(push('outer afterEach'));
    api.it('passes', push('passes'));
    api.it('fails', () => { order.push('fails'); throw new Error('no'); });
    api.it('no function');
    api.describe('nested', () => {
      api.beforeEach(push('inner beforeEach'));
      api.afterEach(push('inner afterEach'));
      api.it('inner', push('inner'));
    });
  });
  api.describe('async', async () => {
    await Promise.resolve();
    api.it('registered after an await', push('late'));
  });
  api.describe('a before that fails', () => {
    api.before(() => { order.push('failing before'); throw new Error('setup'); });
    api.after(push('after, its before having failed'));
    api.it('x', push('never: x'));
    api.it('y', push('never: y'));
  });
  api.describe('an afterEach that fails', () => {
    api.afterEach(() => { throw new Error('teardown'); });
    api.it('z', push('z'));
  });
  api.describe('a nested beforeEach that fails', () => {
    api.describe('inside', () => {
      api.beforeEach(() => { order.push('failing beforeEach'); throw new Error('each'); });
      api.it('w', push('never: w'));
    });
    api.it('beside it', push('beside it'));
  });
  api.it('a done callback', (_t, done) => { setTimeout(() => done?.(), 1); });
  api.it('a done callback given an error', (_t, done) => { done?.(new Error('callback')); });
  api.it('a rejection', async () => { throw new TypeError('async'); });
  api.it('t.skip()', (t) => t.skip());
  api.it('t.diagnostic and t.name', (t) => { t.diagnostic('noted'); order.push(`name ${t.name}`); });
}
