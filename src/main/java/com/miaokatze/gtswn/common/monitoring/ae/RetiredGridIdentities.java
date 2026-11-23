// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.monitoring.ae;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact, append-only membership within a world session. An insertion can grow only its own shard. */
final class RetiredGridIdentities extends AbstractSet<UUID> {

    private static final int SHARD_BITS = 8;
    private final List<Set<UUID>> shards = new ArrayList<>(Collections.nCopies(1 << SHARD_BITS, null));
    private int size;

    @Override
    public boolean add(UUID id) {
        int index = shardIndex(id);
        Set<UUID> shard = shards.get(index);
        if (shard == null) {
            shard = new HashSet<>(4);
            shards.set(index, shard);
        }
        if (!shard.add(id)) return false;
        size++;
        return true;
    }

    @Override
    public boolean contains(Object value) {
        if (!(value instanceof UUID id)) return false;
        Set<UUID> shard = shards.get(shardIndex(id));
        return shard != null && shard.contains(id);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        Collections.fill(shards, null);
        size = 0;
    }

    @Override
    public Iterator<UUID> iterator() {
        return shards.stream()
            .filter(Objects::nonNull)
            .flatMap(Set::stream)
            .iterator();
    }

    private static int shardIndex(UUID id) {
        // Mix both halves separately from HashSet's bucket hash, including for old UUIDs with common prefixes.
        long mixed = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 23);
        return (int) (mixed * 0x9E3779B97F4A7C15L >>> (Long.SIZE - SHARD_BITS));
    }
}
