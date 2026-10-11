// THE COLUMNS A GRID SHOWS: its view's columns laid out as its configuration says -- hidden ones out, its own order
// (the cube's, not the answer's), each pivot key's value order, the pivot's header paths. One function, so the screen
// (app.ts paints its grid with it) and a grid read headless (headless/grid.ts) show the same columns.

import { columnConfig, toColumnLayout, type CubeConfiguration } from './config.ts';
import { pivotHeaderPaths, type CubeView } from './cube.ts';
import { buildColumnModel, type ColumnLayout, type ColumnModel } from './grid/columns.ts';

export function shownColumns(view: CubeView, configuration: CubeConfiguration): ColumnModel {
  return buildColumnModel(
    view.rows,
    view.snapshot.rows,
    view.snapshot.measures.map((m) => m.name),
    {
      ...(toColumnLayout(configuration) as ColumnLayout),
      // THE CUBE'S ORDER, not the answer's.
      //
      // Leaves were ordered by their position in the RESULT, and a
      // pivoted cube's result puts the pivot's own columns before
      // the ones it carried through -- upstream's `_groupByAggCols`
      // emits them in that order too. So pivoting threw the
      // columns into a new order: every year block first, then
      // trade_id, quarter and the rest behind them.
      //
      // DataCube does not have the problem because its grid never
      // takes order from the query: `columnDefs:
      // generateColumnDefs(snapshot, configuration)` builds them in
      // CONFIGURATION order, and the result's column order is
      // nobody's business but the engine's. So the declared order
      // is the default here, and an explicit reorder still wins.
      order: configuration.columnOrder
        ?? view.snapshot.columns.map((c) => c.name),
      // Horizontal Pivots > each key's sort direction: the order of
      // that key's VALUES across the header. Written by the editor
      // and read by nothing until the 2026-09-25 sweep found it.
      pivotDirections: view.snapshot.pivotOn.map((name) =>
        columnConfig(configuration, name).pivotSortDirection ?? 'asc'),
    },
    view.snapshot.pivotOn.length,
    pivotHeaderPaths(view.pivot?.columns),
  );
}
