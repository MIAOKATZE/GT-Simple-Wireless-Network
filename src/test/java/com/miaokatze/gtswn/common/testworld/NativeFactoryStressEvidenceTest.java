package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceRegistryData;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;

/** Regression for native GT source identities and short recipes missed between fourteen-tick status samples. */
public class NativeFactoryStressEvidenceTest {

    @Test
    public void unattendedReleaseKeepsTheControllerAndSupplyChunksUntilEveryOuterChunkIsReleased() {
        TestWorldFootprint footprint = new TestWorldFootprint(80);
        List<ChunkCoordIntPair> chunks = new ArrayList<>();
        for (int i = 0; i < footprint.chunkCount(); i++)
            chunks.add(new ChunkCoordIntPair(footprint.chunkX(i), footprint.chunkZ(i)));
        chunks.sort(Comparator.comparingInt(TestWorldManager::releasePriority));
        assertEquals(100, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkCoordIntPair chunk = chunks.get(i);
            boolean central = chunk.chunkXPos >= -1 && chunk.chunkXPos <= 0
                && chunk.chunkZPos >= -1
                && chunk.chunkZPos <= 0;
            assertEquals(i >= 96, central);
        }
        assertTrue(
            chunks.subList(96, 100)
                .contains(new ChunkCoordIntPair(-1, -1)));
        assertTrue(
            chunks.subList(96, 100)
                .contains(new ChunkCoordIntPair(0, 0)));
    }

    @Test
    public void nativeInputTileAndOutputMetaEntityResolveToTheSameRegisteredEndpoint() throws Exception {
        BaseMetaTileEntity tile = (BaseMetaTileEntity) allocate(BaseMetaTileEntity.class);
        MTEHatchEnergy machine = (MTEHatchEnergy) allocate(MTEHatchEnergy.class);
        machine.setBaseMetaTileEntity(tile);
        Object endpoint = new Object();
        Map<Object, Object> identities = new IdentityHashMap<>();
        NativeFactoryStressScenario.registerNativeSource(identities, tile, endpoint);
        assertSame(endpoint, identities.get(tile));
        assertSame(endpoint, identities.get(machine));
        assertEquals(2, identities.size());
    }

