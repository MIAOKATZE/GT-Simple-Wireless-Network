package com.miaokatze.gtswn.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.panel.TrendLinePalette;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Bounded, read-only snapshots. No compressed NBT or world references cross the wire. */
public final class PacketSyncPortableMonitor implements IMessage {

    public static final int MAX_ROWS = 514;
    public static final int MAX_TOTAL_ROWS = MAX_ROWS + PacketRequestPortableMonitor.MAX_PINS;
    public static final int MAX_SAMPLES = 61;
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int PROTOCOL = PacketRequestPortableMonitor.PROTOCOL;
    public UUID terminalId;
    public int sequence;
    public int hudMode;
    public long bindingRevision;
    /** 0 snapshot, 1 mutation applied, 2 current terminal owner required, 3 target no longer bound. */
    public int result;
    public boolean valid;
    public final List<Panel> panels = new ArrayList<>();

    public PacketSyncPortableMonitor() {}

    public PacketSyncPortableMonitor(UUID terminalId, int sequence, int hudMode) {
        this.terminalId = terminalId;
        this.sequence = sequence;
        this.hudMode = hudMode;
        this.valid = true;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        panels.clear();
        if (buf.readableBytes() > MAX_BYTES || buf.readableBytes() < 39) return;
        try {
            if (buf.readInt() != PacketRequestPortableMonitor.PROTOCOL_MAGIC) return;
            if (buf.readUnsignedByte() != PROTOCOL) return;
            terminalId = new UUID(buf.readLong(), buf.readLong());
            sequence = buf.readInt();
            hudMode = count(buf.readInt(), 2);
            bindingRevision = buf.readLong();
            result = count(buf.readUnsignedByte(), 3);
            int count = count(buf.readUnsignedByte(), MonitorBindings.MAX_PANELS);
            int totalRows = 0;
            int fullPanels = 0;
            for (int i = 0; i < count; i++) {
                Panel panel = new Panel();
                panel.binding = new MonitorBindings.Binding(
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    new UUID(buf.readLong(), buf.readLong()));
                panel.state = count(buf.readUnsignedByte(), 3);
                panel.tab = count(buf.readUnsignedByte(), 2);
                panel.ownerName = readString(buf, 128);
                panel.window = readString(buf, 32);
                panel.aeWindow = readString(buf, 32);
                panel.displayMode = count(buf.readUnsignedByte(), 2);
                panel.palette = new TrendLinePalette(
                    buf.readBoolean(),
                    buf.readInt() & 0xffffff,
                    buf.readInt() & 0xffffff,
                    buf.readInt() & 0xffffff,
                    buf.readInt() & 0xffffff,
                    buf.readInt() & 0xffffff,
                    buf.readInt() & 0xffffff);
                panel.chartKey = readString(buf, 256);
                panel.currentEnergy = readString(buf, 4096);
                panel.aeConnected = buf.readBoolean();
                panel.detail = buf.readBoolean();
                panel.sampleTime = buf.readLong();
                int rows = count(buf.readUnsignedShort(), MAX_ROWS);
                totalRows += rows;
                if (totalRows > MAX_TOTAL_ROWS) throw new IllegalArgumentException("Portable monitor total row bound");
                for (int j = 0; j < rows; j++) {
                    Row row = new Row();
                    row.key = readString(buf, 256);
                    row.name = readString(buf, 128);
                    row.amount = readString(buf, 512);
                    row.rate = finite(buf.readDouble());
                    row.average = finite(buf.readDouble());
                    row.kind = count(buf.readUnsignedByte(), 2);
                    row.monitored = buf.readBoolean();
                    panel.rows.add(row);
                }
                panel.euChart = readChart(buf);
                panel.aeChart = readChart(buf);
                panel.euRateChart = readChart(buf);
                panel.aeRateChart = readChart(buf);
                panel.renderVersion = buf.readLong();
                if (buf.readBoolean()) {
                    if (!panel.detail || ++fullPanels > 1)
                        throw new IllegalArgumentException("Portable full panel bound");
                    panel.render = PortablePanelRenderData.read(buf);
                }
                panels.add(panel);
            }
            valid = !buf.isReadable();
        } catch (RuntimeException ignored) {
            panels.clear();
            terminalId = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int totalRows = 0;
        int fullPanels = 0;
        for (Panel panel : panels) totalRows += Math.min(MAX_ROWS, panel.rows.size());
        for (Panel panel : panels) if (panel.render != null) {
            if (!panel.detail || ++fullPanels > 1) throw new IllegalArgumentException("Portable full panel bound");
        }
        if (panels.size() > MonitorBindings.MAX_PANELS || totalRows > MAX_TOTAL_ROWS) {
            throw new IllegalArgumentException("Portable monitor snapshot bound");
        }
        buf.writeInt(PacketRequestPortableMonitor.PROTOCOL_MAGIC);
        buf.writeByte(PROTOCOL);
        buf.writeLong(terminalId.getMostSignificantBits());
        buf.writeLong(terminalId.getLeastSignificantBits());
        buf.writeInt(sequence);
        buf.writeInt(hudMode);
        buf.writeLong(bindingRevision);
        buf.writeByte(result);
        buf.writeByte(panels.size());
        for (Panel panel : panels) {
            MonitorBindings.Binding b = panel.binding;
            buf.writeInt(b.dimension);
            buf.writeInt(b.x);
            buf.writeInt(b.y);
            buf.writeInt(b.z);
            buf.writeLong(b.panelId.getMostSignificantBits());
            buf.writeLong(b.panelId.getLeastSignificantBits());
            buf.writeByte(panel.state);
            buf.writeByte(panel.tab);
            writeString(buf, panel.ownerName, 128);
            writeString(buf, panel.window, 32);
            writeString(buf, panel.aeWindow, 32);
            buf.writeByte(panel.displayMode);
            buf.writeBoolean(panel.palette.enabled);
            buf.writeInt(panel.palette.energyFlat);
            buf.writeInt(panel.palette.energyUp);
            buf.writeInt(panel.palette.energyDown);
            buf.writeInt(panel.palette.rateFlat);
            buf.writeInt(panel.palette.rateUp);
            buf.writeInt(panel.palette.rateDown);
            writeString(buf, panel.chartKey, 256);
            writeString(buf, panel.currentEnergy, 4096);
            buf.writeBoolean(panel.aeConnected);
            buf.writeBoolean(panel.detail);
            buf.writeLong(panel.sampleTime);
            int size = Math.min(MAX_ROWS, panel.rows.size());
            buf.writeShort(size);
            for (int i = 0; i < size; i++) {
                Row row = panel.rows.get(i);
                writeString(buf, row.key, 256);
                writeString(buf, row.name, 128);
                writeString(buf, row.amount, row.kind == 0 ? 512 : 32);
                buf.writeDouble(finite(row.rate));
                buf.writeDouble(finite(row.average));
                buf.writeByte(row.kind);
                buf.writeBoolean(row.monitored);
            }
            writeChart(buf, panel.euChart);
            writeChart(buf, panel.aeChart);
            writeChart(buf, panel.euRateChart);
            writeChart(buf, panel.aeRateChart);
            buf.writeLong(panel.renderVersion);
            buf.writeBoolean(panel.render != null);
            if (panel.render != null) panel.render.write(buf);
        }
    }

    static int count(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Portable monitor bound");
        return value;
    }

    static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    static String readString(ByteBuf buf, int max) {
        int size = count(buf.readUnsignedShort(), max * 4);
        byte[] data = new byte[size];
        buf.readBytes(data);
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.length() > max) throw new IllegalArgumentException("Portable monitor string bound");
        return text;
    }

