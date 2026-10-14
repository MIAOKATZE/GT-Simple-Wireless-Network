package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortablePanelView;
import com.miaokatze.gtswn.client.render.RenderNetworkInfoPanel;
import com.miaokatze.gtswn.common.panel.PanelConfigStore;
import com.miaokatze.gtswn.common.panel.TrendLinePalette;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class TrendLinePaletteTest {

    @Test
    public void defaultsAndDirectionAreIndependentOfLevelAndScreenYInversion() {
        TrendLinePalette palette = TrendLinePalette.DEFAULT;
        assertEquals(0x1F6FFF, palette.segmentColor(.5, .5, false));
        assertEquals(0x18BFD3, palette.segmentColor(-.8, -.7, false));
        assertEquals(0x7166CC, palette.segmentColor(.8, .7, false));
        assertEquals(0xFF7A18, palette.segmentColor(0, 0, true));
        assertEquals(0x8A9A35, palette.segmentColor(-1, 0, true));
        assertEquals(0xF06432, palette.segmentColor(0, -1, true));
        assertEquals(palette.energyFlat, palette.segmentColor(.5, .5 + 1e-12, false));
        assertEquals(palette.energyFlat, palette.segmentColor(Double.NaN, .5, false));
        assertEquals(palette.rateFlat, palette.segmentColor(.5, Double.POSITIVE_INFINITY, true));
    }

    @Test
    public void paletteAndDistinctWindowsSurvivePersistenceSyncPlacementAndTypedPortableWire() {
        PanelConfigStore settings = new PanelConfigStore(() -> {});
        settings.applyChartConfig(
            "energyFlatColor=123456\nenergyUpColor=234567\nenergyDownColor=345678\n"
                + "rateFlatColor=456789\nrateUpColor=56789A\nrateDownColor=6789AB\ntrendDirectionColors=false");
        NBTTagCompound persisted = new NBTTagCompound();
        settings.writeToNBT(persisted);
        persisted.setInteger("trackingWindow", 0);
        persisted.setInteger("aeTrackingWindow", 2);
        PanelConfigStore restored = new PanelConfigStore(() -> {});
        restored.readFromNBT(persisted);
        NBTTagCompound sync = new NBTTagCompound();
        restored.writeSync(sync);
        PortablePanelRenderData data = new PortablePanelRenderData();
        data.config = sync;
        ByteBuf bytes = Unpooled.buffer();
        try {
            data.write(bytes);
            PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
            panel.render = PortablePanelRenderData.read(bytes);
            assertEquals(0, bytes.readableBytes());
            PortablePanelView view = new PortablePanelView(panel);
            TrendLinePalette palette = view.getTrendLinePalette();
            assertFalse(palette.enabled);
            assertEquals(0x123456, palette.energyFlat);
            assertEquals(0x234567, palette.energyUp);
            assertEquals(0x345678, palette.energyDown);
            assertEquals(0x456789, palette.rateFlat);
            assertEquals(0x56789A, palette.rateUp);
            assertEquals(0x6789AB, palette.rateDown);
            assertEquals(palette.energyFlat, palette.segmentColor(0, 1, false));
            assertEquals(
                0,
                view.getPortableRenderConfig()
                    .getInteger("trackingWindow"));
            assertEquals(2, view.getAETrackingWindow());
        } finally {
            bytes.release();
        }
        NBTTagCompound placement = new NBTTagCompound();
        restored.writePlacement(placement);
        PanelConfigStore placed = new PanelConfigStore(() -> {});
        placed.readPlacement(placement);
        assertEquals(0x345678, placed.getTrendLinePalette().energyDown);
        assertFalse(placed.getTrendLinePalette().enabled);
    }

    @Test
    public void oldSaveGetsEnabledPaletteAndInvalidColorsDoNotCorruptIt() {
        PanelConfigStore settings = new PanelConfigStore(() -> {});
        settings.readFromNBT(new NBTTagCompound());
        assertTrue(settings.getTrendLinePalette().enabled);
        assertEquals(0, settings.getTrackingWindow());
        assertEquals(0, settings.getAETrackingWindow());
        settings.applyChartConfig("energyUpColor=ABC\nrateDownColor=nothex");
        assertEquals(TrendLinePalette.DEFAULT.energyUp, settings.getTrendLinePalette().energyUp);
        assertEquals(TrendLinePalette.DEFAULT.rateDown, settings.getTrendLinePalette().rateDown);
        settings.applyChartConfig("energyUpColor=#112233");
        assertEquals(0x112233, settings.getTrendLinePalette().energyUp);
        settings.applyChartConfig("energyUpColor=");
        assertEquals(TrendLinePalette.DEFAULT.energyUp, settings.getTrendLinePalette().energyUp);
    }

    @Test
    public void allSplinePathsUseActualLocalDirectionIncludingStepsAndTurns() throws Exception {
        double[] values = { .2, .8, .8, .1, .5 };
        String[] methods = { "monotoneCubicPath", "chaikinPath", "monotoneCubicPath", "stepPath" };
        int[] strengths = { 6, 2, 1, 0 };
        for (int mode = 0; mode < methods.length; mode++) {
            Method method = mode == 3 ? RenderNetworkInfoPanel.class.getDeclaredMethod(methods[mode], double[].class)
                : RenderNetworkInfoPanel.class.getDeclaredMethod(methods[mode], double[].class, int.class);
            method.setAccessible(true);
            double[][] path = (double[][]) (mode == 3 ? method.invoke(null, values)
                : method.invoke(null, values, strengths[mode]));
            boolean up = false, down = false;
            for (int i = 1; i < path.length; i++) {
                assertTrue(Double.isFinite(path[i][1]));
                int color = TrendLinePalette.DEFAULT.segmentColor(path[i - 1][1], path[i][1], false);
                up |= color == TrendLinePalette.DEFAULT.energyUp;
                down |= color == TrendLinePalette.DEFAULT.energyDown;
                if (path[i][1] == path[i - 1][1]) assertEquals(TrendLinePalette.DEFAULT.energyFlat, color);
            }
            assertTrue(up);
            assertTrue(down);
        }
    }
}
