package com.miaokatze.gtswn.common.monitoring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persisted intent only: never retains a player, world, tile or credential. */
public final class MonitorProfile {

    public enum Status {
        LIVE,
        CACHED,
        SOURCE_UNLOADED,
        NO_PERMISSION,
        SOURCE_REPLACED,
        JOB_PAUSED
    }

    public static final int MAX_NAME_LENGTH = 48;
    private String name = "";
    private boolean ditPresentation;
    private String ditSort = "AVG", ditDirection = "DESC", ditCount = "NORMAL", ditItemName = "";

    public final UUID id;
    public final UUID legacyId;
    private UUID owner;
    private final Set<UUID> readers = new LinkedHashSet<>();
    private final Map<String, SourceKey> sources = new LinkedHashMap<>();
    long revision = 1;
    boolean offline;
    boolean webEnabled;
    int intervalTicks = 20;
    long lastSampleTick = Long.MIN_VALUE;
    long sampleTime;
    transient boolean sampledThisSession;
    Status status = Status.CACHED;

    MonitorProfile(UUID id, UUID owner, UUID legacyId, Map<String, SourceKey> sources) {
        if (id == null || owner == null || sources == null)
            throw new IllegalArgumentException("Missing profile fields");
        this.id = id;
        this.owner = owner;
        this.legacyId = legacyId;
        this.sources.putAll(sources);
    }

    public String name() {
        return name.isEmpty() ? "Profile " + id.toString()
            .substring(0, 8) : name;
    }

    public static String normalizeName(String value) {
        if (value == null || value.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Profile name must be at most 48 characters");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            int codePoint = character;
            if (Character.isHighSurrogate(character)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("Invalid profile name encoding");
                codePoint = Character.toCodePoint(character, value.charAt(++i));
            } else if (Character.isLowSurrogate(character))
                throw new IllegalArgumentException("Invalid profile name encoding");
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT
                || codePoint == 0x00a7)
                throw new IllegalArgumentException("Profile name cannot contain controls or formatting codes");
        }
        return value.trim();
    }

    void rename(String value) {
        String normalized = normalizeName(value);
        if (!name.equals(normalized)) {
            name = normalized;
            revision++;
        }
    }

    void readName(String value) {
        try {
            name = normalizeName(value);
        } catch (IllegalArgumentException invalid) {
            name = "";
        }
    }

    /** Only presentation primitives are retained; no whole-item NBT, identities, ownership or native credentials. */
    void captureDitPresentation(net.minecraft.item.ItemStack held) {
        if (held == null) return;
        String column = com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.getSortColumn(held);
        String direction = com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.getSortDirection(held);
        String count = com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.getCountMode(held);
        String itemName = "";
        try {
            if (held.hasDisplayName()) itemName = normalizeName(held.getDisplayName());
        } catch (IllegalArgumentException invalid) { /* Invalid legacy display text is not persisted. */ }
        if (!ditPresentation || !ditSort.equals(column)
            || !ditDirection.equals(direction)
            || !ditCount.equals(count)
            || !ditItemName.equals(itemName)) {
            ditPresentation = true;
            ditSort = column;
            ditDirection = direction;
            ditCount = count;
            ditItemName = itemName;
            revision++;
        }
    }

    void restoreDitPresentation(net.minecraft.item.ItemStack held) {
        if (!ditPresentation || held == null) return;
        com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.setSortColumn(held, ditSort);
        com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.setSortDirection(held, ditDirection);
        com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.setCountMode(held, ditCount);
        if (ditItemName.isEmpty()) held.func_135074_t();
        else held.setStackDisplayName(ditItemName);
    }

    void writePresentation(net.minecraft.nbt.NBTTagCompound tag) {
        tag.setString("name", name);
        if (!ditPresentation) return;
        tag.setBoolean("ditPresentation", true);
        tag.setString("ditSort", ditSort);
        tag.setString("ditDirection", ditDirection);
        tag.setString("ditCount", ditCount);
        tag.setString("ditItemName", ditItemName);
    }

    void readPresentation(net.minecraft.nbt.NBTTagCompound tag) {
        readName(tag.getString("name"));
        ditPresentation = tag.getBoolean("ditPresentation");
        String column = tag.getString("ditSort"), count = tag.getString("ditCount");
        ditSort = java.util.Arrays.asList("NAME", "STATE", "INST", "AVG", "POS")
            .contains(column) ? column : "AVG";
        ditDirection = "ASC".equals(tag.getString("ditDirection")) ? "ASC" : "DESC";
        ditCount = java.util.Arrays.asList("NORMAL", "SCIENTIFIC", "THOUSANDS", "VOLTAGE")
            .contains(count) ? count : "NORMAL";
        try {
            ditItemName = normalizeName(tag.getString("ditItemName"));
        } catch (IllegalArgumentException invalid) {
            ditItemName = "";
        }
    }

    public UUID owner() {
        return owner;
    }

    public long revision() {
        return revision;
    }

    public boolean offlineEnabled() {
        return offline;
    }

    public boolean webEnabled() {
        return webEnabled;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public long sampleTime() {
        return sampleTime;
    }

    public int historyPoints() {
        for (SourceKey source : sources.values()) {
            if (source.kind == SourceKey.Kind.QUANTUM) return 0;
            if (source.kind == SourceKey.Kind.EU || source.kind == SourceKey.Kind.AE) return 61;
        }
        return 60;
    }

    public Status status() {
        return status;
    }

    public Map<String, SourceKey> sources() {
        return Collections.unmodifiableMap(sources);
    }

    public Set<UUID> readers() {
        return Collections.unmodifiableSet(readers);
    }

    public boolean mayRead(UUID actor) {
        return owner.equals(actor) || readers.contains(actor);
    }

    public boolean mayModify(UUID actor) {
        return owner.equals(actor);
    }

    void detach(String locator) {
        if (sources.remove(locator) != null) {
            webEnabled = false;
            revision++;
        }
    }

    void share(UUID actor) {
        if (!owner.equals(actor) && readers.add(actor)) revision++;
    }

    void revoke(UUID actor) {
        if (readers.remove(actor)) revision++;
    }

    void transfer(UUID actor) {
        owner = actor;
        readers.clear();
        webEnabled = false;
        offline = false;
        revision++;
    }

    void setWeb(boolean enabled) {
        if (webEnabled != enabled) {
            webEnabled = enabled;
            revision++;
        }
    }

    void setOffline(boolean enabled, int ticks) {
        if (offline != enabled || intervalTicks != ticks) {
            offline = enabled;
            intervalTicks = ticks;
            lastSampleTick = Long.MIN_VALUE;
            status = enabled ? Status.CACHED : Status.JOB_PAUSED;
            revision++;
        }
    }
}
