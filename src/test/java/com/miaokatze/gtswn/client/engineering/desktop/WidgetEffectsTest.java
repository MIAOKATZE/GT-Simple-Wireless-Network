package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

public class WidgetEffectsTest {

    @Test
    public void everyAppearanceAndGeometryCopyKeepsStyleAndPendingDeviceIntent() {
        WidgetEffects effects = new WidgetEffects(WidgetEffects.Style.SEGMENTED, false, 0x334455, 2.5, .3);
        WidgetSpec original = new WidgetSpec("device", WidgetKind.DEVICE, 0, 0, 4, 4).styled(effects)
            .withAutoDevice(true);
        WidgetSpec[] copies = { original.withBounds(1, 2, 5, 6), original.titled("Title"),
            original.configured("Title", Collections.singletonList("status"), 1),
            original.presented(WidgetPresentation.KPI, "instantInput", null), original.withRate(false, 12),
            original.withAutoResource(false), original.withSingleResource(false), original.bordered(0x334455),
            original.boundScreen(null), original.renamed("new-id"), original.asTemplate() };
        for (WidgetSpec copy : copies) {
            assertSame(effects, copy.effects);
            assertTrue(copy.autoDevicePending);
        }
        assertFalse(original.boundDevice(null).autoDevicePending);
        assertFalse(
            original.boundDevice(new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4)).autoDevicePending);
        assertEquals(0xFF334455, effects.accentColor);
    }

    @Test
    public void transferableSingleResourcePreservesShapeAndStyleButDropsSourceIdentity() {
        ResourceBinding binding = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "v2:item:" + String.join("", Collections.nCopies(64, "a")),
            "Item");
        WidgetEffects effects = WidgetEffects.DEFAULT.withStyle(WidgetEffects.Style.SIGNAL);
        WidgetSpec original = new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 0, 2, 2).boundResource(binding)
            .styled(effects);
        WidgetSpec template = original.asTemplate()
            .renamed("imported");
        assertNull(template.resource);
        assertNull(template.screen);
        assertTrue(template.singleResource);
        assertTrue(template.autoResourcePending);
        assertSame(effects, template.effects);
        assertEquals(2, template.columns);
        assertEquals(2, template.rows);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonFiniteSpeedCannotReachAnimationClock() {
        WidgetEffects.DEFAULT.withSpeed(Double.NaN);
    }
}
