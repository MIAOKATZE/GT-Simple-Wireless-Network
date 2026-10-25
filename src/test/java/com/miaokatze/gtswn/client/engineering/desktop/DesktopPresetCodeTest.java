package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import org.junit.Test;

public class DesktopPresetCodeTest {

    @Test
    public void sharesOnlyVisualPreferencesAndDoesNotChangeTheOriginal() throws Exception {
        UUID terminal = UUID.randomUUID(), screen = UUID.randomUUID();
        DeviceBinding device = new DeviceBinding(terminal, "PRIVATE_MACHINE_KEY", 99, 120, -45, 999, 7);
        ResourceBinding resource = new ResourceBinding(
            terminal,
            screen,
            1,
            "v2:item:" + String.join("", Collections.nCopies(64, "b")),
            "PRIVATE_RESOURCE_NAME");
        WidgetEffects effects = new WidgetEffects(WidgetEffects.Style.SEGMENTED, false, 0x224466, 2.25, .4, 1.6);
        DesktopLayout original = new DesktopLayout().withTheme(DashboardTheme.INDUSTRIAL);
        assertTrue(
            original.add(
                new WidgetSpec(
                    UUID.randomUUID()
                        .toString(),
                    WidgetKind.DEVICE,
                    -4,
                    8,
                    4,
                    5).boundDevice(device)
                        .configured("My reading", Arrays.asList("status", "coordinates"), 1)
                        .presented(WidgetPresentation.KPI, "averageOutput", 900.0)
                        .styled(effects)
                        .withRate(false, 120)));
        assertTrue(original.add(new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 8, 2, 2).boundResource(resource)));
        Properties before = DesktopStore.encode(original);
        String code = DesktopPresetCode.export(original);
        assertEquals(before, DesktopStore.encode(original));
        String payload = new String(raw(code), StandardCharsets.ISO_8859_1);
        assertFalse(payload.contains(terminal.toString()));
        assertFalse(payload.contains(screen.toString()));
        assertFalse(payload.contains("PRIVATE_"));
        assertFalse(payload.contains(resource.key));
        assertFalse(
            payload.contains(
                original.widgets()
                    .get(0).id));
        DesktopLayout restored = DesktopPresetCode.decode(code);
        assertTrue(code.startsWith("GTSWN-DESKTOP:2:"));
        assertEquals(DashboardTheme.INDUSTRIAL, restored.theme());
        WidgetSpec reading = restored.find("card1");
        assertNull(reading.device);
        assertNull(restored.find("card2").resource);
        assertNull(restored.find("card2").screen);
        assertFalse(reading.autoDevicePending);
        assertFalse(restored.find("card2").autoResourcePending);
        assertEquals("My reading", reading.title);
        assertEquals(Arrays.asList("status", "coordinates"), reading.fields);
        assertEquals(-4, reading.column);
        assertEquals(8, reading.row);
        assertEquals(900, reading.userTarget, 0);
        assertEquals(WidgetEffects.Style.SEGMENTED, reading.effects.style);
        assertFalse(reading.effects.animated);
        assertEquals(0xFF224466, reading.effects.accentColor);
        assertEquals(2.25, reading.effects.speed, 0);
        assertEquals(.4, reading.effects.intensity, 0);
        assertEquals(1.6, reading.effects.glow, 0);
        assertFalse(reading.averageRate);
        assertEquals(120, reading.averageWindowSeconds);
        assertEquals(code, DesktopPresetCode.export(restored));
        assertEquals(code, DesktopPresetCode.export(DesktopAutoBind.initialize(original)));
    }

    @Test
    public void rejectsUnknownVersionsDamagedEncodingAndOversizedStreams() throws Exception {
        reject("GTSWN-DESKTOP:3:AAAA");
        reject(DesktopPresetCode.PREFIX + "%%%");
        reject(DesktopPresetCode.PREFIX + "AAAA");
        reject(DesktopPresetCode.PREFIX + String.join("", Collections.nCopies(DesktopPresetCode.MAX_CODE_LENGTH, "a")));
        reject(pack(new byte[DesktopPresetCode.MAX_DECOMPRESSED_BYTES + 1]));
        byte[] valid = raw(DesktopPresetCode.export(new DesktopLayout()));
        reject(pack(Arrays.copyOf(valid, valid.length + 1)));
        String code = DesktopPresetCode.export(new DesktopLayout());
        byte[] compressed = Base64.getUrlDecoder()
            .decode(code.substring(DesktopPresetCode.PREFIX.length()));
        reject(
            DesktopPresetCode.PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Arrays.copyOf(compressed, compressed.length - 1)));
    }

    @Test
    public void malformedSecondCardRejectsWholePresetRatherThanKeepingItsValidSibling() throws Exception {
        reject(cards(2, "card1", 0, "source", "card2", 8, "secret"));
        reject(cards(2, "card1", 0, "source", "card1", 8, "source"));
        reject(cards(2, "card1", 0, "source", "card2", 0, "source"));
        reject(cards(2, "card1", 0, "source", "card2", Integer.MAX_VALUE, "source"));
        reject(cards(33, "card1", 0, "source", "card2", 8, "source"));
        assertEquals(
            2,
            DesktopPresetCode.decode(cards(2, "card1", 0, "source", "card2", 8, "source"))
                .widgets()
                .size());
    }

    @Test
    public void versionOneCardsRetainTheirVisualsAndReceiveStandardThemeAndDefaultGlow() throws Exception {
        DesktopLayout restored = DesktopPresetCode.decode(cards(2, "card1", 0, "source", "card2", 8, "source"));
        assertEquals(DashboardTheme.STANDARD, restored.theme());
        assertEquals(WidgetEffects.DEFAULT.glow, restored.find("card1").effects.glow, 0);
        assertEquals(WidgetEffects.Style.ORBITAL, restored.find("card2").effects.style);
        assertTrue(
            DesktopPresetCode.export(restored)
                .startsWith(DesktopPresetCode.PREFIX));
    }

    @Test
    public void versionTwoRoundTripsEveryThemeAndNewStyle() {
        for (DashboardTheme theme : DashboardTheme.values())
            for (WidgetEffects.Style style : WidgetEffects.Style.values()) {
                DesktopLayout layout = new DesktopLayout().withTheme(theme);
                assertTrue(
                    layout.add(
                        new WidgetSpec("test", WidgetKind.MACHINES, 0, 0, 4, 3).styled(
                            WidgetEffects.DEFAULT.withStyle(style)
                                .withGlow(0))));
                String code = DesktopPresetCode.export(layout);
                DesktopLayout restored = DesktopPresetCode.decode(code);
                assertEquals(theme, restored.theme());
                assertEquals(style, restored.find("card1").effects.style);
                assertEquals(0, restored.find("card1").effects.glow, 0);
                assertEquals(code, DesktopPresetCode.export(restored));
            }
    }

    private static void reject(String code) {
        DesktopLayout previous = DesktopPreset.overviewLayout();
        Properties before = DesktopStore.encode(previous);
        try {
            DesktopPresetCode.decode(code);
            fail("Accepted invalid preset");
        } catch (IllegalArgumentException expected) {}
        assertEquals(before, DesktopStore.encode(previous));
    }

    private static String cards(int count, String first, int firstColumn, String firstField, String second,
        int secondColumn, String secondField) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(count);
        card(out, first, firstColumn, firstField);
        card(out, second, secondColumn, secondField);
        out.flush();
        return pack(bytes.toByteArray(), DesktopPresetCode.V1_PREFIX);
    }

    private static void card(DataOutputStream out, String id, int column, String field) throws Exception {
        out.writeUTF(id);
        out.writeUTF("MACHINES");
        out.writeInt(column);
        out.writeInt(0);
        out.writeInt(4);
        out.writeInt(3);
        out.writeUTF("");
        out.writeBoolean(true);
        out.writeInt(1);
        out.writeUTF(field);
        out.writeInt(1);
        out.writeUTF("DEFAULT");
        out.writeUTF("count");
        out.writeBoolean(false);
        out.writeInt(WidgetSpec.DEFAULT_BORDER_COLOR);
        out.writeBoolean(false);
        out.writeBoolean(true);
        out.writeInt(30);
        out.writeUTF("ORBITAL");
        out.writeBoolean(true);
        out.writeInt(WidgetEffects.DEFAULT.accentColor);
        out.writeDouble(1);
        out.writeDouble(.75);
    }

    private static String pack(byte[] raw) throws Exception {
        return pack(raw, DesktopPresetCode.PREFIX);
    }

    private static String pack(byte[] raw, String prefix) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) {
            out.write(raw);
        }
        return prefix + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes.toByteArray());
    }

    private static byte[] raw(String code) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] packed = Base64.getUrlDecoder()
            .decode(code.substring(DesktopPresetCode.PREFIX.length()));
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(packed))) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
