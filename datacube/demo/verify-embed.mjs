// A page embedding the cube hands it a file (src/host-file.ts, `?embed`): the cube opens THAT file, as a picked one,
// and tells the host so -- and a host on an origin the configuration does not allow is not heard at all.
//
// The host is a page of this harness's own, framing the site's index.html?embed: once the cube says it is ready, the
// host posts a small CSV, and the frame's grid must show that CSV's columns and rows. Then the same host page served
// from another origin (another port) keeps handing the file over, and the cube must stay waiting.
//
//   bazel run //datacube:verify_embed

// first: points Playwright at the Chromium Bazel fetched (as a browser_test; a no-op under bazel run)
import '../../tools/browser/pinned-chromium.mjs';
import { chromium } from 'playwright';
import { frames, serve, siteRoot } from './harness.mjs';

const ROOT = siteRoot();
const CSV = 'region,desk,qty\nEast,Rates,3\nWest,FX,5\nEast,FX,7\n';

let failed = false;
function bad(m) { console.log(`FAIL: ${m}`); failed = true; }

/**
 * The host page: the cube in a frame, and what the cube tells it kept in `window.said`. `eager` hands the file over
 * without waiting for the cube's ready -- as a host on an origin the cube never says ready to would have to.
 */
function hostPage(cubeUrl, eager) {
  return `<!doctype html><meta charset="utf-8"><body style="margin:0">
<iframe id="cube" src="${cubeUrl}" style="width:1200px;height:700px;border:0"></iframe>
<script>
window.said = [];
const cube = document.getElementById('cube');
const send = (origin) => {
  const bytes = new TextEncoder().encode(${JSON.stringify(CSV)}).buffer;
  cube.contentWindow.postMessage({ type: 'datacube:open-file', name: 'embedded.csv', bytes, mime: 'text/csv' }, origin, [bytes]);
};
addEventListener('message', (e) => {
  if (e.source !== cube.contentWindow) return;
  window.said.push({ ...e.data, origin: e.origin });
  if (e.data.type === 'datacube:ready') send(e.origin);
});
window.sendAnyway = () => send('*');
${eager ? "cube.addEventListener('load', () => window.sendAnyway());" : ''}
</script>`;
}

const site = await serve(ROOT, {
  route: (_req, res, url) => {
    if (url.pathname !== '/host.html') return false;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(hostPage('/demo/index.html?embed', false));
    return true;
  },
});
// another origin (another port): the cube's config.json names no embedOrigins, so this host is not allowed
const elsewhere = await serve(ROOT, {
  route: (_req, res, url) => {
    if (url.pathname !== '/host.html') return false;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(hostPage(`${site.origin}/demo/index.html?embed`, true));
    return true;
  },
});

const browser = await chromium.launch();
const errs = [];
try {
  // -- an allowed host: the page's own origin ------------------------------------------------------------------------
  const page = await browser.newPage({ viewport: { width: 1280, height: 760 } });
  page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
  await page.goto(`${site.origin}/host.html`, { waitUntil: 'load', timeout: 120_000 });
  await page.waitForFunction(() => window.said.some((m) => m.type === 'datacube:opened' || m.type === 'datacube:error'),
    undefined, { timeout: 120_000 });
  const said = await page.evaluate(() => window.said);
  console.log(`the cube said: ${JSON.stringify(said)}`);
  const ready = said.filter((m) => m.type === 'datacube:ready');
  if (ready.length !== 1 || ready[0].origin !== site.origin) bad(`expected one ready from ${site.origin}`);
  const outcome = said.find((m) => m.type === 'datacube:opened' || m.type === 'datacube:error');
  if (outcome?.type !== 'datacube:opened' || outcome.name !== 'embedded.csv') bad(`the open was not reported: ${JSON.stringify(outcome)}`);

  const cube = page.frames().find((f) => f.url().includes('/demo/index.html'));
  if (!cube) throw new Error('no cube frame');
  await cube.waitForFunction(() => document.querySelectorAll('.dc-row').length > 0, undefined, { timeout: 60_000 });
  await frames(cube);
  const rows = await cube.$$eval('.dc-row', (els) => els.map((el) => [...el.querySelectorAll('.dc-cell')]
    .map((c) => c.textContent?.trim() ?? '')));
  const labels = await cube.$$eval('[role=columnheader]', (els) => els.map((e) => e.textContent?.trim() ?? '').filter(Boolean));
  console.log(`rows: ${JSON.stringify(rows)}`);
  console.log(`headers: ${JSON.stringify(labels)}`);
  for (const column of ['region', 'desk', 'qty']) {
    if (!labels.some((l) => l.includes(column))) bad(`no ${column} column`);
  }
  if (rows.length !== 3) bad(`expected the file's 3 rows, saw ${rows.length}`);
  if (!rows.some((r) => r.includes('West') && r.includes('FX'))) bad('the file\'s rows are not on screen');
  if (rows.some((r) => /AMER|APAC|EMEA/.test(r.join(' ')))) bad('the demo\'s sample is on screen, not the file');
  await page.close();

  // -- a host on an origin not allowed ------------------------------------------------------------------------------
  const other = await browser.newPage();
  other.on('pageerror', (e) => errs.push(`pageerror (other origin): ${e.message}`));
  await other.goto(`${elsewhere.origin}/host.html`, { waitUntil: 'load', timeout: 120_000 });
  const framed = other.frames().find((f) => f.url().includes('/demo/index.html'));
  if (!framed) throw new Error('no cube frame on the other origin');
  // the cube is waiting for its file once DuckDB and the planner are up; then it is handed one again, from here
  await framed.waitForFunction(() => /waiting for the host/.test(document.getElementById('status')?.textContent ?? ''),
    undefined, { timeout: 120_000 });
  await other.evaluate(() => window.sendAnyway());
  await frames(framed);
  const stillWaiting = await framed.evaluate(() => /waiting for the host/.test(document.getElementById('status')?.textContent ?? ''));
  const otherSaid = await other.evaluate(() => window.said);
  console.log(`the cube said to the other origin: ${JSON.stringify(otherSaid)}`);
  if (!stillWaiting) bad('the cube stopped waiting for a file from an origin it does not allow');
  if (otherSaid.length > 0) bad('the cube spoke to an origin it does not allow');
  if (await framed.locator('.dc-row').count() > 0) bad('the cube opened a file from an origin it does not allow');
  await other.close();
} catch (e) {
  bad(e instanceof Error ? e.message : String(e));
} finally {
  await browser.close();
  await site.close();
  await elsewhere.close();
}

for (const e of errs) bad(e);
console.log(failed ? '\nverify-embed: FAILED' : '\nverify-embed: ok');
process.exit(failed ? 1 : 0);
