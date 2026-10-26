package com.miaokatze.gtswn.network;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;

import io.netty.buffer.ByteBuf;

/** Raw, bounded window transform. The base is an actually received client revision, never merely sent. */
public final class PortableHistoryPatch {

    public long baseVersion, targetVersion, configVersion;
    public NBTTagCompound config;
    public String chartKey = "";
    public String euSource = "", aeSource = "";
    public int euDrop, aeDrop;
    public boolean euReset, aeReset;
    public final List<NetworkInfoSample> eu = new ArrayList<>();
    public final List<AEMonitorSample> ae = new ArrayList<>();

    static PortableHistoryPatch between(PortableMonitorRequestQueue.RenderCache base,
        PortableMonitorRequestQueue.RenderCache next, boolean euGraph, boolean aeGraph) {
        PortableHistoryPatch patch = new PortableHistoryPatch();
        patch.baseVersion = base == null ? 0 : base.version;
        patch.targetVersion = next.version;
        patch.configVersion = next.configVersion;
        if (base == null || base.configVersion != next.configVersion)
            patch.config = (NBTTagCompound) next.render.config.copy();
        patch.chartKey = next.render.chartKey;
        patch.euSource = next.render.euSource;
        patch.aeSource = next.render.aeSource;
        List<NetworkInfoSample> oldEU = base == null ? Collections.emptyList() : base.eu;
        List<AEMonitorSample> oldAE = base == null ? Collections.emptyList() : base.ae;
        List<NetworkInfoSample> nextEU = euGraph ? next.eu : Collections.emptyList();
        List<AEMonitorSample> nextAE = aeGraph ? next.ae : Collections.emptyList();
        int euOverlap = euOverlap(oldEU, nextEU), aeOverlap = aeOverlap(oldAE, nextAE);
        patch.euReset = base == null || euOverlap == 0 && !oldEU.isEmpty()
            || base != null && !base.render.euSource.equals(next.render.euSource);
        patch.aeReset = base == null || aeOverlap == 0 && !oldAE.isEmpty()
            || base != null && (!base.render.chartKey.equals(next.render.chartKey)
                || !base.render.aeSource.equals(next.render.aeSource));
        patch.euDrop = patch.euReset ? 0 : oldEU.size() - euOverlap;
        patch.aeDrop = patch.aeReset ? 0 : oldAE.size() - aeOverlap;
        patch.eu.addAll(nextEU.subList(patch.euReset ? 0 : euOverlap, nextEU.size()));
        patch.ae.addAll(nextAE.subList(patch.aeReset ? 0 : aeOverlap, nextAE.size()));
        return patch;
    }

    static int euOverlap(List<NetworkInfoSample> old, List<NetworkInfoSample> next) {
        for (int size = Math.min(old.size(), next.size()); size > 0; size--)
            if (PortablePanelRenderData.sameEU(old.subList(old.size() - size, old.size()), next.subList(0, size)))
                return size;
        return 0;
    }

    static int aeOverlap(List<AEMonitorSample> old, List<AEMonitorSample> next) {
        for (int size = Math.min(old.size(), next.size()); size > 0; size--)
            if (PortablePanelRenderData.sameAE(old.subList(old.size() - size, old.size()), next.subList(0, size)))
                return size;
        return 0;
    }

