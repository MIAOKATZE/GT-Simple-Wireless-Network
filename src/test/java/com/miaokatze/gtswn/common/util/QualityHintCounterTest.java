package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class QualityHintCounterTest {

    @Test
    public void eachCategoryCountsDownExactlyTenTimesAndPlayersAreIndependent() {
        NBTTagCompound first = new NBTTagCompound();
        NBTTagCompound second = new NBTTagCompound();
        for (int remaining = 9; remaining >= 0; remaining--) {
            for (QualityHintCounter.Kind kind : QualityHintCounter.Kind.values()) {
                assertEquals(remaining, QualityHintCounter.consume(first, kind));
            }
        }
        for (QualityHintCounter.Kind kind : QualityHintCounter.Kind.values()) {
            assertEquals(-1, QualityHintCounter.consume(first, kind));
            assertEquals(-1, QualityHintCounter.consume(first, kind));
            assertEquals(9, QualityHintCounter.consume(second, kind));
        }
    }

    @Test
    public void copiedPersistentDataKeepsCountersWithoutChangingOtherPlayerData() {
        NBTTagCompound persisted = new NBTTagCompound();
        NBTTagCompound namespace = new NBTTagCompound();
        namespace.setString("existing", "keep");
        persisted.setTag("gtswn", namespace);
        persisted.setString("otherMod", "keep");
        assertEquals(9, QualityHintCounter.consume(persisted, QualityHintCounter.Kind.NODE));
        NBTTagCompound restored = (NBTTagCompound) persisted.copy();
        assertEquals(8, QualityHintCounter.consume(restored, QualityHintCounter.Kind.NODE));
        assertEquals(9, QualityHintCounter.consume(restored, QualityHintCounter.Kind.LINK));
        assertEquals("keep", restored.getString("otherMod"));
        assertEquals(
            "keep",
            restored.getCompoundTag("gtswn")
                .getString("existing"));
    }

    @Test
    public void malformedCountsAreClampedAndDoNotOverflow() {
        NBTTagCompound persisted = new NBTTagCompound();
        NBTTagCompound namespace = new NBTTagCompound();
        NBTTagCompound counters = new NBTTagCompound();
        counters.setInteger("node", -100);
        counters.setInteger("link", Integer.MAX_VALUE);
        namespace.setTag("qualityHints", counters);
        persisted.setTag("gtswn", namespace);
        assertEquals(9, QualityHintCounter.consume(persisted, QualityHintCounter.Kind.NODE));
        assertEquals(-1, QualityHintCounter.consume(persisted, QualityHintCounter.Kind.LINK));
    }
}
