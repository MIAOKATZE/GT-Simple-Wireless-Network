package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaCodec.Encoded;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Pure wire tests; no world or game startup. These fixtures measure encoded payload, not network/TPS performance. */
public class DeviceTerminalDeltaCodecTest {

    @Test
    public void fullRoundTripPreservesLongsRawDoubleBitsAndBinaryCoordinates() {
        Entry row = row(
            Integer.MIN_VALUE,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            Double.longBitsToDouble(0x7ff8000000000042L),
            -0.0D,
            "设备名称");
        Encoded encoded = DeviceTerminalDeltaCodec.encode(null, Arrays.asList(row));
        assertFalse(encoded.delta);
        assertRows(Arrays.asList(row), DeviceTerminalDeltaCodec.decode(null, false, encoded.bytes));
        // Coordinates are serialized once as integers, not additionally as a coordinate key string.
        assertFalse(new String(encoded.bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(row.key));
    }

    @Test
    public void deltaRoundTripRetainsStaticFieldsAndExactLargeValues() {
        Entry old = row(1, 2, 3, 4, 5, "baseline name");
        Entry updated = row(
            1,
            Long.MAX_VALUE - 1,
            Long.MIN_VALUE + 1,
            Double.longBitsToDouble(0xfff8000000000101L),
            -0.0D,
            "baseline name");
        List<Entry> base = Arrays.asList(old);
        Encoded encoded = DeviceTerminalDeltaCodec.encode(base, Arrays.asList(updated));
        assertTrue(encoded.delta);
        assertEquals(0, encoded.bytes[3]);
        assertEquals(0, encoded.bytes[4]); // no static/recipe strings in this delta dictionary
        assertRows(Arrays.asList(updated), DeviceTerminalDeltaCodec.decode(base, true, encoded.bytes));
    }

    @Test
    public void reorderAddRemoveAndEmptyTargetAreExplicit() {
        Entry a = row(1, 1, 2, 3, 4, "one");
        Entry b = row(2, 1, 2, 3, 4, "two");
        Entry c = row(3, 1, 2, 3, 4, "three");
        Entry added = row(4, 1, 2, 3, 4, "four");
        List<Entry> base = Arrays.asList(a, b, c);
        List<Entry> target = Arrays.asList(c, added, a);
        Encoded encoded = DeviceTerminalDeltaCodec.encode(base, target);
        assertTrue(encoded.delta);
        assertRows(target, DeviceTerminalDeltaCodec.decode(base, encoded.delta, encoded.bytes));
        Encoded empty = DeviceTerminalDeltaCodec.encode(base, Collections.<Entry>emptyList());
        assertFalse(empty.delta); // equal-size empty full is dependency-free
        assertTrue(
            DeviceTerminalDeltaCodec.decode(base, empty.delta, empty.bytes)
                .isEmpty());
    }

    @Test
    public void aPageCanReferToAbsoluteRowsOfTheEntireCompleteBase() {
        List<Entry> base = rows(1024, false);
        List<Entry> target = Arrays.asList(base.get(1023), base.get(400), base.get(0));
        Encoded encoded = DeviceTerminalDeltaCodec.encode(base, target);
        assertTrue(encoded.delta);
        assertRows(target, DeviceTerminalDeltaCodec.decode(base, true, encoded.bytes));
        assertEquals(5 + 3 * 2, encoded.bytes.length);
    }

    @Test
    public void choosesFullWhenAllRowsAreNewAndDeltaWouldBeLarger() {
        List<Entry> base = Arrays.asList(row(1, 1, 2, 3, 4, "old"));
        List<Entry> target = Arrays.asList(row(2, 1, 2, 3, 4, "new"));
        Encoded encoded = DeviceTerminalDeltaCodec.encode(base, target);
        assertFalse(encoded.delta);
        assertArrayEquals(DeviceTerminalDeltaCodec.encode(null, target).bytes, encoded.bytes);
        assertRows(target, DeviceTerminalDeltaCodec.decode(null, false, encoded.bytes));
    }

    @Test
    public void allPowerChangingStillSavesPayloadWithoutResendingStaticStrings() {
        List<Entry> base = rows(1024, false);
        List<Entry> target = rows(1024, true);
        Encoded delta = DeviceTerminalDeltaCodec.encode(base, target);
        Encoded full = DeviceTerminalDeltaCodec.encode(null, target);
        assertTrue(delta.delta);
        assertEquals(5 + 1024 * (6 + 32), delta.bytes.length);
        assertTrue("Fixture encoded payload only", delta.bytes.length * 10 < full.bytes.length * 6);
        assertRows(target, DeviceTerminalDeltaCodec.decode(base, true, delta.bytes));
    }

    @Test
    public void staticChangesAndIgnoredTransitionsRoundTrip() {
        Entry old = row(1, 11, 12, 13, 14, "old name");
        Entry changed = new Entry(
            "0:1:64:0",
            "new name",
            (byte) 1,
            (byte) 1,
            11,
            12,
            13,
            14,
            0,
            1,
            64,
            0,
            "new recipe in",
            "new recipe out",
            987,
            (byte) 1,
            (byte) 2,
            "new alias",
            (byte) 6,
            true,
            (byte) 3,
            (byte) 1);
        List<Entry> base = Arrays.asList(old);
        Encoded encoded = DeviceTerminalDeltaCodec.encode(base, Arrays.asList(changed));
        assertRows(Arrays.asList(changed), DeviceTerminalDeltaCodec.decode(base, encoded.delta, encoded.bytes));
        Entry ignored = new Entry(
            "0:1:64:0",
            "new name",
            (byte) 1,
            (byte) 1,
            11,
            12,
            13,
            14,
            0,
            1,
            64,
            0,
            "old recipe",
            "old recipe",
            987,
            (byte) 1,
            (byte) 2,
            "new alias",
            (byte) 6,
            true,
            (byte) 3,
            (byte) 2);
        encoded = DeviceTerminalDeltaCodec.encode(Arrays.asList(changed), Arrays.asList(ignored));
        assertRows(
            Arrays.asList(ignored),
            DeviceTerminalDeltaCodec.decode(Arrays.asList(changed), encoded.delta, encoded.bytes));
    }

    @Test
    public void rejectsEveryTruncatedPrefixAndTrailingBytes() {
        List<Entry> rows = rows(3, false);
        Encoded encoded = DeviceTerminalDeltaCodec.encode(null, rows);
        for (int length = 0; length < encoded.bytes.length; length++) {
            assertInvalid(null, false, Arrays.copyOf(encoded.bytes, length));
        }
        assertInvalid(null, false, Arrays.copyOf(encoded.bytes, encoded.bytes.length + 1));
    }

    @Test
    public void rejectsUnknownSchemaMaskMissingBaseBadReferenceAndDuplicateRows() {
        List<Entry> base = rows(2, false);
        Encoded encoded = DeviceTerminalDeltaCodec.encode(base, base);
        byte[] bytes = encoded.bytes.clone();
        bytes[0] = 9;
        assertInvalid(base, true, bytes);
        bytes = DeviceTerminalDeltaCodec.encode(base, rows(2, true)).bytes.clone();
        bytes[7] = (byte) 0x80; // first changed-row mask has an unknown high bit
        assertInvalid(base, true, bytes);
        assertInvalid(null, true, encoded.bytes);
        bytes = encoded.bytes.clone();
        bytes[5] = 0x7f; // first base index out of range
        assertInvalid(base, true, bytes);
        bytes = encoded.bytes.clone();
        bytes[7] = 0;
        bytes[8] = 0; // second unchanged row reuses first base index
        assertInvalid(base, true, bytes);
    }

    @Test
    public void rejectsOversizedInputAndInvalidCoordinateIdentity() {
        assertInvalid(null, false, new byte[DeviceTerminalDeltaCodec.MAX_BYTES + 1]);
        try {
            DeviceTerminalDeltaCodec.encode(null, rows(1025, false));
            fail("Oversized row list");
        } catch (IllegalArgumentException expected) {}
        Entry mismatch = new Entry("0:99:64:0", "machine", (byte) 0, (byte) 0, 0, 0, 0, 0, 0, 1, 64, 0, "", "");
        try {
            DeviceTerminalDeltaCodec.encode(null, Arrays.asList(mismatch));
            fail("Coordinate/key mismatch");
        } catch (IllegalArgumentException expected) {}
        try {
            DeviceTerminalDeltaCodec.encode(null, Arrays.asList(rows(1, false).get(0), rows(1, false).get(0)));
            fail("Duplicate key");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void rejectsMalformedUtf8AndDictionaryReferences() {
        // schema, zero rows, dictionary count one, string length one, invalid UTF-8 continuation byte
        assertInvalid(null, false, new byte[] { 1, 0, 0, 0, 1, 0, 1, (byte) 0x80 });
        // schema, one full row, empty dictionary, name reference zero
        assertInvalid(null, false, new byte[] { 1, 0, 1, 0, 0, 0, 0 });
        // Duplicate string dictionary entries are not a canonical encoding.
        assertInvalid(null, false, new byte[] { 1, 0, 0, 0, 2, 0, 0, 0, 0 });
    }

    private static List<Entry> rows(int count, boolean updated) {
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(
                row(
                    i,
                    i + (updated ? 10000 : 1),
                    i + (updated ? 20000 : 2),
                    i + (updated ? 30000.25 : 3.25),
                    i + (updated ? 40000.5 : 4.5),
                    "Machine type " + i % 4));
        }
        return result;
    }

    private static Entry row(int x, long input, long output, double avgIn, double avgOut, String name) {
        return new Entry(
            "0:" + x + ":64:0",
            name,
            (byte) 1,
            (byte) 0,
            input,
            output,
            avgIn,
            avgOut,
            0,
            x,
            64,
            0,
            "static input recipe",
            "static output recipe",
            123,
            (byte) 1,
            (byte) 1,
            "Static alias",
            (byte) 10,
            true,
            (byte) 3,
            (byte) 0);
    }

    private static void assertInvalid(List<Entry> base, boolean delta, byte[] bytes) {
        try {
            DeviceTerminalDeltaCodec.decode(base, delta, bytes);
            fail("Malformed payload accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private static void assertRows(List<Entry> expected, List<Entry> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Entry a = expected.get(i), b = actual.get(i);
            assertEquals(a.key, b.key);
            assertEquals(a.name, b.name);
            assertEquals(a.state, b.state);
            assertEquals(a.powerType, b.powerType);
            assertEquals(a.instIn, b.instIn);
            assertEquals(a.instOut, b.instOut);
            assertEquals(Double.doubleToRawLongBits(a.avgIn), Double.doubleToRawLongBits(b.avgIn));
            assertEquals(Double.doubleToRawLongBits(a.avgOut), Double.doubleToRawLongBits(b.avgOut));
            assertEquals(a.dim, b.dim);
            assertEquals(a.x, b.x);
            assertEquals(a.y, b.y);
            assertEquals(a.z, b.z);
            assertEquals(a.recipeIn, b.recipeIn);
            assertEquals(a.recipeOut, b.recipeOut);
            assertEquals(a.metaTileId, b.metaTileId);
            assertEquals(a.capabilityMask, b.capabilityMask);
            assertEquals(a.recognitionMode, b.recognitionMode);
            assertEquals(a.evidenceKind, b.evidenceKind);
            assertEquals(a.linkNodeType, b.linkNodeType);
            assertEquals(a.displayName, b.displayName);
            assertEquals(a.nameColor, b.nameColor);
            assertEquals(a.nameBold, b.nameBold);
        }
    }
}