    /** Apply atomically; a lost/unknown base is not a fresh snapshot and must be requested in full. */
    public boolean apply(PacketSyncPortableMonitor.Panel target, PacketSyncPortableMonitor.Panel base) {
        if (targetVersion <= 0 || baseVersion < 0
            || baseVersion > targetVersion
            || configVersion <= 0
            || configVersion > targetVersion
            || targetVersion != target.renderVersion
            || euDrop < 0
            || euDrop > 61
            || aeDrop < 0
            || aeDrop > 61
            || eu.size() > 61
            || ae.size() > 61) return false;
        if (baseVersion != 0 && (base == null || base.render == null || base.renderVersion != baseVersion))
            return false;
        if (baseVersion == 0 && (config == null || !euReset || !aeReset)) return false;
        if (config == null && (base == null || base.configVersion != configVersion)) return false;
        PortablePanelRenderData render = new PortablePanelRenderData();
        render.config = (NBTTagCompound) (config == null ? base.render.config : config).copy();
        render.chartKey = chartKey;
        render.euSource = euSource;
        render.aeSource = aeSource;
        render.currentEnergy = target.currentEnergy;
        if (!euReset) {
            if (base == null || euDrop > base.render.eu.size() || !base.render.euSource.equals(euSource)) return false;
            render.eu.addAll(base.render.eu.subList(euDrop, base.render.eu.size()));
        }
        if (!aeReset) {
            if (base == null || aeDrop > base.render.ae.size()
                || !base.render.chartKey.equals(chartKey)
                || !base.render.aeSource.equals(aeSource)) return false;
            render.ae.addAll(base.render.ae.subList(aeDrop, base.render.ae.size()));
        }
        render.eu.addAll(eu);
        render.ae.addAll(ae);
        if (render.eu.size() > 61 || render.ae.size() > 61) return false;
        target.render = render;
        target.configVersion = configVersion;
        target.historyReset = euReset || aeReset;
        List<BigDecimal> energy = new ArrayList<>(), power = new ArrayList<>(), amount = new ArrayList<>(),
            rate = new ArrayList<>();
        for (NetworkInfoSample sample : render.eu) {
            energy.add(new BigDecimal(sample.eu));
            power.add(BigDecimal.valueOf(sample.eut));
        }
        for (AEMonitorSample sample : render.ae) {
            amount.add(BigDecimal.valueOf(sample.amount));
            rate.add(BigDecimal.valueOf(sample.rate));
        }
        target.euChart = PortableMonitorRequestQueue.normalize(energy);
        target.euRateChart = PortableMonitorRequestQueue.normalize(power);
        target.aeChart = PortableMonitorRequestQueue.normalize(amount);
        target.aeRateChart = PortableMonitorRequestQueue.normalize(rate);
        return true;
    }

    void write(ByteBuf buf) {
        buf.writeLong(baseVersion);
        buf.writeLong(targetVersion);
        buf.writeLong(configVersion);
        buf.writeBoolean(config != null);
        if (config != null) PortablePanelRenderData.writeConfig(buf, config);
        PacketSyncPortableMonitor.writeString(buf, chartKey, 256);
        PacketSyncPortableMonitor.writeString(buf, euSource, 256);
        PacketSyncPortableMonitor.writeString(buf, aeSource, 256);
        buf.writeBoolean(euReset);
        buf.writeBoolean(aeReset);
        buf.writeByte(euDrop);
        buf.writeByte(aeDrop);
        buf.writeByte(PacketSyncPortableMonitor.count(eu.size(), 61));
        for (NetworkInfoSample sample : eu) {
            String exact = sample.eu.toString();
            if (exact.length() > 4096)
                throw new IllegalArgumentException("EU history value exceeds bounded exact wire");
            buf.writeLong(sample.timeMs);
            buf.writeLong(sample.tick);
            PacketSyncPortableMonitor.writeString(buf, exact, 4096);
            buf.writeDouble(finite(sample.eut));
        }
        buf.writeByte(PacketSyncPortableMonitor.count(ae.size(), 61));
        for (AEMonitorSample sample : ae) {
            buf.writeLong(sample.timeMs);
            buf.writeLong(sample.tick);
            buf.writeLong(sample.amount);
            buf.writeDouble(finite(sample.rate));
        }
    }

    static PortableHistoryPatch read(ByteBuf buf) {
        PortableHistoryPatch patch = new PortableHistoryPatch();
        patch.baseVersion = buf.readLong();
        patch.targetVersion = buf.readLong();
        patch.configVersion = buf.readLong();
        if (patch.baseVersion < 0 || patch.targetVersion <= 0
            || patch.configVersion <= 0
            || patch.baseVersion > patch.targetVersion
            || patch.configVersion > patch.targetVersion)
            throw new IllegalArgumentException("Invalid portable history revisions");
        if (buf.readBoolean()) patch.config = PortablePanelRenderData.readConfig(buf);
        patch.chartKey = PacketSyncPortableMonitor.readString(buf, 256);
        patch.euSource = PacketSyncPortableMonitor.readString(buf, 256);
        patch.aeSource = PacketSyncPortableMonitor.readString(buf, 256);
        patch.euReset = buf.readBoolean();
        patch.aeReset = buf.readBoolean();
        patch.euDrop = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 61);
        patch.aeDrop = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 61);
        int count = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 61);
        for (int i = 0; i < count; i++) {
            long time = buf.readLong(), tick = buf.readLong();
            BigInteger exact = new BigInteger(PacketSyncPortableMonitor.readString(buf, 4096));
            patch.eu.add(new NetworkInfoSample(time, tick, exact, finite(buf.readDouble())));
        }
        count = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 61);
        for (int i = 0; i < count; i++)
            patch.ae.add(new AEMonitorSample(buf.readLong(), buf.readLong(), buf.readLong(), finite(buf.readDouble())));
        return patch;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite history sample");
        return value;
    }
}
