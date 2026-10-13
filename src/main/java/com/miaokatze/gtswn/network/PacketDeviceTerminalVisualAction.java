package com.miaokatze.gtswn.network;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.miaokatze.gtswn.common.device.DeviceTerminalNameRules;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Bounded client intent, with declared UTF-8 lengths checked before allocation. */
public final class PacketDeviceTerminalVisualAction implements IMessage {

    public static final byte ACTION_TOGGLE = 0;
    public static final byte ACTION_VISUAL_TELEPORT = 1;
    public static final byte ACTION_RENAME = 2;
    public static final UUID NEW_TERMINAL = new UUID(0, 0);
    UUID terminalId;
    byte action = -1;
    String key = "", name = "";
    int color = DeviceTerminalNameRules.DEFAULT_COLOR;
    boolean bold;

    public PacketDeviceTerminalVisualAction() {}

    private PacketDeviceTerminalVisualAction(UUID id, byte action, String key, String name, int color, boolean bold) {
        terminalId = id;
        this.action = action;
        this.key = key == null ? "" : key;
        this.name = name == null ? "" : name;
        this.color = color;
        this.bold = bold;
        if (!valid()) this.action = -1;
    }

    public static PacketDeviceTerminalVisualAction toggle(UUID id) {
        return new PacketDeviceTerminalVisualAction(id == null ? NEW_TERMINAL : id, ACTION_TOGGLE, "", "", 15, false);
    }

    public static PacketDeviceTerminalVisualAction teleport(UUID id, String key) {
        return new PacketDeviceTerminalVisualAction(id, ACTION_VISUAL_TELEPORT, key, "", 15, false);
    }

    public static PacketDeviceTerminalVisualAction rename(UUID id, String key, String name, int color, boolean bold) {
        return new PacketDeviceTerminalVisualAction(id, ACTION_RENAME, key, name, color, bold);
    }

    boolean valid() {
        if (terminalId == null || action < 0 || action > 2) return false;
        if (action == ACTION_TOGGLE) return key.isEmpty() && name.isEmpty();
        if (NEW_TERMINAL.equals(terminalId) || key.length() > 64
            || !PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(key)
                .matches())
            return false;
        return action == ACTION_RENAME
            ? DeviceTerminalNameRules.normalize(name) != null && DeviceTerminalNameRules.validColor(color)
            : name.isEmpty();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        UUID id = terminalId == null ? NEW_TERMINAL : terminalId;
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
        buffer.writeByte(action);
        ByteBufUtils.writeUTF8String(buffer, key);
        ByteBufUtils.writeUTF8String(buffer, name);
        buffer.writeByte(color);
        buffer.writeBoolean(bold);
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        action = -1;
        terminalId = null;
        try {
            if (buffer.readableBytes() < 21 || buffer.readableBytes() > 384) return;
            terminalId = new UUID(buffer.readLong(), buffer.readLong());
            action = buffer.readByte();
            key = readBoundedUtf8(buffer, 64);
            name = readBoundedUtf8(buffer, DeviceTerminalNameRules.MAX_NAME_LENGTH);
            color = buffer.readUnsignedByte();
            int style = buffer.readUnsignedByte();
            bold = style == 1;
            if (style > 1 || buffer.isReadable() || !valid()) {
                action = -1;
                terminalId = null;
            }
        } catch (Exception invalid) {
            action = -1;
            terminalId = null;
        }
    }

    private static String readBoundedUtf8(ByteBuf buffer, int characters) throws Exception {
        int bytes = ByteBufUtils.readVarInt(buffer, 2);
        if (bytes < 0 || bytes > characters * 3 || bytes > buffer.readableBytes())
            throw new IllegalArgumentException("UTF8 length");
        byte[] data = new byte[bytes];
        buffer.readBytes(data);
        String decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString();
        if (decoded.length() > characters) throw new IllegalArgumentException("UTF8 characters");
        return decoded;
    }

    public static final class Handler implements IMessageHandler<PacketDeviceTerminalVisualAction, IMessage> {

        @Override
        public IMessage onMessage(PacketDeviceTerminalVisualAction message, MessageContext context) {
            if (!context.side.isServer() || !message.valid()) return null;
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(24);
            DeviceTerminalVisualActionQueue.enqueue(context.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
