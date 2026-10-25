package com.miaokatze.gtswn.client.engineering;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetPresentation;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;

/** Compact information typography and instruments. The desktop owns the scroll offset and body clip. */
final class DashboardInformationCards {

    private DashboardInformationCards() {}

    /** Natural body height from card.y + 30, including eight pixels after the last field. */
    static int contentHeight(WidgetSpec widget, int width) {
        Rect card = new Rect(0, 0, width, 0);
        if (widget.presentation == WidgetPresentation.CLOCK_FACE) return clockLayout(card, widget).contentHeight;
        return new InformationCardLayout(
            card,
            widget.displayColumns,
            widget.informationFields()
                .size()).contentHeight;
    }

    static String draw(FontRenderer font, Rect card, WidgetSpec widget, DashboardTheme theme, int scrollOffset) {
        Rect shifted = new Rect(card.x, card.y - scrollOffset, card.w, card.h);
        if (widget.presentation == WidgetPresentation.CLOCK_FACE) drawClock(font, shifted, widget, theme);
        else drawFields(font, shifted, widget, theme);
        StringBuilder details = new StringBuilder(
            FactoryDesktop.tr(
                "info.help." + widget.kind.name()
                    .toLowerCase(Locale.ROOT)));
        for (String field : widget.informationFields()) details.append("\n")
            .append(label(field))
            .append(": ")
            .append(value(field));
        if (widget.kind == WidgetKind.TEAM && !widget.informationFields()
            .contains("teamId") && DashboardInformation.value("teamId") != null) details.append("\n")
                .append(label("teamId"))
                .append(": ")
                .append(value("teamId"));
        return details.toString();
    }

