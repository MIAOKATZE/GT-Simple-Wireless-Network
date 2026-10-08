package com.miaokatze.gtswn.common.charging;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.util.InventoryHintReminder;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.recipe.MonitorBatteryRecipe;

import baubles.api.BaublesApi;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Optional;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.ItemCraftedEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.enums.GTValues;
import gregtech.common.misc.WirelessNetworkManager;
import ic2.api.item.IElectricItem;
import ic2.api.item.IElectricItemManager;
import ic2.api.item.ISpecialElectricItem;

/** Server authoritative charging; empty players only pay for a scan once per second. */
public final class MonitorChargingHandler {

    private final Map<EntityPlayer, Boolean> active = new WeakHashMap<>();

    @SubscribeEvent
    public void onCraft(ItemCraftedEvent event) {
        if (event.player.worldObj.isRemote || !(event.craftMatrix instanceof InventoryCrafting)) return;
        ItemStack[] inputs = MonitorBatteryRecipe.inputs((InventoryCrafting) event.craftMatrix);
        if (inputs == null || !ItemStack.areItemStacksEqual(
            new MonitorBatteryRecipe().getCraftingResult((InventoryCrafting) event.craftMatrix),
            event.crafting)) return;
        ItemStack old = MonitorBattery.remove(inputs[0]);
        if (old == null) return;
        // Consume this remainder before vanilla's container-item pass; automation still uses that pass.
        inputs[0].getTagCompound()
            .removeTag(MonitorBattery.NBT_BATTERY);
        if (!event.player.inventory.addItemStackToInventory(old))
            event.player.dropPlayerItemWithRandomChoice(old, false);
    }

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent event) {
        EntityPlayer player = event.player;
        if (event.phase != TickEvent.Phase.END || player.worldObj.isRemote) return;
        if (!Boolean.TRUE.equals(active.get(player)) && player.ticksExisted % 20 != 0) return;
        List<ItemStack> targets = new ArrayList<>();
        List<ItemStack> monitors = new ArrayList<>();
        collect(player.inventory, targets, monitors, true);
        if (Loader.isModLoaded("Baubles")) collectBaubles(player, targets, monitors);
        active.put(player, !monitors.isEmpty());
        if (monitors.isEmpty()) return;
        if (player.ticksExisted % 20 == 0) remind(player, monitors);
        Set<ItemStack> charged = Collections.newSetFromMap(new IdentityHashMap<ItemStack, Boolean>());
        // Rotate priority when several terminals share finite caches.
        int first = Math.floorMod(player.ticksExisted, monitors.size());
        for (int i = 0; i < monitors.size(); i++) {
            ItemStack monitor = monitors.get((first + i) % monitors.size());
            if (!MonitorBattery.hasBattery(monitor)) continue;
            NBTTagCompound data = MonitorBattery.data(monitor);
            if (player.ticksExisted % 200 == 0) refill(monitor, data);
            double cache = data.getDouble("Charge");
            boolean charging = false;
            if (cache > 0) {
                int tier = data.getInteger("Tier");
                if (tier < 0 || tier >= GTValues.V.length) continue;
                int targetStart = targets.isEmpty() ? 0 : Math.floorMod(player.ticksExisted, targets.size());
                for (int j = 0; j < targets.size() && cache > 0; j++) {
                    ItemStack target = targets.get((targetStart + j) % targets.size());
                    if (charged.contains(target)) continue;
                    IElectricItemManager manager = MonitorBattery.manager(target);
                    if (manager == null) continue;
                    double offered = Math.min(cache, GTValues.V[tier]);
                    double accepted = manager.charge(target, offered, tier, false, true);
                    if (accepted <= 0 || !Double.isFinite(accepted)) continue;
                    double received = manager.charge(target, Math.min(offered, accepted), tier, false, false);
                    if (received > 0) {
                        cache = Math.max(0, cache - received);
                        charged.add(target);
                        charging = true;
                    }
                }
                data.setDouble("Charge", cache);
            }
            updateStatus(data, charging);
        }
    }

    private static void collect(IInventory inventory, List<ItemStack> targets, List<ItemStack> monitors,
        boolean includeMonitors) {
        if (inventory == null) return;
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack == null) continue;
            if (stack.getItem() instanceof PortableWirelessNetworkMonitor) {
                // InventoryPlayer's last four slots are armour: terminals draw only in bag/baubles.
                if (includeMonitors && (!(inventory instanceof net.minecraft.entity.player.InventoryPlayer) || i < 36))
                    monitors.add(stack);
            } else if (stack.getItem() instanceof IElectricItem || stack.getItem() instanceof ISpecialElectricItem)
                targets.add(stack);
        }
    }

    @Optional.Method(modid = "Baubles")
    private static void collectBaubles(EntityPlayer player, List<ItemStack> targets, List<ItemStack> monitors) {
        collect(BaublesApi.getBaubles(player), targets, monitors, true);
    }

    private static void refill(ItemStack monitor, NBTTagCompound data) {
        double missing = data.getDouble("Capacity") - data.getDouble("Charge");
        if (missing <= 0 || !Double.isFinite(missing) || !PortableWirelessNetworkMonitor.isMonitorBound(monitor))
            return;
        try {
            UUID owner = UUID.fromString(PortableWirelessNetworkMonitor.getOwnerUUID(monitor));
            BigInteger deduction = ChargeMath.deduction(missing, Config.downlinkLossEU);
            if (WirelessNetworkManager.addEUToGlobalEnergyMap(owner, deduction.negate()))
                data.setDouble("Charge", data.getDouble("Capacity"));
        } catch (IllegalArgumentException ignored) {
            // Invalid saved owner UUID cannot withdraw energy.
        }
    }

    private static void updateStatus(NBTTagCompound data, boolean charging) {
        if (data.getDouble("Charge") <= 0) {
            // Interrupted work stays pending: lack of energy must never count as a quiet completion.
            data.setInteger("Status", charging || data.getInteger("Status") == 2 ? 2 : 0);
            data.setInteger("Quiet", 0);
            return;
        }
        if (charging) {
            data.setInteger("Status", 2);
            data.setInteger("Quiet", 0);
        } else {
            int status = data.getInteger("Status");
            if (status == 2 || status == 3) {
                int quiet = data.getInteger("Quiet") + 1;
                data.setInteger("Quiet", quiet);
                data.setInteger("Status", ChargeMath.quietStatus(quiet));
            } else data.setInteger("Status", 1);
        }
    }

    private static void remind(EntityPlayer player, List<ItemStack> monitors) {
        // Any installed battery demonstrates that the player already knows this feature.
        for (ItemStack monitor : monitors) {
            if (MonitorBattery.hasBattery(monitor)) return;
        }
        InventoryHintReminder
            .remind(player, "GTSWNChargeHintCount", "GTSWNMonitorHeldTicks", "gtswn.chat.monitor.charging_hint");
    }
}
