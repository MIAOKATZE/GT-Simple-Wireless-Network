package com.miaokatze.gtswn.common.util;

import net.minecraft.nbt.NBTTagCompound;

/** Player-persisted counters, independent of the held terminal and hint category. */
public final class QualityHintCounter {

    public enum Kind {

        NODE("node"),
        INCORPORATION("incorporation"),
        LINK("link");

        final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    private static final int LIMIT = 10;
    private static final String NAMESPACE = "gtswn";
    private static final String COUNTERS = "qualityHints";

    private QualityHintCounter() {}

    /** Returns 9..0 for a displayed hint, or -1 when this category is exhausted. */
    public static int consume(NBTTagCompound persisted, Kind kind) {
        NBTTagCompound namespace = persisted.getCompoundTag(NAMESPACE);
        NBTTagCompound counters = namespace.getCompoundTag(COUNTERS);
        int used = Math.max(0, Math.min(LIMIT, counters.getInteger(kind.key)));
        if (used >= LIMIT) return -1;
        counters.setInteger(kind.key, used + 1);
        namespace.setTag(COUNTERS, counters);
        persisted.setTag(NAMESPACE, namespace);
        return LIMIT - used - 1;
    }
}
