package com.miaokatze.gtswn.network;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;

import io.netty.buffer.ByteBuf;

/** Fixed scalar schema plus 61 real samples; no arbitrary/compressed NBT decoder. */
public final class PortablePanelRenderData {

    private static final String[] INTS = { "trackingWindow", "briefRatio", "displayMode", "chartBorderThickness",
        "trendLineThickness", "trendLineSmoothing", "trendLineSplineType", "aeTrackingWindow", "aeChartBorderThickness",
        "aeTrendLineThickness", "aeTrendLineSmoothing", "aeTrendLineSplineType", "aeMonitorFontSize",
        "aeMonitorRenderMode", "aeMonitorIconSize", "chartStyle" };
    private static final String[] BOOLS = { "showBriefEnergy", "showBriefStatus", "showChartEnergy", "showChartStatus",
        "showAEBrief", "showAEChartAmount", "showAEChartRate", "aeMonitorBold", "portableCold", "portableLongSilent",
        "trendDirectionColors" };
    private static final String[] STRINGS = { "energyAxisMin", "energyAxisMax", "eutAxisMin", "eutAxisMax",
        "chartBackgroundColor", "screenBackgroundColor", "aeChartBackgroundColor", "aeAxisMin", "aeAxisMax",
        "aeLineColor", "energyFlatColor", "energyUpColor", "energyDownColor", "rateFlatColor", "rateUpColor",
        "rateDownColor" };

    public NBTTagCompound config = new NBTTagCompound();
    public String chartKey = "";
    /** Negotiated transport provenance, not serialized in the legacy payload. */
    public String euSource = "", aeSource = "";
    public String currentEnergy = "";
    public final List<NetworkInfoSample> eu = new ArrayList<>();
    public final List<AEMonitorSample> ae = new ArrayList<>();

    /** Published responses never expose the server cache's mutable configuration or list containers. */
    public PortablePanelRenderData copy() {
        PortablePanelRenderData copy = new PortablePanelRenderData();
        copy.config = (NBTTagCompound) config.copy();
        copy.chartKey = chartKey;
        copy.euSource = euSource;
        copy.aeSource = aeSource;
        copy.currentEnergy = currentEnergy;
        copy.eu.addAll(eu);
        copy.ae.addAll(ae);
        return copy;
    }

    static boolean sameEU(List<NetworkInfoSample> first, List<NetworkInfoSample> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            NetworkInfoSample a = first.get(i), b = second.get(i);
            if (a.timeMs != b.timeMs || a.tick != b.tick
                || !a.eu.equals(b.eu)
                || Double.doubleToLongBits(a.eut) != Double.doubleToLongBits(b.eut)) return false;
        }
        return true;
    }

    static boolean sameAE(List<AEMonitorSample> first, List<AEMonitorSample> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            AEMonitorSample a = first.get(i), b = second.get(i);
            if (a.timeMs != b.timeMs || a.tick != b.tick
                || a.amount != b.amount
                || Double.doubleToLongBits(a.rate) != Double.doubleToLongBits(b.rate)) return false;
        }
        return true;
    }

    public void write(ByteBuf buf) {
        writeConfig(buf, config);
        PacketSyncPortableMonitor.writeString(buf, chartKey, 256);
        PacketSyncPortableMonitor.writeString(buf, currentEnergy, 4096);
        writeLegacySamples(buf);
    }

    static void writeConfig(ByteBuf buf, NBTTagCompound config) {
        for (String key : INTS) buf.writeInt(config.getInteger(key));
        for (String key : BOOLS)
            buf.writeBoolean("trendDirectionColors".equals(key) && !config.hasKey(key) || config.getBoolean(key));
        for (String key : STRINGS) PacketSyncPortableMonitor.writeString(buf, config.getString(key), 64);
    }

    private void writeLegacySamples(ByteBuf buf) {
        int count = Math.min(61, eu.size());
        buf.writeByte(count);
        for (int i = eu.size() - count; i < eu.size(); i++) {
            NetworkInfoSample sample = eu.get(i);
            String value = sample.eu.toString();
            if (value.length() > 512) throw new IllegalArgumentException("EU sample exceeds preview precision bound");
            buf.writeLong(sample.timeMs);
            buf.writeLong(sample.tick);
            PacketSyncPortableMonitor.writeString(buf, value, 512);
            buf.writeDouble(PacketSyncPortableMonitor.finite(sample.eut));
        }
        count = Math.min(61, ae.size());
        buf.writeByte(count);
        for (int i = ae.size() - count; i < ae.size(); i++) {
            AEMonitorSample sample = ae.get(i);
            buf.writeLong(sample.timeMs);
            buf.writeLong(sample.tick);
            buf.writeLong(sample.amount);
            buf.writeDouble(PacketSyncPortableMonitor.finite(sample.rate));
        }
    }

    static NBTTagCompound readConfig(ByteBuf buf) {
        NBTTagCompound config = new NBTTagCompound();
        for (String key : INTS) config.setInteger(key, buf.readInt());
        for (String key : BOOLS) config.setBoolean(key, buf.readBoolean());
        for (String key : STRINGS) config.setString(key, PacketSyncPortableMonitor.readString(buf, 64));
        return config;
    }

    public static PortablePanelRenderData read(ByteBuf buf) {
        PortablePanelRenderData data = new PortablePanelRenderData();
        data.config = readConfig(buf);
        data.chartKey = PacketSyncPortableMonitor.readString(buf, 256);
        data.currentEnergy = PacketSyncPortableMonitor.readString(buf, 4096);
        int count = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 61);
        for (int i = 0; i < count; i++) {
            long time = buf.readLong(), tick = buf.readLong();
            BigInteger value = new BigInteger(PacketSyncPortableMonitor.readString(buf, 512));
            data.eu.add(new NetworkInfoSample(time, tick, value, PacketSyncPortableMonitor.finite(buf.readDouble())));
        }
        count = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 61);
        for (int i = 0; i < count; i++) data.ae.add(
            new AEMonitorSample(
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                PacketSyncPortableMonitor.finite(buf.readDouble())));
        return data;
    }
}
