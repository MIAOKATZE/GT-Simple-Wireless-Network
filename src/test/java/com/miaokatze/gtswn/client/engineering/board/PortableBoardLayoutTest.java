package com.miaokatze.gtswn.client.engineering.board;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PortableBoardLayoutTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void sourceIdentityIsStableAcrossBindingOrderButSeparatesWorldTerminalAndBindings() {
        String key = PortableBoardLayout.identity("server-a/player", "terminal", Arrays.asList("screen-b", "screen-a"));
        assertEquals(
            key,
            PortableBoardLayout.identity("server-a/player", "terminal", Arrays.asList("screen-a", "screen-b")));
        assertNotEquals(
            key,
            PortableBoardLayout.identity("server-b/player", "terminal", Arrays.asList("screen-a", "screen-b")));
        assertNotEquals(
            key,
            PortableBoardLayout.identity("server-a/player", "other", Arrays.asList("screen-a", "screen-b")));
        assertNotEquals(
            key,
            PortableBoardLayout.identity("server-a/player", "terminal", Collections.singletonList("screen-a")));
        assertNotEquals(
            PortableBoardLayout.identity("a", "b:c", Collections.emptyList()),
            PortableBoardLayout.identity("a:b", "c", Collections.emptyList()));
        assertTrue(key.matches("[0-9a-f]{64}"));
    }

    @Test
    public void narrowViewportsNeverKeepAnUnusableMultiColumnLayout() {
        PortableBoardLayout layout = new PortableBoardLayout();
        layout.columns = 4;
        assertEquals(1, layout.effectiveColumns(240));
        assertEquals(2, layout.effectiveColumns(700));
        assertEquals(4, layout.effectiveColumns(1600));
        layout.columns = 0;
        assertEquals(1, layout.effectiveColumns(0));
        assertEquals(3, layout.effectiveColumns(900));
    }

    @Test
    public void visibleSourcesGetPriorityWithoutStarvingOffscreenOrFailedScreens() {
        PortableBoardLayout layout = new PortableBoardLayout();
        List<String> panels = new ArrayList<>();
        for (int i = 0; i < 16; i++) panels.add("screen-" + i);
        layout.reconcile(panels);
        layout.hidden.add("screen-15");
        Map<String, Long> attempts = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        List<String> visible = Arrays.asList("screen-0", "screen-1");
        assertEquals("screen-0", layout.nextSource(panels, visible, attempts));
        for (int i = 0; i < 120; i++) {
            String next = layout.nextSource(panels, visible, attempts);
            // An unloaded screen still records its attempted request, independently of a successful detail timestamp.
            attempts.put(next, 100000L + i * 1100L);
            counts.put(next, counts.getOrDefault(next, 0) + 1);
        }
        assertEquals(15, counts.size());
        assertFalse(counts.containsKey("screen-15"));
        assertTrue(counts.get("screen-0") > counts.get("screen-14"));
        layout.hidden.addAll(panels);
        assertNull(layout.nextSource(panels, visible, attempts));
    }

    @Test
    public void loadedPreferencesDiscardWithdrawnSourcesAndClampInvalidDimensions() {
        Properties p = new Properties();
        p.setProperty("columns", "100");
        p.setProperty("size", "-8");
        p.setProperty("window", "NaN");
        p.setProperty("order", "gone,b,b,a");
        p.setProperty("hidden", "gone,a,a");
        p.setProperty("instant", "false");
        PortableBoardLayout layout = PortableBoardLayout.read(p, Arrays.asList("a", "b", "c"));
        assertEquals(Arrays.asList("b", "a", "c"), layout.order);
        assertEquals(Collections.singletonList("a"), layout.hidden);
        assertEquals(4, layout.columns);
        assertEquals(0, layout.size);
        assertEquals(0, layout.window);
        assertFalse(layout.instant);
        assertTrue(layout.average);
        layout.move("c", -1);
        assertEquals(Arrays.asList("b", "c", "a"), layout.order);
    }

    @Test
    public void preferencesPersistOnlyToTheirOwnSourceAndMalformedFilesRecover() throws Exception {
        Path directory = temporary.newFolder()
            .toPath();
        PortableBoardStore store = new PortableBoardStore(directory);
        String first = PortableBoardLayout.identity("one", "terminal", Collections.singletonList("a"));
        String second = PortableBoardLayout.identity("two", "terminal", Collections.singletonList("a"));
        PortableBoardLayout layout = new PortableBoardLayout();
        layout.reconcile(Collections.singletonList("a"));
        layout.hidden.add("a");
        layout.average = false;
        layout.window = 2;
        assertTrue(store.save(first, layout));
        PortableBoardLayout loaded = store.load(first, Collections.singletonList("a"));
        assertFalse(loaded.average);
        assertEquals(2, loaded.window);
        assertEquals(Collections.singletonList("a"), loaded.hidden);
        PortableBoardLayout other = store.load(second, Collections.singletonList("a"));
        assertTrue(other.average);
        assertTrue(other.hidden.isEmpty());
        Files.write(
            directory.resolve(first + ".properties"),
            "window=\\uBROKEN".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, store.load(first, Collections.singletonList("a")).window);
    }

    @Test(expected = IllegalArgumentException.class)
    public void sourceKeysCannotEscapeThePreferenceDirectory() throws Exception {
        new PortableBoardStore(
            temporary.newFolder()
                .toPath()).load("../../other", Collections.emptyList());
    }
}
