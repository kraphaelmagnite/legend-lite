// URL and URLSearchParams where an embedding engine has none: whatwg-url, the URL standard's reference implementation
// (src/headless/web.ts says why the web layer is the host's). Loaded after web.ts, whose TextEncoder it uses as it loads.

import { URL, URLSearchParams } from 'whatwg-url';

/** URL and URLSearchParams added to `g` where it has none: an engine's own are kept. */
export function installUrl(g: Record<string, unknown> = globalThis as unknown as Record<string, unknown>): void {
  for (const [name, value] of [['URL', URL], ['URLSearchParams', URLSearchParams]] as const) {
    if (g[name] === undefined) {
      Object.defineProperty(g, name, { value, writable: true, configurable: true, enumerable: false });
    }
  }
}
