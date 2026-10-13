// DATACUBE AGAINST PYTHON'S ENGINE, run in the pinned Chromium (//datacube:python_engine_test). Node here only drives:
// it starts Python's engine (engine_fixture.py, beside it: the cube corpus's rows as a Live frame, DataCube's site
// served beside the API, so the page and the engine are one origin), hands the browser the test page (Playwright's
// routing, for /python-engine.html, its bundle and upstream's recorded Arrow answer) with the engine's token, and
// reports what the page found (test/python-engine/page.ts: every check runs there). Then the page a person opens
// (demo/engine.html), and a notebook's cubes (demo/widget-loader.ts and widget.ts, as anywidget runs a widget), their
// messages carried between the page and Python's widgets as a notebook's channel carries them.

// first: points Playwright at the Chromium Bazel fetched
import '../../../tools/browser/pinned-chromium.mjs';
import { spawn } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { chromium } from 'playwright';
import { runfileFromEnv } from '../../../tools/js/runfiles.mts';

const fixture = runfileFromEnv('ENGINE_FIXTURE');
const site = runfileFromEnv('DATACUBE_SITE');
const bundle = readFileSync(runfileFromEnv('PYTHON_ENGINE_PAGE'));
const upstream = readFileSync(runfileFromEnv('UPSTREAM_ARROW_ANSWER'));
// this user's legend-lite folder, the test's own (the pages Save writes go there, never into a real home)
const home = mkdtempSync(join(process.env.TEST_TMPDIR, 'legend-lite-home-'));

// A WAREHOUSE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C3): the Bazel-built native one, its owner alice and a reader,
// rita, seeded here as its owner (a table rita may read, one she may not); the page reads it through Python, signed in
// there as rita
const warehouse = spawn(runfileFromEnv('WAREHOUSE_BINARY'), ['--port', '0',
  '--data', mkdtempSync(join(process.env.TEST_TMPDIR, 'warehouse-')), '--user', 'alice:alice-pw', '--user', 'rita:rita-pw',
  '--owner', 'alice', '--duckdb-library', runfileFromEnv('WAREHOUSE_DUCKDB_LIBRARY')], { stdio: ['ignore', 'ignore', 'pipe'] });
