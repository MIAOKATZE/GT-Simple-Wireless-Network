package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class DeviceTerminalAnchorRendererTest {

    @Test
    public void clientRegistrationCanInitializeTheRendererSingleton() {
        assertNotNull(DeviceTerminalAnchorRenderer.INSTANCE);
    }

    @Test
    public void labelSizesOneTenAndHundredUseRequestedRatiosAtNearAndFarDistances() {
        int previous = DeviceTerminalVisualConfig.labelSize;
        try {
            Entry entry = new Entry("0:0:0:0", "Machine", (byte) 1, (byte) 0, 32L, 0L, 32D, 0D, 0, 0, 0, 0, "", "");
            for (int size : new int[] { 1, 10, 100 }) {
                DeviceTerminalVisualConfig.setLabelSize(size);
                assertEquals(
                    .027D * 1.5D * size / 10D,
                    DeviceTerminalAnchorRenderer.labelScale(entry, .5D, 1.6D, .5D),
                    1E-7D);
                assertEquals(
                    .15D * 1.5D * size / 10D,
                    DeviceTerminalAnchorRenderer.labelScale(entry, 1000D, 1.6D, .5D),
                    1E-7D);
            }
        } finally {
            DeviceTerminalVisualConfig.labelSize = previous;
        }
    }

    @Test
    public void largeLabelBoundsContainBillboardCornersWhileMarkerBoundsStaySmall() {
        Entry entry = new Entry("0:0:0:0", "Machine", (byte) 1, (byte) 0, 32L, 0L, 32D, 0D, 0, 0, 0, 0, "", "");
        DeviceTerminalAnchorRenderer.Anchor anchor = new DeviceTerminalAnchorRenderer.Anchor(entry, 0D);
        anchor.width = 200;
        anchor.lines.add("Machine");
        anchor.lines.add("32 EU/t");
        double scale = .405D;
        DeviceTerminalAnchorRenderer.updateLabelBounds(anchor, scale);
        double radius = Math.sqrt(104D * 104D + 32D * 32D) * scale;
        assertEquals(.5D - radius, anchor.labelBounds.minX, 0D);
        assertEquals(1.6D + radius, anchor.labelBounds.maxY, 0D);
        assertTrue(anchor.labelBounds.minX < .5D - 104D * scale);
        assertTrue(anchor.labelBounds.minY < 1.6D - 32D * scale);
        assertEquals(-1D, anchor.bounds.minX, 0D);
        assertEquals(2D, anchor.bounds.maxX, 0D);
        assertEquals(3D, anchor.bounds.maxY, 0D);
    }
}
