// A stand-in for node:test, so DataCube's test files run in an engine without Node: it collects describe/it/test and
// the hooks as node:test does, and globalThis.__runTests() runs them in order and answers each test's result as JSON.
// The same stand-in runs in Node for the reference, so a difference between the two runs is the engine's.

class Skip extends Error {}

const root = { name: '', parent: null, children: [], before: [], after: [], beforeEach: [], afterEach: [] };
let current = root;

function parts(a, b, c) {
  // (name, fn) | (name, options, fn) | (fn)
  if (typeof a === 'function') return { name: a.name || '<anonymous>', options: {}, fn: a };
  if (typeof b === 'function') return { name: String(a), options: {}, fn: b };
  return { name: String(a), options: b ?? {}, fn: c };
}

function suite(a, b, c, extra = {}) {
  const { name, options, fn } = parts(a, b, c);
  const node = { name, parent: current, children: [], before: [], after: [], beforeEach: [], afterEach: [],
    skip: Boolean(options.skip || extra.skip) };
  current.children.push({ kind: 'suite', node });
  const prev = current;
  current = node;
  let r;
  try {
    r = fn?.();
  } catch (e) {
    current = prev;
    throw e;
  }
  if (r && typeof r.then === 'function') {
    // an async describe: its tests register after its awaits, so the suite stays current until it settles
    node.pending = r.then(() => { current = prev; }, (e) => { current = prev; throw e; });
    return node.pending;
  }
  current = prev;
  return undefined;
}

function test(a, b, c, extra = {}) {
  const { name, options, fn } = parts(a, b, c);
  current.children.push({ kind: 'test', name, fn, skip: Boolean(options.skip || options.todo || extra.skip) });
}

export const describe = (a, b, c) => suite(a, b, c);
describe.skip = (a, b, c) => suite(a, b, c, { skip: true });
describe.only = describe;
describe.todo = describe.skip;
export const it = (a, b, c) => test(a, b, c);
it.skip = (a, b, c) => test(a, b, c, { skip: true });
it.only = it;
it.todo = it.skip;
export { it as test };
export const suiteAlias = describe;
export const before = (fn) => current.before.push(fn);
export const after = (fn) => current.after.push(fn);
export const beforeEach = (fn) => current.beforeEach.push(fn);
export const afterEach = (fn) => current.afterEach.push(fn);

// node:test's mock.timers, over a virtual clock: setTimeout/setInterval replaced while enabled, tick(ms) runs what falls due
function mockTimers() {
  let enabled = false;
  let now = 0;
  let queue = [];
  let id = 1;
  const saved = {};
  const add = (fn, ms, args, every) => {
    const t = { id: id++, at: now + Math.max(0, Number(ms) || 0), fn, args, every };
    queue.push(t);
    return t.id;
  };
  const remove = (t) => { queue = queue.filter((x) => x.id !== t); };
  return {
    enable(opts = {}) {
      if (enabled) return;
      enabled = true;
      now = typeof opts.now === 'number' ? opts.now : 0;
      for (const k of ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval']) saved[k] = globalThis[k];
      globalThis.setTimeout = (fn, ms, ...args) => add(fn, ms, args, undefined);
      globalThis.setInterval = (fn, ms, ...args) => add(fn, ms, args, Math.max(1, Number(ms) || 1));
      globalThis.clearTimeout = remove;
      globalThis.clearInterval = remove;
    },
    tick(ms = 1) {
      const until = now + ms;
      for (;;) {
        queue.sort((x, y) => x.at - y.at || x.id - y.id);
        const t = queue[0];
        if (!t || t.at > until) break;
        now = t.at;
        if (t.every) t.at += t.every; else queue.shift();
        t.fn(...t.args);
      }
      now = until;
    },
    reset() {
      if (!enabled) return;
      enabled = false;
      queue = [];
      Object.assign(globalThis, saved);
    },
  };
}

export const mock = { timers: mockTimers(), fn: (impl = () => undefined) => { const f = (...a) => { f.mock.calls.push({ arguments: a }); return impl(...a); }; f.mock = { calls: [], callCount: () => f.mock.calls.length }; return f; } };

async function call(fn, t) {
  if (fn.length >= 2) {
    await new Promise((resolve, reject) => fn(t, (e) => (e ? reject(e) : resolve())));
  } else {
    await fn(t);
  }
}

function describeError(e) {
  if (e instanceof Error) return `${e.name}: ${String(e.message).split('\n')[0].slice(0, 300)}`;
  return String(e).slice(0, 300);
}

async function runSuite(node, path, eachBefore, eachAfter, results) {
  if (node.pending) await node.pending;
  const here = node.name ? [...path, node.name] : path;
  if (node.skip) {
    for (const c of node.children) {
      results.push({ name: [...here, c.kind === 'test' ? c.name : c.node.name].join(' > '), status: 'skip' });
    }
    return;
  }
  const befores = [...eachBefore, ...node.beforeEach];
  const afters = [...node.afterEach, ...eachAfter];
  try {
    for (const h of node.before) await h();
  } catch (e) {
    results.push({ name: [...here, '<before>'].join(' > '), status: 'fail', error: describeError(e) });
    return;
  }
  for (const c of node.children) {
    if (c.kind === 'suite') {
      await runSuite(c.node, here, befores, afters, results);
      continue;
    }
    const name = [...here, c.name].join(' > ');
    if (c.skip || !c.fn) {
      results.push({ name, status: 'skip' });
      continue;
    }
    const timers = mockTimers();
    const t = { name: c.name, diagnostic() {}, skip() { throw new Skip(); }, todo() {}, mock: { timers, fn: mock.fn },
      test: async () => { throw new Error('subtests (t.test) are not in this stand-in'); } };
    try {
      for (const h of befores) await h(t);
      await call(c.fn, t);
      for (const h of afters) await h(t);
      results.push({ name, status: 'pass' });
    } catch (e) {
      results.push(e instanceof Skip ? { name, status: 'skip' } : { name, status: 'fail', error: describeError(e) });
    } finally {
      timers.reset();
    }
  }
  for (const h of node.after) {
    try { await h(); } catch (e) { results.push({ name: [...here, '<after>'].join(' > '), status: 'fail', error: describeError(e) }); }
  }
}

globalThis.__runTests = async () => {
  const results = [];
  await runSuite(root, [], [], [], results);
  return JSON.stringify(results);
};
