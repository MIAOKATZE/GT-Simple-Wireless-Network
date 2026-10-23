package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;

/** A signed, selection-relative scale. Missing coverage never contributes an invented zero. */
final class FactoryPowerPalette {

    static final int UNKNOWN = 0x304B5F6B;
    static final int OUTSIDE = 0x184B5F6B;
    private static final int LOW = 0xFF2878F0, MIDDLE = 0xFFF1E5B5, HIGH = 0xFFF05C25;

    final double min, max;
    final boolean hasValues;
    private final boolean usable;
    private final FactoryPowerSelection.Region region;

    private FactoryPowerPalette(boolean usable, FactoryPowerSelection.Region region, double min, double max) {
        this.usable = usable;
        this.region = region;
        this.min = min;
        this.max = max;
        hasValues = min <= max;
    }

    static FactoryPowerPalette forRegion(EngineeringSnapshot snapshot, boolean fresh,
        FactoryPowerSelection.Region region) {
        boolean usable = snapshot != null && fresh
            && "ok".equals(snapshot.status)
            && (snapshot.review == null
                || !snapshot.review.discovering && snapshot.review.loadedChunks >= snapshot.review.totalChunks);
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        if (usable && region != null) for (Cell cell : snapshot.cells) {
            if (!contains(region, cell) || !sampled(cell)) continue;
            double value = net(cell);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new FactoryPowerPalette(usable, region, min, max);
    }

    boolean contains(Cell cell) {
        return region != null && contains(region, cell);
    }

    boolean known(Cell cell) {
        return usable && contains(cell) && sampled(cell);
    }

    int cellColor(Cell cell) {
        return known(cell) && hasValues ? colorAt(position(net(cell))) & 0xFFFFFF | 0x78000000 : UNKNOWN;
    }

    /** Scale before subtracting opposite extremes so a finite range cannot overflow. */
    double position(double value) {
        if (!hasValues || min == max) return .5;
        double fraction;
        if (min < 0 && max > 0) {
            double magnitude = Math.max(-min, max);
            fraction = (value / magnitude - min / magnitude) / (max / magnitude - min / magnitude);
        } else fraction = (value - min) / (max - min);
        return Math.max(0, Math.min(1, fraction));
    }

    int colorAt(double fraction) {
        if (!hasValues) return UNKNOWN;
        if (min == max) return MIDDLE;
        double t = Math.max(0, Math.min(1, fraction));
        return t <= .5 ? blend(LOW, MIDDLE, t * 2) : blend(MIDDLE, HIGH, t * 2 - 1);
    }

    static double net(Cell cell) {
        double value = cell.power.instOut - cell.power.instIn;
        return Double.isInfinite(value) ? Math.copySign(Double.MAX_VALUE, value) : value;
    }

    private static boolean sampled(Cell cell) {
        return cell != null && cell.power != null && cell.monitored > 0 && cell.unknown == 0;
    }

    private static boolean contains(FactoryPowerSelection.Region region, Cell cell) {
        return cell != null && cell.x >= region.minX
            && cell.x <= region.maxX
            && cell.z >= region.minZ
            && cell.z <= region.maxZ;
    }

    private static int blend(int from, int to, double fraction) {
        int red = (int) Math.round((from >> 16 & 255) * (1 - fraction) + (to >> 16 & 255) * fraction);
        int green = (int) Math.round((from >> 8 & 255) * (1 - fraction) + (to >> 8 & 255) * fraction);
        int blue = (int) Math.round((from & 255) * (1 - fraction) + (to & 255) * fraction);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
