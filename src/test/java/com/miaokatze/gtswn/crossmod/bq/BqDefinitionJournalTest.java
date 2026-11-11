// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.crossmod.bq;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

import com.google.gson.JsonObject;

public class BqDefinitionJournalTest {

    private static JsonObject values(String a, String b) {
        JsonObject result = new JsonObject();
        if (a != null) result.addProperty("q:a", a);
        if (b != null) result.addProperty("e:a:b", b);
        return result;
    }

    @Test
    public void intentPrecedesMutationAndCrashReplaysOnlyOldDefinitions() throws Exception {
        Path file = Files.createTempDirectory("gtswn-bq-intent")
            .resolve("intent.json");
        JsonObject old = values("old", "old-position"), target = values("new", "new-position");
        BqDefinitionJournal first = BqDefinitionJournal.prepare(file, "2", true, old, target);
        assertTrue(Files.size(file) > 0);
        assertTrue(first.shouldApply("q:a"));
        assertFalse(first.matches(old));
        BqDefinitionJournal restarted = BqDefinitionJournal
            .prepare(file, "2", false, values("new", "old-position"), target);
        assertFalse(restarted.shouldApply("q:a"));
        assertTrue(restarted.shouldApply("e:a:b"));
        restarted.expect(target);
        assertTrue(restarted.matches(target));
        restarted.committed();
        assertNull(BqDefinitionJournal.prepare(file, "2", false, target, target));
    }

    @Test
    public void editorConflictSurvivesRepeatedStartupAndIsExplicitlyRecorded() throws Exception {
        Path file = Files.createTempDirectory("gtswn-bq-editor")
            .resolve("intent.json");
        BqDefinitionJournal.prepare(file, "2", true, values("old", "old"), values("new", "new"));
        JsonObject edited = values("admin-edit", "old");
        BqDefinitionJournal replay = BqDefinitionJournal.prepare(file, "2", false, edited, values("new", "new"));
        assertEquals(1, replay.conflicts());
        assertFalse(replay.shouldApply("q:a"));
        assertTrue(replay.shouldApply("e:a:b"));
        JsonObject fixed = values("admin-edit", "new");
        replay.expect(fixed);
        BqDefinitionJournal third = BqDefinitionJournal.prepare(file, "2", false, fixed, values("new", "new"));
        assertFalse(third.shouldApply("q:a"));
        assertEquals(1, third.conflicts());
        third.committed();
        assertTrue(
            new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
                .contains("COMMITTED_WITH_CONFLICTS"));
    }

    @Test
    public void runningEditorChangeIsAcceptedButUnsavedBytesCannotCommit() throws Exception {
        Path file = Files.createTempDirectory("gtswn-bq-live")
            .resolve("intent.json");
        JsonObject target = values("new", "new");
        BqDefinitionJournal journal = BqDefinitionJournal.prepare(file, "2", true, values("old", "old"), target);
        journal.expect(target);
        JsonObject admin = values("admin", "new");
        journal.acceptLiveChanges(admin);
        assertFalse(journal.matches(target));
        assertTrue(journal.matches(admin));
        assertEquals(1, journal.conflicts());
    }

    @Test
    public void incompleteDatabaseAndRemovedEntryRemainDetectable() throws Exception {
        Path file = Files.createTempDirectory("gtswn-bq-missing")
            .resolve("intent.json");
        JsonObject target = values("new", null);
        target.add("e:a:b", com.google.gson.JsonNull.INSTANCE);
        BqDefinitionJournal journal = BqDefinitionJournal.prepare(file, "2", true, values("old", "old"), target);
        assertTrue(journal.shouldApply("e:a:b"));
        assertFalse(journal.matches(values(null, null)));
        assertTrue(journal.matches(values("new", null)));
    }

    @Test
    public void malformedIntentFailsClosedAndIsPreserved() throws Exception {
        Path file = Files.createTempDirectory("gtswn-bq-bad")
            .resolve("intent.json");
        Files.write(file, "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            BqDefinitionJournal.prepare(file, "2", true, values("old", null), values("new", null));
            fail();
        } catch (java.io.IOException expected) {
            assertTrue(Files.size(file) > 0);
        }
    }

    @Test
    public void failedIntentPublicationNeverReturnsAnApplicablePlan() throws Exception {
        Path file = Files.createTempDirectory("gtswn-bq-blocked");
        try {
            BqDefinitionJournal.prepare(file, "2", true, values("old", null), values("new", null));
            fail();
        } catch (java.io.IOException expected) {
            assertTrue(Files.isDirectory(file));
        }
    }

    @Test
    public void diskProjectionUsesNativeQuestLineIdAndScopesOwnedKeys() throws Exception {
        net.minecraft.nbt.NBTTagCompound database = new net.minecraft.nbt.NBTTagCompound();
        net.minecraft.nbt.NBTTagList lines = new net.minecraft.nbt.NBTTagList();
        net.minecraft.nbt.NBTTagCompound line = new net.minecraft.nbt.NBTTagCompound();
        java.util.UUID lineId = new java.util.UUID(0x4754574e00000000L, 1);
        betterquesting.api.utils.NBTConverter.UuidValueType.QUEST_LINE.writeId(lineId, line);
        net.minecraft.nbt.NBTTagList entries = new net.minecraft.nbt.NBTTagList();
        net.minecraft.nbt.NBTTagCompound entry = new net.minecraft.nbt.NBTTagCompound();
        java.util.UUID questId = new java.util.UUID(0x4754574e00000000L, 2);
        betterquesting.api.utils.NBTConverter.UuidValueType.QUEST.writeId(questId, entry);
        entry.setInteger("x", 123);
        entries.appendTag(entry);
        line.setTag("quests", entries);
        lines.appendTag(line);
        database.setTag("questLines", lines);
        java.lang.reflect.Method projection = BqQuestInjector.class
            .getDeclaredMethod("snapshot", net.minecraft.nbt.NBTTagCompound.class);
        projection.setAccessible(true);
        JsonObject projected = (JsonObject) projection.invoke(null, database);
        assertTrue(projected.has("e:" + lineId + ":" + questId));
        assertEquals(
            1,
            projected.entrySet()
                .size());
    }
}
