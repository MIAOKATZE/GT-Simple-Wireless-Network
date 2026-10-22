package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class EngineeringTerminalSourcesTest {

    private static ItemStack device() {
        ItemStack stack = new ItemStack(new ItemDeviceInfoTerminal());
        ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        return stack;
    }

    private static ItemStack portable() {
        ItemStack stack = new ItemStack(new PortableWirelessNetworkMonitor());
        stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        stack.stackTagCompound.setString(
            PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            UUID.randomUUID()
                .toString());
        MonitorBindings.ensureId(stack);
        return stack;
    }

    @Test
    public void accessoriesWinAndInventoryProviderNeverFillsTheEightAccessorySlots() {
        ItemStack[] accessories = new ItemStack[8];
        ItemStack equipped = device(), first = device(), second = device();
        ItemStack[] main = { null, first, second };
        Source fallback = EngineeringTerminalSources.resolve(accessories, main, 2);
        assertSame(first, fallback.stack);
        assertEquals(SourceOrigin.INVENTORY, fallback.origin);
        assertNull(accessories[2]);
        accessories[2] = equipped;
        Source selected = EngineeringTerminalSources.resolve(accessories, main, 2);
        assertSame(equipped, selected.stack);
        assertEquals(SourceOrigin.BAUBLE, selected.origin);
        assertFalse(fallback.sameIdentity(selected));
        accessories[2] = new ItemStack(new ItemDeviceInfoTerminal());
        assertFalse(
            EngineeringTerminalSources.resolve(accessories, main, 2)
                .present());
    }

    @Test
    public void fallbackRequiresExistingValidCredentialsAndDoesNotWriteItemNbt() {
        ItemStack invalid = new ItemStack(new ItemDeviceInfoTerminal()), valid = device();
        Source selected = EngineeringTerminalSources.resolve(new ItemStack[8], new ItemStack[] { invalid, valid }, 2);
        assertSame(valid, selected.stack);
        assertNull(invalid.stackTagCompound);
        valid.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, "invalid");
        assertFalse(
            EngineeringTerminalSources.resolve(new ItemStack[8], new ItemStack[] { invalid, valid }, 2)
                .present());
        ItemStack unbound = new ItemStack(new PortableWirelessNetworkMonitor()), monitor = portable();
        assertSame(
            monitor,
            EngineeringTerminalSources.resolve(new ItemStack[8], new ItemStack[] { unbound, monitor }, 0).stack);
        assertNull(unbound.stackTagCompound);
    }

    @Test
    public void otherTerminalCategoriesCannotBorrowAnInventoryProvider() {
        ItemStack quantum = new ItemStack(new ItemNetworkQuantumTerminal());
        assertFalse(
            EngineeringTerminalSources.resolve(new ItemStack[8], new ItemStack[] { quantum }, 3)
                .present());
        assertFalse(
            EngineeringTerminalSources.resolve(new ItemStack[8], new ItemStack[] { device() }, 1)
                .present());
    }

    @Test
    public void sameStackRebindingAndSourceOriginChangesInvalidateCapturedIdentity() {
        ItemStack stack = device();
        Source original = new Source(stack, SourceOrigin.INVENTORY);
        assertTrue(original.sameIdentity(new Source(stack.copy(), SourceOrigin.INVENTORY)));
        assertFalse(original.sameIdentity(new Source(stack, SourceOrigin.BAUBLE)));
        stack.stackTagCompound.setString(
            ItemDeviceInfoTerminal.NBT_UUID,
            UUID.randomUUID()
                .toString());
        assertFalse(original.sameIdentity(new Source(stack, SourceOrigin.INVENTORY)));
        ItemStack monitor = portable();
        Source originalMonitor = new Source(monitor, SourceOrigin.INVENTORY);
        monitor.stackTagCompound.setString(
            PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            UUID.randomUUID()
                .toString());
        assertFalse(originalMonitor.sameIdentity(new Source(monitor, SourceOrigin.INVENTORY)));
    }

    @Test
    public void wireIdentityIsOnlyAnExpectationAndCannotImpersonateAnAccessoryOrAnotherTerminal() {
        Source inventory = new Source(device(), SourceOrigin.INVENTORY);
        PacketEngineeringSubscribe request = new PacketEngineeringSubscribe(1, 2, 0, -1, -1, 1, 1, false, false, false)
            .source(inventory);
        ByteBuf bytes = Unpooled.buffer();
        try {
            request.toBytes(bytes);
            PacketEngineeringSubscribe decoded = new PacketEngineeringSubscribe();
            decoded.fromBytes(bytes);
            assertTrue(decoded.valid);
            assertTrue(decoded.matches(inventory));
            assertFalse(decoded.matches(new Source(inventory.stack, SourceOrigin.BAUBLE)));
            assertFalse(decoded.matches(new Source(device(), SourceOrigin.INVENTORY)));
            assertFalse(decoded.matches(Source.EMPTY));
        } finally {
            bytes.release();
        }
    }
}