    static void writeString(ByteBuf buf, String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() > max) text = text.substring(0, max);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // Limit encoded bytes too: one full screen plus 128 pins stays below 1 MiB.
        while (bytes.length > max) {
            int cut = text.offsetByCodePoints(text.length(), -1);
            text = text.substring(0, cut);
            bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    private static double[] readChart(ByteBuf buf) {
        int count = count(buf.readUnsignedByte(), MAX_SAMPLES);
        double[] values = new double[count];
        for (int i = 0; i < count; i++) values[i] = Math.max(0, Math.min(1, finite(buf.readDouble())));
        return values;
    }

    private static void writeChart(ByteBuf buf, double[] values) {
        int count = Math.min(MAX_SAMPLES, values.length);
        buf.writeByte(count);
        for (int i = 0; i < count; i++) buf.writeDouble(Math.max(0, Math.min(1, finite(values[i]))));
    }

    public static final class Panel {

        public MonitorBindings.Binding binding;
        /** 0 ready, 1 unloaded, 2 missing, 3 replaced. */
        public int state, tab;
        public String ownerName = "", window = "", aeWindow = "";
        public int displayMode;
        public TrendLinePalette palette = TrendLinePalette.DEFAULT;
        public String chartKey = "";
        public String currentEnergy = "";
        public boolean aeConnected;
        public boolean detail;
        public long sampleTime;
        public final List<Row> rows = new ArrayList<>();
        public double[] euChart = new double[0], aeChart = new double[0];
        public double[] euRateChart = new double[0], aeRateChart = new double[0];
        public long renderVersion;
        public PortablePanelRenderData render;
    }

    public static final class Row {

        /** 0 EU, 1 item, 2 fluid. */
        public int kind;
        public String key = "", name = "", amount = "";
        public double rate, average;
        public boolean monitored;
    }

    public static final class Handler implements IMessageHandler<PacketSyncPortableMonitor, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncPortableMonitor message, MessageContext ctx) {
            if (message.valid) GTSimpleWirelessNetwork.proxy.handleSyncPortableMonitor(message);
            return null;
        }
    }
}
