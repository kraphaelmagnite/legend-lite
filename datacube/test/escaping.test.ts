// Getting a user's text into a query and back out intact.
//
// A filter value and an odd column name both come from a person, and in
// Pure text both end up inside single quotes. Escaping the quote alone is
// not enough, because a BACKSLASH escapes whatever follows it -- including
// the closing quote:
//
//   value  C:\      ->  'C:\'     an unterminated literal
//   value  back\'   ->  'back\''  the text becomes grammar
//
// The first is a crash from a pasted Windows path; the second is
// injection. DataCube's queries are trees now (src/query.ts): a value is a
// string node and a name a property, whatever they hold, and only the
// COMPILER writes them as text. So the question these tests ask is the
// compiler's: printed and parsed back, is every hostile value still one
// value, and every odd name one name?

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { accessor, col, lambda, lit, toJson } from '../../pure-protocol/src/index.ts';
import { liteParse, print, printLevel } from './lite-compiler.ts';

const HOSTILE = [
  ['a plain value', 'AMER'],
  ['an apostrophe', "it's"],
  ['a trailing backslash', 'C:\\'],
  ['two trailing backslashes', 'C:\\\\'],
  ['a backslash before a quote', "back\\'"],
  ['a backslash in the middle', 'back\\slash'],
  ['only a backslash', '\\'],
  ['quote then backslash', "'\\"],
  ['many alternating', "\\'\\'\\'"],
  ['a newline', 'a\nb'],
  ['a tab', 'a\tb'],
  ['unicode', 'Ünïcødé'],
  ['an emoji', '🙂'],
  ['sql-ish', "'; DROP TABLE t; --"],
  ['pure-ish', "')->select(~[x])->from(evil"],
  ['the pivot separator', '__|__'],
] as const;

describe('a string value through the compiler\'s print and parse', () => {
  for (const [label, value] of HOSTILE) {
    it(`comes back the same value when it is ${label}`, async () => {
      const query = lambda([], lit.string(value));
      assert.equal(toJson(await liteParse((await print(query)))), toJson(query), (await print(query)));
    });
  }
});

describe('an odd column name through the compiler\'s print and parse', () => {
  for (const [label, value] of HOSTILE) {
    it(`comes back the same name when it is ${label}`, async () => {
      const query = lambda(['x'], col('x', value));
      assert.equal(toJson(await liteParse((await print(query)))), toJson(query), (await print(query)));
    });
  }
});

/**
 * Blank out every single-quoted literal, honouring backslash escapes.
 *
 * Counting `->select(` in the raw text is not an injection test: a
 * payload that merely CONTAINS that text will match while sitting
 * safely inside quotes, which is exactly what a correct escaper
 * produces. The question is whether the payload became a STAGE, so
 * the literals have to come out first.
 */
function stripLiterals(pure: string): string {
  let out = '';
  let inStr = false;
  for (let i = 0; i < pure.length; i++) {
    const ch = pure[i];
    if (inStr) {
      if (ch === '\\') {
        i += 1; // skip the escaped character
        continue;
      }
      if (ch === "'") inStr = false;
      continue;
    }
    if (ch === "'") {
      inStr = true;
      continue;
    }
    out += ch;
  }
  assert.equal(inStr, false, `unterminated literal in: ${pure}`);
  return out;
}

describe('the injection attempt in a whole query', () => {
  const snap = {
    source: { query: accessor('db', 'T') },
    columns: [
      { name: 'region', type: 'String' },
      { name: 'notional', type: 'Float' },
    ],
    derived: [],
    rows: ['region'],
    pivotOn: [],
    measures: [{ name: 'm', column: 'notional', fn: 'sum' as const }],
    sorts: [],
    epoch: 1,
  };

  it('cannot close the literal and append its own pipeline', async () => {
    const evil = "x')->select(~[region])->limit(1)->filter(y|'";
    const pure = (await printLevel(
      { ...snap, filter: { kind: 'condition', column: 'region', operator: 'equal', value: evil } },
      { level: 1, parent: [], limit: 10 },
    ));
    // The payload's pipeline must appear only INSIDE the quotes, never
    // as a stage of the query. With the literals removed, the query
    // must have exactly the stages a clean cube of this shape emits.
    const bare = stripLiterals(pure);
    assert.equal((bare.match(/->select\(/g) ?? []).length, 1, bare);
    assert.equal((bare.match(/->limit\(/g) ?? []).length, 1, bare);
    assert.equal((bare.match(/->filter\(/g) ?? []).length, 1, bare);
  });

  it('cannot break out through a column name either', async () => {
    const evil = "region')->select(~[";
    const pure = (await printLevel(
      { ...snap, rows: [evil], columns: [...snap.columns, { name: evil, type: 'String' }] },
      { level: 1, parent: [], limit: 10 },
    ));
    const bare = stripLiterals(pure);
    assert.equal((bare.match(/->select\(/g) ?? []).length, 1, bare);
    assert.equal((bare.match(/->groupBy\(/g) ?? []).length, 1, bare);
  });
});
