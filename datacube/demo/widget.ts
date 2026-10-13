// DATACUBE UNDER A NOTEBOOK'S CELL (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §3.3; step 2a): DataCube's module for
// `legend_lite.notebook`'s widgets, which the widget's loader (widget-loader.ts) fetches over the widget's channel once
// per notebook page. One file, no lazy chunks (a module imported from a blob URL loads no chunk beside it), its styles
// beside it (widget-styles.css, bundled as widget.css), added to the page by the loader.
//
// DataCube's app (src/app/datacube-app.ts) over Python's services (src/services/python-host.ts), its calls over the
// channel: a page (the widget's `page_key`, Python's `ll.Page`) or one frame (its `table`, `ll.show(df)`: a page of one
// grid). It follows Python by the widget's own state, which Python sets as it changes what it shows -- the page's and
// each frame's `versions`, or the frame's `version` -- so it never polls. Its keys stay with it.

import { openDataCube, type DataCubeApp } from '../src/app/datacube-app.ts';
import type { Versions } from '../src/app/services.ts';
import { browserBasics } from '../src/services/browser-basics.ts';
import { askedOffers, pythonServices, type PythonLink, type PythonShows } from '../src/services/python-host.ts';
import { BASE, type WidgetModel } from './widget-loader.ts';

const px = (model: WidgetModel): string => `${Number(model.get('height')) || 480}px`;

/** The versions the widget says now, of what it shows. */
function versionsOf(model: WidgetModel, shows: PythonShows): Versions | undefined {
  if ('frame' in shows) return { frames: { [shows.frame]: Number(model.get('version')) } };
  const said = model.get('versions') as { version?: number; frames?: Record<string, number> } | undefined;
  return said?.version === undefined ? undefined : { version: said.version, frames: said.frames ?? {} };
}

/** Told at once of the widget's versions, then each time Python moves them. */
function watched(model: WidgetModel, shows: PythonShows) {
  const event = 'frame' in shows ? 'change:version' : 'change:versions';
  return (moved: (now: Versions | string) => void): (() => void) => {
    const tell = (): void => {
      const now = versionsOf(model, shows);
      if (now) moved(now);
    };
    model.on(event, tell);
    queueMicrotask(tell);
    return () => model.off(event, tell);
  };
}

export function render(model: WidgetModel, el: HTMLElement, fetch: typeof globalThis.fetch): () => void {
  const page = model.get('page_key');
  const shows: PythonShows = typeof page === 'string' && page !== '' ? { page } : { frame: String(model.get('table')) };
  const host = document.createElement('div');
  host.style.cssText = 'position:relative;box-sizing:border-box;height:100%';
  el.style.height = px(model);
  // the page fills `host` (it replaces what is in it); the app's windows float beside it, over it, in the widget's own
  // element (Generated Pure & SQL: src/app/datacube-app.ts appWindows)
  const position = el.style.position;
  if (getComputedStyle(el).position === 'static') el.style.position = 'relative';
  el.replaceChildren(host);
  // a key pressed in DataCube is DataCube's: not the notebook's shortcuts (an arrow moves in the grid, not between
  // cells). JupyterLab skips shortcuts under this mark; any page's handler further up never hears the key.
  el.dataset['lmSuppressShortcuts'] = 'true';
  const keep = (e: Event): void => e.stopPropagation();
  el.addEventListener('keydown', keep);
  const resized = (): void => { el.style.height = px(model); };
  model.on('change:height', resized);

  let gone = false;
  let app: DataCubeApp | undefined;
  const status = document.createElement('span');
  status.textContent = 'asking Python…';
  const settled = { show: (_reason: string): void => {} };
  const link: PythonLink = { baseUrl: BASE, fetch };
  // what Python's host offers beside its frames, asked once (services.json), then the app over its services
  askedOffers(link).then((offers) => {
    // where it plans, in its one word (the status bar's readout)
    status.textContent = offers.plane.word;
    return openDataCube({
      // a notebook's cell is no tab of its own: no title to set, and no address another person could open
      services: pythonServices({ link, shows, offers, watch: watched(model, shows), browser: browserBasics }),
      host,
      status,
      windows: el,
      start: 'page' in shows ? { kind: 'live' } : { kind: 'frame', name: shows.frame },
      taken: [],
      settled,
    });
  }).then((opened) => {
    // a view taken down while it opened
    if (gone) opened.dispose();
    else app = opened;
  }, (e: unknown) => {
    const why = `failed to start: ${e instanceof Error ? e.message : String(e)}`;
    settled.show(why);
    if (!gone && host.childElementCount === 0) host.textContent = why;
  });

  return () => {
    gone = true;
    el.removeEventListener('keydown', keep);
    model.off('change:height', resized);
    app?.dispose();
    // the element as the notebook gave it: it may be used again
    el.style.position = position;
    delete el.dataset['lmSuppressShortcuts'];
  };
}
