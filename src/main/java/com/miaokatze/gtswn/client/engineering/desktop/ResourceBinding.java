package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.Objects;
import java.util.UUID;

/** Exact screen resource identity. The v2 key includes registry id, metadata and complete NBT. */
public final class ResourceBinding {

    public final UUID source, screen;
    public final int kind;
    public final String key, name;

    public ResourceBinding(UUID source, UUID screen, int kind, String key, String name) {
        this.source = Objects.requireNonNull(source, "source");
        this.screen = Objects.requireNonNull(screen, "screen");
        if (kind != 1 && kind != 2) throw new IllegalArgumentException("kind");
        if (key == null || !key.matches(kind == 1 ? "v2:item:[0-9a-f]{64}" : "v2:fluid:[0-9a-f]{64}"))
            throw new IllegalArgumentException("exact resource key");
        this.kind = kind;
        this.key = key;
        String cleanName = name == null ? "" : name.replaceAll("[\\p{Cntrl}§]", "");
        this.name = cleanName.substring(0, Math.min(256, cleanName.length()));
    }

    public boolean matches(int kind, String key) {
        return this.kind == kind && this.key.equals(key);
    }
}
