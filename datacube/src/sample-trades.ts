// The demo's sample trades (demo/boot.ts, a page that opens with no source): rows made in the tab as Arrow data, the
// table the demo's model reads (demo/trades.pure's TRADES), put in by DuckDB's Arrow reader -- no SQL statement makes
// them (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5). Row i's values are those DuckDB's own `range()` made of it before,
// value for value (test/sample-trades.test.ts holds them to that statement).

import { arrowIpcStream } from '../../engine-client/src/arrow-ipc.ts';

/** How many trades the demo makes. */
export const SAMPLE_TRADES = 200_000;

const REGIONS = ['EMEA', 'AMER', 'APAC'];
const DESKS = ['Rates', 'Credit', 'FX', 'Equity', 'Commodities'];

/** `rows` sample trades as one Arrow IPC stream: region, desk, year, qtr, book, notional, pnl, qty. */
export function sampleTrades(rows = SAMPLE_TRADES): Uint8Array {
  return arrowIpcStream(rows, {
    region: { text: REGIONS, pick: (i) => i % 3 },
    desk: { text: DESKS, pick: (i) => Math.floor(i / 3) % 5 },
    year: { integers: (i) => 2021 + (Math.floor(i / 15) % 5) },
    qtr: { text: ['Q1', 'Q2', 'Q3', 'Q4'], pick: (i) => Math.floor(i / 75) % 4 },
    book: { text: ['Book 1', 'Book 2', 'Book 3', 'Book 4'], pick: (i) => Math.floor(i / 300) % 4 },
    notional: { doubles: (i) => ((i * 7919) % 1000000) / 100.0 },
    pnl: { doubles: (i) => ((i * 104729) % 200000) / 100.0 - 1000.0 },
    qty: { integers: (i) => ((i * 31) % 97) + 1 },
  });
}
