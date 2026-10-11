import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  OPERATORS,
  fromFilterNode,
  addTo,
  newCondition,
  newGroup,
  operandKind,
  operatorsFor,
  parseList,
  parseTyped,
  toFilter,
  updateNode,
} from '../src/ui/filter-editor.ts';
import { printFilter } from './lite-compiler.ts';

/** The compiler's types for the columns these filters name, as a cube has them. */
const TYPES: Record<string, string> = { region: 'String', desk: 'String', trade_date: 'StrictDate' };
const typed = (c: string): string | undefined => TYPES[c];
import type { FilterNode } from '../src/snapshot.ts';

describe('operator table', () => {
  it('covers all 31 operators exactly once', () => {
    assert.equal(OPERATORS.length, 31);
    assert.equal(new Set(OPERATORS.map((o) => o.op)).size, 31);
  });

  it('gives every operator an arity', () => {
    // The operator decides the input; a missing arity means a text
    // box appears for `is null` and the query is wrong.
    assert.equal(operandKind('isEmpty'), 'none');
    assert.equal(operandKind('in'), 'list');
    assert.equal(operandKind('equalColumn'), 'column');
    assert.equal(operandKind('contains'), 'single');
  });
});

describe('a typed value, by its column\'s type (never by its look)', () => {
  it('keeps a number column\'s plain digits exact, and evaluates arithmetic', () => {
    // '12.30' and an integer past 2^53 would not survive a double
    assert.equal(parseTyped('12.30', 'Decimal'), '12.30');
    assert.equal(parseTyped('9007199254740993', 'Integer'), '9007199254740993');
    assert.equal(parseTyped('-2.5', 'Float'), '-2.5');
    assert.equal(parseTyped('2 * 3', 'Integer'), 6);
    assert.equal(parseTyped('abc', 'Integer'), null);
  });

  it('keeps text text, however numeric it looks (account codes)', () => {
    assert.equal(parseTyped('00123', 'String'), '00123');
    assert.equal(parseTyped("'10'", 'String'), '10');
  });

  it('keeps a date as the text typed, never a JavaScript Date: the compiler judges it on Apply', () => {
    assert.equal(parseTyped('2024-03-01', 'StrictDate'), '2024-03-01');
    assert.equal(parseTyped(' 2024-03-01T12:58 ', 'DateTime'), '2024-03-01T12:58');
    assert.equal(parseTyped('March 1st', 'DateTime'), 'March 1st');
    assert.equal(parseTyped('', 'DateTime'), null, 'nothing typed yet is no value');
  });

  it('types booleans', () => {
    assert.equal(parseTyped('true', 'Boolean'), true);
    assert.equal(parseTyped('false', 'Boolean'), false);
  });

  it('drops empty entries from a list rather than sending them', () => {
    assert.deepEqual(parseList('a, ,b,', 'String'), ['a', 'b']);
    assert.deepEqual(parseList('1, 2', 'Integer'), ['1', '2']);
  });
});

