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
    private static Map<UUID, Object> charges() throws Exception {
        Field field = TerminalBaubles.class.getDeclaredField("CHARGES");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }

    private static Object charge(Player player, ItemStack stack) throws Exception {
        Class<?> type = Class.forName(TerminalBaubles.class.getName() + "$Charge");
        Object charge = allocate(type);
        for (String name : new String[] { "player", "stack" }) {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(charge, name.equals("player") ? player : stack);
        }
        return charge;
    }

    @After
    public void clear() {
        TerminalBaubles.clear();
    }

    @Test
    public void completionCannotCreateAChargeAndOldConnectionCannotConsumeAReplacementCharge() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        ItemStack stack = new ItemStack(new WirelessEnergyTap());
        assertFalse(TerminalBaubles.consumeWheelCharge(old, stack));
        Object fresh = charge(replacement, stack);
        charges().put(id, fresh);
        assertFalse(TerminalBaubles.consumeWheelCharge(old, stack));
        assertSame(fresh, charges().get(id));
        new TerminalBaubles.Events().logout(new cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent(old));
        assertSame(fresh, charges().get(id));
    }

    @Test
    public void cancellationIsABarrierBeforeOtherQueuesDrainAndCannotBeReplacedByCompletion() throws Exception {
        Player actor = player(UUID.randomUUID());
        ItemStack stack = new ItemStack(new WirelessEnergyTap());
        charges().put(actor.id, charge(actor, stack));
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE, 1);
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE, 1);
        TerminalBaubles.enqueue(actor, PacketTerminalBaubleAction.WHEEL_INNER, 1);
        assertFalse(TerminalBaubles.consumeWheelCharge(actor, stack));
        assertTrue(charges().isEmpty());
    }

    @Test
    public void cancellationRemovesOnlyTheRequestingConnectionCharge() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        Object fresh = charge(replacement, new ItemStack(new WirelessEnergyTap()));
        charges().put(id, fresh);
        TerminalBaubles.service(old, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE, 1);
        assertSame(fresh, charges().get(id));
        TerminalBaubles.service(replacement, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE, 1);
        assertTrue(charges().isEmpty());
    }

    @Test
    public void playerClearCannotDiscardAReplacementConnectionsCharge() throws Exception {
        UUID id = UUID.randomUUID();
        Player old = player(id), replacement = player(id);
        Object fresh = charge(replacement, new ItemStack(new WirelessEnergyTap()));
        charges().put(id, fresh);
        TerminalBaubles.clear(old);
        assertSame(fresh, charges().get(id));
        TerminalBaubles.clear(replacement);
        assertTrue(charges().isEmpty());
    }

    @Test
    public void serverChargeRequiresTwentyTicksAndExpiresAfterSixtyIncludingClockRollback() {
        assertFalse(TerminalBaubles.validWheelChargeAge(119, 100));
        assertTrue(TerminalBaubles.validWheelChargeAge(120, 100));
        assertTrue(TerminalBaubles.validWheelChargeAge(160, 100));
        assertFalse(TerminalBaubles.validWheelChargeAge(161, 100));
        assertFalse(TerminalBaubles.validWheelChargeAge(99, 100));
    }
}
