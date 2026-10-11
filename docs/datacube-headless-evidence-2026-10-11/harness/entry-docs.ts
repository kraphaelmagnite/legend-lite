// The real path Python would take: a grid as its page document holds it (JSON text), read back by DataCube's reader,
// opened over its source, and made a class's query by DataCube's builder -- every snapshot of the WASM differential.
import { CASES } from '../../datacube/test/wasm-differential/cases.ts';
import { cubeToJson, openCube, readCube, writeCube } from '../../datacube/src/cube-document.ts';
import { DEFAULT_CONFIGURATION } from '../../datacube/src/config.ts';
import { TreeState } from '../../datacube/src/tree.ts';
import { classLambda } from '../../datacube/src/query.ts';
import { toJson } from '../../pure-protocol/src/index.ts';

(globalThis as { buildAll?: () => string }).buildAll = (): string => JSON.stringify(CASES.map((c) => {
  const saved = cubeToJson(writeCube({
    name: c.name, snapshot: c.snapshot, configuration: DEFAULT_CONFIGURATION, tree: TreeState.empty(),
    source: { _type: 'frame', name: 'TRADES', columns: c.snapshot.columns.map((x) => ({ name: x.name, type: x.type })) },
  }));
  const opened = openCube(readCube(saved), c.snapshot.source, c.snapshot.columns);
  try {
    return { name: c.name, saved: saved.length, class: toJson(classLambda(opened.snapshot)) };
  } catch (e) {
    return { name: c.name, saved: saved.length, refused: e instanceof Error ? e.message : String(e) };
  }
}));
