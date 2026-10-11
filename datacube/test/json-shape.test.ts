import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  explodeType, explodeName, explodeLambda, prettyJson, fieldsOf, inferShape, parseJson, scalarTypeOf, type Field,
} from '../src/json-shape.ts';
import { print } from './lite-compiler.ts';
import { col, fn, lit } from '../../pure-protocol/src/index.ts';

/** Every extraction under a field, by default column name: its lambda as the compiler prints it. */
async function byName(fields: readonly Field[]): Promise<Map<string, { expression: string; type: string; unnest?: boolean }>> {
  const out = new Map<string, { expression: string; type: string; unnest?: boolean }>();
  // each field walked in order, each print awaited, before the map is answered
  const walk = async (f: Field): Promise<void> => {
    for (const e of f.extractions) {
      out.set(e.name, { expression: await print(e.lambda), type: e.type, ...(e.unnest ? { unnest: true } : {}) });
    }
    for (const c of f.children) await walk(c);
  };
  for (const f of fields) await walk(f);
  return out;
}

describe('parseJson', () => {
  it('keeps numbers as written', () => {
    // JSON.parse makes 1.0 an integer and rounds past 2^53: an id
    // column would read as a float, a float column as integers.
    const n = parseJson('[1, 1.0, 9007199254740993, -2e3]');
    assert.equal(n.t, 'array');
    assert.deepEqual(n.t === 'array' ? n.items : [], [
      { t: 'number', text: '1' },
      { t: 'number', text: '1.0' },
      { t: 'number', text: '9007199254740993' },
      { t: 'number', text: '-2e3' },
    ]);
  });

  it('reads strings with escapes, and refuses malformed text', () => {
    assert.deepEqual(parseJson('{"a\\"b":"c\\nd"}'),
      { t: 'object', entries: [['a"b', { t: 'string', value: 'c\nd' }]] });
    assert.throws(() => parseJson('{"a":1'));
    assert.throws(() => parseJson('[1] x'));
  });
});

describe('inferShape', () => {
  it('types a number position from its text', () => {
    const ints = inferShape(['{"v":1}', '{"v":9007199254740993}']);
    assert.equal(scalarTypeOf(ints.shape.fields.get('v')!), 'integer');
    const mixed = inferShape(['{"v":1}', '{"v":1.5}']);
    assert.equal(scalarTypeOf(mixed.shape.fields.get('v')!), 'float');
  });

  it('knows dates and timestamps, and falls to text when mixed', () => {
    const s = inferShape([
      '{"d":"2025-01-02","t":"2025-01-02T10:00:00Z","m":1}',
      '{"d":"2025-02-03","t":"2025-01-02 10:00","m":"x"}',
    ]).shape;
    assert.equal(scalarTypeOf(s.fields.get('d')!), 'date');
    assert.equal(scalarTypeOf(s.fields.get('t')!), 'datetime');
    assert.equal(scalarTypeOf(s.fields.get('m')!), 'text');
  });

  it('counts what it could not read, and skips empty cells', () => {
    const s = inferShape(['{"a":1}', null, 'not json']);
    assert.equal(s.rows, 3);
    assert.equal(s.unreadable, 1);
    assert.equal(s.shape.present, 1);
  });

  it('accepts a cell a driver already decoded', () => {
    const s = inferShape([{ a: 'x' }]);
    assert.equal(scalarTypeOf(s.shape.fields.get('a')!), 'text');
  });
});

describe('fieldsOf', () => {
  const orders = inferShape([
    '{"tier":"gold","contact":{"email":"a@x"},"n":1}',
    '{"tier":"silver","n":2.5}',
    '{"tier":"gold","n":3}',
  ]);

  it('reaches a nested key through get, typed by the sample', async () => {
    const f = (await byName(fieldsOf('customer', orders)));
    assert.deepEqual(f.get('customer_tier'), {
      ...f.get('customer_tier'),
      expression: "x|$x.customer->get('tier')->to(@String)", type: 'String' });
    assert.equal(f.get('customer_contact_email')?.expression,
      "x|$x.customer->get('contact')->get('email')->to(@String)");
    assert.equal(f.get('customer_n')?.type, 'Float');
  });

  it('reports how often a key is there', () => {
    const [root] = fieldsOf('customer', orders);
    const contact = root!.children.find((c) => c.path.join('.') === 'contact');
    assert.equal(Math.round(contact!.presence * 100), 33);
  });

  it('offers count, text, and contains for an array of scalars', async () => {
    const tags = inferShape(['["gift","b2b"]', '["gift"]', '[]']);
    const f = (await byName(fieldsOf('tags', tags)));
    assert.equal(f.get('tags_count')?.expression, 'x|$x.tags->toMany(@Variant)->size()');
    assert.equal(f.get('tags_list')?.expression, "x|$x.tags->toMany(@String)->joinStrings(', ')");
    assert.equal(f.get('tags_has_gift')?.expression, "x|$x.tags->toMany(@String)->contains('gift')");
    assert.equal(f.get('tags_has_gift')?.type, 'Boolean');
  });

  it('offers per-field text, first and total for an array of objects', async () => {
    const items = inferShape(['[{"sku":"A","qty":2},{"sku":"B","qty":1}]']);
    const f = (await byName(fieldsOf('items', items)));
    assert.equal(f.get('items_first_sku')?.expression,
      "x|$x.items->get(0)->get('sku')->to(@String)");
    assert.equal(f.get('items_qty_total')?.expression,
      "x|$x.items->toMany(@Variant)->map(e|$e->get('qty')->to(@Integer)->toOne())->sum()");
    assert.equal(f.get('items_sku_total'), undefined, 'text has no total');
  });

  it('quotes a key that is not a plain name', async () => {
    const odd = inferShape(['{"it\'s":1}']);
    const f = (await byName(fieldsOf('c', odd)));
    assert.equal(f.get('c_it_s')?.expression, "x|$x.c->get('it\\'s')->to(@Integer)");
  });
});

