package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.client.PortablePanelView;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PortablePanelRenderData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class NetworkChartStyleTest {

    @Test
    public void everyStyleSurvivesSaveSyncPlacementAndPortableWire() {
        for (NetworkChartStyle style : NetworkChartStyle.values()) {
            PanelConfigStore source = new PanelConfigStore(() -> {});
            source.applyChartConfig("chartStyle=" + style.ordinal());
            NBTTagCompound tag = new NBTTagCompound();
            source.writeToNBT(tag);
            PanelConfigStore restored = new PanelConfigStore(() -> {});
            restored.readFromNBT(tag);
            assertEquals(style, restored.getChartStyle());
            NBTTagCompound placement = new NBTTagCompound();
            restored.writePlacement(placement);
            PanelConfigStore placed = new PanelConfigStore(() -> {});
            placed.readPlacement(placement);
            assertEquals(style, placed.getChartStyle());
            NBTTagCompound sync = new NBTTagCompound();
            restored.writeSync(sync);
            PanelConfigStore client = new PanelConfigStore(() -> {});
            client.readSync(sync);
            assertEquals(style, client.getChartStyle());
            PortablePanelRenderData render = new PortablePanelRenderData();
            render.config = sync;
            ByteBuf bytes = Unpooled.buffer();
            try {
                render.write(bytes);
                PacketSyncPortableMonitor.Panel panel = new PacketSyncPortableMonitor.Panel();
                panel.render = PortablePanelRenderData.read(bytes);
                assertEquals(0, bytes.readableBytes());
                assertEquals(style, new PortablePanelView(panel).getChartStyle());
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void legacyAndInvalidConfigurationFallBackToLineWithoutChangingWindow() {
        PanelConfigStore store = new PanelConfigStore(() -> {});
        store.readSync(new NBTTagCompound());
        assertEquals(NetworkChartStyle.LINE, store.getChartStyle());
        int window = store.getTrackingWindow();
        store.applyChartConfig("chartStyle=2");
        assertEquals(NetworkChartStyle.COMBINED, store.getChartStyle());
        store.applyChartConfig("line=5");
        assertEquals(NetworkChartStyle.COMBINED, store.getChartStyle());
        for (String bad : new String[] { "-1", "3", "99999999999999999999", "bad", "" }) {
            store.applyChartConfig("chartStyle=2\nchartStyle=" + bad);
            assertEquals(NetworkChartStyle.LINE, store.getChartStyle());
        }
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setInteger("chartStyle", -1);
        store.readPlacement(invalid);
        assertEquals(NetworkChartStyle.LINE, store.getChartStyle());
        invalid.setInteger("chartStyle", 3);
        store.readFromNBT(invalid);
        assertEquals(NetworkChartStyle.LINE, store.getChartStyle());
        assertEquals(window, store.getTrackingWindow());
    }
}
