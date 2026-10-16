package com.miaokatze.gtswn.common.testworld;

/** Verified connected 7-cube geometry: 196 controllers, 772 exposed faces; no optimality claim. */
final class AeStressControllerLayout {

    static final int X = -3, Y = 65, Z = -10, CONTROLLERS = 196, EXPOSED_FACES = 772;
    static final int CHANNEL_BUDGET = EXPOSED_FACES * 32;
    private static final int[] ROW_MASKS = { 111, 105, 93, 54, 91, 44, 115, 85, 22, 98, 85, 106, 21, 109, 93, 33, 93,
        41, 86, 57, 70, 38, 106, 9, 118, 77, 35, 125, 89, 109, 86, 89, 84, 106, 87, 46, 17, 43, 37, 43, 29, 98, 115, 94,
        73, 118, 77, 115, 93 };

    private AeStressControllerLayout() {}

    static int[][] coordinates() {
        int[][] result = new int[CONTROLLERS][3];
        int cursor = 0;
        for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) {
            if ((ROW_MASKS[y * 7 + z] & (1 << x)) != 0) result[cursor++] = new int[] { X + x, Y + y, Z + z };
        }
        if (cursor != CONTROLLERS) throw new IllegalStateException("Corrupt controller geometry");
        return result;
    }
}
