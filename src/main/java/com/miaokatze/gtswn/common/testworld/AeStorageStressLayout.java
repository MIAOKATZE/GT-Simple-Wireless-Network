package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.List;

/** Pure, bounded layout shared by the native fixture and its geometry checks. */
final class AeStorageStressLayout {

    static final int ENTRY_X = 0, ENTRY_Y = 65, ENTRY_Z = 1;

    enum Kind {
        CONTROLLER,
        POWER,
        ENTRY_CHEST,
        NODE,
        DRIVE,
        INTERFACE,
        CHEST,
        CPU_STORAGE,
        CPU_ACCELERATOR,
        CPU_UNIT,
        CPU_MONITOR
    }

    static final class Placement {

        final Kind kind;
        final int x, y, z, module;

        Placement(Kind kind, int x, int y, int z, int module) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.module = module;
        }
    }

    final int layers, modules, cpus, nodes, cells, itemTypes;
    final List<Placement> placements = new ArrayList<>();

    AeStorageStressLayout(boolean large) {
        layers = large ? 2 : 1;
        modules = layers * 256;
        cpus = modules / 8;
        nodes = modules + cpus;
        cells = modules * 11;
        itemTypes = large ? 256 : 128;
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) if (Math.abs(x) == 3 || Math.abs(z) == 3) add(Kind.CONTROLLER, x, 65, z, -1);
        add(Kind.POWER, -3, 66, -3, -1);
        add(Kind.ENTRY_CHEST, ENTRY_X, ENTRY_Y, ENTRY_Z, -1);
        for (int module = 0; module < modules; module++) {
            int x = x(module), y = y(module), z = z(module);
            add(Kind.NODE, x, y, z, module);
            add(Kind.DRIVE, x - 1, y, z, module);
            add(Kind.INTERFACE, x + 1, y, z, module);
            add(Kind.INTERFACE, x, y, z + 1, module);
            add(Kind.CHEST, x, y, z - 1, module);
            if (module % 8 == 0) {
                for (int part = 0; part < 8; part++) {
                    Kind kind = part == 0 ? Kind.CPU_STORAGE
                        : part == 1 ? Kind.CPU_ACCELERATOR : part == 2 ? Kind.CPU_MONITOR : Kind.CPU_UNIT;
                    add(kind, x + part % 2, y + 5 + part / 4, z + part / 2 % 2, module);
                }
                add(Kind.NODE, x + 2, y + 5, z, module);
            }
        }
    }

    static int x(int module) {
        return -30 + module % 16 * 4;
    }

    static int y(int module) {
        return 80 + module / 256 * 16;
    }

    static int z(int module) {
        return -30 + module / 16 % 16 * 4;
    }

    private void add(Kind kind, int x, int y, int z, int module) {
        placements.add(new Placement(kind, x, y, z, module));
    }
}
