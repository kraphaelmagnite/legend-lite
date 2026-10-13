// THE PAGE'S MODEL (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.3): where the page's classes are -- a project in the
// model home (the page's own SDLC by default, the one Studio keeps its projects in), made quietly at the first class,
// named after the page, with a workspace of DataCube's -- and which grid is which class. The page document keeps it
// (`PageModelRef`). Making the project, reading it and saving to it are the generic library's
// (model-changes/, docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4); this is only the page's record.

import type { NewProject } from '../../model-changes/src/project.ts';

/** Where the page's model is, as the page document keeps it. */
export interface PageModelRef {
  /** The project, `groupId:artifactId`. */
  readonly project: string;
  /** DataCube's workspace in it. */
  readonly workspace: string;
  /** The package the page's classes are made in. */
  readonly package: string;
  /** Each grid's table in the project's store, by the grid's cube id: the name its source's table takes in this tab,
   *  kept, since the store's mapping reads it by that name. */
  readonly tables: Readonly<Record<string, string>>;
  /** Each grid's class, by the grid's cube id: the class its rows were made (a page saved before it had none). */
  readonly classes?: Readonly<Record<string, string>>;
}

/** The group a page's project is made in. */
export const PAGE_GROUP = 'datacube';
/** DataCube's workspace in a page's project. */
export const PAGE_WORKSPACE = 'datacube';

/** A page's project, as the library makes it: in DataCube's group, named after the page. */
export function pageProject(pageName: string): NewProject {
  return { name: pageName, group: PAGE_GROUP, description: 'The classes of a DataCube page (Create a Class…)' };
}

export class PageModel {
  #ref: PageModelRef | undefined;

  constructor(ref?: PageModelRef) {
    this.#ref = ref;
  }

  /** Where the page's model is, once it has one. */
  get ref(): PageModelRef | undefined {
    return this.#ref;
  }

  /**
   * Where the next model action writes, as the library takes it (model-changes `Into`): the page's project, or one to
   * make named after the page (`name`), its classes in `pkg` -- the page's from the moment it is made (`made`), so a
   * workspace or a save failing after leaves it the page's, never a project the next class makes again beside it.
   */
  into(name: string, pkg: string): { readonly project: string | NewProject; readonly workspace: string; readonly package: string;
    readonly made: (project: string) => void; } {
    const ref = this.#ref;
    return {
      project: ref?.project ?? pageProject(name),
      workspace: ref?.workspace ?? PAGE_WORKSPACE,
      package: ref?.package ?? pkg,
      made: (project) => {
        this.#ref = { project, workspace: PAGE_WORKSPACE, package: pkg, tables: {} };
      },
    };
  }

  /** The page's model now keeps `cls` as the grid `cube`'s class. */
  keepClass(cube: string, cls: string): void {
    const ref = this.#need();
    if (ref.classes?.[cube] === cls) return;
    this.#ref = { ...ref, classes: { ...ref.classes, [cube]: cls } };
  }

  /** The page's model now keeps `table` for the grid `cube`. */
  keepTable(cube: string, table: string): void {
    const ref = this.#need();
    if (ref.tables[cube] === table) return;
    this.#ref = { ...ref, tables: { ...ref.tables, [cube]: table } };
  }

  #need(): PageModelRef {
    if (!this.#ref) throw new Error('the page has no model yet');
    return this.#ref;
  }
}
