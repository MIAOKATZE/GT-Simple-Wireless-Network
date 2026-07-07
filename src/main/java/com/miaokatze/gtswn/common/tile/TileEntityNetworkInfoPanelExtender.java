package com.miaokatze.gtswn.common.tile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.panel.NetworkScreen;

public class TileEntityNetworkInfoPanelExtender extends TileEntity {

    private int coreX;
    private int coreY;
    private int coreZ;
    private boolean partOfScreen;
    private NetworkScreen screen;

    public void attachToCore(TileEntityNetworkInfoPanel core, NetworkScreen nextScreen) {
        coreX = core.xCoord;
        coreY = core.yCoord;
        coreZ = core.zCoord;
        partOfScreen = true;
        screen = nextScreen;
        markDirty();
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    public void detachFromCore() {
        partOfScreen = false;
        screen = null;
        markDirty();
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    public TileEntityNetworkInfoPanel getCore() {
        if (!partOfScreen || worldObj == null) {
            return null;
        }
        TileEntity tile = worldObj.getTileEntity(coreX, coreY, coreZ);
        if (tile instanceof TileEntityNetworkInfoPanel) {
            return (TileEntityNetworkInfoPanel) tile;
        }
        return null;
    }

    public NetworkScreen getScreen() {
        return screen;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        coreX = tag.getInteger("coreX");
        coreY = tag.getInteger("coreY");
        coreZ = tag.getInteger("coreZ");
        partOfScreen = tag.getBoolean("partOfScreen");
        if (tag.hasKey("screen")) {
            screen = NetworkScreen.fromNBT(tag.getCompoundTag("screen"));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("coreX", coreX);
        tag.setInteger("coreY", coreY);
        tag.setInteger("coreZ", coreZ);
        tag.setBoolean("partOfScreen", partOfScreen);
        if (screen != null) {
            tag.setTag("screen", screen.toNBT());
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());
    }
}
