package com.miaokatze.gtswn.common.monitoring.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.util.SavedDataUtil;

/** OP-owned page definitions. Owner profile consent is necessary but never creates a dashboard by itself. */
public final class WebDashboardRegistry extends WorldSavedData {

    public static final String DATA_NAME = "gtswn_web_dashboards";
    public static final int MAX_DEFINITIONS = 64;
    public static final int MAX_PROFILES_PER_DASHBOARD = 8;
    public static final int MAX_ENABLED_SOURCE_REFERENCES = 4096;
    private final Map<UUID, Dashboard> dashboards = new LinkedHashMap<>();

    interface ProfileExport {

        boolean authorized(UUID id);

        default int sourceCount(UUID id) {
            return 1;
        }

        WebDashboardSnapshot snapshot(UUID id);

        default WebDashboardSnapshot snapshot(UUID id, int maxCards) {
            WebDashboardSnapshot value = snapshot(id);
            if (value == null) return null;
            int count = Math.min(maxCards, value.cards.size());
            return new WebDashboardSnapshot(
                value.id,
                value.title,
                value.revision,
                value.status,
                value.sampleTick,
                value.cards.subList(0, count),
                value.omittedCards + value.cards.size() - count);
        }
    }

    public static final class Dashboard {

        public final UUID id;
        public final String title;
        private final Set<UUID> profiles = new LinkedHashSet<>();
        private boolean enabled;
        private boolean blocked;
        private long revision = 1;

        Dashboard(UUID id, String title) {
            this.id = id;
            this.title = title;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean blocked() {
            return blocked;
        }

        public Set<UUID> profiles() {
            return Collections.unmodifiableSet(profiles);
        }
    }

    public WebDashboardRegistry(String name) {
        super(name);
    }

    public static WebDashboardRegistry get(World requested) {
        MinecraftServer server = MinecraftServer.getServer();
        World root = server == null ? requested : server.worldServerForDimension(0);
        if (root == null) root = requested;
        if (root == null || root.isRemote) throw new IllegalStateException("Server world required");
        return SavedDataUtil.loadOrCreate(root, DATA_NAME, WebDashboardRegistry.class, WebDashboardRegistry::new);
    }

    /** The boolean is attested by the server command sender, never accepted from a network payload. */
    public Dashboard create(boolean operator, String title) {
        requireOperator(operator);
        if (title == null || title.trim()
            .isEmpty() || title.length() > 64)
            throw new IllegalArgumentException("Dashboard name must contain 1..64 characters");
        if (dashboards.size() >= MAX_DEFINITIONS)
            throw new IllegalArgumentException("Dashboard definition quota reached");
        Dashboard result = new Dashboard(UUID.randomUUID(), title.trim());
        dashboards.put(result.id, result);
        changed();
        return result;
    }

    public List<Dashboard> list(boolean operator) {
        requireOperator(operator);
        return Collections.unmodifiableList(new ArrayList<>(dashboards.values()));
    }

    public void add(boolean operator, UUID dashboard, UUID profile, MonitoringProfiles profiles) {
        add(operator, dashboard, profile, source(profiles));
    }

    void add(boolean operator, UUID dashboard, UUID profile, ProfileExport source) {
        requireOperator(operator);
        Dashboard target = require(dashboard);
        if (profile == null || !source.authorized(profile))
            throw new IllegalArgumentException("Profile owner consent and valid fixed sources are required");
        if (!target.profiles.contains(profile) && target.profiles.size() >= MAX_PROFILES_PER_DASHBOARD)
            throw new IllegalArgumentException("Dashboard profile limit reached");
        if (target.enabled && !fitsSourceBudget(target, profile, source))
            throw new IllegalArgumentException("Enabled dashboards exceed the global 4096 source-reference budget");
        if (target.profiles.add(profile)) {
            target.revision++;
            changed();
        }
    }

    public void remove(boolean operator, UUID dashboard, UUID profile) {
        requireOperator(operator);
        Dashboard target = require(dashboard);
        if (target.profiles.remove(profile)) {
            target.revision++;
            changed();
        }
    }

    public void setEnabled(boolean operator, UUID dashboard, boolean enabled, MonitoringProfiles profiles) {
        setEnabled(operator, dashboard, enabled, ReadonlyWebService.maxDashboards(), source(profiles));
    }

