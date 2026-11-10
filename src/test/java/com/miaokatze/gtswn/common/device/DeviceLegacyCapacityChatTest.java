package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;
import net.minecraft.util.StringTranslate;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DeviceLegacyCapacityChatTest {

    private static final String KEY = "gtswn.device.gui.legacy_total";

    @SuppressWarnings("unchecked")
    private static Map<String, String> language(String field) throws Exception {
        Field collector = StatCollector.class.getDeclaredField(field);
        collector.setAccessible(true);
        Field values = StringTranslate.class.getDeclaredField("languageList");
        values.setAccessible(true);
        return (Map<String, String>) values.get(collector.get(null));
    }

    private static void restore(Map<String, String> map, String previous) {
        if (previous == null) map.remove(KEY);
        else map.put(KEY, previous);
    }

    @Test
    public void vanillaChatWireKeepsActualTotalAndLegacyLimitWithoutTheNewClientLanguageKey() throws Exception {
        Map<String, String> localized = language("localizedName"), fallback = language("fallbackTranslator");
        String oldLocalized = localized.get(KEY), oldFallback = fallback.get(KEY);
        String template;
        try (InputStream resource = getClass().getResourceAsStream("/assets/gtswn/lang/en_US.lang")) {
            assertNotNull(resource);
            template = StringTranslate.parseLangFile(resource)
                .get(KEY);
        }
        assertNotNull(template);
        try {
            for (int total : new int[] { 1025, 65536 }) {
                // FML injects the current mod's en_US into the server table before requests are handled.
                localized.put(KEY, template);
                IChatComponent serverMessage = DeviceTerminalRequestQueue.legacyCapacityMessage(total);
                String expected = serverMessage.getUnformattedText();
                assertTrue(expected.contains(Integer.toString(total)));
                assertTrue(expected.contains("1024"));
                assertTrue(expected.contains("legacy connection"));
                // Emulate the old peer: neither its selected language nor vanilla fallback knows the key.
                localized.remove(KEY);
                fallback.remove(KEY);
                assertFalse(StatCollector.canTranslate(KEY));
                assertEquals(KEY, new ChatComponentTranslation(KEY, total, 1024).getUnformattedText());
                ByteBuf bytes = Unpooled.buffer();
                try {
                    new S02PacketChat(serverMessage).writePacketData(new PacketBuffer(bytes));
                    S02PacketChat decoded = new S02PacketChat();
                    decoded.readPacketData(new PacketBuffer(bytes));
                    assertEquals(0, bytes.readableBytes());
                    IChatComponent received = decoded.func_148915_c();
                    assertEquals(expected, received.getUnformattedText());
                    String json = IChatComponent.Serializer.func_150696_a(received);
                    assertFalse(json.contains("\"translate\""));
                    assertTrue(json.contains(Integer.toString(total)));
                    assertTrue(json.contains("1024"));
                } finally {
                    bytes.release();
                }
            }
        } finally {
            restore(localized, oldLocalized);
            restore(fallback, oldFallback);
        }
    }
}
