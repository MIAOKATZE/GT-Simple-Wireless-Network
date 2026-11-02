package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.miaokatze.gtswn.common.device.DeviceLinkSummary;
import com.miaokatze.gtswn.common.device.DeviceLinkTargets;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.device.TerminalLinkSettings;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap.LinkResult;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.eventhandler.Event.Result;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** One bounded queue for whole-device intent and short placement initialization, drained on the server thread. */
public final class DeviceLinkActionQueue {

    private static final int MAX_PENDING = 1024, MAX_PLAYER_PENDING = 16, MAX_BATCH_TARGETS = 4096;
    private static final Map<UUID, PacketDeviceLinkAction> REQUESTS = new LinkedHashMap<>();
    private static final Map<UUID, Settings> SETTINGS = new LinkedHashMap<>();
    private static final Map<UUID, Batch> BATCHES = new LinkedHashMap<>();
    private static final List<Placement> PLACEMENTS = new ArrayList<>();
    private static final Map<UUID, LinkedHashMap<Long, PacketSyncDeviceLinkResult>> RECEIPTS = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    private DeviceLinkActionQueue() {}

    public static synchronized void enqueue(EntityPlayerMP player, PacketDeviceLinkAction request) {
        PacketDeviceLinkAction existing = REQUESTS.get(player.getUniqueID());
        if (existing != null && existing.requestId == request.requestId) {
            send(player, existing, true, 0, 0, 0);
            return;
        }
        if (REQUESTS.size() < MAX_PENDING && !REQUESTS.containsKey(player.getUniqueID())) {
            requestSender.put(player.getUniqueID(), player);
            REQUESTS.put(player.getUniqueID(), request);
        } else send(player, request, false, 0, 0, 1);
    }

    private static final Map<UUID, EntityPlayerMP> requestSender = new HashMap<>();

    public static synchronized void enqueueSettings(EntityPlayerMP player, boolean update, boolean enabled) {
        if (SETTINGS.size() < MAX_PENDING || SETTINGS.containsKey(player.getUniqueID()))
            SETTINGS.put(player.getUniqueID(), new Settings(player, update, enabled));
    }

