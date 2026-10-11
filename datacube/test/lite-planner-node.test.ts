// lite-planner.test.ts's file-URL conversions, held to Node's own fileURLToPath and pathToFileURL. The others are in
// lite-planner.test.ts, which runs in the headless lane too (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

import {
  fileUrlToPath,
  pathToFileUrl,
} from '../src/lite-planner.ts';

describe('fileUrlToPath', () => {
  // Node's own fileURLToPath is the reference; LitePlanner cannot import
  // it (it is bundled for the browser too). Both platform modes are
  // checked on every platform, so a Windows-only fault fails on a Mac.
  const VALID = [
    'file:///C:/users/runneradmin/_bazel/x/wasm/planner/classes.wasm',
    'file:///c:/lower/drive.wasm',
    'file:///D:/a%20space/%E6%97%A5%E6%9C%AC/classes.wasm',
    'file://server/share/planner/classes.wasm',
    'file:///tmp/wasm/planner/classes.wasm',
    'file:///Users/me/a%20b/classes.wasm',
  ];

  for (const windows of [true, false]) {
    for (const url of VALID) {
      it(`matches node's fileURLToPath (windows: ${windows}) for ${url}`, () => {
        let expected: string | Error;
        try {
          expected = fileURLToPath(url, { windows });
        } catch (e) {
          expected = e as Error;
        }
        if (expected instanceof Error) {
          assert.throws(() => fileUrlToPath(url, windows));
        } else {
          assert.equal(fileUrlToPath(url, windows), expected);
        }
      });
    }
  }
});

describe('pathToFileUrl', () => {
  // Node's own pathToFileURL is the reference, in both platform modes
  // on every platform -- the working directory is turned into the base
  // every WASM asset resolves against, and `file://${cwd}` was wrong
  // on Windows.
  const CASES: [string, boolean][] = [
    ['C:\\Users\\runneradmin\\work\\', true],
    ['C:\\a space\\\u65e5\u672c\\x.wasm', true],
    ['d:\\lower\\', true],
    ['\\\\server\\share\\planner\\', true],
    ['/Users/me/a b/', false],
    ['/home/runner/work/x#y?z/', false],
    ['/odd/100%@$=+,;\\name/', false],
    ['C:\\odd\\100%@$=+,;\\', true],
  ];
  for (const [path, windows] of CASES) {
    it(`matches node's pathToFileURL (windows: ${windows}) for ${path}`, () => {
      assert.equal(pathToFileUrl(path, windows), pathToFileURL(path, { windows }).href);
    });
  }
});
