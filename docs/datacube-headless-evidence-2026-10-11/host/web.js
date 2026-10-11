// The small web layer an embedding host gives QuickJS (which has none of it): TextEncoder/TextDecoder (UTF-8),
// EventTarget/Event, DOMException, AbortController/AbortSignal -- each the WHATWG/DOM behaviour DataCube relies on.
// URL comes from whatwg-url (url.js), loaded after this, since it needs TextEncoder.
(() => {
  if (typeof globalThis.TextEncoder === 'undefined') {
    class TextEncoder {
      get encoding() { return 'utf-8'; }
      encode(input = '') {
        const s = String(input);
        const out = [];
        for (let i = 0; i < s.length; i++) {
          let c = s.charCodeAt(i);
          if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
            const d = s.charCodeAt(i + 1);
            if (d >= 0xdc00 && d <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; }
            else c = 0xfffd;
          } else if (c >= 0xd800 && c <= 0xdfff) {
            c = 0xfffd;
          }
          if (c < 0x80) out.push(c);
          else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
          else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
          else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        }
        return new Uint8Array(out);
      }
      encodeInto(s, dest) {
        const b = this.encode(s);
        const n = Math.min(b.length, dest.length);
        dest.set(b.subarray(0, n));
        return { read: s.length, written: n };
      }
    }
    class TextDecoder {
      #fatal; #ignoreBOM;
      constructor(label = 'utf-8', options = {}) {
        const l = String(label).toLowerCase();
        if (l !== 'utf-8' && l !== 'utf8' && l !== 'unicode-1-1-utf-8') throw new RangeError(`unsupported encoding: ${label}`);
        this.#fatal = Boolean(options.fatal);
        this.#ignoreBOM = Boolean(options.ignoreBOM);
      }
      get encoding() { return 'utf-8'; }
      decode(input = new Uint8Array(0)) {
        const b = input instanceof Uint8Array ? input : ArrayBuffer.isView(input)
          ? new Uint8Array(input.buffer, input.byteOffset, input.byteLength) : new Uint8Array(input);
        let s = '';
        let i = 0;
        if (!this.#ignoreBOM && b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) i = 3;
        const bad = () => { if (this.#fatal) throw new TypeError('The encoded data was not valid utf-8'); return 0xfffd; };
        const parts = [];
        while (i < b.length) {
          const x = b[i];
          let c;
          let need = 0;
          let min = 0;
          if (x < 0x80) { c = x; } else if (x >= 0xc2 && x <= 0xdf) { c = x & 31; need = 1; min = 0x80; }
          else if (x >= 0xe0 && x <= 0xef) { c = x & 15; need = 2; min = 0x800; }
          else if (x >= 0xf0 && x <= 0xf4) { c = x & 7; need = 3; min = 0x10000; } else { parts.push(bad()); i++; continue; }
          let j = 1;
          for (; j <= need; j++) {
            const y = b[i + j];
            if (y === undefined || (y & 0xc0) !== 0x80) break;
            c = (c << 6) | (y & 63);
          }
          if (j <= need || c < min || c > 0x10ffff || (c >= 0xd800 && c <= 0xdfff)) { parts.push(bad()); i += Math.max(1, j); continue; }
          parts.push(c);
          i += need + 1;
          if (parts.length > 8192) { s += String.fromCodePoint(...parts); parts.length = 0; }
        }
        return s + String.fromCodePoint(...parts);
      }
    }
    globalThis.TextEncoder = TextEncoder;
    globalThis.TextDecoder = TextDecoder;
  }

  if (typeof globalThis.EventTarget === 'undefined') {
    class Event {
      constructor(type, init = {}) {
        this.type = String(type);
        this.bubbles = Boolean(init.bubbles);
        this.cancelable = Boolean(init.cancelable);
        this.defaultPrevented = false;
        this.target = null;
        this.currentTarget = null;
        this.timeStamp = Date.now();
        this.isTrusted = false;
      }
      preventDefault() { if (this.cancelable) this.defaultPrevented = true; }
      stopPropagation() {}
      stopImmediatePropagation() { this.__stopped = true; }
    }
    class EventTarget {
      #listeners = new Map();
      addEventListener(type, callback, options) {
        if (!callback) return;
        const once = typeof options === 'object' && options !== null && Boolean(options.once);
        const signal = typeof options === 'object' && options !== null ? options.signal : undefined;
        if (signal && signal.aborted) return;
        const list = this.#listeners.get(type) ?? [];
        if (list.some((l) => l.callback === callback)) return;
        list.push({ callback, once });
        this.#listeners.set(type, list);
        if (signal) signal.addEventListener('abort', () => this.removeEventListener(type, callback));
      }
      removeEventListener(type, callback) {
        const list = this.#listeners.get(type);
        if (list) this.#listeners.set(type, list.filter((l) => l.callback !== callback));
      }
      dispatchEvent(event) {
        if (event.target === null) event.target = this;
        event.currentTarget = this;
        const handler = this[`on${event.type}`];
        if (typeof handler === 'function') handler.call(this, event);
        for (const l of [...(this.#listeners.get(event.type) ?? [])]) {
          if (l.once) this.removeEventListener(event.type, l.callback);
          if (typeof l.callback === 'function') l.callback.call(this, event); else l.callback.handleEvent(event);
          if (event.__stopped) break;
        }
        event.currentTarget = null;
        return !event.defaultPrevented;
      }
    }
    globalThis.Event = Event;
    globalThis.EventTarget = EventTarget;
  }

  if (typeof globalThis.DOMException === 'undefined') {
    const CODES = { IndexSizeError: 1, NotFoundError: 8, NotSupportedError: 9, InvalidStateError: 11, SyntaxError: 12,
      NetworkError: 19, AbortError: 20, TimeoutError: 23, DataCloneError: 25 };
    class DOMException extends Error {
      constructor(message = '', name = 'Error') {
        super(message);
        Object.defineProperty(this, 'name', { value: String(name), configurable: true, writable: true });
      }
      get code() { return CODES[this.name] ?? 0; }
    }
    globalThis.DOMException = DOMException;
  }

  if (typeof globalThis.AbortController === 'undefined') {
    const abort = (signal, reason) => {
      if (signal.aborted) return;
      signal.__aborted = true;
      signal.__reason = reason === undefined ? new DOMException('This operation was aborted', 'AbortError') : reason;
      signal.dispatchEvent(new Event('abort'));
    };
    class AbortSignal extends EventTarget {
      constructor() {
        super();
        this.__aborted = false;
        this.__reason = undefined;
        this.onabort = null;
      }
      get aborted() { return this.__aborted; }
      get reason() { return this.__reason; }
      throwIfAborted() { if (this.__aborted) throw this.__reason; }
      static abort(reason) { const s = new AbortSignal(); abort(s, reason); return s; }
      static timeout(ms) {
        const s = new AbortSignal();
        setTimeout(() => abort(s, new DOMException('The operation was aborted due to timeout', 'TimeoutError')), ms);
        return s;
      }
      static any(signals) {
        const s = new AbortSignal();
        for (const x of signals) {
          if (x.aborted) { abort(s, x.reason); return s; }
        }
        for (const x of signals) x.addEventListener('abort', () => abort(s, x.reason), { once: true });
        return s;
      }
    }
    class AbortController {
      #signal = new AbortSignal();
      get signal() { return this.#signal; }
      abort(reason) { abort(this.#signal, reason); }
    }
    globalThis.AbortSignal = AbortSignal;
    globalThis.AbortController = AbortController;
  }
})();
