// B5's measured number (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md, B5's Built note): one call of the page's saved-query
// module over a store of N queries with V versions each, as query-store/src/wasm-server.ts makes it (every record's
// text handed in, the answer parsed). IndexedDB's own read is not in it.
//
// Run, after `bazel build //query-store:page_dir`, with Bazel's node (22.16):
//   node --experimental-wasm-exnref docs/one-platform/evidence/b5-page-call.mjs "$PWD/bazel-bin/query-store/page_dir" 1000 5
// Measured 2026-10-13 (Apple silicon laptop), at the B5 fix-up:
//   1000 queries x 5 versions, 3.2 MB of records
//   search by name: median 55.6 ms (min 52.2, max 65.6)
//   get one (writes lastOpenAt): median 50.0 ms (min 48.5, max 57.7)
const dir = process.argv[2];
const N = Number(process.argv[3] ?? 1000), V = Number(process.argv[4] ?? 5);
const rt = await import(dir + '/wasm-gc-module-runtime.js');
const m = await rt.load(dir + '/classes.wasm', { stackDeobfuscator: { enabled: false }, installImports(i) { i.teavmConsole = { putcharStdout() {}, putcharStderr() {} }; } });
const version = (id, v) => ({
  id, name: `Query ${id}`, description: null, groupId: 'demo', artifactId: 'trading', versionId: '0.0.0', originalVersionId: null,
  executionContext: { _type: 'explicitExecutionContext', mapping: 'demo::trading::TradingMapping', runtime: 'demo::trading::Runtime' },
  content: '|demo::trading::Trade.all()->filter(x|$x.quantity > 100)->project(~[id: x|$x.tradeId, side: x|$x.side, qty: x|$x.quantity])',
  lastUpdatedAt: 1000 + v, createdAt: 1000, lastOpenAt: 1000 + v, deletedAt: null, validUntil: v === V ? null : 1000 + v + 1,
  version: v, taggedValues: [], stereotypes: [], defaultParameterValues: [], owner: 'me', gridConfig: { width: 1.0, columns: ['id', 'side', 'qty'] },
});
const held = new Map();
for (let i = 0; i < N; i++) {
  const id = `q${String(i).padStart(5, '0')}`;
  held.set(id, JSON.stringify(Array.from({ length: V }, (_, k) => version(id, k + 1))));
}
const recordsJson = () => `{${[...held].map(([id, text]) => `${JSON.stringify(id)}:${text}`).join(',')}}`;
const call = (method, rest, body) => JSON.parse(m.exports.answer(method, rest, '', body, 'me', recordsJson()));
const time = (label, f, runs = 10) => {
  f(); f(); // warm
  const t = [];
  for (let i = 0; i < runs; i++) { const s = performance.now(); f(); t.push(performance.now() - s); }
  t.sort((a, b) => a - b);
  console.log(`${label}: median ${t[Math.floor(runs / 2)].toFixed(1)} ms (min ${t[0].toFixed(1)}, max ${t[runs - 1].toFixed(1)})`);
};
console.log(`${N} queries x ${V} versions, ${(recordsJson().length / 1e6).toFixed(1)} MB of records`);
time('search by name', () => call('POST', '/search', JSON.stringify({ searchTermSpecification: { searchTerm: 'query q00042' } })));
time('get one (writes lastOpenAt)', () => call('GET', '/q00042', ''));