describe('toFilter', () => {
  it('is undefined when nothing is usable', () => {
    assert.equal(toFilter(newGroup()), undefined);
    // A half-typed condition narrows nothing rather than narrowing
    // wrongly.
    assert.equal(toFilter(newGroup([newCondition('region')])), undefined);
  });

  it('unwraps a lone condition rather than wrapping it in a group', () => {
    let tree = newGroup([newCondition('region')]);
    tree = updateNode(tree, tree.children[0]!.id, { text: 'EMEA' });
    assert.deepEqual(toFilter(tree), {
      kind: 'condition',
      column: 'region',
      operator: 'equal',
      value: 'EMEA',
    });
  });

  it('joins several conditions with the group operator', async () => {
    let tree = newGroup([newCondition('region'), newCondition('desk')]);
    tree = updateNode(tree, tree.children[0]!.id, { text: 'EMEA' });
    tree = updateNode(tree, tree.children[1]!.id, { text: 'Rates' });
    tree = updateNode(tree, tree.id, { join: 'or' });
    const f = toFilter(tree);
    assert.equal(f?.kind, 'or');
    assert.equal((await printFilter(f!, typed)), "x|($x.region == 'EMEA') || ($x.desk == 'Rates')");
  });

  it('expresses A AND NOT (B OR C), which a flat list cannot', async () => {
    // The reason the editor is a tree: `not` lives on every node,
    // groups included, exactly as DataCube models it.
    let inner = newGroup([newCondition('desk'), newCondition('desk')]);
    inner = updateNode(inner, inner.children[0]!.id, { text: 'Rates' });
    inner = updateNode(inner, inner.children[1]!.id, { text: 'Credit' });
    inner = updateNode(inner, inner.id, { join: 'or', not: true });

    let root = newGroup([newCondition('region')]);
    root = updateNode(root, root.children[0]!.id, { text: 'EMEA' });
    root = addTo(root, root.id, inner);

    assert.equal(
      (await printFilter(toFilter(root)!, typed)),
      "x|($x.region == 'EMEA') && !(($x.desk == 'Rates') || ($x.desk == 'Credit'))",
    );
  });

  it('negates a single condition', async () => {
    let tree = newGroup([newCondition('region')]);
    tree = updateNode(tree, tree.children[0]!.id, { text: 'EMEA', not: true });
    // the compiler prints `not(equal(...))` as upstream's printer does: `!=`
    assert.equal((await printFilter(toFilter(tree)!, typed)), "x|$x.region != 'EMEA'");
  });

  it('drops a group whose children are all incomplete', () => {
    const tree = newGroup([newGroup([newCondition('region')])]);
    assert.equal(toFilter(tree), undefined);
  });

  it('needs no value for a nullary operator', async () => {
    let tree = newGroup([newCondition('region')]);
    tree = updateNode(tree, tree.children[0]!.id, { operator: 'isEmpty' });
    assert.equal((await printFilter(toFilter(tree)!, typed)), 'x|$x.region->isEmpty()');
  });

  it('needs a second column for a column operator', async () => {
    let tree = newGroup([newCondition('region')]);
    tree = updateNode(tree, tree.children[0]!.id, { operator: 'equalColumn' });
    assert.equal(toFilter(tree), undefined, 'incomplete until a column is picked');
    tree = updateNode(tree, tree.children[0]!.id, { rightColumn: 'desk' });
    assert.equal((await printFilter(toFilter(tree)!, typed)), 'x|$x.region == $x.desk');
  });
});

describe('opening on a filter that already exists', () => {
  // Without the inverse, the editor opens EMPTY on a filtered cube
  // and the user's first change writes that emptiness back --
  // silently dropping a filter they can see in force on the grid
  // behind the dialog.

  const roundTrip = (f: FilterNode): FilterNode | undefined =>
    toFilter(
      (() => {
        const d = fromFilterNode(f);
        return d.kind === 'group' && !d.not ? d : newGroup([d]);
      })(),
    );

  it('round-trips a bare condition', () => {
    const f: FilterNode = {
      kind: 'condition',
      column: 'region',
      operator: 'equal',
      value: 'EMEA',
    };
    assert.deepEqual(roundTrip(f), f);
  });

  it('round-trips a nested group', () => {
    const f: FilterNode = {
      kind: 'and',
      children: [
        // a number the editor holds is its exact text
        { kind: 'condition', column: 'a', operator: 'greaterThan', value: '5' },
        {
          kind: 'or',
          children: [
            { kind: 'condition', column: 'b', operator: 'equal', value: 'x' },
            { kind: 'condition', column: 'b', operator: 'equal', value: 'y' },
          ],
        },
      ],
    };
    assert.deepEqual(roundTrip(f), f);
  });

  it('round-trips a negation, which is a NODE here and a FLAG there', () => {
    const f: FilterNode = {
      kind: 'not',
      child: {
        kind: 'or',
        children: [
          { kind: 'condition', column: 'b', operator: 'equal', value: 'x' },
          { kind: 'condition', column: 'b', operator: 'equal', value: 'y' },
        ],
      },
    };
    assert.deepEqual(roundTrip(f), f);
  });

  it('collapses a double negation rather than showing it', () => {
    // The one place the round trip is not literal: NOT NOT x and x
    // are the same filter and the editor should show the simpler.
    const inner: FilterNode = {
      kind: 'condition',
      column: 'a',
      operator: 'equal',
      value: '1',
    };
    const f: FilterNode = { kind: 'not', child: { kind: 'not', child: inner } };
    assert.deepEqual(roundTrip(f), inner);
  });

  it('round-trips a list', () => {
    const f: FilterNode = {
      kind: 'condition',
      column: 'region',
      operator: 'in',
      value: ['EMEA', 'APAC'],
    };
    assert.deepEqual(roundTrip(f), f);
  });

  it('requotes a string that LOOKS numeric', () => {
    // Otherwise reopening the editor silently turns an account code
    // into a number, and the filter stops matching.
    const f: FilterNode = {
      kind: 'condition',
      column: 'account',
      operator: 'equal',
      value: '0042',
    };
    assert.deepEqual(roundTrip(f), f);
  });

  it('keeps a valueless operator valueless', () => {
    const f: FilterNode = {
      kind: 'condition',
      column: 'a',
      operator: 'isEmpty',
    };
    assert.deepEqual(roundTrip(f), f);
  });

  it('does NOT add a bracket level on every reopen', () => {
    const f: FilterNode = {
      kind: 'and',
      children: [
        { kind: 'condition', column: 'a', operator: 'equal', value: '1' },
        { kind: 'condition', column: 'b', operator: 'equal', value: '2' },
      ],
    };
    assert.deepEqual(roundTrip(roundTrip(f) as FilterNode), f);
  });
});

