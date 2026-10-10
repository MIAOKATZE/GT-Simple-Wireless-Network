package com.miaokatze.gtswn.common.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StringTranslate;

import org.junit.Test;

public class TerminalSupplyStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    public void bilingualSuccessComponentsRenderWithNativeTranslationFormatter() throws Exception {
        Field instanceField = StringTranslate.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Field languageField = StringTranslate.class.getDeclaredField("languageList");
        languageField.setAccessible(true);
        Map<String, String> original = new HashMap<>((Map<String, String>) languageField.get(instanceField.get(null)));
        try {
            for (String language : new String[] { "zh_CN", "en_US" }) {
                Properties messages = new Properties();
                try (InputStreamReader reader = new InputStreamReader(
                    getClass().getResourceAsStream("/assets/gtswn/lang/" + language + ".lang"),
                    StandardCharsets.UTF_8)) {
                    messages.load(reader);
                }
                Map<String, String> translations = new HashMap<>();
                for (String key : messages.stringPropertyNames()) translations.put(key, messages.getProperty(key));
                // Raw templates bypass StringTranslate's numeric-placeholder rewriting and exercise the chat parser.
                StringTranslate.replaceWith(translations);
                for (TerminalSupplyStore.Kind kind : TerminalSupplyStore.Kind.values()) {
                    String name = messages.getProperty(
                        kind == TerminalSupplyStore.Kind.ANCHOR ? "gtswn.gui.terminal.anchor"
                            : "gtswn.gui.terminal.tube");
                    for (int remaining : new int[] { 0, 1, 640 }) {
                        IChatComponent success = TerminalSupplyStore.appendRemaining(
                            new ChatComponentText("success"),
                            kind,
                            remaining == 0 ? 1 : remaining,
                            remaining);
                        String rendered = success.getUnformattedText();
                        assertTrue(rendered.contains(name));
                        assertTrue(rendered.contains(Integer.toString(remaining)));
                        assertEquals(
                            rendered,
                            success.createCopy()
                                .getUnformattedText());
                    }
                }
            }
        } finally {
            StringTranslate.replaceWith(original);
        }
    }

    @Test
    public void successHintIncludesLastConsumedSupplyAndUnconsumedPreload() {
        for (TerminalSupplyStore.Kind kind : TerminalSupplyStore.Kind.values()) {
            for (int remaining : new int[] { 0, 1, 640 }) {
                IChatComponent success = TerminalSupplyStore
                    .appendRemaining(new ChatComponentText("success"), kind, remaining == 0 ? 1 : remaining, remaining);
                assertEquals(
                    1,
                    success.getSiblings()
                        .size());
                ChatComponentTranslation hint = (ChatComponentTranslation) success.getSiblings()
                    .get(0);
                assertEquals("gtswn.chat.terminal.preloaded_remaining", hint.getKey());
                assertEquals(remaining, hint.getFormatArgs()[1]);
                assertEquals(
                    kind == TerminalSupplyStore.Kind.ANCHOR ? "gtswn.gui.terminal.anchor" : "gtswn.gui.terminal.tube",
                    ((ChatComponentTranslation) hint.getFormatArgs()[0]).getKey());
                IChatComponent delayedCopy = success.createCopy();
                assertEquals(
                    remaining,
                    ((ChatComponentTranslation) delayedCopy.getSiblings()
                        .get(0)).getFormatArgs()[1]);
            }
        }
    }

    @Test
    public void noPreloadAtOperationStartSuppressesSuccessHint() {
        IChatComponent success = TerminalSupplyStore
            .appendRemaining(new ChatComponentText("success"), TerminalSupplyStore.Kind.ANCHOR, 0, 0);
        assertTrue(
            success.getSiblings()
                .isEmpty());
        assertEquals("success", success.getUnformattedText());
    }

    @Test
    public void fullSuppliesSurviveDiskSerializationAndDeathPersistentCopy() throws Exception {
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setString("otherMod", "retain");
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.ANCHOR, 640);
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.TUBE, 129);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(persisted, bytes);
        NBTTagCompound restored = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
        NBTTagCompound clone = (NBTTagCompound) restored.copy();
        assertEquals(640, TerminalSupplyStore.count(clone, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(129, TerminalSupplyStore.count(clone, TerminalSupplyStore.Kind.TUBE));
        assertEquals("retain", clone.getString("otherMod"));
        TerminalSupplyStore.set(clone, TerminalSupplyStore.Kind.ANCHOR, 639);
        assertEquals(640, TerminalSupplyStore.count(restored, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(129, TerminalSupplyStore.count(clone, TerminalSupplyStore.Kind.TUBE));
    }

    @Test
    public void malformedCountsCannotCreateNegativeOrOversizedSupplies() {
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setInteger("GTSWN_Supply_ANCHOR", Integer.MAX_VALUE);
        persisted.setInteger("GTSWN_Supply_TUBE", Integer.MIN_VALUE);
        assertEquals(640, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(0, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.TUBE));
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.ANCHOR, -1);
        TerminalSupplyStore.set(persisted, TerminalSupplyStore.Kind.TUBE, 641);
        assertEquals(0, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(640, TerminalSupplyStore.count(persisted, TerminalSupplyStore.Kind.TUBE));
    }
}
