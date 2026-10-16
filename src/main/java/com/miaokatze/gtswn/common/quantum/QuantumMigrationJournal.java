package com.miaokatze.gtswn.common.quantum;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/** Durable ordered binding changes, replayed lazily by terminals, nodes and incorporation entries. */
public final class QuantumMigrationJournal extends WorldSavedData {

    private static final String NAME = "gtswn_quantum_migrations";
    private final NavigableMap<Long, Event> events = new TreeMap<>();
    private final Map<AnchorKey, NavigableMap<Long, Event>> sources = new HashMap<>();
    private long sequence;

    public QuantumMigrationJournal(String name) {
        super(name);
    }

    public static QuantumMigrationJournal get(World world) {
        WorldServer overworld = DimensionManager.getWorld(0);
        // The overworld remains loaded on a running server; fallback permits isolated world fixtures.
        World storageWorld = overworld == null ? world : overworld;
        return SavedDataUtil
            .loadOrCreate(storageWorld, NAME, QuantumMigrationJournal.class, QuantumMigrationJournal::new);
    }

    public long currentSequence() {
        return sequence;
    }

    public long append(int dimension, Set<Long> members, AnchorKey target) {
        return append(dimension, members, target, "", "");
    }

    public long append(int dimension, Set<Long> members, AnchorKey target, String sourceGeneration,
        String targetGeneration) {
        return append(dimension, members, target, sourceGeneration, targetGeneration, Collections.emptySet());
    }

    public long append(int dimension, Set<Long> members, AnchorKey target, String sourceGeneration,
        String targetGeneration, Set<Long> legacyMembers) {
        if (members.isEmpty() || sequence == Long.MAX_VALUE) throw new IllegalStateException("Invalid migration");
        long[] positions = new long[members.size()];
        int i = 0;
        for (long member : members) positions[i++] = member;
        Set<Long> eligible = new HashSet<>(legacyMembers);
        eligible.retainAll(members);
        Event event = new Event(++sequence, dimension, positions, target, sourceGeneration, targetGeneration, eligible);
        index(event);
        markDirty();
        QuantumTerminalSyncEpoch.changed();
        return sequence;
    }

    /** Cursor advances even for unrelated events; revisiting a coordinate cannot form a redirect loop. */
    public Resolution resolve(AnchorKey anchor, long cursor) {
        return resolve(anchor, cursor, "");
    }

    public Resolution resolve(AnchorKey anchor, long cursor, String expectedGeneration) {
        AnchorKey result = anchor;
        String generation = expectedGeneration == null ? "" : expectedGeneration;
        long applied = cursor;
        while (applied < sequence) {
            NavigableMap<Long, Event> indexed = sources.get(result);
            Map.Entry<Long, Event> next = indexed == null ? null : indexed.higherEntry(applied);
            if (next == null) break;
            Event event = next.getValue();
            applied = event.sequence;
            if (event.sourceGeneration.isEmpty() || event.sourceGeneration.equals(generation)
                || (generation.isEmpty() && event.legacyMembers
                    .contains(QuantumControllerRegistry.pack(result.getX(), result.getY(), result.getZ())))) {
                result = event.target;
                if (!event.targetGeneration.isEmpty()) generation = event.targetGeneration;
            }
        }
        return new Resolution(result, Math.max(cursor, sequence), generation);
    }

    private void index(Event event) {
        events.put(event.sequence, event);
        for (long member : event.members) {
            AnchorKey source = new AnchorKey(
                event.dimension,
                QuantumControllerRegistry.unpackX(member),
                QuantumControllerRegistry.unpackY(member),
                QuantumControllerRegistry.unpackZ(member));
            sources.computeIfAbsent(source, ignored -> new TreeMap<>())
                .put(event.sequence, event);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        QuantumTerminalSyncEpoch.changed();
        events.clear();
        sources.clear();
        sequence = Math.max(0L, tag.getLong("sequence"));
        NBTTagList list = tag.getTagList("events", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i);
            long id = n.getLong("sequence");
            int[] flat = n.getIntArray("members");
            long[] members = new long[flat.length / 3];
            for (int j = 0; j < members.length; j++)
                members[j] = QuantumControllerRegistry.pack(flat[j * 3], flat[j * 3 + 1], flat[j * 3 + 2]);
            Set<Long> legacyMembers = new HashSet<>();
            int[] legacy = n.getIntArray("legacyMembers");
            for (int j = 0; j + 2 < legacy.length; j += 3)
                legacyMembers.add(QuantumControllerRegistry.pack(legacy[j], legacy[j + 1], legacy[j + 2]));
            if (id <= 0 || members.length == 0) continue;
            sequence = Math.max(sequence, id);
            index(
                new Event(
                    id,
                    n.getInteger("dimension"),
                    members,
                    new AnchorKey(n.getInteger("targetDim"), n.getInteger("x"), n.getInteger("y"), n.getInteger("z")),
                    n.getString("sourceGeneration"),
                    n.getString("targetGeneration"),
                    legacyMembers));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setLong("sequence", sequence);
        NBTTagList list = new NBTTagList();
        for (Event event : events.values()) {
            NBTTagCompound n = new NBTTagCompound();
            n.setLong("sequence", event.sequence);
            n.setInteger("dimension", event.dimension);
            int[] flat = new int[event.members.length * 3];
            for (int i = 0; i < event.members.length; i++) {
                flat[i * 3] = QuantumControllerRegistry.unpackX(event.members[i]);
                flat[i * 3 + 1] = QuantumControllerRegistry.unpackY(event.members[i]);
                flat[i * 3 + 2] = QuantumControllerRegistry.unpackZ(event.members[i]);
            }
            n.setIntArray("members", flat);
            n.setInteger("targetDim", event.target.getDimension());
            n.setInteger("x", event.target.getX());
            n.setInteger("y", event.target.getY());
            n.setInteger("z", event.target.getZ());
            n.setString("sourceGeneration", event.sourceGeneration);
            n.setString("targetGeneration", event.targetGeneration);
            int[] legacy = new int[event.legacyMembers.size() * 3];
            int legacyIndex = 0;
            for (long position : event.legacyMembers) {
                legacy[legacyIndex++] = QuantumControllerRegistry.unpackX(position);
                legacy[legacyIndex++] = QuantumControllerRegistry.unpackY(position);
                legacy[legacyIndex++] = QuantumControllerRegistry.unpackZ(position);
            }
            n.setIntArray("legacyMembers", legacy);
            list.appendTag(n);
        }
        tag.setTag("events", list);
    }

    public static final class Resolution {

        public final AnchorKey anchor;
        public final long sequence;
        public final String generation;

        private Resolution(AnchorKey anchor, long sequence, String generation) {
            this.anchor = anchor;
            this.sequence = sequence;
            this.generation = generation;
        }
    }

    private static final class Event {

        final long sequence;
        final int dimension;
        final long[] members;
        final AnchorKey target;
        final String sourceGeneration, targetGeneration;
        final Set<Long> legacyMembers;

        Event(long sequence, int dimension, long[] members, AnchorKey target, String sourceGeneration,
            String targetGeneration, Set<Long> legacyMembers) {
            this.sequence = sequence;
            this.dimension = dimension;
            this.members = members;
            this.target = target;
            this.sourceGeneration = sourceGeneration == null ? "" : sourceGeneration;
            this.targetGeneration = targetGeneration == null ? "" : targetGeneration;
            this.legacyMembers = legacyMembers;
        }
    }
}
