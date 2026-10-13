// THE WRITER'S WIRE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): what legend-lite's createClass, createAssociation and
// createDataSpace routes take and answer (core's ModelChanges, `/api/lite/v1/model/*`, answered by the tab's module, by
// Python's native library and by legend-lite's server alike), as one shape for every caller. Python's twin is
// python/legend_lite/model.py; test/cases holds the two alike.

import { toJson, type Lambda } from '../../pure-protocol/src/index.ts';
import type { PureChange } from '../../sdlc-client/src/wire.ts';

/** How a column of the relation is taken: a property of the class, a key only (a local property of the mapping, for an
 *  association to join on), or left out. */
export type ColumnRole = 'property' | 'key' | 'omit';

/** What a person changed about one column; a column not named is a property, named by upstream's rule. */
export interface ColumnChoice {
  readonly as?: ColumnRole;
  readonly name?: string;
  readonly documentation?: string;
}

/** The source's first rows, its table's columns in order, each value the database's own text (or null). */
export interface SampleRows {
  readonly columns: readonly string[];
  readonly rows: readonly (readonly (string | number | boolean | null)[])[];
}

/** Where a relation's rows are read from now: the model it is planned over and the runtime its queries run on. */
export interface Source {
  readonly model: string;
  readonly runtime: string;
}

/** A class to make: a relation's rows (DataCube's: a grid's at their finest level), over its own source. */
export interface ClassAction {
  readonly query: Lambda;
  readonly source: Source;
  readonly name: string;
  readonly documentation?: string;
  readonly columns?: Readonly<Record<string, ColumnChoice>>;
  readonly where: {
    /** The Database the relation reads its tables from now (its table model's). */
    readonly from: string;
    /** The place's name in the project: `Browser` (the tab's DuckDB), `Python` (Python's). */
    readonly as: string;
    /** Each table new to the store, by name: its first rows, written as the class's sample. */
    readonly samples?: Readonly<Record<string, SampleRows>>;
  };
}

/**
 * One end of an association, in Pure's own shape: its class, what it is joined on (a property, or a key only), the
 * name the OTHER class reaches it by, how many of it one of the other has -- or the fact (`unique`) the route reads
 * that from.
 */
export interface AssociationEnd {
  readonly class: string;
  readonly on: string;
  readonly name?: string;
  readonly multiplicity?: string;
  readonly unique?: boolean;
}

export interface AssociationAction {
  readonly name?: string;
  readonly ends: readonly [AssociationEnd, AssociationEnd];
}

export interface DataSpaceAction {
  readonly name: string;
  readonly title?: string;
  readonly description?: string;
  /** Its classes; every class of the package's domain when none. */
  readonly classes?: readonly string[];
}

/** One column as the class takes it, as the route named and typed it. */
export interface MadeProperty {
  readonly column: string;
  readonly as: ColumnRole;
  readonly property?: string;
  readonly type?: string;
  readonly multiplicity?: string;
}

export interface MadeClass {
  readonly class: string;
  readonly mapping: string;
  readonly runtime: string;
  /** The class read back as the relation's columns, under their own names. */
  readonly query: string;
  readonly properties: readonly MadeProperty[];
}

export interface MadeAssociation {
  readonly association: string;
  readonly ends: readonly { readonly class: string; readonly on: string; readonly name: string; readonly multiplicity: string }[];
}

/** An association the model suggests: a key named after another class and one of its properties, of its type. */
export interface Suggestion {
  readonly ends: readonly [{ readonly class: string; readonly on: string }, { readonly class: string; readonly on: string }];
}

/** What a class of the project can be joined on: each property and key only, whether the class shows it, its column. */
export interface Joinable {
  readonly class: string;
  /** The relation function its rows are read from (its mapping's `~func`). */
  readonly function?: string;
  readonly keys: readonly { readonly name: string; readonly type: string; readonly shown: boolean; readonly column?: string }[];
}

