// The planner's worker protocol: what `LitePlanner` sends the worker holding legend-lite's module, and what it gets
// back. The one contract between the planner and every host that answers it: planner-worker.ts in a tab (the
// WebAssembly module's exports), and Python's headless host (the native library's functions). Each kind is answered by
// the same `Boundary` function in both (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §3), so a kind added here
// fails both hosts at once: planner-worker.ts's switch by the type checker, the Python host by its lane's test.

/** Every request kind, in one list: the start-up check compares it with what a host answers (the design's §7). */
export const REQUEST_KINDS = [
  'hold',
  'release',
  'plan',
  'relationType',
  'tableModel',
  'catalogColumnsSql',
  'planJson',
  'pureV1',
  'testData',
  'sessionSetup',
] as const;

export type RequestKind = (typeof REQUEST_KINDS)[number];

/** A request, as the planner writes it. */
export type RequestBody =
  // a model held: compiled the first time its text is, the answer its handle
  | { readonly kind: 'hold'; readonly model: string }
  | { readonly kind: 'release'; readonly handle: number }
  // a query's Pure text planned against a model held
  | { readonly kind: 'plan'; readonly handle: number; readonly query: string; readonly runtime: string }
  | { readonly kind: 'relationType'; readonly handle: number; readonly query: string }
  | { readonly kind: 'tableModel'; readonly table: string }
  | { readonly kind: 'catalogColumnsSql'; readonly schema: string; readonly table: string }
  | { readonly kind: 'planJson'; readonly handle: number; readonly lambda: string; readonly runtime: string }
  // one pure/v1 call: its path (`/api/pure/v1/...`), its raw query string ('' for none) and its body
  | { readonly kind: 'pureV1'; readonly path: string; readonly query: string; readonly body: string }
  | { readonly kind: 'testData'; readonly model: string; readonly database: string; readonly tables: string }
  // what a session of a database runs before it is queried, so that it answers as the planner's SQL expects
  | { readonly kind: 'sessionSetup'; readonly databaseType: string };

/** A request as a worker receives it: its id, and where the tab's module lives (a host with its own ignores it). */
export type Request = RequestBody & { readonly id: number; readonly base?: string };

/** What it gets back. `answer` is the function's raw tagged string (`OK\n...` or `ERR\n<kind>\n<message>`). */
export type Response =
  | { readonly id: number; readonly ok: true; readonly answer: string }
  | { readonly id: number; readonly ok: false; readonly error: string };

/**
 * What a planner needs of a worker. The browser's `Worker` is one; a host that answers the same messages off the
 * planner's thread is another (`LitePlannerOptions.worker`).
 */
export interface PlannerWorker {
  postMessage(message: Request): void;
  onmessage: ((e: { readonly data: Response }) => void) | null;
  onerror: ((e: { readonly message: string }) => void) | null;
  terminate(): void;
}

// the list and the requests name the same kinds: a kind in one and not the other is a compile error here
type Same<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
const sameKinds: Same<RequestBody['kind'], RequestKind> = true;
void sameKinds;
