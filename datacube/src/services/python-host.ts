// PYTHON'S SERVICES (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §3.2, §4.3, §5; step 2a): what DataCube's app runs on
// when Python shows it -- in a tab of its own (demo/engine.ts, over HTTP to Python's engine with its token) or under a
// notebook's cell (demo/widget.ts, over the widget's channel, which the notebook authenticates). The app is the tab's;
// only these services differ.
//
// - THE ROWS ARE PYTHON'S (R4): the page sends Pure, never SQL. Each grid is one PlanAndRun (§15): the page's own
//   planner asks Python for the plan, which Python makes against the model it holds and runs on its DuckDB in the same
//   call (`plan-and-run.json`), and the page reads the rows typed by that plan, as the tab reads its own. A grid's
//   columns, a query's print, are the planner protocol's answers, made by Python's native library (`planner.json`).
// - THE DATA IN is Python's frames: each a model Python wrote when the frame was registered (`cube.json?table=`).
// - PYTHON IS ANOTHER EDITOR of what the page shows (§5): it changes the page (`ll.Page`) and its frames, the page
//   follows their versions (`version.json`, or the widget's), and it says what it is now, for `page.read()`
//   (`page.json`, with the version it opened: what it says of a page Python has replaced is refused).
//
// - A PAGE'S FILE (an upload, an example) goes into Python's DuckDB (step 2b): its bytes to Python a part at a time
//   (`file-part.json`), read there as one file by DataCube's own code (upload.ts, run headless: `file.json`), which
//   writes its model; the grid's rows are then Python's over that model, as a frame's are. Offered where Python says it
//   takes files (`services.json`: the datacube extra).
//
// What Python does not answer yet is a service it does not give, and the app does not offer what reads it (§3.4): its
// page store (Save, Open…: step 2d), a remote file (2b's second part), the other planes (2c), its model home and saved
// queries (step 3). A share link needs an address others can open; Python's is this machine's loopback.

import { arrowIpcAnswer } from '../../../engine-client/src/arrow-ipc.ts';
import { toRawTable } from '../../../engine-client/src/duckdb.ts';
import { typedByPlan } from '../../../engine-client/src/engine.ts';
import type { Plan } from '../../../engine-client/src/relation-type.ts';
import { hostOf } from '../../../engine-client/src/receipt.ts';
import type { ResultTable } from '../../../engine-client/src/result.ts';
import type { Lambda } from '../../../pure-protocol/src/index.ts';
import { LitePlanner } from '../lite-planner.ts';
import type { PlannerWorker, Request, Response } from '../planner-protocol.ts';
import { ExactNumber, fromJson, type ValueSpecification } from '../../../pure-protocol/src/index.ts';
import { base64 } from '../headless/web.ts';
import { HostRecords, RuleStore, type HostRecordsCall } from '../cube-store.ts';
import { pageToJson, readPage, type PageDocument } from '../page-document.ts';
import { PlanAndRun, QueryFailure } from '../runner.ts';
import type { InferredModel } from '../infer.ts';
import type { S3Credentials } from '../remote.ts';
import { fileParts, type UploadResult } from '../upload.ts';
import type {
  BrowserService, DatabaseService, FrameModel, LivePage, PageService, Plane, PlaneService, PlaneWord, Services, Versions,
} from '../app/services.ts';

/** How the page reaches Python's engine. */
export interface PythonLink {
  /** What every call's path goes under: a tab's own origin, or the widget channel's base (widget-loader.ts). */
  readonly baseUrl: string;
  /** What carries the calls: the page's fetch, or the widget channel's. */
  readonly fetch: typeof fetch;
  /** The `Authorization` header the engine asks for over HTTP (`Bearer <token>`); a notebook's channel needs none. */
  readonly authorization?: string;
}

/** What Python shows: a page of its own (`ll.Page`, by its key), or one frame (`ll.show(df)`: a page of one grid). */
export type PythonShows = { readonly page: string } | { readonly frame: string };

/** A plane as Python names it: its word, and what the planner menu says of it. */
export interface PythonPlane {
  readonly word: PlaneWord;
  readonly label: string;
}

/** What Python's host offers beside its frames (`services.json`): whether it reads a page's file, the plane it plans on
 *  (chosen once, in Python: R3), and every plane it offers (today only that one: another is step 2c's). */