    void setEnabled(boolean operator, UUID dashboard, boolean enabled, int limit, ProfileExport source) {
        requireOperator(operator);
        Dashboard target = require(dashboard);
        if (enabled) {
            if (!fitsSourceBudget(target, null, source))
                throw new IllegalArgumentException("Enabled dashboards exceed the global 4096 source-reference budget");
            if (!authorized(target, source))
                throw new IllegalArgumentException("Dashboard needs consented, authorized profiles");
            int active = 0;
            for (Dashboard current : dashboards.values())
                if (current.enabled && !current.id.equals(dashboard)) active++;
            if (active >= Math.max(1, Math.min(16, limit)))
                throw new IllegalArgumentException("Enabled dashboard quota reached");
        }
        if (target.enabled != enabled) {
            target.enabled = enabled;
            target.revision++;
            changed();
        }
    }

    public void delete(boolean operator, UUID dashboard) {
        requireOperator(operator);
        if (dashboards.remove(dashboard) == null) throw new IllegalArgumentException("Unknown dashboard");
        changed();
    }

    /** Cheap authorization-only main-thread check; no DTO/history construction on every tick. */
    public void revalidate(MonitoringProfiles profiles) {
        revalidate(source(profiles), ReadonlyWebService.maxDashboards());
    }

    void revalidate(final ProfileExport source, int limit) {
        final Map<UUID, Boolean> decisions = new LinkedHashMap<>();
        ProfileExport memoized = new ProfileExport() {

            @Override
            public boolean authorized(UUID id) {
                Boolean decision = decisions.get(id);
                if (decision == null) {
                    decision = source.authorized(id);
                    decisions.put(id, decision);
                }
                return decision;
            }

            @Override
            public WebDashboardSnapshot snapshot(UUID id) {
                return source.snapshot(id);
            }
        };
        int active = 0, references = 0;
        Set<UUID> admitted = new LinkedHashSet<>();
        for (Dashboard dashboard : dashboards.values()) {
            if (dashboard.enabled) {
                int additional = 0;
                for (UUID id : dashboard.profiles)
                    if (!admitted.contains(id)) additional += Math.max(0, source.sourceCount(id));
                if (active >= Math.max(1, Math.min(16, limit))
                    || additional > MAX_ENABLED_SOURCE_REFERENCES - references) {
                    dashboard.enabled = false;
                    dashboard.revision++;
                    changed();
                } else {
                    active++;
                    references += additional;
                    admitted.addAll(dashboard.profiles);
                }
            }
            boolean blocked = dashboard.enabled && !authorized(dashboard, memoized);
            if (blocked != dashboard.blocked) {
                dashboard.blocked = blocked;
                if (blocked) ReadonlyWebService.revokeDashboard(dashboard.id);
            }
        }
        Map<UUID, Set<UUID>> memberships = new LinkedHashMap<>();
        for (Dashboard dashboard : dashboards.values()) if (dashboard.enabled && !dashboard.blocked)
            for (UUID id : dashboard.profiles) memberships.computeIfAbsent(id, ignored -> new LinkedHashSet<>())
                .add(dashboard.id);
        ReadonlyWebService.setProfileMembership(memberships);
    }

    private boolean fitsSourceBudget(Dashboard target, UUID additionalProfile, ProfileExport source) {
        Set<UUID> references = new LinkedHashSet<>();
        for (Dashboard dashboard : dashboards.values())
            if (dashboard.enabled || dashboard == target) references.addAll(dashboard.profiles);
        if (additionalProfile != null) references.add(additionalProfile);
        int count = 0;
        for (UUID id : references) {
            int next = Math.max(0, source.sourceCount(id));
            if (next > MAX_ENABLED_SOURCE_REFERENCES - count) return false;
            count += next;
        }
        return true;
    }

    public List<WebDashboardSnapshot> exportSnapshots(MonitoringProfiles profiles) {
        return exportSnapshots(source(profiles), ReadonlyWebService.maxDashboards());
    }

