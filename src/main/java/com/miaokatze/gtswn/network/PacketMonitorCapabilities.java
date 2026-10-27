package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/** ID36, only sent in response to the reserved legacy request with an echoed nonce. */
public final class PacketMonitorCapabilities implements IMessage {

    public long nonce;
    public int bits;
    /** Present only for the identity-aware probe; the legacy reply remains exactly 12 bytes. */
    public UUID playerId;

    public PacketMonitorCapabilities() {}

    public PacketMonitorCapabilities(long nonce, int bits) {
        this(nonce, bits, null);
    }

    public PacketMonitorCapabilities(long nonce, int bits, UUID playerId) {
        this.nonce = nonce;
        this.bits = bits;
        this.playerId = playerId;
    }

    public void toBytes(ByteBuf out) {
        out.writeLong(nonce);
        out.writeInt(bits);
        if (playerId != null) {
            out.writeLong(playerId.getMostSignificantBits());
            out.writeLong(playerId.getLeastSignificantBits());
        }
    }

    public void fromBytes(ByteBuf in) {
        nonce = 0;
        bits = 0;
        playerId = null;
        if (in.readableBytes() != 12 && in.readableBytes() != 28) return;
        long value = in.readLong();
        int features = in.readInt();
        if (value <= 0 || (features & ~MonitorCapabilities.ALL) != 0) return;
        nonce = value;
        bits = features;
        if (in.isReadable()) playerId = new UUID(in.readLong(), in.readLong());
    }

    public static final class Handler implements IMessageHandler<PacketMonitorCapabilities, IMessage> {

        public IMessage onMessage(PacketMonitorCapabilities message, MessageContext context) {
            if (context.side.isClient() && message.nonce > 0) Client.receive(message);
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    private static final class Client {

        static void receive(PacketMonitorCapabilities message) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            Object connection = minecraft.getNetHandler();
            minecraft.func_152344_a(() -> {
                if (connection != null && connection == minecraft.getNetHandler())
                    MonitorCapabilities.receive(message.nonce, message.bits, message.playerId);
            });
        }
    }
}
