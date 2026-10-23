package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class WeakPositionIndexTest {

    @Test
    public void registrationOrderDoesNotChangeBoundedPrefix() {
        Object a = new Object(), b = new Object(), c = new Object();
        WeakPositionIndex<Object> first = new WeakPositionIndex<>(), second = new WeakPositionIndex<>();
        first.put(c, 2, 0, 0, 0);
        first.put(b, 0, 2, 0, 0);
        first.put(a, 0, -1, 0, 0);
        second.put(a, 0, -1, 0, 0);
        second.put(c, 2, 0, 0, 0);
        second.put(b, 0, 2, 0, 0);
        assertEquals(Arrays.asList(a, b), prefix(first, 2));
        assertEquals(prefix(first, 2), prefix(second, 2));
    }

    @Test
    public void unloadAndUnregisterRemoveEntriesWithoutChangingSurvivorOrder() {
        Object a = new Object(), b = new Object(), c = new Object();
        WeakPositionIndex<Object> index = new WeakPositionIndex<>();
        index.put(a, 0, 1, 0, 0);
        index.put(b, 0, 2, 0, 0);
        index.put(c, 1, 1, 0, 0);
        index.remove(b);
        index.remove(b);
        assertEquals(Arrays.asList(a, c), prefix(index, 10));
        index.remove(a);
        index.remove(c);
        assertTrue(prefix(index, 10).isEmpty());
    }

    @Test
    public void oldWorldOrTileRemovalCannotDeleteReplacementAtSamePosition() {
        Object oldTile = new Object(), newTile = new Object();
        WeakPositionIndex<Object> index = new WeakPositionIndex<>();
        index.put(oldTile, 0, 1, 2, 3);
        index.put(newTile, 0, 1, 2, 3);
        index.remove(oldTile);
        assertEquals(Arrays.asList(newTile), prefix(index, 10));
        index.remove(newTile);
        assertTrue(prefix(index, 10).isEmpty());
    }

    @Test
    public void movingSameInstanceUpdatesItsPositionWithoutGhostEntry() {
        Object moved = new Object(), other = new Object();
        WeakPositionIndex<Object> index = new WeakPositionIndex<>();
        index.put(moved, 0, 1, 2, 3);
        index.put(other, 0, 2, 2, 3);
        index.put(moved, 1, 1, 2, 3);
        assertEquals(Arrays.asList(other, moved), prefix(index, 10));
        index.remove(moved);
        assertEquals(Arrays.asList(other), prefix(index, 10));
    }

    @Test
    public void oldWorldUnloadOnlyRemovesItsOwnInstances() {
        Object oldA = new Object(), oldB = new Object(), replacement = new Object(), otherWorld = new Object();
        WeakPositionIndex<Object> index = new WeakPositionIndex<>();
        index.put(oldA, 0, 1, 2, 3);
        index.put(oldB, 0, 2, 2, 3);
        index.put(replacement, 0, 1, 2, 3);
        index.put(otherWorld, 1, 1, 2, 3);
        index.removeIf(value -> value == oldA || value == oldB);
        assertEquals(Arrays.asList(replacement, otherWorld), prefix(index, 10));
        index.remove(oldA);
        index.remove(oldB);
        assertEquals(Arrays.asList(replacement, otherWorld), prefix(index, 10));
    }

    private static List<Object> prefix(WeakPositionIndex<Object> index, int limit) {
        List<Object> result = new ArrayList<>();
        for (Object value : index.values()) {
            result.add(value);
            if (result.size() == limit) break;
        }
        return result;
    }
}
