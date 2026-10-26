package com.miaokatze.gtswn.network;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Bounded selection and pin intent; coordinates and screen credentials only come from server item NBT. */
public final class PacketRequestPortableMonitor implements IMessage {

    public static final int PROTOCOL_MAGIC = 0x50574d58;
    public static final int PROTOCOL = 60;

    public UUID terminalId;
    public int sequence;
    public int action;
    public int value;
    public UUID targetPanel = new UUID(0, 0);
    public boolean valid;
    public int selected = -1;
    public UUID selectedPanel = new UUID(0, 0);
    public boolean charts;
    public long knownRenderVersion;
    /** Only set by the separately negotiated history request, never encoded in protocol 60. */
    public boolean rawHistory;
    public long knownConfigVersion;
    public final Map<UUID, HistoryCursor> historyCursors = new HashMap<>();
    public final Map<UUID, Long> metadataVersions = new HashMap<>();

    public static final class HistoryCursor {

        public final long version, configVersion;
        public final int fields;

        public HistoryCursor(long version, long configVersion, int fields) {
            this.version = version;
            this.configVersion = configVersion;
            this.fields = fields;
        }
    }

    /** Protocol-60 opt-in: a complemented revision on the wire; legacy servers safely send a full body. */
    public boolean reuseCharts;
    public final Map<UUID, Set<String>> pins = new HashMap<>();
    public static final int MAX_PINS = 128;
    public static final int MAX_BYTES = 40 * 1024;

    public PacketRequestPortableMonitor() {}

    public PacketRequestPortableMonitor(UUID terminalId, int sequence, int action, int value) {
        this.terminalId = terminalId;
        this.sequence = sequence;
        this.action = action;
        this.value = value;
        valid = terminalId != null;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        pins.clear();
        reuseCharts = false;
        if (buf.readableBytes() < 80 || buf.readableBytes() > MAX_BYTES) return;
        try {
            if (buf.readInt() != PROTOCOL_MAGIC) return;
            if (buf.readUnsignedByte() != PROTOCOL) return;
            terminalId = new UUID(buf.readLong(), buf.readLong());
            sequence = buf.readInt();
            action = buf.readInt();
            value = buf.readInt();
            targetPanel = new UUID(buf.readLong(), buf.readLong());
            selected = buf.readInt();
            selectedPanel = new UUID(buf.readLong(), buf.readLong());
            charts = buf.readBoolean();
            knownRenderVersion = buf.readLong();
            reuseCharts = knownRenderVersion < 0;
            if (reuseCharts) knownRenderVersion = ~knownRenderVersion;
            int count = buf.readUnsignedShort();
            if (count > MAX_PINS) return;
            for (int i = 0; i < count; i++) {
                UUID panel = new UUID(buf.readLong(), buf.readLong());
                int size = buf.readUnsignedShort();
                if (size > 256 || size == 0) return;
                byte[] bytes = new byte[size];
                buf.readBytes(bytes);
                String key = new String(bytes, StandardCharsets.UTF_8);
                pins.computeIfAbsent(panel, ignored -> new HashSet<>())
                    .add(key);
            }
            valid = action >= 0 && action <= 2
                && (action != 1 || !targetPanel.equals(new UUID(0, 0)))
                && (action != 2 || value >= 0 && value <= 2)
                && selected >= -1
                && selected < 16
                && !buf.isReadable();
        } catch (RuntimeException invalid) {
            pins.clear();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (knownRenderVersion < 0) throw new IllegalArgumentException("Negative portable render revision");
        buf.writeInt(PROTOCOL_MAGIC);
        buf.writeByte(PROTOCOL);
        buf.writeLong(terminalId.getMostSignificantBits());
        buf.writeLong(terminalId.getLeastSignificantBits());
        buf.writeInt(sequence);
        buf.writeInt(action);
        buf.writeInt(value);
        buf.writeLong(targetPanel.getMostSignificantBits());
        buf.writeLong(targetPanel.getLeastSignificantBits());
        buf.writeInt(selected);
        buf.writeLong(selectedPanel.getMostSignificantBits());
        buf.writeLong(selectedPanel.getLeastSignificantBits());
        buf.writeBoolean(charts);
        buf.writeLong(reuseCharts ? ~knownRenderVersion : knownRenderVersion);
        int count = 0;
        for (Set<String> keys : pins.values()) count += keys.size();
        if (count > MAX_PINS) throw new IllegalArgumentException("Too many monitor pins");
        buf.writeShort(count);
        for (Map.Entry<UUID, Set<String>> entry : pins.entrySet()) for (String key : entry.getValue()) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 256) throw new IllegalArgumentException("Monitor pin key bound");
            buf.writeLong(
                entry.getKey()
                    .getMostSignificantBits());
            buf.writeLong(
                entry.getKey()
                    .getLeastSignificantBits());
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    public static final class Handler implements IMessageHandler<PacketRequestPortableMonitor, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestPortableMonitor message, MessageContext ctx) {
            if (message.valid) PortableMonitorRequestQueue.enqueue(ctx.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
