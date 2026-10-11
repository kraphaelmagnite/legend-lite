// A MEASUREMENT (scratch): where the tab's 3,937 ms compile of a 200-class page goes -- the boot layer, or the page's
// own model. model-scale.measure.ts's own setup and model shapes (classModel copied verbatim), over the same WASM
// module (//wasm:planner_dir) under Node, plus: a COLD module's warmUp of an empty model (the boot alone), and a warm
// module's hold of an empty model (what a hold costs with nothing in it). Prints JSON lines.
import { WasmPlanner } from '../../../datacube/src/wasm-planner.ts';
import { Session } from 'node:inspector/promises';
import { writeFileSync } from 'node:fs';

const RELATION = 'meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1]';
const store = (i: number): string =>
  `Database files::t${i}::Store ( Table t${i} ( id INTEGER PRIMARY KEY, k INTEGER, v DOUBLE, s VARCHAR(16) ) )`;
function classModel(n: number): string {
  const range = [...Array(n).keys()];
  const links = range.slice(0, -1);
  return [
    '###Relational', ...range.map(store),
    '###Pure',
    ...range.map((i) => `function files::t${i}::read(): ${RELATION} { #>{files::t${i}::Store.t${i}}# }`),
    ...range.map((i) => `Class files::t${i}::T${i} { id: Integer[1]; k: Integer[0..1]; v: Float[0..1]; s: String[0..1]; }`),
    ...links.map((i) => `Association page::Link${i} { next: files::t${i + 1}::T${i + 1}[0..1]; prev: files::t${i}::T${i}[*]; }`),
    '###Mapping',
    'import meta::pure::metamodel::relation::*;',
    'import meta::pure::metamodel::type::*;',
    ...range.map((i) => `Mapping files::t${i}::Mapping ( *files::t${i}::T${i}[t${i}]: Relation { ~func files::t${i}::read():Relation<Any>[1]`
      + ' id: id, k: k, v: v, s: s } )'),
    'Mapping page::Mapping (',
    ...range.map((i) => `  include files::t${i}::Mapping`),
    ...links.map((i) => `  page::Link${i}: ModelJoin { {next: files::t${i + 1}::T${i + 1}[1], prev: files::t${i}::T${i}[1] | $next.id == $prev.k} }`),
    ')',
    '###Connection',
    'RelationalDatabaseConnection page::Connection { store: files::t0::Store; type: DuckDB; specification: DuckDB { }; auth: Test; }',
    '###Runtime',
    `Runtime page::Runtime { mappings: [ page::Mapping ]; connections: [ ${range.map((i) => `files::t${i}::Store: [ c${i}: page::Connection ]`).join(', ')} ]; }`,
  ].join('\n');
}
const median = (xs: number[]): number => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]!;

async function compiled(base: WasmPlanner, model: string, runs: number): Promise<number[]> {
  const times: number[] = [];
  for (let run = 0; run < runs; run += 1) {
    const p = base.withModel(`${model}\n// compile ${run}\n`, 'page::Runtime', { mapping: 'page::Mapping' });
    const start = performance.now();
    await p.warmUp();
    times.push(performance.now() - start);
    p.dispose();
  }
  return times;
}


const assets = process.argv[2]!;
const base = new WasmPlanner({ model: classModel(2), runtime: 'page::Runtime', mapping: 'page::Mapping', assetBaseUrl: assets, cache: false });
await base.warmUp();
await compiled(base, classModel(200), 1);   // the code paths a 200-class hold takes, once, outside the profile
const session = new Session();
session.connect();
await session.post('Profiler.enable');
await session.post('Profiler.setSamplingInterval', { interval: 200 });
await session.post('Profiler.start');
const t = await compiled(base, classModel(200), 1);
const { profile } = await session.post('Profiler.stop');
writeFileSync(process.argv[3]!, JSON.stringify(profile));
console.log(JSON.stringify({ what: 'profiled hold of classModel(200)', ms: t.map(Math.round) }));
base.dispose();
