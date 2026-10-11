// node:test where there is no Node: the headless lane's (//datacube:headless_test) stand-in, aliased for node:test when
// DataCube's tests are bundled to run in V8. It collects describe/it and the hooks as node:test does, and `run()` runs
// them in order and answers each test's result. It decides what ran, so it follows node:test's rules where the tests
// rely on them -- hooks outer to inner (beforeEach) and inner to outer (afterEach, which runs after a failing test
// too), a suite whose `before` fails fails its tests, an async describe's tests in that describe -- and is held to them
// by headless-test-runner.test.ts. What it does not stand in for (only, mocks but the timers, subtests) is refused.
//
// One difference, of when, not what: node:test starts an async describe's function as it is registered and tells its
// later registrations apart by their async context, which V8 alone does not keep. Here an async describe's function
// runs when the run reaches its suite, one suite at a time, so whatever it registers is its own.
//
// One gap: node:test fails a file on a promise rejected and never handled; V8 alone has no hook for one that
// mini-racer passes on, so here such a test passes. A test awaits what it asserts on (the Node lane still catches one
// that does not).

class Skip extends Error {}

type Fn = (t: TestContext, done?: (e?: unknown) => void) => unknown;
type Hook = (t?: TestContext) => unknown;

interface Options {
  readonly skip?: boolean | string;
  readonly todo?: boolean | string;
  // node:test's own scheduling: no effect on what passes (a test that never ends hangs the lane, which Bazel ends)
  readonly timeout?: number;
  readonly concurrency?: number | boolean;
}

interface TestNode {
  readonly kind: 'test';
  readonly name: string;
  readonly fn: Fn | undefined;
  readonly skip: boolean;
}

interface SuiteNode {
  readonly kind: 'suite';
  readonly name: string;
  readonly skip: boolean;
  readonly children: (TestNode | SuiteNode)[];
  readonly before: Hook[];
  readonly after: Hook[];
  readonly beforeEach: Hook[];
  readonly afterEach: Hook[];
  /** An async describe's function, run when the run reaches the suite. */
  build?: () => Promise<unknown>;
}

export interface Result {
  readonly name: string;
  readonly status: 'pass' | 'fail' | 'skip';
  readonly error?: string;
}

const suiteOf = (name: string, skip: boolean): SuiteNode =>
  ({ kind: 'suite', name, skip, children: [], before: [], after: [], beforeEach: [], afterEach: [] });

const root = suiteOf('', false);
let current = root;

const KNOWN = new Set(['skip', 'todo', 'timeout', 'concurrency']);

/** (name, fn) | (name, options, fn) | (fn) | (name), as node:test reads them. */
function parts(a: unknown, b: unknown, c: unknown): { name: string; options: Options; fn: Fn | undefined } {
  let name: string;
  let options: Options = {};
  let fn: unknown;
  if (typeof a === 'function') {
    name = a.name || '<anonymous>';
    fn = a;
  } else if (typeof b === 'function') {
    name = String(a);
    fn = b;
  } else {
    name = String(a);
    options = (b ?? {}) as Options;
    fn = c;
  }
  for (const k of Object.keys(options)) {
    if (!KNOWN.has(k)) throw new Error(`node:test option '${k}' is not in the headless lane's stand-in (test/headless/node-test.ts)`);
  }
  return { name, options, fn: fn as Fn | undefined };
}

const skipped = (o: Options): boolean => Boolean(o.skip) || Boolean(o.todo);

const isAsync = (f: unknown): f is () => Promise<unknown> =>
  typeof f === 'function' && Object.prototype.toString.call(f) === '[object AsyncFunction]';

function suite(a: unknown, b?: unknown, c?: unknown, forceSkip = false): undefined {
  const { name, options, fn } = parts(a, b, c);
  const node = suiteOf(name, forceSkip || skipped(options));
  current.children.push(node);
  if (isAsync(fn)) {
    node.build = fn;
    return undefined;
  }
  const outer = current;
  current = node;
  let r: unknown;
  try {
    r = (fn as unknown as (() => unknown) | undefined)?.();
  } finally {
    current = outer;
  }
  if (r !== null && typeof r === 'object' && typeof (r as Promise<unknown>).then === 'function') {
    throw new Error(`describe '${name}': a function that returns a promise is an async function here (test/headless/node-test.ts)`);
  }
  return undefined;
}

function test(a: unknown, b?: unknown, c?: unknown, forceSkip = false): void {
  const { name, options, fn } = parts(a, b, c);
  // a test with no function is a no-op that passes, its hooks run around it, as node:test runs one
  current.children.push({ kind: 'test', name, fn: fn ?? (() => undefined), skip: forceSkip || skipped(options) });
}

const only = (): never => {
  throw new Error('.only is not run by the headless lane: a test file keeps every test (test/headless/node-test.ts)');
};