export interface PythonOffers {
  readonly files: boolean;
  /** Whether it reads a page's remote file (the httpfs extension it carries). */
  readonly remote: boolean;
  /** Where it keeps the page's saved pages (this user's folder), and whose they are. */
  readonly pages: { readonly where: string; readonly user: string };
  readonly plane: PythonPlane;
  readonly planes: readonly PythonPlane[];
}

export interface PythonServicesOptions {
  readonly link: PythonLink;
  readonly shows: PythonShows;
  /** What Python said it offers (`askedOffers`), asked once as the page starts. */
  readonly offers: PythonOffers;
  /**
   * How the page is told that Python moved what it shows: at once of the versions now, then as they move, or why
   * Python no longer says (a string). A tab asks (`askedVersions`, about once a second); a notebook's widget is told
   * (its `versions`, or its frame's `version`). Returns what stops it.
   */
  readonly watch: (moved: (now: Versions | string) => void) => () => void;
  /** The browser's own things where the page is: a tab's (its title too), a notebook's (no title of its own). */
  readonly browser: BrowserService;
}

const WORDS: readonly PlaneWord[] = ['local', 'remote', 'engine'];

/** A plane Python said, read: its word one of the three every host says (§4.1), or refused. */
function planeOf(said: unknown): PythonPlane {
  const p = said as { word?: unknown; label?: unknown } | null;
  if (p === null || typeof p !== 'object' || !WORDS.includes(p.word as PlaneWord) || typeof p.label !== 'string') {
    throw new Error(`the engine named a plane this page does not know: ${JSON.stringify(said)}`);
  }
  return { word: p.word as PlaneWord, label: p.label };
}

/**
 * WHERE THE PAGE PLANS, as Python says (§4.1, R3): the plane Python plans on, chosen once in Python, and the planes it
 * offers, which the planner menu lists (the one the page is on disabled). Another plane is chosen in Python
 * (`ll.show(..., planner=...)`, step 2c), so a page asks for none: Python offers only its own until then.
 */
function pythonPlanes(offers: PythonOffers): PlaneService {
  // a plane offered here would be one the page could choose, which it cannot yet: refused, never a menu entry that
  // asks to reload and then fails
  const other = offers.planes.find((p) => p.word !== offers.plane.word);
  if (other) throw new Error(`the engine offers ${other.label}, and this page chooses no plane on Python yet (step 2c)`);
  const asPlane = (p: PythonPlane): Plane => ({ id: `host.plane.${p.word}`, word: p.word, label: p.label });
  return {
    word: offers.plane.word,
    planes: offers.planes.map(asPlane),
    choose: (plane) => {
      throw new Error(`${plane.label}: Python plans on ${offers.plane.word}, chosen in Python, not on this page`);
    },
  };
}

const headers = (link: PythonLink): Record<string, string> =>
  (link.authorization === undefined ? {} : { Authorization: link.authorization });

/** What the engine answered to `path`, as text, or -- refused -- why, in the page's words. */
async function asked(link: PythonLink, path: string): Promise<string> {
  const answer = await link.fetch(`${link.baseUrl}/${path}`, { headers: headers(link) })
    .catch((e: unknown) => { throw new Error(`the engine did not answer: ${String(e)}`); });
  if (!answer.ok) throw new Error(`the engine did not say what to show: ${answer.status} ${await answer.text()}`);
  return answer.text();
}

/**
 * THE PLANNER'S WORKER, ANSWERED BY PYTHON (§15.2): the planner protocol's requests -- the ones the tab's worker answers --
 * carried to Python's native library, which answers them as it answers headless's (`planner.json`). No handle crosses
 * the hosts (§15.3): a `hold` is kept here, its model's text by the handle this gives, and a held model's request sends
 * that text in the handle's place, Python planning against the model it holds of that text.
 */
