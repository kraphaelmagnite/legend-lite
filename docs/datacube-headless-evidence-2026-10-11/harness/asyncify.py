"""MEASUREMENT OVERLAY codemod: the tests' calls of lite-compiler's printers and parsers awaited, as DataCube's own
planner API is awaited -- over a real TypeScript syntax tree (tree-sitter), to a fixed point: a function made async
because it awaits makes its own callers await it. Calls inside an `assert.throws(() => ...)` stay as they are (the
builder's refusal still throws synchronously). Callbacks of array methods are listed for a person to check.

    python -I asyncify.py <overlay datacube/test dir>
"""
import re
import sys
from pathlib import Path

import tree_sitter_typescript as tst
from tree_sitter import Language, Parser

PARSER = Parser(Language(tst.language_typescript()))
HELPERS = {'row', 'source', 'print', 'printLevel', 'printValues', 'printChildren', 'printDrill', 'printFilter'}
FUNCTIONS = {'arrow_function', 'function_declaration', 'function_expression', 'function', 'method_definition',
             'generator_function_declaration'}
ARRAY_METHODS = {'map', 'forEach', 'filter', 'some', 'every', 'find', 'findIndex', 'flatMap', 'reduce', 'sort'}


def imported(src):
    m = re.search(r"import \{([^}]*)\} from '\./lite-compiler\.ts'", src)
    names = set()
    for m in re.finditer(r"import \{([^}]*)\} from '\./lite-compiler\.ts'", src):
        for part in m.group(1).split(','):
            bits = part.strip().split(' as ')
            if bits[0].strip() in HELPERS:
                names.add(bits[-1].strip())
    return names


def walk(node):
    yield node
    for c in node.children:
        yield from walk(c)


def enclosing_function(node):
    n = node.parent
    while n is not None:
        if n.type in FUNCTIONS:
            return n
        n = n.parent
    return None


def is_async(fn, src):
    return src[fn.start_byte:fn.end_byte].lstrip().startswith(b'async')


def inside_throws_callback(node, src):
    """Whether `node` is inside the function given as assert.throws' / doesNotThrow's first argument."""
    n = node.parent
    while n is not None:
        if n.type in FUNCTIONS and n.parent is not None and n.parent.type == 'arguments':
            call = n.parent.parent
            callee = src[call.child_by_field_name('function').start_byte:call.child_by_field_name('function').end_byte]
            if callee in (b'assert.throws', b'assert.doesNotThrow') and n.parent.named_children[0] == n:
                return True
        n = n.parent
    return False


def returned(call, fn):
    """Whether `call` is the value its function returns: an arrow's expression body, or a `return <call>` statement's."""
    p = call.parent
    while p is not None and p.type == 'parenthesized_expression':
        p = p.parent
    same = lambda a, b: a is not None and b is not None and (a.start_byte, a.end_byte, a.type) == (b.start_byte, b.end_byte, b.type)
    if same(p, fn) and fn.type == 'arrow_function':
        return True
    return p is not None and p.type == 'return_statement' and same(enclosing_function(p), fn)


def name_of(fn, src):
    if fn.type in ('function_declaration', 'generator_function_declaration'):
        n = fn.child_by_field_name('name')
        return src[n.start_byte:n.end_byte].decode() if n else None
    if fn.parent is not None and fn.parent.type == 'variable_declarator':
        n = fn.parent.child_by_field_name('name')
        return src[n.start_byte:n.end_byte].decode() if n else None
    if fn.type == 'method_definition':
        return None
    return None


def callback_of(fn, src):
    """The array method a function is the callback of, if it is one: `xs.map((x) => ...)`."""
    if fn.parent is None or fn.parent.type != 'arguments':
        return None
    call = fn.parent.parent
    f = call.child_by_field_name('function')
    if f is not None and f.type == 'member_expression':
        prop = f.child_by_field_name('property')
        method = src[prop.start_byte:prop.end_byte].decode()
        if method in ARRAY_METHODS:
            return method
    return None


def asyncify(path, review):
    src = path.read_bytes()
    names = imported(src.decode()) | set(filter(None, __import__('os').environ.get('ALSO', '').split(',')))
    if not names:
        return 0
    total = 0
    while True:
        tree = PARSER.parse(src)
        edits = []          # (byte offset, text to insert)
        made_async = set()
        grew = False
        for node in walk(tree.root_node):
            if node.type != 'call_expression':
                continue
            f = node.child_by_field_name('function')
            if f is None or f.type != 'identifier' or src[f.start_byte:f.end_byte].decode() not in names:
                continue
            if node.parent is not None and node.parent.type == 'await_expression':
                continue
            if node.parent is not None and node.parent.type == 'parenthesized_expression' and node.parent.parent is not None \
                    and node.parent.parent.type == 'await_expression':
                continue
            if inside_throws_callback(node, src):
                continue
            fn = enclosing_function(node)
            if fn is not None and returned(node, fn):
                n = name_of(fn, src)
                if n and n not in names:
                    names.add(n)
                    grew = True
                continue
            edits.append((node.start_byte, b'(await '))
            edits.append((node.end_byte, b')'))
            if fn is not None and not is_async(fn, src) and fn.start_byte not in made_async:
                made_async.add(fn.start_byte)
                edits.append((fn.start_byte, b'async '))
                n = name_of(fn, src)
                if n:
                    names.add(n)
                if n and re.search(rb'[(,]\s*' + n.encode() + rb'\s*[),]', src):
                    review.append(f'{path.name}:{src[:fn.start_byte].count(b"\\n") + 1}: {n} made async and passed by name -- check who awaits it')
                method = callback_of(fn, src)
                line = src[:fn.start_byte].count(b'\n') + 1
                if method:
                    review.append(f'{path.name}:{line}: a .{method}() callback made async -- check its result is awaited')
        # R2: a describe whose callback is async now is itself awaited, so its tests register in it
        for node in walk(tree.root_node):
            if node.type != 'call_expression':
                continue
            f = node.child_by_field_name('function')
            if f is None or src[f.start_byte:f.end_byte] not in (b'describe', b'describe.skip'):
                continue
            args = node.child_by_field_name('arguments')
            cb = next((a for a in (args.named_children if args else []) if a.type in FUNCTIONS), None)
            if cb is None or not (is_async(cb, src) or cb.start_byte in made_async):
                continue
            if node.parent is not None and node.parent.type == 'await_expression':
                continue
            if (node.start_byte, b'(await ') not in edits:
                edits.append((node.start_byte, b'(await '))
                edits.append((node.end_byte, b')'))
                outer = enclosing_function(node)
                if outer is not None and not is_async(outer, src) and outer.start_byte not in made_async:
                    made_async.add(outer.start_byte)
                    edits.append((outer.start_byte, b'async '))
        if not edits and not grew:
            return total
        if not edits:
            continue
        # end-first, and at one offset in the order they were made (an insertion at a call's start before `async`)
        for offset, text in sorted(edits, key=lambda e: -e[0]):
            src = src[:offset] + text + src[offset:]
        total += sum(1 for _, t in edits if t == b'(await ')
        path.write_bytes(src)


review = []
root = Path(sys.argv[1])
for p in sorted(root.glob('*.test.ts')):
    n = asyncify(p, review)
    if n:
        print(f'{p.name:28} {n} calls awaited')
print('\nTO CHECK BY HAND:' if review else '\nnothing to check by hand')
for r in review:
    print('  ' + r)
