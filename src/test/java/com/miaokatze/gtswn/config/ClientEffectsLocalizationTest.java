package com.miaokatze.gtswn.config;

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
                assertTrue(hint.contains("150%"));
                assertTrue(hint.contains("64"));
            }
        }
    }
}
