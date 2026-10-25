package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

/** Bounded information rows shared by the large reading and compact multi-column presentations. */
final class InformationCardLayout {

    enum Form {
        HERO,
        ROW,
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
    final int hidden;

    InformationCardLayout(Rect card, int requestedColumns, int fields) {
        int count = Math.max(0, fields);
        int columns = Math.min(Math.max(1, requestedColumns), Math.max(1, count));
        int available = Math.max(0, card.h - 38);
        int width = Math.max(0, card.w - 24);
        int requiredRows = (count + columns - 1) / columns;
        if ((columns == 1 ? (long) count * 12 : (long) requiredRows * 22) > available)
            available = Math.max(0, available - 14); // Reserve the explicit overflow count below the last value.
        List<Cell> result = new ArrayList<>();
        if (count > 0 && width >= columns && available >= 12) {
            if (columns == 1) {
                boolean hero = available >= 42L + (count - 1L) * 18;
                int step = hero ? available >= 42L + (count - 1L) * 29 ? 29 : 18
                    : Math.max(12, Math.min(18, available / count));
                int y = card.y + 30;
                if (hero) {
                    result.add(new Cell(new Rect(card.x + 12, y, width, 42), Form.HERO));
                    y += 42;
                }
                for (int i = result.size(); i < count && y + step <= card.y + 30 + available; i++) {
                    result.add(new Cell(new Rect(card.x + 12, y, width, step), step >= 29 ? Form.STACK : Form.ROW));
                    y += step;
                }
            } else {
                int rows = (count + columns - 1) / columns;
                int step = Math.max(22, Math.min(29, available / rows));
                int visible = Math.min(count, available / step * columns);
                for (int i = 0; i < visible; i++) {
                    int left = card.x + 12 + i % columns * width / columns;
                    int right = card.x + 12 + (i % columns + 1) * width / columns;
                    result.add(
                        new Cell(
                            new Rect(left, card.y + 30 + i / columns * step, Math.max(1, right - left - 6), step),
                            Form.STACK));
                }
            }
        }
        cells = Collections.unmodifiableList(result);
        hidden = count - cells.size();
    }
}