    /** PlaceEvent only retains this exact just-placed tile for at most forty ticks; no structure polling. */
    public static void placed(EntityPlayerMP player, World world, TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity) || !TerminalLinkSettings.autoEnabled(player)
            || TerminalLinkSettings.linkTerminal(player) == null
            || PLACEMENTS.size() >= MAX_PENDING) return;
        int pending = 0;
        for (Placement placement : PLACEMENTS) if (placement.player == player) pending++;
        if (pending < MAX_PLAYER_PENDING) PLACEMENTS.add(new Placement(player, world, tile));
    }

    /** Called by the already registered DeviceEventHandler at ServerTick END. */
    public static void tick() {
        for (int budget = 0; budget < 64; budget++) {
            Settings settings;
            EntityPlayerMP player;
            PacketDeviceLinkAction request;
            synchronized (DeviceLinkActionQueue.class) {
                Iterator<Settings> settingsIterator = SETTINGS.values()
                    .iterator();
                settings = settingsIterator.hasNext() ? settingsIterator.next() : null;
                if (settings != null) settingsIterator.remove();
                Iterator<Map.Entry<UUID, PacketDeviceLinkAction>> iterator = REQUESTS.entrySet()
                    .iterator();
                if (iterator.hasNext()) {
                    Map.Entry<UUID, PacketDeviceLinkAction> entry = iterator.next();
                    player = requestSender.remove(entry.getKey());
                    request = entry.getValue();
                    iterator.remove();
                } else {
                    player = null;
                    request = null;
                }
            }
            if (settings == null && request == null) break;
            if (settings != null && online(settings.player)
                && (TerminalLinkSettings.holdsInfoTerminal(settings.player)
                    || TerminalLinkSettings.linkTerminal(settings.player) != null)) {
                if (settings.update) TerminalLinkSettings.setAutoEnabled(settings.player, settings.enabled);
                syncSettings(settings.player);
            }
            if (request != null && online(player)) start(player, request);
        }
        int budget = 64;
        Iterator<Batch> batches = BATCHES.values()
            .iterator();
        while (batches.hasNext() && budget-- > 0) {
            Batch batch = batches.next();
            boolean done;
            try {
                done = step(batch);
            } catch (RuntimeException failure) {
                batch.failed += batch.targets.size() - batch.index;
                GTSimpleWirelessNetwork.LOG.error("[DeviceLinks] Batch failed", failure);
                done = true;
            }
            if (done) {
                batches.remove();
                complete(batch);
            }
        }
        Iterator<Placement> placements = PLACEMENTS.iterator();
        budget = 64;
        while (placements.hasNext() && budget-- > 0) {
            Placement placement = placements.next();
            long now = placement.world.getTotalWorldTime();
            if (!online(placement.player) || now < placement.created
                || now - placement.created > 40
                || !TerminalLinkSettings.autoEnabled(placement.player)) {
                placements.remove();
                continue;
            }
            if (now < placement.nextAt) continue;
            ItemStack link = TerminalLinkSettings.linkTerminal(placement.player);
            if (link == null || placement.player.worldObj != placement.world
                || !placement.world.blockExists(placement.tile.xCoord, placement.tile.yCoord, placement.tile.zCoord)
                || placement.world.getTileEntity(placement.tile.xCoord, placement.tile.yCoord, placement.tile.zCoord)
                    != placement.tile) {
                placements.remove();
                continue;
            }
            IGregTechTileEntity tile = (IGregTechTileEntity) placement.tile;
            if (tile.getMetaTileEntity() == null || tile.getOwnerUuid() == null) continue;
            DeviceLinkTargets.Target target = DeviceLinkTargets.placed(tile.getMetaTileEntity());
            if (target == null || !allowed(placement.player, target, link)) {
                placements.remove();
                continue;
            }
            LinkResult result = ((WirelessEnergyTap) link.getItem())
                .setLinkState(link, placement.player, placement.world, tile, target.side, target.dynamo, true);
            if (result != LinkResult.COOLDOWN) placements.remove();
            else placement.nextAt = now + 4;
        }
    }

    private static void start(EntityPlayerMP player, PacketDeviceLinkAction request) {
        LinkedHashMap<Long, PacketSyncDeviceLinkResult> history = RECEIPTS.get(player.getUniqueID());
        if (history != null && history.containsKey(request.requestId)) {
            GTSWNPacketHandler.NETWORK.sendTo(history.get(request.requestId), player);
            return;
        }
        Batch running = BATCHES.get(player.getUniqueID());
        if (running != null) {
            if (running.request.requestId == request.requestId) send(player, request, true, 0, 0, 0);
            else send(player, request, false, 0, 0, 1);
            return;
        }
        Long last = LAST_REQUEST.get(player.getUniqueID());
        if (last != null && request.requestId <= last) {
            send(player, request, false, 0, 0, 1);
            return;
        }
        LAST_REQUEST.put(player.getUniqueID(), request.requestId);
        IGregTechTileEntity controller = resolve(player, request);
        DeviceLinkTargets.Discovery discovery = controller == null ? null
            : DeviceLinkTargets.discover(controller.getMetaTileEntity());
        if (discovery == null || discovery.summary.state != DeviceLinkSummary.KNOWN
            || discovery.targets.size() > MAX_BATCH_TARGETS
            || BATCHES.size() >= 64) {
            send(player, request, false, 0, 0, 1);
            return;
        }
        Batch batch = new Batch(player, request, controller, discovery.targets);
        BATCHES.put(player.getUniqueID(), batch);
        send(player, request, true, 0, 0, 0);
    }

    private static boolean step(Batch batch) {
        EntityPlayerMP player = batch.player;
        IGregTechTileEntity controller = resolve(player, batch.request);
        if (controller != batch.controller || controller.getMetaTileEntity() != batch.controllerMachine) {
            batch.failed += batch.targets.size() - batch.index;
            return true;
        }
        if (++batch.age > batch.targets.size() * 8L + 80L) {
            batch.failed += batch.targets.size() - batch.index;
            return true;
        }
        long now = controller.getWorld()
            .getTotalWorldTime();
        if (now < batch.nextAt) return false;
        DeviceLinkTargets.Target target = batch.targets.get(batch.index);
        DeviceLinkTargets.Discovery current = DeviceLinkTargets.discover(controller.getMetaTileEntity());
        boolean member = false;
        for (DeviceLinkTargets.Target candidate : current.targets) {
            if (candidate.tile == target.tile && candidate.machine == target.machine
                && candidate.side == target.side
                && candidate.dynamo == target.dynamo) {
                member = true;
                break;
            }
        }
        ItemStack link = TerminalLinkSettings.linkTerminal(player);
        LinkResult result;
        if (!member || !target.current() || !allowed(player, target, link)) result = LinkResult.FAILED;
        else result = ((WirelessEnergyTap) link.getItem()).setLinkState(
            link,
            player,
            target.tile.getWorld(),
            target.tile,
            target.side,
            target.dynamo,
            batch.request.desiredState);
        if (result == LinkResult.COOLDOWN) {
            batch.nextAt = now + 4;
            return false;
        }
        if (result == LinkResult.APPLIED) batch.success++;
        else if (result == LinkResult.SKIPPED) batch.skipped++;
        else batch.failed++;
        if (result == LinkResult.APPLIED) batch.nextAt = now + 4;
        batch.index++;
        return batch.index == batch.targets.size();
    }

    /** Full source authorization and exact stored target identity are re-evaluated before every operation. */
    private static IGregTechTileEntity resolve(EntityPlayerMP player, PacketDeviceLinkAction request) {
        if (!online(player) || player.isDead
            || player.getHealth() <= 0
            || player.isPlayerSleeping()
            || DeviceTerminalActionQueue.findTerminalStack(player, request.terminalId) == null
            || TerminalLinkSettings.linkTerminal(player) == null) return null;
        MonitoringProfiles profiles = MonitoringProfiles.get(player.worldObj);
        if (!profiles.canRead(player.getUniqueID(), request.terminalId)
            || !profiles.canModify(player.getUniqueID(), request.terminalId)) return null;
        DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(player.worldObj)
            .getTerminal(request.terminalId);
        DeviceTerminalDataStore.MachineRecord record = data == null || !data.boundKeys.contains(request.key) ? null
            : data.records.get(request.key);
        if (record == null || record.metaTileId != request.meta
            || !request.key.equals(DeviceRegistryData.makeKey(record.dim, record.x, record.y, record.z))) return null;
        World world = DimensionManager.getWorld(record.dim);
        if (world == null || !world.blockExists(record.x, record.y, record.z)) return null;
        TileEntity tile = world.getTileEntity(record.x, record.y, record.z);
        if (!(tile instanceof IGregTechTileEntity) || tile.isInvalid()) return null;
        IGregTechTileEntity machine = (IGregTechTileEntity) tile;
        return machine.getMetaTileID() == record.metaTileId && machine.getMetaTileEntity() != null ? machine : null;
    }

    private static boolean allowed(EntityPlayerMP player, DeviceLinkTargets.Target target, ItemStack link) {
        if (link == null || !target.current() || target.tile.getOwnerUuid() == null) return false;
        IGregTechTileEntity tile = target.tile;
        World world = tile.getWorld();
        if (!world.canMineBlock(player, tile.getXCoord(), tile.getYCoord(), tile.getZCoord())
            || !player.canPlayerEdit(tile.getXCoord(), tile.getYCoord(), tile.getZCoord(), target.side.ordinal(), link)
            || tile instanceof BaseMetaTileEntity && ((BaseMetaTileEntity) tile).privateAccess()
                && !player.getUniqueID()
                    .equals(tile.getOwnerUuid()))
            return false;
        PlayerInteractEvent protection = new PlayerInteractEvent(
            player,
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK,
            tile.getXCoord(),
            tile.getYCoord(),
            tile.getZCoord(),
            target.side.ordinal(),
            world);
        return !MinecraftForge.EVENT_BUS.post(protection) && protection.useBlock != Result.DENY
            && protection.useItem != Result.DENY;
    }

    private static void complete(Batch batch) {
        if (!online(batch.player)) return;
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(batch.player.worldObj);
        DeviceTerminalDataStore.TerminalData data = store.getTerminal(batch.request.terminalId);
        DeviceTerminalDataStore.MachineRecord record = data == null ? null : data.records.get(batch.request.key);
        IGregTechTileEntity controller = resolve(batch.player, batch.request);
        DeviceLinkSummary summary = controller == batch.controller
            && controller.getMetaTileEntity() == batch.controllerMachine
                ? DeviceLinkTargets.discover(controller.getMetaTileEntity()).summary
                : DeviceLinkSummary.UNVERIFIED;
        if (record != null && record.applyLinkSummary(summary)) data.version++;
        PacketSyncDeviceLinkResult result = new PacketSyncDeviceLinkResult(
            batch.request,
            false,
            batch.success,
            batch.skipped,
            batch.failed);
        LinkedHashMap<Long, PacketSyncDeviceLinkResult> history = RECEIPTS
            .computeIfAbsent(batch.player.getUniqueID(), id -> new LinkedHashMap<>());
        history.put(batch.request.requestId, result);
        if (history.size() > 128) history.remove(
            history.keySet()
                .iterator()
                .next());
        GTSWNPacketHandler.NETWORK.sendTo(result, batch.player);
        DeviceTerminalRequestQueue.invalidate(batch.player);
        GTSimpleWirelessNetwork.LOG.info(
            "[DeviceLinks] player={} terminal={} target={} desired={} success={} skipped={} failed={}",
            batch.player.getUniqueID(),
            batch.request.terminalId,
            batch.request.key,
            batch.request.desiredState,
            batch.success,
            batch.skipped,
            batch.failed);
    }

    private static void send(EntityPlayerMP player, PacketDeviceLinkAction request, boolean pending, int success,
        int skipped, int failed) {
        GTSWNPacketHandler.NETWORK
            .sendTo(new PacketSyncDeviceLinkResult(request, pending, success, skipped, failed), player);
    }

    public static void syncSettings(EntityPlayerMP player) {
        GTSWNPacketHandler.NETWORK
            .sendTo(new PacketSyncTerminalLinkSettings(TerminalLinkSettings.autoEnabled(player)), player);
    }

    private static boolean online(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return player != null && player.playerNetServerHandler != null
            && server != null
            && server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) == player;
    }

    public static synchronized void onLogout(UUID player) {
        REQUESTS.remove(player);
        requestSender.remove(player);
        SETTINGS.remove(player);
        BATCHES.remove(player);
        RECEIPTS.remove(player);
        LAST_REQUEST.remove(player);
        PLACEMENTS.removeIf(
            placement -> placement.player.getUniqueID()
                .equals(player));
    }

    public static synchronized void clear() {
        REQUESTS.clear();
        requestSender.clear();
        SETTINGS.clear();
        BATCHES.clear();
        RECEIPTS.clear();
        LAST_REQUEST.clear();
        PLACEMENTS.clear();
    }

    private static final class Settings {

        final EntityPlayerMP player;
        final boolean update, enabled;

        Settings(EntityPlayerMP player, boolean update, boolean enabled) {
            this.player = player;
            this.update = update;
            this.enabled = enabled;
        }
    }

    private static final class Batch {

        final EntityPlayerMP player;
        final PacketDeviceLinkAction request;
        final IGregTechTileEntity controller;
        final gregtech.api.interfaces.metatileentity.IMetaTileEntity controllerMachine;
        final List<DeviceLinkTargets.Target> targets;
        int index, success, skipped, failed;
        long age, nextAt;

        Batch(EntityPlayerMP player, PacketDeviceLinkAction request, IGregTechTileEntity controller,
            List<DeviceLinkTargets.Target> targets) {
            this.player = player;
            this.request = request;
            this.controller = controller;
            this.controllerMachine = controller.getMetaTileEntity();
            this.targets = targets;
        }
    }

    private static final class Placement {

        final EntityPlayerMP player;
        final World world;
        final TileEntity tile;
        final long created;
        long nextAt;

        Placement(EntityPlayerMP player, World world, TileEntity tile) {
            this.player = player;
            this.world = world;
            this.tile = tile;
            this.created = world.getTotalWorldTime();
            this.nextAt = created + 2;
        }
    }
}
