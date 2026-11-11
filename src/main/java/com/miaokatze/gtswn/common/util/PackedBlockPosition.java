// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.util;

/** Shared persisted position layout: signed x26, y12 and z26, with the original truncation rules. */
public final class PackedBlockPosition {

    private PackedBlockPosition() {}

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) ((packed << 38) >> 38);
    }
}
