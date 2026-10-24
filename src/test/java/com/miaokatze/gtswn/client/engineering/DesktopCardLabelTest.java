package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.ResourceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;

public class DesktopCardLabelTest {

    @Test
    public void preciseResourceUsesItsNameUntilUserSetsACustomTitle() {
        ResourceBinding binding = new ResourceBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "v2:item:" + String.join("", Collections.nCopies(64, "a")),
            "Circuit");
        WidgetSpec widget = new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 0, 4, 3).boundResource(binding);
        assertEquals("Circuit", DesktopCardLabel.title(widget, "Resources", null, null));
        assertEquals(
            "Assembly stock",
            DesktopCardLabel.title(widget.titled("Assembly stock"), "Resources", null, null));
    }

    private WidgetSpec device() {
        return new WidgetSpec(
            "device",
            WidgetKind.DEVICE,
            0,
            0,
            4,
            3,
            "",
            new DeviceBinding(UUID.randomUUID(), "-1:12:64:34", -1, 12, 64, 34, 1),
            Collections.<String>emptyList(),
            1);
    }

    @Test
    public void currentDeviceNameAndCoordinatesIdentifyTheDefaultCard() {
        WidgetSpec widget = device();
        assertEquals(
            "LV Macerator · DIM -1 · 12/64/34",
            DesktopCardLabel.title(widget, "Device", "LV Macerator", "machine"));
        assertEquals("machine · DIM -1 · 12/64/34", DesktopCardLabel.title(widget, "Device", "", "machine"));
    }

    @Test
    public void invalidSourceFallsBackToReferenceWithoutKeepingThePreviousName() {
        WidgetSpec widget = device();
        DesktopCardLabel.title(widget, "Device", "Old sampled name", "machine");
        assertEquals("Device · DIM -1 · 12/64/34", DesktopCardLabel.title(widget, "Device", null, null));
        assertEquals("My machine", DesktopCardLabel.title(widget.titled("My machine"), "Device", null, null));
    }
}
