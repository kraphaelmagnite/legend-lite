// legend-lite's planner in this process, answering as the tab's worker does (WasmGrammar over the module's exports):
// what engine-client's tests ask for a project's database (C5), a host's operations (D5) and a snap's queries (D5b).

import { fileURLToPath } from 'node:url';

import { WasmGrammar } from '../src/legend/wasm-grammar.ts';
import { answer, type PlannerModule } from '../src/legend/planner-answer.ts';
import type { RequestBody } from '../src/legend/planner-protocol.ts';
import type { SnapPlanner } from '../src/snap.ts';
import { toJson } from '../../pure-protocol/src/index.ts';
import { runfileDirUrl } from '../../tools/js/runfiles.mts';

let loaded: Promise<PlannerModule> | undefined;

/** The module from WASM_PLANNER (node_test's `wasm`), in-thread, loaded once. */
function module(): Promise<PlannerModule> {
  loaded ??= (async () => {
    const dir = new URL(runfileDirUrl('WASM_PLANNER'));
    const runtime = await import(new URL('wasm-gc-module-runtime.js', dir).href) as {
      load(src: string, options: unknown): Promise<PlannerModule>;
    };
    return runtime.load(fileURLToPath(new URL('classes.wasm', dir)), {
      stackDeobfuscator: { enabled: false },
      installImports(i: Record<string, unknown>) {
        i.teavmConsole = { putcharStdout() {}, putcharStderr() {} };
      },
    });
  })();
  return loaded;
}

const ask = async (r: RequestBody): Promise<string> => answer(await module(), r);

/** A tagged answer's value, or its refusal thrown. */
function value(tagged: string): string {
  if (tagged.startsWith('OK\n')) return tagged.slice(3);
  throw new Error(tagged.split('\n').slice(2).join('\n') || tagged);
}

export async function loadPlanner(): Promise<WasmGrammar> {
  await module();
  return new WasmGrammar({ ask });
}

/** What plans a snap's queries over `model` against `runtime`: the tab's planner, as DataCube's LitePlanner asks. */
export async function snapPlannerFor(model: string, runtime: string): Promise<SnapPlanner> {
  const handle = Number(value(await ask({ kind: 'hold', model })));
  return {
    plan: async (query) => JSON.parse(value(await ask({ kind: 'planJson', handle, lambda: toJson(query), runtime }))) as { sql: string },
  };
}
