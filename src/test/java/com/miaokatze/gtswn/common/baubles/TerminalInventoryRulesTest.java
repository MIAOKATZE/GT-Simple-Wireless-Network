package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class TerminalInventoryRulesTest {

    private static final class Slots implements TerminalInventoryRules.Inventory<Object> {

        final Object[] stacks;

        Slots(int count) {
            stacks = new Object[count];
        }

        public int size() {
            return stacks.length;
        }

        public Object get(int slot) {
            return stacks[slot];
        }

        public void set(int slot, Object stack) {
            stacks[slot] = stack;
        }
    }

    @Test
    public void scanUsesPhysicalInventoryOrderAndCategoryLimits() {
        assertArrayEquals(
            new int[] { 3, 1, 5, 0, 2, 4, 7, 8 },
            TerminalInventoryRules.select(new int[] { 3, 1, 3, 0, 3, 2, 0, 3, 3, 3, 2, 1 }));
        assertArrayEquals(
            new int[] { -1, -1, -1, -1, -1, -1, -1, -1 },
            TerminalInventoryRules.select(new int[] { -1, -1 }));
    }

    @Test
    public void fullInventoryLeavesBothInventoriesUntouched() {
        Slots main = new Slots(36), baubles = new Slots(20);
        for (int i = 0; i < 36; i++) main.set(i, new Object());
        baubles.set(17, new Object());
        Object[] before = main.stacks.clone(), beforeBaubles = baubles.stacks.clone();
        assertNull(TerminalInventoryRules.take(main, 3, baubles, 17));
        assertArrayEquals(before, main.stacks);
        assertArrayEquals(beforeBaubles, baubles.stacks);
    }

    @Test
    public void displacementPrefersNextHotbarAndReturnMovesExactReferenceOnce() {
        Slots main = new Slots(36), baubles = new Slots(20);
        Object terminal = new Object(), held = new Object();
        main.set(8, held);
        baubles.set(17, terminal);
        TerminalInventoryRules.Borrow<Object> borrow = TerminalInventoryRules.take(main, 8, baubles, 17);
        assertSame(held, main.get(0));
        assertSame(terminal, main.get(8));
        assertNull(baubles.get(17));
        assertTrue(borrow.restore(main, 8, baubles));
        assertSame(terminal, baubles.get(17));
        assertNull(main.get(8));
        assertFalse(borrow.restore(main, 8, baubles));
    }

    @Test
    public void occupiedHotbarFallsBackToMainInventoryWithoutOverwriting() {
        Slots main = new Slots(36), baubles = new Slots(8);
        Arrays.fill(main.stacks, new Object());
        main.set(14, null);
        Object held = main.get(2), terminal = new Object();
        baubles.set(4, terminal);
        assertNotNull(TerminalInventoryRules.take(main, 2, baubles, 4));
        assertSame(held, main.get(14));
        assertSame(terminal, main.get(2));
    }

    @Test
    public void replacementEqualLookingStackChangedSlotAndOccupiedOriginCannotAuthorizeReturn() {
        Slots main = new Slots(36), baubles = new Slots(8);
        Object original = new String("same"), replacement = new String("same");
        baubles.set(6, original);
        TerminalInventoryRules.Borrow<Object> borrow = TerminalInventoryRules.take(main, 1, baubles, 6);
        assertFalse(borrow.valid(main, 2));
        main.set(1, replacement);
        assertFalse(borrow.restore(main, 1, baubles));
        assertSame(replacement, main.get(1));
        main.set(1, original);
        baubles.set(6, new Object());
        assertFalse(borrow.restore(main, 1, baubles));
        assertSame(original, main.get(1));
    }

    @Test
    public void selectingAnotherHotbarSlotDoesNotMoveOrInvalidateOriginalStack() {
        Slots main = new Slots(36), baubles = new Slots(8);
        Object original = new Object();
        baubles.set(5, original);
        TerminalInventoryRules.Borrow<Object> borrow = TerminalInventoryRules.take(main, 2, baubles, 5);
        assertTrue(borrow.present(main));
        assertFalse(borrow.valid(main, 4));
        assertFalse(borrow.restore(main, 4, baubles));
        assertTrue(borrow.present(main));
        assertTrue(borrow.valid(main, 2));
        assertTrue(borrow.restore(main, 2, baubles));
        assertSame(original, baubles.get(5));
    }
}
