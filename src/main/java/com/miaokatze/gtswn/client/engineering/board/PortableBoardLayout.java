package com.miaokatze.gtswn.client.engineering.board;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Bounded local preferences. Neither rows nor server credentials are persisted. */
public final class PortableBoardLayout {

    public int columns, size = 1, window;
    public boolean overview = true, eu = true, ae = true, stock = true;
    public boolean amount = true, instant = true, average = true, trends = true;
    public final List<String> order = new ArrayList<>(), hidden = new ArrayList<>();

    public static String identity(String scope, String terminal, List<String> panels) {
        List<String> sorted = new ArrayList<>(panels);
        Collections.sort(sorted);
        // Length framing prevents separators in world/server names from aliasing another source.
        StringBuilder raw = new StringBuilder();
        append(raw, scope);
        append(raw, terminal);
        for (String panel : sorted) append(raw, panel);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(
                    raw.toString()
                        .getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : digest) key.append(String.format("%02x", value & 255));
            return key.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void append(StringBuilder out, String value) {
        out.append(value.length())
            .append(':')
            .append(value);
    }

    public int effectiveColumns(int width) {
        int fit = Math.max(1, Math.min(4, width / 260));
        return columns == 0 ? fit : Math.max(1, Math.min(fit, columns));
    }

    public void reconcile(List<String> panels) {
        order.removeIf(id -> !panels.contains(id));
        hidden.removeIf(id -> !panels.contains(id));
        for (String panel : panels) if (!order.contains(panel)) order.add(panel);
        while (order.size() > 16) order.remove(order.size() - 1);
        while (hidden.size() > 16) hidden.remove(hidden.size() - 1);
    }

    public void move(String id, int delta) {
        int at = order.indexOf(id), to = at + delta;
        if (at >= 0 && to >= 0 && to < order.size()) Collections.swap(order, at, to);
    }

    /**
     * Foreground priority is bounded; unsuccessful attempts also age so an unloaded screen cannot monopolize polling.
     */
    public String nextSource(List<String> panels, List<String> visible, Map<String, Long> attempted) {
        long oldest = Long.MAX_VALUE;
        String selected = null;
        for (String id : panels) {
            if (hidden.contains(id)) continue;
            long score = attempted.getOrDefault(id, 0L);
            if (visible.contains(id)) score -= Math.max(1100L, panels.size() * 550L);
            if (score < oldest) {
                oldest = score;
                selected = id;
            }
        }
        return selected;
    }

    public Properties write() {
        Properties p = new Properties();
        p.setProperty("columns", Integer.toString(columns));
        p.setProperty("size", Integer.toString(size));
        p.setProperty("window", Integer.toString(window));
        p.setProperty("order", String.join(",", order));
        p.setProperty("hidden", String.join(",", hidden));
        p.setProperty("overview", Boolean.toString(overview));
        p.setProperty("eu", Boolean.toString(eu));
        p.setProperty("ae", Boolean.toString(ae));
        p.setProperty("stock", Boolean.toString(stock));
        p.setProperty("amount", Boolean.toString(amount));
        p.setProperty("instant", Boolean.toString(instant));
        p.setProperty("average", Boolean.toString(average));
        p.setProperty("trends", Boolean.toString(trends));
        return p;
    }

    public static PortableBoardLayout read(Properties p, List<String> panels) {
        PortableBoardLayout l = new PortableBoardLayout();
        l.columns = number(p, "columns", 0, 4, 0);
        l.size = number(p, "size", 0, 2, 1);
        l.window = number(p, "window", 0, 2, 0);
        l.overview = flag(p, "overview");
        l.eu = flag(p, "eu");
        l.ae = flag(p, "ae");
        l.stock = flag(p, "stock");
        l.amount = flag(p, "amount");
        l.instant = flag(p, "instant");
        l.average = flag(p, "average");
        l.trends = flag(p, "trends");
        for (String id : p.getProperty("order", "")
            .split(",")) if (panels.contains(id) && !l.order.contains(id)) l.order.add(id);
        for (String id : p.getProperty("hidden", "")
            .split(",")) if (panels.contains(id) && !l.hidden.contains(id)) l.hidden.add(id);
        l.reconcile(panels);
        return l;
    }

    private static int number(Properties p, String key, int min, int max, int fallback) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean flag(Properties p, String key) {
        return !"false".equals(p.getProperty(key));
    }
}
