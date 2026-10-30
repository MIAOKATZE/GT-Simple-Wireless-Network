package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.After;
import org.junit.Test;

public class TerminalRevealCancellationTest {

    private static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    private static Player player(UUID id) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Player player = (Player) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), Player.class);
        player.id = id;
        return player;
    }

    @After
    public void clear() {
        PacketTerminalGesture.clearServerState();
    }

    @Test
    public void cancellationCannotBeOverwrittenBySameWindowBeginScanOrOpen() throws Exception {
        Player actor = player(UUID.randomUUID());
        for (int action : new int[] { PacketTerminalGesture.BEGIN_REVEAL, PacketTerminalGesture.SCAN_DEVICES,
            PacketTerminalGesture.OPEN_LINK }) {
            PacketTerminalGesture.clearServerState();
            PacketTerminalGesture.enqueue(actor, action);
            PacketTerminalGesture.enqueue(actor, PacketTerminalGesture.CANCEL_REVEAL);
            PacketTerminalGesture.enqueue(actor, action);
            assertTrue(PacketTerminalGesture.cancellationPending(actor));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void anotherQueueCannotConsumeBeforeTheCancellationListenerDrains() throws Exception {
        Player actor = player(UUID.randomUUID());
        Field field = PacketTerminalGesture.class.getDeclaredField("CHARGES");
        field.setAccessible(true);
        Map<UUID, Object> charges = (Map<UUID, Object>) field.get(null);
        charges.put(actor.id, new Object()); // Even examining this expired credential before cancellation is a bug.
        PacketTerminalGesture.enqueue(actor, PacketTerminalGesture.CANCEL_REVEAL);
        assertFalse(PacketTerminalGesture.consumeReveal(actor));
        assertTrue(charges.isEmpty());
        assertTrue(PacketTerminalGesture.cancellationPending(actor));
    }

    @Test
    public void cancellationFromOldEntityDoesNotBlockAReplacementWithTheSameUuid() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        PacketTerminalGesture.enqueue(old, PacketTerminalGesture.CANCEL_REVEAL);
        assertFalse(PacketTerminalGesture.cancellationPending(replacement));
        PacketTerminalGesture.enqueue(replacement, PacketTerminalGesture.BEGIN_REVEAL);
        assertFalse(PacketTerminalGesture.cancellationPending(replacement));
        assertFalse(PacketTerminalGesture.cancellationPending(old));
    }

    @Test
    public void revealCredentialHasBothALowerAndUpperAgeLimitIndependentOfDrainOrder() {
        assertFalse(PacketTerminalGesture.validRevealAge(118, 100));
        assertTrue(PacketTerminalGesture.validRevealAge(119, 100));
        assertTrue(PacketTerminalGesture.validRevealAge(160, 100));
        assertFalse(PacketTerminalGesture.validRevealAge(161, 100));
        assertFalse(PacketTerminalGesture.validRevealAge(99, 100));
    }
}
