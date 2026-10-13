package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class DeviceEnergyWorldRendererTest {

    private int previousHeatSize;

    @Before
    public void keepOriginalSizeForExistingCoreAndEnvelopeCases() {
        previousHeatSize = DeviceTerminalVisualConfig.heatSize;
        DeviceTerminalVisualConfig.heatSize = 1;
    }

    @After
    public void restoreClientHeatSize() {
        DeviceTerminalVisualConfig.heatSize = previousHeatSize;
    }

    @Test
    public void boundsContainBothInterpolatedHeatRadiiWithoutChangingMachineCore() {
        Entry entry = new Entry("0:8:64:-12", "Machine", (byte) 1, (byte) 0, 32L, 0L, 32D, 0D, 0, 8, 64, -12, "", "");
        DeviceTerminalAnchorRenderer.Anchor anchor = new DeviceTerminalAnchorRenderer.Anchor(entry, 0D);
        DeviceEnergyWorldRenderer.Visual visual = new DeviceEnergyWorldRenderer.Visual(
            anchor,
            false,
            false,
            32D,
            false);
        visual.intensity = .25D;
        visual.previousRadius = 2.8D;
        visual.updateAppearance();
        assertEquals(8.5D - 2.8D, visual.box.minX, 0D);
        assertEquals(64.5D + 2.8D, visual.box.maxY, 0D);
        assertEquals(-11.5D + 2.8D, visual.box.maxZ, 0D);
        assertTrue(visual.radius < visual.previousRadius);
        assertEquals(8D - .012D, anchor.heatBounds.minX, 0D);
        assertEquals(9D + .012D, visual.coreBox.maxX, 0D);
        assertEquals(7D, anchor.bounds.minX, 0D);
        visual.previousRadius = visual.radius;
        visual.updateBounds();
        assertTrue(visual.box.minX > 8.5D - 2.8D);
    }

    @Test
    public void neutralDevicesKeepSmallCoreAndMixedSteamKeepsItsSeparateSlab() {
        Entry entry = new Entry(
            "0:0:0:0",
            "Machine",
            (byte) 0,
            (byte) 0,
            0L,
            0L,
            0D,
            0D,
            0,
            0,
            0,
            0,
            "",
            "",
            -1,
            (byte) 2,
            (byte) 0,
            (byte) 0,
            0L,
            0L,
            0D,
            0D);
        DeviceTerminalAnchorRenderer.Anchor anchor = new DeviceTerminalAnchorRenderer.Anchor(entry, 0D);
        DeviceEnergyWorldRenderer.Visual visual = new DeviceEnergyWorldRenderer.Visual(anchor, true, true, 0D, true);
        visual.intensity = 1D;
        visual.updateAppearance();
        assertEquals(0D, visual.radius, 0D);
        assertEquals(.72D, visual.coreBox.minY, 0D);
        assertEquals(1.04D, visual.box.maxY, 0D);
        assertTrue(visual.extent < 1D);
        assertEquals(.88D, visual.centerY, 0D);
    }

    @Test
    public void explicitOtherSteamAcceptsEuRecipeMachinesWhileAutoKeepsCapabilityGuard() {
        Entry automatic = entry((byte) 2, (byte) 5, (byte) 0, (byte) 1);
        assertEquals(1, DeviceEnergyWorldRenderer.channels(automatic, false));
        assertEquals(5, DeviceEnergyWorldRenderer.channels(automatic, true));
        Entry forcedSteam = entry((byte) 1, (byte) 4, (byte) 1, (byte) 1);
        assertEquals(4, DeviceEnergyWorldRenderer.channels(forcedSteam, false));
        Entry forcedElectric = entry((byte) 0, (byte) 2, (byte) 1, (byte) 1);
        assertEquals(2, DeviceEnergyWorldRenderer.channels(forcedElectric, true));
    }

    @Test
    public void ignoredStateStopsChannelsAndClearsOldHeatWithoutNormalizationMagnitude() {
        // Even malformed stale values and old channel bits cannot preserve a previous hot envelope.
        Entry ignored = entry((byte) 2, (byte) 15, (byte) 2, (byte) 3);
        assertTrue(DeviceEnergyWorldRenderer.ignored(ignored));
        assertEquals(0, DeviceEnergyWorldRenderer.channels(ignored, true));
        DeviceTerminalAnchorRenderer.Anchor anchor = new DeviceTerminalAnchorRenderer.Anchor(ignored, 0D);
        DeviceEnergyWorldRenderer.Visual visual = new DeviceEnergyWorldRenderer.Visual(
            anchor,
            false,
            false,
            1E9D,
            false);
        visual.previousRadius = 2.8D;
        visual.intensity = 1D;
        visual.updateAppearance();
        assertTrue(visual.neutral);
        assertEquals(0D, visual.magnitude, 0D);
        assertEquals(0D, visual.radius, 0D);
        assertEquals(0D, visual.previousRadius, 0D);
        assertEquals(anchor.heatBounds.minX, visual.box.minX, 0D);
        assertEquals(anchor.heatBounds.maxY, visual.box.maxY, 0D);
        assertEquals(visual.red, visual.green, 0F);
        assertEquals(visual.green, visual.blue, 0F);
        assertEquals(0, DeviceEnergyWorldRenderer.channels(entry((byte) 0, (byte) 1, (byte) 0, (byte) 3), false));
        assertEquals(0, DeviceEnergyWorldRenderer.channels(entry((byte) 0, (byte) 1, (byte) 2, (byte) 1), false));
    }

    @Test
    public void largestLegalSizeKeepsFiniteEnvelopeAndOriginalCoreAtWorldEdge() {
        DeviceTerminalVisualConfig.setHeatSize(1000);
        Entry entry = new Entry(
            "0:30000000:255:-30000000",
            "Machine",
            (byte) 1,
            (byte) 0,
            32L,
            0L,
            32D,
            0D,
            0,
            30000000,
            255,
            -30000000,
            "",
            "");
        DeviceTerminalAnchorRenderer.Anchor anchor = new DeviceTerminalAnchorRenderer.Anchor(entry, 0D);
        DeviceEnergyWorldRenderer.Visual visual = new DeviceEnergyWorldRenderer.Visual(
            anchor,
            false,
            false,
            32D,
            false);
        visual.intensity = 1D;
        visual.previousRadius = 28D;
        visual.updateAppearance();
        assertEquals(2800D, visual.radius, 0D);
        assertEquals(2800D, visual.extent, 0D);
        assertEquals(visual.centerX - 2800D, visual.box.minX, 0D);
        assertEquals(visual.centerZ + 2800D, visual.box.maxZ, 0D);
        assertTrue(Double.isFinite(visual.box.minX));
        assertTrue(Double.isFinite(visual.box.maxY));
        assertEquals(30000000D - .012D, visual.coreBox.minX, 0D);
        assertEquals(30000000D - 1D, anchor.bounds.minX, 0D);
        DeviceTerminalVisualConfig.setHeatSize(1);
        visual.previousRadius = visual.radius;
        visual.updateAppearance();
        assertEquals(2.8D, visual.radius, 0D);
        assertEquals(visual.centerX - 2800D, visual.box.minX, 0D);
        visual.previousRadius = visual.radius;
        visual.updateBounds();
        assertEquals(visual.centerX - 2.8D, visual.box.minX, 0D);
    }

    private static Entry entry(byte kind, byte capabilities, byte mode, byte state) {
        return new Entry(
            "0:0:0:0",
            "Machine",
            state,
            (byte) 0,
            32L,
            0L,
            32D,
            0D,
            0,
            0,
            0,
            0,
            "",
            "",
            -1,
            kind,
            (byte) 0,
            (byte) 0,
            100L,
            0L,
            100D,
            0D,
            "",
            (byte) 15,
            false,
            capabilities,
            mode);
    }
}
