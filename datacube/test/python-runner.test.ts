// A PAGE ON PYTHON, PLANNED ON A SERVER (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md G1): the page's runner asks Python
// alone -- its plan-and-run, and its pure/v1 for a query's types -- with the token, and reads the plan Python answers
// as the tab reads a server's (planner.ts readServerPlan). Python sends each call on to the server and runs the SQL
// itself (python/tests/test_upstream.py runs the rows against a live server).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { col, element, fn, lambda, toJson, type Lambda } from '../../pure-protocol/src/index.ts';
import { pythonRunner, type PythonLink } from '../src/services/python-host.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';

const MODEL = '###Pure\nClass x::T {}';
const RUNTIME = 'x::R';
const QUERY: Lambda = lambda([], fn('select', fn('from', element('x::DB')), col('x', 'desk')));

/** Python, as the page sees it: each call caught, and answered as `answer` says. */
function python(answer: (path: string, body: string) => Response): { link: PythonLink; calls: { path: string; body: string; auth: string | null }[] } {
  const calls: { path: string; body: string; auth: string | null }[] = [];
  const fetch = (async (url: string, init: RequestInit) => {
    const path = new URL(url).pathname;
    const body = String(init.body ?? '');
    calls.push({ path, body, auth: (init.headers as Record<string, string> | undefined)?.['Authorization'] ?? null });
    return answer(path, body);
  }) as typeof globalThis.fetch;
  return { link: { baseUrl: 'http://127.0.0.1:9', fetch, authorization: 'Bearer t0ken' }, calls };
}

/** A JSON answer, as much of a Response as the page reads (V8 inside Python, the headless lane, has no Response). */
const json = (body: unknown, status = 200): Response => {
  const text = JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? 'application/json' : null) },
    text: async () => text,
    json: async () => JSON.parse(text) as unknown,
  } as unknown as Response;
};

describe("a page on Python whose Python plans on a server", () => {
  it("sends Python the query as the server's planner takes it, and reads the server's refusal in its words", async () => {
    const refusal = { code: -1, status: 'error', message: "Can't find the packageable element 'x::DB'" };
    const { link, calls } = python(() => json({ plan: JSON.stringify(refusal) }));
    const runner = pythonRunner(link, MODEL, RUNTIME, 'remote');
    await assert.rejects(runner.run(QUERY, { epoch: 1 } as CubeSnapshot), /Can't find the packageable element 'x::DB'/);
    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.path, '/plan-and-run.json');
    assert.equal(calls[0]!.auth, 'Bearer t0ken');
    assert.deepEqual(JSON.parse(calls[0]!.body), { lambda: toJson(QUERY), model: MODEL, runtime: RUNTIME });
  });

  it("asks Python's pure/v1 for a query's types, which Python sends on to the server", async () => {
    const type = { columns: [{ name: 'desk', genericType: { rawType: { _type: 'packageableType', fullPath: 'String' } } }] };
    const { link, calls } = python(() => json(type));
    const columns = await pythonRunner(link, MODEL, RUNTIME, 'engine').relationType(QUERY);
    assert.deepEqual(columns, [{ name: 'desk', type: 'String' }]);
    assert.equal(calls[0]!.path, '/api/pure/v1/compilation/lambdaRelationType');
    assert.equal(calls[0]!.auth, 'Bearer t0ken');
  });

  it("on the local plane reads Python's own planner's answer, as before", async () => {
    const { link, calls } = python(() => json({ plan: 'ERR\nCOMPILATION\nno such class' }));
    await assert.rejects(pythonRunner(link, MODEL, RUNTIME).run(QUERY, { epoch: 1 } as CubeSnapshot), /no such class/);
    assert.equal(calls[0]!.path, '/plan-and-run.json');
  });
});
