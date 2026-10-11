// writes boot-split.ts's model texts (the same function) to files, so the native library holds byte-identical texts
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
for (const n of [1, 10, 50, 200]) writeFileSync(`${process.argv[2]}/classModel-${n}.pure`, classModel(n));
