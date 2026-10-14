package com.miaokatze.gtswn.crossmod.mm;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

/** Only binding settings travel in blueprints; owner, identity, AE proxy and inventories never do. */
public final class QuantumCopy {

    private QuantumCopy() {}

    public static NBTTagCompound capture(TileEntity tile) {
        if (tile == null || tile.getWorldObj() == null || tile.getWorldObj().isRemote) return null;
        if (tile instanceof TileEntityNetworkQuantumNode node) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setBoolean("node", true);
            tag.setInteger("dim", node.getAnchorDim());
            tag.setInteger("x", node.getAnchorX());
            tag.setInteger("y", node.getAnchorY());
            tag.setInteger("z", node.getAnchorZ());
            return tag;
        }
        NBTTagCompound binding = QuantumIncorporationRegistry.get(tile.getWorldObj())
            .copyBinding(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
        if (binding == null) return null;
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("incorporation", binding);
        return tag;
    }

    public static void restore(TileEntity tile, NBTTagCompound data, EntityPlayer placer) {
        if (tile == null || data == null || placer == null || tile.getWorldObj() == null || tile.getWorldObj().isRemote)
            return;
        if (data.getBoolean("node") && tile instanceof TileEntityNetworkQuantumNode node) {
            node.setAnchor(data.getInteger("dim"), data.getInteger("x"), data.getInteger("y"), data.getInteger("z"));
            node.setPlacer(placer);
            tile.getWorldObj()
                .markBlockForUpdate(tile.xCoord, tile.yCoord, tile.zCoord);
        } else if (data.hasKey("incorporation", 10)) {
            QuantumIncorporationRegistry.get(tile.getWorldObj())
                .restoreCopiedBinding(
                    tile.getWorldObj(),
                    tile.xCoord,
                    tile.yCoord,
                    tile.zCoord,
                    data.getCompoundTag("incorporation"),
                    placer);
        }
    }
}
