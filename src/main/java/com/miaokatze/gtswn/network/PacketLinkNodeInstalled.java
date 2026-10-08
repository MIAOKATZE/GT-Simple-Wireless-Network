package com.miaokatze.gtswn.network;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import io.netty.buffer.ByteBuf;

/** Successful player installation only; existing covers do not produce this event. */
public final class PacketLinkNodeInstalled implements IMessage {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    public int dimension, x, y, z, side;
    public boolean energy;
    public long nonce;
    public boolean valid;

    public PacketLinkNodeInstalled() {}

    public PacketLinkNodeInstalled(int dimension, int x, int y, int z, int side, boolean energy, long nonce) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.energy = energy;
        this.nonce = nonce;
        valid = side >= 0 && side < 6 && y >= 0 && y < 256 && nonce > 0;
    }

    public static void broadcast(ICoverable host, ForgeDirection side, boolean energy) {
        if (host == null || side == null || side == ForgeDirection.UNKNOWN) return;
        World world = host.getWorld();
        if (world == null || world.isRemote) return;
        Object cover = host.getCoverAtSide(side);
        if (!(cover instanceof GTswnCoverWirelessBase) || !((GTswnCoverWirelessBase) cover).isValid()
            || (cover instanceof GTswn_Cover_EnergyWireless) != energy) return;
        GTSWNPacketHandler.NETWORK.sendToAllAround(
            new PacketLinkNodeInstalled(
                world.provider.dimensionId,
                host.getXCoord(),
                host.getYCoord(),
                host.getZCoord(),
                side.ordinal(),
                energy,
                SEQUENCE.incrementAndGet()),
            new NetworkRegistry.TargetPoint(
                world.provider.dimensionId,
                host.getXCoord() + .5,
                host.getYCoord() + .5,
                host.getZCoord() + .5,
                64));
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(side);
        buf.writeBoolean(energy);
        buf.writeLong(nonce);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        if (buf.readableBytes() < 26) return;
        dimension = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        side = buf.readUnsignedByte();
        int type = buf.readUnsignedByte();
        energy = type == 1;
        nonce = buf.readLong();
        valid = side < 6 && type <= 1 && y >= 0 && y < 256 && nonce > 0;
    }

    public static final class Handler implements IMessageHandler<PacketLinkNodeInstalled, IMessage> {

        @Override
        public IMessage onMessage(PacketLinkNodeInstalled message, MessageContext context) {
            if (context.side.isClient() && message.valid) {
                GTSimpleWirelessNetwork.proxy.handleLinkNodeInstalled(message);
            }
            return null;
        }
    }
}
