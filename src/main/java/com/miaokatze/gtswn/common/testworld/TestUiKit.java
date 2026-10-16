package com.miaokatze.gtswn.common.testworld;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.misc.GlobalEnergyWorldSavedData;
import gregtech.common.misc.GlobalVariableStorage;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;
import gregtech.common.misc.spaceprojects.SpaceProjectWorldSavedData;

/** Explicit OP test kit. Originals live in persistent player NBT until restoration succeeds. */
public final class TestUiKit {

    private static final String BACKUP = "GTSWN_TestUiKitBackup_v1";
    private static final String OVERFLOW = "GTSWN_TestUiKitRecovery_v1";
    public static final String MARKER = "GTSWN_TestUiKit";
    private static final String READY_MARKER = "GTSWN_TestUiKitReady";
    private static final int PREPARATION_TIMEOUT_TICKS = 400;
    private static final int POWER_LIFETIME_TICKS = 36000;
    private static final long POWER_BUDGET_AE = 7200000L;
    private static boolean registered;

    private TestUiKit() {}

    public static void register() {
        if (!DebugTestGate.isEnabled()) return;
        if (registered) return;
        registered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(new TestUiKit());
    }

    private static NBTTagCompound persistent(EntityPlayer player) {
        NBTTagCompound data = player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10))
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    public static boolean hasBackup(EntityPlayer player) {
        return player != null && player.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
            .hasKey(BACKUP, 10);
    }

    /** Explicit-command arbitration only; never scans players in the normal tick path. */
    public static boolean inUse() {
        if (!DebugTestGate.isEnabled()) return false;
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null) return false;
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayer player = (EntityPlayer) entry;
            if (TestDimension.isTest(player.worldObj) && hasBackup(player)) return true;
        }
        return false;
    }

    public static boolean isKit(ItemStack stack) {
        return stack != null && stack.hasTagCompound() && stack.stackTagCompound.getBoolean(MARKER);
    }

    /** Server-authored readiness bit plus the actual synchronized anchor; safe for client-side waiting. */
    public static boolean isReady(EntityPlayer player) {
        if (player == null) return false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (!isKit(stack) || !stack.stackTagCompound.getBoolean(READY_MARKER)) return false;
        }
        if (!(player.inventory.mainInventory[0].getItem() instanceof PortableWirelessNetworkMonitor)
            || !(player.inventory.mainInventory[1].getItem() instanceof ItemDeviceInfoTerminal)) return false;
        ItemStack quantum = player.inventory.mainInventory[2];
        if (!(quantum.getItem() instanceof ItemNetworkQuantumTerminal) || !ItemNetworkQuantumTerminal.isBound(quantum))
            return false;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(quantum);
        return anchor != null && anchor[0] == player.dimension
            && anchor[1] == TestWorldFixtures.CONTROLLER_X
            && anchor[2] == TestWorldFixtures.Y
            && anchor[3] == TestWorldFixtures.CONTROLLER_Z;
    }

    public static void grant(EntityPlayerMP player) {
        DebugTestGate.requireEnabled();
        if (!TestDimension.isTest(player.worldObj) || !player.canCommandSenderUseCommand(4, "gtswn"))
            throw new IllegalStateException("UI kit requires OP4 in the GTSWN test dimension");
        if (TestWorldManager.busy())
            throw new IllegalStateException("Stop the active server test operation before preparing UI fixtures");
        if (hasBackup(player)) {
            player.addChatMessage(
                new ChatComponentText(
                    isReady(player) ? "[GTSWN-UI] KIT_READY (validated existing bound kit; backup retained)"
                        : "[GTSWN-UI] KIT_PREPARING (existing backup retained; awaiting actual quantum binding)"));
            return;
        }
        if (inUse()) throw new IllegalStateException("Another test operator is using the shared UI fixtures");
        if (persistent(player).hasKey(OVERFLOW, 9))
            throw new IllegalStateException("Restore pending UI kit recovery items before granting another kit");
        if (player.inventory.getItemStack() != null)
            throw new IllegalStateException("Clear the real cursor stack before granting a temporary UI kit");
        player.closeScreen();
        NBTTagCompound backup = new NBTTagCompound();
        backup.setInteger("Selected", player.inventory.currentItem);
        NBTTagList originals = new NBTTagList();
        for (int slot = 0; slot < 4; slot++) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("Slot", slot);
            ItemStack stack = player.inventory.mainInventory[slot];
            if (stack != null) entry.setTag("Stack", stack.writeToNBT(new NBTTagCompound()));
            originals.appendTag(entry);
        }
        backup.setTag("Originals", originals);
        persistent(player).setTag(BACKUP, backup);
        // Persist the backup before touching a single inventory slot. A pre-grant restart is a no-op restore.
        save(player);
        ItemStack portable = GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        ItemStack device = GTSWNItemList.Device_Info_Terminal.get(1);
        ItemStack quantum = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        ItemStack[] kit = { portable, device, quantum };
        for (ItemStack stack : kit) {
            if (stack == null) throw new IllegalStateException("UI kit item is not registered");
            if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
            stack.stackTagCompound.setBoolean(MARKER, true);
        }
        portable.stackTagCompound.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        portable.stackTagCompound.setString(
            PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            player.getUniqueID()
                .toString());
        portable.stackTagCompound.setString("OwnerName", player.getCommandSenderName());
        MonitorBindings.ensureId(portable);
        for (int slot = 0; slot < kit.length; slot++) player.inventory.mainInventory[slot] = kit[slot];
        player.inventory.mainInventory[3] = null;
        backup.setBoolean("Granted", true);
        device.setStackDisplayName("GTSWN UI Device 长名称 Layout 01234567890123456789");
        quantum.setStackDisplayName("GTSWN UI Quantum 长名称 Layout 01234567890123456789");
        ItemDeviceInfoTerminal.getOrCreateTerminalId(device);
        UUID scopeOwner = persistNewScope(player, device, backup);
        prepareFixtures(player, portable, device, scopeOwner);
        synchronize(player);
        save(player);
        player.addChatMessage(
            new ChatComponentText(
                "[GTSWN-UI] KIT_PREPARING slots=0:portable,1:device,2:quantum; originals persisted; waiting for naturally powered AE controller and actual binding"));
    }

    public static void restore(EntityPlayerMP player) {
        if (!DebugTestGate.isEnabled()) return;
        NBTTagCompound persistent = persistent(player);
        NBTTagList recovery = persistent.getTagList(OVERFLOW, 10);
        if (hasBackup(player)) {
            // Scope metadata was saved before fixture/binding creation. Recover only these exact new keys.
            cleanupScope(player, persistent.getCompoundTag(BACKUP));
            ItemStack cursor = player.inventory.getItemStack();
            if (cursor != null && !isKit(cursor)) recovery.appendTag(cursor.writeToNBT(new NBTTagCompound()));
            player.inventory.setItemStack(null);
            player.closeScreen();
            NBTTagCompound backup = persistent.getCompoundTag(BACKUP);
            if (backup.getBoolean("Granted")) {
                // Remove marked temporary tools wherever the player moved them; never drop displaced items.
                for (int slot = 0; slot < player.inventory.mainInventory.length; slot++)
                    if (isKit(player.inventory.mainInventory[slot])) player.inventory.mainInventory[slot] = null;
                for (int slot = 0; slot < player.inventory.armorInventory.length; slot++)
                    if (isKit(player.inventory.armorInventory[slot])) player.inventory.armorInventory[slot] = null;
                if (isKit(player.inventory.getItemStack())) player.inventory.setItemStack(null);
                NBTTagList originals = backup.getTagList("Originals", 10);
                for (int index = 0; index < originals.tagCount(); index++) {
                    NBTTagCompound entry = originals.getCompoundTagAt(index);
                    int slot = entry.getInteger("Slot");
                    if (slot < 0 || slot >= 4) throw new IllegalStateException("Invalid UI kit backup slot");
                    ItemStack displaced = player.inventory.mainInventory[slot];
                    if (displaced != null) recovery.appendTag(displaced.writeToNBT(new NBTTagCompound()));
                    player.inventory.mainInventory[slot] = entry.hasKey("Stack", 10)
                        ? ItemStack.loadItemStackFromNBT(entry.getCompoundTag("Stack"))
                        : null;
                }
                player.inventory.currentItem = Math.max(0, Math.min(8, backup.getInteger("Selected")));
            }
            persistent.removeTag(BACKUP);
        }
        NBTTagList remaining = new NBTTagList();
        for (int index = 0; index < recovery.tagCount(); index++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(recovery.getCompoundTagAt(index));
            if (stack != null && !player.inventory.addItemStackToInventory(stack))
                remaining.appendTag(stack.writeToNBT(new NBTTagCompound()));
        }
        if (remaining.tagCount() == 0) persistent.removeTag(OVERFLOW);
        else persistent.setTag(OVERFLOW, remaining);
        reconcileActualTerminals(player);
        synchronize(player);
        save(player);
        player.addChatMessage(
            new ChatComponentText(
                "[GTSWN-UI] KIT_RESTORED recovery=" + remaining.tagCount()
                    + (remaining.tagCount() == 0 ? "" : " (make inventory space; run /gtswn test kit restore)")));
    }

    private static UUID persistNewScope(EntityPlayerMP player, ItemStack device, NBTTagCompound backup) {
        UUID owner = UUID
            .nameUUIDFromBytes(("GTSWN explicit UI kit owner:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        UUID terminal = ItemDeviceInfoTerminal.readTerminalId(device);
        World global = DimensionManager.getWorld(0);
        if (global == null || terminal == null)
            throw new IllegalStateException("UI scope requires overworld and a new terminal identity");
        // Absence is checked without getUserEU/getOrCreate: those production readers may create a GT team/key.
        if (SpaceProjectManager.spaceTeams.containsKey(owner) || GlobalVariableStorage.GlobalEnergy.containsKey(owner)
            || NetworkInfoDataStore.get(global)
                .getIfPresent(owner.toString()) != null
            || DeviceTerminalDataStore.get(player.worldObj)
                .getTerminal(terminal) != null)
            throw new IllegalStateException("New UI scope identity has preexisting data; refusing to overwrite it");
        backup.setInteger("OriginDim", player.dimension);
        backup.setString("ScopeOwner", owner.toString());
        backup.setString("UiTerminal", terminal.toString());
        backup.setBoolean("UiTerminalWasAbsent", true);
        String key = DeviceRegistryData
            .makeKey(player.dimension, TestWorldFixtures.DEVICE_X, 65, TestWorldFixtures.DEVICE_Z);
        DeviceRegistryData.DeviceEntry existing = DeviceRegistryData.get(player.worldObj)
            .getEntry(key);
        if (existing != null) {
            NBTTagCompound before = new NBTTagCompound();
            before.setString("Owner", existing.owner.toString());
            before.setString("Name", existing.localName == null ? "" : existing.localName);
            before.setBoolean("NullName", existing.localName == null);
            before.setInteger("Meta", existing.metaTileId);
            backup.setTag("DeviceEntryBefore", before);
        }
        backup.setBoolean("UiScopePrepared", true);
        save(player);
        return owner;
    }

    /** Narrow authority for removal of this grant's newly-created terminal, also during login/dimension recovery. */
    public static boolean ownsTerminal(EntityPlayer player, World origin, UUID terminal) {
        if (player == null || terminal == null || !hasBackup(player) || !TestDimension.isTest(origin)) return false;
        NBTTagCompound backup = persistent(player).getCompoundTag(BACKUP);
        return backup.getBoolean("UiScopePrepared") && backup.getBoolean("UiTerminalWasAbsent")
            && backup.getInteger("OriginDim") == origin.provider.dimensionId
            && terminal.toString()
                .equals(backup.getString("UiTerminal"));
    }

    private static void cleanupScope(EntityPlayerMP player, NBTTagCompound backup) {
        if (!backup.getBoolean("UiScopePrepared")) return; // Older staged backups never authorize guessed cleanup keys.
        int dimension = backup.getInteger("OriginDim");
        if (!DimensionManager.isDimensionRegistered(dimension))
            throw new IllegalStateException("UI scope origin dimension unavailable; backup retained");
        if (DimensionManager.getWorld(dimension) == null) DimensionManager.initDimension(dimension);
        WorldServer origin = DimensionManager.getWorld(dimension);
        if (!TestDimension.isTest(origin))
            throw new IllegalStateException("UI scope origin is not the isolated test dimension");
        UUID owner = UUID.fromString(backup.getString("ScopeOwner"));
        UUID terminal = UUID.fromString(backup.getString("UiTerminal"));
        UUID leader = SpaceProjectManager.spaceTeams.get(owner);
        if (leader != null && !owner.equals(leader)) throw new IllegalStateException(
            "UI scope owner joined a foreign team; refusing foreign network access; backup retained");
        World global = DimensionManager.getWorld(0);
        if (global == null) throw new IllegalStateException("UI cleanup requires overworld; backup retained");
        // Stop all four producers first. Only tiles still owned by this exact new scope may be removed.
        for (int x = 2; x <= 4; x++) {
            origin.theChunkProviderServer.loadChunk(x >> 4, 1 >> 4);
            TileEntity tile = origin.getTileEntity(x, 65, 1);
            if (tile instanceof TileEntityNetworkInfoPanel
                && owner.equals(((TileEntityNetworkInfoPanel) tile).getOwnerUUID()))
                if (!origin.setBlockToAir(x, 65, 1))
                    throw new IllegalStateException("Could not detach owned UI panel; backup retained");
        }
        origin.theChunkProviderServer.loadChunk(2 >> 4, -1 >> 4);
        TileEntity monitor = origin.getTileEntity(2, 65, -1);
        if (monitor instanceof BaseMetaTileEntity && owner.equals(((BaseMetaTileEntity) monitor).getOwnerUuid()))
            if (!origin.setBlockToAir(2, 65, -1))
                throw new IllegalStateException("Could not detach owned UI monitor; backup retained");
        DeviceTerminalDataStore.get(origin)
            .removeUiKitTerminal(player, origin, terminal);
        String deviceKey = DeviceRegistryData
            .makeKey(dimension, TestWorldFixtures.DEVICE_X, 65, TestWorldFixtures.DEVICE_Z);
        DeviceRegistryData registry = DeviceRegistryData.get(origin);
        if (backup.hasKey("DeviceEntryBefore", 10)) {
            NBTTagCompound before = backup.getCompoundTag("DeviceEntryBefore");
            registry.register(
                deviceKey,
                UUID.fromString(before.getString("Owner")),
                before.getBoolean("NullName") ? null : before.getString("Name"),
                before.getInteger("Meta"));
        } else registry.unregister(deviceKey);
        NetworkInfoDataStore.get(global)
            .remove(owner.toString());
        GlobalVariableStorage.GlobalEnergy.remove(owner);
        SpaceProjectManager.spaceTeams.remove(owner);
        if (GlobalEnergyWorldSavedData.INSTANCE != null) GlobalEnergyWorldSavedData.INSTANCE.markDirty();
        if (SpaceProjectWorldSavedData.INSTANCE != null) SpaceProjectWorldSavedData.INSTANCE.markDirty();
        if (DeviceTerminalDataStore.get(origin)
            .getTerminal(terminal) != null
            || NetworkInfoDataStore.get(global)
                .getIfPresent(owner.toString()) != null
            || GlobalVariableStorage.GlobalEnergy.containsKey(owner)
            || SpaceProjectManager.spaceTeams.containsKey(owner))
            throw new IllegalStateException("UI scope cleanup postcondition failed; backup retained");
        GTSimpleWirelessNetwork.LOG.info(
            "[GTSWN-UI] SCOPE_CLEANED terminal={} owner={} origin={} exactKeysAbsent=true",
            terminal,
            owner,
            dimension);
    }

    private static void reconcileActualTerminals(EntityPlayerMP player) {
        Set<UUID> actual = new HashSet<>();
        for (ItemStack stack : player.inventory.mainInventory) addActualTerminal(actual, stack);
        for (ItemStack stack : player.inventory.armorInventory) addActualTerminal(actual, stack);
        addActualTerminal(actual, player.inventory.getItemStack());
        DeviceTerminalDataStore.synchronizeHeldTerminals(player.getUniqueID(), actual);
    }

    private static void addActualTerminal(Set<UUID> actual, ItemStack stack) {
        if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal) {
            UUID id = ItemDeviceInfoTerminal.readTerminalId(stack);
            if (id != null) actual.add(id);
        }
    }

    private static void prepareFixtures(EntityPlayerMP player, ItemStack portable, ItemStack device, UUID scopeOwner) {
        WorldServer world = (WorldServer) player.worldObj;
        // Real AE placement establishes the operator's native grid ownership; no authorization is bypassed.
        TileEntity controller = TestWorldFixtures.aeOwnedBlock(
            world,
            TestWorldFixtures.CONTROLLER_X,
            TestWorldFixtures.Y,
            TestWorldFixtures.CONTROLLER_Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            player);
        ((IGridProxyable) controller).getProxy()
            .setOwner(player);
        NBTTagList bindings = new NBTTagList();
        for (int tab = 0; tab < 3; tab++) {
            int x = 2 + tab;
            TestWorldFixtures.place(world, x, 65, 1, BlockRegistrar.networkInfoPanel, 3);
            TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) world.getTileEntity(x, 65, 1);
            panel.bindOwner(scopeOwner, "GTSWN isolated UI display");
            panel.setCurrentTab(tab);
            if (tab == 1) panel.setChartItem(new ItemStack(Items.iron_ingot));
            if (tab == 2) {
                panel.toggleItemMonitor(new ItemStack(Items.iron_ingot));
                panel.toggleItemMonitor(new ItemStack(Items.gold_ingot));
                panel.toggleItemMonitor(new ItemStack(Items.diamond));
            }
            panel.markDirty();
            world.markBlockForUpdate(x, 65, 1);
            // Credentials are authored by the OP-only test fixture; all displayed samples still use real panel polling.
            NBTTagCompound binding = new NBTTagCompound();
            binding.setInteger("dim", world.provider.dimensionId);
            binding.setInteger("x", x);
            binding.setInteger("y", 65);
            binding.setInteger("z", 1);
            binding.setString(
                "id",
                panel.getPortableMonitorId()
                    .toString());
            bindings.appendTag(binding);
        }
        portable.stackTagCompound.setTag("MonitorPanels", bindings);
        portable.stackTagCompound.setLong("MonitorRevision", 1);
        portable.stackTagCompound.setString("GTSWN_TestUiSource", "CURRENT_OPERATOR_READ_ONLY");
        TestWorldFixtures.machine(world, 2, -1, GTSWNItemList.Wireless_Energy_Monitor.get(1), scopeOwner);
        boolean sneaking = player.isSneaking();
        player.setSneaking(false);
        try {
            ((ItemDeviceInfoTerminal) device.getItem()).onItemUseFirst(
                device,
                player,
                world,
                TestWorldFixtures.DEVICE_X,
                65,
                TestWorldFixtures.DEVICE_Z,
                1,
                .5f,
                .5f,
                .5f);
            DeviceTerminalDataStore.get(world)
                .renameBinding(
                    ItemDeviceInfoTerminal.readTerminalId(device),
                    DeviceRegistryData.makeKey(
                        world.provider.dimensionId,
                        TestWorldFixtures.DEVICE_X,
                        65,
                        TestWorldFixtures.DEVICE_Z),
                    "GTSWN UI 长名称设备 Layout Stress 0123456789",
                    15,
                    true);
        } finally {
            player.setSneaking(sneaking);
        }
    }

    @SubscribeEvent
    public void prepareQuantum(TickEvent.PlayerTickEvent event) {
        if (!DebugTestGate.isEnabled()) return;
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (!TestDimension.isTest(player.worldObj) || !hasBackup(player)) return;
        NBTTagCompound backup = persistent(player).getCompoundTag(BACKUP);
        if (backup.getBoolean("UiFailed")) return;
        if (!backup.getBoolean("Granted") || !backup.getBoolean("UiScopePrepared")) {
            failKit(
                player,
                backup,
                "KIT_NOT_READY: grant/scope preparation was incomplete; no fixture power was supplied");
            return;
        }
        int tick = backup.getInteger("UiTicks") + 1;
        backup.setInteger("UiTicks", tick);
        long requested = backup.getLong("UiPowerRequestedAE");
        if (tick > POWER_LIFETIME_TICKS || requested >= POWER_BUDGET_AE) {
            failKit(player, backup, "KIT_EXPIRED: natural tick/AE fixture power budget exhausted");
            return;
        }
        TileEntity tile = player.worldObj
            .getTileEntity(TestWorldFixtures.CONTROLLER_X, 65, TestWorldFixtures.CONTROLLER_Z);
        String pending = "actual controller is missing";
        if (tile instanceof TileController && tile instanceof IGridProxyable) {
            try {
                // AE requires >30 continuously powered ticks. A 200 AE pulse every 20t cannot sustain
                // GTNH UsageMultiplier=10; follow the proven native scenario's 200 AE/natural tick path.
                ((IGridProxyable) tile).getProxy()
                    .getEnergy()
                    .injectPower(200, Actionable.MODULATE);
                backup.setLong("UiPowerRequestedAE", requested + 200);
                if (backup.getBoolean("UiReady")) return;
                pending = "actual controller has not reached native powered/online state";
                ItemStack quantum = player.inventory.mainInventory[2];
                if (isKit(quantum) && quantum.getItem() instanceof ItemNetworkQuantumTerminal
                    && ItemNetworkQuantumTerminal.isControllerActive((TileController) tile)
                    && tick % 20 == 0) {
                    boolean sneaking = player.isSneaking();
                    boolean handled;
                    player.setSneaking(false);
                    try {
                        handled = ((ItemNetworkQuantumTerminal) quantum.getItem()).onItemUseFirst(
                            quantum,
                            player,
                            player.worldObj,
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord,
                            1,
                            .5f,
                            .5f,
                            .5f);
                    } finally {
                        player.setSneaking(sneaking);
                    }
                    int[] anchor = ItemNetworkQuantumTerminal.getAnchor(quantum);
                    boolean matches = anchor != null && anchor[0] == player.dimension
                        && anchor[1] == tile.xCoord
                        && anchor[2] == tile.yCoord
                        && anchor[3] == tile.zCoord;
                    if (handled && ItemNetworkQuantumTerminal.isBound(quantum) && matches) {
                        for (int slot = 0; slot < 3; slot++) {
                            ItemStack stack = player.inventory.mainInventory[slot];
                            if (!isKit(stack)) throw new IllegalStateException(
                                "Issued UI kit stack moved before preparation completed");
                            stack.stackTagCompound.setBoolean(READY_MARKER, true);
                        }
                        backup.setBoolean("UiReady", true);
                        synchronize(player);
                        save(player);
                        player.addChatMessage(
                            new ChatComponentText(
                                "[GTSWN-UI] KIT_READY actual quantum binding verified; native power budget=36000 ticks/7200000 AE requested maximum"));
                        return;
                    }
                    pending = "production bind did not establish the exact requested anchor (handled=" + handled + ")";
                }
            } catch (GridAccessException ignored) {
                pending = "native AE grid is not ready";
            } catch (RuntimeException failure) {
                failKit(
                    player,
                    backup,
                    "KIT_NOT_READY: " + failure.getClass()
                        .getSimpleName() + ": " + failure.getMessage());
                return;
            }
        }
        if (!backup.getBoolean("UiReady") && tick >= PREPARATION_TIMEOUT_TICKS)
            failKit(player, backup, "KIT_NOT_READY: preparation timed out; " + pending);
    }

    private static void failKit(EntityPlayerMP player, NBTTagCompound backup, String reason) {
        backup.setBoolean("UiFailed", true);
        for (ItemStack stack : player.inventory.mainInventory)
            if (isKit(stack)) stack.stackTagCompound.setBoolean(READY_MARKER, false);
        GTSimpleWirelessNetwork.LOG.warn("[GTSWN-UI] {}", reason);
        player.addChatMessage(new ChatComponentText("[GTSWN-UI] " + reason + "; restoring temporary kit"));
        synchronize(player);
        try {
            restore(player);
        } catch (RuntimeException failure) {
            save(player);
            GTSimpleWirelessNetwork.LOG
                .error("[GTSWN-UI] automatic kit restore failed; durable backup retained", failure);
            player.addChatMessage(
                new ChatComponentText("[GTSWN-UI] KIT_RESTORE_FAILED; backup retained; use /gtswn test kit restore"));
        }
    }

    private static void synchronize(EntityPlayerMP player) {
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        player.sendContainerToPlayer(player.inventoryContainer);
    }

    private static void save(EntityPlayerMP player) {
        player.worldObj.getSaveHandler()
            .getSaveHandler()
            .writePlayerData(player);
    }

    private static void recover(EntityPlayer player) {
        if (!DebugTestGate.isEnabled()) return;
        if (!(player instanceof EntityPlayerMP) || player.worldObj.isRemote) return;
        if (hasBackup(player) || persistent(player).hasKey(OVERFLOW, 9)) {
            GTSimpleWirelessNetwork.LOG.info("[GTSWN-UI] recovering persistent kit for {}", player.getUniqueID());
            try {
                restore((EntityPlayerMP) player);
            } catch (RuntimeException failure) {
                GTSimpleWirelessNetwork.LOG.error("[GTSWN-UI] scope recovery failed; durable backup retained", failure);
                player.addChatMessage(
                    new ChatComponentText(
                        "[GTSWN-UI] KIT_RESTORE_FAILED; durable backup retained: " + failure.getMessage()));
            }
        }
    }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent event) {
        recover(event.player);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        recover(event.player);
    }

    @SubscribeEvent
    public void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        recover(event.player);
    }

    @SubscribeEvent
    public void respawn(PlayerEvent.PlayerRespawnEvent event) {
        recover(event.player);
    }
}
