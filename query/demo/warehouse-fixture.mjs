// THE WAREHOUSE'S DEMO FIXTURE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5b): the trading demo's tables
// (models/trading-seed.sql), loaded into a warehouse as its owner through the warehouse's own API -- a real server's
// data, which no page loads. Query's config-warehouse.json reads them. Its browser test loads them so (verify.mjs), and a
// person by `bazel run //query:warehouse_fixture -- <the warehouse's URL> [--user U --password P]`
// (load-warehouse-fixture.mjs).

import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

/** Each statement of the fixture (one per line, `--` comments skipped), run as `user` on the warehouse at `url`. */
export async function loadWarehouseFixture(url, { user, password }) {
  const login = await fetch(`${url}/sql/v1/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ user, password }),
  });
  if (!login.ok) throw new Error(`signing in to the warehouse at ${url} as ${user}: ${login.status} ${await login.text()}`);
  const { token } = await login.json();
  const fixture = await readFile(fileURLToPath(new URL('models/trading-seed.sql', import.meta.url)), 'utf8');
  let ran = 0;
  for (const line of fixture.split('\n')) {
    const sql = line.trim();
    if (sql === '' || sql.startsWith('--')) continue;
    const said = await (await fetch(`${url}/sql/v1/statements`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql, catalog: 'main', waitMs: 30_000 }),
    })).json();
    if (said.state !== 'succeeded') throw new Error(`the warehouse's fixture: ${sql}: ${JSON.stringify(said)}`);
    ran += 1;
  }
  return ran;
}
