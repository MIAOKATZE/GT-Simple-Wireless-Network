package com.miaokatze.gtswn.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Screen-space EU clustering. No world reads, networking, GL state or floating-point energy sums. */
final class DevicePowerClusterLayout {

    static final int MAX_POINTS = 512;
    private static final int CELL = 8, MERGE_PASSES = 4;
    private static final String[] METRIC_UNITS = { "", "K", "M", "G", "T", "P", "E", "Z", "Y" };
    private final Map<String, String> previous = new HashMap<>();

    enum Reading {
        CONFIRMED,
        UNKNOWN,
        UNAVAILABLE,
        IGNORED
    }

    static final class Point {

        final String key, detail;
        final double x, y;
        final long input, output;
        final Reading reading;
        final int width, height;

        Point(String key, double x, double y, long input, long output, Reading reading, String detail, int width,
            int height) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.input = input;
            this.output = output;
            this.reading = reading;
            this.detail = detail;
            this.width = width;
            this.height = height;
        }
    }

    static final class Group {

        final List<Point> points = new ArrayList<>();
        BigInteger input = BigInteger.ZERO, output = BigInteger.ZERO;
        int confirmed, unknown, unavailable, ignored;
        double x, y;
        int left, top, width, height;
        boolean remainder;
        String[] lines;

        void add(Point point) {
            points.add(point);
            x += point.x;
            y += point.y;
            switch (point.reading) {
                case CONFIRMED:
                    input = input.add(BigInteger.valueOf(point.input));
                    output = output.add(BigInteger.valueOf(point.output));
                    confirmed++;
                    break;
                case UNKNOWN:
                    unknown++;
                    break;
                case UNAVAILABLE:
                    unavailable++;
                    break;
                case IGNORED:
                    ignored++;
                    break;
            }
        }

        double centerX() {
            return x / points.size();
        }

        double centerY() {
            return y / points.size();
        }
    }

    interface Measure {

        /** Populate lines, width and height. Width must be bounded by the supplied viewport width. */
        void size(Group group, int availableWidth);
    }

    static final class Bounds {

        final int left, top, right, bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    static final class Result {

        final List<Group> groups = new ArrayList<>();
        int included, duplicates, outside, overflow, gridVisits;
    }

    void clear() {
        previous.clear();
    }

    /** Metric rounding is decimal too: a double conversion can move values across a rounding boundary. */
    static String formatTotal(BigInteger value) {
        if (value.abs()
            .compareTo(BigInteger.valueOf(1000)) < 0) return value.toString();
        int unit = Math.min(
            METRIC_UNITS.length - 1,
            (value.abs()
                .toString()
                .length() - 1) / 3);
        return new BigDecimal(value).movePointLeft(unit * 3)
            .setScale(1, RoundingMode.HALF_UP)
            .toPlainString() + METRIC_UNITS[unit];
    }

    Result layout(List<Point> source, Bounds bounds, int budget, Measure measure) {
        Result result = new Result();
        if (bounds.right - bounds.left < 24 || bounds.bottom - bounds.top < 36 || budget <= 0) return result;
        List<Point> points = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int work = 0;
        for (Point point : source) {
            if (++work > MAX_POINTS) {
                result.overflow = source.size() - MAX_POINTS;
                break;
            }
            if (!seen.add(point.key)) {
                result.duplicates++;
                continue;
            }
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !bounds.contains(point.x, point.y)) {
                result.outside++;
                continue;
            }
            points.add(point);
        }
        points.sort(Comparator.comparing(point -> point.key));
        result.included = points.size();
        List<Group> groups = new ArrayList<>();
        for (Point point : points) {
            Group group = new Group();
            group.add(point);
            measure.size(group, bounds.right - bounds.left);
            groups.add(group);
        }
        // A small extra release margin for former neighbours stops repeated merge/split near a zoom threshold.
        groups = merge(groups, bounds, measure, true, result);
        for (int pass = 0; pass < MERGE_PASSES; pass++) {
            List<Group> merged = merge(groups, bounds, measure, false, result);
            if (merged.size() == groups.size()) break;
            groups = merged;
        }
        previous.clear();
        for (Group group : groups) {
            if (group.points.size() < 2) continue;
            String identity = group.points.get(0).key;
            for (Point point : group.points) previous.put(point.key, identity);
        }
        // The small footer is reserved only when needed; rejected groups are still represented with exact totals.
        place(groups, bounds, Math.min(64, budget), measure, result, false);
        if (result.groups.size() < groups.size()) {
            result.groups.clear();
            place(groups, bounds, Math.min(64, budget), measure, result, true);
        }
        return result;
    }

    private List<Group> merge(List<Group> groups, Bounds bounds, Measure measure, boolean hysteresis, Result result) {
        int[] roots = new int[groups.size()];
        for (int i = 0; i < roots.length; i++) roots[i] = i;
        Map<String, Map<Long, Integer>> oldGrids = new HashMap<>();
        Map<Long, Integer> grid = new HashMap<>();
        boolean changed = false;
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            Map<Long, Integer> active = grid;
            int margin = 2;
            if (hysteresis) {
                String old = previous.get(group.points.get(0).key);
                if (old == null) continue;
                active = oldGrids.computeIfAbsent(old, key -> new HashMap<>());
                margin = 6;
            }
            int left = Math.max(bounds.left, (int) Math.floor(group.centerX() - group.width / 2D) - margin);
            int top = Math.max(bounds.top, (int) Math.floor(group.centerY() - group.height) - margin);
            int right = Math.min(bounds.right - 1, left + group.width + margin * 2);
            int bottom = Math.min(bounds.bottom - 1, top + group.height + margin * 2);
            for (int y = Math.floorDiv(top, CELL); y <= Math.floorDiv(bottom, CELL); y++) {
                for (int x = Math.floorDiv(left, CELL); x <= Math.floorDiv(right, CELL); x++) {
                    result.gridVisits++;
                    long cell = (long) x << 32 ^ y & 0xFFFFFFFFL;
                    Integer other = active.putIfAbsent(cell, i);
                    if (other == null) continue;
                    int a = root(roots, i), b = root(roots, other);
                    if (a != b) {
                        roots[Math.max(a, b)] = Math.min(a, b);
                        changed = true;
                    }
                }
            }
        }
        if (!changed) return groups;
        Map<Integer, Group> merged = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            Group group = merged.computeIfAbsent(root(roots, i), key -> new Group());
            for (Point point : groups.get(i).points) group.add(point);
        }
        for (Group group : merged.values()) measure.size(group, bounds.right - bounds.left);
        return new ArrayList<>(merged.values());
    }

    private static int root(int[] roots, int index) {
        while (index != roots[index]) {
            roots[index] = roots[roots[index]];
            index = roots[index];
        }
        return index;
    }

    private static void place(List<Group> groups, Bounds bounds, int budget, Measure measure, Result result,
        boolean footer) {
        Group remainder = new Group();
        remainder.remainder = true;
        // Up to three summary lines; width-aware text wrapping can increase this on a narrow viewport.
        if (footer) {
            for (Group group : groups) for (Point point : group.points) remainder.add(point);
            measure.size(remainder, bounds.right - bounds.left);
        }
        int bottom = footer ? bounds.bottom - remainder.height - 4 : bounds.bottom;
        int labelLimit = footer ? budget - 1 : budget;
        List<Point> rejected = new ArrayList<>();
        for (Group group : groups) {
            boolean placed = false;
            if (result.groups.size() < labelLimit && group.height <= bottom - bounds.top) {
                // Nine local positions, bounded by 64 labels; no pairwise device scan.
                for (int attempt = 0; attempt < 9 && !placed; attempt++) {
                    int dx = attempt == 0 ? 0 : ((attempt - 1) % 3 - 1) * (group.width / 2 + 4);
                    int dy = attempt == 0 ? 0 : ((attempt - 1) / 3 - 1) * (group.height + 4);
                    group.left = Math.max(
                        bounds.left,
                        Math.min(
                            bounds.right - group.width,
                            (int) Math.round(group.centerX() - group.width / 2D) + dx));
                    group.top = Math.max(
                        bounds.top,
                        Math.min(bottom - group.height, (int) Math.round(group.centerY() - group.height - 2) + dy));
                    placed = true;
                    for (Group accepted : result.groups) {
                        if (group.left < accepted.left + accepted.width + 3
                            && group.left + group.width + 3 > accepted.left
                            && group.top < accepted.top + accepted.height + 3
                            && group.top + group.height + 3 > accepted.top) {
                            placed = false;
                            break;
                        }
                    }
                }
            }
            if (placed) result.groups.add(group);
            else rejected.addAll(group.points);
        }
        if (footer && !rejected.isEmpty()) {
            remainder = new Group();
            remainder.remainder = true;
            for (Point point : rejected) remainder.add(point);
            measure.size(remainder, bounds.right - bounds.left);
            remainder.left = bounds.left;
            remainder.top = Math.max(bounds.top, bounds.bottom - remainder.height);
            result.groups.add(remainder);
        }
    }
}
