package com.miaokatze.gtswn.common.monitoring;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtswn.common.util.EUDataSet;

/** MTE local-rate history provenance. Preserves old/legacy windows instead of joining different accounts. */
public final class NativeEUHistory {

    public static final int MAX_ARCHIVES = 64;
    private String source = "";
    private NBTTagList archives = new NBTTagList();
    private boolean continuityPaused;

    /** False means the preservation quota is full: caller must not append a new source to the old window. */
    public boolean bind(String token, EUDataSet active) {
        if (continuityPaused || token == null || token.isEmpty()) return false;
        if (token.equals(source)) return true;
        if (!active.isEmpty()) {
            if (archives.tagCount() >= MAX_ARCHIVES) return false;
            NBTTagCompound archive = new NBTTagCompound();
            archive.setString("source", source.isEmpty() ? "legacy-unverified" : source);
            active.saveToNBT(archive, "history");
            archives.appendTag(archive);
        }
        active.clear();
        source = token;
        return true;
    }

    /** Persistent realm/account identity, deliberately independent of the HUD/cache runtime epoch. */
    public boolean bindDurable(UUID realm, UUID account, EUDataSet active) {
        return realm != null && account != null && bind("account:" + realm + ":" + account, active);
    }

    /** A genuine clock rollback preserves the old window but cannot derive a rate across timelines. */
    public boolean breakClockContinuity(EUDataSet active) {
        if (continuityPaused) return false;
        if (!active.isEmpty()) {
            if (archives.tagCount() >= MAX_ARCHIVES) {
                continuityPaused = true;
                return false;
            }
            NBTTagCompound archive = new NBTTagCompound();
            archive.setString("source", source.isEmpty() ? "legacy-unverified" : source);
            archive.setString("boundary", "clock-rollback");
            active.saveToNBT(archive, "history");
            archives.appendTag(archive);
        }
        active.clear();
        return true;
    }

    public void write(NBTTagCompound tag) {
        tag.setString("nativeEUSource", source);
        tag.setBoolean("nativeEUContinuityPaused", continuityPaused);
        tag.setTag("nativeEUHistoryArchives", archives.copy());
    }

    public void read(NBTTagCompound tag) {
        source = tag.getString("nativeEUSource");
        continuityPaused = tag.getBoolean("nativeEUContinuityPaused");
        archives = (NBTTagList) tag.getTagList("nativeEUHistoryArchives", 10)
            .copy();
    }

    public String source() {
        return source;
    }

    public int archiveCount() {
        return archives.tagCount();
    }
}
