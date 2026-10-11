// CREATE A CLASS… OF A GRID (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15): its rows at its finest level made a class
// of the page's model -- the project in the model home (the page's own SDLC: Studio's), made with the page's first
// class -- with everything the class needs to run, its source's first rows its sample. The route writes the files
// (legend-lite's createClass, asked of the tab's module on every plane); the page's model saves them, as one revision of
// DataCube's workspace. Loaded the first time it is asked for: a page that makes no class never fetches it -- and it
// imports nothing the page loads at startup (its queries are handed in), so the page's own modules stay in its bundle.

import type { Lambda } from '../../pure-protocol/src/index.ts';
import type { SdlcClient } from '../../sdlc-client/src/client.ts';
import type { QueryEngine } from '../../engine-client/src/engine.ts';
import type { Planner } from './cube.ts';
import { PageModel, saveFresh, type PageModelRef } from './page-model.ts';
import type { ClassAction, GridSource, ModelWriter } from './model-writer.ts';
import { BROWSER_PLACE, suggestedClassName, suggestedPackage } from './model-names.ts';
import { classDialog, type ClassChoice, type ClassPreview, type CreatedClass } from './ui/class-dialog.ts';
import type { ModelAnswer } from './model-writer.ts';

export interface CreateClassOf {
  /** The grid's rows at their finest level (query.ts classLambda): the class's relation. */
  readonly query: Lambda;
  /** The table the grid's source reads (query.ts storeTableOf). */
  readonly table: { readonly database: string; readonly schema: string; readonly table: string };
  /** The source's first rows (query.ts sampleLambda): the class's sample. */
  readonly sample: Lambda;
  /** The grid's planner: its sample is planned there. */
  readonly planner: Planner;
  /** The grid's source: its planner's model and runtime, which the class is made over. */
  readonly source: GridSource;
  /** The model's writer: the tab's module, on every plane. */
  readonly writer: ModelWriter;
  /** Where the grid's rows run: its sample is read there. */
  readonly engine: QueryEngine;
  /** What the grid's header says it reads. */
  readonly sourceName: string;
  /** The grid's cube id on the page: its table's name is kept by it. */
  readonly cube: string | undefined;
  /** The page's name, as it would be saved. */
  readonly pageName: string;
  /** The page's model, when it has one. */
  readonly model: PageModelRef | undefined;
  /** The model home's SDLC. */
  readonly client: SdlcClient;
  /** Studio's editor on a workspace, on this page's own address. */
  readonly studio: (project: string, workspace: string) => string;
  /** Ask the browser to keep this origin's storage: whether it promised. */
  readonly persist: () => Promise<boolean>;
}

/**
 * Ask for the class and write it. Resolves with the page's model as it is after -- made with its first class (kept
 * even when its first save failed), its grid's table kept -- or undefined when nothing of it changed.
 */
export async function createClassOf(o: CreateClassOf): Promise<PageModelRef | undefined> {
  const { query, table } = o;
  const model = new PageModel(o.client, o.model);
  // its source's first rows, as the database has them: the class's sample (a Data element)
  const sample = await o.engine.execute(await o.planner.plan(o.sample), 0);
  const samples = {
    [table.table]: {
      columns: sample.columns.map((c) => c.name),
      rows: [...Array(sample.rowCount).keys()].map((r) => sample.columns.map((c) => {
        const v = c.values[r] ?? null;
        return typeof v === 'bigint' ? String(v) : v;
      })),
    },
  };
  const action = (c: ClassChoice): ClassAction => ({
    query,
    source: o.source,
    name: c.className,
    ...(c.documentation ? { documentation: c.documentation } : {}),
    columns: c.columns,
    where: { from: table.database, as: BROWSER_PLACE, samples },
  });
  const preview = (answer: ModelAnswer): ClassPreview => ({
    changes: answer.changes, properties: answer.classes[0]?.properties ?? [], notes: answer.notes,
  });
  await classDialog(document, {
    sourceName: o.sourceName,
    className: suggestedClassName(o.sourceName),
    package: model.ref?.package ?? suggestedPackage(o.pageName),
    project: { name: model.ref?.project ?? o.pageName, isNew: model.ref === undefined },
    preview: async (c) => preview(await o.writer.createClass(model.ref ? (await model.state()).text : '', c.package, action(c))),
    create: async (c): Promise<CreatedClass> => {
      const ref = await model.ensure(c.projectName ?? o.pageName, c.package);
      const made = await saveFresh(model, `Create a Class: ${c.className}`,
        (project) => o.writer.createClass(project, ref.package, action(c)));
      // the grid's table keeps its name from now: the project's store reads it by that name
      if (o.cube !== undefined) {
        model.keepTable(o.cube, table.table);
        const cls = made.classes[0]?.class;
        if (cls !== undefined) model.keepClass(o.cube, cls);
      }
      const after = model.ref ?? ref;
      const kept = await o.persist();
      return {
        className: made.classes[0]?.class ?? c.className,
        where: `the project ${after.project} (workspace ${after.workspace}), in this browser`,
        studio: o.studio(after.project, after.workspace),
        notes: [
          ...made.notes,
          ...(kept ? [] : ['This browser has not promised to keep its storage: it may clear the project.']),
        ],
      };
    },
  });
  // the page's model as it is now, when the window changed it: made (whether or not its save then went through), or a
  // grid's table kept
  return model.ref !== o.model ? model.ref : undefined;
}