    List<WebDashboardSnapshot> exportSnapshots(ProfileExport source, int limit) {
        revalidate(source, limit);
        List<WebDashboardSnapshot> result = new ArrayList<>();
        Map<UUID, WebDashboardSnapshot> projected = new LinkedHashMap<>();
        int enabledCount = 0;
        for (Dashboard dashboard : dashboards.values()) if (dashboard.enabled && !dashboard.blocked) enabledCount++;
        Map<UUID, Integer> profileLimits = new LinkedHashMap<>();
        for (Dashboard dashboard : dashboards.values()) if (dashboard.enabled && !dashboard.blocked) {
            int share = Math
                .max(1, WebDashboardSnapshot.MAX_CARDS / Math.max(1, enabledCount) / dashboard.profiles.size());
            for (UUID id : dashboard.profiles)
                profileLimits.put(id, Math.max(share, profileLimits.getOrDefault(id, 0)));
        }
        for (Dashboard dashboard : dashboards.values()) {
            if (!dashboard.enabled || dashboard.blocked) continue;
            List<WebDashboardSnapshot.Card> cards = new ArrayList<>();
            String status = "LIVE";
            long sampleTick = Long.MAX_VALUE, revision = dashboard.revision;
            boolean valid = true;
            int omittedCards = 0;
            int perProfile = Math
                .max(1, WebDashboardSnapshot.MAX_CARDS / Math.max(1, enabledCount) / dashboard.profiles.size());
            for (UUID id : dashboard.profiles) {
                WebDashboardSnapshot projection = projected.get(id);
                if (projection == null) {
                    projection = source.snapshot(id, profileLimits.get(id));
                    if (projection != null) projected.put(id, projection);
                }
                if (projection == null) {
                    valid = false;
                    break;
                }
                revision = 31 * revision + projection.revision;
                sampleTick = Math.min(sampleTick, projection.sampleTick);
                if (!"LIVE".equals(projection.status)) status = projection.status;
                omittedCards += projection.omittedCards + Math.max(0, projection.cards.size() - perProfile);
                int included = 0;
                for (WebDashboardSnapshot.Card card : projection.cards) {
                    if (included++ >= perProfile) break;
                    cards.add(card);
                }
            }
            if (valid) result.add(
                new WebDashboardSnapshot(
                    dashboard.id,
                    dashboard.title,
                    revision,
                    status,
                    sampleTick == Long.MAX_VALUE ? 0 : sampleTick,
                    cards,
                    omittedCards));
            else {
                dashboard.blocked = true;
                ReadonlyWebService.revokeDashboard(dashboard.id);
            }
        }
        return result;
    }

    private static ProfileExport source(final MonitoringProfiles profiles) {
        return new ProfileExport() {

            @Override
            public boolean authorized(UUID id) {
                return profiles.canPublish(id);
            }

            @Override
            public int sourceCount(UUID id) {
                return profiles.sourceCount(id);
            }

            @Override
            public WebDashboardSnapshot snapshot(UUID id) {
                return profiles.exportWebSnapshot(id);
            }

            @Override
            public WebDashboardSnapshot snapshot(UUID id, int maxCards) {
                return profiles.exportWebSnapshot(id, maxCards);
            }
        };
    }

    private static boolean authorized(Dashboard dashboard, ProfileExport source) {
        if (dashboard.profiles.isEmpty()) return false;
        for (UUID profile : dashboard.profiles) if (!source.authorized(profile)) return false;
        return true;
    }

    private Dashboard require(UUID id) {
        Dashboard result = dashboards.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown dashboard");
        return result;
    }

    private static void requireOperator(boolean operator) {
        if (!operator) throw new IllegalArgumentException("Operator permission level 2 required");
    }

    private void changed() {
        markDirty();
        ReadonlyWebService.clearPublished();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setInteger("format", 1);
        NBTTagList list = new NBTTagList();
        for (Dashboard dashboard : dashboards.values()) {
            NBTTagCompound item = new NBTTagCompound();
            item.setString("id", dashboard.id.toString());
            item.setString("title", dashboard.title);
            item.setBoolean("enabled", dashboard.enabled);
            item.setLong("revision", dashboard.revision);
            NBTTagList references = new NBTTagList();
            for (UUID id : dashboard.profiles) {
                NBTTagCompound ref = new NBTTagCompound();
                ref.setString("id", id.toString());
                references.appendTag(ref);
            }
            item.setTag("profiles", references);
            list.appendTag(item);
        }
        tag.setTag("dashboards", list);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        dashboards.clear();
        ReadonlyWebService.clearPublished();
        NBTTagList list = tag.getTagList("dashboards", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < Math.min(MAX_DEFINITIONS, list.tagCount()); i++) {
            try {
                NBTTagCompound item = list.getCompoundTagAt(i);
                UUID id = UUID.fromString(item.getString("id"));
                String title = item.getString("title");
                if (title.isEmpty() || title.length() > 64 || dashboards.containsKey(id)) continue;
                Dashboard dashboard = new Dashboard(id, title);
                NBTTagList references = item.getTagList("profiles", Constants.NBT.TAG_COMPOUND);
                if (references.tagCount() > MAX_PROFILES_PER_DASHBOARD) continue;
                for (int j = 0; j < references.tagCount(); j++) dashboard.profiles.add(
                    UUID.fromString(
                        references.getCompoundTagAt(j)
                            .getString("id")));
                dashboard.enabled = item.getBoolean("enabled");
                dashboard.blocked = dashboard.enabled;
                dashboard.revision = Math.max(1, item.getLong("revision"));
                dashboards.put(id, dashboard);
            } catch (IllegalArgumentException malformed) { /* Do not reinterpret malformed fixed references. */ }
        }
    }
}
