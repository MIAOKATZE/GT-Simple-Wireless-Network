package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.config.Configuration;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;

/** Client preferences only. No world positions, item NBT, terminal identity or machine samples. */
public final class FactoryDisplayPreferences {

    enum Key {

        SIDEBAR(0),
        RAIL(1),
        POWER(1),
        DASHBOARD(1),
        TASK(0, 0, 3),
        DASHBOARD_PRESET(0, 0, 2),
        DASHBOARD_CARDS(15, 0, 15),
        DASHBOARD_WIDE(1),
        DASHBOARD_REVERSE(0),
        ALERTS(1),
        SETTINGS(0),
        SELECTED_SLOT(-1, -1, 7),
        PAGE(0, 0, 3),
        SIDEBAR_SCROLL(0, 0, 100000),
        RAIL_SCROLL(0, 0, 100000),
        DETAIL_SCROLL(0, 0, 100000),
        SETTINGS_PAGE(0, 0, 1),
        SETTINGS_SCROLL(0, 0, 100000),
        MACHINE_FILTER(0, 0, 7),
        POWER_FILTER(0, 0, 2),
        ACTIVE_SLOT(-1, -1, 7),
        OVERVIEW(1),
        SUSPENDED(0),
        GRID(0, 0, 2),
        BOUND_ONLY(0),
        AUDIT(0),
        NODE_1(0),
        NODE_3(0),
        NODE_4(0),
        NODE_5(0),
        NODE_6(0),
        NODE_7(0),
        YAW(45, -360, 360),
        PITCH(60, 15, 85),
        ZOOM(1, .025, 8),
        FOLLOWING(0),
        CAMERA_PANEL(0),
        DEVICE_VISUALIZATION(0);

        final double initial, min, max;

        Key(double initial) {
            this(initial, 0, 1);
        }

        Key(double initial, double min, double max) {
            this.initial = initial;
            this.min = min;
            this.max = max;
        }

        double sanitize(double value) {
            if (!Double.isFinite(value)) return initial;
            value = Math.max(min, Math.min(max, value));
            return this == YAW || this == PITCH || this == ZOOM ? value : Math.rint(value);
        }
    }

    private static Store store = new Store(null);
    private static boolean registered;

    private FactoryDisplayPreferences() {}

    static void register() {
        if (registered) return;
        registered = true;
        store = new Store(new File(Minecraft.getMinecraft().mcDataDir, "config/gtswn/factory_display_client.cfg"));
    }

    static int integer(Key key) {
        return (int) store.number(key);
    }

    static double number(Key key) {
        return store.number(key);
    }

    static boolean flag(Key key) {
        return integer(key) != 0;
    }

    static void put(Key key, double value) {
        store.put(key, value);
    }

    static void put(Key key, boolean value) {
        put(key, value ? 1 : 0);
    }

    static String text(String key) {
        return store.text(key);
    }

    static void text(String key, String value) {
        store.text(key, value);
    }

    static void save() {
        store.save();
    }

    static void saveIfDue() {
        store.saveIfDue(System.nanoTime());
    }

    public static boolean deviceVisualization() {
        return flag(Key.DEVICE_VISUALIZATION);
    }

    public static void toggleDeviceVisualization() {
        put(Key.DEVICE_VISUALIZATION, !deviceVisualization());
        save();
    }

    static Key nodes(int slot) {
        return slot == 1 ? Key.NODE_1 : slot >= 3 && slot <= 7 ? Key.values()[Key.NODE_3.ordinal() + slot - 3] : null;
    }

