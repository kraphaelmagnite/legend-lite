// A PROJECT TO WRITE MODELS INTO (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): in an SDLC with lite's text routes --
// the page's own, the model home (Python's too), sdlc-server -- through the one SDLC client. Made, with its workspace,
// when it is not yet; read as one model text at one revision; a change saved over that revision as one, made again once
// over a newer one. A real legend-sdlc answers neither text route (its project ids are its own too): it is owed, over
// its entities (B4). Python's twin is python/legend_lite/model.py.

import { SdlcError, type SdlcClient } from '../../sdlc-client/src/client.ts';
import { projectText } from '../../sdlc-client/src/project-text.ts';
import type { PureChange } from '../../sdlc-client/src/wire.ts';

/** A project to make: its name (its artifact id by the SDLC's rule, a taken one numbered), its group, its description. */
export interface NewProject {
  readonly name: string;
  readonly group: string;
  readonly description?: string;
}

/** Where to write: a project (`groupId:artifactId`) and a workspace in it. */
export interface Place {
  readonly project: string;
  readonly workspace: string;
}

/** A project's files as one model text: what the writer reads beside what it makes (sdlc-client's one rule). */
export { projectText };

/** A name as an artifact id: lowercase words joined by hyphens, led by a letter (the SDLC's own rule). */
export function artifactIdOf(name: string): string {
  const words = name.toLowerCase().split(/[^a-z0-9]+/).filter((w) => w !== '');
  const id = words.join('-').replace(/^[^a-z]+/, '');
  return id === '' ? 'project' : id;
}

/** A project made: in its group, named after `made.name` -- a name another project of the group has already numbered
 *  (`trades-2`). */
export async function makeProject(sdlc: SdlcClient, made: NewProject): Promise<string> {
  const taken = new Set((await sdlc.projects()).map((p) => p.projectId));
  const base = artifactIdOf(made.name);
  let artifactId = base;
  for (let n = 2; taken.has(`${made.group}:${artifactId}`); n += 1) artifactId = `${base}-${n}`;
  const project = await sdlc.createProject({
    name: made.name.trim() === '' ? artifactId : made.name.trim(),
    description: made.description ?? '',
    groupId: made.group,
    artifactId,
  });
  return project.projectId;
}

/**
 * The workspace, there: made from the project line when it is not (a commit in Studio closes a workspace). Its making
 * refused, the workspace is read again -- another ask may have made it meanwhile, which lite's SDLC answers with a 500
 * ("Branch already exists") once the workspace has commits (the Studio line's review) -- and the refusal said only
 * when it is still not there. A project that is not there is said, by name.
 */
export async function ensureWorkspace(sdlc: SdlcClient, at: Place): Promise<void> {
  try {
    await sdlc.workspace(at.project, at.workspace);
    return;
  } catch (e) {
    if (!(e instanceof SdlcError && e.status === 404)) throw e;
  }
  try {
    await sdlc.createWorkspace(at.project, at.workspace);
  } catch (e) {
    if (e instanceof SdlcError && e.status === 404) throw new ProjectGone(at.project);
    const there = await sdlc.workspace(at.project, at.workspace).then(() => true, () => false);
    if (!there) throw e;
  }
}

/** The workspace's files now as one model text, and the revision they are (the workspace made when it is not). */
export async function projectState(sdlc: SdlcClient, at: Place): Promise<{ readonly text: string; readonly revision: string }> {
  await ensureWorkspace(sdlc, at);
  const revision = (await sdlc.revision(at)).id;
  const files = await sdlc.pure({ ...at, revision });
  return { text: projectText(files), revision };
}

/**
 * MAKE AND SAVE, ONCE AGAIN WHEN STALE: `make` the changes against the project's model text as it is, and save them
 * over that revision as one; refused as stale (409: lite's one refusal of a save before its changes, the workspace moved
 * since -- Studio saved it), the project is read again and the changes made again against it, once -- a second refusal
 * is said, not retried. Answers what was made and the revision it was saved as: the one read, when the save changed
 * nothing (no changes, or every one the bytes already there: the SDLC's 204).
 */
export async function saveFresh<T extends { readonly changes: readonly PureChange[] }>(
  sdlc: SdlcClient, at: Place, message: string, make: (model: string) => Promise<T>,
): Promise<{ readonly made: T; readonly revision: string }> {
  for (let attempt = 0; ; attempt += 1) {
    const state = await projectState(sdlc, at);
    const made = await make(state.text);
    if (made.changes.length === 0) return { made, revision: state.revision };
    try {
      const saved = await sdlc.performPureChanges(at.project, at.workspace,
        { message, changes: made.changes, revisionId: state.revision });
      return { made, revision: saved?.id ?? state.revision };
    } catch (e) {
      if (!(e instanceof SdlcError && e.status === 409) || attempt > 0) throw e;
    }
  }
}

/** A project the SDLC no longer has (another browser's page's, a cleared store): said by name. */
export class ProjectGone extends Error {
  readonly project: string;
  constructor(project: string) {
    super(`the project ${project} is not in this model home: it was made elsewhere, or its store was cleared`);
    this.name = 'ProjectGone';
    this.project = project;
  }
}
