package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

public class DesktopStyleStoreTest {

    @Test
    public void defaultEffectsDoNotChangeOldCanonicalStorageAndCustomEffectsSurviveCopies() {
        DesktopLayout layout = new DesktopLayout();
        WidgetSpec widget = new WidgetSpec("device", WidgetKind.DEVICE, 0, 0, 4, 4);
        assertTrue(layout.add(widget));
        for (String key : DesktopStore.encode(layout)
            .stringPropertyNames()) assertFalse(key.contains("effects."));
        assertTrue(
            layout.replace(
                widget.styled(new WidgetEffects(WidgetEffects.Style.SIGNAL, false, 0x224466, 2, .2))
                    .withAutoDevice(true)
                    .titled("Saved")
                    .withBounds(0, 0, 5, 5)));
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout.copy()))
            .find("device");
        assertEquals(WidgetEffects.Style.SIGNAL, restored.effects.style);
        assertFalse(restored.effects.animated);
        assertEquals(0xFF224466, restored.effects.accentColor);
        assertEquals(2, restored.effects.speed, 0);
        assertEquals(.2, restored.effects.intensity, 0);
        assertTrue(restored.autoDevicePending);
    }

    @Test
    public void corruptOptionalStyleFieldsKeepTheCardAndUseSafeDefaults() {
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(new WidgetSpec("device", WidgetKind.DEVICE, 0, 0, 4, 4)));
        Properties data = DesktopStore.encode(layout);
        data.setProperty("widget.0.effects.style", "FUTURE");
        data.setProperty("widget.0.effects.accent", "no-color");
        data.setProperty("widget.0.effects.speed", "NaN");
        data.setProperty("widget.0.effects.intensity", "12");
        WidgetSpec restored = DesktopStore.decode(data)
            .find("device");
        assertNotNull(restored);
        assertEquals(WidgetEffects.DEFAULT.style, restored.effects.style);
        assertEquals(WidgetEffects.DEFAULT.accentColor, restored.effects.accentColor);
        assertEquals(WidgetEffects.DEFAULT.speed, restored.effects.speed, 0);
        assertEquals(WidgetEffects.DEFAULT.intensity, restored.effects.intensity, 0);
    }
}
