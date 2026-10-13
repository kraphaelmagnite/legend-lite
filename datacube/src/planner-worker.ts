// The planner module, on its own thread.
//
// Not an optimisation of last resort -- a correction. Building the
// boot layer (parsing the 300 KB Pure prelude, the system metamodel,
// then resolving and normalizing both) is ~600ms of SYNCHRONOUS work,
// and WebAssembly has no yield. On the main thread that is a 600ms
// freeze: DuckDB's own startup was measured slipping from 409ms to
// 1038ms purely because the planner was holding the thread, and
// starting the planner earlier made the page SLOWER, not faster.
// Two tasks do not overlap when one of them never yields.
//
// So the module lives here. The main thread stays free for DuckDB and
// for rendering, and the two startups genuinely run at once.
//
// Its messages are planner-protocol.ts's, the contract every host of the
// planner answers (Python's headless host too).
//
// This worker deliberately contains no planning logic of its own: it
// forwards to the module's `holdModelOrError`, `releaseModel`, `planHeldOrError`,
// `planJsonHeldOrError`, `relationTypeHeldOrError`, `tableModelOrError`,
// `catalogColumnsSqlOrError`, `pureV1OrError`, `testDataSqlOrError` and `sessionSetupOrError` exports and
// returns what they say. A second planner is the one thing this whole
// design exists to avoid. The models it holds (compiled once, planned
// against many times: docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §8) are
// the module's, so they live as long as this worker does.

import type { Request, Response } from './planner-protocol.ts';

interface TeavmModule {
  readonly exports: {
    holdModelOrError(model: string): string;
    releaseModel(handle: number): number;
    planHeldOrError(handle: number, query: string, runtime: string): string;
    planJsonHeldOrError(handle: number, lambdaJson: string, runtime: string): string;
    relationTypeHeldOrError(handle: number, query: string): string;
    tableModelOrError(table: string): string;
    catalogColumnsSqlOrError(schema: string, table: string): string;
    // legend-engine's pure/v1, routed as legend-lite's server routes it: `OK\n<status>\n<type>\n<body>`
    pureV1OrError(path: string, rawQuery: string, body: string): string;
    testDataSqlOrError(model: string, database: string, tablesJson: string): string;
    sessionSetupOrError(databaseType: string): string;
  };
}

let modulePromise: Promise<TeavmModule> | undefined;

async function load(base: string): Promise<TeavmModule> {
  const runtime = await import(
    /* @vite-ignore */ `${base}wasm-gc-module-runtime.js`
  ) as {
    load(src: string, options?: unknown): Promise<TeavmModule>;
  };
  return runtime.load(`${base}classes.wasm`, {
    stackDeobfuscator: { enabled: false },
    installImports(imports: Record<string, unknown>) {
      // Diagnostics arrive one character at a time; the messages that
      // matter come back through each export's return value.
      imports.teavmConsole = { putcharStdout() {}, putcharStderr() {} };
    },
  });
}

/** One request, answered by the module's export of the same name: no logic of its own. */
function answerOf(module: TeavmModule, msg: Request): string {
  switch (msg.kind) {
    case 'hold': return module.exports.holdModelOrError(msg.model);
    case 'release': module.exports.releaseModel(msg.handle); return 'OK\n';
    case 'relationType': return module.exports.relationTypeHeldOrError(msg.handle, msg.query);
    case 'tableModel': return module.exports.tableModelOrError(msg.table);
    case 'catalogColumnsSql': return module.exports.catalogColumnsSqlOrError(msg.schema, msg.table);
    case 'planJson': return module.exports.planJsonHeldOrError(msg.handle, msg.lambda, msg.runtime);
    case 'pureV1': return module.exports.pureV1OrError(msg.path, msg.query, msg.body);
    case 'testData': return module.exports.testDataSqlOrError(msg.model, msg.database, msg.tables);
    case 'plan': return module.exports.planHeldOrError(msg.handle, msg.query, msg.runtime);
    case 'sessionSetup': return module.exports.sessionSetupOrError(msg.databaseType);
  }
}

self.onmessage = async (e: MessageEvent<Request>) => {
  const msg = e.data;
  try {
    if (!modulePromise) {
      const base = msg.base ?? './vendor/';
      modulePromise = load(base);
      modulePromise.catch(() => { modulePromise = undefined; });
    }
    const module = await modulePromise;
    const answer = answerOf(module, msg);
    const ok: Response = { id: msg.id, ok: true, answer };
    self.postMessage(ok);
  } catch (cause) {
    const bad: Response = {
      id: msg.id,
      ok: false,
      error: cause instanceof Error ? cause.message : String(cause),
    };
    self.postMessage(bad);
  }
};
