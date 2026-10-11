// Reference run: the bundle in Node (the repo's pinned toolchain), its output written and timed.
const fs = require('fs');
const t0 = process.hrtime.bigint();
new Function(fs.readFileSync(process.argv[2], 'utf8'))();
const t1 = process.hrtime.bigint();
const out = globalThis.buildAll();
const t2 = process.hrtime.bigint();
fs.writeFileSync(process.argv[3], out);
console.log(`node: load ${(Number(t1 - t0) / 1e6).toFixed(1)} ms, build all ${(Number(t2 - t1) / 1e6).toFixed(1)} ms, ${JSON.parse(out).length} queries, ${out.length} chars`);
