package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import org.junit.Test;

public class DesktopAutoBindTest {

    @Test
    public void absentClientContextLeavesAppliedPowerDevicesPending() {
        assertNull(Minecraft.getMinecraft());
        DesktopLayout applied = DesktopAutoBind.initialize(DesktopPreset.POWER.createLayout());
        assertFalse(DesktopAutoBind.initializeAvailable(applied));
        for (WidgetSpec widget : applied.widgets()) {
            if (!widget.kind.supportsDeviceBinding()) continue;
            assertNull(widget.device);
            assertTrue(widget.autoDevicePending);
        }
    }

    @Test
    public void everyApplicationCreatesFreshUnboundIntentWithoutChangingTemplate() {
        DesktopLayout template = new DesktopLayout();
        DeviceBinding previous = new DeviceBinding(UUID.randomUUID(), "old", 0, 1, 2, 3, 4);
        assertTrue(template.add(new WidgetSpec("device", WidgetKind.DEVICE, 0, 0, 4, 4).boundDevice(previous)));
        assertTrue(template.add(new WidgetSpec("resource", WidgetKind.RESOURCES, 4, 0, 2, 2).withSingleResource(true)));
        DesktopLayout applied = DesktopAutoBind.initialize(template);
        assertSame(previous, template.find("device").device);
        assertNull(applied.find("device").device);
        assertTrue(applied.find("device").autoDevicePending);
        assertTrue(applied.find("resource").autoResourcePending);
        assertTrue(applied.find("resource").singleResource);
        assertFalse(DesktopAutoBind.initializeDevices(applied, Collections.emptyList()));
        assertTrue(applied.find("device").autoDevicePending);
        assertTrue(DesktopAutoBind.initializeDevices(applied, Collections.singletonList(previous)));
        assertFalse(applied.find("device").autoDevicePending);
        assertSame(previous, applied.find("device").device);
        assertNull(
            DesktopAutoBind.initialize(template)
                .find("device").device);
    }

    @Test
    public void automaticDeviceChoicesAreStableAndDeduplicatedAcrossTerminalsAndExplicitClearsStayClear() {
        DesktopLayout template = new DesktopLayout();
        for (int index = 0; index < 3; index++)
            assertTrue(template.add(new WidgetSpec("device" + index, WidgetKind.DEVICE, index * 4, 0, 4, 4)));
        DesktopLayout applied = DesktopAutoBind.initialize(template);
        DeviceBinding first = new DeviceBinding(new UUID(0, 1), "first", 0, 1, 2, 3, 4);
        DeviceBinding duplicate = new DeviceBinding(new UUID(0, 2), "other-key", 0, 1, 2, 3, 4);
        DeviceBinding second = new DeviceBinding(new UUID(0, 3), "second", 0, 2, 2, 3, 4);
        assertTrue(DesktopAutoBind.initializeDevices(applied, Arrays.asList(second, duplicate, first)));
        assertEquals(first, applied.find("device0").device);
        assertEquals(second, applied.find("device1").device);
        assertNull(applied.find("device2").device);
        assertTrue(applied.find("device2").autoDevicePending);
        assertTrue(
            applied.replace(
                applied.find("device0")
                    .boundDevice(null)));
        assertTrue(DesktopAutoBind.initializeDevices(applied, Arrays.asList(first, second)));
        assertNull(applied.find("device0").device);
        assertFalse(applied.find("device0").autoDevicePending);
        assertEquals(first, applied.find("device2").device);
    }
}