function pythonWorker(link: PythonLink): PlannerWorker {
  const held = new Map<number, string>();
  let next = 1;
  let alive = true;
  const worker: PlannerWorker = {
    onmessage: null,
    onerror: null,
    postMessage(message: Request): void {
      const reply = (response: Response): void => { if (alive) worker.onmessage?.({ data: response }); };
      const { id, base: _base, ...body } = message;
      if (body.kind === 'hold') {
        const handle = next++;
        held.set(handle, body.model);
        reply({ id, ok: true, answer: `OK\n${handle}` });
        return;
      }
      if (body.kind === 'release') {
        held.delete(body.handle);
        reply({ id, ok: true, answer: 'OK\n' });
        return;
      }
      let sent: Record<string, unknown> = body;
      if ('handle' in body) {
        const { handle, ...rest } = body;
        const model = held.get(handle);
        if (model === undefined) {
          reply({ id, ok: true, answer: `ERR\nMODEL\nno model held as ${handle} on this page` });
          return;
        }
        sent = { ...rest, model };
      }
      void link.fetch(`${link.baseUrl}/planner.json`, {
        method: 'POST',
        headers: { ...headers(link), 'Content-Type': 'application/json' },
        body: JSON.stringify(sent),
      }).then(async (answer) => {
        const text = await answer.text();
        if (!answer.ok) {
          reply({ id, ok: false, error: `the engine answered ${answer.status}: ${text}` });
          return;
        }
        reply({ id, ...(JSON.parse(text) as { ok: true; answer: string } | { ok: false; error: string }) });
      }, (e: unknown) => {
        // the call itself failed: the worker's death, as a crashed tab worker's is (the planner starts another)
        if (!alive) return;
        alive = false;
        worker.onerror?.({ message: `the engine did not answer: ${String(e)}` });
      });
    },
    terminate(): void {
      alive = false;
    },
  };
  return worker;
}

/**
 * PLANANDRUN ON PYTHON (§15.2): the tab's planner over Python's worker for the compiler's questions, and one call
 * (`plan-and-run.json`) that plans the query against the model Python holds and runs its SQL on Python's DuckDB --
 * the page sends Pure, never SQL (R4). Python answers the database's rows as Arrow, the planner's answer in their
 * metadata; the page reads the plan as its planner reads its own, and the rows typed by it as the tab's executor types
 * the tab's (engine.ts typedByPlan, §15.3a).
 */
export function pythonRunner(link: PythonLink, model: string, runtime: string): PlanAndRun {
  const planner = new LitePlanner({ model, runtime, worker: () => pythonWorker(link) });
  const readPlan = (answer: string, query: Lambda): Plan => {
    try {
      return planner.readPlan(answer, query);
    } catch (e) {
      throw new QueryFailure(e, query);
    }
  };
  const planAndRun = async (query: Lambda, epoch: number, signal?: AbortSignal): Promise<{ plan: Plan; rows: ResultTable }> => {
    const started = performance.now();
    const answer = await link.fetch(`${link.baseUrl}/plan-and-run.json`, {
      method: 'POST',
      headers: { ...headers(link), 'Content-Type': 'application/json' },
      body: JSON.stringify(planner.planRequest(query)),
      ...(signal ? { signal } : {}),
    }).catch((e: unknown) => {
      if (signal?.aborted) throw e;
      throw new QueryFailure(new Error(`the engine did not answer: ${String(e)}`), query);
    });
    if (!answer.ok || (answer.headers.get('Content-Type') ?? '').startsWith('application/json')) {
      // no rows: the planner refused (its own answer, read as it reads every refusal), or its SQL did (and why)
      const text = await answer.text();
      const said = (() => { try { return JSON.parse(text) as { plan?: string; error?: string }; } catch { return {}; } })();
      if (said.plan === undefined) throw new QueryFailure(new Error(`the engine answered ${answer.status}: ${text}`), query);
      const plan = readPlan(said.plan, query);
      throw new QueryFailure(new Error(said.error ?? `the engine answered ${answer.status}`), query, plan.sql);
    }
    // a body cut short or not Arrow: said as every other failure here is, never thrown raw at the cube
    const { table, metadata } = await answer.arrayBuffer().then((bytes) => arrowIpcAnswer(new Uint8Array(bytes))).catch((e: unknown) => {
      if (signal?.aborted) throw e;
      throw new QueryFailure(new Error(`the engine's rows could not be read: ${e instanceof Error ? e.message : String(e)}`), query);
    });
    const said = metadata.get('legend.plan');
    if (said === undefined) throw new QueryFailure(new Error('the engine answered rows without the plan they are of'), query);
    const plan = readPlan(said, query);
    try {
      const rows = typedByPlan(toRawTable(table, epoch, performance.now() - started), plan);
      return { plan, rows: { ...rows, receipt: { plane: 'engine', where: `the engine at ${hostOf(link.baseUrl)}` } } };
    } catch (e) {
      throw new QueryFailure(e, query, plan.sql);
    }
  };
  return new PlanAndRun({
    name: 'python',
    planner,
    planAndRun,
    // the whole result as one chunk: streamed back in chunks is a follow-up (§15.7, 3)
    stream: async (query, epoch, onChunk, signal) => { onChunk((await planAndRun(query, epoch, signal)).rows); },
  });
}

