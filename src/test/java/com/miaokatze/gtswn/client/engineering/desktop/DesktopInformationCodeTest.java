package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import org.junit.Test;

public class DesktopInformationCodeTest {

    @Test
    public void informationKindFieldsRoundTripWithoutOperationalBindingsOrReadings() {
        DesktopLayout layout = new DesktopLayout();
        int index = 0;
        for (WidgetKind kind : WidgetKind.values()) {
            if (!kind.isInformation()) continue;
            WidgetSpec widget = new WidgetSpec("info" + index, kind, index * 4, 0, 4, 3)
                .configured("", WidgetSpec.availableFields(kind), 1);
            assertTrue(layout.add(widget));
            index++;
        }
        DesktopLayout decoded = DesktopPresetCode.decode(DesktopPresetCode.export(layout));
        assertEquals(
            5,
            decoded.widgets()
                .size());
        for (WidgetSpec widget : decoded.widgets()) {
            assertTrue(widget.kind.isInformation());
            assertEquals(WidgetSpec.availableFields(widget.kind), widget.fields);
            assertTrue(widget.fieldsConfigured);
            assertTrue(
                WidgetSpec.availableMetrics(widget.kind)
                    .isEmpty());
            assertFalse(widget.kind.supportsDeviceBinding());
            assertFalse(widget.kind.supportsScreenBinding());
            assertNull(widget.device);
            assertNull(widget.screen);
            assertNull(widget.resource);
        }
    }
}
