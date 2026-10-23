package com.miaokatze.gtswn.common.quantum;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Ordered live positions for bounded reads; never retains a world or a node strongly. */
final class WeakPositionIndex<T> {

    private static final Comparator<AnchorKey> ORDER = Comparator.comparingInt(AnchorKey::getDimension)
        .thenComparingInt(AnchorKey::getX)
        .thenComparingInt(AnchorKey::getY)
        .thenComparingInt(AnchorKey::getZ);
    private final Map<AnchorKey, PositionReference<T>> positions = new TreeMap<>(ORDER);
    private final Map<T, AnchorKey> keys = new WeakHashMap<>();
    private final ReferenceQueue<T> expired = new ReferenceQueue<>();

    void put(T value, int dimension, int x, int y, int z) {
        reap();
        AnchorKey key = new AnchorKey(dimension, x, y, z);
        AnchorKey previous = keys.put(value, key);
        if (previous != null && !previous.equals(key)) removePosition(value, previous);
        positions.put(key, new PositionReference<>(value, key, expired));
    }

    void remove(T value) {
        reap();
        AnchorKey key = keys.remove(value);
        if (key != null) removePosition(value, key);
    }

    void removeIf(Predicate<T> predicate) {
        Iterator<PositionReference<T>> iterator = positions.values()
            .iterator();
        while (iterator.hasNext()) {
            PositionReference<T> reference = iterator.next();
            T value = reference.get();
            if (value == null || predicate.test(value)) {
                iterator.remove();
                if (value != null && reference.key.equals(keys.get(value))) keys.remove(value);
            }
        }
    }

    private void removePosition(T value, AnchorKey key) {
        PositionReference<T> reference = positions.get(key);
        if (reference != null && reference.get() == value) positions.remove(key);
    }

    Iterable<T> values() {
        reap();
        return () -> new Iterator<T>() {

            private final Iterator<PositionReference<T>> iterator = positions.values()
                .iterator();

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public T next() {
                T value = iterator.next()
                    .get();
                // Dead positions remain one iteration each, so the caller's inspection budget still applies.
                if (value == null) iterator.remove();
                return value;
            }
        };
    }

    private void reap() {
        // A large GC batch must not turn a bounded dashboard read into an unbounded cleanup pass.
        for (int i = 0; i < 64; i++) {
            @SuppressWarnings("unchecked")
            PositionReference<T> reference = (PositionReference<T>) expired.poll();
            if (reference == null) break;
            positions.remove(reference.key, reference);
        }
    }

    private static final class PositionReference<T> extends WeakReference<T> {

        final AnchorKey key;

        PositionReference(T value, AnchorKey key, ReferenceQueue<T> queue) {
            super(value, queue);
            this.key = key;
        }
    }
}