/** A frame as Python says it is now (`cube.json`): the model Python wrote for it, and its version. */
async function frameOf(link: PythonLink, name: string): Promise<FrameModel> {
  const said = JSON.parse(await asked(link, `cube.json?table=${encodeURIComponent(name)}`)) as {
    model: string; runtime: string; source: ValueSpecification; version: number;
  };
  return { model: said.model, runtime: said.runtime, source: said.source, version: said.version };
}

/** What Python's host offers the page (`services.json`), or why it did not say. */
export async function askedOffers(link: PythonLink): Promise<PythonOffers> {
  const said = JSON.parse(await asked(link, 'services.json')) as {
    files?: unknown; remote?: unknown; pages?: { where?: unknown; user?: unknown }; plane?: unknown; planes?: unknown;
  };
  if (typeof said.files !== 'boolean' || typeof said.remote !== 'boolean') {
    throw new Error(`the engine did not say which of a page's files it reads: ${JSON.stringify(said)}`);
  }
  if (typeof said.pages?.where !== 'string' || typeof said.pages.user !== 'string') {
    throw new Error(`the engine did not say where it keeps saved pages: ${JSON.stringify(said)}`);
  }
  if (!Array.isArray(said.planes)) throw new Error(`the engine did not say which planes it offers: ${JSON.stringify(said)}`);
  return {
    files: said.files, remote: said.remote, pages: { where: said.pages.where, user: said.pages.user },
    plane: planeOf(said.plane), planes: said.planes.map(planeOf),
  };
}

