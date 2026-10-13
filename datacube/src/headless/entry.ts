// The headless bundle's entry: what Python's host loads into V8 (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md
// §3), built as one script whose exports are the global `legendLiteHeadless`. Its first two imports put the web layer
// in place before any module that needs it as it loads.

import './install-web.ts';
import './install-url.ts';

import { REQUEST_KINDS, type PlannerWorker } from '../planner-protocol.ts';
import { hostCalls, hostDatabase, hostWorker, type HostDatabase } from './host.ts';
import { gridRelation, gridView } from './grid.ts';
import { forget, ingest, mount } from './data-in.ts';

/** The request kinds this bundle's planner sends: the host checks, before anything runs, that it answers each (§7). */
export { REQUEST_KINDS };

/** A grid, headless (§4): its view, and its rows as a relation. */
export { gridRelation, gridView };

/** Data in (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §4.3): a file read into the host's database by upload.ts, its
 *  table dropped again, and a remote file mounted as a view. */
export { forget, ingest, mount };

/** What DataCube's code runs on in this host: its planner's worker, and the database. */
export interface Headless {
  readonly worker: () => PlannerWorker;
  readonly database: HostDatabase;
}

/** The planner's worker and the database, over the host's calls (`legendLiteHost`, defined before the bundle loads). */
export function start(): Headless {
  const calls = hostCalls();
  return { worker: () => hostWorker(calls), database: hostDatabase(calls) };
}
