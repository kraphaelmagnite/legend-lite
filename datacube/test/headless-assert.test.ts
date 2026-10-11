// The headless lane's node:assert stand-in (test/headless/node-assert.ts) held to Node's own node:assert/strict: it
// judges DataCube's tests in V8, so a stand-in more lenient than Node would pass a test Node fails. Every assertion the
// lane's tests use is asked here over a corpus of values -- each against each, as both a value and a deep copy -- and
// Node's answer and the stand-in's must be the same: both pass, or both fail with the same kind of error.

import * as nodeAssert from 'node:assert/strict';
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import * as standIn from './headless/node-assert.ts';

/** How a call came out: passed, or the kind of error it threw. */
async function outcome(call: () => unknown): Promise<string> {
  try {
    await call();
    return 'pass';
  } catch (e) {
    if (e instanceof Error) return e.name === 'AssertionError' ? 'AssertionError' : e.constructor.name;
    return `threw ${typeof e}`;
  }
}

type Both = typeof standIn;
const NODE = nodeAssert as unknown as Both;
const OURS = standIn;

/** One call made of each, compared; its description when they differ. */
async function differs(what: string, call: (a: Both) => unknown): Promise<string | null> {
  const [theirs, ours] = [await outcome(() => call(NODE)), await outcome(() => call(OURS))];
  return theirs === ours ? null : `${what}: Node ${theirs}, ours ${ours}`;
}

class Point {
  readonly x: number;
  readonly y: number;
  constructor(x: number, y: number) {
    this.x = x;
    this.y = y;
  }
}

const sym = Symbol('k');
const cyclic = (): Record<string, unknown> => {
  const o: Record<string, unknown> = { a: 1 };
  o['self'] = o;
  return o;
};
const withHole = (): unknown[] => {
  const a = [1, 2, 3];
  delete a[1];
  return a;
};
const errorWith = (name: string, message: string): Error => {
  const e = new Error(message);
  e.name = name;
  return e;
};