/** A call carrying JSON to Python: its answer's text, or -- refused -- Python's reason. */
async function posted(link: PythonLink, path: string, body: unknown): Promise<string> {
  const answer = await link.fetch(`${link.baseUrl}/${path}`, {
    method: 'POST',
    headers: { ...headers(link), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).catch((e: unknown) => { throw new Error(`the engine did not answer: ${String(e)}`); });
  const text = await answer.text();
  if (!answer.ok) throw new Error(text || `the engine answered ${answer.status}`);
  return text;
}

/**
 * Python's DuckDB as the page's database: its frames, and -- where Python takes them -- a page's file, read there by
 * DataCube's own code into the table the page named (the page's table names are its own: no grid's is replaced).
 */
export function pythonDatabase(link: PythonLink, offers: PythonOffers): DatabaseService {
  return {
    frame: (name) => frameOf(link, name),
    ...(offers.files ? {
      ingestFile: async (file: File, options: { readonly table: string }) => {
        // the bytes the tab's DuckDB is given (upload.ts fileParts: a text file decoded as the tab always has), sent a
        // part at a time as they are read (file-part.json, each saying where it starts), so no file is too big to send
        // and none is ever whole in the page; then read there, as one file (file.json)
        let upload: string | undefined;
        let at = 0;
        const send = async (part: Uint8Array): Promise<void> => {
          const said = JSON.parse(await posted(link, 'file-part.json', { ...(upload ? { upload } : {}), at, bytes: base64(part) })) as
            { upload: string };
          upload = said.upload;
          at += part.length;
        };
        for await (const part of fileParts(file)) await send(part);
        // an empty file is an upload too: Python reads it, and says what an empty file is
        if (upload === undefined) await send(new Uint8Array(0));
        return JSON.parse(await posted(link, 'file.json', { upload, name: file.name, table: options.table })) as UploadResult;
      },
      forgetFile: async (fileName: string, table: string) => { await posted(link, 'forget.json', { name: fileName, table }); },
    } : {}),
    // a remote file, read by Python's DuckDB through the httpfs extension Python carries; Python reads it only from a
    // remote URL, never from its own machine's files
    ...(offers.remote ? {
      mountRemote: async (url: string, s3: S3Credentials | undefined, name: string) =>
        JSON.parse(await posted(link, 'remote.json', { url, name, ...(s3 ? { s3 } : {}) })) as InferredModel,
    } : {}),
  };
}

/**
 * THE SAVED PAGES, in this user's folder (step 2d): the store's rules the page's own (RuleStore), over the records
 * Python keeps -- the browser's IndexedDB, in Python's terms (§6, Q1). Kept on disk, so nothing more to ask for to keep
 * them.
 */
function pythonPages(link: PythonLink, offers: PythonOffers): PageService {
  const carry = async (call: HostRecordsCall): Promise<unknown> => {
    const answer = await link.fetch(`${link.baseUrl}/saved-pages.json`, {
      method: 'POST',
      headers: { ...headers(link), 'Content-Type': 'application/json' },
      body: JSON.stringify(call),
    }).catch((e: unknown) => { throw new Error(`the engine did not answer: ${String(e)}`); });
    // every answer read whole, an empty one too: a response left unread is cancelled by the browser (it reported the
    // put as aborted, though Python had kept the record)
    const text = await answer.text();
    if (!answer.ok) throw new Error(`the engine refused the saved pages' ${call.call}: ${answer.status} ${text}`);
    // a record, the records, or -- none found, or put and removed -- nothing
    return text === '' ? undefined : (JSON.parse(text) as unknown) ?? undefined;
  };
  return {
    store: new RuleStore(new HostRecords(carry), offers.pages.user),
    where: `in ${offers.pages.where}`,
    memoryOnly: false,
    persist: async () => true,
  };
}

/** The page Python serves (`page.json`), as the protocol's exact JSON reads it: a calculated column's `12.30` stays. */
function livePage(link: PythonLink, key: string): LivePage {
  const at = `page.json?page=${encodeURIComponent(key)}`;
  return {
    read: async () => {
      const said = fromJson(await asked(link, at)) as { version: unknown; page: unknown };
      const version = said.version instanceof ExactNumber && said.version.isInteger ? Number(said.version.text) : NaN;
      if (!Number.isSafeInteger(version)) throw new Error('the engine said no version of the page');
      return { version, page: readPage(said.page) };
    },
    report: async (version: number, page: PageDocument) => {
      const answer = await link.fetch(`${link.baseUrl}/${at}&version=${version}`, {
        method: 'POST',
        headers: { ...headers(link), 'Content-Type': 'application/json' },
        body: pageToJson(page),
      });
      if (!answer.ok) throw new Error(`the engine refused the page: ${answer.status} ${await answer.text()}`);
    },
  };
}

/**
 * The versions Python says now (`version.json`) of what it shows -- a page's own and each of its frames', or one
 * frame's -- or why it does not say (the page or the frame closed, the engine gone).
 */
export async function askedVersions(link: PythonLink, shows: PythonShows): Promise<Versions | string> {
  const query = 'page' in shows ? `page=${encodeURIComponent(shows.page)}` : `table=${encodeURIComponent(shows.frame)}`;
  const answer = await link.fetch(`${link.baseUrl}/version.json?${query}`, { headers: headers(link) }).catch(() => undefined);
  if (answer === undefined) return 'the engine stopped';
  if (answer.status === 404) return 'page' in shows ? 'the page was closed' : 'the frame was closed';
  if (!answer.ok) return `the engine answered ${answer.status}`;
  const said = (await answer.json()) as { version: number; frames?: Record<string, number> };
  return 'page' in shows ? { version: said.version, frames: said.frames ?? {} } : { frames: { [shows.frame]: said.version } };
}

/** Python's services, over its link. */
export function pythonServices(o: PythonServicesOptions): Services {
  const { link } = o;
  return {
    engine: {
      plane: pythonPlanes(o.offers),
      label: o.offers.plane.word,
      // planned and run by Python: the page sends Pure, and the rows come back (R4). A mapping or enumerations would
      // change the plan, and Python plans over the model it wrote: refused rather than dropped. Bit columns are a
      // reading of legend-engine's types (relation-type.ts, S23), not of legend-lite's, which Python plans with
      rows: (model, runtime, how) => {
        if (how?.mapping !== undefined || (how?.enumerations?.length ?? 0) > 0) {
          throw new Error('Python plans a source over the model it wrote: it takes no mapping or enumerations from the page');
        }
        // PlanAndRun on Python (§15): planned against the model Python holds and run on its DuckDB, in one call
        return { runner: pythonRunner(link, model, runtime) };
      },
      database: pythonDatabase(link, o.offers),
    },
    pages: Promise.resolve(pythonPages(link, o.offers)),
    live: {
      watch: o.watch,
      ...('page' in o.shows ? { page: livePage(link, o.shows.page) } : {}),
    },
    browser: o.browser,
  };
}