    @Test
    public void realOutputAndEnergyPermitAShortRecipeWhoseActiveFlagWasNeverSampled() {
        TestWorldReport.Machine fact = new TestWorldReport.Machine(3, 0, 100, 0, 1);
        fact.nativeStructureFormed = true;
        fact.exportedInput = 379;
        fact.importedOutput = 35;
        fact.ditSamples = 1;
        fact.ditInputEUt = 63.36;
        NativeFactoryStressScenario.observeMaterialWork(fact, false, 300, 30, 0, 0, 4029);
        assertEquals(0, fact.activeSamples);
        assertFalse(fact.nativeActive);
        assertTrue(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 4030));
        assertEquals("ae_output_modulate", fact.latestNativeEvidence);
        assertEquals(NativeFactoryStressScenario.EVIDENCE_OUTPUT, fact.nativeEvidenceMask);
        assertFalse(NativeFactoryStressScenario.machineReady(fact, false, true, true, true, 4030));
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, false, true, true, 4030));
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, true, false, true, 4030));
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, true, true, false, 4030));
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 5029));
        fact.nativeStructureFormed = false;
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 4030));
    }

    @Test
    public void fuelAndNativeRecipeCountersRefreshEvidenceWithoutInventingActiveSamples() {
        TestWorldReport.Machine fact = new TestWorldReport.Machine(384, 0, 117, 0, 1);
        fact.nativeStructureFormed = true;
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 4000));
        NativeFactoryStressScenario
            .observeNativeWork(fact, 4000, NativeFactoryStressScenario.EVIDENCE_FUEL, "ae_fuel_modulate");
        NativeFactoryStressScenario
            .observeNativeWork(fact, 4014, NativeFactoryStressScenario.EVIDENCE_RECIPES, "native_recipe_counter");
        assertTrue(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 4030));
        assertEquals(0, fact.activeSamples);
        assertEquals(4014, fact.lastNativeProgressTick);
        assertEquals("native_recipe_counter", fact.latestNativeEvidence);
        assertEquals(
            NativeFactoryStressScenario.EVIDENCE_FUEL | NativeFactoryStressScenario.EVIDENCE_RECIPES,
            fact.nativeEvidenceMask);
    }

    @Test
    public void refundedFuelExtractionWithNoNetGrowthDoesNotRefreshActivity() {
        TestWorldReport.Machine fact = new TestWorldReport.Machine(384, 0, 117, 0, 1);
        fact.nativeStructureFormed = true;
        fact.fluidInput = 100;
        NativeFactoryStressScenario.observeMaterialWork(fact, true, 0, 0, 100, 0, 4000);
        assertFalse(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 4000));
        assertEquals(0, fact.nativeEvidenceMask);
        fact.fluidInput++;
        NativeFactoryStressScenario.observeMaterialWork(fact, true, 0, 0, 100, 0, 4014);
        assertTrue(NativeFactoryStressScenario.machineReady(fact, true, true, true, true, 4014));
        assertEquals(NativeFactoryStressScenario.EVIDENCE_FUEL, fact.nativeEvidenceMask);
    }

    @Test
    public void repeatedFixtureInstallCreatesANewNativeRegistrationAndPreservesUnrelatedKeys() throws Exception {
        DeviceRegistryData registry = new DeviceRegistryData("factory-repeat-fixture");
        UUID owner = TestWorldFixtures.owner(NativeFactoryStressScenario.ID + ":owner");
        WorldServer world = world(true);
        BaseMetaTileEntity tile = controller(world);
        String key = DeviceRegistryData.makeKey(world.provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord);
        String otherTest = DeviceRegistryData.makeKey(world.provider.dimensionId, 12, 65, 12);
        String production = DeviceRegistryData.makeKey(0, tile.xCoord, tile.yCoord, tile.zCoord);
        registry.register(key, owner, "native machine", 1);
        registry.register(otherTest, owner, "unrelated test", 2);
        registry.register(production, owner, "production canary", 3);
        DeviceRegistryData.DeviceEntry unrelated = registry.getEntry(otherTest), canary = registry.getEntry(production);
        UUID previousSource = registry.getEntry(key).sourceId;
        for (int run = 0; run < 2; run++) {
            assertTrue(NativeFactoryStressScenario.clearFixtureRegistration(registry, world, owner, tile));
            assertNull(registry.getEntry(key));
            // Same native registration used by the real DIT callback, with the identical machine identity.
            assertTrue(registry.register(key, owner, "native machine", 1));
            assertNotEquals(previousSource, registry.getEntry(key).sourceId);
            previousSource = registry.getEntry(key).sourceId;
        }
        assertSame(unrelated, registry.getEntry(otherTest));
        assertSame(canary, registry.getEntry(production));
    }

    @Test
    public void foreignOwnerAndProductionWorldAreRejectedWithoutRegistryMutation() throws Exception {
        DeviceRegistryData registry = new DeviceRegistryData("factory-scope-fixture");
        UUID owner = TestWorldFixtures.owner(NativeFactoryStressScenario.ID + ":owner");
        WorldServer world = world(true);
        BaseMetaTileEntity tile = controller(world);
        String key = DeviceRegistryData.makeKey(world.provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord);
        registry.register(key, UUID.randomUUID(), "foreign owner", 1);
        DeviceRegistryData.DeviceEntry foreign = registry.getEntry(key);
        assertThrows(
            IllegalStateException.class,
            () -> NativeFactoryStressScenario.clearFixtureRegistration(registry, world, owner, tile));
        assertSame(foreign, registry.getEntry(key));
        WorldServer productionWorld = world(false);
        BaseMetaTileEntity productionTile = controller(productionWorld);
        String productionKey = DeviceRegistryData
            .makeKey(0, productionTile.xCoord, productionTile.yCoord, productionTile.zCoord);
        registry.register(productionKey, owner, "production canary", 1);
        DeviceRegistryData.DeviceEntry canary = registry.getEntry(productionKey);
        assertThrows(
            IllegalStateException.class,
            () -> NativeFactoryStressScenario
                .clearFixtureRegistration(registry, productionWorld, owner, productionTile));
        assertSame(canary, registry.getEntry(productionKey));
    }

    private static BaseMetaTileEntity controller(WorldServer world) throws Exception {
        BaseMetaTileEntity tile = (BaseMetaTileEntity) allocate(BaseMetaTileEntity.class);
        tile.setWorldObj(world);
        tile.xCoord = -76;
        tile.yCoord = 100;
        tile.zCoord = -76;
        return tile;
    }

    private static WorldServer world(boolean test) throws Exception {
        WorldServer world = (WorldServer) allocate(WorldServer.class);
        WorldProvider provider = test ? new WorldProviderTest() : new WorldProviderSurface();
        provider.dimensionId = test ? TestDimension.dimId() : 0;
        Field storedProvider = World.class.getDeclaredField("provider");
        storedProvider.setAccessible(true);
        storedProvider.set(world, provider);
        return world;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }
}
