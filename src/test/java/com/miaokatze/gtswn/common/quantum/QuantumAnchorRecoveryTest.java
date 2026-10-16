package com.miaokatze.gtswn.common.quantum;

import static com.miaokatze.gtswn.common.quantum.QuantumControllerPlacementTest.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

/** Recovery uses physical coordinates; native security remains a separate connection-time decision. */
public class QuantumAnchorRecoveryTest {

    @Test
    public void savedSchemaTwoRetiredGenerationRecoversAtRebuiltCurrentCoordinates() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(0));
        String retired = registry.canonicalAnchor(pos(1)).generation;
        remove(registry, 0, 1, 2);
        registry.quantizeAll(members(0, 1, 2, 3), pos(3));
        NBTTagCompound saved = save(registry);
        saved.setInteger("anchorSchema", 2);
        // This is the already-saved faulty format: no captured aliases, and stale device generation hints.
        saved.removeTag("legacyAnchorAliases");
        saved.removeTag("bindingRecoveryGroups");
        saved.removeTag("bindingRecoveryAliases");
        registry.readFromNBT(saved);
        for (long member : members(0, 1, 2, 3)) {
            assertNotNull(registry.resolveAnchor(member, retired));
            assertSame(registry.canonicalAnchor(member), registry.resolveAnchor(member, ""));
        }
    }

    @Test
    public void ordinaryNoncanonicalRemovalKeepsSurvivorsCanonicalAndPhysicalEvidence() throws Exception {
        FixtureWorld world = world();
        Controller root = controller(world, 0);
        controller(world, 1);
        Controller removed = controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1, 2), pos(0));
        QuantumControllerRegistry.CanonicalAnchor original = registry.canonicalAnchor(pos(0));
        String identity = root.identity;
        world.tiles.remove(pos(2));
        world.loadedAir = members(2);
        QuantumTileLifecycle.removed(removed);
        assertEquals(members(0, 1), registry.getAll());
        assertSame(original, registry.canonicalAnchor(pos(1)));
        assertEquals(identity, root.identity);
        assertTrue(registry.isCurrentController(world, pos(0)));
    }

    @Test
    public void ordinaryMiddleRemovalKeepsBothNativeComponentsAndTheirOwnCanonical() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        Controller removed = controller(world, 1);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1, 2), pos(1));
        world.tiles.remove(pos(1));
        world.loadedAir = members(1);
        QuantumTileLifecycle.removed(removed);
        assertEquals(members(0, 2), registry.getAll());
        assertEquals(pos(0), registry.canonicalAnchor(pos(0)).position);
        assertEquals(pos(2), registry.canonicalAnchor(pos(2)).position);
        assertNotEquals(registry.canonicalAnchor(pos(0)).generation, registry.canonicalAnchor(pos(2)).generation);
        assertEquals(pos(0), registry.resolveAnchor(pos(1), "obsolete").position);
    }

    @Test
    public void physicalIdentityRefreshKeepsActiveRegistrationAndTheLogicalDefault() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1), pos(0));
        QuantumControllerRegistry.CanonicalAnchor original = registry.canonicalAnchor(pos(0));
        Controller replacement = controller(world, 1);
        assertFalse(registry.isCurrentController(world, pos(1)));
        assertTrue(registry.refreshController(world, pos(1)));
        assertTrue(registry.isQuantized(pos(1)));
        assertTrue(registry.ownsController(replacement));
        assertSame(original, registry.canonicalAnchor(pos(1)));
    }

    @Test
    public void removedDefaultFindsSurvivorsAcrossSaveAndCurrentCoordinateWinsOverTheHistoricalAlias() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(0));
        String retired = registry.canonicalAnchor(pos(0)).generation;
        remove(registry, 0, 1, 2);
        assertNull(registry.resolveAnchor(pos(0), retired));
        registry.quantizeAll(members(2), pos(2));
        registry = reload(registry);
        assertEquals(pos(2), registry.resolveAnchor(pos(0), retired).position);
        registry.quantize(0, 64, 0);
        assertEquals(pos(0), registry.resolveAnchor(pos(0), retired).position);
        assertNotEquals(
            registry.canonicalAnchor(pos(2)).generation,
            registry.resolveAnchor(pos(0), retired).generation);
    }

    @Test
    public void oldDefaultFollowsRepeatedDestructionBeyondItsOriginalMembersWithoutAliasCycles() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(0));
        String original = registry.canonicalAnchor(pos(0)).generation;
        remove(registry, 0, 1, 2);
        registry.quantizeAll(members(1, 2, 3), pos(1));
        registry = reload(registry);
        remove(registry, 1, 2, 3);
        registry.quantize(3, 64, 0);
        registry = reload(registry);
        assertEquals(pos(3), registry.resolveAnchor(pos(0), original).position);
        long revision = registry.getRevision();
        for (int i = 0; i < 10_000; i++) assertEquals(pos(3), registry.resolveAnchor(pos(0), original).position);
        assertEquals(revision, registry.getRevision());
    }

    @Test
    public void equalSizeResizeRefreshesRecoveryEvidenceBeforeTheNextRemovalBatch() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(0));
        String original = registry.canonicalAnchor(pos(0)).generation;
        registry.dequantize(pos(0));
        assertTrue(registry.quantizeAll(members(1, 2, 3), pos(3)));
        registry.dequantize(pos(1));
        registry.dequantize(pos(2));
        assertEquals(pos(3), registry.resolveAnchor(pos(0), original).position);
        assertEquals(pos(3), reload(registry).resolveAnchor(pos(0), original).position);
    }

    @Test
    public void deletingCanonicalPublishesOneReplacementAndStableLookupsDoNotPublishEvents() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(0));
        registry.dequantize(pos(0));
        long revision = registry.getRevision(), epoch = QuantumTerminalSyncEpoch.bindingEpoch();
        QuantumControllerRegistry.CanonicalAnchor anchor = registry.resolveAnchor(pos(1), "retired");
        assertEquals(pos(1), anchor.position);
        assertTrue(registry.getRevision() > revision);
        assertNotEquals(epoch, QuantumTerminalSyncEpoch.bindingEpoch());
        revision = registry.getRevision();
        epoch = QuantumTerminalSyncEpoch.bindingEpoch();
        for (int i = 0; i < 10_000; i++) assertSame(anchor, registry.resolveAnchor(pos(2), "retired"));
        assertEquals(revision, registry.getRevision());
        assertEquals(epoch, QuantumTerminalSyncEpoch.bindingEpoch());
        assertSame(anchor, registry.resolveAnchor(pos(0), "retired"));
    }

    @Test
    public void pausedStructureCanRemoveItsDefaultAndAddMembersWithoutLosingBindings() throws Exception {
        FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1, 2), pos(0));
        String generation = registry.canonicalAnchor(pos(0)).generation;
        assertTrue(registry.suspendStructure(pos(0)));
        world.tiles.remove(pos(0));
        world.loadedAir = members(0);
        controller(world, 3);
        assertTrue(registry.quantizeAll(members(1, 2, 3), pos(3)));
        assertEquals(generation, registry.resolveAnchor(pos(1), generation).generation);
        assertEquals(pos(1), registry.resolveAnchor(pos(0), generation).position);
        assertTrue(registry.isQuantized(pos(3)));
    }

    @Test
    public void missingGenerationsAndCanonicalInSchemaTwoAreRepairedOnceWithoutWorldReads() {
        QuantumControllerRegistry original = registry();
        original.quantizeAll(members(0, 1, 2), pos(2));
        NBTTagCompound damaged = save(original);
        damaged.setInteger("anchorSchema", 2);
        damaged.setTag("memberGenerations", new NBTTagList());
        damaged.setTag("canonicalAnchors", new NBTTagList());
        QuantumControllerRegistry registry = registry();
        registry.readFromNBT(damaged);
        assertNotNull(registry.resolveAnchor(pos(2), "old-lost-generation"));
        assertEquals(pos(0), registry.resolveAnchor(pos(1), "").position);
        String repaired = registry.canonicalAnchor(pos(0)).generation;
        assertEquals(repaired, reload(registry).resolveAnchor(pos(2), "old-lost-generation").generation);
    }

    @Test
    public void savedDisconnectedMembersSharingOneGenerationBecomeIndependentComponents() {
        QuantumControllerRegistry original = registry();
        original.quantizeAll(members(0, 1, 2), pos(0));
        NBTTagCompound damaged = save(original);
        damaged.setIntArray("controllers", new int[] { 0, 0, 64, 0, 0, 2, 64, 0 });
        QuantumControllerRegistry registry = registry();
        registry.readFromNBT(damaged);
        assertEquals(pos(0), registry.canonicalAnchor(pos(0)).position);
        assertEquals(pos(2), registry.canonicalAnchor(pos(2)).position);
        assertNotEquals(registry.canonicalAnchor(pos(0)).generation, registry.canonicalAnchor(pos(2)).generation);
    }

    @Test
    public void oldSchemaTwoDeadCanonicalCoordinateRetainsItsSavedCompanionEvidence() {
        QuantumControllerRegistry original = registry();
        original.quantizeAll(members(0, 1, 2), pos(0));
        NBTTagCompound damaged = save(original);
        damaged.setInteger("anchorSchema", 2);
        damaged.setIntArray("controllers", new int[] { 0, 1, 64, 0, 0, 2, 64, 0 });
        QuantumControllerRegistry registry = registry();
        registry.readFromNBT(damaged);
        assertEquals(pos(1), registry.resolveAnchor(pos(0), "saved-stale-generation").position);
        assertEquals(pos(1), reload(registry).resolveAnchor(pos(0), "").position);
    }

    @Test
    public void defaultElectionUsesMostBindingsAndStableCoordinateTies() {
        Map<Long, Integer> votes = new HashMap<>();
        votes.put(pos(0), 3);
        votes.put(pos(1), 8);
        votes.put(pos(2), 5);
        assertEquals(pos(1), QuantumControllerRegistry.electAnchor(members(0, 1, 2), votes));
        votes.put(pos(2), 8);
        assertEquals(pos(1), QuantumControllerRegistry.electAnchor(members(2, 1, 0), votes));
        assertEquals(pos(0), QuantumControllerRegistry.electAnchor(members(2, 1, 0), Collections.emptyMap()));
    }

    @Test
    public void clusterMigrationIncludesAirAliasesAndStaleOfflineHintsButLeavesTheOtherSplitComponent() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(1));
        registry.dequantize(pos(1));
        registry.canonicalAnchor(pos(0));
        registry.canonicalAnchor(pos(2));
        Set<Long> bindings = registry.migrationBindingMembers(members(0));
        assertEquals(members(0, 1), bindings);
        assertFalse(bindings.contains(pos(2)));
        String current = registry.canonicalAnchor(pos(0)).generation;
        QuantumMigrationJournal journal = new QuantumMigrationJournal("cluster-migration-test");
        AnchorKey target = new AnchorKey(7, 10, 64, 0);
        journal.appendClusterMigration(0, bindings, target, current);
        registry.dequantize(pos(0));
        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        QuantumMigrationJournal restored = new QuantumMigrationJournal("cluster-migration-test");
        restored.readFromNBT(saved);
        for (String staleHint : new String[] { "", "old-before-retirement", current }) {
            QuantumMigrationJournal.Resolution moved = restored.resolve(new AnchorKey(0, 1, 64, 0), 0, staleHint);
            assertEquals(target, moved.anchor);
            assertEquals(current, moved.generation);
        }
        AnchorKey other = new AnchorKey(0, 2, 64, 0);
        assertEquals(other, restored.resolve(other, 0, "old-before-retirement").anchor);
        AnchorKey explicitlyRebound = new AnchorKey(0, 1, 64, 0);
        assertEquals(
            explicitlyRebound,
            restored.resolve(explicitlyRebound, restored.currentSequence(), "new-explicit-binding").anchor);
    }

    @Test
    public void savedDefaultSurvivesFullRemovalRestartAndRebuildWithoutAnyLoadedNodes() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(1));
        remove(registry, 0, 1, 2);
        registry = reload(registry);
        assertTrue(registry.quantizeAll(members(0, 1, 2, 3), pos(0)));
        assertEquals(pos(1), registry.canonicalAnchor(pos(0)).position);
        registry = reload(registry);
        assertEquals(pos(1), registry.canonicalAnchor(pos(3)).position);
        assertTrue(registry.quantizeAll(members(0, 1, 2, 3, 4), pos(4)));
        assertEquals(pos(1), registry.canonicalAnchor(pos(4)).position);
    }

    @Test
    public void splitRenamesOnlyExistingLegacyMigrationAliasesAcrossReadAndRuntimeRepair() {
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setIntArray("controllers", new int[] { 0, 0, 64, 0, 0, 1, 64, 0, 0, 2, 64, 0 });
        QuantumControllerRegistry registry = registry();
        registry.readFromNBT(legacy);
        registry.canonicalAnchor(pos(0));
        registry.dequantize(pos(1));
        String fragment = registry.canonicalAnchor(pos(2)).generation;
        assertEquals(members(2), registry.legacyMembers(members(2), fragment));
        QuantumMigrationJournal journal = new QuantumMigrationJournal("split-legacy-test");
        AnchorKey target = new AnchorKey(7, 10, 64, 0);
        journal.append(0, members(2), target, fragment, fragment, registry.legacyMembers(members(2), fragment));
        assertEquals(target, journal.resolve(new AnchorKey(0, 2, 64, 0), 0, "").anchor);

        registry = registry();
        registry.readFromNBT(legacy);
        registry.canonicalAnchor(pos(0));
        NBTTagCompound splitSave = save(registry);
        splitSave.setIntArray("controllers", new int[] { 0, 0, 64, 0, 0, 2, 64, 0 });
        registry.readFromNBT(splitSave);
        fragment = registry.canonicalAnchor(pos(2)).generation;
        assertEquals(members(2), registry.legacyMembers(members(2), fragment));

        QuantumControllerRegistry modern = registry();
        modern.quantizeAll(members(0, 1, 2), pos(0));
        modern.dequantize(pos(1));
        String modernFragment = modern.canonicalAnchor(pos(2)).generation;
        assertTrue(
            modern.legacyMembers(members(2), modernFragment)
                .isEmpty());
    }

    @Test
    public void explicitFixtureResetClearsOnlyRemovedInVolumeHistoryAndInvalidatesOnce() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2, 3), pos(1));
        remove(registry, 0, 1, 2);
        registry.quantizeAll(members(80, 81, 82), pos(81));
        remove(registry, 80, 81, 82);
        assertEquals(pos(3), registry.resolveAnchor(pos(0), "").position);
        long revision = registry.getRevision();
        long epoch = QuantumTerminalSyncEpoch.bindingEpoch();
        assertTrue(
            registry.forgetRemovedControllerHistory(position -> QuantumControllerRegistry.unpackX(position) < 2));
        assertEquals(revision + 1, registry.getRevision());
        assertEquals(epoch + 1, QuantumTerminalSyncEpoch.bindingEpoch());
        assertFalse(
            registry.forgetRemovedControllerHistory(position -> QuantumControllerRegistry.unpackX(position) < 2));
        assertEquals(revision + 1, registry.getRevision());
        assertEquals(epoch + 1, QuantumTerminalSyncEpoch.bindingEpoch());
        registry = reload(registry);
        assertNull(registry.resolveAnchor(pos(0), ""));
        assertNull(registry.resolveAnchor(pos(1), ""));
        assertEquals(pos(3), registry.resolveAnchor(pos(2), "").position);
        assertTrue(registry.isQuantized(pos(3)));
        registry.quantizeAll(members(80, 81, 82), pos(80));
        assertEquals(pos(81), registry.canonicalAnchor(pos(80)).position);
    }

    @Test
    public void explicitFixtureResetPreservesRegisteredAndSuspendedMembersEvenInsideVolume() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(1));
        remove(registry, 0);
        long revision = registry.getRevision();
        assertFalse(registry.forgetRemovedControllerHistory(position -> position == pos(1) || position == pos(2)));
        assertEquals(revision, registry.getRevision());
        assertEquals(pos(1), registry.canonicalAnchor(pos(2)).position);
        registry.suspendStructure(pos(1));
        revision = registry.getRevision();
        assertFalse(registry.forgetRemovedControllerHistory(position -> position == pos(1) || position == pos(2)));
        assertEquals(revision, registry.getRevision());
        registry = reload(registry);
        assertTrue(registry.isSuspendedController(pos(1)));
        assertTrue(registry.isSuspendedController(pos(2)));
        registry.quantizeAll(members(1, 2), pos(2));
        assertEquals(pos(1), registry.canonicalAnchor(pos(2)).position);
    }

    @Test
    public void repeatedFixtureResetsLetEachFreshStructureUseItsNewFirstClick() {
        QuantumControllerRegistry registry = registry();
        registry.quantizeAll(members(0, 1, 2), pos(1));
        // The lifecycle scenario splits at the middle, so its surviving left member becomes default.
        registry.dequantize(pos(1));
        assertEquals(pos(0), registry.canonicalAnchor(pos(0)).position);
        remove(registry, 0, 2);
        assertTrue(registry.forgetRemovedControllerHistory(position -> true));
        registry = reload(registry);
        registry.quantizeAll(members(0, 1, 2), pos(1));
        assertEquals(pos(1), registry.canonicalAnchor(pos(0)).position);
        remove(registry, 0, 1, 2);
        assertTrue(registry.forgetRemovedControllerHistory(position -> true));
        registry = reload(registry);
        registry.quantizeAll(members(0, 1, 2), pos(2));
        assertEquals(pos(2), registry.canonicalAnchor(pos(0)).position);
    }

    @Test
    public void fixtureResetCompressesInsideHistoricalBridgesWithoutLosingOutsideRecovery() {
        long outsideOld = QuantumControllerRegistry.pack(32, 64, 0);
        long insideBridge = QuantumControllerRegistry.pack(31, 64, 0);
        long insideUpper = QuantumControllerRegistry.pack(31, 65, 0);
        long outsideTarget = QuantumControllerRegistry.pack(32, 65, 0);
        for (boolean targetAlreadyRebuilt : new boolean[] { false, true }) {
            QuantumControllerRegistry registry = registry();
            assertTrue(registry.quantizeAll(new HashSet<>(Arrays.asList(outsideOld, insideBridge)), outsideOld));
            registry.dequantize(outsideOld);
            registry.dequantize(insideBridge);
            assertTrue(
                registry
                    .quantizeAll(new HashSet<>(Arrays.asList(insideBridge, insideUpper, outsideTarget)), insideBridge));
            registry.dequantize(insideBridge);
            registry.dequantize(insideUpper);
            registry.dequantize(outsideTarget);
            if (targetAlreadyRebuilt) {
                registry.quantizeAll(Collections.singleton(outsideTarget), outsideTarget);
                assertEquals(outsideTarget, registry.resolveAnchor(outsideOld, "").position);
            }
            long revision = registry.getRevision();
            assertTrue(
                registry.forgetRemovedControllerHistory(position -> QuantumControllerRegistry.unpackX(position) < 32));
            assertEquals(revision + 1, registry.getRevision());
            NBTTagList groups = save(registry).getTagList("bindingRecoveryGroups", 10);
            for (int index = 0; index < groups.tagCount(); index++) {
                NBTTagCompound group = groups.getCompoundTagAt(index);
                if (group.hasKey("default"))
                    assertTrue(QuantumControllerRegistry.unpackX(group.getLong("default")) >= 32);
                int[] coordinates = group.getIntArray("members");
                for (int offset = 0; offset < coordinates.length; offset += 3) assertTrue(coordinates[offset] >= 32);
            }
            registry = reload(registry);
            assertNull(registry.resolveAnchor(insideBridge, ""));
            assertNull(registry.resolveAnchor(insideUpper, ""));
            if (!targetAlreadyRebuilt) {
                assertNull(registry.resolveAnchor(outsideOld, ""));
                registry.quantizeAll(Collections.singleton(outsideTarget), outsideTarget);
            }
            assertEquals(outsideTarget, registry.resolveAnchor(outsideOld, "").position);
            registry.dequantize(outsideTarget);
            registry = reload(registry);
            assertNull(registry.resolveAnchor(outsideOld, ""));
            registry.quantizeAll(Collections.singleton(outsideTarget), outsideTarget);
            assertEquals(outsideTarget, registry.resolveAnchor(outsideOld, "").position);
        }
    }

    private static QuantumControllerRegistry registry() {
        return new QuantumControllerRegistry("recovery-test");
    }

    private static NBTTagCompound save(QuantumControllerRegistry registry) {
        NBTTagCompound tag = new NBTTagCompound();
        registry.writeToNBT(tag);
        return tag;
    }

    private static QuantumControllerRegistry reload(QuantumControllerRegistry registry) {
        QuantumControllerRegistry restored = registry();
        restored.readFromNBT(save(registry));
        return restored;
    }

    private static void remove(QuantumControllerRegistry registry, int... xs) {
        for (int x : xs) registry.dequantize(pos(x));
    }

    private static Set<Long> members(int... xs) {
        Set<Long> result = new HashSet<>();
        Arrays.stream(xs)
            .forEach(x -> result.add(pos(x)));
        return result;
    }
}
