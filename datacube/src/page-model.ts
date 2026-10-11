// THE PAGE'S MODEL (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.3): the project in the model home -- the page's own
// SDLC by default, the one Studio keeps its projects in, so Studio opens it and Query reads it once it is committed --
// that the page's classes are written to. Made quietly at the first class, named after the page, with a workspace of
// DataCube's; each class made is one save. The page document keeps where it is (`PageModelRef`).

import { SdlcError, type SdlcClient } from '../../sdlc-client/src/client.ts';
import type { PureChange } from '../../sdlc-client/src/wire.ts';

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
}

/** The group a page's project is made in. */
export const PAGE_GROUP = 'datacube';
/** DataCube's workspace in a page's project. */
export const PAGE_WORKSPACE = 'datacube';

/** The project's files as one model text: what the route reads beside the source it promotes. */
export function projectText(files: readonly { readonly pureCode: string }[]): string {
  return files.map((f) => (f.pureCode.startsWith('###') ? f.pureCode : `###Pure\n${f.pureCode}`)).join('\n');
}

/** A page's name as an artifact id: lowercase words joined by hyphens, led by a letter (the SDLC's own rule). */
export function artifactIdOf(name: string): string {
  const words = name.toLowerCase().split(/[^a-z0-9]+/).filter((w) => w !== '');
  const id = words.join('-').replace(/^[^a-z]+/, '');
  return id === '' ? 'page' : id;
}

/** The project's files at one revision, and that revision: a save names it, so a save over a newer one is refused. */
export interface ProjectState {
  readonly text: string;
  readonly revision: string;
}

export class PageModel {
  readonly #client: SdlcClient;
  #ref: PageModelRef | undefined;

  constructor(client: SdlcClient, ref?: PageModelRef) {
    this.#client = client;
    this.#ref = ref;
  }

  /** Where the page's model is, once it has one. */
  get ref(): PageModelRef | undefined {
    return this.#ref;
  }

  /**
   * The page's project, made the first time (`name`: the page's; `pkg`: where its classes go): a project in DataCube's
   * group named after the page -- a name another project already has numbered (`trades-2`) -- and DataCube's workspace
   * in it.
   */
  async ensure(name: string, pkg: string): Promise<PageModelRef> {
    if (this.#ref) return this.#ref;
    const taken = new Set((await this.#client.projects()).map((p) => p.projectId));
    const base = artifactIdOf(name);
    let artifactId = base;
    for (let n = 2; taken.has(`${PAGE_GROUP}:${artifactId}`); n += 1) artifactId = `${base}-${n}`;
    const project = await this.#client.createProject({
      name: name.trim() === '' ? artifactId : name.trim(),
      description: 'The classes of a DataCube page (Create a Class…)',
      groupId: PAGE_GROUP,
      artifactId,
    });
    // the page's from the moment it is made: a workspace or a save failing after leaves it the page's, never a project
    // the next class makes again beside it (the audit's S2); its workspace is made by `state`, as after a commit
    this.#ref = { project: project.projectId, workspace: PAGE_WORKSPACE, package: pkg, tables: {} };
    await this.#workspace(this.#ref);
    return this.#ref;
  }

  /** The workspace's files now, and the revision they are -- the workspace made again from the project line when it
   *  is gone (its review was committed in Studio, which closes a workspace). */
  async state(): Promise<ProjectState> {
    const ref = this.#need();
    const where = { project: ref.project, workspace: ref.workspace };
    await this.#workspace(ref);
    const revision = (await this.#client.revision(where)).id;
    const files = await this.#client.pure({ ...where, revision });
    return { text: projectText(files), revision };
  }

  /**
   * `changes` saved as one revision over `revision` -- refused as STALE (`StaleSave`) when the workspace moved since
   * (Studio saved it), so the caller reads it again and makes its change again.
   */
  async save(changes: readonly PureChange[], message: string, revision: string): Promise<void> {
    const ref = this.#need();
    if (changes.length === 0) return;
    try {
      await this.#client.performPureChanges(ref.project, ref.workspace, { message, changes, revisionId: revision });
    } catch (e) {
      if (e instanceof SdlcError && e.status === 409) throw new StaleSave(e.message);
      throw e;
    }
  }

  /**
   * DataCube's workspace, there: made from the project line when it is not (a commit in Studio closed it) -- another
   * ask having made it meanwhile (409) is its being there. A project gone (another browser's page, a cleared store) is
   * said, by name.
   */
  async #workspace(ref: PageModelRef): Promise<void> {
    try {
      await this.#client.workspace(ref.project, ref.workspace);
      return;
    } catch (e) {
      if (!(e instanceof SdlcError && e.status === 404)) throw e;
    }
    try {
      await this.#client.createWorkspace(ref.project, ref.workspace);
    } catch (e) {
      if (e instanceof SdlcError && e.status === 409) return;
      if (e instanceof SdlcError && e.status === 404) {
        throw new Error(`the page's project ${ref.project} is not in this browser's model home: it was made in another `
          + 'browser, or this browser\'s storage was cleared');
      }
      throw e;
    }
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

/** A save refused because the workspace moved since its files were read. */
export class StaleSave extends Error {
  constructor(detail: string) {
    super(detail);
    this.name = 'StaleSave';
  }
}

/**
 * MAKE AND SAVE, ONCE AGAIN WHEN STALE: `make` the changes against the project as it is, and save them over that
 * revision; refused as stale (the workspace moved since: Studio saved it), the project is read again and the changes
 * made again against it, once -- a second refusal is said, not retried.
 */
export async function saveFresh<T extends { readonly changes: readonly PureChange[] }>(
  model: PageModel, message: string, make: (project: string) => Promise<T>,
): Promise<T> {
  for (let attempt = 0; ; attempt += 1) {
    const state = await model.state();
    const made = await make(state.text);
    try {
      await model.save(made.changes, message, state.revision);
      return made;
    } catch (e) {
      if (!(e instanceof StaleSave) || attempt > 0) throw e;
    }
  }
}
