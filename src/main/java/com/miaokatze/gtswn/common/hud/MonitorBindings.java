package com.miaokatze.gtswn.common.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

/** Server-authored remote viewing credentials; no client supplied coordinates are trusted. */
public final class MonitorBindings {

    public static final int MAX_PANELS = 16;
    private static final String ID = "MonitorId";
    private static final String PANELS = "MonitorPanels";

    private MonitorBindings() {}

    public static UUID id(ItemStack stack) {
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack)) return null;
        try {
            return UUID.fromString(stack.stackTagCompound.getString(ID));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static UUID ensureId(ItemStack stack) {
        UUID id = id(stack);
        if (id == null && PortableWirelessNetworkMonitor.isMonitorBound(stack)) {
            id = UUID.randomUUID();
            stack.stackTagCompound.setString(ID, id.toString());
        }
        return id;
    }

    public static List<Binding> read(ItemStack stack) {
        List<Binding> result = new ArrayList<>();
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack)) return result;
        NBTTagList list = stack.stackTagCompound.getTagList(PANELS, 10);
        for (int i = 0; i < Math.min(MAX_PANELS, list.tagCount()); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            try {
                result.add(
                    new Binding(
                        tag.getInteger("dim"),
                        tag.getInteger("x"),
                        tag.getInteger("y"),
                        tag.getInteger("z"),
                        UUID.fromString(tag.getString("id"))));
            } catch (IllegalArgumentException ignored) {
                // Old or malformed entries never resolve a screen.
            }
        }
        return result;
    }

    public static void remove(ItemStack stack, int index) {
        List<Binding> list = read(stack);
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        write(stack, list);
    }

    public static void clear(ItemStack stack) {
        if (stack.stackTagCompound != null) {
            stack.stackTagCompound.removeTag(PANELS);
            stack.stackTagCompound.removeTag(ID);
        }
    }

    public static boolean bind(ItemStack stack, EntityPlayer player, TileEntityNetworkInfoPanel panel) {
        if (player.worldObj.isRemote) return true;
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack) || !player.getUniqueID()
            .toString()
            .equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack))) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.monitor.bind.owner"));
            return true;
        }
        if (panel.getWorldObj() != player.worldObj
            || player.getDistanceSq(panel.xCoord + .5, panel.yCoord + .5, panel.zCoord + .5) > 64) return true;
        ensureId(stack);
        UUID panelId = panel.getPortableMonitorId();
        List<Binding> list = read(stack);
        for (Binding binding : list) {
            if (binding.panelId.equals(panelId)) {
                player.addChatMessage(new ChatComponentTranslation("gtswn.monitor.bind.exists"));
                return true;
            }
        }
        if (list.size() >= MAX_PANELS) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.monitor.bind.full", MAX_PANELS));
            return true;
        }
        list.add(
            new Binding(panel.getWorldObj().provider.dimensionId, panel.xCoord, panel.yCoord, panel.zCoord, panelId));
        write(stack, list);
        player.inventory.markDirty();
        player.addChatMessage(new ChatComponentTranslation("gtswn.monitor.bind.added", list.size()));
        return true;
    }

    private static void write(ItemStack stack, List<Binding> bindings) {
        NBTTagList list = new NBTTagList();
        for (Binding binding : bindings) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("dim", binding.dimension);
            tag.setInteger("x", binding.x);
            tag.setInteger("y", binding.y);
            tag.setInteger("z", binding.z);
            tag.setString("id", binding.panelId.toString());
            list.appendTag(tag);
        }
        stack.stackTagCompound.setTag(PANELS, list);
    }

    public static final class Binding {

        public final int dimension, x, y, z;
        public final UUID panelId;

        public Binding(int dimension, int x, int y, int z, UUID panelId) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.panelId = panelId;
        }

        public String label() {
            return dimension + ": " + x + ", " + y + ", " + z;
        }
    }
}
