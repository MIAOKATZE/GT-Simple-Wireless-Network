package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.network.telemetry.ByteDelta;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/** ID42: typed full/reset, exact metadata/state ranges, or small freshness heartbeat. */
public final class PacketEngineeringCompact implements IMessage {

    public long session, base, target, serverTick;
    public int slot;
    public ByteDelta delta;
    public boolean valid;

    public PacketEngineeringCompact() {}

    public PacketEngineeringCompact(long session, int slot, long base, long target, long tick, ByteDelta delta) {
        this.session = session;
        this.slot = slot;
        this.base = delta.full ? 0 : base;
        this.target = target;
        serverTick = tick;
        this.delta = delta;
        valid = true;
    }

    public void toBytes(ByteBuf out) {
        out.writeByte(1);
        out.writeLong(session);
        out.writeByte(slot);
        out.writeLong(base);
        out.writeLong(target);
        out.writeLong(serverTick);
        delta.write(out);
    }

    public void fromBytes(ByteBuf in) {
        valid = false;
        delta = null;
        try {
            if (in.readableBytes() < 41 || in.readableBytes() > 350_000 || in.readUnsignedByte() != 1) return;
            session = in.readLong();
            slot = in.readUnsignedByte();
            base = in.readLong();
            target = in.readLong();
            serverTick = in.readLong();
            if (session <= 0 || slot > 7 || base < 0 || target <= 0 || target < base || serverTick < 0) return;
            delta = ByteDelta.read(in);
            valid = !in.isReadable() && (delta.full ? base == 0 : base > 0);
        } catch (RuntimeException invalid) {
            delta = null;
        }
    }

    public static final class Handler implements IMessageHandler<PacketEngineeringCompact, IMessage> {

        public IMessage onMessage(PacketEngineeringCompact message, MessageContext context) {
            if (context.side.isClient() && message.valid) Client.receive(message);
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    private static final class Client {

        static void receive(PacketEngineeringCompact message) {
            final Object connection = net.minecraft.client.Minecraft.getMinecraft()
                .getNetHandler();
            net.minecraft.client.Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    if (connection == net.minecraft.client.Minecraft.getMinecraft()
                        .getNetHandler())
                        com.miaokatze.gtswn.client.engineering.EngineeringDashboardCache.receiveCompact(message);
                });
        }
    }
}
