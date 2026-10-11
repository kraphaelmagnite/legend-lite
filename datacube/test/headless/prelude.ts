// The start of every test bundle the headless lane runs (esbuild --inject): the web layer in place before anything
// loads, and what Python's host looks for -- the planner's request kinds and `start`, as the product bundle exports
// them (src/headless/entry.ts), and the node:test stand-in's `run`.

import '../../src/headless/install-web.ts';
import '../../src/headless/install-url.ts';

import { REQUEST_KINDS, start } from '../../src/headless/entry.ts';
import { run } from './node-test.ts';

(globalThis as { legendLiteHeadless?: unknown }).legendLiteHeadless = { REQUEST_KINDS, start, run };
