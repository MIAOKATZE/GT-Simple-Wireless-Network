package com.miaokatze.gtswn.common.monitoring;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.api.monitor.GtswnApi;
import com.miaokatze.gtswn.api.monitor.HistorySample;
import com.miaokatze.gtswn.api.monitor.HistoryWindow;
import com.miaokatze.gtswn.api.monitor.NetworkSnapshot;
import com.miaokatze.gtswn.common.device.KeyFormat;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot;
import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;
import com.miaokatze.gtswn.common.panel.AEMonitorDataStore;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/** Authoritative fixed imports from a real held monitor; no arbitrary client coordinates or stored AE credentials. */
public final class PortableProfileAdapter {

    private PortableProfileAdapter() {}

    public static boolean supports(ItemStack stack) {
        return stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor;
    }

    public static Map<String, SourceKey> capture(EntityPlayerMP actor, ItemStack stack, UUID epoch) {
        if (!supports(stack) || actor.getHeldItem() != stack
            || !actor.getUniqueID()
                .toString()
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack)))
            throw new IllegalArgumentException("Hold your own initialized portable monitor");
        UUID network = SpaceProjectManager.getLeader(actor.getUniqueID());
        if (network == null) throw new IllegalArgumentException("Wireless account identity unavailable");
        Map<String, SourceKey> sources = new LinkedHashMap<>();
        sources.put("eu|" + network, new SourceKey(SourceKey.Kind.EU, epoch, network.toString(), 1));
        for (MonitorBindings.Binding binding : MonitorBindings.read(stack)) {
            String locator = "panel|" + binding.dimension
                + ":"
                + binding.x
                + ":"
                + binding.y
                + ":"
                + binding.z
                + "|"
                + binding.panelId;
            TileEntityNetworkInfoPanel panel = panel(locator);
            if (panel == null || !actor.getUniqueID()
                .equals(panel.getOwnerUUID()))
                throw new IllegalArgumentException("Import requires your loaded original panels");
            SourceKey source;
            if (panel.getAEMonitorSourceIdentity() != null) {
                if (!nativePermission(actor.getUniqueID(), panel))
                    throw new IllegalArgumentException("AE profile export requires native SECURITY permission");
                source = panel.getAEMonitorSourceKey();
                if (source == null) throw new IllegalArgumentException("AE identity unavailable");
            } else {
                if (hasAEState(panel)) throw new IllegalArgumentException(
                    "AE panel is unavailable; reconnect it before importing its source");
                source = new SourceKey(SourceKey.Kind.EU, epoch, network.toString(), 1);
            }
            sources.put(locator, source);
        }
        return sources;
    }

    public static MonitorProfile.Status check(UUID owner, String locator, SourceKey source, UUID epoch) {
        if (!epoch.equals(source.epoch)) return MonitorProfile.Status.SOURCE_REPLACED;
        if (source.kind == SourceKey.Kind.EU) {
            UUID current = SpaceProjectManager.getLeader(owner);
            if (current == null || !current.toString()
                .equals(source.nativeId)) return MonitorProfile.Status.NO_PERMISSION;
            if (locator.startsWith("eu|")) return MonitorProfile.Status.LIVE;
        }
        int[] position = position(locator);
        if (position == null) return MonitorProfile.Status.SOURCE_REPLACED;
        World world = DimensionManager.getWorld(position[0]);
        if (world == null || !world.blockExists(position[1], position[2], position[3]))
            return MonitorProfile.Status.SOURCE_UNLOADED;
        TileEntityNetworkInfoPanel panel = panel(locator);
        if (panel == null) return MonitorProfile.Status.SOURCE_REPLACED;
        if (!owner.equals(panel.getOwnerUUID())) return MonitorProfile.Status.NO_PERMISSION;
        if (source.kind == SourceKey.Kind.EU) return euPanelStatus(panel);
        if (source.kind != SourceKey.Kind.AE) return MonitorProfile.Status.SOURCE_REPLACED;
        if (panel.getAEMonitorSourceIdentity() == null) return MonitorProfile.Status.SOURCE_UNLOADED;
        if (!nativePermission(owner, panel)) return MonitorProfile.Status.NO_PERMISSION;
        SourceKey current = panel.getAEMonitorSourceKey();
        if (current == null) return MonitorProfile.Status.SOURCE_UNLOADED;
        return source.equals(current) ? MonitorProfile.Status.LIVE : MonitorProfile.Status.SOURCE_REPLACED;
    }

    /**
     * A fixed EU-only import must never acquire AE data merely because its panel was connected later.
     * Retained AE configuration/history also counts: an unpowered panel can still expose cached AE rows.
     */
    static MonitorProfile.Status euPanelStatus(TileEntityNetworkInfoPanel panel) {
        return hasAEState(panel) ? MonitorProfile.Status.SOURCE_REPLACED : MonitorProfile.Status.LIVE;
    }

    static boolean hasAEState(TileEntityNetworkInfoPanel panel) {
        return panel.getAEMonitorSourceIdentity() != null || panel.getChartItem() != null
            || panel.getChartFluid() != null
            || !panel.getMonitoredItems()
                .isEmpty()
            || !panel.getMonitoredFluids()
                .isEmpty()
            || !panel.getAEChartSamples()
                .isEmpty()
            || !panel.getAEMonitorLatest()
                .isEmpty();
    }

    /** Native permission is checked from the live providers every time, including while the owner is offline. */
    static boolean nativePermission(UUID owner, TileEntityNetworkInfoPanel panel) {
        if (owner == null || !owner.equals(panel.getOwnerUUID())) return false;
        Object identity = panel.getAEMonitorSourceIdentity();
        if (!(identity instanceof IGrid)) return false;
        IGrid grid = (IGrid) identity;
        int actor = AEApi.instance()
            .registries()
            .players()
            .getID(new GameProfile(owner, "monitor-profile"));
        if (actor < 0) return false;
        boolean secured = false;
        for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!ISecurityProvider.class.isAssignableFrom(type)) continue;
            for (IGridNode node : grid.getMachines(type)) {
                if (!(node.getMachine() instanceof ISecurityProvider)) continue;
                secured = true;
                ISecurityProvider provider = (ISecurityProvider) node.getMachine();
                if (!permitsProvider(provider, actor)) return false;
            }
        }
        if (secured) return true;
        // A public grid has no security authority; require the actual panel's native node ownership too.
        IGridNode node = panel.getGridNode(ForgeDirection.UNKNOWN);
        return node != null && node.getPlayerID() == actor;
    }

    static boolean permitsProvider(ISecurityProvider provider, int actor) {
        if (provider == null || actor < 0 || provider.getOwner() < 0) return false;
        if (provider.getOwner() == actor) return true;
        HashMap<Integer, EnumSet<SecurityPermissions>> permissions = new HashMap<>();
        provider.readPermissions(permissions);
        EnumSet<SecurityPermissions> grants = permissions.get(actor);
        if (grants == null) grants = permissions.get(-1);
        return grants != null && grants.contains(SecurityPermissions.SECURITY);
    }

    public static boolean matches(ItemStack held, MonitorProfile profile) {
        if (!supports(held) || !profile.id.equals(MonitorBindings.id(held))
            || !profile.owner()
                .toString()
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(held)))
            return false;
        Map<String, Boolean> expected = new LinkedHashMap<>();
        for (String locator : profile.sources()
            .keySet()) if (locator.startsWith("panel|")) expected.put(locator, true);
        List<MonitorBindings.Binding> bindings = MonitorBindings.read(held);
        if (bindings.size() != expected.size()) return false;
        for (MonitorBindings.Binding binding : bindings) if (expected.remove(
            "panel|" + binding.dimension + ":" + binding.x + ":" + binding.y + ":" + binding.z + "|" + binding.panelId)
            == null) return false;
        return expected.isEmpty();
    }

    public static void restore(ItemStack held, MonitorProfile profile) {
        if (!supports(held)) throw new IllegalArgumentException("Hold a portable wireless monitor");
        if (held.stackTagCompound == null) held.stackTagCompound = new NBTTagCompound();
        NBTTagCompound tag = held.stackTagCompound;
        tag.setString(MonitoringProfiles.NBT_PROFILE, profile.id.toString());
        tag.setString("MonitorId", profile.id.toString());
        tag.setString(
            PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
            profile.owner()
                .toString());
        tag.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
        tag.setLong("MonitorRevision", profile.revision());
        NBTTagList bindings = new NBTTagList();
        for (String locator : profile.sources()
            .keySet()) {
            String[] parts = locator.split("\\|", -1);
            int[] pos = position(locator);
            if (parts.length != 3 || pos == null) continue;
            NBTTagCompound item = new NBTTagCompound();
            item.setInteger("dim", pos[0]);
            item.setInteger("x", pos[1]);
            item.setInteger("y", pos[2]);
            item.setInteger("z", pos[3]);
            item.setString("id", parts[2]);
            bindings.appendTag(item);
        }
        tag.setTag("MonitorPanels", bindings);
    }

    /** Reuses the existing shared sampler/locks, never samples in HTTP or loads a chunk. */
    public static void renew(String locator, SourceKey source, boolean job) {
        if (source.kind == SourceKey.Kind.EU) {
            UUID account = UUID.fromString(source.nativeId);
            World root = DimensionManager.getWorld(0);
            if (root == null) return;
            com.miaokatze.gtswn.common.panel.NetworkInfoDataStore.get(root)
                .getOrCreateNative(account)
                .renewObservation(root.getTotalWorldTime(), job ? 40 : 100);
            GtswnApi.getControlApi()
                .requestNetworkSample(account);
        } else if (source.kind == SourceKey.Kind.AE) {
            TileEntityNetworkInfoPanel panel = panel(locator);
            if (panel != null) {
                if (job) panel.renewMonitoringJobInterest(40);
                else panel.renewPortableInterest();
            }
        }
    }

    public static int cardCount(String locator, SourceKey source) {
        if (source.kind == SourceKey.Kind.EU) return locator.startsWith("eu|") ? 1 : 0;
        if (source.kind != SourceKey.Kind.AE) return 0;
        AEMonitorDataSet dataSet = aeDataSet(panel(locator));
        return dataSet == null ? 0 : dataSet.sampledKeyCount();
    }

    public static List<WebDashboardSnapshot.Card> export(String locator, SourceKey source) {
        return export(locator, source, 128);
    }

    public static List<WebDashboardSnapshot.Card> export(String locator, SourceKey source, int maxCards) {
        List<WebDashboardSnapshot.Card> cards = new ArrayList<>();
        int limit = Math.max(0, Math.min(128, maxCards));
        if (limit == 0) return cards;
        if (source.kind == SourceKey.Kind.EU && locator.startsWith("eu|")) {
            UUID account = UUID.fromString(source.nativeId);
            Optional<NetworkSnapshot> latest = GtswnApi.getMonitorApi()
                .getNetworkSnapshot(account);
            cards.add(
                new WebDashboardSnapshot.Card(
                    "Wireless energy",
                    latest.isPresent() ? latest.get().euStored.toString() : "unknown",
                    "EU",
                    history(
                        GtswnApi.getMonitorApi()
                            .getNetworkHistory(account, 0))));
        } else if (source.kind == SourceKey.Kind.AE) {
            cards.addAll(exportAE(aeDataSet(panel(locator)), limit));
        }
        return cards;
    }

    public static long sampleTick(String locator, SourceKey source) {
        if (source.kind == SourceKey.Kind.EU) return GtswnApi.getMonitorApi()
            .getNetworkSnapshot(UUID.fromString(source.nativeId))
            .map(snapshot -> snapshot.sampleTick)
            .orElse(0L);
        return source.kind == SourceKey.Kind.AE ? aeSampleTick(panel(locator)) : 0;
    }

    /** Server histories, not the client-only panel mirror, establish actual collector freshness. */
    static long aeSampleTick(TileEntityNetworkInfoPanel panel) {
        AEMonitorDataSet dataSet = aeDataSet(panel);
        return dataSet == null ? 0
            : dataSet.projectionSampleTick(
                panel.getWorldObj()
                    .getTotalWorldTime());
    }

    private static AEMonitorDataSet aeDataSet(TileEntityNetworkInfoPanel panel) {
        if (panel == null || panel.getWorldObj() == null || panel.getWorldObj().isRemote) return null;
        String historyKey = panel.getAEMonitorDataKey();
        return historyKey == null ? null
            : AEMonitorDataStore.get(panel.getWorldObj())
                .getIfPresent(historyKey);
    }

    /** Select the bounded prefix before copying raw history; the legacy full-snapshot API is unchanged. */
    static List<WebDashboardSnapshot.Card> exportAE(AEMonitorDataSet dataSet, int maxCards) {
        List<WebDashboardSnapshot.Card> cards = new ArrayList<>();
        int limit = Math.max(0, Math.min(WebDashboardSnapshot.MAX_CARDS, maxCards));
        if (dataSet == null || limit == 0) return cards;
        for (String key : dataSet.sampledKeys(limit)) {
            AEMonitorSample sample = dataSet.newest(key);
            List<AEMonitorSample> history = dataSet.query(key, AEMonitorDataSet.WINDOW_5_MIN);
            double[] values = new double[Math.min(WebDashboardSnapshot.MAX_HISTORY, history.size())];
            for (int i = 0; i < values.length; i++) values[i] = history.get(history.size() - values.length + i).amount;
            cards.add(new WebDashboardSnapshot.Card(key, Long.toString(sample.amount), "amount", values));
        }
        return cards;
    }

    static double[] history(Optional<HistoryWindow> window) {
        if (!window.isPresent()) return new double[0];
        List<HistorySample> samples = window.get().samples;
        double[] values = new double[Math.min(61, samples.size())];
        for (int i = 0; i < values.length; i++)
            values[i] = samples.get(samples.size() - values.length + i).value.doubleValue();
        return values;
    }

    private static int[] position(String locator) {
        String[] parts = locator.split("\\|", -1);
        if (parts.length != 3 || !"panel".equals(parts[0])) return null;
        return KeyFormat.parse(parts[1]);
    }

    private static TileEntityNetworkInfoPanel panel(String locator) {
        int[] pos = position(locator);
        if (pos == null) return null;
        World world = DimensionManager.getWorld(pos[0]);
        if (world == null || !world.blockExists(pos[1], pos[2], pos[3])) return null;
        TileEntity tile = world.getTileEntity(pos[1], pos[2], pos[3]);
        if (!(tile instanceof TileEntityNetworkInfoPanel)) return null;
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
        return locator.endsWith("|" + panel.getPortableMonitorId()) ? panel : null;
    }
}
