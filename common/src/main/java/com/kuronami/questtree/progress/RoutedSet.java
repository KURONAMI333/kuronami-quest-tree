package com.kuronami.questtree.progress;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A set that keeps node keys belonging to {@code shared:false} nodes in one player's part and the rest in the team's
 * record. The rest of the code keeps reading {@code completed} directly; only the backing store changes.
 */
final class RoutedSet extends AbstractSet<String> {

    private final Set<String> shared;
    private final Set<String> personal;

    RoutedSet(Set<String> shared, Set<String> personal) {
        this.shared = shared;
        this.personal = personal;
    }

    private Set<String> of(String key) {
        return PlayerProgress.isPersonal(key) ? personal : shared;
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof String key && of(key).contains(key);
    }

    @Override
    public boolean add(String key) {
        return of(key).add(key);
    }

    @Override
    public boolean remove(Object o) {
        return o instanceof String key && of(key).remove(key);
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
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private Iterator<String> first = shared.iterator();
            private Iterator<String> second = personal.iterator();
            private boolean inSecond;
            private String last;
            private boolean removalFromSecond;

            @Override
            public boolean hasNext() {
                if (!inSecond && !first.hasNext()) {
                    inSecond = true;
                }
                return inSecond ? second.hasNext() : first.hasNext();
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                removalFromSecond = inSecond;
                last = inSecond ? second.next() : first.next();
                return last;
            }

            @Override
            public void remove() {
                if (last == null) {
                    throw new IllegalStateException();
                }
                (removalFromSecond ? personal : shared).remove(last);
                last = null;
            }
        };
    }
}
