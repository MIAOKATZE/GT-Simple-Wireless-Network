package com.miaokatze.gtswn.common.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.monitoring.web.WebDashboardSnapshot;
import com.miaokatze.gtswn.common.quantum.AnchorKey;
import com.miaokatze.gtswn.common.quantum.AnchorReachability;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkStatsCache;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import appeng.me.helpers.IGridProxyable;

/** Main-thread fixed-source adapter. A profile never stores or manufactures quantum pairing credentials. */
public final class QuantumProfileAdapter {

    private QuantumProfileAdapter() {}

    public static boolean supports(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemNetworkQuantumTerminal;
    }

    public static Map<String, SourceKey> capture(EntityPlayerMP actor, ItemStack held, UUID epoch) {
        if (actor == null || actor.getHeldItem() != held || !authorized(actor, held) || epoch == null)
            return Collections.emptyMap();
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(held);
        SourceKey source = identity(anchor, epoch);
        if (source == null) return Collections.emptyMap();
        Map<String, SourceKey> result = new LinkedHashMap<>();
        result.put(locator(anchor), source);
        return result;
    }

    /** A profile reference is not permission to retarget a terminal to a different native network. */
    public static boolean matches(ItemStack stack, MonitorProfile profile) {
        if (!supports(stack) || profile == null
            || profile.sources()
                .size() != 1)
            return false;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return false;
        SourceKey expected = profile.sources()
            .get(locator(anchor));
        return expected != null && expected.kind == SourceKey.Kind.QUANTUM
            && expected.equals(identity(anchor, expected.epoch));
    }

    /** Supplemental managed-profile ACL. Unmarked legacy terminals preserve their native behavior. */
    public static boolean profilePermits(EntityPlayer actor, ItemStack stack, boolean modify) {
        if (!MonitoringProfiles.hasProfileReference(stack)) return true;
        if (actor == null || actor.worldObj == null || actor.worldObj.isRemote) return false;
        UUID id = MonitoringProfiles.readProfileReference(stack);
        if (id == null) return false;
        MonitoringProfiles profiles = MonitoringProfiles.get(actor.worldObj);
        MonitorProfile profile = profiles.profileForTerminal(id);
        return profile != null && matches(stack, profile)
            && profiles.canRead(actor.getUniqueID(), id)
            && (!modify || profiles.canModify(actor.getUniqueID(), id));
    }

    private static boolean authorized(EntityPlayerMP actor, ItemStack stack) {
        return supports(stack) && ItemNetworkQuantumTerminal.getAnchor(stack) != null
            && QuantumTerminalAccess.validate(actor, stack)
            && QuantumTerminalAccess.canChangeNetworkSafety(actor, stack);
    }

    public static MonitorProfile.Status check(UUID owner, String locator, SourceKey source, UUID epoch,
        boolean loadedRequired) {
        if (source == null || source.kind != SourceKey.Kind.QUANTUM || !source.epoch.equals(epoch))
            return MonitorProfile.Status.SOURCE_REPLACED;
        int[] anchor = parse(locator);
        if (anchor == null) return MonitorProfile.Status.SOURCE_REPLACED;
        WorldServer world = DimensionManager.getWorld(anchor[0]);
        if (world == null || !world.blockExists(anchor[1], anchor[2], anchor[3]))
            return MonitorProfile.Status.SOURCE_UNLOADED;
        SourceKey actual = identity(anchor, epoch);
        if (!source.equals(actual)) return MonitorProfile.Status.SOURCE_REPLACED;
        return nativeAuthority(owner, world, anchor);
    }

