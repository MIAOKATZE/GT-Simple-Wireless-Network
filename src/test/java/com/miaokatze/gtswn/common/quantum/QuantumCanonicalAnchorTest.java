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
        assertEquals(generation, restored.resolveAnchor(pos(0), "").generation);
        assertEquals(new AnchorKey(7, 1, 64, 0), restored.canonicalKey(new AnchorKey(7, 3, 64, 0)));
    }

    @Test
    public void legacyAliasesUpgradeOnceAndRebuiltCoordinatesRecoverCurrentGeneration() {
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
        assertEquals(
            saved.canonicalAnchor(pos(2)).generation,
            saved.resolveAnchor(pos(2), first.generation).generation);
        assertEquals(saved.canonicalAnchor(pos(2)).generation, reload(saved).resolveAnchor(pos(2), "").generation);
    }

    @Test
    public void manyIndependentLegacyComponentsKeepTheirAliasesAndGenerationsAcrossSchemaTwoReloads() {
        int componentCount = 2048;
        Set<Long> coordinates = disconnectedMembers(componentCount);
        int[] flat = new int[coordinates.size() * 4];
        int index = 0;
        for (long member : coordinates) {
            flat[index++] = 7;
            flat[index++] = QuantumControllerRegistry.unpackX(member);
            flat[index++] = QuantumControllerRegistry.unpackY(member);
            flat[index++] = QuantumControllerRegistry.unpackZ(member);
        }
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setIntArray("controllers", flat);
        QuantumControllerRegistry migrated = registry();
        migrated.readFromNBT(legacy);

        QuantumControllerRegistry.CanonicalAnchor[] anchors = new QuantumControllerRegistry.CanonicalAnchor[componentCount];
        Set<String> generations = new HashSet<>();
        for (int component = 0; component < componentCount; component++) {
            int first = component * 8, size = 1 + component % 4;
            QuantumControllerRegistry.CanonicalAnchor anchor = migrated.resolveAnchor(pos(first), "");
            assertNotNull(anchor);
            assertTrue(generations.add(anchor.generation));
            assertTrue(anchor.position >= pos(first) && anchor.position <= pos(first + size - 1));
            anchors[component] = anchor;
            for (int offset = 0; offset < size; offset++) {
                long member = pos(first + offset);
                assertSame(anchor, migrated.resolveAnchor(member, ""));
                assertSame(anchor, migrated.resolveAnchor(member, "stale-legacy-hint"));
                assertEquals(
                    size,
                    migrated.registeredStructure(member)
                        .size());
            }
        }

        NBTTagCompound saved = new NBTTagCompound();
        migrated.writeToNBT(saved);
        assertEquals(3, saved.getInteger("anchorSchema"));
        assertEquals(
            componentCount,
            saved.getTagList("canonicalAnchors", 10)
                .tagCount());
        assertEquals(
            coordinates.size(),
            saved.getTagList("legacyAnchorAliases", 10)
                .tagCount());
        QuantumControllerRegistry restored = registry();
        restored.readFromNBT(saved);
        restored = reload(restored);
        assertEquals(coordinates, restored.getAll());
        for (int component = 0; component < componentCount; component++) {
            int first = component * 8, size = 1 + component % 4;
            QuantumControllerRegistry.CanonicalAnchor expected = anchors[component];
            AnchorKey canonical = new AnchorKey(7, QuantumControllerRegistry.unpackX(expected.position), 64, 0);
            for (int offset = 0; offset < size; offset++) {
                QuantumControllerRegistry.CanonicalAnchor actual = restored.resolveAnchor(pos(first + offset), "");
                assertNotNull(actual);
                assertEquals(expected.position, actual.position);
                assertEquals(expected.generation, actual.generation);
                assertEquals(canonical, restored.canonicalKey(new AnchorKey(7, first + offset, 64, 0)));
            }
        }
    }

    @Test
    public void bulkQuantizationKeepsIndependentComponentsSeparateWithoutGrantingLegacyAliases() {
        int componentCount = 257;
        Set<Long> coordinates = disconnectedMembers(componentCount);
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(coordinates);
        QuantumControllerRegistry restored = reload(registry);
        assertEquals(coordinates, restored.getAll());
        Set<String> generations = new HashSet<>();
        for (int component = 0; component < componentCount; component++) {
            int first = component * 8, size = 1 + component % 4;
            QuantumControllerRegistry.CanonicalAnchor anchor = restored.canonicalAnchor(pos(first));
            assertNotNull(anchor);
            assertTrue(generations.add(anchor.generation));
            assertEquals(registry.canonicalAnchor(pos(first)).position, anchor.position);
            assertEquals(registry.canonicalAnchor(pos(first)).generation, anchor.generation);
            for (int offset = 0; offset < size; offset++) {
                assertSame(anchor, restored.canonicalAnchor(pos(first + offset)));
                assertSame(anchor, restored.resolveAnchor(pos(first + offset), ""));
                assertEquals(
                    size,
                    restored.registeredStructure(pos(first + offset))
                        .size());
            }
        }
    }

    @Test
    public void physicalExpansionCoalescesPreviouslyRegisteredGenerationsOnce() {
        QuantumControllerRegistry registry = registry();
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        long revision = registry.getRevision();
        assertTrue(registry.canExtendStructure(members(0, 1, 2)));
        assertTrue(registry.quantizeAll(members(0, 1, 2), pos(1)));
        assertTrue(registry.isQuantized(pos(1)));
        assertTrue(registry.getRevision() > revision);
        assertSame(registry.canonicalAnchor(pos(0)), registry.canonicalAnchor(pos(2)));
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
        assertSame(current.canonicalAnchor(pos(0)), current.resolveAnchor(pos(0), "erased-by-old-cancel"));
        assertSame(current.canonicalAnchor(pos(1)), current.resolveAnchor(pos(1), generation));
        assertSame(current.canonicalAnchor(pos(2)), current.resolveAnchor(pos(2), ""));
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

    private static Set<Long> disconnectedMembers(int componentCount) {
        Set<Long> result = new HashSet<>();
        for (int component = 0; component < componentCount; component++) {
            for (int offset = 0; offset < 1 + component % 4; offset++) result.add(pos(component * 8 + offset));
        }
        return result;
    }

    private static Set<Long> members(int... xs) {
        Set<Long> result = new HashSet<>();
        Arrays.stream(xs)
            .forEach(x -> result.add(pos(x)));
        return result;
    }
}
