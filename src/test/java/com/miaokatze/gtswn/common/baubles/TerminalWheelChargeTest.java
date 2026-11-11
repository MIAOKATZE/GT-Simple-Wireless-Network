// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import org.junit.After;
import org.junit.Test;

import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;

public class TerminalWheelChargeTest {

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

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }

    private static Player player(UUID id) throws Exception {
        Player player = (Player) allocate(Player.class);
        player.id = id;
        return player;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> receipts() throws Exception {
        Field field = TerminalBaubles.class.getDeclaredField("WHEEL_RECEIPTS");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }

    private static Object receipt(Player player, ItemStack stack) throws Exception {
        Class<?> type = Class.forName(TerminalBaubles.class.getName() + "$WheelReceipt");
        Object receipt = allocate(type);
        for (String name : new String[] { "player", "stack" }) {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(receipt, name.equals("player") ? player : stack);
        }
        return receipt;
    }

    @After
    public void clear() {
        TerminalBaubles.clear();
    }

    @Test
    public void missingReceiptAndOldConnectionCannotConsumeAReplacementReceipt() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        ItemStack stack = new ItemStack(new WirelessEnergyTap());
        assertFalse(TerminalBaubles.consumeWheelReceipt(old, stack));
        Object fresh = receipt(replacement, stack);
        receipts().put(id, fresh);
        assertFalse(TerminalBaubles.consumeWheelReceipt(old, stack));
        assertSame(fresh, receipts().get(id));
        new TerminalBaubles.Events().logout(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent(old));
        assertSame(fresh, receipts().get(id));
    }

    @Test
    public void cancellationIsABarrierBeforeOtherQueuesDrainAndCannotBeReplacedByCompletion() throws Exception {
        Player actor = player(UUID.randomUUID());
        ItemStack stack = new ItemStack(new WirelessEnergyTap());
        receipts().put(actor.id, receipt(actor, stack));
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE, 1);
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE, 1);
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_INNER, 1);
        assertFalse(TerminalBaubles.consumeWheelReceipt(actor, stack));
        assertTrue(receipts().isEmpty());
    }

    @Test
    public void cancellationRemovesOnlyTheRequestingConnectionReceipt() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        Object fresh = receipt(replacement, new ItemStack(new WirelessEnergyTap()));
        receipts().put(id, fresh);
        TerminalBaubles.service(old, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE, 1);
        assertSame(fresh, receipts().get(id));
        TerminalBaubles.service(replacement, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE, 1);
        assertTrue(receipts().isEmpty());
    }

    @Test
    public void playerClearCannotDiscardAReplacementConnectionsReceipt() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        Object fresh = receipt(replacement, new ItemStack(new WirelessEnergyTap()));
        receipts().put(id, fresh);
        TerminalBaubles.clear(old);
        assertSame(fresh, receipts().get(id));
        TerminalBaubles.clear(replacement);
        assertTrue(receipts().isEmpty());
    }

    @Test
    public void staleQueueStackCannotConsumeOrDiscardTheLatestClickReceipt() throws Exception {
        Player actor = player(UUID.randomUUID());
        ItemStack oldStack = new ItemStack(new WirelessEnergyTap()), freshStack = oldStack.copy();
        Object fresh = receipt(actor, freshStack);
        receipts().put(actor.id, fresh);
        assertFalse(TerminalBaubles.consumeWheelReceipt(actor, oldStack));
        assertSame(fresh, receipts().get(actor.id));
    }

    @Test
    public void legacyBeginIsIgnoredWithoutAdmittingOrOverwritingAReceipt() throws Exception {
        Player actor = player(UUID.randomUUID());
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE, 1);
        TerminalBaubles.service(actor, PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE, 1);
        assertTrue(receipts().isEmpty());
        Field field = TerminalBaubles.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(null)).isEmpty());
        Object fresh = receipt(actor, new ItemStack(new WirelessEnergyTap()));
        receipts().put(actor.id, fresh);
        TerminalBaubles.service(actor, PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE, 1);
        assertSame(fresh, receipts().get(actor.id));
    }

    @Test
    public void clickReceiptIsImmediatelyValidAndExpiresAfterSixtyTicksIncludingClockRollback() {
        assertTrue(TerminalBaubles.validWheelReceiptAge(100, 100));
        assertTrue(TerminalBaubles.validWheelReceiptAge(101, 100));
        assertTrue(TerminalBaubles.validWheelReceiptAge(120, 100));
        assertTrue(TerminalBaubles.validWheelReceiptAge(160, 100));
        assertFalse(TerminalBaubles.validWheelReceiptAge(161, 100));
        assertFalse(TerminalBaubles.validWheelReceiptAge(99, 100));
    }
}
