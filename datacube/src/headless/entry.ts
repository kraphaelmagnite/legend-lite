// The headless bundle's entry: what Python's host loads into V8 (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md
// §3), built as one script whose exports are the global `legendLiteHeadless`. Its first two imports put the web layer
// in place before any module that needs it as it loads.

import './install-web.ts';
import './install-url.ts';

import { REQUEST_KINDS, type PlannerWorker } from '../planner-protocol.ts';
import { hostCalls, hostDatabase, hostWorker, type HostDatabase } from './host.ts';
import { gridRelation, gridSql, gridView } from './grid.ts';

/** The request kinds this bundle's planner sends: the host checks, before anything runs, that it answers each (§7). */
export { REQUEST_KINDS };

/** A grid, headless (§4): its view, its rows as a relation, and that relation's SQL. */
export { gridRelation, gridSql, gridView };

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
