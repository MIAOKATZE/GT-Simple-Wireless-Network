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
