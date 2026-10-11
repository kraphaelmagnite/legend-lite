// node:assert/strict where there is no Node: the headless lane's (//datacube:headless_test) stand-in, aliased for
// node:assert and node:assert/strict when DataCube's tests are bundled to run in V8. It JUDGES the tests, so it is held
// to Node's own assert case for case by headless-assert.test.ts (in Node): every function here has Node's strict
// semantics -- deepEqual is deepStrictEqual (prototypes, own enumerable keys and symbols, Maps, Sets, typed arrays,
// dates, regular expressions, errors), equal is Object.is -- and a call it does not know is refused, never waved by.

export class AssertionError extends Error {
  readonly code = 'ERR_ASSERTION';
  readonly generatedMessage: boolean;
  readonly operator: string;
  readonly actual: unknown;
  readonly expected: unknown;

  constructor(options: { message?: string | undefined; generated: string; operator: string; actual?: unknown; expected?: unknown }) {
    super(options.message ?? options.generated);
    this.name = 'AssertionError';
    this.generatedMessage = options.message === undefined;
    this.operator = options.operator;
    this.actual = options.actual;
    this.expected = options.expected;
  }
}

type Message = string | Error | undefined;

/** A failure: the caller's Error thrown as it is, else an AssertionError with the caller's words or the generated ones. */
function failure(message: Message, generated: string, operator: string, actual?: unknown, expected?: unknown): never {
  if (message instanceof Error) throw message;
  throw new AssertionError({ message, generated, operator, actual, expected });
}

function show(v: unknown): string {
  try {
    if (typeof v === 'string') return JSON.stringify(v.length > 200 ? `${v.slice(0, 200)}...` : v);
    if (typeof v === 'bigint') return `${v}n`;
    if (typeof v === 'symbol' || typeof v === 'function' || v === undefined) return String(v);
    const s = JSON.stringify(v, (_k, x: unknown) => (typeof x === 'bigint' ? `${x}n` : x));
    return s === undefined ? String(v) : s.length > 300 ? `${s.slice(0, 300)}...` : s;
  } catch {
    return Object.prototype.toString.call(v);
  }
}

// ---- deep strict equality, as Node's isDeepStrictEqual (lib/internal/util/comparisons.js, strict mode)

const tag = (v: object): string => Object.prototype.toString.call(v);

/** Own enumerable keys, strings then symbols. */
function ownKeys(o: object): (string | symbol)[] {
  return Reflect.ownKeys(o).filter((k) => Object.prototype.propertyIsEnumerable.call(o, k));
}

type Memo = Map<object, Set<object>>;

export function isDeepStrictEqual(a: unknown, b: unknown): boolean {
  return deep(a, b, new Map());
}

function deep(a: unknown, b: unknown, memo: Memo): boolean {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
  if (tag(a) !== tag(b)) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  // a cycle: a pair still being compared, met again inside itself, is taken as equal, as Node's memos take it -- and
  // only while it is: the pair leaves the memo when its comparison ends, so a pair that failed is never read as equal
  // later (a Set's or a Map's search tries a member against several)
  const seen = memo.get(a);
  if (seen?.has(b)) return true;
  if (seen) seen.add(b);
  else memo.set(a, new Set([b]));
  try {
    return deepObjects(a, b, memo);
  } finally {
    const still = memo.get(a);
    still?.delete(b);
    if (still?.size === 0) memo.delete(a);
  }
}

