// A FILE FROM THE PAGE AROUND THE CUBE: an app that shows DataCube in a frame hands it rows it fetched itself (a query
// its own server ran) as a file, and the cube opens it as it opens a picked one (Open a File…). The cube says it is
// ready, the host answers with the file's name and bytes, and the cube says how the open went.
//
// Only the frame's parent is heard, and only from the cube's own origin or one the deployment names (config.json
// `embedOrigins`): a file is data the cube shows as fact, so who may hand one over is the deployment's to say, never
// the address's.

/** The host's one message: the file, by name (its extension is its format: .csv, .parquet, .json) and bytes. */
export interface OpenFileMessage {
  readonly type: 'datacube:open-file';
  readonly name: string;
  readonly bytes: ArrayBuffer | Uint8Array;
  readonly mime?: string;
}

/** What the cube tells its host. */
export type ToHost =
  | { readonly type: 'datacube:ready' }
  | { readonly type: 'datacube:opened'; readonly name: string }
  | { readonly type: 'datacube:error'; readonly message: string };

/** The part of a window the link uses: declared so a test hands in its own. */
export interface FrameWindow {
  readonly parent: { postMessage(message: ToHost, targetOrigin: string): void };
  readonly location: { readonly origin: string };
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
  removeEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
}

/** The cube's side of the link, once listening. */
export interface HostLink {
  /** The first well-formed file an allowed host hands over. */
  readonly file: Promise<File>;
  /** Tell the host the file opened, or -- a reason -- that it did not. */
  told(problem: string | undefined): void;
  /** Stop listening. */
  close(): void;
}

/**
 * The origins a file is taken from: the cube's own, and each the deployment names, as origins. A malformed one, or
 * `*` (anyone), is left out rather than guessed at.
 */
export function allowedOrigins(own: string, named: readonly string[]): string[] {
  const origins = new Set([own]);
  for (const n of named) {
    if (n === '*') continue;
    try {
      const { origin } = new URL(n);
      if (origin !== 'null') origins.add(origin);
    } catch {
      // not a URL: left out
    }
  }
  return [...origins];
}

function isOpenFile(data: unknown): data is OpenFileMessage {
  if (typeof data !== 'object' || data === null) return false;
  const m = data as Record<string, unknown>;
  return m['type'] === 'datacube:open-file';
}

/** Why a message of the right type cannot be opened, or undefined when it can. */
function malformed(m: OpenFileMessage): string | undefined {
  if (typeof m.name !== 'string' || m.name.trim() === '') return 'the file has no name';
  if (!(m.bytes instanceof ArrayBuffer) && !(m.bytes instanceof Uint8Array)) return `${m.name} came without its bytes`;
  if (m.mime !== undefined && typeof m.mime !== 'string') return `${m.name}'s type is not text`;
  return undefined;
}

/**
 * LISTEN FOR THE HOST'S FILE, and say the cube is ready to every origin allowed (a message to another origin is
 * dropped by the browser, so only the real host hears it). Messages from anyone but the parent, or from an origin not
 * allowed, are not read; a malformed one is answered with why, and the link keeps listening.
 */
export function hostFile(win: FrameWindow, embedOrigins: readonly string[]): HostLink {
  const origins = allowedOrigins(win.location.origin, embedOrigins);
  /** The file taken, and the origin it came from: the one the cube answers from then on. */
  let taken: { readonly origin: string; readonly name: string } | undefined;
  let resolve!: (file: File) => void;
  const file = new Promise<File>((r) => {
    resolve = r;
  });
  const listener = (event: MessageEvent): void => {
    if (taken !== undefined || event.source !== win.parent || !origins.includes(event.origin)) return;
    if (!isOpenFile(event.data)) return;
    const why = malformed(event.data);
    if (why !== undefined) {
      win.parent.postMessage({ type: 'datacube:error', message: why }, event.origin);
      return;
    }
    const { name, bytes, mime } = event.data;
    taken = { origin: event.origin, name };
    win.removeEventListener('message', listener);
    resolve(new File([bytes as BlobPart], name, mime ? { type: mime } : {}));
  };
  win.addEventListener('message', listener);
  for (const origin of origins) win.parent.postMessage({ type: 'datacube:ready' }, origin);
  return {
    file,
    told: (problem) => {
      if (taken === undefined) return;
      win.parent.postMessage(problem === undefined
        ? { type: 'datacube:opened', name: taken.name }
        : { type: 'datacube:error', message: problem }, taken.origin);
    },
    close: () => win.removeEventListener('message', listener),
  };
}
