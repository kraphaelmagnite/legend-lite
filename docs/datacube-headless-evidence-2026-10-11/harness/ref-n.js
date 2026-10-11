// Reference run with a count: node ref-n.js <bundle> <out> <n>
const fs = require('fs');
new Function(fs.readFileSync(process.argv[2], 'utf8'))();
const t = process.hrtime.bigint();
const out = globalThis.buildAll(Number(process.argv[4]));
const ms = Number(process.hrtime.bigint() - t) / 1e6;
fs.writeFileSync(process.argv[3], out);
const all = JSON.parse(out);
const refused = all.filter((x) => x.startsWith('refused')).length;
console.log(`node: ${all.length} builds in ${ms.toFixed(0)} ms: ${all.length - refused - all.filter((x) => x === 'none').length} queries, ${refused} refusals, ${all.filter((x) => x === 'none').length} none; ${(out.length / 1e6).toFixed(1)} MB`);