    private static void drawFields(FontRenderer font, Rect card, WidgetSpec widget, DashboardTheme theme) {
        List<String> fields = widget.informationFields();
        InformationCardLayout layout = new InformationCardLayout(card, widget.displayColumns, fields.size());
        if (fields.isEmpty()) {
            DashboardVisuals.label(
                font,
                FactoryDesktop.tr("fields.empty"),
                card.x + 12,
                card.y + 34,
                card.w - 24,
                theme.muted,
                .85f);
            return;
        }
        int accent = accent(widget, theme);
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            InformationCardLayout.Cell cell = layout.cells.get(index);
            Rect bounds = cell.bounds;
            String reading = value(field);
            boolean meter = field.equals("health") || field.equals("food");
            boolean badge = field.equals("teamRole") || field.equals("connection") || field.equals("weather");
            if (meter) {
                fieldLabel(font, field, bounds, theme);
                DashboardVisuals.label(font, reading, bounds.x, bounds.y + 10, bounds.w, theme.text, 1f);
                meter(
                    new Rect(bounds.x, bounds.y + 22, bounds.w, 3),
                    DashboardInformation.fraction(field),
                    field.equals("health") ? theme.positive : theme.warning,
                    theme);
            } else if (badge) {
                fieldLabel(font, field, bounds, theme);
                int width = Math.min(bounds.w, font.getStringWidth(reading) + 10);
                Gui.drawRect(bounds.x, bounds.y + 10, bounds.x + width, bounds.y + 24, theme.track);
                Gui.drawRect(bounds.x, bounds.y + 10, bounds.x + 2, bounds.y + 24, accent);
                DashboardVisuals
                    .label(font, reading, bounds.x + 5, bounds.y + 13, Math.max(0, width - 8), theme.text, .85f);
            } else if (cell.form == InformationCardLayout.Form.HERO && primary(widget.kind, field)) {
                // The card header already names this primary reading; do not repeat its label beneath it.
                float size = field.equals("localTime") || field.equals("gameTime") || field.equals("sessionDuration")
                    ? 1.65f
                    : 1.12f;
                DashboardVisuals.label(
                    font,
                    reading,
                    bounds.x,
                    bounds.y + 2,
                    bounds.w,
                    field.equals("teamName") || field.equals("playerName") ? theme.text : accent,
                    fittedScale(font, reading, bounds.w, size, 1.05f));
                rhythm(new Rect(bounds.x, bounds.y + 24, bounds.w, 3), widget.effects, accent, theme);
            } else {
                fieldLabel(font, field, bounds, theme);
                DashboardVisuals.label(font, reading, bounds.x, bounds.y + 11, bounds.w, theme.text, 1f);
            }
        }
    }

    private static ClockFaceLayout clockLayout(Rect card, WidgetSpec widget) {
        int count = widget.clockFields()
            .size() - (widget.clockSourceField() == null ? 0 : 1);
        return new ClockFaceLayout(card, widget.displayColumns, count);
    }

    private static void drawClock(FontRenderer font, Rect card, WidgetSpec widget, DashboardTheme theme) {
        List<String> fields = new ArrayList<>(widget.clockFields());
        String source = widget.clockSourceField();
        fields.remove(source);
        ClockFaceLayout face = clockLayout(card, widget);
        DashboardClock clock = DashboardInformation.clock(source);
        DashboardClockDrawing.draw(face, clock, theme, widget.effects);
        if (clock == null) DashboardVisuals.label(
            font,
            FactoryDesktop.tr("info.unavailable"),
            (int) (face.centerX - face.radius + 9),
            (int) face.centerY - 4,
            (int) (face.radius * 2 - 18),
            theme.muted,
            .8f);
        else if (face.radius >= 44) {
            for (int index = 0; index < 4; index++) {
                String number = new String[] { "12", "3", "6", "9" }[index];
                double angle = index * Math.PI / 2 - Math.PI / 2;
                int x = (int) (face.centerX + Math.cos(angle) * (face.radius - 18) - font.getStringWidth(number) * .4);
                int y = (int) (face.centerY + Math.sin(angle) * (face.radius - 18) - 3);
                DashboardVisuals.label(font, number, x, y, 14, theme.muted, .8f);
            }
        }
        String sourceLabel = source == null ? FactoryDesktop.tr("fields.empty") : label(source);
        DashboardVisuals.label(
            font,
            sourceLabel,
            face.source.x,
            face.source.y,
            face.source.w,
            theme.muted,
            fittedScale(font, sourceLabel, face.source.w, .85f, .7f));
        for (int index = 0; index < fields.size(); index++) {
            Rect bounds = face.details.get(index);
            fieldLabel(font, fields.get(index), bounds, theme);
            DashboardVisuals.label(font, value(fields.get(index)), bounds.x, bounds.y + 11, bounds.w, theme.text, 1f);
        }
    }

    private static String label(String field) {
        return FactoryDesktop.tr("field." + field);
    }

    private static float fittedScale(FontRenderer font, String text, int width, float desired, float minimum) {
        return Math.max(minimum, Math.min(desired, width / (float) Math.max(1, font.getStringWidth(text))));
    }

    private static void fieldLabel(FontRenderer font, String field, Rect bounds, DashboardTheme theme) {
        String text = label(field);
        DashboardVisuals
            .label(font, text, bounds.x, bounds.y, bounds.w, theme.muted, fittedScale(font, text, bounds.w, .85f, .7f));
    }

    private static boolean primary(WidgetKind kind, String field) {
        switch (kind) {
            case CLOCK:
                return field.equals("localTime") || field.equals("gameTime");
            case SESSION:
                return field.equals("sessionDuration");
            case TEAM:
                return field.equals("teamName");
            case PLAYER:
                return field.equals("playerName");
            case WORLD:
                return field.equals("dimension");
            default:
                return false;
        }
    }

    private static String value(String field) {
        String reading = DashboardInformation.value(field);
        return reading == null ? FactoryDesktop.tr("info.unavailable") : reading;
    }

    private static int accent(WidgetSpec widget, DashboardTheme theme) {
        return FactoryDashboardArt.readableAccent(
            widget.effects.accentColor == WidgetEffects.DEFAULT.accentColor ? theme.accent
                : widget.effects.accentColor);
    }

    private static void meter(Rect bounds, Double fraction, int color, DashboardTheme theme) {
        Gui.drawRect(bounds.x, bounds.y, bounds.x + bounds.w, bounds.y + bounds.h, theme.track);
        if (fraction != null) Gui
            .drawRect(bounds.x, bounds.y, bounds.x + (int) Math.round(bounds.w * fraction), bounds.y + bounds.h, color);
    }

    /** Decorative rhythm only: it never changes a player meter, elapsed duration, or clock hands. */
    private static void rhythm(Rect bounds, WidgetEffects effects, int accent, DashboardTheme theme) {
        double phase = effects.animated && effects.speed > 0 ? (System.nanoTime() / 1_000_000_000.0 * effects.speed) % 1
            : .5;
        int marks = Math.max(3, Math.min(12, bounds.w / 9));
        for (int index = 0; index < marks; index++) {
            double position = index / (double) marks;
            double strength;
            int y = bounds.y;
            switch (effects.style) {
                case SIGNAL:
                    double distance = Math.abs(position - phase);
                    distance = Math.min(distance, 1 - distance);
                    strength = Math.exp(-distance * distance / .02);
                    break;
                case SEGMENTED:
                    strength = .5 + .5 * Math.cos((phase - index / 3.0) * Math.PI * 2);
                    break;
                case PRISM:
                    strength = .5 + .5 * Math.cos((phase - Math.abs(position - .5)) * Math.PI * 2);
                    break;
                case WAVE:
                    strength = .5 + .5 * Math.sin((position - phase) * Math.PI * 2);
                    break;
                default:
                    strength = .5 + .5 * Math.cos((position - phase) * Math.PI * 2);
                    break;
            }
            int x = bounds.x + index * bounds.w / marks;
            int color = DashboardTheme.mix(theme.track, accent, (.16 + .4 * strength) * effects.intensity);
            Gui.drawRect(x, y, x + (effects.style == WidgetEffects.Style.SEGMENTED ? 5 : 2), y + 2, color);
        }
    }
}
