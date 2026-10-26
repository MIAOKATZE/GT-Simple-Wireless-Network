package com.miaokatze.gtswn.common.panel;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Main-thread, one-tick memo of native grid reads. Never grants access or shares panel histories. */
final class AESharedSampling {

    private static final int MAX_SOURCES = 1024;
    private static final int MAX_KEYS = 4096;
    private final Map<Key, Frame> frames = new HashMap<>();

    Map<String, Long> collect(Object world, Object grid, long tick, Set<String> requested,
        Function<Set<String>, Map<String, Long>> collector) {
        if (requested.isEmpty()) return Collections.emptyMap();
        // Unknown identity is deliberately unshareable, not a global "null grid" bucket.
        if (world == null || grid == null || requested.size() > MAX_KEYS) return collector.apply(requested);
        Iterator<Key> iterator = frames.keySet()
            .iterator();
        while (iterator.hasNext()) if (!iterator.next()
            .alive()) iterator.remove();
        Key key = new Key(world, grid);
        Frame frame = frames.get(key);
        if (frame == null) {
            if (frames.size() >= MAX_SOURCES) frames.clear();
            frame = new Frame(tick);
            frames.put(key, frame);
        } else if (frame.tick != tick) {
            frame.tick = tick;
            frame.amounts.clear();
        }
        Set<String> missing = new HashSet<>(requested);
        missing.removeAll(frame.amounts.keySet());
        if (!missing.isEmpty()) {
            Map<String, Long> fresh = collector.apply(Collections.unmodifiableSet(missing));
            // A failed native read is unavailable, not a zero-count sample or a partial fresh snapshot.
            if (fresh == null || !fresh.keySet()
                .containsAll(missing)) return Collections.emptyMap();
            for (String name : missing) if (fresh.get(name) == null) return Collections.emptyMap();
            if (frame.amounts.size() + missing.size() > MAX_KEYS) frame.amounts.clear();
            for (String name : missing) frame.amounts.put(name, fresh.get(name));
            // Overflow eviction must not turn previously cached requested values into fake zeroes.
            if (!frame.amounts.keySet()
                .containsAll(requested)) return collector.apply(requested);
        }
        Map<String, Long> projected = new HashMap<>();
        for (String name : requested) projected.put(name, frame.amounts.get(name));
        return projected;
    }

    private static final class Frame {

        long tick;
        final Map<String, Long> amounts = new HashMap<>();

        Frame(long tick) {
            this.tick = tick;
        }
    }

    private static final class Key {

        final WeakReference<Object> world, grid;
        final int hash;

        Key(Object world, Object grid) {
            this.world = new WeakReference<>(world);
            this.grid = new WeakReference<>(grid);
            hash = System.identityHashCode(world) * 31 + System.identityHashCode(grid);
        }

        boolean alive() {
            return world.get() != null && grid.get() != null;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object object) {
            if (object == this) return true;
            if (!(object instanceof Key) || !alive()) return false;
            Key other = (Key) object;
            return world.get() == other.world.get() && grid.get() == other.grid.get();
        }
    }
}
