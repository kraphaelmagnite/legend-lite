// `bazel run //query:warehouse_fixture -- <the warehouse's URL> [--user alice --password alice-pw]`: the trading
// demo's tables loaded into a warehouse as its owner (warehouse-fixture.mjs), for Query's config-warehouse.json to
// read. A hand tool: manual, built by :tools_build_test.

import { loadWarehouseFixture } from './warehouse-fixture.mjs';

// the URL, and each `--name value` after or before it
const flags = new Map();
const positional = [];
for (let i = 2; i < process.argv.length; i++) {
  const a = process.argv[i];
  if (a.startsWith('--')) flags.set(a.slice(2), process.argv[++i]);
  else positional.push(a);
}
const flag = (name, otherwise) => flags.get(name) ?? otherwise;
const url = positional[0];
if (!url) {
  console.error('the warehouse\'s URL, then optionally --user and --password (its owner\'s: alice, alice-pw by default)');
  process.exit(2);
}
const ran = await loadWarehouseFixture(url.replace(/\/$/, ''), { user: flag('user', 'alice'), password: flag('password', 'alice-pw') });
console.log(`the trading demo's fixture loaded into ${url}: ${ran} statements`);
