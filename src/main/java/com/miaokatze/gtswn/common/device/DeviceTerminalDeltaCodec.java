package com.miaokatze.gtswn.common.device;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * Bounded DIT row codec. A delta references the server-acknowledged COMPLETE row dictionary, never a display prefix.
 * Target order is explicit; omitted base indices are removals, -1 introduces a row, and an empty target clears all
 * rows.
 * Unchanged strings/coordinates are absent from deltas. Changed strings share a per-delivery dictionary, also used by
 * full
 * snapshots. Exact long and raw IEEE-754 bits are retained. Authorization, epoch and revision checks belong to
 * transport.
 */
public final class DeviceTerminalDeltaCodec {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = PacketSyncDeviceTerminalData.MAX_ENTRIES;
    private static final int MAX_STRINGS = MAX_ROWS * 5;
    private static final int MAX_STRING_BYTES = PacketSyncDeviceTerminalData.MAX_RECIPE_LEN * 4;
    private static final int SCHEMA = 1;
    private static final int NAME = 1;
    private static final int STATE = 1 << 1;
    private static final int POWER = 1 << 2;
    private static final int INPUT = 1 << 3;
    private static final int OUTPUT = 1 << 4;
    private static final int AVG_INPUT = 1 << 5;
    private static final int AVG_OUTPUT = 1 << 6;
    private static final int RECIPE_INPUT = 1 << 7;
    private static final int RECIPE_OUTPUT = 1 << 8;
    private static final int META = 1 << 9;
    private static final int CAPABILITY = 1 << 10;
    private static final int RECOGNITION = 1 << 11;
    private static final int EVIDENCE = 1 << 12;
    private static final int LINK = 1 << 13;
    private static final int DISPLAY = 1 << 14;
    private static final int COLOR = 1 << 15;
    private static final int BOLD = 1 << 16;
    private static final int COORDINATES = 1 << 17;
    private static final int ALL = (1 << 18) - 1;

    private DeviceTerminalDeltaCodec() {}

    public static final class Encoded {

        public final boolean delta;
        public final byte[] bytes;

        private Encoded(boolean delta, byte[] bytes) {
            this.delta = delta;
            this.bytes = bytes;
        }
    }

    /** Null base requests a full reset. Equal-size encodings prefer full, avoiding an unnecessary dependency. */
    public static Encoded encode(List<Entry> base, List<Entry> target) {
        validateRows(target);
        if (base != null) validateRows(base);
        byte[] full = write(null, target);
        if (base != null) {
            byte[] delta = write(base, target);
            if (delta.length < full.length) return new Encoded(true, delta);
        }
        return new Encoded(false, full);
    }

