// The web platform an embedding host gives DataCube's non-screen code where no browser does (Python's V8:
// docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §3): the few interfaces that code uses, each written to its
// standard's own algorithm -- UTF-8 by the Encoding standard, events and abort signals by the DOM standard, base64 by
// HTML's forgiving-base64 -- and held to the built-ins of a conformant engine, case for case, by
// test/headless-web.test.ts. URL and URLSearchParams are whatwg-url, the URL standard's reference implementation
// (url.ts: it makes a TextEncoder as it loads, so it is installed after this). The fetch API is left out: nothing
// DataCube does headless speaks HTTP.
//
// `installWeb` adds only what the engine lacks, so where an engine has its own (Node, a browser) nothing changes. This
// module imports nothing: a headless bundle evaluates it (install-web.ts) before any module that needs it as it loads.

// ---- the Encoding standard: UTF-8 (https://encoding.spec.whatwg.org/)

const REPLACEMENT = 0xfffd;

/** The labels of UTF-8 (the Encoding standard's table), ASCII whitespace trimmed and lowercased before matching. */
const UTF8_LABELS = new Set(['unicode-1-1-utf-8', 'unicode11utf8', 'unicode20utf8', 'utf-8', 'utf8', 'x-unicode20utf8']);

export class TextEncoder {
  get encoding(): 'utf-8' {
    return 'utf-8';
  }

  /** `input` converted to a scalar-value string (a lone surrogate becomes U+FFFD), then UTF-8 encoded. */
  encode(input: string = ''): Uint8Array {
    const s = String(input);
    const out = new Uint8Array(s.length * 3);
    let n = 0;
    for (let i = 0; i < s.length; i++) {
      const c = scalarAt(s, i);
      if (c > 0xffff) i++;
      n = put(out, n, c);
    }
    return out.slice(0, n);
  }

  /** As much of `source` as fits in `destination`, whole scalar values only: the code units read and bytes written. */
  encodeInto(source: string, destination: Uint8Array): { read: number; written: number } {
    const s = String(source);
    let read = 0;
    let written = 0;
    for (let i = 0; i < s.length; i++) {
      const c = scalarAt(s, i);
      const size = c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
      if (written + size > destination.length) break;
      written = put(destination, written, c);
      const units = c > 0xffff ? 2 : 1;
      read += units;
      i += units - 1;
    }
    return { read, written };
  }
}

/** The scalar value at `i`: a surrogate pair's code point, a lone surrogate as U+FFFD. */
function scalarAt(s: string, i: number): number {
  const c = s.charCodeAt(i);
  if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
    const d = s.charCodeAt(i + 1);
    if (d >= 0xdc00 && d <= 0xdfff) return 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00);
  }
  return c >= 0xd800 && c <= 0xdfff ? REPLACEMENT : c;
}

/** `c`'s UTF-8 bytes into `out` at `n`; the next free index. */
function put(out: Uint8Array, n: number, c: number): number {
  if (c < 0x80) {
    out[n++] = c;
  } else if (c < 0x800) {
    out[n++] = 0xc0 | (c >> 6);
    out[n++] = 0x80 | (c & 63);
  } else if (c < 0x10000) {
    out[n++] = 0xe0 | (c >> 12);
    out[n++] = 0x80 | ((c >> 6) & 63);
    out[n++] = 0x80 | (c & 63);
  } else {
    out[n++] = 0xf0 | (c >> 18);
    out[n++] = 0x80 | ((c >> 12) & 63);
    out[n++] = 0x80 | ((c >> 6) & 63);
    out[n++] = 0x80 | (c & 63);
  }
  return n;
}

export class TextDecoder {
  readonly #fatal: boolean;
  readonly #ignoreBOM: boolean;

  constructor(label: string = 'utf-8', options: { fatal?: boolean; ignoreBOM?: boolean } = {}) {
    if (!UTF8_LABELS.has(String(label).replace(/^[\t\n\f\r ]+|[\t\n\f\r ]+$/g, '').toLowerCase())) {
      // this host knows UTF-8 alone; a label the standard maps to another encoding is not silently read as UTF-8
      throw new RangeError(`The encoding label provided ('${label}') is not supported here: UTF-8 alone`);
    }
    this.#fatal = Boolean(options.fatal);
    this.#ignoreBOM = Boolean(options.ignoreBOM);
  }

