// Planners for tests: legend-lite's own planner, through the test's host (#test-host) -- in Node the module the tab
// loads, in the headless lane the native library behind the host's worker.

import { plannerOptions } from '#test-host';
import { LitePlanner } from '../src/lite-planner.ts';

/** A planner over a model, from the test's host. */
export function plannerFor(model: string, runtime: string, how: { readonly mapping?: string } = {}): LitePlanner {
  return new LitePlanner({ model, runtime, ...how, ...plannerOptions(), cache: false });
}