    private static QuantumNetworkStatsCache.Snapshot sample(UUID owner, String locator, SourceKey source,
        boolean renew) {
        if (source == null || check(owner, locator, source, source.epoch, true) != MonitorProfile.Status.LIVE)
            return null;
        int[] anchor = parse(locator);
        WorldServer world = DimensionManager.getWorld(anchor[0]);
        AnchorReachability.Result reach = AnchorReachability
            .resolve(world, anchor[1], anchor[2], anchor[3], true, true);
        if (reach.status != AnchorReachability.Status.OK) return null;
        try {
            IGrid grid = ((IGridProxyable) reach.anchorTE).getProxy()
                .getGrid();
            AnchorKey key = new AnchorKey(anchor[0], anchor[1], anchor[2], anchor[3]);
            return renew ? QuantumNetworkStatsCache.getOrCompute(key, world, grid)
                : QuantumNetworkStatsCache.getCached(key, world, grid);
        } catch (Exception unavailable) {
            return null;
        }
    }

    public static long sampleTick(UUID owner, String locator, SourceKey source) {
        QuantumNetworkStatsCache.Snapshot stats = sample(owner, locator, source, false);
        return stats == null ? 0 : stats.sampleTick;
    }

    public static List<WebDashboardSnapshot.Card> export(UUID owner, String locator, SourceKey source) {
        QuantumNetworkStatsCache.Snapshot stats = sample(owner, locator, source, false);
        if (stats == null) return Collections.emptyList();
        try {
            List<WebDashboardSnapshot.Card> cards = new ArrayList<>();
            cards.add(card("Quantum channels used", stats.usedChannels, "channels"));
            cards.add(
                new WebDashboardSnapshot.Card(
                    "Quantum channel capacity",
                    QuantumControllerRegistry.isChannelsInfinite() ? "unlimited"
                        : Integer.toString(stats.totalChannels),
                    "channels",
                    null));
            cards.add(card("Quantum nodes", stats.quantumNodeCount, "nodes"));
            cards.add(card("Incorporated devices", stats.incorporationCount, "devices"));
            cards.add(card("Incorporation channels", stats.incorporationChannels, "channels"));
            return Collections.unmodifiableList(cards);
        } catch (Exception unavailable) {
            return Collections.emptyList();
        }
    }

    /** Offline work uses current native UUID security, never a saved terminal key or a fake player. */
    public static long renewJob(UUID owner, String locator, SourceKey source, long tick) {
        // Existing 100-tick shared cache; never claim a new sample merely because an old cache entry was read.
        QuantumNetworkStatsCache.Snapshot stats = sample(owner, locator, source, true);
        return stats == null ? 0 : stats.sampleTick;
    }

    private static WebDashboardSnapshot.Card card(String label, int value, String unit) {
        return new WebDashboardSnapshot.Card(label, Integer.toString(value), unit, null);
    }

