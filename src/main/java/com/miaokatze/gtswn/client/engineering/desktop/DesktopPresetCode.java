package com.miaokatze.gtswn.client.engineering.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/** Versioned visual-only wire format. Operational references and readings have no representation here. */
public final class DesktopPresetCode {

    public static final String PREFIX = "GTSWN-DESKTOP:2:";
    public static final String V1_PREFIX = "GTSWN-DESKTOP:1:";
    public static final int MAX_CODE_LENGTH = 32768, MAX_DECOMPRESSED_BYTES = 65536;

    private DesktopPresetCode() {}

    public static String export(DesktopLayout layout) {
        if (layout == null) throw new IllegalArgumentException("layout");
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(raw);
            output.writeUTF(
                layout.theme()
                    .name());
            output.writeInt(
                layout.widgets()
                    .size());
            int index = 0;
            for (WidgetSpec widget : layout.widgets()) {
                // IDs are local handles and may themselves be UUIDs; share only fresh visual handles.
                output.writeUTF("card" + ++index);
                output.writeUTF(widget.kind.name());
                output.writeInt(widget.column);
                output.writeInt(widget.row);
                output.writeInt(widget.columns);
                output.writeInt(widget.rows);
                output.writeUTF(widget.title);
                output.writeBoolean(widget.fieldsConfigured);
                output.writeInt(widget.fields.size());
                for (String field : widget.fields) output.writeUTF(field);
                output.writeInt(widget.displayColumns);
                output.writeUTF(widget.presentation.name());
                output.writeUTF(widget.metricField);
                output.writeBoolean(widget.userTarget != null);
                if (widget.userTarget != null) output.writeDouble(widget.userTarget);
                output.writeInt(widget.borderColor);
                output.writeBoolean(widget.singleResource);
                output.writeBoolean(widget.averageRate);
                output.writeInt(widget.averageWindowSeconds);
                output.writeUTF(widget.effects.style.name());
                output.writeBoolean(widget.effects.animated);
                output.writeInt(widget.effects.accentColor);
                output.writeDouble(widget.effects.speed);
                output.writeDouble(widget.effects.intensity);
                output.writeDouble(widget.effects.glow);
            }
            output.flush();
            if (raw.size() > MAX_DECOMPRESSED_BYTES) throw new IllegalArgumentException("size");
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            try (DeflaterOutputStream compressor = new DeflaterOutputStream(packed)) {
                compressor.write(raw.toByteArray());
            }
            String result = PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(packed.toByteArray());
            // The same strict reader validates every export, without mutating the source.
            decode(result);
            return result;
        } catch (IOException impossible) {
            throw new IllegalArgumentException("preset", impossible);
        }
    }

    /** Returns an unbound template. Applying it explicitly initializes local automatic binding intent. */
    public static DesktopLayout decode(String code) {
        if (code == null || code.length() > MAX_CODE_LENGTH) throw new IllegalArgumentException("preset size");
        String value = code.trim();
        boolean legacy = value.startsWith(V1_PREFIX);
        if (value.length() > MAX_CODE_LENGTH || !legacy && !value.startsWith(PREFIX))
            throw new IllegalArgumentException("version or size");
        String encoded = value.substring((legacy ? V1_PREFIX : PREFIX).length());
        if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("encoding");
        try {
            byte[] packed = Base64.getUrlDecoder()
                .decode(encoded);
            if (!Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(packed)
                .equals(encoded)) throw new IllegalArgumentException("encoding");
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(inflate(packed)));
            DashboardTheme theme = legacy ? DashboardTheme.STANDARD : DashboardTheme.valueOf(input.readUTF());
            int count = input.readInt();
            if (count < 0 || count > DesktopLayout.MAX_WIDGETS) throw new IllegalArgumentException("count");
            DesktopLayout result = new DesktopLayout().withTheme(theme);
            for (int index = 0; index < count; index++) {
                String id = input.readUTF();
                if (!id.matches("card([1-9]|[12][0-9]|3[0-2])")) throw new IllegalArgumentException("id");
                WidgetKind kind = WidgetKind.valueOf(input.readUTF());
                int column = input.readInt(), row = input.readInt(), columns = input.readInt(), rows = input.readInt();
                String title = input.readUTF();
                boolean configured = flag(input);
                int fieldCount = input.readInt();
                if (fieldCount < 0 || fieldCount > WidgetSpec.availableFields(kind)
                    .size()) throw new IllegalArgumentException("fields");
                List<String> fields = new ArrayList<>();
                Set<String> distinct = new HashSet<>();
                for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                    String field = input.readUTF();
                    if (!WidgetSpec.availableFields(kind)
                        .contains(field) || !distinct.add(field)) throw new IllegalArgumentException("field");
                    fields.add(field);
                }
                int displayColumns = input.readInt();
                if (displayColumns < 1 || displayColumns > 4) throw new IllegalArgumentException("displayColumns");
                WidgetPresentation presentation = WidgetPresentation.valueOf(input.readUTF());
                String metric = input.readUTF();
                if (!(metric.isEmpty() && WidgetSpec.availableMetrics(kind)
                    .isEmpty()) && !WidgetSpec.availableMetrics(kind)
                        .contains(metric))
                    throw new IllegalArgumentException("metric");
                Double target = flag(input) ? input.readDouble() : null;
                int border = input.readInt();
                boolean single = flag(input), average = flag(input);
                if (single && kind != WidgetKind.RESOURCES) throw new IllegalArgumentException("singleResource");
                int window = input.readInt();
                if (window < 1 || window > 3600) throw new IllegalArgumentException("window");
                WidgetEffects.Style style = WidgetEffects.Style.valueOf(input.readUTF());
                boolean animated = flag(input);
                int accent = input.readInt();
                if ((accent >>> 24) != 255) throw new IllegalArgumentException("accent");
                WidgetEffects effects = new WidgetEffects(
                    style,
                    animated,
                    accent,
                    input.readDouble(),
                    input.readDouble(),
                    legacy ? WidgetEffects.DEFAULT.glow : input.readDouble());
                WidgetSpec widget = new WidgetSpec(
                    id,
                    kind,
                    column,
                    row,
                    columns,
                    rows,
                    title,
                    null,
                    fields,
                    displayColumns,
                    configured,
                    presentation,
                    metric,
                    target,
                    border,
                    null,
                    null).withSingleResource(single)
                        .withRate(average, window)
                        .styled(effects);
                if (!widget.title.equals(title) || widget.borderColor != border || !result.add(widget))
                    throw new IllegalArgumentException("invalid card or overlap");
            }
            if (input.read() != -1) throw new IllegalArgumentException("trailing data");
            return result;
        } catch (IOException | DataFormatException failure) {
            throw new IllegalArgumentException("invalid preset", failure);
        }
    }

    private static boolean flag(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("boolean");
        return value == 1;
    }

    private static byte[] inflate(byte[] packed) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 || raw.size() + count > MAX_DECOMPRESSED_BYTES)
                    throw new IllegalArgumentException("compressed size or incomplete stream");
                raw.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0) throw new IllegalArgumentException("trailing compressed data");
            return raw.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