function deepObjects(a: object, b: object, memo: Memo): boolean {
  if (a instanceof Date) {
    // compared with !==, as Node compares them: two invalid dates (NaN) are not equal
    if (a.getTime() !== (b as Date).getTime()) return false;
  } else if (a instanceof RegExp) {
    const r = b as RegExp;
    if (a.source !== r.source || a.flags !== r.flags || a.lastIndex !== r.lastIndex) return false;
  } else if (a instanceof Error) {
    const e = b as Error;
    if (a.message !== e.message || a.name !== e.name) return false;
    if (('cause' in a) !== ('cause' in e) || ('cause' in a && !deep(a.cause, e.cause, memo))) return false;
    if (a instanceof AggregateError && !deep(a.errors, (e as AggregateError).errors, memo)) return false;
  } else if (ArrayBuffer.isView(a)) {
    const x = new Uint8Array(a.buffer, a.byteOffset, a.byteLength);
    const yv = b as ArrayBufferView;
    const y = new Uint8Array(yv.buffer, yv.byteOffset, yv.byteLength);
    if (x.length !== y.length) return false;
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
    // a typed array's own enumerable keys are its indices, compared above: only keys beyond them remain
    return sameKeys(a, b, memo, (k) => typeof k === 'string' && /^\d+$/.test(k));
  } else if (a instanceof ArrayBuffer || (typeof SharedArrayBuffer !== 'undefined' && a instanceof SharedArrayBuffer)) {
    const x = new Uint8Array(a as ArrayBuffer);
    const y = new Uint8Array(b as ArrayBuffer);
    if (x.length !== y.length) return false;
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
  } else if (a instanceof Map) {
    if (!sameMaps(a, b as Map<unknown, unknown>, memo)) return false;
  } else if (a instanceof Set) {
    if (!sameSets(a, b as Set<unknown>, memo)) return false;
  } else if (a instanceof Number || a instanceof String || a instanceof Boolean || a instanceof BigInt || a instanceof Symbol) {
    if (!Object.is(a.valueOf(), (b as { valueOf(): unknown }).valueOf())) return false;
  } else if (a instanceof WeakMap || a instanceof WeakSet || a instanceof Promise) {
    // nothing to see inside one: equal only to itself (Object.is, above)
    return false;
  }
  if (Array.isArray(a) && a.length !== (b as unknown[]).length) return false;
  return sameKeys(a, b, memo);
}

function sameKeys(a: object, b: object, memo: Memo, skip?: (k: string | symbol) => boolean): boolean {
  const ka = ownKeys(a).filter((k) => !skip?.(k));
  const kb = ownKeys(b).filter((k) => !skip?.(k));
  if (ka.length !== kb.length) return false;
  const inB = new Set(kb);
  for (const k of ka) {
    if (!inB.has(k)) return false;
    if (!deep((a as Record<string | symbol, unknown>)[k], (b as Record<string | symbol, unknown>)[k], memo)) return false;
  }
  return true;
}

const isObject = (v: unknown): v is object => (typeof v === 'object' && v !== null) || typeof v === 'function';

function sameSets(a: Set<unknown>, b: Set<unknown>, memo: Memo): boolean {
  if (a.size !== b.size) return false;
  // primitives by the set's own lookup; objects each matched to one deep-equal member of the other, one to one
  const unmatched: unknown[] = [];
  for (const x of a) {
    if (isObject(x)) {
      if (!b.has(x)) unmatched.push(x);
    } else if (!b.has(x)) {
      return false;
    }
  }
  if (unmatched.length === 0) return true;
  const candidates = [...b].filter((y) => isObject(y) && !a.has(y));
  for (const x of unmatched) {
    const at = candidates.findIndex((y) => deep(x, y, memo));
    if (at < 0) return false;
    candidates.splice(at, 1);
  }
  return true;
}

function sameMaps(a: Map<unknown, unknown>, b: Map<unknown, unknown>, memo: Memo): boolean {
  if (a.size !== b.size) return false;
  const unmatched: [unknown, unknown][] = [];
  for (const [k, v] of a) {
    if (isObject(k) && !b.has(k)) {
      unmatched.push([k, v]);
    } else if (!b.has(k) || !deep(v, b.get(k), memo)) {
      return false;
    }
  }
  if (unmatched.length === 0) return true;
  const candidates = [...b].filter(([k]) => isObject(k) && !a.has(k));
  for (const [k, v] of unmatched) {
    const at = candidates.findIndex(([k2, v2]) => deep(k, k2, memo) && deep(v, v2, memo));
    if (at < 0) return false;
    candidates.splice(at, 1);
  }
  return true;
}

// ---- the assertions DataCube's tests use

export function ok(...args: [value?: unknown, message?: Message]): void {
  if (args.length === 0) failure(undefined, 'No value argument passed to `assert.ok()`', '==', undefined, true);
  const [value, message] = args;
  if (!value) failure(message, `The expression evaluated to a falsy value:\n\n  assert.ok(${show(value)})\n`, '==', value, true);
}