    /** Rejects the whole delivery on malformed input; no prefix can accidentally become a baseline. */
    public static List<Entry> decode(List<Entry> base, boolean delta, byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw invalid("Payload length");
        if (delta && base == null) throw invalid("Missing complete base");
        if (delta) validateRows(base);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readUnsignedByte() != SCHEMA) throw invalid("Unknown codec schema");
            int count = in.readUnsignedShort();
            if (count > MAX_ROWS) throw invalid("Row count");
            int dictionaryCount = in.readUnsignedShort();
            if (dictionaryCount > MAX_STRINGS) throw invalid("Dictionary count");
            List<String> dictionary = new ArrayList<>(dictionaryCount);
            Set<String> distinct = new HashSet<>();
            for (int i = 0; i < dictionaryCount; i++) {
                int length = in.readUnsignedShort();
                if (length > MAX_STRING_BYTES || length > in.available()) throw invalid("String length");
                byte[] utf8 = new byte[length];
                in.readFully(utf8);
                String value = utf8(utf8);
                if (value.length() > PacketSyncDeviceTerminalData.MAX_RECIPE_LEN || !distinct.add(value)) {
                    throw invalid("Invalid string dictionary");
                }
                dictionary.add(value);
            }
            List<Entry> result = new ArrayList<>(count);
            Set<Integer> referenced = new HashSet<>();
            Set<String> keys = new HashSet<>();
            for (int row = 0; row < count; row++) {
                Entry prior = null;
                int mask = ALL;
                if (delta) {
                    int reference = in.readUnsignedShort();
                    int index = reference == 0xffff ? -1 : reference & 0x3ff;
                    if (reference != 0xffff && (reference & ~0x43ff) != 0 || index >= base.size())
                        throw invalid("Base row index");
                    boolean changed = reference == 0xffff || (reference & 0x4000) != 0;
                    mask = changed ? in.readInt() : 0;
                    if ((mask & ~ALL) != 0 || changed && mask == 0) throw invalid("Unknown field mask");
                    if (index == -1) {
                        if (mask != ALL) throw invalid("Incomplete added row");
                    } else {
                        if (!referenced.add(index) || (mask & COORDINATES) != 0)
                            throw invalid("Invalid base reference");
                        prior = base.get(index);
                    }
                }
                Entry entry = readRow(in, dictionary, prior, mask);
                if (!keys.add(entry.key)) throw invalid("Duplicate target key");
                result.add(entry);
            }
            if (in.available() != 0) throw invalid("Trailing bytes");
            return Collections.unmodifiableList(result);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed DIT delivery", failure);
        }
    }

    private static byte[] write(List<Entry> base, List<Entry> target) {
        Map<String, Integer> indices = new HashMap<>();
        if (base != null) {
            for (int i = 0; i < base.size(); i++) indices.put(base.get(i).key, i);
        }
        int[] refs = new int[target.size()];
        int[] masks = new int[target.size()];
        Map<String, Integer> dictionary = new LinkedHashMap<>();
        for (int i = 0; i < target.size(); i++) {
            Entry next = target.get(i);
            Integer index = indices.get(next.key);
            refs[i] = index == null ? -1 : index;
            masks[i] = index == null ? ALL : changed(base.get(index), next);
            addStrings(dictionary, next, masks[i]);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(SCHEMA);
            out.writeShort(target.size());
            out.writeShort(dictionary.size());
            for (String value : dictionary.keySet()) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                out.writeShort(utf8.length);
                out.write(utf8);
            }
            for (int i = 0; i < target.size(); i++) {
                if (base != null) {
                    boolean changed = masks[i] != 0;
                    out.writeShort(refs[i] < 0 ? 0xffff : refs[i] | (changed ? 0x4000 : 0));
                    if (changed) out.writeInt(masks[i]);
                }
                writeRow(out, dictionary, target.get(i), masks[i]);
            }
            out.flush();
            if (bytes.size() > MAX_BYTES) throw invalid("Payload length");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void addStrings(Map<String, Integer> dictionary, Entry entry, int mask) {
        if ((mask & NAME) != 0) add(dictionary, entry.name);
        if ((mask & RECIPE_INPUT) != 0) add(dictionary, entry.recipeIn);
        if ((mask & RECIPE_OUTPUT) != 0) add(dictionary, entry.recipeOut);
        if ((mask & DISPLAY) != 0) add(dictionary, entry.displayName);
    }

    private static void add(Map<String, Integer> dictionary, String value) {
        if (!dictionary.containsKey(value)) dictionary.put(value, dictionary.size());
    }

    private static void writeRow(DataOutputStream out, Map<String, Integer> dictionary, Entry e, int mask)
        throws IOException {
        if ((mask & NAME) != 0) out.writeShort(dictionary.get(e.name));
        if ((mask & STATE) != 0) out.writeByte(e.state);
        if ((mask & POWER) != 0) out.writeByte(e.powerType);
        if ((mask & INPUT) != 0) out.writeLong(e.instIn);
        if ((mask & OUTPUT) != 0) out.writeLong(e.instOut);
        if ((mask & AVG_INPUT) != 0) out.writeLong(Double.doubleToRawLongBits(e.avgIn));
        if ((mask & AVG_OUTPUT) != 0) out.writeLong(Double.doubleToRawLongBits(e.avgOut));
        if ((mask & RECIPE_INPUT) != 0) out.writeShort(dictionary.get(e.recipeIn));
        if ((mask & RECIPE_OUTPUT) != 0) out.writeShort(dictionary.get(e.recipeOut));
        if ((mask & META) != 0) out.writeInt(e.metaTileId);
        if ((mask & CAPABILITY) != 0) out.writeByte(e.capabilityMask);
        if ((mask & RECOGNITION) != 0) out.writeByte(e.recognitionMode);
        if ((mask & EVIDENCE) != 0) out.writeByte(e.evidenceKind);
        if ((mask & LINK) != 0) out.writeByte(e.linkNodeType);
        if ((mask & DISPLAY) != 0) out.writeShort(dictionary.get(e.displayName));
        if ((mask & COLOR) != 0) out.writeByte(e.nameColor);
        if ((mask & BOLD) != 0) out.writeBoolean(e.nameBold);
        if ((mask & COORDINATES) != 0) {
            out.writeInt(e.dim);
            out.writeInt(e.x);
            out.writeInt(e.y);
            out.writeInt(e.z);
        }
    }

    private static Entry readRow(DataInputStream in, List<String> dictionary, Entry b, int m) throws IOException {
        String name = (m & NAME) != 0 ? string(in, dictionary, PacketSyncDeviceTerminalData.MAX_NAME_LEN) : b.name;
        byte state = (m & STATE) != 0 ? in.readByte() : b.state;
        byte power = (m & POWER) != 0 ? in.readByte() : b.powerType;
        long input = (m & INPUT) != 0 ? in.readLong() : b.instIn;
        long output = (m & OUTPUT) != 0 ? in.readLong() : b.instOut;
        double avgIn = (m & AVG_INPUT) != 0 ? Double.longBitsToDouble(in.readLong()) : b.avgIn;
        double avgOut = (m & AVG_OUTPUT) != 0 ? Double.longBitsToDouble(in.readLong()) : b.avgOut;
        String recipeIn = (m & RECIPE_INPUT) != 0 ? string(in, dictionary, PacketSyncDeviceTerminalData.MAX_RECIPE_LEN)
            : b.recipeIn;
        String recipeOut = (m & RECIPE_OUTPUT) != 0
            ? string(in, dictionary, PacketSyncDeviceTerminalData.MAX_RECIPE_LEN)
            : b.recipeOut;
        int meta = (m & META) != 0 ? in.readInt() : b.metaTileId;
        byte cap = (m & CAPABILITY) != 0 ? in.readByte() : b.capabilityMask;
        byte recognition = (m & RECOGNITION) != 0 ? in.readByte() : b.recognitionMode;
        byte evidence = (m & EVIDENCE) != 0 ? in.readByte() : b.evidenceKind;
        byte link = (m & LINK) != 0 ? in.readByte() : b.linkNodeType;
        String display = (m & DISPLAY) != 0 ? string(in, dictionary, DeviceTerminalNameRules.MAX_NAME_LENGTH)
            : b.displayName;
        byte color = (m & COLOR) != 0 ? in.readByte() : b.nameColor;
        int bold = (m & BOLD) != 0 ? in.readUnsignedByte() : b.nameBold ? 1 : 0;
        int dim = (m & COORDINATES) != 0 ? in.readInt() : b.dim;
        int x = (m & COORDINATES) != 0 ? in.readInt() : b.x;
        int y = (m & COORDINATES) != 0 ? in.readInt() : b.y;
        int z = (m & COORDINATES) != 0 ? in.readInt() : b.z;
        if (state < 0 || state > 3
            || power < 0
            || power > 1
            || cap < 0
            || cap > 3
            || recognition < 0
            || recognition > 2
            || evidence < 0
            || evidence > 3
            || link < 0
            || link > 3
            || color < 0
            || color > 15
            || bold > 1
            || !display.equals(DeviceTerminalNameRules.normalize(display))) throw invalid("Invalid row value");
        if (display.isEmpty() && (color != DeviceTerminalNameRules.DEFAULT_COLOR || bold != 0)) {
            throw invalid("Invalid empty alias style");
        }
        if ((recognition == 2 || state == 3) && (state != 3 || cap != 0
            || evidence != 0
            || input != 0
            || output != 0
            || Double.doubleToRawLongBits(avgIn) != 0
            || Double.doubleToRawLongBits(avgOut) != 0
            || !recipeIn.isEmpty()
            || !recipeOut.isEmpty())) throw invalid("Invalid ignored row");
        return new Entry(
            key(dim, x, y, z),
            name,
            state,
            power,
            input,
            output,
            avgIn,
            avgOut,
            dim,
            x,
            y,
            z,
            recipeIn,
            recipeOut,
            meta,
            evidence,
            link,
            display,
            color,
            bold == 1,
            cap,
            recognition);
    }

    private static int changed(Entry a, Entry b) {
        int mask = 0;
        if (!a.name.equals(b.name)) mask |= NAME;
        if (a.state != b.state) mask |= STATE;
        if (a.powerType != b.powerType) mask |= POWER;
        if (a.instIn != b.instIn) mask |= INPUT;
        if (a.instOut != b.instOut) mask |= OUTPUT;
        if (Double.doubleToRawLongBits(a.avgIn) != Double.doubleToRawLongBits(b.avgIn)) mask |= AVG_INPUT;
        if (Double.doubleToRawLongBits(a.avgOut) != Double.doubleToRawLongBits(b.avgOut)) mask |= AVG_OUTPUT;
        if (!a.recipeIn.equals(b.recipeIn)) mask |= RECIPE_INPUT;
        if (!a.recipeOut.equals(b.recipeOut)) mask |= RECIPE_OUTPUT;
        if (a.metaTileId != b.metaTileId) mask |= META;
        if (a.capabilityMask != b.capabilityMask) mask |= CAPABILITY;
        if (a.recognitionMode != b.recognitionMode) mask |= RECOGNITION;
        if (a.evidenceKind != b.evidenceKind) mask |= EVIDENCE;
        if (a.linkNodeType != b.linkNodeType) mask |= LINK;
        if (!a.displayName.equals(b.displayName)) mask |= DISPLAY;
        if (a.nameColor != b.nameColor) mask |= COLOR;
        if (a.nameBold != b.nameBold) mask |= BOLD;
        return mask;
    }

    private static void validateRows(List<Entry> rows) {
        if (rows == null || rows.size() > MAX_ROWS) throw invalid("Row count");
        Set<String> keys = new HashSet<>();
        for (Entry e : rows) {
            if (e == null || !key(e.dim, e.x, e.y, e.z).equals(e.key) || !keys.add(e.key)) {
                throw invalid("Invalid or duplicate coordinate identity");
            }
            // Entry caps and normalizes strings at construction. Reject invalid state instead of silently changing it.
            if (e.state < 0 || e.state > 3) throw invalid("Invalid row state");
            validText(e.name);
            validText(e.recipeIn);
            validText(e.recipeOut);
            validText(e.displayName);
        }
    }

    private static void validText(String value) {
        if (!StandardCharsets.UTF_8.newEncoder()
            .canEncode(value)) throw invalid("Malformed UTF-16");
    }

    private static String string(DataInputStream in, List<String> dictionary, int cap) throws IOException {
        int index = in.readUnsignedShort();
        if (index >= dictionary.size()) throw invalid("String reference");
        String value = dictionary.get(index);
        if (value.length() > cap) throw invalid("Field string length");
        return value;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Malformed UTF-8", malformed);
        }
    }

    private static String key(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
