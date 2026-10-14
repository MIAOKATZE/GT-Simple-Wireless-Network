package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.google.gson.JsonParser;

public class QuantumIncorporationConnectionResourcesTest {

    private static final String QUEST_KEY = "betterquesting.quest.R1RXTgAAAAAAAAAAAAAADg.desc=";

    @Test
    public void bothLanguagesDescribeCableOnlyBoundaryAndNativeRules() throws Exception {
        String english = description("en_US");
        String chinese = description("zh_CN");
        assertTrue(english.contains("only to actual ME cables"));
        assertTrue(english.contains("Use wire cutters"));
        assertTrue(english.contains("native direction, channel-capacity and security rules"));
        assertFalse(english.contains("including Quantum Nodes"));
        assertTrue(chinese.contains("只阻止与实际 ME 线缆的物理连接"));
        assertTrue(chinese.contains("用剪线钳切换"));
        assertTrue(chinese.contains("方向、频道容量和安全权限仍遵循原生规则"));
        assertFalse(chinese.contains("包括量子节点"));
    }

    @Test
    public void defaultQuestDescriptionMatchesEnglishLocalizationExactly() throws Exception {
        String json = resource(
            "assets/gtswn/bqquests/Quests/GTSimpleWireless-R1RXTgAAAAAAAAAAAAAAAQ==/"
                + "QuantumIncorpora-R1RXTgAAAAAAAAAAAAAADg==.json");
        String fallback = new JsonParser().parse(json)
            .getAsJsonObject()
            .getAsJsonObject("properties:10")
            .getAsJsonObject("betterquesting:10")
            .get("desc:8")
            .getAsString();
        assertEquals(description("en_US").replace("%n", "\n"), fallback);
    }

    private static String description(String locale) throws Exception {
        for (String line : resource("assets/gtswn/lang/" + locale + ".lang").split("\r?\n"))
            if (line.startsWith(QUEST_KEY)) return line.substring(QUEST_KEY.length());
        throw new AssertionError("Missing incorporation quest localization: " + locale);
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = QuantumIncorporationConnectionResourcesTest.class.getClassLoader()
            .getResourceAsStream(path)) {
            assertNotNull(path, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
