package com.miaokatze.gtswn.common.quantum;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

/** Small server-authored, read-only snapshots of one pointed quantum connection. */
public final class QuantumConnectionInfo {

    private QuantumConnectionInfo() {}

    public static void node(EntityPlayer player, TileEntityNetworkQuantumNode node) {
        tell(player, "gtswn.chat.quantum.info.node");
        position(player, node.xCoord, node.yCoord, node.zCoord);
        owner(player, node.getPlacerName(), node.getPlacerUUID());
        tell(player, "gtswn.chat.quantum.info.native_owner", node.getNativeOwnerID());
        if (node.hasAnchor())
            anchor(player, node.getAnchorDim(), node.getAnchorX(), node.getAnchorY(), node.getAnchorZ());
        else tell(player, "gtswn.chat.quantum.info.unbound");
        tell(player, "gtswn.chat.quantum.info.node_channels", node.getBridgeUsedChannels());
    }

    public static void incorporation(EntityPlayer player, QuantumIncorporationRegistry.Entry entry, int x, int y,
        int z) {
        tell(player, "gtswn.chat.quantum.info.incorporation");
        position(player, x, y, z);
        owner(player, entry.ownerName(), entry.owner());
        anchor(player, entry.dim, entry.ax, entry.ay, entry.az);
        tell(player, entry.isConnected() ? "gtswn.chat.quantum.node_online" : "gtswn.chat.quantum.node_offline");
        tell(
            player,
            "gtswn.chat.quantum.info.incorporation_channels",
            entry.usedChannels(),
            QuantumControllerRegistry.isChannelsInfinite()
                ? new ChatComponentTranslation("gtswn.chat.quantum.info.infinite")
                : QuantumIncorporationRegistry.MAX_CHANNELS);
    }

    private static void position(EntityPlayer player, int x, int y, int z) {
        tell(player, "gtswn.chat.quantum.info.position", player.worldObj.provider.dimensionId, x, y, z);
    }

    private static void anchor(EntityPlayer player, int dimension, int x, int y, int z) {
        tell(player, "gtswn.chat.quantum.info.anchor", dimension, x, y, z);
    }

    private static void owner(EntityPlayer player, String name, String uuid) {
        tell(player, "gtswn.chat.quantum.info.owner", known(name), known(uuid));
    }

    private static Object known(String value) {
        return value == null || value.isEmpty() ? new ChatComponentTranslation("gtswn.chat.quantum.info.unknown")
            : value;
    }

    private static void tell(EntityPlayer player, String key, Object... values) {
        player.addChatComponentMessage(new ChatComponentTranslation(key, values));
    }
}
