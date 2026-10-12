"""Port of TeaVM master's unmodifiable-wrapper delegation (5971683cf1) onto 0.15.0's TCollections.java, plus the same
for unmodifiableCollection: contains/containsAll/isEmpty (set, collection) and get/containsKey/containsValue/size/
isEmpty (map) delegate to the wrapped instance, as the JDK's wrappers do."""
import sys
src, dst = sys.argv[1], sys.argv[2]
t = open(src, encoding='utf-8').read()
def put(anchor, extra, count=1):
    global t
    assert t.count(anchor) == count, (anchor, t.count(anchor))
    t = t.replace(anchor, anchor + extra)
# the collection wrapper (c) and the set wrapper (s): after their size()
for var, cls in (('c', 'TAbstractCollection'), ('s', 'TAbstractSet')):
    anchor = ("        return new %s<>() {\n            @Override public TIterator<T> iterator() {\n"
              "                return unmodifiableIterator(%s.iterator());\n            }\n"
              "            @Override public int size() {\n                return %s.size();\n            }\n") % (cls, var, var)
    put(anchor,
        "            @Override public boolean contains(Object o) {\n                return %s.contains(o);\n            }\n"
        "            @Override public boolean containsAll(TCollection<?> other) {\n                return %s.containsAll(other);\n            }\n"
        "            @Override public boolean isEmpty() {\n                return %s.isEmpty();\n            }\n" % (var, var, var))
# the map wrapper (m): after its entrySet()
anchor = ("        return new TAbstractMap<>() {\n            @Override public TSet<Entry<K, V>> entrySet() {\n"
          "                return unmodifiableMapEntrySet(m.entrySet());\n            }\n")
put(anchor,
    "            @Override public V get(Object key) {\n                return m.get(key);\n            }\n"
    "            @Override public boolean containsKey(Object key) {\n                return m.containsKey(key);\n            }\n"
    "            @Override public boolean containsValue(Object value) {\n                return m.containsValue(value);\n            }\n"
    "            @Override public int size() {\n                return m.size();\n            }\n"
    "            @Override public boolean isEmpty() {\n                return m.isEmpty();\n            }\n")
open(dst, 'w', encoding='utf-8').write(t)
print("written", dst, len(t.splitlines()), "lines")
