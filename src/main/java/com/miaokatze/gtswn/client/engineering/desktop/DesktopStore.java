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
import java.util.List;
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
        Properties saved = read(profile);
        DesktopLayout layout = decode(saved);
        if (untouchedLegacyDefault(layout) && untouchedLegacyProperties(saved, layout)) {
            layout = migratedDefault(layout);
            put(profile, layout);
        }
        return layout;
    }

    /** Decoding may discard malformed optional values; their original presence must still prevent replacement. */
    private static boolean untouchedLegacyProperties(Properties saved, DesktopLayout layout) {
        Properties canonical = encode(layout);
        for (String key : saved.stringPropertyNames()) {
            if (key.equals("view.x") || key.equals("view.y") || key.equals("view.scale")) continue;
            if (!saved.getProperty(key)
                .equals(canonical.getProperty(key))) return false;
        }
        return true;
    }

    /** Only shipped display templates migrate; operational references do not customize their appearance. */
    static boolean untouchedLegacyDefault(DesktopLayout layout) {
        if (matchesDisplay(layout, previousOverview()) || matchesDisplay(layout, v218Overview())
            || matchesDisplay(layout, v219Overview())) return true;
        String[] ids = { "machines", "input", "output", "wireless", "history", "region", "resources", "attention" };
        WidgetKind[] kinds = { WidgetKind.MACHINES, WidgetKind.INPUT, WidgetKind.OUTPUT, WidgetKind.WIRELESS,
            WidgetKind.HISTORY, WidgetKind.REGION, WidgetKind.RESOURCES, WidgetKind.ATTENTION };
        int[][] compact = { { 0, 0, 4, 3 }, { 0, 3, 4, 2 }, { 4, 3, 4, 2 }, { 4, 0, 4, 3 }, { 8, 0, 8, 4 },
            { 0, 5, 4, 3 }, { 8, 4, 8, 4 }, { 4, 5, 4, 3 } };
        int[][] tall = { { 0, 0, 4, 2 }, { 4, 0, 4, 2 }, { 8, 0, 4, 2 }, { 12, 0, 4, 2 }, { 0, 2, 10, 5 },
            { 10, 2, 6, 5 }, { 0, 7, 10, 7 }, { 10, 7, 6, 7 } };
        if (layout.widgets()
            .size() != ids.length) return false;
        boolean matchesCompact = true, matchesTall = true;
        for (int index = 0; index < ids.length; index++) {
            WidgetSpec widget = layout.find(ids[index]);
            if (widget == null || widget.kind != kinds[index]
                || !widget.title.isEmpty()
                || !widget.fields.isEmpty()
                || widget.fieldsConfigured
                || widget.displayColumns != 1
                || widget.device != null
                || widget.userTarget != null
                || !widget.averageRate
                || widget.averageWindowSeconds != 30
                || widget.autoResourcePending
                || widget.singleResource != (widget.resource != null)
                || widget.borderColor != WidgetSpec.DEFAULT_BORDER_COLOR
                || !defaultEffects(widget.effects)
                || widget.autoDevicePending
                || widget.presentation != WidgetPresentation.DEFAULT) return false;
            List<String> metrics = WidgetSpec.availableMetrics(widget.kind);
            if (!widget.metricField.equals(metrics.isEmpty() ? "" : metrics.get(0))) return false;
            matchesCompact &= geometryMatches(widget, compact[index]);
            matchesTall &= geometryMatches(widget, tall[index]);
        }
        return matchesCompact || matchesTall;
    }

    private static boolean matchesDisplay(DesktopLayout layout, DesktopLayout template) {
        return displayPreferences(layout, template).equals(displayPreferences(template, template));
    }

    private static Properties displayPreferences(DesktopLayout layout, DesktopLayout template) {
        Properties display = encode(layout);
        for (int index = 0; index < layout.widgets()
            .size(); index++) {
            String prefix = "widget." + index + ".";
            for (String suffix : new String[] { "source", "key", "dimension", "x", "y", "z", "meta", "resource.source",
                "resource.screen", "resource.kind", "resource.key", "resource.name", "screen.source", "screen.id",
                "autoResourcePending", "autoDevicePending" }) display.remove(prefix + suffix);
            WidgetSpec widget = layout.widgets()
                .get(index);
            WidgetSpec original = template.find(widget.id);
            // Selecting an exact row implicitly switches mode; compare that operational consequence
            // with the frozen template, while retaining explicit unbound display-mode changes.
            boolean singleResource = widget.resource != null && original != null ? original.singleResource
                : widget.singleResource;
            display.setProperty(prefix + "singleResource", Boolean.toString(singleResource));
        }
        return display;
    }

    /** Retain exact references by stable component ID while replacing only the untouched shipped display. */
    private static DesktopLayout migratedDefault(DesktopLayout previous) {
        DesktopLayout migrated = DesktopLayout.defaultLayout();
        for (WidgetSpec widget : migrated.widgets()) {
            WidgetSpec old = previous.find(widget.id);
            if (old == null) continue;
            WidgetSpec retained = new WidgetSpec(
                widget.id,
                widget.kind,
                widget.column,
                widget.row,
                widget.columns,
                widget.rows,
                widget.title,
                old.device,
                widget.fields,
                widget.displayColumns,
                widget.fieldsConfigured,
                widget.presentation,
                widget.metricField,
                widget.userTarget,
                widget.borderColor,
                old.resource,
                old.screen).withRate(old.averageRate, old.averageWindowSeconds)
                    .withAutoResource(
                        widget.autoResourcePending && old.autoResourcePending
                            && old.resource == null
                            && old.screen == null)
                    .withSingleResource(old.singleResource)
                    .withAutoDevice(old.autoDevicePending)
                    .styled(widget.effects);
            if (!migrated.replace(retained)) throw new IllegalStateException("Invalid migrated widget: " + widget.id);
        }
        // A previous template may contain operational cards removed from the new visual arrangement.
        // Retain those exact cards below it rather than losing an explicitly chosen source.
        for (WidgetSpec old : previous.widgets()) {
            if (migrated.find(old.id) != null || old.device == null && old.resource == null && old.screen == null)
                continue;
            int bottom = 0;
            for (WidgetSpec widget : migrated.widgets()) bottom = Math.max(bottom, widget.row + widget.rows);
            if (!migrated.add(old.withBounds(old.column, bottom, old.columns, old.rows)))
                throw new IllegalStateException("Invalid retained widget: " + old.id);
        }
        return migrated;
    }

    /** Frozen v2.0.17 template, kept independent of future presets for precise opt-in-free migration. */
    private static DesktopLayout previousOverview() {
        String[] ids = { "machines", "wireless", "netRate", "region", "history", "resources", "attention", "device" };
        WidgetKind[] kinds = { WidgetKind.MACHINES, WidgetKind.WIRELESS, WidgetKind.WIRELESS, WidgetKind.REGION,
            WidgetKind.HISTORY, WidgetKind.RESOURCES, WidgetKind.ATTENTION, WidgetKind.DEVICE };
        WidgetPresentation[] forms = { WidgetPresentation.KPI, WidgetPresentation.KPI, WidgetPresentation.KPI,
            WidgetPresentation.DEFAULT, WidgetPresentation.TREND, WidgetPresentation.STATUS_LIST,
            WidgetPresentation.STATUS_LIST, WidgetPresentation.DEFAULT };
        String[] metrics = { "count", "stored", "netRate", "", "", "", "", "" };
        int[][] bounds = { { 8, 0, 4, 2 }, { 0, 0, 4, 2 }, { 4, 0, 4, 2 }, { 12, 0, 4, 2 }, { 0, 2, 10, 3 },
            { 0, 5, 10, 3 }, { 10, 2, 6, 3 }, { 10, 5, 6, 3 } };
        DesktopLayout layout = new DesktopLayout();
        for (int i = 0; i < ids.length; i++) {
            int[] b = bounds[i];
            layout.add(new WidgetSpec(ids[i], kinds[i], b[0], b[1], b[2], b[3]).presented(forms[i], metrics[i], null));
        }
        return layout;
    }

    /** Frozen v2.0.18 display; never derive an old template from the current preset. */
    private static DesktopLayout v218Overview() {
        String[] ids = { "machines", "wireless", "netRate", "history", "resources", "device" };
        WidgetKind[] kinds = { WidgetKind.MACHINES, WidgetKind.WIRELESS, WidgetKind.WIRELESS, WidgetKind.HISTORY,
            WidgetKind.RESOURCES, WidgetKind.DEVICE };
        WidgetPresentation[] forms = { WidgetPresentation.KPI, WidgetPresentation.GAUGE, WidgetPresentation.KPI,
            WidgetPresentation.COMBO, WidgetPresentation.STATUS_LIST, WidgetPresentation.DEFAULT };
        String[] metrics = { "count", "stored", "netRate", "", "", "" };
        int[][] bounds = { { 0, 0, 5, 2 }, { 10, 0, 6, 5 }, { 5, 0, 5, 2 }, { 0, 2, 10, 3 }, { 0, 5, 10, 3 },
            { 10, 5, 6, 3 } };
        DesktopLayout layout = new DesktopLayout();
        for (int index = 0; index < ids.length; index++) {
            int[] b = bounds[index];
            layout.add(
                new WidgetSpec(ids[index], kinds[index], b[0], b[1], b[2], b[3])
                    .presented(forms[index], metrics[index], null));
        }
        return layout;
    }

    /** Frozen v2.0.19 template, including its four independent resource slots. */
    private static DesktopLayout v219Overview() {
        DesktopLayout layout = v218Overview();
        layout.replace(
            layout.find("resources")
                .withAutoResource(true));
        for (int index = 0; index < 4; index++) {
            WidgetSpec widget = new WidgetSpec("resource" + (index + 1), WidgetKind.RESOURCES, index * 4, 8, 4, 2)
                .presented(WidgetPresentation.STATUS_LIST, "", null)
                .withSingleResource(true)
                .withAutoResource(true);
            if (!layout.add(widget)) throw new IllegalStateException("Invalid frozen resource slot");
        }
        return layout;
    }

    private static boolean geometryMatches(WidgetSpec widget, int[] geometry) {
        return widget.column == geometry[0] && widget.row == geometry[1]
            && widget.columns == geometry[2]
            && widget.rows == geometry[3];
    }

    public DesktopViewport loadView(String profile) {
        Properties p = read(profile);
        DesktopViewport view = new DesktopViewport();
        view.panX = number(p, "view.x", 0);
        view.panY = number(p, "view.y", 0);
        view.scale = number(p, "view.scale", DesktopViewport.DEFAULT_SCALE);
        view.sanitize();
        return view;
    }

    public boolean hasView(String profile) {
        return read(profile).containsKey("view.scale");
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
            if (!widget.averageRate) p.setProperty(prefix + "averageRate", "false");
            if (widget.averageWindowSeconds != 30)
                p.setProperty(prefix + "averageWindowSeconds", Integer.toString(widget.averageWindowSeconds));
            if (widget.autoResourcePending) p.setProperty(prefix + "autoResourcePending", "true");
            if (widget.autoDevicePending) p.setProperty(prefix + "autoDevicePending", "true");
            writeEffects(p, prefix, widget.effects);
            if (widget.singleResource && widget.resource == null) p.setProperty(prefix + "singleResource", "true");
            if (widget.borderColor != WidgetSpec.DEFAULT_BORDER_COLOR)
                p.setProperty(prefix + "borderColor", Integer.toHexString(widget.borderColor & 0xFFFFFF));
            if (widget.resource != null) {
                p.setProperty(prefix + "resource.source", widget.resource.source.toString());
                p.setProperty(prefix + "resource.screen", widget.resource.screen.toString());
                p.setProperty(prefix + "resource.kind", Integer.toString(widget.resource.kind));
                p.setProperty(prefix + "resource.key", widget.resource.key);
                p.setProperty(prefix + "resource.name", widget.resource.name);
            }
            if (widget.screen != null) {
                p.setProperty(prefix + "screen.source", widget.screen.source.toString());
                p.setProperty(prefix + "screen.id", widget.screen.screen.toString());
            }
            // Optional schema-1 additions keep old files and template-empty field semantics intact.
            if (widget.presentation != WidgetPresentation.DEFAULT)
                p.setProperty(prefix + "presentation", widget.presentation.name());
            if (!widget.metricField.isEmpty() && !widget.metricField.equals(
                WidgetSpec.availableMetrics(widget.kind)
                    .get(0)))
                p.setProperty(prefix + "metricField", widget.metricField);
            if (widget.userTarget != null) p.setProperty(prefix + "userTarget", widget.userTarget.toString());
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
                DeviceBinding binding = device(p, prefix, kind);
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
                                    .isEmpty()))),
                    presentation(p, prefix, kind),
                    metric(p, prefix, kind),
                    target(p, prefix, kind),
                    border(p, prefix),
                    resource(p, prefix),
                    screen(p, prefix))
                        .withRate(
                            Boolean.parseBoolean(p.getProperty(prefix + "averageRate", "true")),
                            (int) number(p, prefix + "averageWindowSeconds", 30))
                        .withAutoResource(Boolean.parseBoolean(p.getProperty(prefix + "autoResourcePending", "false")))
                        .withAutoDevice(Boolean.parseBoolean(p.getProperty(prefix + "autoDevicePending", "false")))
                        .styled(effects(p, prefix));
                if (p.containsKey(prefix + "singleResource"))
                    widget = widget.withSingleResource(Boolean.parseBoolean(p.getProperty(prefix + "singleResource")));
                result.add(widget);
            } catch (IllegalArgumentException ignored) { /* Keep valid siblings. */ }
        }
        return result.widgets()
            .isEmpty() && count > 0 ? DesktopLayout.defaultLayout() : result;
    }

    private static int integer(Properties p, String key) {
        return Integer.parseInt(p.getProperty(key, ""));
    }

    private static boolean defaultEffects(WidgetEffects effects) {
        WidgetEffects standard = WidgetEffects.DEFAULT;
        return effects.style == standard.style && effects.animated == standard.animated
            && effects.accentColor == standard.accentColor
            && effects.speed == standard.speed
            && effects.intensity == standard.intensity;
    }

    /** Omit defaults so old schema-1 files remain canonical for precise template migration. */
    private static void writeEffects(Properties p, String prefix, WidgetEffects effects) {
        WidgetEffects standard = WidgetEffects.DEFAULT;
        if (effects.style != standard.style) p.setProperty(prefix + "effects.style", effects.style.name());
        if (effects.animated != standard.animated)
            p.setProperty(prefix + "effects.animated", Boolean.toString(effects.animated));
        if (effects.accentColor != standard.accentColor)
            p.setProperty(prefix + "effects.accent", Integer.toHexString(effects.accentColor & 0xFFFFFF));
        if (effects.speed != standard.speed) p.setProperty(prefix + "effects.speed", Double.toString(effects.speed));
        if (effects.intensity != standard.intensity)
            p.setProperty(prefix + "effects.intensity", Double.toString(effects.intensity));
    }

    private static WidgetEffects effects(Properties p, String prefix) {
        WidgetEffects standard = WidgetEffects.DEFAULT;
        WidgetEffects.Style style = standard.style;
        try {
            style = WidgetEffects.Style.valueOf(p.getProperty(prefix + "effects.style", standard.style.name()));
        } catch (IllegalArgumentException ignored) {}
        String color = p.getProperty(prefix + "effects.accent", "");
        int accent = color.matches("[0-9a-fA-F]{1,6}") ? Integer.parseInt(color, 16) : standard.accentColor;
        double speed = number(p, prefix + "effects.speed", standard.speed);
        double intensity = number(p, prefix + "effects.intensity", standard.intensity);
        return new WidgetEffects(
            style,
            Boolean.parseBoolean(p.getProperty(prefix + "effects.animated", "true")),
            accent,
            speed < 0 || speed > 3 ? standard.speed : speed,
            intensity < 0 || intensity > 1 ? standard.intensity : intensity);
    }

    private static DeviceBinding device(Properties p, String prefix, WidgetKind kind) {
        if (!kind.supportsDeviceBinding() || !p.containsKey(prefix + "key")) return null;
        try {
            return new DeviceBinding(
                UUID.fromString(p.getProperty(prefix + "source", "")),
                p.getProperty(prefix + "key"),
                integer(p, prefix + "dimension"),
                integer(p, prefix + "x"),
                integer(p, prefix + "y"),
                integer(p, prefix + "z"),
                integer(p, prefix + "meta"));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static int border(Properties p, String prefix) {
        String value = p.getProperty(prefix + "borderColor", "");
        return value.matches("[0-9a-fA-F]{1,6}") ? 0xFF000000 | Integer.parseInt(value, 16)
            : WidgetSpec.DEFAULT_BORDER_COLOR;
    }

    private static ResourceBinding resource(Properties p, String prefix) {
        if (!p.containsKey(prefix + "resource.key")) return null;
        try {
            return new ResourceBinding(
                UUID.fromString(p.getProperty(prefix + "resource.source", "")),
                UUID.fromString(p.getProperty(prefix + "resource.screen", "")),
                integer(p, prefix + "resource.kind"),
                p.getProperty(prefix + "resource.key"),
                p.getProperty(prefix + "resource.name", ""));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ScreenBinding screen(Properties p, String prefix) {
        try {
            if (p.containsKey(prefix + "screen.id")) return new ScreenBinding(
                UUID.fromString(p.getProperty(prefix + "screen.source", "")),
                UUID.fromString(p.getProperty(prefix + "screen.id", "")));
            ResourceBinding legacy = resource(p, prefix);
            return legacy == null ? null : new ScreenBinding(legacy.source, legacy.screen);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static WidgetPresentation presentation(Properties p, String prefix, WidgetKind kind) {
        try {
            WidgetPresentation form = WidgetPresentation.valueOf(p.getProperty(prefix + "presentation", "DEFAULT"));
            if (WidgetSpec.availablePresentations(kind)
                .contains(form)) return form;
        } catch (IllegalArgumentException ignored) { /* Future forms keep the existing data source. */ }
        return WidgetPresentation.DEFAULT;
    }

    private static String metric(Properties p, String prefix, WidgetKind kind) {
        String metric = p.getProperty(prefix + "metricField", "");
        if (kind == WidgetKind.DEVICE) {
            if ("input".equals(metric)) metric = "instantInput";
            if ("output".equals(metric)) metric = "instantOutput";
        }
        return WidgetSpec.availableMetrics(kind)
            .contains(metric) ? metric : "";
    }

    private static Double target(Properties p, String prefix, WidgetKind kind) {
        double target = number(p, prefix + "userTarget", 0);
        if (!p.getProperty(prefix + "metricField", "")
            .isEmpty() && metric(p, prefix, kind).isEmpty()) return null;
        return target > 0 && !WidgetSpec.availableMetrics(kind)
            .isEmpty() ? target : null;
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