type SuiteFn = () => unknown;

/** node:test's forms: (name, fn), (name, options, fn), (fn). */
interface Register<F, R> {
  (name: string, fn?: F): R;
  (name: string, options: Options, fn?: F): R;
  (fn: F): R;
}

type Describe = Register<SuiteFn, undefined> & {
  skip: Register<SuiteFn, undefined>;
  todo: Register<SuiteFn, undefined>;
  only: () => never;
};
type It = Register<Fn, void> & {
  skip: Register<Fn, void>;
  todo: Register<Fn, void>;
  only: () => never;
};

export const describe = Object.assign((a: unknown, b?: unknown, c?: unknown) => suite(a, b, c), {
  skip: (a: unknown, b?: unknown, c?: unknown) => suite(a, b, c, true),
  todo: (a: unknown, b?: unknown, c?: unknown) => suite(a, b, c, true),
  only,
}) as Describe;
export const it = Object.assign((a: unknown, b?: unknown, c?: unknown) => test(a, b, c), {
  skip: (a: unknown, b?: unknown, c?: unknown) => test(a, b, c, true),
  todo: (a: unknown, b?: unknown, c?: unknown) => test(a, b, c, true),
  only,
}) as It;
export { it as test, describe as suite };

export const before = (fn: Hook): void => { current.before.push(fn); };
export const after = (fn: Hook): void => { current.after.push(fn); };
export const beforeEach = (fn: Hook): void => { current.beforeEach.push(fn); };
export const afterEach = (fn: Hook): void => { current.afterEach.push(fn); };

/** node:test's mocks: not stood in for; a test that needs one says so. */
export const mock = new Proxy({}, {
  get(_t, k) {
    throw new Error(`node:test's mock.${String(k)} is not in the headless lane's stand-in (test/headless/node-test.ts)`);
  },
});

interface Timer {
  readonly id: number;
  at: number;
  readonly every: number | undefined;
  readonly fn: (...a: unknown[]) => void;
  readonly args: unknown[];
}

const TIMER_APIS = new Set(['setTimeout', 'setInterval']);

/**
 * node:test's mock timers (`t.mock.timers`), for the APIs the lane's tests mock: setTimeout and setInterval (with
 * their clears), on a clock that moves only by `tick`, as Node's does (held to it by headless-test-runner.test.ts):
 * the clock moves to the tick's end first, then what falls due by then runs in order, so a timer a callback schedules
 * counts from the tick's end. One difference: an interval cleared from inside its own callback stops here, where
 * Node's mock runs it again (it puts the interval back after the callback). Reset after each test, as Node's are.
 * Another API (Date, setImmediate) is refused by name.
 */
export class MockTimers {
  #enabled = false;
  #now = 0;
  #next = 1;
  #queue: Timer[] = [];
  #cleared = new Set<number>();
  #saved: Record<string, unknown> = {};

