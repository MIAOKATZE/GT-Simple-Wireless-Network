package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

/** Circular face and bounded optional field rows; the face never stretches with the card. */
final class ClockFaceLayout {

    final double centerX, centerY, radius;
    final Rect source;
    final List<Rect> details;
    final int contentHeight;

    ClockFaceLayout(Rect card, int requestedColumns, int detailCount) {
        int count = Math.max(0, detailCount);
        int columns = Math.min(Math.max(1, requestedColumns), Math.max(1, count));
        int width = Math.max(0, card.w - 24);
        int rows = (count + columns - 1) / columns;
        int faceHeight = Math.min(176, Math.max(104, width));
        double diameter = Math.min(width, faceHeight);
        centerX = card.x + card.w / 2.0;
        centerY = card.y + 30 + faceHeight / 2.0;
        radius = Math.max(0, diameter / 2 - 2);
        source = new Rect(card.x + 12, card.y + 30 + faceHeight + 3, width, 12);
        List<Rect> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int left = card.x + 12 + index % columns * width / columns;
            int right = card.x + 12 + (index % columns + 1) * width / columns;
            result.add(new Rect(left, source.y + 15 + index / columns * 28, Math.max(1, right - left - 6), 28));
        }
        details = Collections.unmodifiableList(result);
        contentHeight = faceHeight + 18 + rows * 28 + 8;
    }
}