  get encoding(): 'utf-8' {
    return 'utf-8';
  }

  get fatal(): boolean {
    return this.#fatal;
  }

  get ignoreBOM(): boolean {
    return this.#ignoreBOM;
  }

  /** The Encoding standard's UTF-8 decoder over the whole of `input` (a streaming decode is refused, not half-done). */
  decode(input?: ArrayBuffer | ArrayBufferView, options: { stream?: boolean } = {}): string {
    if (options.stream) throw new TypeError("this host's TextDecoder decodes whole inputs: { stream: true } is not supported");
    const b = input === undefined ? new Uint8Array(0)
      : ArrayBuffer.isView(input) ? new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
        : new Uint8Array(input);
    const units: number[] = [];
    let s = '';
    const emit = (c: number): void => {
      if (c > 0xffff) units.push(0xd800 + ((c - 0x10000) >> 10), 0xdc00 + ((c - 0x10000) & 0x3ff));
      else units.push(c);
      if (units.length >= 8192) {
        s += String.fromCharCode(...units);
        units.length = 0;
      }
    };
    const error = (): void => {
      if (this.#fatal) throw new TypeError('The encoded data was not valid for encoding utf-8');
      emit(REPLACEMENT);
    };
    let i = !this.#ignoreBOM && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf ? 3 : 0;
    let needed = 0;
    let seen = 0;
    let code = 0;
    let lower = 0x80;
    let upper = 0xbf;
    while (i < b.length) {
      const byte = b[i]!;
      if (needed === 0) {
        i++;
        if (byte <= 0x7f) {
          emit(byte);
        } else if (byte >= 0xc2 && byte <= 0xdf) {
          needed = 1;
          code = byte & 0x1f;
        } else if (byte >= 0xe0 && byte <= 0xef) {
          if (byte === 0xe0) lower = 0xa0;
          if (byte === 0xed) upper = 0x9f;
          needed = 2;
          code = byte & 0xf;
        } else if (byte >= 0xf0 && byte <= 0xf4) {
          if (byte === 0xf0) lower = 0x90;
          if (byte === 0xf4) upper = 0x8f;
          needed = 3;
          code = byte & 0x7;
        } else {
          error();
        }
        continue;
      }
      if (byte < lower || byte > upper) {
        // the sequence so far is one error; this byte is read again, as the start of what follows
        needed = seen = code = 0;
        lower = 0x80;
        upper = 0xbf;
        error();
        continue;
      }
      i++;
      lower = 0x80;
      upper = 0xbf;
      code = (code << 6) | (byte & 0x3f);
      seen++;
      if (seen === needed) {
        emit(code);
        needed = seen = code = 0;
      }
    }
    if (needed !== 0) error();
    return s + String.fromCharCode(...units);
  }
}

// ---- the DOM standard: DOMException, Event, EventTarget, AbortController, AbortSignal (https://dom.spec.whatwg.org/)

/** WebIDL's legacy codes, by name; any other name's code is 0. */
const LEGACY_CODES: Readonly<Record<string, number>> = {
  IndexSizeError: 1, HierarchyRequestError: 3, WrongDocumentError: 4, InvalidCharacterError: 5,
  NoModificationAllowedError: 7, NotFoundError: 8, NotSupportedError: 9, InUseAttributeError: 10,
  InvalidStateError: 11, SyntaxError: 12, InvalidModificationError: 13, NamespaceError: 14, InvalidAccessError: 15,
  TypeMismatchError: 17, SecurityError: 18, NetworkError: 19, AbortError: 20, URLMismatchError: 21,
  QuotaExceededError: 22, TimeoutError: 23, InvalidNodeTypeError: 24, DataCloneError: 25,
};

export class DOMException extends Error {
  constructor(message: string = '', name: string = 'Error') {
    super(String(message));
    Object.defineProperty(this, 'name', { value: String(name), writable: false, enumerable: false, configurable: true });
  }

