package com.miaokatze.gtswn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import org.junit.Test;

public class ClientEffectsLocalizationTest {

    @Test
    public void bilingualQuantumSecurityGuidanceIncludesExactRequestedChinese() throws Exception {
        for (String language : new String[] { "zh_CN", "en_US" }) {
            Properties messages = new Properties();
            try (InputStreamReader reader = new InputStreamReader(
                getClass().getResourceAsStream("/assets/gtswn/lang/" + language + ".lang"),
                StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
            String warning = messages.getProperty("gtswn.chat.quantum.open_network_hint");
            String management = messages.getProperty("gtswn.chat.quantum.manage_permission_required");
            assertNotNull(warning);
            assertNotNull(management);
            if ("zh_CN".equals(language)) {
                assertEquals("目前量子化AE网络没有安全策略，任何人都可以进行网络调色、网络迁移、量子化取消。绑定一次任意量子终端启用安全策略。", warning);
                assertEquals("必须是网络拥有者或者持有‘安全’权限", management);
            } else {
                assertTrue(warning.contains("Anyone can recolor or migrate"));
                assertTrue(warning.contains("ME Security Terminal"));
                assertTrue(management.contains("network owner or"));
                assertTrue(management.contains("'Security' permission"));
            }
        }
    }

    @Test
    public void bilingualUsageAndSuccessHintsFormatWithLiteralPercentages() throws Exception {
        for (String language : new String[] { "zh_CN", "en_US" }) {
            Properties messages = new Properties();
            try (InputStreamReader reader = new InputStreamReader(
                getClass().getResourceAsStream("/assets/gtswn/lang/" + language + ".lang"),
                StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
            String current = String
                .format(Locale.ROOT, messages.getProperty("gtswn.command.effects.current"), "tap", "on", 100, 100, 64);
            assertTrue(current.contains("100%"));
            for (String group : new String[] { "tap", "quantum" }) {
                String usage = String
                    .format(Locale.ROOT, messages.getProperty("gtswn.command.effects." + group + "_usage"));
                assertTrue(usage.contains("/gtswn " + group));
                assertTrue(usage.contains("0-200"));
                assertFalse(usage.contains("oldultra"));
            }
            for (String kind : new String[] { "link", "node", "incorporation" }) {
                String template = messages.getProperty("gtswn.chat.quality_hint." + kind);
                assertNotNull(template);
                String hint = String.format(Locale.ROOT, template, 9);
                assertTrue(hint.contains("/gtswn " + ("link".equals(kind) ? "tap" : "quantum")));
                assertTrue(hint.contains("100%"));
                assertFalse(hint.contains("150%"));
                assertTrue(hint.contains("64"));
            }
        }
    }
}