    /** Only absolute slicing is world/player/dimension scoped; portable UI and orientation are global preferences. */
    static String currentSliceProfile() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return "";
        String world = mc.isSingleplayer() && mc.getIntegratedServer() != null ? "local:" + mc.getIntegratedServer()
            .getFolderName() : mc.func_147104_D() == null ? "" : "server:" + mc.func_147104_D().serverIP;
        if (world.isEmpty()) return "";
        return profile(
            world,
            mc.thePlayer.getUniqueID()
                .toString(),
            mc.theWorld.provider.dimensionId);
    }

    static String profile(String world, String player, int dimension) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((world + "\n" + player + "\n" + dimension).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("slice_");
            for (byte part : digest) result.append(String.format(Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String encode(HeightSlice slice) {
        if (slice == null || !slice.enabled()) return "";
        return (slice.hasLower() ? "1" : "0") + ","
            + (slice.hasUpper() ? "1" : "0")
            + ","
            + slice.minimumY()
            + ","
            + slice.maximumY()
            + ","
            + slice.featherWidth()
            + ","
            + slice.featheredCaps()
            + ","
            + slice.lightCompensation();
    }

    static HeightSlice decode(String value) {
        try {
            String[] fields = value.split(",", -1);
            if (fields.length != 7) return HeightSlice.disabled();
            boolean lower = "1".equals(fields[0]), upper = "1".equals(fields[1]);
            int min = Integer.parseInt(fields[2]), max = Integer.parseInt(fields[3]);
            double feather = Double.parseDouble(fields[4]);
            if (min < 0 || min > 256
                || max < 0
                || max > 256
                || !Double.isFinite(feather)
                || feather < 0
                || feather > 256
                || !lower && !upper) return HeightSlice.disabled();
            HeightSlice slice = lower && upper ? HeightSlice.between(min, max, feather)
                : lower ? HeightSlice.above(min, feather) : HeightSlice.below(max, feather);
            return slice.withFeatheredCaps(Boolean.parseBoolean(fields[5]))
                .withLightCompensation(Boolean.parseBoolean(fields[6]));
        } catch (IllegalArgumentException invalid) {
            return HeightSlice.disabled();
        }
    }

    static final class Store {

        private final File file;
        private final EnumMap<Key, Double> values = new EnumMap<>(Key.class);
        private final Map<String, String> strings = new HashMap<>();
        private boolean dirty;
        private long lastSaveNanos = Long.MIN_VALUE;

        Store(File file) {
            this.file = file;
            for (Key key : Key.values()) values.put(key, key.initial);
            if (file == null || !file.isFile()) return;
            try {
                Configuration config = new Configuration(file);
                config.load();
                for (Key key : Key.values()) values.put(
                    key,
                    key.sanitize(
                        config.get("display", name(key), key.initial)
                            .getDouble(key.initial)));
                for (String key : config.getCategory("state")
                    .keySet())
                    strings.put(
                        key,
                        config.get("state", key, "")
                            .getString());
            } catch (RuntimeException invalid) {
                GTSimpleWirelessNetwork.LOG.error("Could not load factory display preferences", invalid);
            }
        }

        double number(Key key) {
            return values.get(key);
        }

        void put(Key key, double value) {
            double safe = key.sanitize(value);
            if (Double.compare(values.get(key), safe) == 0) return;
            values.put(key, safe);
            dirty = true;
        }

        String text(String key) {
            return strings.getOrDefault(key, "");
        }

        void text(String key, String value) {
            if (key == null || value == null || value.equals(text(key))) return;
            strings.put(key, value);
            dirty = true;
        }

        boolean dirty() {
            return dirty;
        }

        void saveIfDue(long now) {
            if (!dirty || lastSaveNanos != Long.MIN_VALUE && now - lastSaveNanos < 1_000_000_000L) return;
            lastSaveNanos = now;
            save();
        }

        void save() {
            if (!dirty || file == null) return;
            try {
                Configuration config = new Configuration(file);
                for (Key key : Key.values()) config.get("display", name(key), key.initial)
                    .set(number(key));
                for (Map.Entry<String, String> entry : strings.entrySet()) config.get("state", entry.getKey(), "")
                    .set(entry.getValue());
                config.save();
                dirty = false;
            } catch (RuntimeException invalid) {
                GTSimpleWirelessNetwork.LOG.error("Could not save factory display preferences", invalid);
            }
        }

        private static String name(Key key) {
            return key.name()
                .toLowerCase(Locale.ROOT);
        }
    }
}
