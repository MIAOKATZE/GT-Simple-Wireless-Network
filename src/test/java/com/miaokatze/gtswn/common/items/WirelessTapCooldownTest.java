package com.miaokatze.gtswn.common.items;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class WirelessTapCooldownTest {

    @Test
    public void firstUseAtZeroStartsFourTickCooldownWithoutExtendingRejectedUses() {
        NBTTagCompound tag = new NBTTagCompound();
        assertTrue(WirelessTapCooldown.consume(tag, 0, 0));
        for (long tick = 0; tick < 4; tick++) assertFalse(WirelessTapCooldown.consume(tag, tick, 0));
        assertEquals(0, tag.getLong("LastUseTime"));
        assertTrue(WirelessTapCooldown.consume(tag, 4, 0));
        assertFalse(WirelessTapCooldown.consume(tag, 7, 0));
        assertTrue(WirelessTapCooldown.consume(tag, 8, 0));
    }

    @Test
    public void dimensionChangesWorkWithEarlierLaterAndEqualClocks() {
        for (long destination : new long[] { 0, 999, 1000, 1001, 100000 }) {
            NBTTagCompound tag = new NBTTagCompound();
            assertTrue(WirelessTapCooldown.consume(tag, 1000, 0));
            assertTrue(WirelessTapCooldown.consume(tag, destination, 7));
            assertEquals(7, tag.getInteger("LastUseDimension"));
            assertFalse(WirelessTapCooldown.consume(tag, destination + 3, 7));
            assertTrue(WirelessTapCooldown.consume(tag, destination + 4, 7));
        }
    }

    @Test
    public void rollbackAndFutureTimestampRecoverImmediatelyThenDebounce() {
        for (long last : new long[] { 1001, 100000, Long.MAX_VALUE }) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("LastUseTime", last);
            tag.setInteger("LastUseDimension", 7);
            assertTrue(WirelessTapCooldown.consume(tag, 2, 7));
            assertEquals(2, tag.getLong("LastUseTime"));
            assertFalse(WirelessTapCooldown.consume(tag, 2, 7));
            assertTrue(WirelessTapCooldown.consume(tag, 6, 7));
        }
    }

    @Test
    public void oldTickAndMillisecondNbtMigrateOnceAndPreserveOtherState() {
        for (long oldTime : new long[] { 0, 1, 100000, 1700000000000L }) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("LastUseTime", oldTime);
            tag.setBoolean("OutputMode", true);
            tag.setInteger("BindNotifyCount", 5);
            assertTrue(WirelessTapCooldown.consume(tag, 1, -42));
            assertTrue(tag.getBoolean("OutputMode"));
            assertEquals(5, tag.getInteger("BindNotifyCount"));
            assertEquals(1, tag.getLong("LastUseTime"));
            assertEquals(-42, tag.getInteger("LastUseDimension"));
            assertFalse(WirelessTapCooldown.consume(tag, 1, -42));
            assertTrue(WirelessTapCooldown.consume(tag, 5, -42));
        }
    }
}
