// CREATE A CLASS… (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15): what DataCube asks legend-lite's
// promoteRelationToModel route (core's PromoteRelationToModel, through pure/v1's routing) and what it answers -- the
// class's files, in the SDLC's own change shape -- and the names DataCube offers before a person changes them.

import type { Lambda } from '../../pure-protocol/src/index.ts';
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

/** The request, less the model and the relation (the planner adds those). */
export interface PromoteRequest {
  readonly package: string;
  readonly class: string;
  readonly documentation?: string;
  readonly columns?: Readonly<Record<string, ColumnChoice>>;
  readonly where: {
    /** The Database the relation reads its tables from now (the tab's table model's). */
    readonly from: string;
    /** The runtime it runs on now. */
    readonly runtime: string;
    /** The place's name in the project: `Browser`, the tab's DuckDB. */
    readonly as: string;
    /** Each table new to the store, by name: its first rows, written as the class's sample. */
    readonly samples?: Readonly<Record<string, SampleRows>>;
  };
}

/** One column as the class takes it, as the route named and typed it. */
export interface PromotedProperty {
  readonly column: string;
  readonly as: ColumnRole;
  readonly property?: string;
  readonly type?: string;
  readonly multiplicity?: string;
}

/** The route's answer. */
export interface PromoteAnswer {
  /** The files to write: `CREATE` for an element the project does not hold, `MODIFY` for one it does. */
  readonly changes: readonly PureChange[];
  readonly class: string;
  readonly mapping: string;
  readonly runtime: string;
  /** The class read back as the relation's columns, under their own names. */
  readonly query: string;
  readonly properties: readonly PromotedProperty[];
  readonly notes: readonly string[];
}

/** A planner that writes classes: legend-lite's route, asked where the planner plans (the tab's own, WasmPlanner). */
export interface ClassWriter {
  /** `query` made a class of the project whose files' text is `project`, beside the planner's own model. */
  promoteRelationToModel(query: Lambda, project: string, request: PromoteRequest): Promise<PromoteAnswer>;
  /** The runtime the planner's source runs on now. */
  readonly runtime: string;
}

/** The place DataCube's tab is, in a project: the tab's DuckDB. */
export const BROWSER_PLACE = 'Browser';

/**
 * The class a source's name suggests: its file's name without the extension, in upper camel case, singular --
 * `trades.csv` a `Trade`, `order_lines.parquet` an `OrderLine`. A suggestion only: the person names it.
 */
export function suggestedClassName(sourceName: string): string {
  const base = sourceName.replace(/\.[A-Za-z0-9]+$/, '');
  const words = base.split(/[^A-Za-z0-9]+/).filter((w) => w !== '');
  if (words.length > 0) words[words.length - 1] = singular(words[words.length - 1]!);
  const name = words.map((w) => w.charAt(0).toUpperCase() + (w === w.toUpperCase() ? w.slice(1).toLowerCase() : w.slice(1))).join('');
  if (name === '') return 'Item';
  return /^[0-9]/.test(name) ? `_${name}` : name;
}

/** An English plural's singular, for the common endings; anything else as it is. */
function singular(word: string): string {
  const lower = word.toLowerCase();
  if (/(ss|us|is)$/.test(lower)) return word;
  if (lower.endsWith('ies') && word.length > 3) return `${word.slice(0, -3)}${word.slice(-3) === 'IES' ? 'Y' : 'y'}`;
  if (/(xes|ches|shes|sses)$/.test(lower)) return word.slice(0, -2);
  if (lower.endsWith('s') && word.length > 1) return word.slice(0, -1);
  return word;
}

/**
 * The package a page's name suggests for its project's elements: its words in lower camel case (`Trades by desk`
 * `tradesByDesk`), a Pure name; `datacube` when the name has none.
 */
export function suggestedPackage(pageName: string): string {
  const words = pageName.split(/[^A-Za-z0-9]+/).filter((w) => w !== '');
  const name = words.map((w, i) => {
    const rest = w === w.toUpperCase() ? w.slice(1).toLowerCase() : w.slice(1);
    return (i === 0 ? w.charAt(0).toLowerCase() : w.charAt(0).toUpperCase()) + rest;
  }).join('');
  if (name === '') return 'datacube';
  return /^[0-9]/.test(name) ? `_${name}` : name;
}

/** A Pure name: letters, digits and `_`, not led by a digit, and no word Pure reads as a literal. */
export function isPureName(name: string): boolean {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) && name !== 'true' && name !== 'false';
}

/** A package: Pure names joined by `::`. */
export function isPackage(name: string): boolean {
  return name.split('::').every(isPureName);
}
