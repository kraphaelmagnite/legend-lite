// The measurement's one entry: every cube query the WASM differential plans, built by DataCube's own builder
// (src/query.ts, through test/wasm-differential/cases.ts), as the protocol's JSON -- run in each engine, compared.
import { queries } from '../../datacube/test/wasm-differential/cases.ts';
import { toJson } from '../../pure-protocol/src/index.ts';

(globalThis as { buildAll?: () => string }).buildAll = (): string =>
  JSON.stringify(queries().map((q) => ({ name: q.name, json: toJson(q.query) })));
