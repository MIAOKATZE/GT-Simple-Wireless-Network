package com.miaokatze.gtswn.common.monitoring;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** Server-authored native identity. A location is only a locator, never the identity. */
public final class SourceKey {

    public enum Kind {
        DIT,
        EU,
        AE,
        QUANTUM
    }

    public final Kind kind;
    public final UUID epoch;
    public final String nativeId;
    public final long generation;

    public SourceKey(Kind kind, UUID epoch, String nativeId, long generation) {
        if (kind == null || epoch == null
            || nativeId == null
            || nativeId.isEmpty()
            || nativeId.length() > 160
            || generation < 1) throw new IllegalArgumentException("Invalid source identity");
        this.kind = kind;
        this.epoch = epoch;
        this.nativeId = nativeId;
        this.generation = generation;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("kind", kind.name());
        tag.setString("epoch", epoch.toString());
        tag.setString("native", nativeId);
        tag.setLong("generation", generation);
        return tag;
    }

    public static SourceKey read(NBTTagCompound tag) {
        return new SourceKey(
            Kind.valueOf(tag.getString("kind")),
            UUID.fromString(tag.getString("epoch")),
            tag.getString("native"),
            tag.getLong("generation"));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SourceKey)) return false;
        SourceKey key = (SourceKey) other;
        return kind == key.kind && epoch.equals(key.epoch)
            && nativeId.equals(key.nativeId)
            && generation == key.generation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, epoch, nativeId, generation);
    }

    @Override
    public String toString() {
        return kind + ":" + epoch + ":" + nativeId + ":" + generation;
    }
}
