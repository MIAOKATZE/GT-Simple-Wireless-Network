package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.gtswn.common.engineering.EngineeringGrid;

/** Pure factory power intent; previews never perform network I/O. */
final class FactoryPowerSelection {

    enum Grid {
        NONE,
        MC,
        GT
    }

    enum Change {
        DEFAULT_REGION,
        HIDDEN,
        SELECTING,
        NEED_GRID,
        NEED_PATTERN
    }

    private Grid grid = Grid.NONE;
    private boolean custom, selecting, dragging, audit;

    Change grid(Grid requested) {
        if (grid == requested && clearCustom()) return Change.DEFAULT_REGION;
        if (grid == requested) {
            grid = Grid.NONE;
            return Change.HIDDEN;
        }
        clearCustom();
        grid = requested;
        return Change.DEFAULT_REGION;
    }

    Change custom(boolean patternResolved) {
        if (clearCustom()) return Change.DEFAULT_REGION;
        if (grid == Grid.NONE) return Change.NEED_GRID;
        if (grid == Grid.GT && !patternResolved) return Change.NEED_PATTERN;
        selecting = true;
        return Change.SELECTING;
    }

    boolean clearCustom() {
        boolean had = custom || selecting || dragging;
        custom = selecting = dragging = false;
        return had;
    }

    boolean toggleAudit() {
        audit = !audit;
        return !audit && clearCustom();
    }

    void beginDrag() {
        if (selecting) dragging = true;
    }

    boolean finish(Region region) {
        dragging = false;
        if (!selecting || region == null || !region.valid(grid == Grid.GT)) return false;
        selecting = false;
        custom = true;
        return true;
    }

    void reset() {
        grid = Grid.NONE;
        custom = selecting = dragging = audit = false;
    }

    boolean visible() {
        return grid != Grid.NONE;
    }

    boolean gt() {
        return grid == Grid.GT;
    }

    boolean custom() {
        return custom;
    }

    boolean selecting() {
        return selecting;
    }

    boolean dragging() {
        return dragging;
    }

    boolean audit() {
        return audit;
    }

    static Region region(double startX, double startZ, double endX, double endZ, boolean gt, boolean axis) {
        return new Region(
            EngineeringGrid.cellOfBlock((int) Math.floor(Math.min(startX, endX)), gt, axis),
            EngineeringGrid.cellOfBlock((int) Math.floor(Math.min(startZ, endZ)), gt, axis),
            EngineeringGrid.cellOfBlock((int) Math.floor(Math.max(startX, endX)), gt, axis),
            EngineeringGrid.cellOfBlock((int) Math.floor(Math.max(startZ, endZ)), gt, axis));
    }

    static final class Region {

        final int minX, minZ, maxX, maxZ;

        Region(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        long count() {
            return ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        }

        boolean valid(boolean gt) {
            return EngineeringGrid.valid(minX, minZ, maxX, maxZ, gt);
        }
    }
}
