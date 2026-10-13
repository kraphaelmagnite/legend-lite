// A PROJECT'S FILES AS ONE MODEL TEXT (the Studio / SDLC / Depot line's review of B4, 2026-10-13): the one rule every
// client of lite's text routes joins a project's files by -- a file already led by a section (`###Mapping`) as it is,
// every other one led by `###Pure`, each on its own line. model-changes/ (and its Python twin, legend_lite.model
// project_text) read a project so before the writer is asked.

import type { PureFile } from './wire.ts';

export function projectText(files: readonly Pick<PureFile, 'pureCode'>[]): string {
  return files.map((f) => (f.pureCode.startsWith('###') ? f.pureCode : `###Pure\n${f.pureCode}`)).join('\n');
}
