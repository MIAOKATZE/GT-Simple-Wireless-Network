package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Ordinary mode ACKs remain 17 bytes. Explicit action outcomes are optional and never toggle mode implicitly. */
public final class PacketSyncDeviceTerminalVisualState implements IMessage {

    public UUID terminalId;
    public boolean enabled;
    public boolean hasResult;
    public byte result;
    public String targetKey = "";
    public int cooldownTicks;
    public double x, y, z;

    public PacketSyncDeviceTerminalVisualState() {}

    public PacketSyncDeviceTerminalVisualState(UUID id, boolean enabled) {
        terminalId = id;
        this.enabled = enabled;
    }

    public PacketSyncDeviceTerminalVisualState(UUID id, byte result, String key, int cooldown, double x, double y,
        double z) {
        terminalId = id;
        hasResult = true;
        this.result = result;
        targetKey = key == null ? "" : key;
        cooldownTicks = Math.max(0, Math.min(60, cooldown));
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(terminalId.getMostSignificantBits());
        buffer.writeLong(terminalId.getLeastSignificantBits());
        buffer.writeBoolean(enabled);
        if (hasResult) {
            buffer.writeByte(1);
            buffer.writeByte(result);
            ByteBufUtils.writeUTF8String(buffer, targetKey);
            buffer.writeInt(cooldownTicks);
            buffer.writeDouble(x);
            buffer.writeDouble(y);
            buffer.writeDouble(z);
        }
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        terminalId = null;
        enabled = false;
        hasResult = false;
        targetKey = "";
        cooldownTicks = 0;
        try {
            if (buffer.readableBytes() < 17 || buffer.readableBytes() > 240) return;
            UUID id = new UUID(buffer.readLong(), buffer.readLong());
            int mode = buffer.readUnsignedByte();
            if (mode > 1 || PacketDeviceTerminalVisualAction.NEW_TERMINAL.equals(id)) return;
            if (buffer.isReadable()) {
                if (buffer.readUnsignedByte() != 1) return;
                result = buffer.readByte();
                targetKey = PacketDeviceTerminalVisualAction.readBoundedUtf8(buffer, 64);
                cooldownTicks = buffer.readInt();
                x = buffer.readDouble();
                y = buffer.readDouble();
                z = buffer.readDouble();
                if (result < 0 || result > 12
                    || cooldownTicks < 0
                    || cooldownTicks > 60
                    || buffer.isReadable()
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || (!targetKey.isEmpty() && !PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(targetKey)
                        .matches()))
                    return;
                hasResult = true;
            }
            terminalId = id;
            enabled = mode == 1;
        } catch (Exception invalid) {
            terminalId = null;
            hasResult = false;
        }
    }

    public static final class Handler implements IMessageHandler<PacketSyncDeviceTerminalVisualState, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceTerminalVisualState message, MessageContext context) {
            if (context.side.isClient() && message.terminalId != null)
                GTSimpleWirelessNetwork.proxy.handleSyncDeviceTerminalVisualState(message);
            return null;
        }
    }
}
