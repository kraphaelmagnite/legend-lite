// THE PAGE'S MODEL, AS DATACUBE NAMES IT, HEADLESS (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): what Python's
// `grid.create_class()`, `page.create_association()` and `page.create_data_space()` ask of DataCube's own code beside
// the generic library (legend_lite/model.py) -- the names the windows offer before a person changes them, the project a
// page's classes go in, and the query that finds a class's key held twice over its rows -- so Python writes none of
// DataCube's rules again.

import { fn, toJson } from '../../../pure-protocol/src/index.ts';
import { suggestedDataSpaceName, suggestedPackage } from '../model-names.ts';
import { PAGE_WORKSPACE, pageProject } from '../page-model.ts';
import { duplicateLambda } from '../query.ts';

/** A page's model as the windows name it before its first class: its package, its data space, its project to make
 *  (page-model.ts `pageProject`) and DataCube's workspace in it. JSON. */
export function pageModelNames(pageName: string): string {
  return JSON.stringify({
    package: suggestedPackage(pageName), dataSpace: suggestedDataSpaceName(pageName), project: pageProject(pageName),
    workspace: PAGE_WORKSPACE,
  });
}

/**
 * The query that finds `column`'s first value held twice over a class's rows, read through its relation function
 * (`fn`, its mapping's `~func`) over the project -- the fact Create an Association… presets how many of an end from
 * (model-actions.ts `unique`: no row, and the key is unique). JSON, as the protocol writes it.
 */
export function classDuplicate(relationFunction: string, column: string): string {
  return toJson(duplicateLambda(fn(relationFunction), column));
}