describe('freeName', () => {
  it('keeps a free name and numbers a taken one', async () => {
    const { freeName } = await import('../src/ui/json-fields.ts');
    assert.equal(freeName('tier', new Set(['x'])), 'tier');
    assert.equal(freeName('tier', new Set(['tier', 'tier_2'])), 'tier_3');
  });
});

describe('as JSON', () => {
  it('pulls a nested object or array out, and every element field as one array', async () => {
    const docs = inferShape(['{"c":{"e":"a"},"items":[{"sku":"A","tags":["x"]}]}']);
    const f = (await byName(fieldsOf('d', docs)));
    assert.deepEqual(f.get('d_c'), { ...f.get('d_c'), expression: "x|$x.d->get('c')", type: 'Variant' });
    assert.equal(f.get('d_items')?.expression, "x|$x.d->get('items')");
    assert.equal(f.get('d_items_sku')?.expression,
      "x|$x.d->get('items')->toMany(@Variant)->map(e|$e->get('sku'))->toVariant()");
    // An array inside each element: its values from every element.
    assert.equal(f.get('d_items_tags')?.type, 'Variant');
    // one row per element (explode): each element's one scalar field, sku, typed
    const each = f.get('items_sku');
    assert.deepEqual([each?.expression, each?.type, each?.unnest],
      ["x|$x.d->get('items')->toMany(@Variant)->map(e|$e->get('sku')->to(@String))", 'String', true]);
    // one element, whole (T10): a JSON column of its own
    assert.deepEqual([f.get('d_items_first')?.expression, f.get('d_items_first')?.type],
      ["x|$x.d->get('items')->get(0)", 'Variant']);
  });

  it('is not offered for the column itself', async () => {
    const f = (await byName(fieldsOf('c', inferShape(['{"a":1}']))));
    assert.equal(f.get('c'), undefined);
  });
});


describe('explode: one column from the fields ticked', () => {
  const address = [{ key: 'kind', type: 'String', kind: 'dimension' as const },
    { key: 'city', type: 'String', kind: 'dimension' as const }];
  const array = fn('get', col('x', 'customer'), lit.string('addresses'));

  it('several fields: ONE JSON object holding just them, (kind, city)', async () => {
    assert.equal((await print(explodeLambda(array, address))),
      "x|$x.customer->get('addresses')->toMany(@Variant)->map(e|['kind'->pair($e->get('kind')->toVariant()), "
      + "'city'->pair($e->get('city')->toVariant())]->newMap()->toVariant())");
    assert.equal(explodeName(['customer', 'addresses'], address), 'addresses_kind_city');
    assert.equal(explodeType(address), 'Variant');
  });

  it('one field: its value, typed; none: the element as JSON', async () => {
    assert.equal((await print(explodeLambda(array, [address[1]!]))),
      "x|$x.customer->get('addresses')->toMany(@Variant)->map(e|$e->get('city')->to(@String))");
    assert.equal((await print(explodeLambda(array, []))), "x|$x.customer->get('addresses')->toMany(@Variant)");
    assert.equal(explodeName(['customer', 'addresses'], []), 'customer_addresses_element');
    assert.equal(explodeType([]), 'Variant');
  });

  it('an array of plain values explodes to its values, typed', async () => {
    const f = (await byName(fieldsOf('d', inferShape(['{"tags":["a","b"]}']))));
    assert.deepEqual([f.get('tags_value')?.expression, f.get('tags_value')?.unnest],
      ["x|$x.d->get('tags')->toMany(@String)", true]);
  });
});

describe('prettyJson', () => {
  it('shows a document for the eye: bare keys and plain strings, numbers as written', () => {
    assert.equal(prettyJson('{"kind":"billing","city":"Paris"}'), '{kind: billing, city: Paris}');
    assert.equal(prettyJson('{"sizes":["S","M"],"qty":2,"price":1.50,"ok":true,"gone":null}'),
      '{sizes: [S, M], qty: 2, price: 1.50, ok: true, gone: null}');
  });

  it('quotes what could be misread: punctuation, spaces at the ends, empty, number- or keyword-like', () => {
    assert.equal(prettyJson('{"city":"New York"}'), '{city: New York}');
    assert.equal(prettyJson('{"city":"Paris, France"}'), '{city: "Paris, France"}');
    assert.equal(prettyJson('["", " x", "12", "true", "null", "a:b"]'), '["", " x", "12", "true", "null", "a:b"]');
    // a space inside is plain
    assert.equal(prettyJson('{"New York":"New York"}'), '{New York: New York}');
  });

  it('shows text that is not JSON as it is', () => {
    assert.equal(prettyJson('not json'), 'not json');
  });
});