export function equal(actual: unknown, expected: unknown, message?: Message): void {
  if (!Object.is(actual, expected)) {
    failure(message, `Expected values to be strictly equal:\n\n${show(actual)} !== ${show(expected)}\n`, 'strictEqual', actual, expected);
  }
}

export function notEqual(actual: unknown, expected: unknown, message?: Message): void {
  if (Object.is(actual, expected)) {
    failure(message, `Expected "actual" to be strictly unequal to: ${show(expected)}`, 'notStrictEqual', actual, expected);
  }
}

export function deepEqual(actual: unknown, expected: unknown, message?: Message): void {
  if (!isDeepStrictEqual(actual, expected)) {
    failure(message, `Expected values to be strictly deep-equal:\n${show(actual)}\n\nshould equal\n\n${show(expected)}`,
      'deepStrictEqual', actual, expected);
  }
}

export function notDeepEqual(actual: unknown, expected: unknown, message?: Message): void {
  if (isDeepStrictEqual(actual, expected)) {
    failure(message, `Expected "actual" not to be strictly deep-equal to: ${show(expected)}`, 'notDeepStrictEqual', actual, expected);
  }
}

function regExpOf(name: string, re: unknown): RegExp {
  if (!(re instanceof RegExp)) {
    throw new TypeError(`The "regexp" argument must be an instance of RegExp. Received ${show(re)}`);
  }
  void name;
  return re;
}

export function match(string: unknown, regexp: RegExp, message?: Message): void {
  const re = regExpOf('match', regexp);
  if (typeof string !== 'string') {
    failure(message, `The "string" argument must be of type string. Received type ${typeof string} (${show(string)})`, 'match', string, re);
  }
  if (re.exec(string) === null) failure(message, `The input did not match the regular expression ${re}. Input:\n\n${show(string)}\n`, 'match', string, re);
}

export function doesNotMatch(string: unknown, regexp: RegExp, message?: Message): void {
  const re = regExpOf('doesNotMatch', regexp);
  if (typeof string !== 'string') {
    failure(message, `The "string" argument must be of type string. Received type ${typeof string} (${show(string)})`, 'doesNotMatch', string, re);
  }
  if (re.exec(string) !== null) failure(message, `The input was expected to not match the regular expression ${re}. Input:\n\n${show(string)}\n`, 'doesNotMatch', string, re);
}

export function fail(message?: Message): never {
  return failure(message, 'Failed', 'fail');
}

type Expected = RegExp | (new (...a: never[]) => unknown) | ((e: unknown) => unknown) | object;

/** `actual` against what throws/rejects was told to expect, as Node's expectedException judges it. */
function expectedError(actual: unknown, expected: Expected, message: Message, operator: string): void {
  if (expected instanceof RegExp) {
    if (expected.exec(String(actual)) !== null) return;
    failure(message, `The input did not match the regular expression ${expected}. Input:\n\n${show(String(actual))}\n`, operator, actual, expected);
  }
  if (typeof expected === 'function') {
    if (expected.prototype !== undefined && actual instanceof (expected as new () => unknown)) return;
    if (Object.prototype.isPrototypeOf.call(Error, expected)) {
      failure(message, `The error is expected to be an instance of "${expected.name}". Received ${describeError(actual)}`, operator, actual, expected);
    }
    const res = Reflect.apply(expected as (e: unknown) => unknown, {}, [actual]);
    if (res !== true) {
      failure(message, `The ${expected.name ? `"${expected.name}" ` : ''}validation function is expected to return "true". Received ${show(res)}`,
        operator, actual, expected);
    }
    return;
  }
  if (typeof actual !== 'object' || actual === null) {
    failure(message, `The "actual" argument is expected to be an object. Received ${show(actual)}`, operator, actual, expected);
  }
  const keys: (string | symbol)[] = Object.keys(expected);
  if (expected instanceof Error) keys.push('name', 'message');
  else if (keys.length === 0) throw new TypeError('The argument \'error\' may not be an empty object. Received {}');
  for (const key of keys) {
    const got = (actual as Record<string | symbol, unknown>)[key];
    const want = (expected as Record<string | symbol, unknown>)[key];
    if (typeof got === 'string' && want instanceof RegExp && want.exec(got) !== null) continue;
    if (!(key in actual) || !isDeepStrictEqual(got, want)) {
      failure(message, `Expected values to be strictly deep-equal (the error's ${String(key)}): ${show(got)} should equal ${show(want)}`,
        operator, actual, expected);
    }
  }
}