/** A route's answer: the files to write, what was made, the associations suggested. */
export interface ModelAnswer {
  /** `CREATE` for an element the project does not hold, `MODIFY` for one it does. */
  readonly changes: readonly PureChange[];
  readonly classes: readonly MadeClass[];
  readonly associations: readonly MadeAssociation[];
  readonly dataSpace?: string;
  readonly suggestions: readonly Suggestion[];
  /** Each class of the package, after the changes: what it can be joined on. */
  readonly joinable: readonly Joinable[];
  readonly notes: readonly string[];
}


/** legend-lite's model writer: its three routes, wherever they are answered (the tab's module, Python's native library,
 *  a legend-lite server: `serverWriter`). Each is one call, atomic, and writes nothing itself: it answers the files. */
export interface ModelWriter {
  /** `model`: the project's files as one model text (project.ts `projectText`), which the route reads beside what it
   *  makes. */
  createClass(model: string, pkg: string, cls: ClassAction): Promise<ModelAnswer>;
  /** With no association, nothing is made: the answer's suggestions and joinable classes start a window. */
  createAssociation(model: string, pkg: string, association: AssociationAction | undefined,
    classes?: readonly ClassAction[]): Promise<ModelAnswer>;
  createDataSpace(model: string, pkg: string, dataSpace: DataSpaceAction, classes?: readonly ClassAction[],
    associations?: readonly AssociationAction[]): Promise<ModelAnswer>;
}

/**
 * A request's body, each class's relation spliced in as `toJson` wrote it (so its numbers keep their digits); the
 * grid's runtime goes in its `where`, as the route reads it.
 */
export function modelRequestBody(lambdaJson: (query: Lambda) => string, model: string, pkg: string, parts: {
  readonly class?: ClassAction;
  readonly classes?: readonly ClassAction[];
  readonly association?: AssociationAction;
  readonly associations?: readonly AssociationAction[];
  readonly dataSpace?: DataSpaceAction;
}): string {
  const cls = (c: ClassAction): string => {
    const { query, source, where, ...rest } = c;
    const fields = JSON.stringify({ source: { _type: 'text', code: source.model }, ...rest,
      where: { ...where, runtime: source.runtime } });
    return `{"lambda":${lambdaJson(query)},${fields.slice(1)}`;
  };
  // the route's `project` field is the project's model text
  const fields = [`"project":${JSON.stringify({ _type: 'text', code: model })}`, `"package":${JSON.stringify(pkg)}`];
  if (parts.class) fields.push(`"class":${cls(parts.class)}`);
  if (parts.classes) fields.push(`"classes":[${parts.classes.map(cls).join(',')}]`);
  if (parts.association !== undefined) fields.push(`"association":${JSON.stringify(parts.association)}`);
  if (parts.associations) fields.push(`"associations":${JSON.stringify(parts.associations)}`);
  if (parts.dataSpace) fields.push(`"dataSpace":${JSON.stringify(parts.dataSpace)}`);
  return `{${fields.join(',')}}`;
}

/** Each route's body as every writer sends it -- the tab's module, a legend-lite server, Python's twin
 *  (python/legend_lite/model.py `writer_body`): a route's lists always there, empty when there is nothing in them. */
export const writerBodies = {
  createClass: (model: string, pkg: string, cls: ClassAction): string =>
    modelRequestBody(toJson, model, pkg, { class: cls }),
  createAssociation: (model: string, pkg: string, association: AssociationAction | undefined,
    classes: readonly ClassAction[] = []): string =>
    modelRequestBody(toJson, model, pkg, association === undefined ? { classes } : { classes, association }),
  createDataSpace: (model: string, pkg: string, dataSpace: DataSpaceAction, classes: readonly ClassAction[] = [],
    associations: readonly AssociationAction[] = []): string =>
    modelRequestBody(toJson, model, pkg, { classes, associations, dataSpace }),
};