/** The corpus: each made afresh by a function, so a value and its deep copy are two objects. */
const VALUES: readonly (readonly [string, () => unknown])[] = [
  ['0', () => 0], ['-0', () => -0], ['NaN', () => NaN], ['1', () => 1], ['1.5', () => 1.5], ["'1'", () => '1'], ["''", () => ''],
  ['true', () => true], ['false', () => false], ['null', () => null], ['undefined', () => undefined], ['1n', () => 1n],
  ['{}', () => ({})], ['{a:1}', () => ({ a: 1 })], ['{a:1,b:undefined}', () => ({ a: 1, b: undefined })],
  ['{a:{b:[1,2]}}', () => ({ a: { b: [1, 2] } })], ['{a:{b:[1,3]}}', () => ({ a: { b: [1, 3] } })],
  ['{b:1,a:2}', () => ({ b: 1, a: 2 })], ['{a:2,b:1}', () => ({ a: 2, b: 1 })],
  ['null-proto', () => Object.assign(Object.create(null) as object, { a: 1 })],
  ['Point(1,2)', () => new Point(1, 2)], ['{x:1,y:2}', () => ({ x: 1, y: 2 })],
  ['[]', () => []], ['[1,2]', () => [1, 2]], ['[2,1]', () => [2, 1]], ['[1,2,3] hole', withHole], ['[1,undefined,3]', () => [1, undefined, 3]],
  ['[[1],[2]]', () => [[1], [2]]], ["['1']", () => ['1']],
  ['Date 0', () => new Date(0)], ['Date 1', () => new Date(1)], ['Date NaN', () => new Date(NaN)],
  ['/a/', () => /a/], ['/a/g', () => /a/g], ['/b/', () => /b/],
  ['Map a->1', () => new Map([['a', 1]])], ['Map a->2', () => new Map([['a', 2]])], ['Map {k}->1', () => new Map([[{ k: 1 }, 1]])],
  ['Map {k}->2', () => new Map([[{ k: 1 }, 2]])], ['Map 1->a,2->b', () => new Map([[1, 'a'], [2, 'b']])],
  ['Map 2->b,1->a', () => new Map([[2, 'b'], [1, 'a']])],
  ['Set 1,2', () => new Set([1, 2])], ['Set 2,1', () => new Set([2, 1])], ['Set 1,3', () => new Set([1, 3])],
  ['Set {a},{b}', () => new Set([{ a: 1 }, { b: 1 }])], ['Set {b},{a}', () => new Set([{ b: 1 }, { a: 1 }])],
  ['Set {a},{a}', () => new Set([{ a: 1 }, { a: 1 }])],
  ['Uint8 1,2', () => Uint8Array.from([1, 2])], ['Int8 1,2', () => Int8Array.from([1, 2])], ['Uint8 1,3', () => Uint8Array.from([1, 3])],
  ['Float64 NaN', () => Float64Array.from([NaN])], ['Float64 0', () => Float64Array.from([0])], ['Float64 -0', () => Float64Array.from([-0])],
  ['ArrayBuffer 2', () => Uint8Array.from([1, 2]).buffer],
  ['Error a', () => new Error('a')], ['Error b', () => new Error('b')], ['TypeError a', () => new TypeError('a')],
  ['named a', () => errorWith('Named', 'a')], ['Error a cause', () => new Error('a', { cause: 1 })],
  ['Number 1', () => Object(1) as unknown], ['Number 2', () => Object(2) as unknown], ['String a', () => Object('a') as unknown],
  ['{[sym]:1}', () => ({ [sym]: 1 })], ['{[sym]:2}', () => ({ [sym]: 2 })],
  ['cyclic', cyclic], ['function', () => Math.max],
  // members sharing an object: a Set's search tries one member against several, and a pair that failed stays failed
  ['Set shared s,t', () => { const a = { v: 1 }; const b = { v: 2 }; return new Set([{ p: a, z: 1 }, { p: b, z: 2 }]); }],
  ['Set shared t,t', () => { const b = { v: 2 }; return new Set([{ p: b, z: 2 }, { p: b, z: 1 }]); }],
  ['Map shared', () => { const a = { v: 1 }; return new Map<unknown, unknown>([[{ k: a }, a], [{ k: { v: 2 } }, a]]); }],
];

