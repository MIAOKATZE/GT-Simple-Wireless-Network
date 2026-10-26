package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.hud.HudController;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class FactoryReadDemandTest {

    @Test
    public void factoryDeviceCatalogueReadsTheNormalCacheWhileItemVisualizationStaysDisabled() throws Exception {
        ItemStack stack = new ItemStack(new ItemDeviceInfoTerminal());
        UUID id = ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        Entry entry = new Entry("0:0:64:0", "machine", (byte) 1, (byte) 0, 32, 0, 32, 0, 0, 0, 64, 0, "", "");
        DeviceTerminalClientCache.receivePage(id, 1, 0, 1, Collections.singletonList(entry));
        // Assemble the view with the lifecycle gate's explicit result. Receipt/source admission is
        // exercised separately by FactoryVisualizationReceiptTest without constructing a game client.
        Method capture = EngineeringTerminalHudSnapshot.class
            .getDeclaredMethod("device", Source.class, DeviceTerminalClientCache.Snapshot.class);
        capture.setAccessible(true);
        Field reading = EngineeringTerminalHudSnapshot.class.getDeclaredField("deviceReading");
        reading.setAccessible(true);
        try {
            reading.setBoolean(null, true);
            EngineeringTerminalHudSnapshot.Source view = (EngineeringTerminalHudSnapshot.Source) capture
                .invoke(null, new Source(stack, SourceOrigin.INVENTORY), null);
            assertTrue(view.enabled);
            assertFalse(view.fresh);
            assertTrue(view.devices.isEmpty());
            assertFalse(view.itemDisplayEnabled);
            view = (EngineeringTerminalHudSnapshot.Source) capture
                .invoke(null, new Source(stack, SourceOrigin.INVENTORY), DeviceTerminalClientCache.getSnapshot(id));
            assertTrue(view.enabled);
            assertTrue(view.fresh);
            assertFalse(view.itemDisplayEnabled);
            assertSame(entry, view.devices.get(0));
            Method historical = EngineeringTerminalHudSnapshot.class.getDeclaredMethod(
                "device",
                Source.class,
                DeviceTerminalClientCache.Snapshot.class,
                boolean.class,
                long.class);
            historical.setAccessible(true);
            view = (EngineeringTerminalHudSnapshot.Source) historical.invoke(
                null,
                new Source(stack, SourceOrigin.INVENTORY),
                DeviceTerminalClientCache.getSnapshot(id),
                false,
                123L);
            assertFalse(view.fresh);
            assertSame(entry, view.devices.get(0));
            assertEquals(123L, view.receivedAtMillis);
            assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(stack));
            reading.setBoolean(null, false);
            view = (EngineeringTerminalHudSnapshot.Source) capture
                .invoke(null, new Source(stack, SourceOrigin.INVENTORY), DeviceTerminalClientCache.getSnapshot(id));
            assertFalse(view.enabled);
            assertTrue(view.devices.isEmpty());
            assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(stack));
        } finally {
            reading.setBoolean(null, false);
            DeviceTerminalClientCache.clear();
        }
    }

    @Test
    public void factoryPortableDemandReusesTheHudSamplerWithoutWritingHudModeOrOwner() throws Exception {
        ItemStack stack = new ItemStack(new PortableWirelessNetworkMonitor());
        stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        String owner = UUID.randomUUID()
            .toString();
        stack.stackTagCompound.setString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID, owner);
        MonitorBindings.ensureId(stack);
        UUID id = MonitorBindings.id(stack);
        HudController hud = new HudController();
        Method inspect = HudController.class.getDeclaredMethod("inspectFactoryMonitor", ItemStack.class);
        inspect.setAccessible(true);
        Object inactive = inspect.invoke(hud, stack);
        Field mode = inactive.getClass()
            .getDeclaredField("hudMode");
        mode.setAccessible(true);
        assertEquals(0, mode.getInt(inactive));
        hud.setFactoryReading(true);
        assertEquals(1, mode.getInt(inspect.invoke(hud, stack)));
        assertEquals(0, PortableWirelessNetworkMonitor.getHudMode(stack));
        assertEquals(owner, PortableWirelessNetworkMonitor.getOwnerUUID(stack));
        assertEquals(id, MonitorBindings.id(stack));
        hud.setFactoryReading(false);
        assertEquals(0, mode.getInt(inspect.invoke(hud, stack)));
        assertEquals(0, PortableWirelessNetworkMonitor.getHudMode(stack));
    }
}
