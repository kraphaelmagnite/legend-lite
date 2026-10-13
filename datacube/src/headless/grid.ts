// A GRID, HEADLESS (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §4): a page's cube over its source, opened and
// run by DataCube's own code, with no screen -- openCube over the source as the compiler types it now (as a page opens
// a saved cube), its configuration folded into its query as the tab's state owner folds it (cube-state.ts `fold`: its
// row cap, each column's aggregate and kind, pivot totals and sorts), CubeController.run for its view (its levels,
// totals, pivot and truncation) laid out as the screen lays it out (shownColumns: hidden columns out, its order), and
// classLambda for its rows at their finest level (Create a Class's relation), which Python plans and runs itself for
// all of them (Grid.to_pandas, typed by its plan: docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §15.3a). Its planner is
// legend-lite's over the host's worker, its engine DuckDB over the host's connection: the tab's own pair, in Python.
//
// Each call opens the grid afresh from the cube document it is given: the document is the grid's state, Python's
// page keeps it, and nothing here holds a grid between calls.

import { DuckDbEngine } from '../../../engine-client/src/duckdb.ts';
import type { Scalar } from '../../../engine-client/src/result.ts';
import { fromJson, type ValueSpecification } from '../../../pure-protocol/src/index.ts';
import { CubeController, type CubeView } from '../cube.ts';
import { fold, type CubeState } from '../cube-state.ts';
import { openCube, readCube, type OpenedCube } from '../cube-document.ts';
import { STALE } from '../epoch.ts';
import { LitePlanner } from '../lite-planner.ts';
import { classLambda } from '../query.ts';
import { shownColumns } from '../shown-columns.ts';
import { sourceColumns } from '../source-columns.ts';
import { hostCalls, hostDatabase, hostWorker } from './host.ts';

/** A grid as Python hands it in: its cube document (page-document's, as JSON text), and its source's model. */
export interface GridSpec {
  /** The cube document, as JSON text: read as the protocol's exact JSON (a `12.30` stays `12.30`). */
  readonly cube: string;
  /** The model its source is declared in, and the runtime its queries run on. */
  readonly model: string;
  readonly runtime: string;
  /** Its source relation, as Pure text: `#>{frames::DB.trades}#`. */
  readonly source: string;
}

interface Opened {
  readonly planner: LitePlanner;
  readonly cube: OpenedCube;
  /** Its state as the query sees it: the configuration folded into the snapshot. */
  readonly state: CubeState;
}

/** The grid opened: its planner over the host's worker, its cube over the source as the compiler types it now. */
async function opened(spec: GridSpec): Promise<Opened> {
  const calls = hostCalls();
  const planner = new LitePlanner({ model: spec.model, runtime: spec.runtime, worker: () => hostWorker(calls) });
  try {
    const relation = (await planner.parse(`|${spec.source}`)).body[0] as ValueSpecification | undefined;
    if (relation === undefined) throw new Error(`the source is not a relation: ${spec.source}`);
    const columns = await sourceColumns(planner, relation);
    const cube = openCube(readCube(fromJson(spec.cube)), { query: relation }, columns);
    return { planner, cube, state: fold({ snapshot: cube.snapshot, configuration: cube.configuration, tree: cube.tree }) };
  } catch (e) {
    planner.dispose();
    throw e;
  }
}

async function using<T>(spec: GridSpec, use: (o: Opened) => Promise<T>): Promise<T> {
  const o = await opened(spec);
  try {
    return await use(o);
  } finally {
    o.planner.dispose();
  }
}

/** A cell as JSON Python reads exactly: an integer past 2^53 and a float JSON cannot write are tagged. */
function cell(v: Scalar): unknown {
  if (typeof v === 'bigint') return { $int: v.toString() };
  if (typeof v === 'number' && (!Number.isFinite(v) || Object.is(v, -0))) return { $float: Object.is(v, -0) ? '-0.0' : String(v) };
  return v;
}

/** The view as the grid shows it: its columns (the leaves the screen shows, in its order), its rows, their tree, what
 *  was cut. */
function viewJson(view: CubeView, configuration: CubeState['configuration'], query: string): string {
  const leaves = shownColumns(view, configuration).leaves;
  const result = view.rows;
  const rows = Array.from({ length: result.rowCount }, (_, r) => leaves.map((l) => cell(result.columns[l.index]?.values[r] ?? null)));
  return JSON.stringify({
    columns: leaves.map((l) => ({
      name: l.name, path: l.path, type: l.type, dimension: l.isDimension, ...(l.label !== undefined ? { label: l.label } : {}),
    })),
    rows,
    tree: view.treeRows.map((t) => ({
      path: t.path, level: t.level, group: t.isGroup, expanded: t.expanded, total: t.isTotal, ...(t.isDetail ? { detail: true } : {}),
    })),
    truncated: view.truncated.map((l) => ({ level: l.level, parent: l.parent })),
    ...(view.totalRows !== undefined ? { totalRows: view.totalRows } : {}),
    sql: view.sql,
    query,
  });
}

/** What the grid shows, as DataCube computes it: JSON text (viewJson). */
export async function gridView(spec: GridSpec): Promise<string> {
  return using(spec, async ({ planner, state }) => {
    const controller = new CubeController(new DuckDbEngine(hostDatabase(hostCalls()).connection), planner);
    const view = await controller.run({ snapshot: state.snapshot, tree: state.tree });
    if (view === STALE) throw new Error('the grid was asked again before its view was ready');
    return viewJson(view, state.configuration, await planner.compose(view.query, 'STANDARD'));
  });
}

/** The grid's rows at their finest level as Pure, as Create a Class makes its class of them (classLambda). */
export async function gridRelation(spec: GridSpec): Promise<string> {
  return using(spec, async ({ planner, state }) => planner.compose(classLambda(state.snapshot), 'STANDARD'));
}
