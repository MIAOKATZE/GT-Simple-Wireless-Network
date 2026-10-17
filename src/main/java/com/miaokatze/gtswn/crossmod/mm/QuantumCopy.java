package com.miaokatze.gtswn.crossmod.mm;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.recursive_pineapple.matter_manipulator.common.building.BlockAnalyzer.IBlockApplyContext;

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

    public static ItemStack incorporationMaterial() {
        return GTSWNItemList.Quantum_Incorporation_Anchor.get(1);
    }

    public static boolean hasIncorporationBinding(NBTTagCompound data) {
        if (data == null || !data.hasKey("incorporation", 10)) return false;
        NBTTagCompound binding = data.getCompoundTag("incorporation");
        return binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, 3)
            && binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 3)
            && binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, 3)
            && binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, 3);
    }

    /** Planning and application share the same test; valid existing targets are never billed twice. */
    public static boolean needsIncorporation(TileEntity tile, NBTTagCompound data) {
        return hasIncorporationBinding(data) && tile != null
            && tile.getWorldObj() != null
            && !tile.getWorldObj().isRemote
            && !QuantumIncorporationRegistry.isIncorporated(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord)
            && QuantumIncorporationRegistry.isEligible(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
    }

    /** Actual MM placement uses its own inventory, including pending drops, ME and uplink access. */
    public static boolean restore(TileEntity tile, NBTTagCompound data, IBlockApplyContext context) {
        if (data == null) return true;
        EntityPlayer placer = context == null ? null : context.getRealPlayer();
        if (tile == null || placer == null || tile.getWorldObj() == null || tile.getWorldObj().isRemote) return false;
        if (!data.hasKey("incorporation", 10)) return restoreLegacy(tile, data, placer);
        QuantumIncorporationRegistry registry = QuantumIncorporationRegistry.get(tile.getWorldObj());
        boolean existing = registry.find(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord) != null;
        if (!existing && !needsIncorporation(tile, data)) {
            CopySummary.incorporationFailed(placer, tile, false);
            return false;
        }
        boolean creative = placer.capabilities.isCreativeMode;
        ItemStack material = creative || existing ? null : incorporationMaterial();
        if (!creative && !existing && (material == null || material.stackSize != 1)) {
            CopySummary.incorporationFailed(placer, tile, false);
            return false;
        }
        IncorporationCopyCost.Result result = IncorporationCopyCost.apply(
            existing,
            creative,
            () -> context.tryConsumeItems(material.copy()),
            () -> registry.restoreCopiedBinding(
                tile.getWorldObj(),
                tile.xCoord,
                tile.yCoord,
                tile.zCoord,
                data.getCompoundTag("incorporation"),
                placer),
            () -> {
                QuantumIncorporationRegistry.Entry entry = registry
                    .find(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
                return tile.getWorldObj()
                    .getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile && entry != null
                    && placer.getUniqueID()
                        .toString()
                        .equals(entry.owner());
            },
            () -> context.givePlayerItems(material.copy()));
        if (result.created()) CopySummary.incorporationInstalled(placer, tile, creative ? 0 : 1);
        else if (!result.successful()) CopySummary.incorporationFailed(placer, tile, result.missingMaterial());
        if (result.failure() != null) GTSimpleWirelessNetwork.LOG.warn(
            "MM quantum incorporation update failed at {},{},{} (registration committed: {})",
            tile.xCoord,
            tile.yCoord,
            tile.zCoord,
            result.created(),
            result.failure());
        return result.successful();
    }

    /** Compatibility entry point: creating a new incorporation requires the MM inventory context. */
    public static void restore(TileEntity tile, NBTTagCompound data, EntityPlayer placer) {
        restoreLegacy(tile, data, placer);
    }

    private static boolean restoreLegacy(TileEntity tile, NBTTagCompound data, EntityPlayer placer) {
        if (data == null) return true;
        if (tile == null || placer == null || tile.getWorldObj() == null || tile.getWorldObj().isRemote) return false;
        if (data.getBoolean("node") && tile instanceof TileEntityNetworkQuantumNode node) {
            node.setAnchor(data.getInteger("dim"), data.getInteger("x"), data.getInteger("y"), data.getInteger("z"));
            node.setPlacer(placer);
            tile.getWorldObj()
                .markBlockForUpdate(tile.xCoord, tile.yCoord, tile.zCoord);
            CopySummary.quantumNodeRestored(placer, tile);
            return true;
        } else if (data.hasKey("incorporation", 10)) {
            return QuantumIncorporationRegistry
                .isIncorporated(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
        }
        return true;
    }
}