function describeError(e: unknown): string {
  return e instanceof Error ? `"${e.name}": ${e.message}` : show(e);
}

export function throws(fn: () => unknown, expected?: Expected | string, message?: Message): void {
  if (typeof fn !== 'function') throw new TypeError(`The "fn" argument must be of type function. Received ${show(fn)}`);
  if (typeof expected === 'string') {
    message = expected;
    expected = undefined;
  }
  let error: unknown;
  let threw = false;
  try {
    fn();
  } catch (e) {
    threw = true;
    error = e;
  }
  if (!threw) {
    const named = typeof expected === 'function' && expected.name ? ` (${expected.name})` : '';
    failure(message, `Missing expected exception${named}${typeof message === 'string' ? `: ${message}` : '.'}`, 'throws', undefined, expected);
  }
  if (expected !== undefined) expectedError(error, expected, message, 'throws');
}

export async function rejects(promiseFn: Promise<unknown> | (() => Promise<unknown>), expected?: Expected | string, message?: Message): Promise<void> {
  if (typeof expected === 'string') {
    message = expected;
    expected = undefined;
  }
  let promise: unknown;
  if (typeof promiseFn === 'function') {
    promise = promiseFn();
    if (!isThenable(promise)) {
      throw new TypeError(`Expected instance of Promise to be returned from the "promiseFn" function but got ${show(promise)}.`);
    }
  } else if (isThenable(promiseFn)) {
    promise = promiseFn;
  } else {
    throw new TypeError(`The "promiseFn" argument must be of type function or an instance of Promise. Received ${show(promiseFn)}`);
  }
  let error: unknown;
  let rejected = false;
  try {
    await promise;
  } catch (e) {
    rejected = true;
    error = e;
  }
  if (!rejected) {
    const named = typeof expected === 'function' && expected.name ? ` (${expected.name})` : '';
    failure(message, `Missing expected rejection${named}${typeof message === 'string' ? `: ${message}` : '.'}`, 'rejects', undefined, expected);
  }
  if (expected !== undefined) expectedError(error, expected, message, 'rejects');
}

function isThenable(v: unknown): v is PromiseLike<unknown> {
  return v instanceof Promise
    || (isObject(v) && typeof (v as { then?: unknown }).then === 'function' && typeof (v as { catch?: unknown }).catch === 'function');
}

/** What node:assert/strict exports that these tests do not use: refused, so a test that needs one says so. */
function refused(name: string): () => never {
  return () => {
    throw new Error(`assert.${name} is not in the headless lane's node:assert stand-in (datacube/test/headless/node-assert.ts)`);
  };
}

export const strictEqual = equal;
export const notStrictEqual = notEqual;
export const deepStrictEqual = deepEqual;
export const notDeepStrictEqual = notDeepEqual;
export const doesNotThrow = refused('doesNotThrow');
export const doesNotReject = refused('doesNotReject');
export const ifError = refused('ifError');
export const partialDeepStrictEqual = refused('partialDeepStrictEqual');

type Assert = ((value: unknown, message?: Message) => void) & Record<string, unknown> & { strict: Assert };

const assert = Object.assign((...args: [value?: unknown, message?: Message]) => ok(...args), {
  ok, equal, notEqual, deepEqual, notDeepEqual, match, doesNotMatch, fail, throws, rejects, AssertionError,
  strictEqual, notStrictEqual, deepStrictEqual, notDeepStrictEqual, doesNotThrow, doesNotReject, ifError,
  partialDeepStrictEqual,
}) as unknown as Assert;
assert.strict = assert;

export default assert;
export { assert as strict };
