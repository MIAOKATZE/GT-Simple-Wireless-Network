package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

public class DesktopHistoryTest {

    private DesktopLayout initial() {
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(new WidgetSpec("card", WidgetKind.DEVICE, 0, 0, 4, 4)));
        return layout;
    }

    @Test
    public void savedCheckpointSurvivesUndoRedoBranchingAndSnapshotMutation() {
        DesktopLayout layout = initial();
        DesktopHistory history = new DesktopHistory(layout);
        DesktopSaveSession save = new DesktopSaveSession(layout);
        assertFalse(history.record(layout, null));
        assertTrue(layout.move("card", 4, 0));
        assertTrue(history.record(layout, null));
        save.accept(layout);
        assertTrue(layout.move("card", 8, 0));
        history.record(layout, null);
        layout = history.undo();
        assertFalse(save.unsaved(layout));
        assertEquals(4, layout.find("card").column);
        layout = history.undo();
        assertTrue(save.unsaved(layout));
        assertEquals(0, layout.find("card").column);
        layout = history.redo();
        assertFalse(save.unsaved(layout));
        assertTrue(
            layout.replace(
                layout.find("card")
                    .titled("branch")));
        assertTrue(history.record(layout, null));
        assertNull(history.redo());
        assertEquals(
            "",
            history.undo()
                .find("card").title);
        assertEquals(
            "branch",
            history.redo()
                .find("card").title);
        history.reset(save.saved());
        assertNull(history.undo());
        assertNull(history.redo());
    }

    @Test
    public void textInputCoalescesButSeparateFocusAndSaveSessionsDoNot() {
        DesktopLayout layout = initial();
        DesktopHistory history = new DesktopHistory(layout);
        Object input = new Object();
        for (String title : new String[] { "A", "AB", "ABC" }) {
            layout.replace(
                layout.find("card")
                    .titled(title));
            history.record(layout, input);
        }
        history.endGroup();
        layout.replace(
            layout.find("card")
                .titled("ABCD"));
        history.record(layout, input);
        assertEquals(
            "ABC",
            history.undo()
                .find("card").title);
        assertEquals(
            "",
            history.undo()
                .find("card").title);
        assertNull(history.undo());
        assertEquals(
            "ABC",
            history.redo()
                .find("card").title);
        assertEquals(
            "ABCD",
            history.redo()
                .find("card").title);
    }

    @Test
    public void historyRetainsOnly64ActionsEvenAt512CardsAndDoesNotKeepLiveContainers() {
        DesktopLayout layout = new DesktopLayout();
        for (int index = 0; index < 512; index++)
            assertTrue(layout.add(new WidgetSpec("card-" + index, WidgetKind.DEVICE, index * 4, 0, 4, 4)));
        DesktopHistory history = new DesktopHistory(layout);
        for (int index = 1; index <= 90; index++) {
            layout.replace(
                layout.find("card-0")
                    .titled("edit-" + index));
            history.record(layout, null);
        }
        for (int index = 89; index >= 26; index--) {
            DesktopLayout restored = history.undo();
            assertEquals(
                512,
                restored.widgets()
                    .size());
            assertEquals("edit-" + index, restored.find("card-0").title);
            restored.remove("card-1");
        }
        assertNull(history.undo());
        assertEquals(
            512,
            history.redo()
                .widgets()
                .size());
    }

    @Test
    public void automaticBindingsRebaseHistoryWithoutMakingAnActionOrOverwritingExplicitClears() {
        DesktopLayout layout = initial();
        layout.replace(
            layout.find("card")
                .withAutoDevice(true));
        DesktopHistory history = new DesktopHistory(layout);
        layout.replace(
            layout.find("card")
                .titled("title"));
        history.record(layout, null);
        DesktopLayout before = layout.copy();
        DeviceBinding binding = new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4);
        layout.replace(
            layout.find("card")
                .boundDevice(binding));
        history.acceptAutomatic(before, layout);
        DesktopLayout initialized = layout.copy();
        DesktopLayout restored = history.undo();
        assertSame(binding, restored.find("card").device);
        assertEquals("", restored.find("card").title);
        assertNull(history.undo());
        layout = history.redo();
        layout.replace(
            layout.find("card")
                .boundDevice(null));
        history.record(layout, null);
        layout.replace(
            layout.find("card")
                .titled("cleared"));
        history.record(layout, null);
        history.acceptAutomatic(before, initialized);
        assertNull(
            history.undo()
                .find("card").device);
        assertSame(
            binding,
            history.undo()
                .find("card").device);
        assertNull(
            history.redo()
                .find("card").device);
    }

    @Test
    public void restoredResourcesKeepSourceScreenPrecisionStyleAndGeometry() {
        UUID source = UUID.randomUUID();
        UUID screen = UUID.randomUUID();
        ResourceBinding resource = new ResourceBinding(
            source,
            screen,
            1,
            "v2:item:" + String.join("", java.util.Collections.nCopies(64, "a")),
            "Ore");
        DesktopLayout layout = new DesktopLayout();
        WidgetSpec card = new WidgetSpec("resource", WidgetKind.RESOURCES, 0, 0, 4, 4).boundResource(resource);
        assertTrue(layout.add(card));
        DesktopHistory history = new DesktopHistory(layout);
        layout.replace(
            card.titled("changed")
                .styled(WidgetEffects.DEFAULT.withStyle(WidgetEffects.Style.SIGNAL)));
        history.record(layout, null);
        DesktopLayout restored = history.undo();
        assertSame(resource, restored.find("resource").resource);
        assertEquals(source, restored.find("resource").screen.source);
        assertEquals(screen, restored.find("resource").screen.screen);
        assertEquals(4, restored.find("resource").columns);
        assertEquals(
            WidgetEffects.Style.SIGNAL,
            history.redo()
                .find("resource").effects.style);
    }
}
