package com.miaokatze.gtswn.common.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.config.Config;

/** Success-only guidance about the affected network, independent of the terminal's encoded key. */
public final class QuantumTerminalSecurityHints {

    private QuantumTerminalSecurityHints() {}

    /** Snapshot after authorization and before mutations can remove or transfer the network's security latch. */
    public static boolean isOpen(World world, int x, int y, int z) {
        return world != null && !world.isRemote
            && world.blockExists(x, y, z)
            && !QuantumTerminalAccess.isNetworkSafetyEnabled(world, x, y, z);
    }

    public static boolean isOpen(ItemStack terminal) {
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(terminal);
        return anchor != null && isOpen(DimensionManager.getWorld(anchor[0]), anchor[1], anchor[2], anchor[3]);
    }

    /** Call exactly once after a successful terminal action, using its pre-mutation snapshot. */
    public static void show(EntityPlayer player, boolean wasOpen) {
        if (!Config.quantumSafetyEnabled || !wasOpen || player.worldObj.isRemote) return;
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        int remaining = QualityHintCounter.consume(persisted, QualityHintCounter.Kind.OPEN_NETWORK);
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        if (remaining >= 0) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.chat.quantum.open_network_hint"));
        }
    }
}
