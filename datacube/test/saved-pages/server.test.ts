// legend-lite's server (`//core:server --query-store DIR`: the saved pages in DIR/dataCube), held to the one suite of
// saved pages: the store the page answers is the store a server answers (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B6).

import { liteServer } from '../../../query-store/test/lite-server.ts';
import { conformance } from './conformance.ts';

const server = liteServer();

conformance("legend-lite's server", () => ({ api: server.api(), fetch: globalThis.fetch.bind(globalThis), user: 'anonymous' }));
