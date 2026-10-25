package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class DesktopSelectionTest {

    @Test
    public void reverseMarqueeAddsToExistingSelectionAndCaptureLossRestoresIt() {
        DesktopLayout layout = new DesktopLayout();
        layout.add(new WidgetSpec("a", WidgetKind.DEVICE, -4, -3, 3, 3));
        layout.add(new WidgetSpec("b", WidgetKind.DEVICE, 1, 0, 3, 3));
        DesktopSelection selection = new DesktopSelection();
        selection.only("b");
        selection.begin(0, 0);
        selection.update(layout, -320, -256);
        assertTrue(
            selection.ids()
                .containsAll(Arrays.asList("a", "b")));
        selection.cancel();
        assertEquals(java.util.Collections.singleton("b"), selection.ids());
        selection.begin(-320, -256);
        selection.update(layout, 0, 0);
        selection.finish();
        assertTrue(selection.toggle("a") == false);
        assertEquals(java.util.Collections.singleton("b"), selection.ids());
    }

    @Test
    public void groupUsesOneGridDeltaAndValidatesEveryCollisionBeforeApplying() {
        DesktopLayout layout = new DesktopLayout();
        WidgetSpec a = new WidgetSpec("a", WidgetKind.DEVICE, 0, 0, 3, 3);
        WidgetSpec b = new WidgetSpec("b", WidgetKind.DEVICE, 3, 0, 3, 3);
        layout.add(a);
        layout.add(b);
        layout.add(new WidgetSpec("obstacle", WidgetKind.DEVICE, 10, 0, 3, 3));
        DesktopDrag drag = new DesktopDrag();
        assertTrue(drag.beginGroup(layout, Arrays.asList("a", "b"), "a", 7, 15));
        assertTrue(drag.update(103, 47));
        assertEquals(2, layout.find("a").column);
        assertEquals(5, layout.find("b").column);
        assertEquals(1, layout.find("a").row);
        assertEquals(1, layout.find("b").row);
        assertFalse(drag.update(7 + 5 * 64, 15));
        assertEquals(2, layout.find("a").column);
        assertEquals(5, layout.find("b").column);
        assertEquals(
            "a",
            layout.widgets()
                .get(0).id);
        assertEquals(
            "b",
            layout.widgets()
                .get(1).id);
        drag.cancel();
        assertSame(a, layout.find("a"));
        assertSame(b, layout.find("b"));
    }

    @Test
    public void outOfBoundsRejectsTheWholeGroupAndFinishRetainsTheLastValidResult() {
        DesktopLayout layout = new DesktopLayout();
        layout.add(new WidgetSpec("a", WidgetKind.DEVICE, DesktopLayout.MAX_COORD - 8, 0, 3, 3));
        layout.add(new WidgetSpec("b", WidgetKind.DEVICE, DesktopLayout.MAX_COORD - 4, 0, 3, 3));
        DesktopDrag drag = new DesktopDrag();
        assertTrue(drag.beginGroup(layout, Arrays.asList("a", "b"), "a", 0, 0));
        assertTrue(drag.update(64, 0));
        assertFalse(drag.update(128, 0));
        assertEquals(DesktopLayout.MAX_COORD - 7, layout.find("a").column);
        assertEquals(DesktopLayout.MAX_COORD - 3, layout.find("b").column);
        assertTrue(drag.finish());
        drag.cancel();
        assertEquals(DesktopLayout.MAX_COORD - 7, layout.find("a").column);
    }

    @Test
    public void savingOneProfileSucceedsEvenWhenAnotherQueuedProfileCannotBeWritten() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("gtswn-save-profiles-");
        String bad = DesktopStore.profile("bad", "player"), good = DesktopStore.profile("good", "player");
        java.nio.file.Path blocker = java.nio.file.Files.createDirectory(folder.resolve(bad + ".properties"));
        java.nio.file.Path child = java.nio.file.Files.createFile(blocker.resolve("occupied"));
        try {
            DesktopStore store = new DesktopStore(folder.toFile());
            DesktopLayout layout = new DesktopLayout();
            layout.add(new WidgetSpec("a", WidgetKind.DEVICE, 2, 0, 3, 3));
            store.put(bad, layout);
            assertTrue(store.saveLayout(good, layout, new DesktopViewport()));
            assertEquals(
                2,
                new DesktopStore(folder.toFile()).load(good)
                    .find("a").column);
            assertFalse(store.flush());
            assertEquals(
                2,
                new DesktopStore(folder.toFile()).load(good)
                    .find("a").column);
        } finally {
            java.nio.file.Files.deleteIfExists(folder.resolve(good + ".properties"));
            java.nio.file.Files.deleteIfExists(child);
            java.nio.file.Files.deleteIfExists(blocker);
            java.nio.file.Files.deleteIfExists(folder);
        }
    }

    @Test
    public void viewportPreferencesAndFailedWritesNeverCommitAnUnconfirmedLayout() throws Exception {
        java.io.File blocked = java.io.File.createTempFile("gtswn-store-", ".blocked");
        try {
            DesktopStore store = new DesktopStore(blocked);
            String profile = DesktopStore.profile("world", "player");
            DesktopLayout baseline = new DesktopLayout();
            baseline.add(new WidgetSpec("a", WidgetKind.DEVICE, 0, 0, 3, 3));
            DesktopSaveSession session = new DesktopSaveSession(baseline);
            DesktopLayout draft = baseline.copy();
            draft.move("a", 2, 0);
            DesktopViewport view = new DesktopViewport();
            view.panX = 200;
            assertFalse(session.save(store, profile, draft, view));
            assertTrue(session.unsaved(draft));
            assertEquals(
                0,
                session.saved()
                    .find("a").column);
            assertTrue(store.saveIfDue(System.nanoTime()));
            DesktopStore memory = new DesktopStore(null);
            memory.put(profile, baseline);
            memory.putView(profile, view);
            assertEquals(
                0,
                memory.load(profile)
                    .find("a").column);
            assertTrue(session.save(memory, profile, draft, view));
            assertFalse(session.unsaved(draft));
            assertEquals(
                2,
                memory.load(profile)
                    .find("a").column);
        } finally {
            assertTrue(blocked.delete());
        }
    }
}
