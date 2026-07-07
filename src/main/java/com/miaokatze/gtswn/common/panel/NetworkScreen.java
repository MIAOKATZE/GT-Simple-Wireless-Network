package com.miaokatze.gtswn.common.panel;

import net.minecraft.nbt.NBTTagCompound;

public class NetworkScreen {

    public int minX;
    public int minY;
    public int minZ;
    public int maxX;
    public int maxY;
    public int maxZ;
    public int coreX;
    public int coreY;
    public int coreZ;
    public int facing;

    public int getWidth() {
        if (facing == 2 || facing == 3) {
            return maxX - minX + 1;
        }
        return maxZ - minZ + 1;
    }

    public int getHeight() {
        return maxY - minY + 1;
    }

    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("minX", minX);
        tag.setInteger("minY", minY);
        tag.setInteger("minZ", minZ);
        tag.setInteger("maxX", maxX);
        tag.setInteger("maxY", maxY);
        tag.setInteger("maxZ", maxZ);
        tag.setInteger("coreX", coreX);
        tag.setInteger("coreY", coreY);
        tag.setInteger("coreZ", coreZ);
        tag.setInteger("facing", facing);
        return tag;
    }

    public static NetworkScreen fromNBT(NBTTagCompound tag) {
        NetworkScreen screen = new NetworkScreen();
        screen.minX = tag.getInteger("minX");
        screen.minY = tag.getInteger("minY");
        screen.minZ = tag.getInteger("minZ");
        screen.maxX = tag.getInteger("maxX");
        screen.maxY = tag.getInteger("maxY");
        screen.maxZ = tag.getInteger("maxZ");
        screen.coreX = tag.getInteger("coreX");
        screen.coreY = tag.getInteger("coreY");
        screen.coreZ = tag.getInteger("coreZ");
        screen.facing = tag.getInteger("facing");
        return screen;
    }
}
