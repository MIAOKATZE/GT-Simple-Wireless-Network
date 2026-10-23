package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.EnumMap;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.config.Configuration;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/** Client-only camera multipliers. Changes apply to the next camera policy sample and survive restarts. */
public final class FactoryControlSettings {

    public enum Setting {

        KEYBOARD_PAN,
        MOUSE_PAN,
        ROTATION,
        WHEEL_ZOOM;

        public double minimum() {
            return .25;
        }

        public double maximum() {
            return 4;
        }

        public double defaultValue() {
            return 1;
        }

        double sanitize(double value) {
            return !Double.isFinite(value) || value <= 0 ? defaultValue()
                : Math.max(minimum(), Math.min(maximum(), value));
        }
    }

    public enum Preset {

        PRECISE(.5),
        NORMAL(1),
        FAST(2);

        private final double multiplier;

        Preset(double multiplier) {
            this.multiplier = multiplier;
        }
    }

    private static Store store = new Store(null);
    private static boolean registered;

    private FactoryControlSettings() {}

    static void register() {
        if (registered) return;
        registered = true;
        store = new Store(new File(Minecraft.getMinecraft().mcDataDir, "config/gtswn/factory_controls_client.cfg"));
    }

    public static double value(Setting setting) {
        return store.value(setting);
    }

    public static void set(Setting setting, double value) {
        if (setting != null) store.set(setting, value);
    }

    /** One +/- step is one quarter of the default sensitivity. */
    public static void adjust(Setting setting, int steps) {
        if (setting != null)
            set(setting, Math.max(setting.minimum(), Math.min(setting.maximum(), value(setting) + steps * .25)));
    }

    public static void resetDefaults() {
        applyPreset(Preset.NORMAL);
    }

    public static void applyPreset(Preset preset) {
        if (preset != null) store.preset(preset.multiplier);
    }

    static long revision() {
        return store.revision;
    }

    /** File-bound state is separate from Minecraft so persistence and invalid inputs can be tested directly. */
    static final class Store {

        private final File file;
        private final EnumMap<Setting, Double> values = new EnumMap<>(Setting.class);
        private long revision;

        Store(File file) {
            this.file = file;
            for (Setting setting : Setting.values()) values.put(setting, setting.defaultValue());
            if (file == null || !file.isFile()) return;
            try {
                Configuration configuration = new Configuration(file);
                configuration.load();
                for (Setting setting : Setting.values()) values.put(
                    setting,
                    setting.sanitize(
                        configuration.get("sensitivity", name(setting), setting.defaultValue())
                            .getDouble(setting.defaultValue())));
            } catch (RuntimeException e) {
                GTSimpleWirelessNetwork.LOG.error("Could not load factory camera settings", e);
            }
        }

        double value(Setting setting) {
            return values.get(setting);
        }

        void set(Setting setting, double value) {
            double safe = setting.sanitize(value);
            if (Double.compare(values.get(setting), safe) == 0) return;
            values.put(setting, safe);
            revision++;
            save();
        }

        void preset(double multiplier) {
            boolean changed = false;
            for (Setting setting : Setting.values()) {
                double safe = setting.sanitize(multiplier);
                changed |= Double.compare(values.put(setting, safe), safe) != 0;
            }
            if (!changed) return;
            revision++;
            save();
        }

        private void save() {
            if (file == null) return;
            try {
                Configuration configuration = new Configuration(file);
                for (Setting setting : Setting.values())
                    configuration.get("sensitivity", name(setting), setting.defaultValue())
                        .set(value(setting));
                configuration.save();
            } catch (RuntimeException e) {
                GTSimpleWirelessNetwork.LOG.error("Could not save factory camera settings", e);
            }
        }

        private static String name(Setting setting) {
            return setting.name()
                .toLowerCase(Locale.ROOT);
        }
    }
}
