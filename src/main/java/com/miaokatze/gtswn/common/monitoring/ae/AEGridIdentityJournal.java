// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.monitoring.ae;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.io.AtomicFilePublisher;

/** A single world writer. The background compactor only reads already committed revisions. */
final class AEGridIdentityJournal {

    private static final int MAGIC = 0x47534931;
    private static final int HEADER_BYTES = Integer.BYTES * 4 + Long.BYTES;
    private static final int CHECKPOINT = 1;
    private static final int RECOVERY_FENCE = 2;
    private static final int COMPACT_AFTER_BATCHES = 32;

    static final class State {

        final long revision;
        final Map<Long, UUID> storages;
        final Set<UUID> retired;
        final boolean requireBackup;

        State(long revision, Map<Long, UUID> storages, Set<UUID> retired, boolean requireBackup) {
            this.revision = revision;
            this.storages = storages;
            this.retired = retired;
            this.requireBackup = requireBackup;
        }

        State apply(State delta) {
            if (delta.requireBackup) retired.addAll(storages.values());
            storages.putAll(delta.storages);
            retired.addAll(delta.retired);
            return new State(delta.revision, storages, retired, requireBackup || delta.requireBackup);
        }
    }

    private final Path directory;
    private final Path checkpoint;
    private final Path openMarker;
    private long revision;
    private volatile long checkpointRevision;
    private Thread compactor;

    AEGridIdentityJournal(Path directory) {
        this.directory = directory;
        this.checkpoint = directory.resolve("checkpoint.bin");
        this.openMarker = directory.resolve("open");
    }

    State open(State legacy) throws IOException {
        Files.createDirectories(directory);
        if (Files.notExists(checkpoint)) {
            if (!Files.notExists(openMarker) || !batches().isEmpty()) {
                throw new IOException("AE grid identity checkpoint is missing");
            }
            write(checkpoint, legacy, CHECKPOINT);
        }
        State base = read(checkpoint, CHECKPOINT);
        checkpointRevision = base.revision;
        State state = replay(base, Long.MAX_VALUE);
        revision = state.revision;
        if (Files.notExists(openMarker)) {
            AtomicFilePublisher.publishAtomic(openMarker, new byte[] { 1 });
        } else {
            // An interrupted session may have written chunks ahead of the last identity batch.
            State fence = new State(nextRevision(), Collections.emptyMap(), Collections.emptySet(), true);
            append(fence);
            state = state.apply(fence);
            GTSimpleWirelessNetwork.LOG.warn(
                "Recovered an interrupted AE identity save session in {}. Unproven grid histories will start a new identity.",
                directory);
        }
        return state;
    }

    boolean needsLegacy() {
        return Files.notExists(checkpoint);
    }

    void save(Map<Long, UUID> storages, Set<UUID> retired) throws IOException {
        if (storages.isEmpty() && retired.isEmpty()) return;
        append(new State(nextRevision(), storages, retired, false));
        if (revision - checkpointRevision >= COMPACT_AFTER_BATCHES && (compactor == null || !compactor.isAlive())) {
            long through = revision;
            compactor = new Thread(() -> {
                try {
                    compact(through);
                } catch (IOException failure) {
                    GTSimpleWirelessNetwork.LOG.warn("Could not compact AE grid identities in {}", directory, failure);
                }
            }, "GTSWN AE identity checkpoint");
            compactor.setDaemon(true);
            compactor.start();
        }
    }

    void close(boolean clean) throws IOException {
        if (compactor != null) {
            try {
                compactor.join();
            } catch (InterruptedException failure) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException("Interrupted while closing AE grid identity journal", failure);
            }
        }
        if (clean) Files.delete(openMarker);
    }

    void compact(long through) throws IOException {
        State base = read(checkpoint, CHECKPOINT);
        State state = replay(base, through);
        if (state.revision != through) throw new IOException("AE grid identity checkpoint revision is incomplete");
        write(checkpoint, state, CHECKPOINT);
        checkpointRevision = through;
        // Keep the previous checkpoint's replay suffix through the next checkpoint replacement.
        for (Path batch : batches()) {
            if (revisionOf(batch) <= base.revision) Files.deleteIfExists(batch);
        }
    }

    private long nextRevision() throws IOException {
        if (revision == Long.MAX_VALUE) throw new IOException("AE grid identity revision overflow");
        return revision + 1;
    }

    private void append(State state) throws IOException {
        Path file = directory.resolve(String.format(Locale.ROOT, "%019d.delta", state.revision));
        if (!Files.notExists(file)) throw new IOException("AE grid identity revision already exists: " + file);
        write(file, state, 0);
        revision = state.revision;
    }

    private State replay(State state, long through) throws IOException {
        for (Path batch : batches()) {
            long number = revisionOf(batch);
            if (number <= state.revision || number > through) continue;
            if (number != state.revision + 1)
                throw new IOException("Missing AE grid identity revision before " + batch);
            State change = read(batch, 0);
            if (change.revision != number) throw new IOException("Wrong AE grid identity revision in " + batch);
            state = state.apply(change);
        }
        return state;
    }

    private List<Path> batches() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(
                path -> path.getFileName()
                    .toString()
                    .matches("[0-9]{19}\\.delta"))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    private static long revisionOf(Path path) throws IOException {
        try {
            return Long.parseLong(
                path.getFileName()
                    .toString()
                    .substring(0, 19));
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid AE grid identity revision: " + path, failure);
        }
    }

    private static void write(Path path, State state, int type) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(type | (state.requireBackup ? RECOVERY_FENCE : 0));
            out.writeLong(state.revision);
            out.writeInt(state.storages.size());
            out.writeInt(state.retired.size());
            for (Map.Entry<Long, UUID> entry : state.storages.entrySet()) {
                out.writeLong(entry.getKey());
                writeUuid(out, entry.getValue());
            }
            for (UUID uuid : state.retired) writeUuid(out, uuid);
            CRC32 checksum = new CRC32();
            checksum.update(bytes.toByteArray());
            out.writeInt((int) checksum.getValue());
        }
        AtomicFilePublisher.publishAtomic(path, bytes.toByteArray());
    }

    private static State read(Path path, int type) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int length = bytes.length - Integer.BYTES;
        if (length < HEADER_BYTES) throw new IOException("Truncated AE grid identity data: " + path);
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, length);
        if (ByteBuffer.wrap(bytes, length, Integer.BYTES)
            .getInt() != (int) checksum.getValue()) {
            throw new IOException("Bad AE grid identity checksum: " + path);
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 0, length))) {
            int magic = in.readInt(), flags = in.readInt();
            long revision = in.readLong();
            int mappings = in.readInt(), retiredCount = in.readInt();
            boolean recovery = (flags & RECOVERY_FENCE) != 0;
            if (magic != MAGIC || (flags & ~RECOVERY_FENCE) != type
                || revision < 0
                || mappings < 0
                || retiredCount < 0
                || (type == 0 && recovery && (mappings != 0 || retiredCount != 0))
                || length != HEADER_BYTES + 24L * mappings + 16L * retiredCount) {
                throw new IOException("Invalid AE grid identity data: " + path);
            }
            Map<Long, UUID> storages = new LinkedHashMap<>();
            Set<UUID> retired = new LinkedHashSet<>();
            for (int i = 0; i < mappings; i++) {
                long storage = in.readLong();
                if (storage < 0 || storages.put(storage, readUuid(in)) != null) {
                    throw new IOException("Invalid AE grid storage ID in " + path);
                }
            }
            for (int i = 0; i < retiredCount; i++) retired.add(readUuid(in));
            return new State(revision, storages, retired, recovery);
        }
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }
}
