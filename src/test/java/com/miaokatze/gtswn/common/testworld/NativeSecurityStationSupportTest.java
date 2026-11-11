// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import org.junit.Test;

public class NativeSecurityStationSupportTest {

    @Test
    public void bothNativeChatRoutesKeepBoundedIndependentEvidence() throws Exception {
        NativeSecurityStationSupport.RecordingActor actor = collector();
        actor.addChatMessage(new ChatComponentTranslation("gtswn.chat.quantum.no_permission"));
        actor.addChatComponentMessage(new ChatComponentText("actual block reply"));
        List<String> first = actor.messages();
        assertEquals(2, first.size());
        assertTrue(
            first.get(0)
                .contains("\"translate\":\"gtswn.chat.quantum.no_permission\""));
        assertTrue(
            first.get(1)
                .contains("actual block reply"));
        for (int index = 0; index < 40; index++) actor.addChatMessage(new ChatComponentText("message " + index));
        assertEquals(
            32,
            actor.messages()
                .size());
        assertTrue(
            actor.messages()
                .get(0)
                .endsWith(" | message 8"));
        actor.clearMessages();
        assertTrue(
            actor.messages()
                .isEmpty());
        assertEquals(2, first.size());
        assertTrue(
            first.get(1)
                .contains("actual block reply"));
    }

    /** Only the chat collector is under test; no world, native permissions or online actor are simulated. */
    private static NativeSecurityStationSupport.RecordingActor collector() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        NativeSecurityStationSupport.RecordingActor actor = (NativeSecurityStationSupport.RecordingActor) unsafe
            .getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), NativeSecurityStationSupport.RecordingActor.class);
        Field messages = NativeSecurityStationSupport.RecordingActor.class.getDeclaredField("messages");
        messages.setAccessible(true);
        messages.set(actor, new ArrayList<String>());
        return actor;
    }
}
