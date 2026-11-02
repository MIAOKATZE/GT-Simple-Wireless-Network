package com.miaokatze.gtswn.network;

import java.util.UUID;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Summary receipt only; no per-node UI or coordinates. */
public final class PacketSyncDeviceLinkResult implements IMessage {

    public UUID terminalId;
    public String key;
    public long requestId;
    public boolean valid, pending;
    public int success, skipped, failed;

    public PacketSyncDeviceLinkResult() {}

    public PacketSyncDeviceLinkResult(PacketDeviceLinkAction request, boolean pending, int success, int skipped,
        int failed) {
        this.terminalId = request.terminalId;
        this.key = request.key;
        this.requestId = request.requestId;
        this.pending = pending;
        this.success = success;
        this.skipped = skipped;
        this.failed = failed;
        this.valid = true;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(terminalId.getMostSignificantBits());
        buf.writeLong(terminalId.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, key);
        buf.writeLong(requestId);
        buf.writeBoolean(pending);
        buf.writeInt(success);
        buf.writeInt(skipped);
        buf.writeInt(failed);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        try {
            terminalId = new UUID(buf.readLong(), buf.readLong());
            key = ByteBufUtils.readUTF8String(buf);
            requestId = buf.readLong();
            int state = buf.readUnsignedByte();
            pending = state == 1;
            success = buf.readInt();
            skipped = buf.readInt();
            failed = buf.readInt();
            valid = requestId > 0 && key.length() <= 64
                && PacketSyncDeviceTerminalData.KEY_PATTERN.matcher(key)
                    .matches()
                && state <= 1
                && success >= 0
                && skipped >= 0
                && failed >= 0
                && !buf.isReadable();
        } catch (RuntimeException malformed) {
            valid = false;
        }
    }

    public static final class Handler implements IMessageHandler<PacketSyncDeviceLinkResult, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceLinkResult message, MessageContext context) {
            if (context.side.isClient() && message.valid) Client.receive(message);
            return null;
        }
    }

    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    private static final class Client {

        static void receive(PacketSyncDeviceLinkResult message) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            Object connection = mc.getNetHandler(), player = mc.thePlayer, world = mc.theWorld;
            mc.func_152344_a(() -> {
                if (connection != null && connection == mc.getNetHandler()
                    && player == mc.thePlayer
                    && world == mc.theWorld) com.miaokatze.gtswn.client.TerminalLinkClientState.receive(message);
            });
        }
    }

}
