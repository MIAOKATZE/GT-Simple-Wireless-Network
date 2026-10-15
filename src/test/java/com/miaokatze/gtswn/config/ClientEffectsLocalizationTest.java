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
    public void bilingualQuantumSecurityGuidanceRequiresExplicitEnablement() throws Exception {
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
                assertTrue(warning.contains("安全为OFF"));
                assertTrue(warning.contains("终端界面开启"));
                assertTrue(warning.contains("不会自动开启"));
                assertEquals("必须是网络拥有者或者持有‘安全’权限", management);
            } else {
                assertTrue(warning.contains("Anyone can recolor or migrate"));
                assertTrue(warning.contains("safety OFF"));
                assertTrue(warning.contains("quantum terminal UI"));
                assertTrue(warning.contains("Pairing alone does not enable it"));
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
            String voltage = String
                .format(Locale.ROOT, messages.getProperty("gtswn.command.effects.voltage"), "off", 75);
            assertTrue(voltage.contains("off"));
            assertTrue(voltage.contains("high/medium"));
            String tapUsage = messages.getProperty("gtswn.command.effects.tap_usage");
            assertTrue(tapUsage.contains("voltage <on|off>"));
            assertTrue(tapUsage.contains("ratio <25|50|75|100>"));
            assertNotNull(messages.getProperty("gtswn.gui.terminal.voltage_colors"));
            assertNotNull(messages.getProperty("gtswn.gui.terminal.voltage_ratio"));
            for (int percent : new int[] { 25, 50, 75, 100 }) {
                String status = String
                    .format(Locale.ROOT, messages.getProperty("gtswn.command.effects.voltage"), "on", percent);
                assertTrue(status.contains(percent + "%"));
            }
            if ("zh_CN".equals(language)) {
                assertTrue(voltage.contains("配色比例 75%"));
                assertTrue(voltage.contains("关闭恢复能源/动力色"));
                assertTrue(tapUsage.contains("识别机器的 GT 电压等级"));
            } else {
                assertTrue(voltage.contains("color ratio 75%"));
                assertTrue(voltage.contains("off restores energy/dynamo colors"));
                assertTrue(tapUsage.contains("recognized machine's GT voltage tier"));
            }
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