describe('the stand-in judges as node:assert/strict judges', () => {
  it(`deepEqual and notDeepEqual over ${VALUES.length} x ${VALUES.length} pairs, each with its own copy`, async () => {
    const wrong: string[] = [];
    for (const [na, a] of VALUES) {
      for (const [nb, b] of VALUES) {
        for (const op of ['deepEqual', 'notDeepEqual'] as const) {
          const d = await differs(`${op}(${na}, ${nb})`, (x) => x[op](a(), b()));
          if (d) wrong.push(d);
        }
      }
    }
    assert.deepEqual(wrong, []);
  });

  it(`equal and notEqual over the same pairs, and the same value twice`, async () => {
    const wrong: string[] = [];
    for (const [na, a] of VALUES) {
      const same = a();
      for (const op of ['equal', 'notEqual'] as const) {
        const d = await differs(`${op}(${na}, itself)`, (x) => x[op](same, same));
        if (d) wrong.push(d);
      }
      for (const [nb, b] of VALUES) {
        for (const op of ['equal', 'notEqual'] as const) {
          const d = await differs(`${op}(${na}, ${nb})`, (x) => x[op](a(), b()));
          if (d) wrong.push(d);
        }
      }
    }
    assert.deepEqual(wrong, []);
  });

  it('ok, fail, match and doesNotMatch', async () => {
    const wrong: string[] = [];
    for (const [n, v] of VALUES) {
      const d = await differs(`ok(${n})`, (x) => x.ok(v()));
      if (d) wrong.push(d);
      for (const re of [/^1$/, /a/, /x/]) {
        for (const op of ['match', 'doesNotMatch'] as const) {
          const m = await differs(`${op}(${n}, ${re})`, (x) => x[op](v() as string, re));
          if (m) wrong.push(m);
        }
      }
    }
    for (const [what, call] of [
      ['ok()', (x: Both) => (x.ok as () => void)()],
      ['fail()', (x: Both) => x.fail()],
      ["fail('m')", (x: Both) => x.fail('m')],
      ['fail(new TypeError)', (x: Both) => x.fail(new TypeError('t'))],
      ["equal(1, 2, new RangeError)", (x: Both) => x.equal(1, 2, new RangeError('r'))],
      ["match('a', 'a')", (x: Both) => x.match('a', 'a' as unknown as RegExp)],
    ] as const) {
      const d = await differs(what, call);
      if (d) wrong.push(d);
    }
    assert.deepEqual(wrong, []);
  });

  it('throws and rejects, with every kind of expectation', async () => {
    class Custom extends Error {}
    const thrown: readonly (readonly [string, () => unknown])[] = [
      ['Error a', () => new Error('a')], ['TypeError b', () => new TypeError('b')], ['Custom c', () => new Custom('c')],
      ["'text'", () => 'text'], ['{code:1}', () => ({ code: 1, message: 'm' })], ['undefined', () => undefined],
    ];
    const expectations: readonly (readonly [string, unknown])[] = [
      ['none', undefined], ['/a/', /a/], ['/^Error: a$/', /^Error: a$/], ['/text/', /text/], ['Error', Error], ['TypeError', TypeError],
      ['Custom', Custom], ['RangeError', RangeError], ["{message:'a'}", { message: 'a' }], ['{message:/b/}', { message: /b/ }],
      ['{code:1}', { code: 1 }], ["{name:'TypeError'}", { name: 'TypeError' }], ['new Error(a)', new Error('a')],
      ['fn true', (e: unknown) => e instanceof TypeError], ['fn truthy', () => 1], ["'a message'", 'a message'],
    ];
    const wrong: string[] = [];
    for (const [nt, t] of thrown) {
      for (const [ne, e] of expectations) {
        const args = (ne === 'none' ? [] : [e]) as [];
        const thr = await differs(`throws(${nt}, ${ne})`, (x) => x.throws(() => { throw t(); }, ...args));
        if (thr) wrong.push(thr);
        const rej = await differs(`rejects(${nt}, ${ne})`, (x) => x.rejects(Promise.reject(t()), ...args));
        if (rej) wrong.push(rej);
        const fn = await differs(`rejects(() => ${nt}, ${ne})`, (x) => x.rejects(async () => { throw t(); }, ...args));
        if (fn) wrong.push(fn);
      }
    }
    for (const [what, call] of [
      ['throws(no throw)', (x: Both) => x.throws(() => 1)],
      ['throws(no throw, TypeError)', (x: Both) => x.throws(() => 1, TypeError)],
      ['rejects(resolved)', (x: Both) => x.rejects(Promise.resolve(1))],
      ['rejects(fn not returning a promise)', (x: Both) => x.rejects((() => 1) as unknown as () => Promise<unknown>)],
      ['throws(not a function)', (x: Both) => x.throws(1 as unknown as () => unknown)],
      ['throws(e, {})', (x: Both) => x.throws(() => { throw new Error('a'); }, {})],
    ] as const) {
      const d = await differs(what, call);
      if (d) wrong.push(d);
    }
    assert.deepEqual(wrong, []);
  });

  it('the default export asserts as ok does, and strict is itself', async () => {
    const ours = standIn.default;
    assert.equal(ours.strict, ours);
    assert.equal(await outcome(() => ours(true)), 'pass');
    assert.equal(await outcome(() => ours(0)), await outcome(() => nodeAssert.default(0)));
  });

  it('refuses, by name, what it does not stand in for', () => {
    assert.throws(() => standIn.doesNotThrow(), /not in the headless lane's node:assert stand-in/);
  });
});