    /**
     * Native security provider tables support UUID-backed player IDs without an online EntityPlayer.
     * Unknown/unavailable authority pauses rather than falling back to NBT ownership or old credentials.
     */
    private static MonitorProfile.Status nativeAuthority(UUID owner, WorldServer world, int[] anchor) {
        if (owner == null) return MonitorProfile.Status.NO_PERMISSION;
        try {
            TileEntity tile = world.getTileEntity(anchor[1], anchor[2], anchor[3]);
            if (!(tile instanceof IGridHost)) return MonitorProfile.Status.JOB_PAUSED;
            IGridNode anchorNode = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            IGrid grid = anchorNode == null ? null : anchorNode.getGrid();
            if (grid == null) return MonitorProfile.Status.JOB_PAUSED;
            int actor = AEApi.instance()
                .registries()
                .players()
                .getID(new GameProfile(owner, "monitor-profile"));
            if (actor < 0) return MonitorProfile.Status.JOB_PAUSED;
            List<ISecurityProvider> providers = new ArrayList<>();
            for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
                if (!ISecurityProvider.class.isAssignableFrom(type)) continue;
                for (IGridNode node : grid.getMachines(type)) {
                    if (!(node.getMachine() instanceof ISecurityProvider)) continue;
                    providers.add((ISecurityProvider) node.getMachine());
                }
            }
            MonitorProfile.Status authority = providerAuthority(actor, providers);
            if (authority != null) return authority;
            // Without a native security provider, require ownership of every actual registered controller.
            java.util.Set<Long> members = QuantumControllerRegistry.get(world)
                .registeredStructure(QuantumControllerRegistry.pack(anchor[1], anchor[2], anchor[3]));
            if (members.isEmpty() || members.size() > 4096) return MonitorProfile.Status.JOB_PAUSED;
            for (long member : members) {
                int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                    z = QuantumControllerRegistry.unpackZ(member);
                if (!world.blockExists(x, y, z)) return MonitorProfile.Status.SOURCE_UNLOADED;
                if (QuantumTerminalAccess.networkSafetyIdentity(world, x, y, z)
                    .isEmpty()) return MonitorProfile.Status.SOURCE_REPLACED;
                TileEntity controller = world.getTileEntity(x, y, z);
                if (!(controller instanceof IGridHost)) return MonitorProfile.Status.SOURCE_REPLACED;
                IGridNode node = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN);
                if (node == null) return MonitorProfile.Status.JOB_PAUSED;
                if (node.getGrid() != grid || node.getPlayerID() != actor) return MonitorProfile.Status.NO_PERMISSION;
            }
            return MonitorProfile.Status.LIVE;
        } catch (RuntimeException unavailable) {
            return MonitorProfile.Status.JOB_PAUSED;
        }
    }

    /** Live native ACL projection shared by online/offline checks; null requires real controller ownership fallback. */
    static MonitorProfile.Status providerAuthority(int actor, Iterable<ISecurityProvider> providers) {
        if (actor < 0 || providers == null) return MonitorProfile.Status.JOB_PAUSED;
        try {
            boolean secured = false;
            for (ISecurityProvider provider : providers) {
                secured = true;
                if (!PortableProfileAdapter.permitsProvider(provider, actor))
                    return MonitorProfile.Status.NO_PERMISSION;
            }
            return secured ? MonitorProfile.Status.LIVE : null;
        } catch (RuntimeException unavailable) {
            return MonitorProfile.Status.JOB_PAUSED;
        }
    }

    private static SourceKey identity(int[] anchor, UUID epoch) {
        if (anchor == null || epoch == null) return null;
        WorldServer world = DimensionManager.getWorld(anchor[0]);
        if (world == null || !world.blockExists(anchor[1], anchor[2], anchor[3])) return null;
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        long position = QuantumControllerRegistry.pack(anchor[1], anchor[2], anchor[3]);
        QuantumControllerRegistry.CanonicalAnchor canonical = registry.canonicalAnchor(position);
        String controller = QuantumTerminalAccess.networkSafetyIdentity(world, anchor[1], anchor[2], anchor[3]);
        if (canonical == null || controller.isEmpty()) return null;
        List<Long> members = new ArrayList<>(registry.registeredStructure(position));
        if (members.isEmpty() || members.size() > 4096) return null;
        Collections.sort(members);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (long member : members) digest.update((member + ";").getBytes(StandardCharsets.US_ASCII));
            byte[] bytes = digest.digest();
            StringBuilder boundary = new StringBuilder();
            for (int i = 0; i < 16; i++) boundary.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i] & 255));
            return new SourceKey(
                SourceKey.Kind.QUANTUM,
                epoch,
                canonical.generation + ":" + controller + ":" + boundary,
                1);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String locator(int[] anchor) {
        return "quantum:" + anchor[0] + ":" + anchor[1] + ":" + anchor[2] + ":" + anchor[3];
    }

    static int[] parse(String locator) {
        if (locator == null || locator.length() > 96) return null;
        String[] parts = locator.split(":");
        if (parts.length != 5 || !"quantum".equals(parts[0])) return null;
        try {
            int[] result = { Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]) };
            return result[2] < 0 || result[2] >= 256 ? null : result;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