let warehouseSaid = '';
const warehousePort = await new Promise((done, fail) => {
  warehouse.stderr.on('data', (b) => {
    warehouseSaid += String(b);
    for (const line of String(b).split('\n')) if (line) process.stderr.write(`[warehouse] ${line}\n`);
    const m = /listening on 127\.0\.0\.1:(\d+)/.exec(warehouseSaid);
    if (m) done(Number(m[1]));
  });
  warehouse.on('exit', (code) => fail(new Error(`the warehouse exited (${code}): ${warehouseSaid}`)));
});
const warehouseStopped = new Promise((done) => warehouse.once('exit', done));
const warehouseUrl = `http://127.0.0.1:${warehousePort}`;
const owner = (await (await fetch(`${warehouseUrl}/sql/v1/login`, { method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user: 'alice', password: 'alice-pw' }) })).json()).token;
for (const sql of [
  'CREATE TABLE POSITIONS (desk VARCHAR, qty INTEGER, px DECIMAL(10,2))',
  "INSERT INTO POSITIONS VALUES ('FX', 10, 1.50), ('EQ', 5, 2.25), ('FX', 3, 4.00)",
  'CREATE TABLE secret (s VARCHAR)',
  'GRANT SELECT ON TABLE POSITIONS TO rita',
]) {
  const said = await (await fetch(`${warehouseUrl}/sql/v1/statements`, { method: 'POST',
    headers: { Authorization: `Bearer ${owner}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ sql, catalog: 'main', waitMs: 30_000 }) })).json();
  if (said.state !== 'succeeded') throw new Error(`the warehouse's setup: ${sql}: ${JSON.stringify(said)}`);
}

// the library by its runfiles path, as //python's tests are given it: a program a test starts gets no BUILD env
const engine = spawn(fixture, ['--site', site], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: { ...process.env, LEGEND_LITE_LIBRARY: runfileFromEnv('LEGEND_LITE_LIBRARY'),
    LEGEND_LITE_DATACUBE: runfileFromEnv('LEGEND_LITE_DATACUBE'),
    LEGEND_LITE_DUCKDB_EXTENSIONS: runfileFromEnv('LEGEND_LITE_DUCKDB_EXTENSIONS'), LEGEND_LITE_HOME: home, PYTHONUTF8: '1',
    LEGEND_LITE_QUERY_SITE: runfileFromEnv('QUERY_SITE'), LEGEND_LITE_STUDIO_SITE: runfileFromEnv('STUDIO_SITE'),
    PYTHONDONTWRITEBYTECODE: '1' },
});
// the engine's own account, in this test's log: a server-side reason is then never invisible
engine.stderr.on('data', (b) => { for (const line of String(b).split('\n')) if (line) process.stderr.write(`[engine] ${line}\n`); });
const stopped = new Promise((done) => engine.once('exit', done));
// the engine's lines: the first says what it serves, each after it answers a command (`done <command>`); and, as they
// come, what its notebook cubes send their page (`sent ...`, `trait ...`), handed to the widget page as a notebook's
// channel would
const waiting = [];
const queued = [];
let toWidgets = () => {};
createInterface({ input: engine.stdout }).on('line', (line) => {
  if (line.startsWith('sent ') || line.startsWith('trait ')) {
    toWidgets(line);
    return;
  }
  const next = waiting.shift();
  if (next) next(line);
  else queued.push(line);
});
const exited = new Promise((_, fail) => engine.once('exit', (code) => fail(new Error(`the engine exited (${code})`))));
const nextLine = () => Promise.race([
  queued.length > 0 ? Promise.resolve(queued.shift()) : new Promise((done) => { waiting.push(done); }),
  exited,
]);
// (show() says its link first, "DataCube: <url>", as it does for a person)
let first = await nextLine();
while (first !== undefined && !first.startsWith('{')) first = await nextLine();
const served = JSON.parse(first);
const origin = served.url;

let failed = false;
// the browser closing at the end: a message the engine sends meanwhile (an answer to a call a view taken down made, say)
// finds its page gone, which is the test's own close and no case's failure (CI runs 38175909649 and 38187695487)
let closing = false;
let browser;
try {
  browser = await chromium.launch();
  // a browser away from UTC: every check here answers alike whatever zone the person is in (the tab's DuckDB set up as
  // Python's: services/browser.ts setUpSession)
  const page = await browser.newPage({ timezoneId: 'America/New_York' });
  page.on('pageerror', (e) => { console.log(`page error: ${e.message}`); failed = true; });
  page.on('console', (m) => { if (m.type() === 'error') console.log(`page console: ${m.text()}`); });
  page.on('requestfailed', (r) => console.log(`request failed: ${r.url()} -- ${r.failure()?.errorText ?? ''}`));
  // what the page is handed: the token, and the frame the engine serves (the address is the page's own origin)
  const { url: _origin, link: _link, ...given } = served;
  await page.addInitScript((g) => { window.__pythonEngineServed = g; }, { ...given, warehouse: warehouseUrl });
  // the test page, its bundle and upstream's recorded answer; everything else (the site, the API) is the engine's
  await page.route(`${origin}/python-engine.html`, (r) => r.fulfill({
    contentType: 'text/html',
    body: '<!doctype html><meta charset="utf-8"><title>DataCube on Python</title><script type="module" src="python-engine.js"></script>',
  }));
  await page.route(`${origin}/python-engine.js`, (r) => r.fulfill({ contentType: 'text/javascript', body: bundle }));
  await page.route(`${origin}/upstream.arrows.zst`, (r) => r.fulfill({ contentType: 'application/octet-stream', body: upstream }));
  await page.goto(`${origin}/python-engine.html`);
  await page.waitForFunction(() => window.__pythonEngine?.done === true, undefined, { timeout: 280_000 }).catch(async (e) => {
    console.log(`the page did not finish; it was at: ${await page.evaluate(() => window.__pythonEngineStage ?? '(no stage)')}`);
    throw e;
  });
  const verdict = await page.evaluate(() => window.__pythonEngine);
  for (const o of verdict.outcomes) {
    console.log(`${o.ok ? 'ok  ' : 'FAIL'} ${o.name} (${Math.round(o.ms)} ms)${o.ok ? '' : `\n${o.message}`}`);
    if (!o.ok) failed = true;
  }
  if (verdict.setupError) {
    console.log(`FAIL the setup: ${verdict.setupError}`);
    failed = true;
  }
  if (verdict.outcomes.length !== 9) {
    console.log(`FAIL ${verdict.outcomes.length} checks ran, not 9`);
    failed = true;
  }

  // THE PAGE A PERSON OPENS (demo/engine.html, from the engine's site): the link names the frame and carries the
  // token in its fragment; the cube opens over the engine and shows the frame's rows, with nothing run in the tab
  const cube = await browser.newPage();
  cube.on('pageerror', (e) => { console.log(`engine page error: ${e.message}`); failed = true; });
  cube.on('requestfailed', (r) => console.log(`engine page request failed: ${r.url()} ${r.postData() ?? ''} -- ${r.failure()?.errorText ?? ''}`));
  cube.on('console', (m) => console.log(`engine page console ${m.type()}: ${m.text()}`));
  // every file and call the page asks for is answered: a refusal (a missing file, a refused call) fails the test -- but
  // for the SDLC's own "not there yet": the page asks for its workspace before it makes one, and the SDLC answers a
  // workspace not yet made 404, as legend-sdlc does (the in-page SDLC answers the same, inside the tab)
  cube.on('response', (r) => {
    const notYet = r.status() === 404 && r.request().method() === 'GET'
      && /^\/sdlc\/api\/projects\/[^/]+\/workspaces\/[^/]+$/.test(new URL(r.url()).pathname);
    if (r.status() >= 400 && !notYet) {
      failed = true;
      // with the engine's reason, as the page was told it
      void r.text().then((t) => t, () => '').then((t) => console.log(`FAIL engine page answer ${r.status()}: ${r.url()} ${t.slice(0, 600)}`));
    }
  });
  // the link show() returned (cube.url)
  await cube.goto(served.link);
  const shown = await cube.waitForFunction(() => /\b10 rows\b/.test(document.body.innerText)
    && document.body.innerText.includes('APAC'), undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${shown ? 'ok  ' : 'FAIL'} the engine's page opens the frame's cube and shows its rows`);
  if (!shown) {
    console.log(`  the page showed: ${(await cube.evaluate(() => document.body.innerText)).slice(0, 400)}`);
    failed = true;
  }

  // THE APP ON PYTHON (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md, step 2a): the frame is a page of one grid (R1), the
  // tab's app over Python's services; its Generated Pure & SQL window has the query the page sent and the SQL that
  // answered it, which Python's planner wrote and Python ran (R4: the page sends Pure, never SQL)
  const onApp = await cube.waitForFunction(() => document.querySelector('.dc-page') !== null
    && window.__dataPage?.grids.length === 1
    // where it plans, as Python said it (services.json: R3), in the status bar's one word
    && document.querySelector('.dc-app-status')?.textContent === 'local'
    && /#>\{/.test(document.getElementById('pure')?.textContent ?? '')
    && /\bSELECT\b/i.test(document.getElementById('sql')?.textContent ?? ''), undefined, { timeout: 30_000 }).then(() => true, () => false);
  console.log(`${onApp ? 'ok  ' : 'FAIL'} the frame is a page of one grid on DataCube's app, its Pure & SQL window the query sent and the SQL Python ran`);
  if (!onApp) {
    const seen = await cube.evaluate(() => ({ grids: window.__dataPage?.grids.length, pure: document.getElementById('pure')?.textContent?.slice(0, 200),
      sql: document.getElementById('sql')?.textContent?.slice(0, 200) }));
    console.log(`  the page had: ${JSON.stringify(seen)}`);
    failed = true;
  }

  // FOLLOWING THE FRAME (show's cube.update): Python changes it, and the open page shows the change by itself
  const after = async (command, name, test) => {
    engine.stdin.write(`${command}\n`);
    if ((await nextLine()) !== `done ${command}`) throw new Error(`the engine did not do ${command}`);
    const ok = await cube.waitForFunction(test, undefined, { timeout: 30_000 }).then(() => true, () => false);
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}`);
    if (!ok) {
      console.log(`  the page showed: ${(await cube.evaluate(() => document.body.innerText)).slice(0, 400)}`);
      failed = true;
    }
  };
  await after('update', 'a frame updated in Python shows on the open page by itself (the same columns: the view re-run)',
    () => /\b11 rows\b/.test(document.body.innerText));
  await after('columns', 'a frame with a new column opens the cube again over its new model',
    () => document.body.innerText.includes('trader'));

  // A FILE INTO PYTHON'S DUCKDB (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md, step 2b): the app's own picker on Python's
  // page, a CSV chosen there; Python reads it into its DuckDB with DataCube's own code (upload.ts, headless), and the
  // grid's rows are Python's over the model that code wrote -- the SQL Python ran, in the Pure & SQL window, reads the
  // file's own table
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank .dc-primary').click();
  await cube.setInputFiles('.dc-picker-file', {
    name: 'books.csv', mimeType: 'text/csv', buffer: Buffer.from('title,pages\nDune,412\nEmma,474\nUlysses,730\n'),
  });
  const fileRead = await cube.waitForFunction(() => document.querySelector('.dc-picker') === null
    && /\b3 rows\b/.test(document.body.innerText) && document.body.innerText.includes('Ulysses')
    && /"?books"?/.test(document.getElementById('sql')?.textContent ?? ''), undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${fileRead ? 'ok  ' : 'FAIL'} a file chosen on Python's page is read into Python's DuckDB, its rows Python's`);
  if (!fileRead) {
    console.log(`  the page showed: ${(await cube.evaluate(() => `${document.body.innerText.slice(0, 300)} | sql: ${document.getElementById('sql')?.textContent?.slice(0, 200)}`))}`);
    failed = true;
  }
  // CREATE A CLASS… ON PYTHON'S PAGE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B1, B3): the app's own action over Python's
  // services -- the class's sample read where the grid's rows are (PlanAndRun on Python), written by Python's native
  // library, its project made in Python's model home (the same SDLC rules, a git repository in this test's own folder)
  const menuItem = (label) => cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(`:scope > .dc-menu-label:text-is(${JSON.stringify(label)})`) }).first();
  await cube.locator('.dc-tile-menu').last().click();
  await menuItem('Create a Class…').click();
  const classShown = await cube.waitForFunction(() => document.querySelectorAll('.dc-class-file').length > 0
    || document.querySelector('.dc-class .dc-picker-status.dc-failed') !== null, undefined, { timeout: 60_000 }).then(() => true, () => false);
  if (classShown) {
    await cube.locator('.dc-class .dc-primary').click();
    await cube.waitForFunction(() => document.querySelector('.dc-class .dc-picker-status.dc-ok, .dc-class .dc-picker-status.dc-failed') !== null,
      undefined, { timeout: 60_000 }).catch(() => undefined);
  }
  const classSaid = classShown ? (await cube.locator('.dc-class .dc-picker-status').textContent()) ?? '' : '(no window)';
  const repository = readdirSync(join(home, 'projects', 'refs', 'heads'), { recursive: true });
  const madeClass = /^Created \S+::domain::Book in the project \S+ \(workspace \S+\), in this user's folder/.test(classSaid)
    && repository.length > 0;
  console.log(`${madeClass ? 'ok  ' : 'FAIL'} Create a Class… on Python's page makes its class in Python's model home, a git repository`);
  if (!madeClass) {
    console.log(`  the window said: ${classSaid}; the repository's branches: ${JSON.stringify(repository)}`);
    failed = true;
  }
  // AND OPENS IN STUDIO, served by the same Python (E): the window's link, its token traded for Studio's cookie on the
  // first load, onto the project's workspace with the class in it
  const studioHref = classShown && (await cube.locator('.dc-class-studio').count()) > 0
    ? await cube.locator('.dc-class-studio').getAttribute('href') : null;
  let inStudio = false;
  if (studioHref !== null && /\/studio\/demo\/index\.html\?token=[^#]+#\/edit\/[^/]+\/[^/]+$/.test(studioHref)) {
    const studio = await browser.newPage();
    studio.on('pageerror', (e) => { console.log(`studio error: ${e.message}`); failed = true; });
    await studio.goto(studioHref);
    inStudio = await studio.waitForFunction(() => /\bBook\b/.test(document.body.innerText) && !location.search.includes('token='),
      undefined, { timeout: 60_000 }).then(() => true, () => false);
    if (!inStudio) console.log(`  Studio showed: ${(await studio.evaluate(() => document.body.innerText.slice(0, 300)))}`);
    await studio.close();
  }
  console.log(`${inStudio ? 'ok  ' : 'FAIL'} the class opens in Studio, served by the same Python, from the window's link`);
  if (!inStudio) {
    console.log(`  the link: ${studioHref}`);
    failed = true;
  }

  // A PROJECT'S QUERY ON PYTHON (PARK-35, docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5): Studio's demo projects loaded
  // into Python's repository (Studio served by Python), then Query served by Python opens the party project by name
  // and runs a query over it -- its rows the project's own Data rows, in the project's database inside Python's
  // DuckDB, as a tab's Query answers them (site/verify.mjs: 5 parties, Banque Lumière among them)
  const token = served.authorization.replace(/^Bearer /, '');
  const demo = await browser.newPage();
  demo.on('pageerror', (e) => { console.log(`studio error: ${e.message}`); failed = true; });
  await demo.goto(`${origin}/studio/demo/index.html?token=${token}#/`);
  await demo.getByRole('button', { name: 'Load demo projects' }).click({ timeout: 60_000 });
  const published = await demo.waitForFunction(() => /Demo projects published/.test(
    document.querySelector('[data-testid=demo-status]')?.textContent ?? ''), undefined, { timeout: 180_000 }).then(() => true, () => false);
  await demo.close();
  let partyRows = '';
  if (published) {
    const enc = encodeURIComponent;
    const q = await browser.newPage();
    q.on('pageerror', (e) => { console.log(`query error: ${e.message}`); failed = true; });
    await q.goto(`${origin}/query/demo/index.html?token=${token}#/create/manual/${enc('org.finos.lite.demo:party:master-SNAPSHOT')}`
      + `/${enc('demo::party::PartyMapping')}/${enc('demo::party::Runtime')}?class=${enc('demo::party::Party')}`);
    await q.waitForSelector('.q-node', { timeout: 120_000 });
    for (const property of ['Name', 'Country']) await q.dblclick(`.q-node:has-text('${property}')`);
    await q.click('button.q-run');
    await q.waitForFunction(() => document.querySelector('.q-error-box')
      || /\d+ rows? in \d+ ms/.test(document.querySelector('.q-results-bar')?.textContent ?? ''), undefined, { timeout: 120_000 });
    const refused = await q.$('.q-error-box');
    partyRows = refused ? `refused: ${await refused.textContent()}`
      : `${await q.locator('.q-results-bar').textContent()} | ${(await q.$$eval('.q-grid tbody tr', (trs) => trs.map((tr) => tr.textContent ?? ''))).join(' | ')}`;
    await q.close();
  }
  const ranOnPython = /(^|\D)5 rows? in/.test(partyRows) && partyRows.includes('Banque Lumière');
  console.log(`${ranOnPython ? 'ok  ' : 'FAIL'} a project loaded in Studio on Python runs in Query on Python, on its own rows (5 parties)`);
  if (!ranOnPython) {
    console.log(`  published: ${published}; Query showed: ${partyRows.slice(0, 400)}`);
    failed = true;
  }
  if (classShown) {
    await cube.locator('.dc-class .dc-primary').click();
    await cube.locator('.dc-class').waitFor({ state: 'detached', timeout: 10_000 }).catch(() => undefined);
  }
  // the page changed (its model made): the next page asks first whether to leave it, as the tab's does -- answered yes
  cube.once('dialog', (d) => { console.log(`     (asked: ${d.message().slice(0, 160)})`); void d.accept(); });
  // AN EXAMPLE goes the same way: generated in the page, read into Python's DuckDB as a file is (and the file before it
  // let go of: its grid gone, its table dropped in Python -- forget.json, every answer here a success)
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank .dc-primary').click();
  await cube.locator('.dc-picker-tab[data-section="examples"]').click();
  // the trades example, a CSV (an example whose rows hold nested values, a Variant, is read too; its queries wait on
  // the compatibility plan's spelling of Variant, which Python's execute plans by)
  await cube.click('.dc-picker-card[data-example="trades"]');
  await cube.fill('.dc-picker-rows', '300');
  await cube.click('.dc-picker-choice .dc-primary');
  const exampleRead = await cube.waitForFunction(() => document.querySelector('.dc-picker') === null
    && /\b300 rows\b/.test(document.body.innerText) && /\bSELECT\b/i.test(document.getElementById('sql')?.textContent ?? ''),
  undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${exampleRead ? 'ok  ' : 'FAIL'} an example opened on Python's page is read into Python's DuckDB as a file is`);
  if (!exampleRead) {
    console.log(`  the page showed: ${(await cube.evaluate(() => document.body.innerText.slice(0, 300)))}`);
    failed = true;
  }
  // NESTED VALUES (a Variant column) on Python's page: the orders example, its customers and items JSON -- planned and
  // run as the tab plans and runs them (PlanAndRun), where Python's legend-engine answer refused a Variant column
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank .dc-primary').click();
  await cube.locator('.dc-picker-tab[data-section="examples"]').click();
  await cube.click('.dc-picker-card[data-example="orders-json"]');
  await cube.fill('.dc-picker-rows', '40');
  await cube.click('.dc-picker-choice .dc-primary');
  const nestedRead = await cube.waitForFunction(() => document.querySelector('.dc-picker') === null
    && /\b40 rows\b/.test(document.body.innerText), undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${nestedRead ? 'ok  ' : 'FAIL'} a file of nested values (Variant columns) opens on Python's page, as in the tab`);
  if (!nestedRead) {
    console.log(`  the page showed: ${(await cube.evaluate(() => document.body.innerText.slice(0, 300)))}`);
    failed = true;
  }
  // A REMOTE FILE, read by Python's DuckDB through the httpfs extension the wheel carries: the app's own Remote section,
  // a Parquet file a server on this machine serves (nothing leaves the machine)
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank .dc-primary').click();
  await cube.locator('.dc-picker-tab[data-section="remote"]').click();
  await cube.fill('.dc-picker-url', served.remote);
  await cube.click('.dc-picker-form .dc-primary');
  const remoteRead = await cube.waitForFunction(() => document.querySelector('.dc-picker') === null
    && /\b3 rows\b/.test(document.body.innerText) && document.body.innerText.includes('Ulysses'),
  undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${remoteRead ? 'ok  ' : 'FAIL'} a remote file opened on Python's page is read by Python's DuckDB, through the httpfs it carries`);
  if (!remoteRead) {
    console.log(`  the page showed: ${(await cube.evaluate(() => document.body.innerText.slice(0, 300)))}`);
    failed = true;
  }

  // SAVE AND OPEN ON PYTHON (step 2d): the page saved by the app's own Save As…, its record in this user's folder (the
  // store's rules the page's own, Python keeping the records), then opened again from Open… over its source as it is now
  const menuEntry = async (label) => {
    await cube.click('.dc-titlebar-menu');
    await cube.locator(`.dc-menu-item:has(> .dc-menu-label:text-is(${JSON.stringify(label)}))`).first().click();
  };
  await menuEntry('Save As\u2026');
  await cube.locator('.dc-save').waitFor({ timeout: 10_000 });
  await cube.fill('.dc-save-input', 'Books from Python');
  await cube.locator('.dc-save-foot .dc-primary').click();
  const saved = await cube.locator('.dc-save').waitFor({ state: 'detached', timeout: 30_000 }).then(() => true, () => false);
  const records = saved ? readdirSync(join(home, 'pages')).filter((f) => f.endsWith('.json')) : [];
  const record = records.length === 1 ? JSON.parse(readFileSync(join(home, 'pages', records[0]), 'utf8')) : undefined;
  const inFolder = saved && record?.name === 'Books from Python' && record.owner === 'this user' && typeof record.content === 'string';
  console.log(`${inFolder ? 'ok  ' : 'FAIL'} a page saved on Python's page is a record in this user's folder`);
  if (!inFolder) {
    console.log(`  saved: ${saved}, records: ${JSON.stringify(records)}, record: ${JSON.stringify(record)?.slice(0, 300)}`);
    failed = true;
  }
  // a blank page, then the saved one opened from Open…: its grid over its remote file again
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank').waitFor({ timeout: 10_000 });
  await menuEntry('Open\u2026');
  const row = cube.locator('#cubelib .dc-lib-row', { hasText: 'Books from Python' });
  const listed = await row.waitFor({ timeout: 10_000 }).then(() => true, () => false);
  if (listed) await row.locator('.dc-lib-button', { hasText: 'Open' }).click();
  const reopened = listed && await cube.waitForFunction(() => /\b3 rows\b/.test(document.body.innerText)
    && document.body.innerText.includes('Ulysses') && document.querySelector('.dc-page-name')?.textContent?.includes('Books from Python'),
  undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${reopened ? 'ok  ' : 'FAIL'} a page saved on Python opens again from Open…, over its source`);
  if (!reopened) {
    console.log(`  listed: ${listed}; the page showed: ${(await cube.evaluate(() => document.body.innerText.slice(0, 300)))}`);
    failed = true;
  }

  // AN ICEBERG TABLE (G5), read by Python's DuckDB through the iceberg extension the wheel carries, every file it names
  // served by the server on this machine: the app's own Remote section, as for a remote file
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank .dc-primary').click();
  await cube.locator('.dc-picker-tab[data-section="remote"]').click();
  await cube.fill('.dc-picker-url', served.iceberg);
  await cube.click('.dc-picker-form .dc-primary');
  const icebergRead = await cube.waitForFunction(() => document.querySelector('.dc-picker') === null
    && /\b3 rows\b/.test(document.body.innerText) && document.body.innerText.includes('Cygnus'),
  undefined, { timeout: 60_000 }).then(() => true, () => false);
  console.log(`${icebergRead ? 'ok  ' : 'FAIL'} an Iceberg table opened on Python's page is read by Python's DuckDB, through the iceberg it carries`);
  if (!icebergRead) {
    console.log(`  the page showed: ${(await cube.evaluate(() => document.body.innerText.slice(0, 300)))}`);
    failed = true;
  }

  // A WAREHOUSE TABLE ON PYTHON'S PAGE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C3), by the app's own Database section:
  // Python signs in as rita, the table opens Live (its rows the warehouse's, its receipt saying so), the grid's plane
  // button snaps it into Python's DuckDB (the button and the receipt saying where the copy is), and goes live again
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("New")') }).hover();
  await cube.locator('.dc-menu .dc-menu-item', { has: cube.locator(':scope > .dc-menu-label:text-is("Blank Page")') }).click();
  await cube.locator('.dc-blank .dc-primary').click();
  await cube.locator('.dc-picker-tab[data-section="database"]').click();
  await cube.getByLabel('Address').fill(warehouseUrl);
  await cube.getByLabel('User').fill('rita');
  await cube.getByLabel('Password').fill('rita-pw');
  await cube.click('.dc-picker-form .dc-primary');
  await cube.click('.dc-picker-row[data-object="main.POSITIONS"]', { timeout: 60_000 });
  // the plane button and the receipt of the grid on screen (an earlier page's are in the document too, hidden)
  const plane = () => cube.evaluate(() => {
    const shown = (e) => e !== null && e.offsetParent !== null;
    const toggle = [...document.querySelectorAll('.dc-titlebar-toggle')].find(shown);
    const receipt = [...document.querySelectorAll('.dc-status-receipt')].find(shown);
    return { text: toggle?.textContent?.trim() ?? '', title: toggle?.getAttribute('title') ?? '', receipt: receipt?.textContent ?? '',
      rows: /\b3 rows\b/.test(document.body.innerText), picker: document.querySelector('.dc-picker') !== null };
  });
  const until = async (want) => {
    const t0 = Date.now();
    for (;;) {
      const p = await plane();
      if (want(p) || Date.now() - t0 > 60_000) return p;
      await cube.waitForTimeout(200);
    }
  };
  const live = await until((p) => !p.picker && p.rows && p.text === 'Live' && /the warehouse/.test(p.receipt));
  const liveOk = !live.picker && live.rows && live.text === 'Live' && /the warehouse/.test(live.receipt) && /rita/.test(live.receipt)
    && /snap a copy of your rows into Python's database/.test(live.title);
  console.log(`${liveOk ? 'ok  ' : 'FAIL'} a warehouse table opened on Python's page reads Live on the warehouse, as rita, its Snap offered into Python's database`);
  if (!liveOk) {
    console.log(`  the grid said: ${JSON.stringify(live)}`);
    failed = true;
  }
  await cube.locator('.dc-titlebar-toggle:visible').click();
  const snapped = await until((p) => p.text === 'Snapped' && /Python's copy/.test(p.receipt));
  const snappedOk = snapped.text === 'Snapped' && /Python's copy/.test(snapped.receipt) && /a copy in Python's database/.test(snapped.title)
    && /3 rows/.test(snapped.title) && snapped.rows;
  console.log(`${snappedOk ? 'ok  ' : 'FAIL'} its plane button snaps it into Python's DuckDB, the button and the receipt saying where the copy is`);
  if (!snappedOk) {
    console.log(`  the grid said: ${JSON.stringify(snapped)}`);
    failed = true;
  }
  await cube.locator('.dc-titlebar-toggle:visible').click();
  const back = await until((p) => p.text === 'Live' && /the warehouse/.test(p.receipt));
  const backOk = back.text === 'Live' && /the warehouse/.test(back.receipt) && back.rows;
  console.log(`${backOk ? 'ok  ' : 'FAIL'} clicked again, it reads Live on the warehouse once more, the copy let go`);
  if (!backOk) {
    console.log(`  the grid said: ${JSON.stringify(back)}`);
    failed = true;
  }

  // A FRAME CLOSED IN PYTHON: its open tab keeps what it shows, and says it no longer follows the frame -- in its status
  // line and its title (the app's live page, docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §5)
  engine.stdin.write('show-closing\n');
  const closingLine = await nextLine();
  if (!closingLine.startsWith('done show-closing ')) throw new Error(`the engine did not show the frame: ${closingLine}`);
  const closingTab = await browser.newPage();
  closingTab.on('pageerror', (e) => { console.log(`closing tab error: ${e.message}`); failed = true; });
  await closingTab.goto(JSON.parse(closingLine.slice('done show-closing '.length)));
  const closingShown = await closingTab.waitForFunction(() => /\b2 rows\b/.test(document.body.innerText), undefined,
    { timeout: 60_000 }).then(() => true, () => false);
  engine.stdin.write('close-closing\n');
  if ((await nextLine()) !== 'done close-closing') throw new Error('the engine did not close the frame');
  const closedSaid = closingShown && await closingTab.waitForFunction(
    () => /not following the frame: the frame was closed/.test(document.querySelector('.dc-app-status')?.textContent ?? '')
      && /not followed: the frame was closed/.test(document.title) && /\b2 rows\b/.test(document.body.innerText),
    undefined, { timeout: 30_000 }).then(() => true, () => false);
  console.log(`${closedSaid ? 'ok  ' : 'FAIL'} a frame closed in Python: its tab keeps its rows and says it no longer follows the frame`);
  if (!closedSaid) {
    console.log(`  the tab said: ${await closingTab.evaluate(() => `${document.title} | ${document.querySelector('.dc-app-status')?.textContent}`)}`);
    failed = true;
  }
  await closingTab.close();

  // A PAGE FROM PYTHON (docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md, step 1): a page of the engine's two frames
  // on two sheets, written by DataCube's own writers (the test page's __pythonPageDocument), served by Python as `q3`,
  // and opened as a person opens it (demo/engine.html?page=q3); then a frame updated, and the page changed, in Python
  const doc = await page.evaluate(() => window.__pythonPageDocument());
  engine.stdin.write(`page ${JSON.stringify(doc)}\n`);
  if ((await nextLine()) !== 'done page') throw new Error('the engine did not serve the page');
  const tabbed = await browser.newPage();
  tabbed.on('pageerror', (e) => { console.log(`page tab error: ${e.message}`); failed = true; });
  tabbed.on('console', (m) => { if (m.type() === 'error') console.log(`page tab console: ${m.text()}`); });
  tabbed.on('response', (r) => {
    // the one refusal that is not a failure: the page's report of a version Python replaced while it was on its way
    // (engine.py, _page_said), the page then opening the new one
    if (r.status() === 409 && r.request().method() === 'POST' && new URL(r.url()).pathname === '/page.json') return;
    if (r.status() >= 400) { console.log(`FAIL page tab answer ${r.status()}: ${r.url()}`); failed = true; }
  });
  await tabbed.goto(`${origin}/engine.html?page=q3${new URL(served.link).hash}`);
  const sheetNames = () => [...document.querySelectorAll('.dc-sheet-tab .dc-sheet-label')].map((t) => t.textContent).join('|');
  const seen = async (name, test, arg) => {
    const ok = await tabbed.waitForFunction(test, arg, { timeout: 60_000 }).then(() => true, () => false);
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}`);
    if (!ok) {
      console.log(`  the page showed: ${(await tabbed.evaluate(() => document.body.innerText)).slice(0, 400)}`);
      failed = true;
    }
  };
  await seen('a page from Python opens in a tab: its sheets, the first one\'s grid over its frame and the chart beside it',
    `(${String(sheetNames)})() === 'trades|Desks' && document.querySelector('[data-tile="grid-1"]')?.innerText.includes('EMEA')
      && document.querySelector('[data-tile="chart-1"]') !== null`);
  await tabbed.locator('.dc-sheet-tab', { hasText: 'Desks' }).click();
  await seen('its second sheet shows the grid over the second frame',
    () => document.querySelector('[data-tile="grid-2"]')?.innerText.includes('Credit') === true);
  const python = async (command) => {
    engine.stdin.write(`${command}\n`);
    if ((await nextLine()) !== `done ${command}`) throw new Error(`the engine did not do ${command}`);
  };
  await python('desks-update');
  await seen('a frame updated in Python shows on the open page by itself',
    () => document.querySelector('[data-tile="grid-2"]')?.innerText.includes('Equity') === true);
  await python('page-again');
  await seen('the page changed in Python opens again, on the sheet it showed',
    `(${String(sheetNames)})() === 'trades|Desks (v2)' && document.querySelector('[data-tile="grid-2"]')?.offsetParent !== null`);

  // A PAGE BUILT WITH ll.Page (step 2): built and shown in Python as a person does; its link is the one show() gives.
  // Then a sheet added in Python (the open page opens it again), and a sheet renamed in DataCube read back by
  // page.read() (the open page reports its document as it changes)
  engine.stdin.write('py-page\n');
  const built = await nextLine();
  if (!built.startsWith('done py-page ')) throw new Error(`the engine did not build the page: ${built}`);
  await tabbed.goto(JSON.parse(built.slice('done py-page '.length)));
  await seen('a page built with ll.Page opens in a tab: its sheets, its grid over its frame and the chart beside it',
    `(${String(sheetNames)})() === 'pytrades|Desks' && document.querySelector('[data-tile="grid-1"]')?.innerText.includes('EMEA')
      && document.querySelector('[data-tile="chart-1"]') !== null`);
  await python('py-page-sheet');
  await seen('a sheet added to it in Python shows on the open page', `(${String(sheetNames)})() === 'pytrades|Desks|Added in Python'`);
  await tabbed.locator('.dc-sheet-tab', { hasText: 'Desks' }).dblclick();
  await tabbed.locator('.dc-sheet-tab input').fill('Renamed in DataCube');
  await tabbed.locator('.dc-sheet-tab input').press('Enter');
  const read = await (async () => {
    // the page reports its document once a change settles (half a second): asked until it has, or ten seconds
    for (let tries = 0; tries < 20; tries += 1) {
      await new Promise((done) => { setTimeout(done, 500); });
      engine.stdin.write('py-page-read\n');
      const line = await nextLine();
      if (!line.startsWith('done py-page-read ')) throw new Error(`the engine did not read the page: ${line}`);
      const names = JSON.parse(line.slice('done py-page-read '.length));
      if (names.includes('Renamed in DataCube')) return names;
    }
    return undefined;
  })();
  console.log(`${read ? 'ok  ' : 'FAIL'} a sheet renamed in DataCube is in page.read() in Python${read ? ` (${read.join(', ')})` : ''}`);
  if (!read) failed = true;

  // SAVED FROM PYTHON (D, B6): page.save() keeps the page in this user's saved pages by the rules the tab's own Save
  // keeps it by; DataCube's Open… lists it, and it opens -- its sheets as Python saved them, its grid over its frame
  engine.stdin.write('py-page-save\n');
  const savedLine = await nextLine();
  if (!savedLine.startsWith('done py-page-save ')) throw new Error(`the engine did not save the page: ${savedLine}`);
  // opened in the first tab, which shows another page (the one saved there, "Books from Python")
  await cube.click('.dc-titlebar-menu');
  await cube.locator('.dc-menu-item:has(> .dc-menu-label:text-is("Open\u2026"))').first().click();
  const pyRow = cube.locator('#cubelib .dc-lib-row', { hasText: 'Built in Python' });
  const pyListed = await pyRow.waitFor({ timeout: 10_000 }).then(() => true, () => false);
  if (pyListed) await pyRow.locator('.dc-lib-button', { hasText: 'Open' }).click();
  const pyOpened = pyListed && await cube.waitForFunction(`(${String(sheetNames)})() === 'pytrades|Renamed in DataCube|Added in Python'
    && document.querySelector('.dc-page-name')?.textContent?.includes('Built in Python')`, undefined, { timeout: 60_000 })
    .then(() => true, () => false);
  console.log(`${pyOpened ? 'ok  ' : 'FAIL'} a page saved from Python (page.save()) is in Open… and opens, its sheets as Python saved them`);
  if (!pyOpened) {
    console.log(`  listed: ${pyListed}; sheets: ${await cube.evaluate(`(${String(sheetNames)})()`)}; the page showed: ${
      (await cube.evaluate(() => document.body.innerText.slice(0, 400)))}`);
    failed = true;
  }

  // EVERY PAGE PYTHON WRITES IS ONE DATACUBE READS: pages built with each kind of edit Python makes, one with a frozen
  // chart kept without its grid, and DataCube's own page loaded and changed in Python, read by DataCube's reader
  engine.stdin.write('py-documents\n');
  const written = await nextLine();
  if (!written.startsWith('done py-documents ')) throw new Error(`the engine did not write the pages: ${written}`);
  const texts = JSON.parse(written.slice('done py-documents '.length));
  const refused = await page.evaluate((all) => all.map((text) => window.__readPythonPage(text)), texts);
  const readAll = texts.length === 3 && refused.every((why) => why === null);
  console.log(`${readAll ? 'ok  ' : 'FAIL'} every page Python writes DataCube's reader reads (${texts.length} pages)`);
  if (!readAll) {
    refused.forEach((why, i) => { if (why !== null) console.log(`  page ${i + 1}: ${why}`); });
    failed = true;
  }

  // A NOTEBOOK'S CUBES (legend_lite.notebook.DataCube): two widgets on one page, each its script loaded as anywidget
  // loads one (its text as a module), their messages carried to Python's widgets and back as a notebook's channel
  // carries them. DataCube's module comes over the channel once for the page; no call goes over HTTP.
  const widgets = await browser.newPage();
  widgets.on('pageerror', (e) => { console.log(`widget page error: ${e.message}`); failed = true; });
  widgets.on('console', (m) => { if (m.type() === 'error') console.log(`widget page console: ${m.text()}`); });
  const http = [];
  widgets.on('request', (r) => { if (!r.url().endsWith('/widgets.html')) http.push(r.url()); });
  const fetched = new Map();
  await widgets.exposeFunction('__widgetSend', (name, message) => {
    const { path } = JSON.parse(message);
    fetched.set(path, (fetched.get(path) ?? 0) + 1);
    engine.stdin.write(`widget ${name} ${message}\n`);
  });
  // what Python's widgets send goes to every page with a view of that widget, as a notebook's channel brings it to
  // every view of a model
  const widgetPages = [widgets];
  toWidgets = (line) => {
    const space = line.indexOf(' ');
    for (const page of widgetPages) {
      page.evaluate(([kind, json]) => {
        const m = JSON.parse(json);
        const model = window.__models?.[m.widget];
        if (model === undefined) return;
        if (kind === 'trait') model.set(m.name, m.value);
        else {
          const views = m.buffers.map((b) => new DataView(Uint8Array.from(atob(b), (c) => c.charCodeAt(0)).buffer));
          model.fire('msg:custom', m.content, views);
        }
      }, [line.slice(0, space), line.slice(space + 1)]).catch((e) => {
        if (closing) return;
        console.log(`FAIL a widget message: ${e}`);
        failed = true;
      });
    }
  };
  await widgets.route(`${origin}/widgets.html`, (r) => r.fulfill({
    contentType: 'text/html',
    body: '<!doctype html><meta charset="utf-8"><title>notebook cubes</title><div id="nb" style="width:1000px"></div>'
      + '<div id="nb2" style="width:1000px"></div><div id="nbp" style="width:1000px"></div>',
  }));
  await widgets.goto(`${origin}/widgets.html`);
  await widgets.evaluate(async (given) => {
    window.__models = {};
    window.__heardAbove = [];
    document.addEventListener('keydown', (e) => window.__heardAbove.push(e.key));
    for (const [name, { esm, state }] of Object.entries(given)) {
      // the part of anywidget's model a widget uses, as anywidget's front end gives it
      const listeners = new Map();
      const values = { ...state };
      const model = {
        get: (key) => values[key],
        on: (event, f) => { if (!listeners.has(event)) listeners.set(event, new Set()); listeners.get(event).add(f); },
        off: (event, f) => { listeners.get(event)?.delete(f); },
        send: (content) => { window.__widgetSend(name, JSON.stringify(content)); },
        fire: (event, ...args) => { for (const f of listeners.get(event) ?? []) f(...args); },
        set: (key, value) => { values[key] = value; model.fire(`change:${key}`); },
      };
      window.__models[name] = model;
      // as anywidget loads a widget's script: its text as a module (a blob URL), imported
      const url = URL.createObjectURL(new Blob([esm], { type: 'text/javascript' }));
      const widget = (await import(url)).default;
      URL.revokeObjectURL(url);
      widget.render({ model, el: document.getElementById(name) });
    }
  }, served.widgets);
  const check = async (name, test, arg) => {
    const ok = await widgets.waitForFunction(test, arg, { timeout: 60_000 }).then(() => true, () => false);
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}`);
    if (!ok) {
      console.log(`  the page showed: ${(await widgets.evaluate(() => document.body.innerText)).slice(0, 400)}`);
      failed = true;
    }
  };
  await check('two notebook cubes show their frames\' rows, over the widget\'s channel',
    () => /\b10 rows\b/.test(document.getElementById('nb').innerText) && document.getElementById('nb').innerText.includes('APAC')
      && /\b3 rows\b/.test(document.getElementById('nb2').innerText));
  // the app's windows in a notebook: its Generated Pure & SQL window, in the widget's own element, has the SQL that
  // answered the grid -- Python's planner's, run by Python
  await check('a notebook cube\'s Pure & SQL window has the query sent and the SQL Python ran',
    () => /#>\{/.test(document.getElementById('nb').querySelector('#pure')?.textContent ?? '')
      && /\bSELECT\b/i.test(document.getElementById('nb').querySelector('#sql')?.textContent ?? ''));
  const once = fetched.get('/widget.js') === 1 && fetched.get('/widget.css') === 1;
  console.log(`${once ? 'ok  ' : 'FAIL'} DataCube's module came once for the page (${fetched.get('/widget.js')} widget.js, ${fetched.get('/widget.css')} widget.css)`);
  if (!once) failed = true;
  console.log(`${http.length === 0 ? 'ok  ' : 'FAIL'} the notebook cubes made no HTTP call${http.length ? `: ${http.join(', ')}` : ''}`);
  if (http.length) failed = true;
  // A NOTEBOOK'S PAGE (ll.Page under a cell, notebook.PageCube): its sheet and its grid, over the widget's channel; a
  // sheet added in Python shows by itself (its versions followed, no polling)
  const pageTabs = () => [...document.getElementById('nbp').querySelectorAll('.dc-sheet-tab .dc-sheet-label')]
    .map((t) => t.textContent).join('|');
  await check('a notebook\'s page shows its sheet and its grid over its frame, over the widget\'s channel',
    `(${String(pageTabs)})() === 'nbpage' && document.getElementById('nbp').innerText.includes('EMEA')`);
  engine.stdin.write('widget-page-sheet\n');
  if ((await nextLine()) !== 'done widget-page-sheet') throw new Error('the engine did not do widget-page-sheet');
  await check('a sheet added to the notebook\'s page in Python shows by itself (its versions followed)',
    `(${String(pageTabs)})() === 'nbpage|Added in Python'`);
  engine.stdin.write('widget-update\n');
  if ((await nextLine()) !== 'done widget-update') throw new Error('the engine did not do widget-update');
  await check('a notebook cube\'s frame updated in Python shows by itself (its version followed, no polling)',
    () => /\b11 rows\b/.test(document.getElementById('nb').innerText));
  const kept = await widgets.evaluate(() => {
    const el = document.getElementById('nb');
    el.querySelector('*')?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    return { marked: el.dataset.lmSuppressShortcuts === 'true', heardAbove: window.__heardAbove.length };
  });
  const keys = kept.marked && kept.heardAbove === 0;
  console.log(`${keys ? 'ok  ' : 'FAIL'} a key pressed in a notebook cube stays with it (${JSON.stringify(kept)})`);
  if (!keys) failed = true;

  // A VIEW TAKEN DOWN WHILE DATACUBE LOADS (the audit of step 7, S2): on a fresh page, two views of one widget; the
  // first starts the module's load over its channel and is taken down at once (its cell run again), which cuts its
  // calls short. The second loads the module over its own channel, and shows its rows.
  const cut = await browser.newPage();
  cut.on('pageerror', (e) => { console.log(`second widget page error: ${e.message}`); failed = true; });
  const cutFetched = new Map();
  await cut.exposeFunction('__widgetSend', (name, message) => {
    const { path } = JSON.parse(message);
    cutFetched.set(path, (cutFetched.get(path) ?? 0) + 1);
    engine.stdin.write(`widget ${name} ${message}\n`);
  });
  await cut.route(`${origin}/cut.html`, (r) => r.fulfill({
    contentType: 'text/html',
    body: '<!doctype html><meta charset="utf-8"><title>a view taken down</title><div id="a" style="width:1000px"></div>'
      + '<div id="b" style="width:1000px"></div>',
  }));
  await cut.goto(`${origin}/cut.html`);
  widgetPages.push(cut);
  await cut.evaluate(async ({ esm, state }) => {
    const listeners = new Map();
    const values = { ...state };
    const model = {
      get: (key) => values[key],
      on: (event, f) => { if (!listeners.has(event)) listeners.set(event, new Set()); listeners.get(event).add(f); },
      off: (event, f) => { listeners.get(event)?.delete(f); },
      send: (content) => { window.__widgetSend('nb2', JSON.stringify(content)); },
      fire: (event, ...args) => { for (const f of listeners.get(event) ?? []) f(...args); },
      set: (key, value) => { values[key] = value; model.fire(`change:${key}`); },
    };
    window.__models = { nb2: model };
    const url = URL.createObjectURL(new Blob([esm], { type: 'text/javascript' }));
    const widget = (await import(url)).default;
    URL.revokeObjectURL(url);
    const takeDownFirst = widget.render({ model, el: document.getElementById('a') });
    widget.render({ model, el: document.getElementById('b') });
    takeDownFirst();
  }, served.widgets.nb2);
  const rerun = await cut.waitForFunction(() => /\b3 rows\b/.test(document.getElementById('b').innerText), undefined,
    { timeout: 60_000 }).then(() => true, () => false);
  const loads = cutFetched.get('/widget.js');
  const shownAfterCut = rerun && loads === 2;
  console.log(`${shownAfterCut ? 'ok  ' : 'FAIL'} a view taken down mid-load leaves the other view to load DataCube itself (${loads} widget.js asked)`);
  if (!shownAfterCut) {
    console.log(`  the second view showed: ${(await cut.evaluate(() => document.getElementById('b').innerText)).slice(0, 300)}`);
    failed = true;
  }
} finally {
  closing = true;
  await browser?.close();
  // stopped and WAITED for: the engine serves until its input closes, and is gone before the test reports
  engine.stdin.end();
  await stopped;
  warehouse.kill();
  await warehouseStopped;
}
process.exit(failed ? 1 : 0);
