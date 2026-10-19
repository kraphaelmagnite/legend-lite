// A file from the page around the cube (src/host-file.ts): who is heard, what is taken, what the host is told.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { allowedOrigins, hostFile, type FrameWindow, type ToHost } from '../src/host-file.ts';

const OWN = 'https://cube.example';
const HOST = 'https://app.example';

/** A frame's window: its parent records what it is told, by origin; `send` delivers a message as the browser would. */
function frame() {
  const told: { message: ToHost; origin: string }[] = [];
  const parent = { postMessage: (message: ToHost, origin: string) => void told.push({ message, origin }) };
  const listeners = new Set<(event: MessageEvent) => void>();
  const win: FrameWindow = {
    parent,
    location: { origin: OWN },
    addEventListener: (_type, listener) => void listeners.add(listener),
    removeEventListener: (_type, listener) => void listeners.delete(listener),
  };
  const send = (data: unknown, origin: string, source: unknown = parent): void => {
    for (const l of [...listeners]) l({ data, origin, source } as MessageEvent);
  };
  return { win, told, send, listening: () => listeners.size };
}

const bytes = new TextEncoder().encode('region,qty\nEast,3\n');
const settle = () => new Promise((r) => setTimeout(r, 0));

describe('the origins a file is taken from', () => {
  it('the cube\'s own, and each named, as origins; anyone and malformed ones left out', () => {
    assert.deepEqual(allowedOrigins(OWN, [`${HOST}/some/page`, '*', 'not a url', OWN]), [OWN, HOST]);
  });
});

describe('a file from the host', () => {
  it('says it is ready to every allowed origin', () => {
    const f = frame();
    hostFile(f.win, [HOST]);
    assert.deepEqual(f.told, [
      { message: { type: 'datacube:ready' }, origin: OWN },
      { message: { type: 'datacube:ready' }, origin: HOST },
    ]);
  });

  it('takes the first file from an allowed parent, and stops listening', async () => {
    const f = frame();
    const link = hostFile(f.win, [HOST]);
    f.send({ type: 'datacube:open-file', name: 'bids.csv', bytes: bytes.buffer, mime: 'text/csv' }, HOST);
    const file = await link.file;
    assert.equal(file.name, 'bids.csv');
    assert.equal(file.type, 'text/csv');
    assert.equal(await file.text(), 'region,qty\nEast,3\n');
    assert.equal(f.listening(), 0);
  });

  it('hears no one but the parent, and no origin not allowed', async () => {
    const f = frame();
    const link = hostFile(f.win, []);
    let taken = false;
    void link.file.then(() => { taken = true; });
    f.send({ type: 'datacube:open-file', name: 'a.csv', bytes }, HOST);
    f.send({ type: 'datacube:open-file', name: 'a.csv', bytes }, OWN, { another: 'window' });
    await settle();
    assert.equal(taken, false);
    f.send({ type: 'datacube:open-file', name: 'a.csv', bytes }, OWN);
    assert.equal((await link.file).name, 'a.csv');
  });

  it('answers a malformed file with why, and keeps listening', async () => {
    const f = frame();
    const link = hostFile(f.win, [HOST]);
    f.told.length = 0;
    f.send({ type: 'datacube:open-file', name: ' ', bytes }, HOST);
    f.send({ type: 'datacube:open-file', name: 'b.csv', bytes: 'text' }, HOST);
    f.send({ type: 'something else' }, HOST);
    assert.deepEqual(f.told, [
      { message: { type: 'datacube:error', message: 'the file has no name' }, origin: HOST },
      { message: { type: 'datacube:error', message: 'b.csv came without its bytes' }, origin: HOST },
    ]);
    f.send({ type: 'datacube:open-file', name: 'b.csv', bytes }, HOST);
    assert.equal((await link.file).name, 'b.csv');
  });

  it('tells the host how the open went, at the origin the file came from only', async () => {
    const f = frame();
    const link = hostFile(f.win, [HOST]);
    f.told.length = 0;
    link.told(undefined);
    assert.equal(f.told.length, 0, 'nothing taken: nothing to tell');
    f.send({ type: 'datacube:open-file', name: 'c.json', bytes }, HOST);
    await link.file;
    link.told(undefined);
    link.told('could not open c.json: bad JSON');
    assert.deepEqual(f.told, [
      { message: { type: 'datacube:opened', name: 'c.json' }, origin: HOST },
      { message: { type: 'datacube:error', message: 'could not open c.json: bad JSON' }, origin: HOST },
    ]);
  });
});
