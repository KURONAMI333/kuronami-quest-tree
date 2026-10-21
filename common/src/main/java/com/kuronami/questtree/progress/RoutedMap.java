package com.kuronami.questtree.progress;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A map whose keys are node keys ({@code <chapter>/<node>#<index>}): entries of {@code shared:false} nodes live in the
 * player's part, the rest in the team's record. Used for task counters, repeats and cooldowns.
 */
final class RoutedMap<V> extends AbstractMap<String, V> {

    private final Map<String, V> shared;
    private final Map<String, V> personal;

    RoutedMap(Map<String, V> shared, Map<String, V> personal) {
        this.shared = shared;
        this.personal = personal;
    }

    private Map<String, V> of(String key) {
        return PlayerProgress.isPersonal(nodeOf(key)) ? personal : shared;
    }

    private static String nodeOf(String key) {
        int hash = key.lastIndexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    @Override
    public V get(Object key) {
        return key instanceof String s ? of(s).get(s) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String s && of(s).containsKey(s);
    }

    @Override
    public V put(String key, V value) {
        return of(key).put(key, value);
    }

    @Override
    public V remove(Object key) {
        return key instanceof String s ? of(s).remove(s) : null;
    }

    @Override
    public void clear() {
        shared.clear();
        personal.clear();
    }

    @Override
    public int size() {
        return shared.size() + personal.size();
    }

    @Override
    public Set<Entry<String, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return shared.size() + personal.size();
            }

            @Override
            public Iterator<Entry<String, V>> iterator() {
                return new Iterator<>() {
                    private Iterator<Entry<String, V>> first = shared.entrySet().iterator();
                    private Iterator<Entry<String, V>> second = personal.entrySet().iterator();
                    private boolean inSecond;
                    private Entry<String, V> last;

                    @Override
                    public boolean hasNext() {
                        if (!inSecond && !first.hasNext()) {
                            inSecond = true;
                        }
                        return inSecond ? second.hasNext() : first.hasNext();
                    }

                    @Override
                    public Entry<String, V> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        last = inSecond ? second.next() : first.next();
                        return last;
                    }

                    @Override
                    public void remove() {
                        if (last == null) {
                            throw new IllegalStateException();
                        }
                        (inSecond ? personal : shared).remove(last.getKey());
                        last = null;
                    }
                };
            }
        };
    }
}
