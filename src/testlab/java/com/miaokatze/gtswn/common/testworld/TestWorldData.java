// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/** Per-save journal; interrupted work is never automatically resumed after restart. */
public final class TestWorldData extends WorldSavedData {

    public int dimension;
    public int layoutSchema;
    public boolean built;
    /** Retained independently of operation/report state until a complete reset removes old fixtures. */
    public int authoredRadius = TestWorldFootprint.DEFAULT_RADIUS;
    public String operation = "IDLE";
    public String lastRun = "";
    public String lastSelection = "";
    public Boolean lastActorLevel2, lastActorLevel4;
    public String lastCleanup = "NOT_STARTED";
    public String lastActor = "not observed";
    public String lastTransport = "not observed";
    public String activeRun = "";
    public String lastOutcome = "NOT_RUN";
    public String lastReport = "";

    public TestWorldData(String name) {
        super(name);
    }

    public static TestWorldData get(World overworld) {
        return SavedDataUtil.loadOrCreate(overworld, "gtswn_testworld", TestWorldData.class, TestWorldData::new);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        dimension = nbt.getInteger("dimension");
        layoutSchema = nbt.getInteger("layoutSchema");
        built = nbt.getBoolean("built");
        authoredRadius = TestWorldFootprint.savedRadius(nbt.getInteger("authoredRadius"));
        operation = nbt.getString("operation");
        lastRun = nbt.getString("lastRun");
        lastSelection = nbt.getString("lastSelection");
        lastActorLevel2 = nbt.hasKey("lastActorLevel2") ? nbt.getBoolean("lastActorLevel2") : null;
        lastActorLevel4 = nbt.hasKey("lastActorLevel4") ? nbt.getBoolean("lastActorLevel4") : null;
        lastCleanup = nbt.hasKey("lastCleanup") ? nbt.getString("lastCleanup") : "UNKNOWN";
        lastActor = nbt.hasKey("lastActor") ? nbt.getString("lastActor") : "unknown legacy report";
        lastTransport = nbt.hasKey("lastTransport") ? nbt.getString("lastTransport") : "unknown legacy report";
        activeRun = nbt.getString("activeRun");
        lastOutcome = nbt.hasKey("lastOutcome") ? nbt.getString("lastOutcome") : "NOT_RUN";
        lastReport = nbt.getString("lastReport");
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("dimension", dimension);
        nbt.setInteger("layoutSchema", layoutSchema);
        nbt.setBoolean("built", built);
        nbt.setInteger("authoredRadius", TestWorldFootprint.savedRadius(authoredRadius));
        nbt.setString("operation", operation);
        nbt.setString("lastRun", lastRun);
        nbt.setString("lastSelection", lastSelection);
        if (lastActorLevel2 == null) nbt.removeTag("lastActorLevel2");
        else nbt.setBoolean("lastActorLevel2", lastActorLevel2);
        if (lastActorLevel4 == null) nbt.removeTag("lastActorLevel4");
        else nbt.setBoolean("lastActorLevel4", lastActorLevel4);
        nbt.setString("lastCleanup", lastCleanup);
        nbt.setString("lastActor", lastActor);
        nbt.setString("lastTransport", lastTransport);
        nbt.setString("activeRun", activeRun);
        nbt.setString("lastOutcome", lastOutcome);
        nbt.setString("lastReport", lastReport);
    }

    TestWorldFootprint beginResetFootprint(int requestedRadius) {
        TestWorldFootprint requested = new TestWorldFootprint(requestedRadius);
        authoredRadius = Math.max(TestWorldFootprint.savedRadius(authoredRadius), requested.radius);
        markDirty(); // Record the expanded recovery boundary before loading or modifying any new chunk.
        return new TestWorldFootprint(authoredRadius);
    }

    void completeResetFootprint(int requestedRadius) {
        authoredRadius = new TestWorldFootprint(requestedRadius).radius;
        markDirty();
    }
}
