package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class DelayedPlayerMessagesTest {

    @Test
    public void successMessagesAndHintWaitFortyTicksAndConsumeAtDelivery() {
        DelayedPlayerMessages<String> queue = new DelayedPlayerMessages<>(10);
        UUID player = UUID.randomUUID();
        NBTTagCompound persisted = new NBTTagCompound();
        List<String> messages = new ArrayList<>();
        queue.schedule(player, "success", 40);
        queue.schedule(player, "second success", 40);
        for (int tick = 0; tick < 39; tick++) {
            queue.advance((id, message) -> messages.add(message));
        }
        assertTrue(messages.isEmpty());
        queue.advance((id, message) -> {
            assertEquals(player, id);
            messages.add(message);
            messages.add("remaining=" + QualityHintCounter.consume(persisted, QualityHintCounter.Kind.LINK));
        });
        assertEquals(Arrays.asList("success", "remaining=9", "second success", "remaining=8"), messages);
        queue.advance((id, message) -> messages.add(message));
        assertEquals(4, messages.size());
    }

    @Test
    public void logoutCancelsPendingNoticesWithoutSpendingCountersOrAffectingOthers() {
        DelayedPlayerMessages<String> queue = new DelayedPlayerMessages<>(10);
        UUID offline = UUID.randomUUID();
        UUID online = UUID.randomUUID();
        NBTTagCompound persisted = new NBTTagCompound();
        List<String> messages = new ArrayList<>();
        queue.schedule(offline, "cancelled", 40);
        queue.schedule(online, "delivered", 40);
        queue.cancel(offline);
        for (int tick = 0; tick < 40; tick++) {
            queue.advance((id, message) -> {
                assertEquals(online, id);
                messages.add(message);
            });
        }
        assertEquals(Arrays.asList("delivered"), messages);
        assertEquals(9, QualityHintCounter.consume(persisted, QualityHintCounter.Kind.LINK));
    }

    @Test
    public void stoppingClearsQueueAndRestartRetainsFullDelay() {
        DelayedPlayerMessages<String> queue = new DelayedPlayerMessages<>(10);
        UUID player = UUID.randomUUID();
        List<String> messages = new ArrayList<>();
        queue.schedule(player, "old world", 40);
        for (int tick = 0; tick < 20; tick++) queue.advance((id, message) -> messages.add(message));
        queue.clear();
        queue.schedule(player, "new world", 40);
        for (int tick = 0; tick < 39; tick++) queue.advance((id, message) -> messages.add(message));
        assertTrue(messages.isEmpty());
        queue.advance((id, message) -> messages.add(message));
        assertEquals(Arrays.asList("new world"), messages);
    }

    @Test
    public void pathologicalBurstsKeepQueueBounded() {
        DelayedPlayerMessages<String> queue = new DelayedPlayerMessages<>(2);
        UUID player = UUID.randomUUID();
        List<String> messages = new ArrayList<>();
        queue.schedule(player, "evicted", 1);
        queue.schedule(player, "second", 1);
        queue.schedule(player, "third", 1);
        queue.advance((id, message) -> messages.add(message));
        assertEquals(Arrays.asList("second", "third"), messages);
    }
}
