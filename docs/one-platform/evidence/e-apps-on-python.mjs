// E's measured walk (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md, E's Built note): Legend Studio and Legend Query served by
// Python from the installed wheel, in one browser (one cookie), on a fresh LEGEND_LITE_HOME:
//   0. the link clicked on a page of another site (http://localhost:<another port>, as Jupyter's output is): the
//      cookie is SameSite=Strict, and the page's own calls are same-site, so they carry it;
//   1. Studio: "Load demo projects" writes the demo's four projects into Python's repository and publishes each;
//   2. Studio: a published project viewed read-only and compiled (F9);
//   3. Query: the landing lists the Depot's projects; one opened at HEAD; a class's query built (two columns) and run;
//   4. a page of another server on 127.0.0.1 (another port: the same site, so the browser sends it the cookie) POSTs a
//      saved query, no-cors: the engine refuses it (E's audit, B1), and the app's own calls still answer.
//
// Run, after `bazel build //python:wheel` and installing it into a virtual environment, with Python serving:
//   LEGEND_LITE_HOME=<empty folder> <venv>/bin/python -c "import json, time, legend_lite as ll; \
//     print(json.dumps({'query': ll.query(browser=False), 'studio': ll.studio(browser=False)}), flush=True); time.sleep(1e6)"
// (it prints a "Legend Query: ..." and a "Legend Studio: ..." line first, then the JSON), then, with Bazel's node
// (22.16) and DataCube's Playwright (`bazel build //datacube:node_modules` for bazel-bin/datacube/node_modules; its
// Chromium, Playwright's own download, not the chain's pinned headless shell):
//   node docs/one-platform/evidence/e-apps-on-python.mjs '<the printed JSON>' "$PWD/bazel-bin/datacube/node_modules/"
// Measured 2026-10-13 (Apple silicon laptop): all eight checks ok -- the demo published in 293 ms, the Depot's four
// projects listed -- with no page error and no failed call, but the last run's
// execute, refused 500 as PARK-35 says ("a project's queries run only in a browser tab for now").
// (2026-10-13, later: C5 closed PARK-35 -- a project's query on Python now runs on its own rows, so the refusal
// this records was the state on E's day; datacube/test/python-engine/python-engine.mjs measures the run now.)
import { createRequire } from 'node:module';
import http from 'node:http';
const links = JSON.parse(process.argv[2]);
const { chromium } = createRequire(process.argv[3])('playwright');
const browser = await chromium.launch();
const page = await (await browser.newContext({ viewport: { width: 1400, height: 900 } })).newPage();
const errors = [], failed = [];
page.on('pageerror', (e) => errors.push(e.message.split('\n')[0]));
page.on('response', (r) => { if (r.status() >= 400) failed.push(`${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`); });
const text = async () => (await page.locator('body').innerText()).replace(/\s+/g, ' ');
const step = (name, ok, detail) => console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);

const other = http.createServer((_, r) => { r.setHeader('Content-Type', 'text/html'); r.end(`<a id=go href="${links.studio}">Studio</a>`); });
await new Promise((ok) => other.listen(0, 'localhost', ok));
await page.goto(`http://localhost:${other.address().port}/`);
await page.click('#go');
await page.getByText('Load demo projects').waitFor({ timeout: 60_000 });
other.close();
step('the link, clicked on another site, traded for the cookie', !page.url().includes('token='), page.url());
const t0 = Date.now();
await page.getByText('Load demo projects').click();
await page.getByText('Demo projects published.').waitFor({ timeout: 180_000 });
step('Studio: demo projects published', true, `${Date.now() - t0} ms`);
const studio = links.studio.replace(/\?.*/, '');
await page.goto(`${studio}#/view/${encodeURIComponent('org.finos.lite.demo:party')}`);
await page.getByText('PartyMapping', { exact: true }).first().click();
await page.keyboard.press('F9');
await page.waitForTimeout(5000);
step('Studio: a published project compiled', /HEAD \(read only\) 0 0/.test(await text()));

const query = links.query.replace(/\?.*/, '');
await page.goto(`${query}#/setup`);
await page.getByText('Projects in Depot').waitFor({ timeout: 60_000 });
const listed = (await page.locator('button[data-project]').evaluateAll((bs) => bs.map((b) => b.dataset.project))).sort();
step('Query: the Depot\'s projects listed', listed.length === 4, listed.join(', '));
await page.locator('button[data-project="org.finos.lite.demo:trading"]').click();
await page.locator('.q-list-row', { hasText: 'PartyMapping' }).first().waitFor({ timeout: 60_000 });
step('Query: a project opened at HEAD', true);
await page.locator('.q-list-row', { hasText: 'PartyMapping' }).first().click();
await page.getByText('Run Query').waitFor({ timeout: 60_000 });
for (const p of ['Id', 'Name']) await page.locator(`text="${p}"`).first().dblclick();
const before = failed.length;
await page.getByRole('button', { name: 'Run Query' }).click();
await page.waitForTimeout(6000);
const refusal = /a project's queries run only in a browser tab/.test(await text());
step('Query: the run refused as PARK-35 says', refusal && failed.length === before + 1, failed.slice(before).join(', '));
step('no page error, and no failed call but the run', errors.length === 0 && failed.length === before + 1,
  JSON.stringify({ errors, failed }));

const origin = new URL(links.query).origin;
const elsewhere = http.createServer((_, r) => { r.setHeader('Content-Type', 'text/html'); r.end('<p>another server</p>'); });
await new Promise((ok) => elsewhere.listen(0, '127.0.0.1', ok));
const another = await page.context().newPage();
const answered = [];
another.on('response', (r) => { if (new URL(r.url()).pathname === '/api/pure/v1/query') answered.push(r.status()); });
await another.goto(`http://127.0.0.1:${elsewhere.address().port}/`);
await another.evaluate((o) => fetch(`${o}/api/pure/v1/query`, { method: 'POST', mode: 'no-cors', credentials: 'include',
  headers: { 'Content-Type': 'text/plain' }, body: '{}' }), origin);
const own = await page.evaluate(async (o) => (await fetch(`${o}/api/server/v1/currentUser`)).status, origin);
step('another port\'s POST refused, the page\'s own call answered', answered.at(-1) === 401 && own === 200,
  JSON.stringify({ other: answered.at(-1), own }));
elsewhere.close();
await browser.close();