  get code(): number {
    return LEGACY_CODES[this.name] ?? 0;
  }
}

export class Event {
  static readonly NONE = 0;
  static readonly CAPTURING_PHASE = 1;
  static readonly AT_TARGET = 2;
  static readonly BUBBLING_PHASE = 3;

  readonly type: string;
  readonly bubbles: boolean;
  readonly cancelable: boolean;
  readonly composed: boolean;
  readonly timeStamp: number;
  readonly isTrusted = false;
  /** @internal set while the event is dispatched */
  target: EventTarget | null = null;
  /** @internal */
  currentTarget: EventTarget | null = null;
  /** @internal */
  eventPhase = Event.NONE;
  #canceled = false;
  /** @internal */
  stopped = false;
  /** @internal set while a passive listener runs */
  inPassive = false;

  constructor(type: string, init: { bubbles?: boolean; cancelable?: boolean; composed?: boolean } = {}) {
    if (arguments.length === 0) throw new TypeError("Failed to construct 'Event': 1 argument required");
    this.type = String(type);
    this.bubbles = Boolean(init.bubbles);
    this.cancelable = Boolean(init.cancelable);
    this.composed = Boolean(init.composed);
    this.timeStamp = typeof performance === 'undefined' ? Date.now() : performance.now();
  }

  get defaultPrevented(): boolean {
    return this.#canceled;
  }

  get returnValue(): boolean {
    return !this.#canceled;
  }

  preventDefault(): void {
    if (this.cancelable && !this.inPassive) this.#canceled = true;
  }

  stopPropagation(): void {
    // one target, no tree to propagate through: nothing further to stop
  }

  stopImmediatePropagation(): void {
    this.stopped = true;
  }
}

type Listener = ((event: Event) => void) | { handleEvent(event: Event): void };

interface Registered {
  readonly callback: Listener;
  readonly capture: boolean;
  readonly once: boolean;
  readonly passive: boolean;
  removed: boolean;
}

/** What `options` says, a boolean being `capture` alone (the DOM's "flatten more"). */
function flattened(options: unknown): { capture: boolean; once: boolean; passive: boolean; signal: AbortSignal | undefined } {
  if (typeof options !== 'object' || options === null) {
    return { capture: Boolean(options), once: false, passive: false, signal: undefined };
  }
  const o = options as { capture?: unknown; once?: unknown; passive?: unknown; signal?: AbortSignal };
  return { capture: Boolean(o.capture), once: Boolean(o.once), passive: Boolean(o.passive), signal: o.signal };
}

export class EventTarget {
  readonly #listeners = new Map<string, Registered[]>();

  addEventListener(type: string, callback: Listener | null, options?: boolean | object): void {
    if (callback === null || callback === undefined) return;
    const { capture, once, passive, signal } = flattened(options);
    if (signal?.aborted) return;
    const list = this.#listeners.get(String(type)) ?? [];
    if (list.some((l) => l.callback === callback && l.capture === capture)) return;
    const registered: Registered = { callback, capture, once, passive, removed: false };
    list.push(registered);
    this.#listeners.set(String(type), list);
    signal?.addEventListener('abort', () => this.removeEventListener(type, callback, { capture }));
  }

  removeEventListener(type: string, callback: Listener | null, options?: boolean | object): void {
    const capture = flattened(options).capture;
    const list = this.#listeners.get(String(type));
    if (!list) return;
    const at = list.findIndex((l) => l.callback === callback && l.capture === capture);
    if (at < 0) return;
    list[at]!.removed = true;
    list.splice(at, 1);
  }

