package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

/** Complete natural information layout. The caller owns clipping and scrolling; no field is discarded. */
final class InformationCardLayout {

    enum Form {
        HERO,
        STACK
    }

    static final class Cell {

        final Rect bounds;
        final Form form;

        Cell(Rect bounds, Form form) {
            this.bounds = bounds;
            this.form = form;
        }
    }

    final List<Cell> cells;
    final int contentHeight;

    InformationCardLayout(Rect card, int requestedColumns, int fields) {
        int count = Math.max(0, fields);
        int columns = Math.min(Math.max(1, requestedColumns), Math.max(1, count));
        int width = Math.max(0, card.w - 24);
        List<Cell> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int left = card.x + 12 + index % columns * width / columns;
            int right = card.x + 12 + (index % columns + 1) * width / columns;
            int y = card.y + 30 + (columns == 1 ? index == 0 ? 0 : 32 + (index - 1) * 26 : index / columns * 32);
            result.add(
                new Cell(
                    new Rect(
                        left,
                        y,
                        Math.max(1, right - left - (columns == 1 ? 0 : 6)),
                        columns == 1 && index == 0 ? 32 : columns == 1 ? 26 : 32),
                    columns == 1 && index == 0 ? Form.HERO : Form.STACK));
        }
        cells = Collections.unmodifiableList(result);
        contentHeight = count == 0 ? 28
            : columns == 1 ? 32 + (count - 1) * 26 + 8 : (count + columns - 1) / columns * 32 + 8;
    }
}