describe('TODAY and NOW', () => {
  // Upstream's advanced values (DataCubeOperationAdvancedValueType):
  // a date relative to when the query runs.
  it('parse from their call spelling only, on a date column; on a text column they are text', () => {
    assert.deepEqual(parseTyped('today()', 'StrictDate'), { relative: 'today' });
    assert.deepEqual(parseTyped('now()', 'DateTime'), { relative: 'now' });
    assert.equal(parseTyped('today', 'StrictDate'), 'today', 'text, which the compiler refuses as a date');
    assert.equal(parseTyped('today()', 'String'), 'today()');
  });

  it('render as the Pure functions, so the date moves with the day', async () => {
    assert.equal(
      (await printFilter({ kind: 'condition', column: 'trade_date',
        operator: 'lessThan', value: { relative: 'today' } }, typed)),
      'x|$x.trade_date < today()',
    );
  });
});

describe('operatorsFor a Variant', () => {
  it('offers what the compiler takes, in either spelling of the type', () => {
    // A document compares for equality and membership (fromJson), and is there or not; the
    // text operators take a String and Pure declares no order on JSON, so the compiler
    // refuses those. Extract a value (get/to) to filter by its text or its order.
    for (const t of ['Variant', 'meta::pure::metamodel::variant::Variant']) {
      assert.deepEqual(operatorsFor(t),
        ['equal', 'notEqual', 'isEmpty', 'isNotEmpty', 'in', 'notIn', 'equalColumn', 'notEqualColumn']);
    }
  });
});

// Leg B / B4, P2-150: reopening the Filters window turned each stored value into display text and
// read it back, and the round trip lost information: '' vanished, a numeric-looking string on a
// String column changed, a quoted value lost its quotes. Reopening must give back EXACTLY the
// filter the cube has, whatever its values.
describe('reopening a filter gives back exactly the filter (P2-150)', () => {
  const colType = (c: string): string | undefined => ({ region: 'String', desk: 'String', notional: 'Decimal' } as Record<string, string>)[c];
  const node = (column: string, value: string): FilterNode =>
    ({ kind: 'condition', column, operator: 'equal', value } as FilterNode);
  for (const [what, filter] of [
    ['an empty string', node('region', '')],
    ['a numeric-looking string on a String column', node('region', '00123')],
    ['a value that is itself quoted', node('desk', '"N/A"')],
    ['a decimal with a trailing zero', node('notional', '12.30')],
    ['blanks only', node('region', '  ')],
    ['a value quoted in single quotes', node('desk', "'x'")],
    ['all of them, grouped', { kind: 'and', children: [node('region', ''), node('region', '00123'),
      node('desk', '"N/A"'), node('notional', '12.30')] } as FilterNode],
  ] as const) {
    it(`${what}`, () => {
      const root = fromFilterNode(filter);
      const tree = root.kind === 'group' ? root : newGroup([root]);
      assert.deepEqual(toFilter(tree, colType), filter);
    });
  }

  it('the stored value is shown as it is (no quoting games), and published only while untouched', () => {
    const root = fromFilterNode(node('desk', '"N/A"'));
    assert.equal(root.kind === 'condition' ? root.text : '', '"N/A"', 'shown as it is');
    const tree = newGroup([root]);
    // edited: the typed text is read, by the column's type (quotes around text are stripped)
    const edited = updateNode(tree, root.id, { text: '"N/B"' });
    assert.deepEqual(toFilter(edited, colType), node('desk', 'N/B'));
    // another column: the stored value is the old column's, and is not carried over
    const moved = updateNode(tree, root.id, { column: 'region' });
    assert.deepEqual(toFilter(moved, colType), node('region', 'N/A'));
  });
});
