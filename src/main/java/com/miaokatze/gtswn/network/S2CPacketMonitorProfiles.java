package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.common.monitoring.MonitorProfile;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/** ID47: bounded metadata only; no source coordinates, item NBT, history or pairing credentials. */
public final class S2CPacketMonitorProfiles implements IMessage {

    public static final int PAGE_SIZE = 5;
    public long requestId;
    public int page, total;
    public boolean open, success, valid;
    public String message = "";
    public List<Entry> entries = Collections.emptyList();

    public S2CPacketMonitorProfiles() {}

    public static final class Entry {

        public UUID id, owner;
        public String name = "";
        public int sources, readers, interval, status;
        public boolean editable, offline, web;
        public List<UUID> readerIds = Collections.emptyList();
    }

    public static S2CPacketMonitorProfiles snapshot(EntityPlayerMP actor, long request, int requestedPage, boolean open,
        boolean success, String message) {
        S2CPacketMonitorProfiles result = new S2CPacketMonitorProfiles();
        result.requestId = request;
        result.open = open;
        result.success = success;
        result.message = message == null ? "" : message.substring(0, Math.min(160, message.length()));
        MonitoringProfiles store = MonitoringProfiles.get(actor.worldObj);
        List<MonitorProfile> all = store.visibleProfiles(actor.getUniqueID());
        result.total = Math.min(MonitoringProfiles.MAX_PROFILES, all.size());
        result.page = Math.max(0, Math.min(requestedPage, Math.max(0, (result.total - 1) / PAGE_SIZE)));
        List<Entry> entries = new ArrayList<>();
        for (int i = result.page * PAGE_SIZE; i < Math.min(result.total, (result.page + 1) * PAGE_SIZE); i++) {
            MonitorProfile profile = all.get(i);
            store.canRead(actor.getUniqueID(), profile.id);
            Entry entry = new Entry();
            entry.id = profile.id;
            entry.owner = profile.owner();
            entry.name = profile.name();
            entry.sources = profile.sources()
                .size();
            entry.readers = profile.readers()
                .size();
            entry.interval = profile.intervalTicks();
            entry.status = profile.status()
                .ordinal();
            entry.editable = profile.mayModify(actor.getUniqueID());
            entry.offline = profile.offlineEnabled();
            entry.web = profile.webEnabled();
            if (entry.editable) entry.readerIds = Collections.unmodifiableList(new ArrayList<>(profile.readers()));
            entries.add(entry);
        }
        result.entries = Collections.unmodifiableList(entries);
        result.valid = true;
        return result;
    }

    public void toBytes(ByteBuf out) {
        out.writeLong(requestId);
        out.writeInt(page);
        out.writeInt(total);
        out.writeBoolean(open);
        out.writeBoolean(success);
        writeString(out, message);
        out.writeByte(entries.size());
        for (Entry e : entries) {
            writeUuid(out, e.id);
            writeUuid(out, e.owner);
            writeName(out, e.name);
            out.writeInt(e.sources);
            out.writeInt(e.readers);
            out.writeInt(e.interval);
            out.writeByte(e.status);
            out.writeBoolean(e.editable);
            out.writeBoolean(e.offline);
            out.writeBoolean(e.web);
            out.writeByte(e.readerIds.size());
            for (UUID reader : e.readerIds) writeUuid(out, reader);
        }
    }

    public void fromBytes(ByteBuf in) {
        valid = false;
        entries = Collections.emptyList();
        try {
            if (in.readableBytes() > 8192 || in.readableBytes() < 21) return;
            requestId = in.readLong();
            page = in.readInt();
            total = in.readInt();
            open = in.readBoolean();
            success = in.readBoolean();
            message = readString(in);
            int count = in.readUnsignedByte();
            if (requestId < 0 || page < 0
                || total < 0
                || total > MonitoringProfiles.MAX_PROFILES
                || page > MonitoringProfiles.MAX_PROFILES / PAGE_SIZE
                || count > PAGE_SIZE) return;
            List<Entry> decoded = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Entry e = new Entry();
                e.id = readUuid(in);
                e.owner = readUuid(in);
                e.name = readName(in);
                e.sources = in.readInt();
                e.readers = in.readInt();
                e.interval = in.readInt();
                e.status = in.readUnsignedByte();
                e.editable = in.readBoolean();
                e.offline = in.readBoolean();
                e.web = in.readBoolean();
                if (e.sources < 0 || e.sources > MonitoringProfiles.MAX_SOURCES
                    || e.readers < 0
                    || e.readers > MonitoringProfiles.MAX_READERS
                    || e.interval < 20
                    || e.interval > 72000
                    || e.status >= MonitorProfile.Status.values().length) return;
                int readers = in.readUnsignedByte();
                if (readers > MonitoringProfiles.MAX_READERS || readers > e.readers || !e.editable && readers != 0)
                    return;
                List<UUID> readerIds = new ArrayList<>();
                for (int j = 0; j < readers; j++) readerIds.add(readUuid(in));
                e.readerIds = Collections.unmodifiableList(readerIds);
                decoded.add(e);
            }
            if (in.isReadable()) return;
            entries = Collections.unmodifiableList(decoded);
            valid = true;
        } catch (RuntimeException malformed) {
            valid = false;
            entries = Collections.emptyList();
        }
    }

    static void writeName(ByteBuf out, String name) {
        writeString(out, MonitorProfile.normalizeName(name));
    }

    static String readName(ByteBuf in) {
        int length = in.readUnsignedShort();
        if (length > MonitorProfile.MAX_NAME_LENGTH * 4 || length > in.readableBytes())
            throw new IllegalArgumentException("Invalid name length");
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        try {
            String name = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
            return MonitorProfile.normalizeName(name);
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid name encoding");
        }
    }

    static void writeUuid(ByteBuf out, UUID id) {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    static UUID readUuid(ByteBuf in) {
        return new UUID(in.readLong(), in.readLong());
    }

    static void writeString(ByteBuf out, String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 640) throw new IllegalArgumentException("Message too long");
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }

    static String readString(ByteBuf in) {
        int length = in.readUnsignedShort();
        if (length > 640 || length > in.readableBytes()) throw new IllegalArgumentException("Invalid message");
        byte[] bytes = new byte[length];
        in.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replaceAll("[\\p{Cntrl}§]", "");
    }

    public static final class Handler implements IMessageHandler<S2CPacketMonitorProfiles, IMessage> {

        public IMessage onMessage(S2CPacketMonitorProfiles packet, MessageContext context) {
            if (context.side.isClient() && packet.valid) Client.receive(packet);
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    private static final class Client {

        static void receive(S2CPacketMonitorProfiles packet) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            Object connection = mc.getNetHandler();
            mc.func_152344_a(() -> {
                if (connection == null || connection != mc.getNetHandler()) return;
                if (packet.open) mc.displayGuiScreen(new com.miaokatze.gtswn.client.gui.GuiMonitorProfiles(packet));
                else if (mc.currentScreen instanceof com.miaokatze.gtswn.client.gui.GuiMonitorProfiles)
                    ((com.miaokatze.gtswn.client.gui.GuiMonitorProfiles) mc.currentScreen).receive(packet);
            });
        }
    }
}
