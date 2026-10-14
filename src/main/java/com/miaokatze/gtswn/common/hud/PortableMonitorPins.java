package com.miaokatze.gtswn.common.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fixed subscriptions: titles, plots and values are independently selectable. */
public final class PortableMonitorPins {

    public static final String EU_TITLE = "$hud:eu:title", EU_PLOT = "$hud:eu:graph";
    public static final String AE_TITLE = "$hud:ae:title", AE_PLOT = "$hud:ae:graph";
    public static final String LEGACY_EU = "$chart:eu", LEGACY_AE = "$chart:ae";
    private static final String STOCK_INSTANT = "$stock:instant:", STOCK_AVERAGE = "$stock:average:";

    private PortableMonitorPins() {}

    public static String title(int tab) {
        return tab == 0 ? EU_TITLE : AE_TITLE;
    }

    public static String plot(int tab) {
        return tab == 0 ? EU_PLOT : AE_PLOT;
    }

    public static String legacy(int tab) {
        return tab == 0 ? LEGACY_EU : LEGACY_AE;
    }

    public static boolean component(String key) {
        return EU_TITLE.equals(key) || EU_PLOT.equals(
            key) || AE_TITLE.equals(key) || AE_PLOT.equals(key) || LEGACY_EU.equals(key) || LEGACY_AE.equals(key);
    }

    public static boolean selected(String[] entries, String prefix, String key) {
        List<String> pins = Arrays.asList(entries);
        if (pins.contains(prefix + key)) return true;
        if (key.startsWith(STOCK_INSTANT)) return pins.contains(prefix + requestKey(key));
        if (stockRow(key)) return pins.contains(prefix + stock(key, false)) || pins.contains(prefix + stock(key, true));
        if (EU_TITLE.equals(key) || EU_PLOT.equals(key)) return pins.contains(prefix + LEGACY_EU);
        if (AE_TITLE.equals(key) || AE_PLOT.equals(key)) return pins.contains(prefix + LEGACY_AE);
        return false;
    }

    public static String stock(String key, boolean average) {
        return (average ? STOCK_AVERAGE : STOCK_INSTANT) + key;
    }

    /** Field choices are local; transport still subscribes once to the native AE target. */
    public static String requestKey(String key) {
        if (key.startsWith(STOCK_INSTANT)) return key.substring(STOCK_INSTANT.length());
        if (key.startsWith(STOCK_AVERAGE)) return key.substring(STOCK_AVERAGE.length());
        return key;
    }

    private static boolean stockRow(String key) {
        return key.startsWith("item:") || key.startsWith("fluid:");
    }

    /** Expand only this panel's touched legacy component; other terminals retain their exact entries. */
    public static String[] toggle(String[] entries, String prefix, String key, int limit) {
        List<String> pins = new ArrayList<>(Arrays.asList(entries));
        String target = requestKey(key);
        if (!target.equals(key) && stockRow(target)) {
            // Legacy whole-row subscriptions displayed instant rate. Migrate only the touched target.
            if (pins.remove(prefix + target) && !pins.contains(prefix + stock(target, false)))
                pins.add(prefix + stock(target, false));
            if (!pins.remove(prefix + key)) pins.add(prefix + key);
            return pins.size() > limit ? null : pins.toArray(new String[0]);
        }
        if (EU_TITLE.equals(key) || EU_PLOT.equals(key) || AE_TITLE.equals(key) || AE_PLOT.equals(key)) {
            int tab = key.startsWith("$hud:eu:") ? 0 : 1;
            if (pins.remove(prefix + legacy(tab))) {
                String retained = prefix + (key.equals(title(tab)) ? plot(tab) : title(tab));
                if (!pins.contains(retained)) pins.add(retained);
                pins.remove(prefix + key);
                return pins.toArray(new String[0]);
            }
        }
        if (!pins.remove(prefix + key)) {
            if (pins.size() >= limit) return null;
            pins.add(prefix + key);
        }
        return pins.toArray(new String[0]);
    }
}
