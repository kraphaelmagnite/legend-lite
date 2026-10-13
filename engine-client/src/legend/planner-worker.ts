// legend-lite's planner (WebAssembly) on its own thread: THE worker every app that plans in a tab runs (DataCube,
// Query, Studio; docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C2). Each request of the planner protocol (planner-protocol.ts)
// is answered by the module's export of the same name, with no logic of its own (planner-answer.ts).
//
// Not an optimisation of last resort -- a correction. Building the boot layer (parsing the Pure prelude and the system
// metamodel, then resolving and normalizing both) is long SYNCHRONOUS work, and WebAssembly has no yield: on the main
// thread it froze the page, and DuckDB's own startup slipped behind it. So the module lives here, and the two startups
// run at once. The models it holds (compiled once, planned against many times) are the module's, so they live as long
// as this worker does.

import { answer, type PlannerModule } from './planner-answer.ts';
import type { Request, Response } from './planner-protocol.ts';

let modulePromise: Promise<PlannerModule> | undefined;

async function load(base: string): Promise<PlannerModule> {
  const runtime = await import(/* @vite-ignore */ `${base}wasm-gc-module-runtime.js`) as {
    load(src: string, options?: unknown): Promise<PlannerModule>;
  };
  return runtime.load(`${base}classes.wasm`, {
    stackDeobfuscator: { enabled: false },
    installImports(imports: Record<string, unknown>) {
      // diagnostics arrive one character at a time; the messages that matter come back through each export's answer
      imports.teavmConsole = { putcharStdout() {}, putcharStderr() {} };
    },
  });
}

const scope = globalThis as unknown as {
  onmessage: ((e: MessageEvent<Request>) => void) | null;
  postMessage(m: Response): void;
};

scope.onmessage = async (e: MessageEvent<Request>) => {
  const msg = e.data;
  try {
    if (!modulePromise) {
      modulePromise = load(msg.base ?? './vendor/');
      modulePromise.catch(() => { modulePromise = undefined; });
    }
    scope.postMessage({ id: msg.id, ok: true, answer: answer(await modulePromise, msg) });
  } catch (cause) {
    scope.postMessage({ id: msg.id, ok: false, error: cause instanceof Error ? cause.message : String(cause) });
  }
};