  /** The event to this target's listeners, in the order added: false when one canceled it. */
  dispatchEvent(event: Event): boolean {
    if (!(event instanceof Event)) throw new TypeError("Failed to execute 'dispatchEvent': parameter 1 is not of type 'Event'");
    if (event.eventPhase !== Event.NONE) throw new DOMException('The event is already being dispatched', 'InvalidStateError');
    event.target = this;
    event.currentTarget = this;
    event.eventPhase = Event.AT_TARGET;
    try {
      // capture listeners first, then the others: at the target both run, in that order
      for (const capturing of [true, false]) {
        for (const l of [...(this.#listeners.get(event.type) ?? [])]) {
          if (event.stopped) break;
          if (l.removed || l.capture !== capturing) continue;
          if (l.once) this.removeEventListener(event.type, l.callback, { capture: l.capture });
          event.inPassive = l.passive;
          try {
            if (typeof l.callback === 'function') l.callback.call(this, event);
            else l.callback.handleEvent(event);
          } catch (e) {
            // the DOM reports a listener's exception and goes on to the next listener
            report(e);
          } finally {
            event.inPassive = false;
          }
        }
      }
    } finally {
      event.eventPhase = Event.NONE;
      event.currentTarget = null;
      event.stopped = false;
    }
    return !event.defaultPrevented;
  }
}

/** A listener's exception, reported as the platform reports one: to its `reportError`, else its console. */
function report(e: unknown): void {
  const g = globalThis as { reportError?: (e: unknown) => void; console?: { error(...a: unknown[]): void } };
  if (typeof g.reportError === 'function') g.reportError(e);
  else g.console?.error(e);
}

export class AbortSignal extends EventTarget {
  #aborted = false;
  #reason: unknown = undefined;
  #onabort: ((event: Event) => void) | null = null;
  /** The `onabort` handler's own listener: added where it is first set, among the others (HTML's event handlers). */
  readonly #handler = (event: Event): void => {
    this.#onabort?.call(this, event);
  };

  get onabort(): ((event: Event) => void) | null {
    return this.#onabort;
  }

  set onabort(handler: ((event: Event) => void) | null) {
    const value = typeof handler === 'function' ? handler : null;
    if (value === null) this.removeEventListener('abort', this.#handler);
    else if (this.#onabort === null) this.addEventListener('abort', this.#handler);
    this.#onabort = value;
  }

  get aborted(): boolean {
    return this.#aborted;
  }

  get reason(): unknown {
    return this.#reason;
  }

  throwIfAborted(): void {
    if (this.#aborted) throw this.#reason;
  }

  /** @internal the DOM's "signal abort" */
  signalAbort(reason: unknown): void {
    if (this.#aborted) return;
    this.#aborted = true;
    this.#reason = reason === undefined ? new DOMException('This operation was aborted', 'AbortError') : reason;
    this.dispatchEvent(new Event('abort'));
  }

  static abort(reason?: unknown): AbortSignal {
    const s = new AbortSignal();
    s.signalAbort(reason);
    return s;
  }

  static timeout(ms: number): AbortSignal {
    const s = new AbortSignal();
    setTimeout(() => s.signalAbort(new DOMException('The operation was aborted due to timeout', 'TimeoutError')), ms);
    return s;
  }

  static any(signals: Iterable<AbortSignal>): AbortSignal {
    const s = new AbortSignal();
    const all = [...signals];
    const first = all.find((x) => x.aborted);
    if (first) {
      s.signalAbort(first.reason);
      return s;
    }
    for (const x of all) x.addEventListener('abort', () => s.signalAbort(x.reason), { once: true });
    return s;
  }
}

export class AbortController {
  readonly #signal = new AbortSignal();

  get signal(): AbortSignal {
    return this.#signal;
  }

  abort(reason?: unknown): void {
    this.#signal.signalAbort(reason);
  }
}

// ---- HTML's forgiving-base64 (https://infra.spec.whatwg.org/#forgiving-base64)

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const CODES = Uint8Array.from(ALPHABET, (c) => c.charCodeAt(0));
/** Each character's value by its code (255: not of the alphabet). */
const VALUE = (() => {
  const values = new Uint8Array(128).fill(255);
  CODES.forEach((code, i) => { values[code] = i; });
  return values;
})();
const PAD = '='.charCodeAt(0);
/** Characters made into a string at a time: a call's arguments stay well within what V8 takes. */
const PIECE = 0x8000;

/**
 * Bytes as base64 (the host's own crossing of bytes, and `btoa`'s encoding). Written into character codes and made into
 * strings a piece at a time, joined once: a file's bytes (a page's file read in Python) are tens of megabytes, which one
 * concatenation per three bytes made a rope of millions of pieces.
 */
export function base64(bytes: Uint8Array): string {
  const codes = new Uint8Array(Math.ceil(bytes.length / 3) * 4);
  let o = 0;
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i]! << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    codes[o++] = CODES[(n >> 18) & 63]!;
    codes[o++] = CODES[(n >> 12) & 63]!;
    codes[o++] = i + 1 < bytes.length ? CODES[(n >> 6) & 63]! : PAD;
    codes[o++] = i + 2 < bytes.length ? CODES[n & 63]! : PAD;
  }
  const pieces: string[] = [];
  for (let p = 0; p < codes.length; p += PIECE) pieces.push(String.fromCharCode(...codes.subarray(p, p + PIECE)));
  return pieces.join('');
}

/** base64 as bytes, by the forgiving-base64 decode; a string it refuses throws InvalidCharacterError. */
export function fromBase64(text: string): Uint8Array {
  let s = String(text).replace(/[\t\n\f\r ]/g, '');
  if (s.length % 4 === 0) s = s.replace(/={1,2}$/, '');
  if (s.length % 4 === 1 || /[^A-Za-z0-9+/]/.test(s)) {
    throw new DOMException('The string to be decoded is not correctly encoded.', 'InvalidCharacterError');
  }
  const out = new Uint8Array(Math.floor((s.length * 3) / 4));
  let o = 0;
  let buffer = 0;
  let bits = 0;
  // by index and a table, not a Map per character: a file's bytes are tens of megabytes (the alphabet is checked above)
  for (let i = 0; i < s.length; i++) {
    buffer = ((buffer << 6) | VALUE[s.charCodeAt(i)]!) & 0xffffff;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (buffer >> bits) & 0xff;
    }
  }
  return out.subarray(0, o);
}

export function btoa(data: string): string {
  const s = String(data);
  const bytes = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c > 0xff) throw new DOMException('The string to be encoded contains characters outside of the Latin1 range.', 'InvalidCharacterError');
    bytes[i] = c;
  }
  return base64(bytes);
}

export function atob(data: string): string {
  let out = '';
  for (const b of fromBase64(data)) out += String.fromCharCode(b);
  return out;
}

// ---- installed where the engine has none

/** Each interface `g` lacks, added: an engine's own are kept. */
export function installWeb(g: Record<string, unknown> = globalThis as unknown as Record<string, unknown>): void {
  const give = (name: string, value: unknown): void => {
    if (g[name] === undefined) {
      Object.defineProperty(g, name, { value, writable: true, configurable: true, enumerable: false });
    }
  };
  give('TextEncoder', TextEncoder);
  give('TextDecoder', TextDecoder);
  give('DOMException', DOMException);
  give('Event', Event);
  give('EventTarget', EventTarget);
  give('AbortSignal', AbortSignal);
  give('AbortController', AbortController);
  give('atob', atob);
  give('btoa', btoa);
  // a microtask's exception reported as the platform reports one, never lost in a rejected promise
  give('queueMicrotask', (callback: () => void) => {
    void Promise.resolve().then(() => {
      try {
        callback();
      } catch (e) {
        report(e);
      }
    });
  });
  if (g['performance'] === undefined) {
    const origin = Date.now();
    give('performance', { timeOrigin: origin, now: () => Date.now() - origin });
  }
  const setTimeoutOf = g['setTimeout'] as ((f: () => void, ms?: number) => unknown) | undefined;
  const clearTimeoutOf = g['clearTimeout'] as ((id: unknown) => void) | undefined;
  if (g['setInterval'] === undefined && setTimeoutOf && clearTimeoutOf) {
    // an interval as a chain of timeouts on the engine's own timers, one id for the chain
    const chains = new Map<number, unknown>();
    let next = 1;
    give('setInterval', (callback: (...a: unknown[]) => void, ms?: number, ...args: unknown[]) => {
      const id = next++;
      const every = Math.max(0, Number(ms) || 0);
      const tick = (): void => {
        chains.set(id, setTimeoutOf(tick, every));
        callback(...args);
      };
      chains.set(id, setTimeoutOf(tick, every));
      return id;
    });
    give('clearInterval', (id: number) => {
      const t = chains.get(id);
      chains.delete(id);
      if (t !== undefined) clearTimeoutOf(t);
    });
  }
}
