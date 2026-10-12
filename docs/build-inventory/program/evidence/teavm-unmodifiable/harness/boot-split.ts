// A MEASUREMENT (scratch): where the tab's 3,937 ms compile of a 200-class page goes -- the boot layer, or the page's
// own model. model-scale.measure.ts's own setup and model shapes (classModel copied verbatim), over the same WASM
// module (//wasm:planner_dir) under Node, plus: a COLD module's warmUp of an empty model (the boot alone), and a warm
// module's hold of an empty model (what a hold costs with nothing in it). Prints JSON lines.
import { WasmPlanner } from '../../../datacube/src/wasm-planner.ts';

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
const out = (o: object): void => console.log(JSON.stringify(o));

// 1. the boot alone: a cold module (its own transport), an empty model held
{
  const cold = new WasmPlanner({ model: '', runtime: 'page::Runtime', assetBaseUrl: assets, cache: false });
  const start = performance.now();
  await cold.warmUp();
  out({ what: 'cold module: load + boot + empty model held', ms: Math.round(performance.now() - start) });
  const again = await compiled(cold, '', 5);
  out({ what: 'same module, empty model held again', ms: again.map(Math.round), median: Math.round(median(again)) });
  cold.dispose();
}
// 2. model-scale.measure.ts's setup: a module warmed with classModel(2) and one class plan
const base = new WasmPlanner({ model: classModel(2), runtime: 'page::Runtime', mapping: 'page::Mapping', assetBaseUrl: assets, cache: false });
{
  const start = performance.now();
  await base.warmUp();
  out({ what: 'cold module: load + boot + classModel(2) held', ms: Math.round(performance.now() - start) });
  const l = await base.parse('|files::t0::T0.all()->filter(x|$x.id > 1)->project(~[id: x|$x.id, next: x|$x.next.v])');
  await base.plan(l);
}
for (const [name, model] of [['empty', ''], ['classModel(1)', classModel(1)], ['classModel(10)', classModel(10)],
  ['classModel(50)', classModel(50)], ['classModel(200)', classModel(200)], ['empty, after 200', '']] as const) {
  const t = await compiled(base, model, 3);
  out({ what: `warm module: ${name} held`, kb: Math.round(model.length / 1024), ms: t.map(Math.round), median: Math.round(median(t)) });
}
base.dispose();
