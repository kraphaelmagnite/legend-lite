// A headless bundle's FIRST import: the web layer in place before any module that needs it as it loads (apache-arrow
// and whatwg-url each make a TextDecoder or a TextEncoder at load), and, inside the host, crypto.subtle.digest over its
// calls. ES modules are evaluated in import order, so the entry imports this, then install-url.ts, then the rest.

import { installDigest } from './calls.ts';
import { installWeb } from './web.ts';

installWeb();
const host = (globalThis as { legendLiteHost?: Parameters<typeof installDigest>[0] }).legendLiteHost;
if (host !== undefined) installDigest(host);
