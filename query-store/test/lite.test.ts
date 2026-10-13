// legend-lite's server (`//core:server --query-store`), held to the one suite: the store the page
// answers is the store a server answers.

import { conformance } from './conformance.ts';
import { liteServer } from './lite-server.ts';

const server = liteServer();

conformance("legend-lite's server", () => ({ api: server.api(), fetch: globalThis.fetch.bind(globalThis), user: 'anonymous' }));
