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

    public static final int DEFAULT_BORDER_COLOR = 0xFF38576E;

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
    private static final Map<WidgetKind, List<String>> METRICS = new EnumMap<>(WidgetKind.class);
    private static final Map<WidgetKind, List<WidgetPresentation>> PRESENTATIONS = new EnumMap<>(WidgetKind.class);
    static {
        METRICS.put(WidgetKind.MACHINES, fieldList("count", "running", "idle", "stopped", "unknown"));
        METRICS.put(WidgetKind.INPUT, fieldList("input"));
        METRICS.put(WidgetKind.OUTPUT, fieldList("output"));
        METRICS.put(WidgetKind.WIRELESS, fieldList("stored"));
        METRICS.put(WidgetKind.REGION, fieldList("monitored", "unknown", "loadedChunks", "totalChunks"));
        METRICS.put(WidgetKind.DEVICE, fieldList("instantInput", "instantOutput", "averageInput", "averageOutput"));
        for (WidgetKind kind : WidgetKind.values()) {
            List<WidgetPresentation> forms = new ArrayList<>();
            forms.add(WidgetPresentation.DEFAULT);
            if (METRICS.containsKey(kind)) {
                forms.add(WidgetPresentation.KPI);
                forms.add(WidgetPresentation.BAR);
                forms.add(WidgetPresentation.GAUGE);
            }
            if (kind == WidgetKind.HISTORY) forms.add(WidgetPresentation.TREND);
            else forms.add(WidgetPresentation.STATUS_LIST);
            PRESENTATIONS.put(kind, Collections.unmodifiableList(forms));
        }
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

    /** Primary numeric readings, separate from optional descriptive rows. */
    public static List<String> availableMetrics(WidgetKind kind) {
        List<String> metrics = METRICS.get(kind);
        return metrics == null ? Collections.<String>emptyList() : metrics;
    }

    public static List<WidgetPresentation> availablePresentations(WidgetKind kind) {
        List<WidgetPresentation> forms = PRESENTATIONS.get(kind);
        return forms == null ? Collections.<WidgetPresentation>emptyList() : forms;
    }

    public final String id, title;
    public final WidgetKind kind;
    public final int column, row, columns, rows, displayColumns;
    public final DeviceBinding device;
    public final List<String> fields;
    /** False uses the component template; true includes an intentionally empty field selection. */
    public final boolean fieldsConfigured;
    public final WidgetPresentation presentation;
    public final String metricField;
    /** Optional user-entered goal in the metric's own units; never a measured device capacity. */
    public final Double userTarget;
    public final int borderColor;
    public final ResourceBinding resource;

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows) {
        this(id, kind, column, row, columns, rows, "", null, Collections.<String>emptyList(), 1);
    }

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows, String title,
        DeviceBinding device, List<String> fields, int displayColumns) {
        this(id, kind, column, row, columns, rows, title, device, fields, displayColumns, false);
    }

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows, String title,
        DeviceBinding device, List<String> fields, int displayColumns, boolean fieldsConfigured) {
        this(
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
            fieldsConfigured,
            WidgetPresentation.DEFAULT,
            "",
            null);
    }

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows, String title,
        DeviceBinding device, List<String> fields, int displayColumns, boolean fieldsConfigured,
        WidgetPresentation presentation, String metricField, Double userTarget) {
        this(
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
            fieldsConfigured,
            presentation,
            metricField,
            userTarget,
            DEFAULT_BORDER_COLOR,
            null);
    }

    public WidgetSpec(String id, WidgetKind kind, int column, int row, int columns, int rows, String title,
        DeviceBinding device, List<String> fields, int displayColumns, boolean fieldsConfigured,
        WidgetPresentation presentation, String metricField, Double userTarget, int borderColor,
        ResourceBinding resource) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("id");
        this.id = id;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        if (!availablePresentations(kind).contains(presentation)) throw new IllegalArgumentException("presentation");
        String metric = metricField == null ? "" : metricField;
        if (kind == WidgetKind.DEVICE) {
            if ("input".equals(metric)) metric = "instantInput";
            if ("output".equals(metric)) metric = "instantOutput";
        }
        List<String> metrics = availableMetrics(kind);
        if (metric.isEmpty() && !metrics.isEmpty()) metric = metrics.get(0);
        if (!metric.isEmpty() && !metrics.contains(metric)) throw new IllegalArgumentException("metricField");
        if (userTarget != null && (!Double.isFinite(userTarget) || userTarget <= 0 || metric.isEmpty()))
            throw new IllegalArgumentException("userTarget");
        this.metricField = metric;
        this.userTarget = userTarget;
        this.borderColor = 0xFF000000 | borderColor;
        this.resource = kind == WidgetKind.RESOURCES ? resource : null;
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
        int minimum = kind == WidgetKind.RESOURCES ? resource == null ? kind.minColumns : 4
            : presentation == WidgetPresentation.DEFAULT ? kind.minColumns : presentation.minColumns;
        return Math.max(minimum, fields.isEmpty() ? 0 : Math.min(displayColumns, fields.size()) * 2);
    }

    public int minRows() {
        // Header/footer plus compact field rows, with room for wrapping long evidence or recipe descriptions.
        int minimum = kind == WidgetKind.RESOURCES ? resource == null ? kind.minRows : 3
            : presentation == WidgetPresentation.DEFAULT ? kind.minRows : presentation.minRows;
        int pixels = presentation == WidgetPresentation.DEFAULT || presentation == WidgetPresentation.STATUS_LIST ? 56
            : presentation.minRows * DesktopLayout.CELL - 18;
        for (int index = 0; index < fields.size(); index += displayColumns) {
            int rowPixels = 29;
            for (int cell = index; cell < Math.min(fields.size(), index + displayColumns); cell++) {
                String field = fields.get(cell);
                if (field.equals("coordinates") || field.equals("recipeInput")
                    || field.equals("recipeOutput")
                    || field.equals("capabilities")) rowPixels = 36;
            }
            pixels += rowPixels;
        }
        return Math.max(minimum, (pixels + DesktopLayout.CELL - 1) / DesktopLayout.CELL);
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
            fieldsConfigured,
            presentation,
            metricField,
            userTarget,
            borderColor,
            resource);
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
            fieldsConfigured,
            presentation,
            metricField,
            userTarget,
            borderColor,
            resource);
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
            true,
            presentation,
            metricField,
            userTarget,
            borderColor,
            resource);
        return candidate
            .withBounds(column, row, Math.max(columns, candidate.minColumns()), Math.max(rows, candidate.minRows()));
    }

    public WidgetSpec presented(WidgetPresentation presentation, String metricField, Double userTarget) {
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
            fieldsConfigured,
            presentation,
            metricField,
            userTarget,
            borderColor,
            resource);
        return candidate
            .withBounds(column, row, Math.max(columns, candidate.minColumns()), Math.max(rows, candidate.minRows()));
    }

    public WidgetSpec bordered(int color) {
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
            fieldsConfigured,
            presentation,
            metricField,
            userTarget,
            color,
            resource);
    }

    public WidgetSpec boundResource(ResourceBinding binding) {
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
            fieldsConfigured,
            presentation,
            metricField,
            userTarget,
            borderColor,
            binding);
        return candidate
            .withBounds(column, row, Math.max(columns, candidate.minColumns()), Math.max(rows, candidate.minRows()));
    }
}
