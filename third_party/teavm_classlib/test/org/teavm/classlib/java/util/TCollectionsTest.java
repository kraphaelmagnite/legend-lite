// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.teavm.classlib.java.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.teavm.classlib.java.lang.TUnsupportedOperationException;

/**
 * The corrected TCollections, run on the JVM (it is plain Java): unmodifiableSet and unmodifiableMap answer their
 * lookups from the wrapped instance and open no iterator, and still refuse every mutation. TeaVM 0.15.0's wrappers
 * overrode iterator, size and remove only, so TAbstractCollection's contains and TAbstractMap's get scanned the whole
 * instance for each lookup: the tab's 3.6 s boot and 4 s compile of a 200-class page
 * (docs/build-inventory/program/TEAVM_UNMODIFIABLE_DESIGN_2026_10_11.md). The wrapped set and map here answer their
 * lookups directly and count the iterators (the entry sets) they hand out, so a wrapper that scans is seen.
 */
class TCollectionsTest {

    /** A set answering its lookups directly, counting the iterators it opens. */
    private static final class CountingSet extends TAbstractSet<String> {
        final LinkedHashSet<String> members = new LinkedHashSet<>();
        int iterators;

        @Override
        public TIterator<String> iterator() {
            iterators++;
            Iterator<String> it = members.iterator();
            return new TIterator<>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public String next() {
                    return it.next();
                }

                @Override
                public void remove() {
                    it.remove();
                }
            };
        }

        @Override
        public int size() {
            return members.size();
        }

        @Override
        public boolean isEmpty() {
            return members.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return members.contains(o);
        }

        @Override
        public boolean containsAll(TCollection<?> c) {
            for (TIterator<?> it = c.iterator(); it.hasNext();) {
                if (!members.contains(it.next())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean add(String e) {
            return members.add(e);
        }

        @Override
        public boolean remove(Object o) {
            return members.remove(o);
        }
    }

    /** A map answering its lookups directly, counting the entry sets it hands out (TAbstractMap scans through them). */
    private static final class CountingMap extends TAbstractMap<String, Integer> {
        final LinkedHashMap<String, Integer> entries = new LinkedHashMap<>();
        int entrySets;

        @Override
        public TSet<TMap.Entry<String, Integer>> entrySet() {
            entrySets++;
            return new TAbstractSet<>() {
                @Override
                public int size() {
                    return entries.size();
                }

                @Override
                public TIterator<TMap.Entry<String, Integer>> iterator() {
                    Iterator<Map.Entry<String, Integer>> it = entries.entrySet().iterator();
                    return new TIterator<>() {
                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public TMap.Entry<String, Integer> next() {
                            Map.Entry<String, Integer> e = it.next();
                            return new TAbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue());
                        }

                        @Override
                        public void remove() {
                            it.remove();
                        }
                    };
                }
            };
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return entries.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return entries.containsValue(value);
        }

        @Override
        public Integer get(Object key) {
            return entries.get(key);
        }

        @Override
        public Integer put(String key, Integer value) {
            return entries.put(key, value);
        }
    }

    /**
     * In the module both are the one java.lang.UnsupportedOperationException (TeaVM compiles its T-classes as the
     * JDK's); on the JVM TeaVM's TUnsupportedOperationException is a class of its own, so a refusal may be either.
     */
    private static void refused(String what, Executable mutation) {
        try {
            mutation.execute();
        } catch (UnsupportedOperationException | TUnsupportedOperationException e) {
            return;
        } catch (Throwable t) {
            fail(what + " failed with " + t + " instead of refusing");
        }
        fail(what + " was not refused");
    }

    @Test
    void theUnmodifiableSetLooksUpWithoutAnIteratorAndStillRefusesMutation() {
        CountingSet s = new CountingSet();
        s.add("a");
        s.add("b");
        s.add("c");
        CountingSet asked = new CountingSet();
        asked.add("a");
        asked.add("c");
        TSet<String> u = TCollections.unmodifiableSet(s);

        assertTrue(u.contains("b"));
        assertFalse(u.contains("z"));
        assertTrue(u.containsAll(asked));
        asked.add("z");
        assertFalse(u.containsAll(asked));
        assertEquals(3, u.size());
        assertFalse(u.isEmpty());
        assertEquals(0, s.iterators, "a lookup through the wrapper opened an iterator on the wrapped set");

        // a view, not a copy
        s.add("d");
        assertTrue(u.contains("d"));
        assertEquals(4, u.size());
        assertEquals(0, s.iterators);

        refused("add", () -> u.add("e"));
        refused("remove", () -> u.remove("a"));
        refused("clear", u::clear);
        TIterator<String> it = u.iterator();
        assertEquals("a", it.next());
        refused("the iterator's remove", it::remove);
        assertEquals(List.of("a", "b", "c", "d"), List.copyOf(s.members), "a refused mutation changed the set");
    }

    @Test
    void theUnmodifiableMapLooksUpWithoutAnEntrySetAndStillRefusesMutation() {
        CountingMap m = new CountingMap();
        m.put("one", 1);
        m.put("two", 2);
        TMap<String, Integer> u = TCollections.unmodifiableMap(m);

        assertEquals(2, u.get("two"));
        assertNull(u.get("three"));
        assertTrue(u.containsKey("one"));
        assertFalse(u.containsKey("three"));
        assertTrue(u.containsValue(1));
        assertFalse(u.containsValue(3));
        assertEquals(2, u.size());
        assertFalse(u.isEmpty());
        assertEquals(0, m.entrySets, "a lookup through the wrapper asked the wrapped map for its entry set");

        // a view, not a copy
        m.put("three", 3);
        assertEquals(3, u.get("three"));
        assertEquals(3, u.size());
        assertEquals(0, m.entrySets);

        refused("put", () -> u.put("four", 4));
        refused("remove", () -> u.remove("one"));
        refused("clear", u::clear);
        TIterator<TMap.Entry<String, Integer>> it = u.entrySet().iterator();
        assertEquals("one", it.next().getKey());
        refused("the entry iterator's remove", it::remove);
        assertEquals(Map.of("one", 1, "two", 2, "three", 3), Map.copyOf(m.entries),
                "a refused mutation changed the map");
    }
}
