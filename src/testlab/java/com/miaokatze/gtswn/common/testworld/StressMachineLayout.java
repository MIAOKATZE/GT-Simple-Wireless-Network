// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

/** Native NORTH-facing structure envelopes, including a clear border for ME connections. */
final class StressMachineLayout {

    static final int SINGLE_COUNT = 128, MULTI_COUNT = 256, GENERATOR_COUNT = 64;
    static final int COUNT = SINGLE_COUNT + MULTI_COUNT + GENERATOR_COUNT, RADIUS = 80;
    static final int SINGLE_Y = 100, MULTI_TOP = 80, GENERATOR_TOP = 118;

    // Dimensions follow the actual StructureLib arrays, before any tooltip-axis reordering.
    static final Definition[] MULTIS = { new Definition("MTEVacuumFreezer", 3, 3, 3, 1, 1, 0),
        new Definition("MTEIndustrialCentrifuge", 5, 5, 5, 2, 2, 1),
        new Definition("MTEIndustrialElectrolyzer", 5, 5, 5, 2, 2, 1),
        new Definition("MTEIndustrialMixer", 5, 7, 5, 2, 5, 0), new Definition("MTEIndustrialSifter", 5, 6, 5, 2, 4, 0),
        new Definition("MTEIndustrialWireMill", 7, 3, 3, 3, 2, 0),
        new Definition("MTEIndustrialThermalCentrifuge", 5, 8, 6, 2, 7, 0),
        new Definition("MTEIndustrialForgeHammer", 5, 9, 5, 2, 7, 1),
        new Definition("MTEIndustrialBendingMachine", 6, 3, 3, 1, 1, 0),
        new Definition("MTEIndustrialFormingPress", 5, 5, 3, 2, 4, 0),
        new Definition("MTEIndustrialExtractor", 5, 5, 5, 2, 4, 0),
        new Definition("MTEIndustrialBrewery", 3, 5, 3, 1, 2, 0),
        new Definition("MTEIndustrialChemicalBath", 5, 4, 5, 2, 2, 0),
        new Definition("MTELargeFluidExtractor", 5, 9, 5, 2, 8, 0),
        new Definition("MTEIndustrialMacerator", 3, 6, 3, 1, 5, 0),
        new Definition("MTELargeChemicalReactor", 3, 3, 3, 1, 1, 0) };
    static final Definition LARGE_GENERATOR = new Definition("MTELargeCombustionEngine", 8, 3, 4, 1, 1, 1);
    static final Definition PARALLEL_FURNACE = new Definition("MTEMultiFurnace", 3, 3, 3, 1, 2, 0);
    static final Definition MEGA_CHEMICAL = new Definition("MTEMegaChemicalReactor", 5, 5, 9, 2, 2, 0);
    static final Definition ORE_FACTORY = new Definition("MTEIntegratedOreFactory", 15, 9, 13, 7, 6, 1);

    private StressMachineLayout() {}

    static Slot slot(int index) {
        if (index < 0 || index >= COUNT) throw new IllegalArgumentException("Unknown stress machine " + index);
        if (index < SINGLE_COUNT) return new Slot(index, null, SINGLE_Y);
        if (index < SINGLE_COUNT + MULTI_COUNT) {
            int local = index - SINGLE_COUNT;
            if (local >= 240) return new Slot(local, MEGA_CHEMICAL, MULTI_TOP);
            if (local >= 224) return new Slot(local, PARALLEL_FURNACE, MULTI_TOP);
            if (local >= 216) return new Slot(local - 216, ORE_FACTORY, 168, 20, 8);
            return new Slot(local, MULTIS[local % MULTIS.length], MULTI_TOP);
        }
        int local = index - SINGLE_COUNT - MULTI_COUNT;
        return new Slot(local, local % 4 == 0 ? LARGE_GENERATOR : null, GENERATOR_TOP);
    }

    static final class Definition {

        final String type;
        final int width, height, depth, offsetX, offsetY, offsetZ;
        final boolean fluidInput, fluidOutput;

        Definition(String type, int width, int height, int depth, int offsetX, int offsetY, int offsetZ) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            switch (type) {
                case "MTEIndustrialWireMill":
                case "MTEIndustrialThermalCentrifuge":
                case "MTEIndustrialBendingMachine":
                case "MTEIndustrialExtractor":
                case "MTEIndustrialMacerator":
                case "MTEMultiFurnace":
                    fluidInput = false;
                    fluidOutput = false;
                    break;
                case "MTELargeFluidExtractor":
                    fluidInput = false;
                    fluidOutput = true;
                    break;
                case "MTEIndustrialFormingPress":
                case "MTEIntegratedOreFactory":
                case "MTELargeCombustionEngine":
                    fluidInput = true;
                    fluidOutput = false;
                    break;
                default:
                    fluidInput = true;
                    fluidOutput = true;
                    break;
            }
        }
    }

    static final class Slot {

        final Definition definition;
        final int cellX, cellZ, cellWidth, minX, minY, minZ, maxX, maxY, maxZ, x, y, z;

        Slot(int local, Definition definition, int top) {
            this(local, definition, top, 10, 16);
        }

        Slot(int local, Definition definition, int top, int stride, int columns) {
            this.definition = definition;
            cellWidth = stride;
            cellX = -RADIUS + local % columns * stride;
            cellZ = -RADIUS + local / columns * stride;
            minX = cellX + (definition == null ? 4 : 1);
            minZ = cellZ + (definition == null ? 4 : 1);
            maxY = top;
            minY = top - (definition == null ? 0 : definition.height - 1);
            maxX = minX + (definition == null ? 0 : definition.width - 1);
            maxZ = minZ + (definition == null ? 0 : definition.depth - 1);
            // In StructureLib's unrotated NORTH orientation, A points WEST, B points DOWN, C SOUTH.
            x = definition == null ? cellX + 4 : maxX - definition.offsetX;
            y = definition == null ? top : top - definition.offsetY;
            z = definition == null ? cellZ + 4 : minZ + definition.offsetZ;
        }

        boolean containsConnection(int px, int py, int pz) {
            return px >= cellX && px < cellX + cellWidth
                && pz >= cellZ
                && pz < cellZ + cellWidth
                && py >= 64
                && py <= 255;
        }
    }
}
