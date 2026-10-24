package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Objects;
import java.util.UUID;

/** Local stable reference only. Every live read/action still needs the current terminal receipt. */
public final class DeviceBinding {

    public final UUID source;
    public final String key;
    public final int dimension, x, y, z, meta;

    public DeviceBinding(UUID source, String key, int dimension, int x, int y, int z, int meta) {
        this.source = Objects.requireNonNull(source, "source");
        if (key == null || key.isEmpty() || key.length() > 256) throw new IllegalArgumentException("key");
        this.key = key;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.meta = meta;
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof DeviceBinding)) return false;
        DeviceBinding b = (DeviceBinding) value;
        return source.equals(b.source) && key.equals(b.key)
            && dimension == b.dimension
            && x == b.x
            && y == b.y
            && z == b.z
            && meta == b.meta;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, key, dimension, x, y, z, meta);
    }
}
