// The reference: one test bundle in Node with the same stand-ins, legend-lite's pure/v1 from the WASM module (the tab's).
// node run-node.mjs <planner_dir> <bundle.mjs>
import { pathToFileURL } from 'node:url';
const [dir, bundle, engineModules] = process.argv.slice(2);
const runtime = await import(pathToFileURL(`${dir}/wasm-gc-module-runtime.js`).href);
const m = await runtime.load(`${dir}/classes.wasm`, {
  stackDeobfuscator: { enabled: false },
  installImports(i) { i.teavmConsole = { putcharStdout() {}, putcharStderr() {} }; },
});
globalThis.__pureV1 = (p, q, b) => m.exports.pureV1OrError(p, q, b);
globalThis.__loadRuntime = async () => ({ load: async () => m });
globalThis.__hostRunfiles = true;
if (engineModules) {
  const { createRequire } = await import('node:module');
  const { dirname } = await import('node:path');
  const req = createRequire(`${engineModules}/`);
  globalThis.__duckdbModule = req('@duckdb/duckdb-wasm/blocking');
  globalThis.__duckdbDist = dirname(req.resolve('@duckdb/duckdb-wasm/blocking'));
}
let out;
try {
  await import(pathToFileURL(bundle).href);
  out = await globalThis.__runTests();
} catch (e) {
  out = JSON.stringify([{ name: '<load>', status: 'fail', error: `${e?.name}: ${String(e?.message).split('\n')[0].slice(0, 300)}` }]);
}
process.stdout.write(out);
