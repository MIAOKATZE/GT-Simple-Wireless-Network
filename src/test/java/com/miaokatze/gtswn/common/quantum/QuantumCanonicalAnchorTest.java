package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class QuantumCanonicalAnchorTest {

    @Test
    public void firstClickSurvivesExpansionUnloadAndSaveReload() {
        QuantumControllerRegistry registry = registry();
        assertTrue(registry.quantizeAll(members(0, 1), pos(1)));
        QuantumControllerRegistry.CanonicalAnchor first = registry.canonicalAnchor(pos(0));
        String generation = first.generation;
        assertSame(first, registry.canonicalAnchor(pos(1)));
        assertSame(first, registry.resolveAnchor(pos(0), generation));
        assertTrue(registry.quantizeAll(members(0, 1, 2, 3), pos(3)));
        assertSame(first, registry.canonicalAnchor(pos(3)));
        registry.unload();
        QuantumControllerRegistry restored = reload(registry);
        for (long member : members(0, 1, 2, 3)) {
            assertEquals(pos(1), restored.canonicalAnchor(member).position);
            assertEquals(generation, restored.canonicalAnchor(member).generation);
        }
        assertNull("New structures never grant generation-less legacy adoption", restored.resolveAnchor(pos(0), ""));
        assertEquals(new AnchorKey(7, 1, 64, 0), restored.canonicalKey(new AnchorKey(7, 3, 64, 0)));
    }

    @Test
    public void legacyAliasesUpgradeOnceAndCannotInheritRebuiltCoordinates() {
        NBTTagCompound old = new NBTTagCompound();
        old.setIntArray("controllers", new int[] { 7, 0, 64, 0, 7, 1, 64, 0, 7, 2, 64, 0 });
        QuantumControllerRegistry migrated = registry();
        migrated.readFromNBT(old);
        QuantumControllerRegistry.CanonicalAnchor first = migrated.resolveAnchor(pos(2), "");
        assertNotNull(first);
        QuantumControllerRegistry saved = reload(migrated);
        for (int x = 0; x < 3; x++) {
            assertEquals(first.position, saved.resolveAnchor(pos(x), "").position);
            assertEquals(first.generation, saved.resolveAnchor(pos(x), "").generation);
        }
        for (long member : members(0, 1, 2)) saved.dequantize(member);
        assertTrue(saved.quantizeAll(members(0, 1, 2), pos(2)));
        assertNotEquals(first.generation, saved.canonicalAnchor(pos(2)).generation);
        assertNull(saved.resolveAnchor(pos(2), first.generation));
        assertNull(reload(saved).resolveAnchor(pos(2), ""));
    }

    @Test
    public void expansionCannotJoinTwoPreviouslyRegisteredGenerations() {
        QuantumControllerRegistry registry = registry();
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        long revision = registry.getRevision();
        assertFalse(registry.canExtendStructure(members(0, 1, 2)));
        assertFalse(registry.quantizeAll(members(0, 1, 2), pos(1)));
        assertFalse(registry.isQuantized(pos(1)));
        assertEquals(revision, registry.getRevision());
        assertNotEquals(registry.canonicalAnchor(pos(0)).generation, registry.canonicalAnchor(pos(2)).generation);
    }

    @Test
    public void explicitTransferMovesCanonicalWithoutChangingGenerationAndOldSourceExpires() {
        QuantumControllerRegistry source = registry(), target = registry();
        source.quantizeAll(members(0, 1), pos(1));
        String generation = source.canonicalAnchor(pos(0)).generation;
        assertTrue(target.quantizeTransferred(members(10, 11), pos(11), generation));
        for (long member : members(0, 1)) source.dequantize(member);
        assertNull(source.resolveAnchor(pos(0), generation));
        assertEquals(pos(11), reload(target).resolveAnchor(pos(10), generation).position);
    }

    @Test
    public void savedSchemaOneCanceledHintsUseCurrentCoordinateAliasesOnceAndFutureRemovalInvalidatesThem() {
        QuantumControllerRegistry current = registry();
        current.quantizeAll(members(0, 1, 2), pos(1));
        String generation = current.canonicalAnchor(pos(0)).generation;
        NBTTagCompound old = new NBTTagCompound();
        current.writeToNBT(old);
        old.setInteger("anchorSchema", 1);
        net.minecraft.nbt.NBTTagList aliases = new net.minecraft.nbt.NBTTagList();
        for (long member : members(0, 1, 2)) {
            NBTTagCompound alias = new NBTTagCompound();
            alias.setLong("pos", member);
            alias.setString("generation", "erased-by-old-cancel");
            aliases.appendTag(alias);
        }
        old.setTag("legacyAnchorAliases", aliases);
        current.readFromNBT(old);
        assertEquals(generation, current.canonicalAnchor(pos(0)).generation);
        for (long member : members(0, 1, 2)) {
            assertEquals(pos(1), current.resolveAnchor(member, "erased-by-old-cancel").position);
            assertEquals(generation, current.resolveAnchor(member, "").generation);
        }
        current = reload(current);
        for (long member : members(0, 1, 2)) current.dequantize(member);
        current.quantizeAll(members(0, 1, 2), pos(2));
        assertNotEquals(generation, current.canonicalAnchor(pos(0)).generation);
        current = reload(current);
        assertNull(current.resolveAnchor(pos(0), "erased-by-old-cancel"));
        assertNull(current.resolveAnchor(pos(1), generation));
        assertNull(current.resolveAnchor(pos(2), ""));
        assertTrue(
            current.legacyMembers(members(0, 1, 2), current.canonicalAnchor(pos(0)).generation)
                .isEmpty());
    }

    @Test
    public void typedJournalDoesNotRedirectARebuiltGenerationAndPreservesLegacyChains() {
        QuantumMigrationJournal journal = new QuantumMigrationJournal("test");
        AnchorKey a = new AnchorKey(7, 0, 64, 0), b = new AnchorKey(8, 10, 64, 0), c = new AnchorKey(9, 20, 64, 0);
        journal.append(7, members(0, 1), b, "old", "old", members(0));
        journal.append(8, members(10, 11), c, "old", "old");
        NBTTagCompound save = new NBTTagCompound();
        journal.writeToNBT(save);
        QuantumMigrationJournal restored = new QuantumMigrationJournal("test");
        restored.readFromNBT(save);
        assertEquals(c, restored.resolve(a, 0, "old").anchor);
        assertEquals("old", restored.resolve(a, 0, "").generation);
        assertEquals(c, restored.resolve(a, 0, "").anchor);
        assertEquals(new AnchorKey(7, 1, 64, 0), restored.resolve(new AnchorKey(7, 1, 64, 0), 0, "").anchor);
        assertEquals(c, restored.resolve(a, 0, "old-canceled-hint").anchor);
        // New bindings carry the journal head cursor and cannot replay an earlier coordinate alias move.
        assertEquals(a, restored.resolve(a, restored.currentSequence(), "rebuilt").anchor);
        assertEquals("rebuilt", restored.resolve(a, restored.currentSequence(), "rebuilt").generation);
        assertEquals(2L, restored.resolve(a, restored.currentSequence(), "rebuilt").sequence);
    }

    private static QuantumControllerRegistry registry() {
        return new QuantumControllerRegistry("test");
    }

    private static QuantumControllerRegistry reload(QuantumControllerRegistry registry) {
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        QuantumControllerRegistry result = registry();
        result.readFromNBT(saved);
        return result;
    }

    private static long pos(int x) {
        return QuantumControllerRegistry.pack(x, 64, 0);
    }

    private static Set<Long> members(int... xs) {
        Set<Long> result = new HashSet<>();
        Arrays.stream(xs)
            .forEach(x -> result.add(pos(x)));
        return result;
    }
}
