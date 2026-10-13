// The saved-cube store (src/cube-store.ts): upstream's query-store rules
// (legend-engine DataCubeQueryStoreManager, 4.145.0), over records in memory.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { CubeStoreError, HostRecords, MemoryRecords, RuleStore, type DataCubeQuery, type HostRecordsCall } from '../src/cube-store.ts';
import { ExactNumber, fromJson } from '../../pure-protocol/src/index.ts';

function clock(start = 1_000): () => number {
  let t = start;
  return () => (t += 10);
}

const cube = (id: string, name: string, extra: Partial<DataCubeQuery> = {}): DataCubeQuery =>
  ({ id, name, content: { kind: 'datacube.cube', n: 1 }, ...extra });

describe('the store', () => {
  it('creates with the client\'s id, forces the owner, and refuses a taken id', async () => {
    const store = new RuleStore(new MemoryRecords(), 'me', clock());
    const made = await store.create(cube('a', 'Alpha', { owner: 'someone else' }));
    assert.equal(made.owner, 'me', 'the owner is the current user, whatever the request says');
    assert.ok(made.createdAt !== undefined && made.lastUpdatedAt === made.createdAt);
    await assert.rejects(store.create(cube('a', 'Again')), /already existed/);
    await assert.rejects(store.create(cube('', 'x')), /ID is missing/);
    await assert.rejects(store.create(cube('b', '')), /name is missing/);
  });

  it('stamps lastOpenAt when a cube is read', async () => {
    const store = new RuleStore(new MemoryRecords(), 'me', clock());
    const made = await store.create(cube('a', 'Alpha'));
    const opened = await store.get('a');
    assert.ok((opened.lastOpenAt ?? 0) > (made.lastOpenAt ?? 0));
    await assert.rejects(store.get('nope'), (e: unknown) => e instanceof CubeStoreError && e.status === 404);
  });

  it('lets only the owner update or delete, and never changes an id', async () => {
    const records = new MemoryRecords();
    const mine = new RuleStore(records, 'me', clock());
    const theirs = new RuleStore(records, 'you', clock(5_000));
    await mine.create(cube('a', 'Alpha'));
    await assert.rejects(theirs.update('a', cube('a', 'Mine now')), (e: unknown) => e instanceof CubeStoreError && e.status === 403);
    await assert.rejects(theirs.delete('a'), /Only owner/);
    await assert.rejects(mine.update('a', cube('b', 'Moved')), /Updating query ID is not supported/);
    const updated = await mine.update('a', cube('a', 'Alpha 2'));
    assert.equal(updated.name, 'Alpha 2');
    await mine.delete('a');
    await assert.rejects(mine.get('a'), /Can't find/);
  });

  it('searches upstream\'s way: light records, mine first, the chosen sort, the limit', async () => {
    const records = new MemoryRecords();
    const mine = new RuleStore(records, 'me', clock());
    const theirs = new RuleStore(records, 'you', clock(100_000));
    await mine.create(cube('1', 'Trades by desk'));
    await mine.create(cube('2', 'Risk'));
    await theirs.create(cube('3', 'Trades, theirs'));
    const all = await mine.search({ sortByOption: 'SORT_BY_CREATE' });
    assert.deepEqual(all.map((q) => q.id), ['2', '1', '3'], 'mine first, newest first');
    assert.equal('content' in (all[0] as object), false, 'no content in a search');
    assert.deepEqual((await mine.search({ searchTermSpecification: { searchTerm: 'trades' } })).map((q) => q.id).sort(), ['1', '3']);
    assert.deepEqual((await mine.search({ searchTermSpecification: { searchTerm: '2' } })).map((q) => q.id), ['2'], 'an id matches');
    assert.deepEqual((await mine.search({ showCurrentUserQueriesOnly: true })).map((q) => q.id).sort(), ['1', '2']);
    assert.equal((await mine.search({ limit: 1 })).length, 1);
  });

  it('keeps a content\'s exact numbers', async () => {
    const store = new RuleStore(new MemoryRecords(), 'me', clock());
    const content = fromJson('{"kind":"datacube.cube","big":123456789012345678901234,"dec":12.30}') as Record<string, unknown>;
    await store.create({ id: 'x', name: 'Exact', content });
    const back = (await store.get('x')).content;
    assert.ok(back['big'] instanceof ExactNumber && back['big'].text === '123456789012345678901234');
    assert.ok(back['dec'] instanceof ExactNumber && back['dec'].text === '12.30');
  });
});

describe('records a host keeps (Python: this user\'s folder)', () => {
  /** A host as Python answers the four calls: each record kept as the JSON text it was sent, nothing of it read. */
  function host(): { carry: (call: HostRecordsCall) => Promise<unknown>; calls: string[]; kept: Map<string, string> } {
    const kept = new Map<string, string>();
    const calls: string[] = [];
    const carry = async (call: HostRecordsCall): Promise<unknown> => {
      // over the wire, as JSON both ways
      const sent = JSON.parse(JSON.stringify(call)) as HostRecordsCall;
      calls.push(sent.call);
      if (sent.call === 'all') return [...kept.values()].map((t) => JSON.parse(t) as unknown);
      if (sent.call === 'find') return kept.has(sent.id) ? JSON.parse(kept.get(sent.id)!) as unknown : undefined;
      if (sent.call === 'put') kept.set(String(sent.record['id']), JSON.stringify(sent.record));
      else kept.delete(sent.id);
      return undefined;
    };
    return { carry, calls, kept };
  }

  it('runs the store\'s own rules over them, as over the browser\'s', async () => {
    const h = host();
    const store = new RuleStore(new HostRecords(h.carry), 'this user', clock());
    const made = await store.create(cube('a', 'Alpha', { owner: 'someone else' }));
    assert.equal(made.owner, 'this user');
    await assert.rejects(store.create(cube('a', 'Again')), /already existed/);
    assert.deepEqual((await store.search({})).map((q) => q.name), ['Alpha']);
    await store.delete('a');
    await assert.rejects(store.get('a'), (e: unknown) => e instanceof CubeStoreError && e.status === 404);
    assert.ok(h.calls.includes('put') && h.calls.includes('remove') && h.calls.includes('all'));
  });

  it('keeps a content\'s exact numbers: the record crosses with its content as JSON text', async () => {
    const h = host();
    const store = new RuleStore(new HostRecords(h.carry), 'this user', clock());
    const content = fromJson('{"kind":"datacube.page","big":123456789012345678901234,"dec":12.30}') as Record<string, unknown>;
    await store.create({ id: 'x', name: 'Exact', content });
    // kept as the host keeps it: the record's content is the exact JSON text, never a parsed number
    const row = JSON.parse(h.kept.get('x')!) as { content: unknown };
    assert.equal(typeof row.content, 'string');
    assert.match(row.content as string, /"big":123456789012345678901234[,}]/);
    assert.match(row.content as string, /"dec":12\.30[,}]/);
    const back = (await store.get('x')).content;
    assert.ok(back['big'] instanceof ExactNumber && back['big'].text === '123456789012345678901234');
    assert.ok(back['dec'] instanceof ExactNumber && back['dec'].text === '12.30');
  });
});
