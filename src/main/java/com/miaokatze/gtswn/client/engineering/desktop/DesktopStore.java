package com.miaokatze.gtswn.client.engineering.desktop;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Separate local preference files: existing factory/portable configuration remains untouched. */
public final class DesktopStore {

    public static final int SCHEMA = 1;
    private final Path directory;
    private final Map<String, Properties> profiles = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private long lastSave;

    public DesktopStore(File directory) {
        this.directory = directory == null ? null : directory.toPath();
    }

    /** Length framing avoids aliases between server/world names and player identifiers. */
    public static String profile(String world, String player) {
        if (world == null || player == null) throw new IllegalArgumentException("profile");
        String identity = world.length() + ":" + world + player.length() + ":" + player;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public DesktopLayout load(String profile) {
        return decode(read(profile));
    }

    public DesktopViewport loadView(String profile) {
        Properties p = read(profile);
        DesktopViewport view = new DesktopViewport();
        view.panX = number(p, "view.x", 0);
        view.panY = number(p, "view.y", 0);
        view.scale = number(p, "view.scale", 1);
        view.sanitize();
        return view;
    }

    public void put(String profile, DesktopLayout layout) {
        if (layout == null) return;
        Properties merged = encode(layout);
        Properties old = read(profile);
        for (String key : new String[] { "view.x", "view.y", "view.scale" })
            if (old.containsKey(key)) merged.setProperty(key, old.getProperty(key));
        update(profile, merged);
    }

    public void putView(String profile, DesktopViewport view) {
        if (view == null) return;
        Properties merged = new Properties();
        merged.putAll(read(profile));
        DesktopViewport safe = view.copy();
        merged.setProperty("view.x", Double.toString(safe.panX));
        merged.setProperty("view.y", Double.toString(safe.panY));
        merged.setProperty("view.scale", Double.toString(safe.scale));
        update(profile, merged);
    }

    private void update(String profile, Properties value) {
        if (value.equals(read(profile))) return;
        profiles.put(profile, value);
        dirty.add(profile);
    }

    private Properties read(String profile) {
        validateProfile(profile);
        Properties cached = profiles.get(profile);
        if (cached != null) return cached;
        Properties p = new Properties();
        if (directory != null) {
            Path file = directory.resolve(profile + ".properties");
            try {
                if (Files.isRegularFile(file) && Files.size(file) <= 131072) {
                    try (InputStream input = Files.newInputStream(file)) {
                        p.load(input);
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                p.clear();
            }
        }
        profiles.put(profile, p);
        return p;
    }

    public boolean saveIfDue(long nowNanos) {
        if (dirty.isEmpty()) return true;
        if (lastSave != 0 && nowNanos - lastSave < 1000000000L) return false;
        lastSave = nowNanos;
        return flush();
    }

    public boolean flush() {
        if (directory == null) {
            dirty.clear();
            return true;
        }
        boolean success = true;
        for (String profile : new HashSet<>(dirty)) {
            Path temp = null;
            try {
                Files.createDirectories(directory);
                temp = Files.createTempFile(directory, profile + "-", ".tmp");
                try (OutputStream output = Files.newOutputStream(temp)) {
                    profiles.get(profile)
                        .store(output, "GTSWN desktop display preferences; no machine samples");
                }
                Path file = directory.resolve(profile + ".properties");
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty.remove(profile);
            } catch (IOException ignored) {
                success = false;
            } finally {
                if (temp != null) try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
        }
        return success;
    }

    private static void validateProfile(String profile) {
        if (profile == null || !profile.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("profile");
    }

    public static Properties encode(DesktopLayout layout) {
        Properties p = new Properties();
        p.setProperty("schema", Integer.toString(SCHEMA));
        p.setProperty(
            "count",
            Integer.toString(
                layout.widgets()
                    .size()));
        int index = 0;
        for (WidgetSpec widget : layout.widgets()) {
            String prefix = "widget." + index++ + ".";
            p.setProperty(prefix + "id", widget.id);
            p.setProperty(prefix + "kind", widget.kind.name());
            p.setProperty(prefix + "column", Integer.toString(widget.column));
            p.setProperty(prefix + "row", Integer.toString(widget.row));
            p.setProperty(prefix + "columns", Integer.toString(widget.columns));
            p.setProperty(prefix + "rows", Integer.toString(widget.rows));
            p.setProperty(prefix + "title", widget.title);
            p.setProperty(prefix + "fields", String.join(",", widget.fields));
            p.setProperty(prefix + "fieldsConfigured", Boolean.toString(widget.fieldsConfigured));
            p.setProperty(prefix + "displayColumns", Integer.toString(widget.displayColumns));
            DeviceBinding b = widget.device;
            if (b != null) {
                p.setProperty(prefix + "source", b.source.toString());
                p.setProperty(prefix + "key", b.key);
                p.setProperty(prefix + "dimension", Integer.toString(b.dimension));
                p.setProperty(prefix + "x", Integer.toString(b.x));
                p.setProperty(prefix + "y", Integer.toString(b.y));
                p.setProperty(prefix + "z", Integer.toString(b.z));
                p.setProperty(prefix + "meta", Integer.toString(b.meta));
            }
        }
        return p;
    }

    public static DesktopLayout decode(Properties p) {
        if (!p.containsKey("schema") || !p.containsKey("count")) return DesktopLayout.defaultLayout();
        int count;
        try {
            if (Integer.parseInt(p.getProperty("schema")) < 1) return DesktopLayout.defaultLayout();
            count = Math.max(0, Math.min(DesktopLayout.MAX_WIDGETS, Integer.parseInt(p.getProperty("count"))));
        } catch (IllegalArgumentException ignored) {
            return DesktopLayout.defaultLayout();
        }
        DesktopLayout result = new DesktopLayout();
        for (int index = 0; index < count; index++) {
            String prefix = "widget." + index + ".";
            try {
                WidgetKind kind = WidgetKind.valueOf(p.getProperty(prefix + "kind", ""));
                DeviceBinding binding = kind == WidgetKind.DEVICE
                    ? new DeviceBinding(
                        UUID.fromString(p.getProperty(prefix + "source", "")),
                        p.getProperty(prefix + "key"),
                        integer(p, prefix + "dimension"),
                        integer(p, prefix + "x"),
                        integer(p, prefix + "y"),
                        integer(p, prefix + "z"),
                        integer(p, prefix + "meta"))
                    : null;
                WidgetSpec widget = new WidgetSpec(
                    p.getProperty(prefix + "id"),
                    kind,
                    integer(p, prefix + "column"),
                    integer(p, prefix + "row"),
                    integer(p, prefix + "columns"),
                    integer(p, prefix + "rows"),
                    p.getProperty(prefix + "title", ""),
                    binding,
                    Arrays.asList(
                        p.getProperty(prefix + "fields", "")
                            .split(",", -1)),
                    (int) number(p, prefix + "displayColumns", 1),
                    Boolean.parseBoolean(
                        p.getProperty(
                            prefix + "fieldsConfigured",
                            Boolean.toString(
                                !p.getProperty(prefix + "fields", "")
                                    .isEmpty()))));
                result.add(widget);
            } catch (IllegalArgumentException ignored) { /* Keep valid siblings. */ }
        }
        return result.widgets()
            .isEmpty() && count > 0 ? DesktopLayout.defaultLayout() : result;
    }

    private static int integer(Properties p, String key) {
        return Integer.parseInt(p.getProperty(key, ""));
    }

    private static double number(Properties p, String key, double fallback) {
        try {
            double value = Double.parseDouble(p.getProperty(key, ""));
            return Double.isFinite(value) ? value : fallback;
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
