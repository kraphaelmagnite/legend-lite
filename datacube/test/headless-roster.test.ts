// THE HEADLESS LANE'S ROSTER CANNOT DRIFT (the Compiler Rewrite line's review, 2026-10-11): BUILD.bazel's
// _HEADLESS_TESTS is a list kept by hand, and a non-screen test file nobody adds would be silently out of
// //datacube:headless_test. So every test file is exactly one of: in _HEADLESS_TESTS; in _HEADLESS_LEFT_OUT, with its
// reason; or a screen or Node-only test -- it, or a test helper it imports, imports jsdom, a Node module beside
// node:test and node:assert, or the runfiles reader (which reads the file system). A lane file reaching any of those
// is refused too: it would not run in V8.

import assert from 'node:assert/strict';
import { posix } from 'node:path';
import { test } from 'node:test';

import { Sources } from '../../tools/js/runfiles.mts';

const SOURCES = new Sources('SOURCES', 'datacube');

const NODE_TEST = new Set(['node:test', 'node:assert', 'node:assert/strict']);

/** A file's imports, as written. */
function importsOf(text: string): string[] {
  return [...text.matchAll(/^\s*(?:import|export)\s[^;]*?from\s+'([^']+)'/gms), ...text.matchAll(/^\s*import\s+'([^']+)'/gm)]
    .map((m) => m[1]!);
}

/** Why a test file (or a helper it imports, within test/) needs Node or a screen: the first reason found, or null. */
function nodeOnly(file: string, seen = new Set<string>()): string | null {
  if (seen.has(file) || !SOURCES.has(file)) return null;
  seen.add(file);
  for (const spec of importsOf(SOURCES.read(file))) {
    if (spec === 'jsdom') return `${file} imports jsdom`;
    if (spec.startsWith('node:') && !NODE_TEST.has(spec)) return `${file} imports ${spec}`;
    if (spec.endsWith('/runfiles.mts')) return `${file} reads files through the runfiles reader`;
    if (spec.startsWith('.')) {
      // a package-relative path, '/'-separated on every platform as the scanner names files
      const target = posix.normalize(posix.join(posix.dirname(file), spec));
      // the test's own helpers are followed; DataCube's sources bundle with --platform=neutral, which refuses Node
      if (target.startsWith('test/')) {
        const why = nodeOnly(target, seen);
        if (why) return why;
      }
    }
  }
  return null;
}

function listed(build: string, name: string, pattern: RegExp): string[] {
  const body = new RegExp(`${name} = [\\[{]([^\\]}]*)[\\]}]`).exec(build)?.[1];
  assert.ok(body !== undefined, `BUILD.bazel has no ${name}`);
  return [...body.matchAll(pattern)].map((m) => m[1]!).sort();
}

test('every test file is in the headless lane, left out by name with a reason, or needs Node or a screen', () => {
  const build = SOURCES.read('BUILD.bazel');
  const lane = new Set(listed(build, '_HEADLESS_TESTS', /^\s*"([^"]+)",/gm));
  const leftOut = new Map(listed(build, '_HEADLESS_LEFT_OUT', /^\s*"([^"]+)": "[^"]+",/gm).map((n) => [n, true]));
  const files = SOURCES.under('test', '.test.ts').filter((f) => !f.slice('test/'.length).includes('/'));
  assert.ok(files.length > 50 && lane.size > 40, 'the scan is not looking');
  const wrong: string[] = [];
  for (const f of files) {
    const name = f.slice('test/'.length, -'.test.ts'.length);
    const why = nodeOnly(f);
    const places = [lane.has(name) && 'the lane', leftOut.has(name) && '_HEADLESS_LEFT_OUT', why && 'Node-only'].filter(Boolean);
    if (lane.has(name) && why) wrong.push(`${name}: in the lane, but ${why}`);
    else if (places.length === 0) wrong.push(`${name}: in no list and needs nothing of Node: add it to _HEADLESS_TESTS`);
    else if (lane.has(name) && leftOut.has(name)) wrong.push(`${name}: both in the lane and left out`);
  }
  for (const name of [...lane, ...leftOut.keys()]) {
    if (!files.includes(`test/${name}.test.ts`)) wrong.push(`${name}: listed, but no test/${name}.test.ts`);
  }
  assert.deepEqual(wrong, []);
});
