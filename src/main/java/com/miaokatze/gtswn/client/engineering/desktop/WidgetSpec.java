package com.miaokatze.gtswn.client.engineering.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable geometry and display preferences. Coordinates and sizes are grid cells. */
public final class WidgetSpec {

    public static final List<String> AVAILABLE_FIELDS = Collections.unmodifiableList(
        Arrays.asList(
            "name",
            "status",
            "input",
            "output",
            "stored",
            "coordinates",
            "source",
            "updated",
            "instantInput",
            "instantOutput",
            "averageInput",
            "averageOutput",
            "recipeInput",
            "recipeOutput",
            "evidence",
            "recognition",
            "capabilities",
            "type"));
    private static final Map<WidgetKind, List<String>> FIELDS = new EnumMap<>(WidgetKind.class);
    static {
        FIELDS.put(WidgetKind.MACHINES, fieldList("status", "source", "updated"));
        FIELDS.put(WidgetKind.INPUT, fieldList("input", "status", "source", "updated"));
        FIELDS.put(WidgetKind.OUTPUT, fieldList("output", "status", "source", "updated"));
        FIELDS.put(WidgetKind.WIRELESS, fieldList("stored", "source", "updated"));
        FIELDS.put(WidgetKind.HISTORY, fieldList("source", "updated"));
        FIELDS.put(WidgetKind.RESOURCES, fieldList("source", "updated"));
        FIELDS.put(WidgetKind.ATTENTION, fieldList("status", "source", "updated"));
        FIELDS.put(WidgetKind.REGION, fieldList("input", "output", "status", "source", "updated"));
        FIELDS.put(
            WidgetKind.DEVICE,
            fieldList(
                "name",
                "status",
                "instantInput",
                "instantOutput",
                "averageInput",
                "averageOutput",
                "recipeInput",
                "recipeOutput",
                "coordinates",
                "source",
                "updated",
                "evidence",
                "recognition",
                "capabilities",
                "type"));
    }

    private static List<String> fieldList(String... fields) {
        return Collections.unmodifiableList(Arrays.asList(fields));
    }

    /**
     * Only fields backed by this component's actual source. Aliases are read for compatibility, never offered twice.
     */
    public static List<String> availableFields(WidgetKind kind) {
        List<String> fields = FIELDS.get(kind);
        return fields == null ? Collections.<String>emptyList() : fields;
    }

    public final String id, title;
    public final WidgetKind kind;
    public final int column, row, columns, rows, displayColumns;
    public final DeviceBinding device;
    public final List<String> fields;
    /** False uses the component template; true includes an intentionally empty field selection. */
    public final boolean fieldsConfigured;

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows) {
        this(id, kind, column, row, columns, rows, "", null, Collections.<String>emptyList(), 1);
    }

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows, String title,
        DeviceBinding device, List<String> fields, int displayColumns) {
        this(id, kind, column, row, columns, rows, title, device, fields, displayColumns, false);
    }

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows, String title,
        DeviceBinding device, List<String> fields, int displayColumns, boolean fieldsConfigured) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("id");
        this.id = id;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.column = column;
        this.row = row;
        this.columns = columns;
        this.rows = rows;
        this.title = cleanTitle(title);
        this.device = kind == WidgetKind.DEVICE ? device : null;
        this.displayColumns = Math.max(1, Math.min(4, displayColumns));
        this.fieldsConfigured = fieldsConfigured;
        List<String> selected = new ArrayList<>();
        if (fields != null) for (String field : fields) {
            if (kind == WidgetKind.DEVICE) {
                if ("input".equals(field)) field = "instantInput";
                if ("output".equals(field)) field = "instantOutput";
            }
            if (availableFields(kind).contains(field) && !selected.contains(field)) selected.add(field);
        }
        this.fields = Collections.unmodifiableList(selected);
    }

    private static String cleanTitle(String title) {
        if (title == null) return "";
        String clean = title.replaceAll("[\\p{Cntrl}§]", "")
            .trim();
        return clean.substring(0, Math.min(64, clean.length()));
    }

    public int minColumns() {
        return Math.max(kind.minColumns, fields.isEmpty() ? 0 : displayColumns * 2);
    }

    public int minRows() {
        // Header/footer plus compact field rows, with room for wrapping long evidence or recipe descriptions.
        int pixels = 56;
        for (int index = 0; index < fields.size(); index += displayColumns) {
            int rowPixels = 18;
            for (int cell = index; cell < Math.min(fields.size(), index + displayColumns); cell++) {
                String field = fields.get(cell);
                if (field.equals("coordinates") || field.equals("recipeInput")
                    || field.equals("recipeOutput")
                    || field.equals("capabilities")) rowPixels = 36;
            }
            pixels += rowPixels;
        }
        return Math.max(kind.minRows, (pixels + DesktopLayout.CELL - 1) / DesktopLayout.CELL);
    }

    public int x() {
        return column * DesktopLayout.CELL;
    }

    public int y() {
        return row * DesktopLayout.CELL;
    }

    public int width() {
        return columns * DesktopLayout.CELL;
    }

    public int height() {
        return rows * DesktopLayout.CELL;
    }

    public boolean contains(double x, double y) {
        return x >= x() && x < x() + width() && y >= y() && y < y() + height();
    }

    public WidgetSpec withBounds(int column, int row, int columns, int rows) {
        return new WidgetSpec(
            id,
            kind,
            column,
            row,
            columns,
            rows,
            title,
            device,
            fields,
            displayColumns,
            fieldsConfigured);
    }

    public WidgetSpec titled(String title) {
        return new WidgetSpec(
            id,
            kind,
            column,
            row,
            columns,
            rows,
            title,
            device,
            fields,
            displayColumns,
            fieldsConfigured);
    }

    public WidgetSpec configured(String title, List<String> fields, int displayColumns) {
        WidgetSpec candidate = new WidgetSpec(
            id,
            kind,
            column,
            row,
            columns,
            rows,
            title,
            device,
            fields,
            displayColumns,
            true);
        return candidate
            .withBounds(column, row, Math.max(columns, candidate.minColumns()), Math.max(rows, candidate.minRows()));
    }
}
