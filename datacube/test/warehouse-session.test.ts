// Leg B / B6: the warehouse session on the host page (docs/DATACUBE_LEG_B_STATE_OWNER_2026_09_28.md).
// A signed-in session renewed must reach the cube already open (P2-297); signing in is one step
// with its table list, so a failed listing never leaves one user's tables beside another's
// session (P2-334).

import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it } from 'node:test';

import { WarehouseEngine, type WarehouseSession } from '../../engine-client/src/warehouse.ts';

const session = (token: string, principal = 'alice'): WarehouseSession =>
  ({ baseUrl: 'https://wh.example', token, principal, expiresAt: '2099-01-01T00:00:00Z' });

let sent: { url: string; auth: string | null }[];
let reply: (url: string) => Response;
const realFetch = globalThis.fetch;

beforeEach(() => {
  sent = [];
  // every statement fails at once: one request each, whose token is what these tests read
  reply = () => new Response(JSON.stringify({ statementId: 's', state: 'failed',
    error: { code: 'TEST', message: 'answered by the test' } }), { status: 200 });
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    sent.push({ url, auth: headers.get('Authorization') });
    return reply(url);
  }) as typeof fetch;
});
afterEach(() => {
  globalThis.fetch = realFetch;
});

describe('a renewed sign-in reaches the open cube (P2-297)', () => {
  it('a sign-in as SOMEONE ELSE never becomes the open cube\'s: it is refused, said', () => {
    const engine = new WarehouseEngine(session('tok1', 'alice'));
    assert.throws(() => engine.renew(session('tok9', 'bob')), /alice/);
    assert.equal(engine.principal, 'alice');
  });
});