  enable(options: { apis?: readonly string[]; now?: number } = {}): void {
    if (this.#enabled) throw new Error('mock.timers is enabled already (node:test refuses enabling twice)');
    const apis = options.apis ?? ['setTimeout', 'setInterval'];
    for (const api of apis) {
      if (!TIMER_APIS.has(api)) throw new Error(`mock.timers for ${api} is not in the headless lane's stand-in (test/headless/node-test.ts)`);
    }
    this.#enabled = true;
    this.#now = options.now ?? 0;
    const g = globalThis as unknown as Record<string, unknown>;
    const add = (fn: (...a: unknown[]) => void, ms: unknown, args: unknown[], every: boolean): number => {
      const delay = Math.max(0, Number(ms) || 0);
      const timer: Timer = { id: this.#next++, at: this.#now + delay, every: every ? Math.max(1, delay) : undefined, fn, args };
      this.#queue.push(timer);
      return timer.id;
    };
    const clear = (id: unknown): void => {
      this.#queue = this.#queue.filter((t) => t.id !== id);
      if (typeof id === 'number') this.#cleared.add(id);
    };
    for (const api of apis) {
      const clearName = api === 'setTimeout' ? 'clearTimeout' : 'clearInterval';
      this.#saved[api] = g[api];
      this.#saved[clearName] = g[clearName];
      g[api] = (fn: (...a: unknown[]) => void, ms?: unknown, ...args: unknown[]) => add(fn, ms, args, api === 'setInterval');
      g[clearName] = clear;
    }
  }

  tick(ms: number = 1): void {
    if (!this.#enabled) throw new Error('mock.timers.tick before enable');
    this.#now += ms;
    for (;;) {
      this.#queue.sort((a, b) => a.at - b.at || a.id - b.id);
      const due = this.#queue[0];
      if (due === undefined || due.at > this.#now) break;
      this.#queue.shift();
      due.fn(...due.args);
      if (due.every !== undefined && !this.#cleared.has(due.id)) {
        due.at += due.every;
        this.#queue.push(due);
      }
    }
  }

  reset(): void {
    if (!this.#enabled) return;
    Object.assign(globalThis as unknown as Record<string, unknown>, this.#saved);
    this.#saved = {};
    this.#queue = [];
    this.#cleared.clear();
    this.#enabled = false;
  }
}

/** What a test's function is given. */
export class TestContext {
  readonly name: string;
  readonly diagnostics: string[] = [];
  readonly #timers = new MockTimers();

  constructor(name: string) {
    this.name = name;
  }

  /** node:test's mocks: the timers alone (`t.mock.timers`); a mock of a function or a method is refused. */
  get mock(): { readonly timers: MockTimers } {
    const timers = this.#timers;
    return new Proxy({ timers }, {
      get(target, k) {
        if (k === 'timers') return target.timers;
        throw new Error(`t.mock.${String(k)} is not in the headless lane's stand-in (test/headless/node-test.ts)`);
      },
    });
  }

  /** @internal after the test, as node:test resets its mocks */
  resetMocks(): void {
    this.#timers.reset();
  }

  diagnostic(message: string): void {
    this.diagnostics.push(String(message));
  }

  skip(): never {
    throw new Skip();
  }

  todo(): never {
    throw new Skip();
  }

  test(): never {
    throw new Error("subtests (t.test) are not in the headless lane's stand-in (test/headless/node-test.ts)");
  }
}

async function call(fn: Fn, t: TestContext): Promise<void> {
  if (fn.length >= 2) {
    await new Promise<void>((resolve, reject) => {
      fn(t, (e?: unknown) => (e ? reject(e) : resolve()));
    });
  } else {
    await fn(t);
  }
}

function describeError(e: unknown): string {
  if (e instanceof Error) return `${e.name}: ${String(e.message).split('\n').slice(0, 6).join('\n').slice(0, 2000)}`;
  return String(e).slice(0, 2000);
}

async function runSuite(node: SuiteNode, path: readonly string[], eachBefore: readonly Hook[], eachAfter: readonly Hook[],
  results: Result[]): Promise<void> {
  const here = node.name ? [...path, node.name] : [...path];
  if (node.skip) {
    for (const c of node.children) results.push({ name: [...here, c.name].join(' > '), status: 'skip' });
    if (node.build) results.push({ name: here.join(' > '), status: 'skip' });
    return;
  }
  if (node.build) {
    // an async describe, built now: nothing else registers while it runs
    const outer = current;
    current = node;
    try {
      await node.build();
    } catch (e) {
      results.push({ name: [...here, '<describe>'].join(' > '), status: 'fail', error: describeError(e) });
      return;
    } finally {
      current = outer;
    }
  }
  if (node.skip) {
    for (const c of node.children) results.push({ name: [...here, c.name].join(' > '), status: 'skip' });
    return;
  }
  const befores = [...eachBefore, ...node.beforeEach];
  const afters = [...node.afterEach, ...eachAfter];
  const afterHooks = async (): Promise<void> => {
    for (const h of node.after) {
      try {
        await h();
      } catch (e) {
        results.push({ name: [...here, '<after>'].join(' > '), status: 'fail', error: describeError(e) });
      }
    }
  };
  try {
    for (const h of node.before) await h();
  } catch (e) {
    // node:test fails each of the suite's tests when its before hook does, and still runs its after hooks
    for (const c of node.children) {
      results.push({ name: [...here, c.name].join(' > '), status: 'fail', error: `before hook: ${describeError(e)}` });
    }
    await afterHooks();
    return;
  }
  for (const c of node.children) {
    if (c.kind === 'suite') {
      await runSuite(c, here, befores, afters, results);
      continue;
    }
    const name = [...here, c.name].join(' > ');
    if (c.skip || c.fn === undefined) {
      results.push({ name, status: 'skip' });
      continue;
    }
    const t = new TestContext(c.name);
    let outcome: Result;
    try {
      for (const h of befores) await h(t);
      await call(c.fn, t);
      outcome = { name, status: 'pass' };
    } catch (e) {
      outcome = e instanceof Skip ? { name, status: 'skip' } : { name, status: 'fail', error: describeError(e) };
    }
    // afterEach runs after a failing test too, and its own failure fails the test
    for (const h of afters) {
      try {
        await h(t);
      } catch (e) {
        if (outcome.status !== 'fail') outcome = { name, status: 'fail', error: `afterEach hook: ${describeError(e)}` };
      }
    }
    t.resetMocks();
    results.push(outcome);
  }
  await afterHooks();
}

/** Every test registered, run in order: each one's result. */
export async function run(): Promise<Result[]> {
  const results: Result[] = [];
  await runSuite(root, [], [], [], results);
  return results;
}

export default { describe, it, test: it, suite: describe, before, after, beforeEach, afterEach, mock, run };
