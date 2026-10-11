// A stand-in for node:assert/strict: the ten functions DataCube's tests use, with Node's strict semantics (deepEqual is
// deepStrictEqual: prototypes, Maps, Sets, Dates, RegExps, errors' message; equal is Object.is).

export class AssertionError extends Error {
  constructor(message) {
    super(message);
    this.name = 'AssertionError';
    this.code = 'ERR_ASSERTION';
  }
}

const show = (v) => {
  try {
    if (typeof v === 'string') return JSON.stringify(v.length > 200 ? `${v.slice(0, 200)}...` : v);
    if (typeof v === 'bigint') return `${v}n`;
    const s = JSON.stringify(v, (k, x) => (typeof x === 'bigint' ? `${x}n` : x));
    return s === undefined ? String(v) : s.length > 300 ? `${s.slice(0, 300)}...` : s;
  } catch {
    return String(v);
  }
};

const failWith = (message, fallback) => {
  if (message instanceof Error) throw message;
  throw new AssertionError(message ?? fallback);
};

function deep(a, b, seen) {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
  const pair = seen.get(a);
  if (pair === b) return true;
  seen.set(a, b);
  if (a instanceof Date) return Object.is(a.getTime(), b.getTime());
  if (a instanceof RegExp) return a.source === b.source && a.flags === b.flags && a.lastIndex === b.lastIndex;
  if (a instanceof Error && (a.message !== b.message || a.name !== b.name)) return false;
  if (ArrayBuffer.isView(a)) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!Object.is(a[i], b[i])) return false;
  }
  if (a instanceof Map) {
    if (a.size !== b.size) return false;
    for (const [k, v] of a) {
      if (!b.has(k) || !deep(v, b.get(k), seen)) return false;
    }
  }
  if (a instanceof Set) {
    if (a.size !== b.size) return false;
    outer: for (const x of a) {
      if (b.has(x)) continue;
      if (typeof x === 'object' && x !== null) {
        for (const y of b) if (deep(x, y, seen)) continue outer;
      }
      return false;
    }
  }
  if (Array.isArray(a) && a.length !== b.length) return false;
  const ka = Reflect.ownKeys(a).filter((k) => Object.prototype.propertyIsEnumerable.call(a, k));
  const kb = Reflect.ownKeys(b).filter((k) => Object.prototype.propertyIsEnumerable.call(b, k));
  if (ka.length !== kb.length) return false;
  for (const k of ka) {
    if (!Object.prototype.propertyIsEnumerable.call(b, k)) return false;
    if (!deep(a[k], b[k], seen)) return false;
  }
  return true;
}

export function ok(v, message) {
  if (!v) failWith(message, `The expression evaluated to a falsy value: ${show(v)}`);
}
export function equal(a, b, message) {
  if (!Object.is(a, b)) failWith(message, `Expected values to be strictly equal: ${show(a)} !== ${show(b)}`);
}
export function notEqual(a, b, message) {
  if (Object.is(a, b)) failWith(message, `Expected "actual" to be strictly unequal to: ${show(b)}`);
}
export function deepEqual(a, b, message) {
  if (!deep(a, b, new Map())) failWith(message, `Expected values to be strictly deep-equal: ${show(a)} vs ${show(b)}`);
}
export function notDeepEqual(a, b, message) {
  if (deep(a, b, new Map())) failWith(message, 'Expected "actual" not to be strictly deep-equal to "expected"');
}
export function match(s, re, message) {
  if (typeof s !== 'string' || !re.test(s)) failWith(message, `The input did not match ${re}: ${show(s)}`);
}
export function doesNotMatch(s, re, message) {
  if (typeof s !== 'string' || re.test(s)) failWith(message, `The input was expected not to match ${re}: ${show(s)}`);
}
export function fail(message) {
  failWith(message, 'Failed');
}

function matches(e, expected) {
  if (expected === undefined) return true;
  if (expected instanceof RegExp) return expected.test(String(e));
  if (typeof expected === 'function') {
    if (expected.prototype !== undefined && e instanceof expected) return true;
    if (Error.isPrototypeOf(expected) || expected === Error) return false;
    return expected.call({}, e) === true;
  }
  if (typeof expected === 'object' && expected !== null) {
    for (const k of Reflect.ownKeys(expected)) {
      const want = expected[k];
      const got = e?.[k];
      if (want instanceof RegExp && typeof got === 'string') {
        if (!want.test(got)) return false;
      } else if (!deep(got, want, new Map())) {
        return false;
      }
    }
    return true;
  }
  return false;
}

export function throws(fn, expected, message) {
  if (typeof expected === 'string') [message, expected] = [expected, undefined];
  let threw = false;
  let error;
  try {
    fn();
  } catch (e) {
    threw = true;
    error = e;
  }
  if (!threw) failWith(message, 'Missing expected exception.');
  if (!matches(error, expected)) {
    failWith(message, `The error did not match: ${error instanceof Error ? `${error.name}: ${error.message}` : show(error)}`);
  }
}

export async function rejects(p, expected, message) {
  if (typeof expected === 'string') [message, expected] = [expected, undefined];
  let threw = false;
  let error;
  try {
    await (typeof p === 'function' ? p() : p);
  } catch (e) {
    threw = true;
    error = e;
  }
  if (!threw) failWith(message, 'Missing expected rejection.');
  if (!matches(error, expected)) {
    failWith(message, `The rejection did not match: ${error instanceof Error ? `${error.name}: ${error.message}` : show(error)}`);
  }
}

export const strictEqual = equal;
export const deepStrictEqual = deepEqual;
export const notStrictEqual = notEqual;
export const notDeepStrictEqual = notDeepEqual;
const assert = Object.assign((v, m) => ok(v, m), {
  ok, equal, notEqual, deepEqual, notDeepEqual, match, doesNotMatch, fail, throws, rejects, AssertionError,
  strictEqual, deepStrictEqual, notStrictEqual, notDeepStrictEqual, strict: undefined,
});
assert.strict = assert;
export default assert;
export { assert as strict };
