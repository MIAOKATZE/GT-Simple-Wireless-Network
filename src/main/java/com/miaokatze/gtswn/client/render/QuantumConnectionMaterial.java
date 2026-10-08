package com.miaokatze.gtswn.client.render;

import java.awt.image.BufferedImage;

/** Connection-only material changes; shared core pixels and source assets stay untouched. */
final class QuantumConnectionMaterial {

    private QuantumConnectionMaterial() {}

    static BufferedImage create(BufferedImage source, boolean translucent) {
        if (source.getWidth() != 32 || source.getHeight() % 32 != 0) {
            throw new IllegalArgumentException("Quantum connection material requires 32px square frames");
        }
        BufferedImage result = new BufferedImage(32, source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, 32, source.getHeight(), source.getRGB(0, 0, 32, source.getHeight(), null, 0, 32), 0, 32);
        for (int frame = 0; frame < source.getHeight(); frame += 32) {
            // Arms use longitudinal UV [0,10) / [22,32), with transverse UV [12,20).
            // The core's [10,22) square and its connection face rings are left exact.
            for (int axial = 0; axial < 32; axial++) {
                if (axial >= 10 && axial < 22) continue;
                for (int side = 0; side < 2; side++) {
                    int target = side == 0 ? 14 : 17;
                    int edge = side == 0 ? 13 : 18;
                    int vertical = source.getRGB(translucent ? edge : target, frame + axial);
                    int horizontal = source.getRGB(axial, frame + (translucent ? edge : target));
                    result.setRGB(target, frame + axial, translucent ? vertical : vertical & 0x00FFFFFF);
                    result.setRGB(axial, frame + target, translucent ? horizontal : horizontal & 0x00FFFFFF);
                }
            }
        }
        return result;
    }
}
