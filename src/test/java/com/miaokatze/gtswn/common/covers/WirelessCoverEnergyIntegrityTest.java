package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.config.Config;

import gregtech.api.covers.CoverContext;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.misc.GlobalEnergyWorldSavedData;
import gregtech.common.misc.GlobalVariableStorage;
import gregtech.common.misc.WirelessNetworkManager;

/** No machine tick or chunk mutation may be needed when a delayed buffer transaction commits. */
public class WirelessCoverEnergyIntegrityTest {

    private final UUID owner = UUID.randomUUID();
    private float downlinkLoss, uplinkLoss;
    private int interval;
    private GlobalEnergyWorldSavedData previousEnergyData;

    @Before
    public void setup() {
        downlinkLoss = Config.downlinkLossEU;
        uplinkLoss = Config.uplinkLossEU;
        interval = Config.interactionRateTicks;
        Config.downlinkLossEU = Config.uplinkLossEU = 0;
        Config.interactionRateTicks = 1;
        previousEnergyData = GlobalEnergyWorldSavedData.INSTANCE;
        GlobalEnergyWorldSavedData.INSTANCE = new GlobalEnergyWorldSavedData("cover-energy-test");
        GlobalVariableStorage.GlobalEnergy.put(owner, BigInteger.ZERO);
    }

    @After
    public void cleanup() {
        Config.downlinkLossEU = downlinkLoss;
        Config.uplinkLossEU = uplinkLoss;
        Config.interactionRateTicks = interval;
        GlobalVariableStorage.GlobalEnergy.remove(owner);
        GlobalEnergyWorldSavedData.INSTANCE = previousEnergyData;
    }

    @Test
    public void fundedClientRemovalCannotCreditTheSharedGlobalEnergyMap() throws Exception {
        Host host = host(true);
        GTswn_Cover_EnergyWireless client = energy(host, 700);
        // WAILA's cover-data packet can populate this same client-side NBT state.
        client.onCoverRemoval();
        assertEquals(BigInteger.ZERO, WirelessNetworkManager.getUserEU(owner));
        assertEquals(700, client.dashboardStored());
        assertEquals(0, host.dirty);
    }

    @Test
    public void serverRemovalRefundsOnlyOnce() throws Exception {
        Host host = host(false);
        GTswn_Cover_EnergyWireless cover = energy(host, 700);
        cover.onCoverRemoval();
        cover.onCoverRemoval();
        assertEquals(BigInteger.valueOf(700), WirelessNetworkManager.getUserEU(owner));
        assertEquals(0, cover.dashboardStored());
        assertTrue(host.dirty > 0);
    }

    @Test
    public void detachedOrWorldlessRemovalDoesNotDiscardOrCreditEnergy() throws Exception {
        GTswn_Cover_EnergyWireless detached = energy(null, 700);
        detached.onCoverRemoval();
        assertEquals(700, detached.dashboardStored());
        Host host = host(false);
        GTswn_Cover_EnergyWireless loading = energy(host, 700);
        host.setWorldObj(null);
        loading.onCoverRemoval();
        loading.onBaseTEDestroyed();
        assertEquals(700, loading.dashboardStored());
        assertEquals(BigInteger.ZERO, WirelessNetworkManager.getUserEU(owner));
    }

    @Test
    public void idleRefillMarksTheHostDirtyAndPersistsItsCredit() throws Exception {
        Host host = host(false);
        GTswn_Cover_EnergyWireless cover = energy(host, 0);
        long capacity = cover.dashboardCapacity();
        GlobalVariableStorage.GlobalEnergy.put(owner, BigInteger.valueOf(capacity));
        cover.doCoverThings((byte) 0, 1);
        assertEquals(capacity, cover.dashboardStored());
        assertEquals(BigInteger.ZERO, WirelessNetworkManager.getUserEU(owner));
        assertEquals(1, host.dirty);
        GTswn_Cover_EnergyWireless restored = energy(null, 0);
        restored.readDataFromNbt(cover.saveDataToNbt());
        assertEquals(capacity, restored.dashboardStored());
    }

    @Test
    public void stoppedGeneratorUploadMarksTheHostDirtyAndCannotReplaySavedBuffer() throws Exception {
        Host host = host(false);
        GTswn_Cover_DynamoWireless cover = new GTswn_Cover_DynamoWireless(context(host));
        NBTTagCompound saved = new NBTTagCompound();
        saved.setBoolean("configured", true);
        saved.setLong("storedEU", 700);
        cover.readDataFromNbt(saved);
        cover.doCoverThings((byte) 0, 1);
        assertEquals(BigInteger.valueOf(700), WirelessNetworkManager.getUserEU(owner));
        assertEquals(0, cover.dashboardStored());
        assertEquals(1, host.dirty);
        GTswn_Cover_DynamoWireless restored = new GTswn_Cover_DynamoWireless(context(host));
        restored.readDataFromNbt(cover.saveDataToNbt());
        restored.doCoverThings((byte) 0, 2);
        assertEquals(BigInteger.valueOf(700), WirelessNetworkManager.getUserEU(owner));
        assertEquals(1, host.dirty);
    }

    @Test
    public void declinedRefillDoesNotDirtyAnUnchangedHost() throws Exception {
        Host host = host(false);
        GTswn_Cover_EnergyWireless cover = energy(host, 0);
        cover.doCoverThings((byte) 0, 1);
        assertEquals(0, cover.dashboardStored());
        assertEquals(0, host.dirty);
    }

    private static CoverContext context(Host host) {
        return new CoverContext(null, ForgeDirection.NORTH, host);
    }

    private static GTswn_Cover_EnergyWireless energy(Host host, long stored) {
        GTswn_Cover_EnergyWireless cover = new GTswn_Cover_EnergyWireless(context(host));
        NBTTagCompound saved = new NBTTagCompound();
        saved.setBoolean("configured", true);
        saved.setInteger("voltage", 32);
        saved.setInteger("amperage", 1);
        saved.setLong("storedEU", stored);
        cover.readDataFromNbt(saved);
        return cover;
    }

    private Host host(boolean remote) throws Exception {
        World world = (World) allocate(WorldServer.class);
        field(World.class, "isRemote").setBoolean(world, remote);
        field(World.class, "perWorldStorage").set(world, new MapStorage(null));
        Host host = (Host) allocate(Host.class);
        host.setWorldObj(world);
        host.owner = owner;
        return host;
    }

    private static final class Host extends BaseMetaTileEntity {

        UUID owner;
        int dirty;

        @Override
        public UUID getOwnerUuid() {
            return owner;
        }

        @Override
        public void markDirty() {
            dirty++;
        }

        @Override
        public long getOutputVoltage() {
            return 0;
        }

        @Override
        public long getOutputAmperage() {
            return 0;
        }

        @Override
        public long getStoredEUuncapped() {
            return 0;
        }

        @Override
        public long getEUCapacity() {
            return 0;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
