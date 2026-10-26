package com.miaokatze.gtswn.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Negotiated physical screen stream. Static block/AE binding NBT stays on full description/config updates. */
public final class PacketSyncPanelHistory implements IMessage {

    public int x, y, z;
    public UUID panelId, sessionId;
    public long sequence;
    public PortableHistoryPatch history;
    public String energy = "", status = "";
    public double power;
    public boolean aePresent;
    public final Map<String, AEMonitorSample> latest = new HashMap<>();
    public final Map<String, Double> averages = new HashMap<>();
    public boolean valid;

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeLong(panelId.getMostSignificantBits());
        buf.writeLong(panelId.getLeastSignificantBits());
        buf.writeLong(sessionId.getMostSignificantBits());
        buf.writeLong(sessionId.getLeastSignificantBits());
        buf.writeLong(sequence);
        history.write(buf);
        if (energy.length() > 4096) throw new IllegalArgumentException("Exact EU value exceeds wire bound");
        PacketSyncPortableMonitor.writeString(buf, energy, 4096);
        PacketSyncPortableMonitor.writeString(buf, status, 512);
        buf.writeDouble(power);
        buf.writeBoolean(aePresent);
        java.util.Set<String> keys = new java.util.HashSet<>(latest.keySet());
        keys.addAll(averages.keySet());
        buf.writeShort(PacketSyncPortableMonitor.count(keys.size(), 512));
        for (String key : keys) {
            PacketSyncPortableMonitor.writeString(buf, key, 256);
            AEMonitorSample sample = latest.get(key);
            Double average = averages.get(key);
            buf.writeByte((sample == null ? 0 : 1) | (average == null ? 0 : 2));
            if (sample != null) {
                buf.writeLong(sample.timeMs);
                buf.writeLong(sample.tick);
                buf.writeLong(sample.amount);
                buf.writeDouble(sample.rate);
            }
            if (average != null) buf.writeDouble(average);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        latest.clear();
        averages.clear();
        if (buf.readableBytes() < 80 || buf.readableBytes() > 1024 * 1024) return;
        try {
            x = buf.readInt();
            y = buf.readInt();
            z = buf.readInt();
            panelId = new UUID(buf.readLong(), buf.readLong());
            sessionId = new UUID(buf.readLong(), buf.readLong());
            sequence = buf.readLong();
            if (sequence <= 0) return;
            history = PortableHistoryPatch.read(buf);
            energy = PacketSyncPortableMonitor.readString(buf, 4096);
            new java.math.BigInteger(energy);
            status = PacketSyncPortableMonitor.readString(buf, 512);
            power = finite(buf.readDouble());
            aePresent = buf.readBoolean();
            int count = PacketSyncPortableMonitor.count(buf.readUnsignedShort(), 512);
            for (int i = 0; i < count; i++) {
                String key = PacketSyncPortableMonitor.readString(buf, 256);
                int flags = buf.readUnsignedByte();
                if (flags == 0 || flags > 3 || latest.containsKey(key) || averages.containsKey(key)) return;
                if ((flags & 1) != 0) latest.put(
                    key,
                    new AEMonitorSample(buf.readLong(), buf.readLong(), buf.readLong(), finite(buf.readDouble())));
                if ((flags & 2) != 0) averages.put(key, finite(buf.readDouble()));
            }
            valid = !buf.isReadable();
        } catch (RuntimeException malformed) {
            latest.clear();
            averages.clear();
        }
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite panel sample");
        return value;
    }

    public static final class Handler implements IMessageHandler<PacketSyncPanelHistory, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncPanelHistory message, MessageContext context) {
            if (message.valid) GTSimpleWirelessNetwork.proxy.handleSyncPanelHistory(message);
            return null;
        }
    }
}
