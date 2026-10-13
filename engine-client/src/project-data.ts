// A PROJECT'S OWN DATABASE in this tab (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5): where a project version's Data
// rows live -- a database of their own inside the tab's DuckDB, its schemas as the model declares them, never beside a
// person's own tables. What it is, and every statement that makes, points at, fills and drops it, is the planner's
// (Boundary.projectData, the platform's ProjectData in Java, by the tab's database type); this tab spells none.

/** The planner's answer for one project's database: its name, and the statements of each step, in order. */
export interface ProjectDataStatements {
  /** Its name, from the identity it is kept by. */
  readonly name: string;
  /** Make it, empty (on any connection). */
  readonly open: readonly string[];
  /** Point a connection of its own at it. */
  readonly use: readonly string[];
  /** Make and fill every table a relational Data element fills (on that connection). */
  readonly fill: readonly string[];
  /** Drop it, with everything in it (on any connection). */
  readonly close: readonly string[];
}

/** Where a project's database comes from: legend-lite's planner (WasmGrammar.projectData, DataCube's LitePlanner). */
export interface ProjectDataSource {
  /** `identity`: a project version's coordinates; '' for the model's text (a workspace whose text changes). */
  projectData(model: string, identity: string, databaseType: string): Promise<ProjectDataStatements>;
}
