package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;

import org.junit.Test;

import com.google.common.io.ByteStreams;

import gregtech.api.covers.CoverContext;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CoverableTileEntity;
import gregtech.common.covers.Cover;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class WirelessCoverInputTest {

    private static GTswnCoverWirelessBase cover(boolean dynamo, Host host) {
        CoverContext context = new CoverContext(null, ForgeDirection.NORTH, host);
        return dynamo ? new GTswn_Cover_DynamoWireless(context) : new GTswn_Cover_EnergyWireless(context);
    }

    private static NBTTagCompound data(boolean allowed) {
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("allowInput", allowed);
        data.setBoolean("configured", true);
        data.setInteger("voltage", 32);
        data.setDouble("amperageDecimal", 0.5);
        data.setLong("storedEU", 123);
        data.setLong("ticksSinceLastRefill", 17);
        data.setLong("ticksSinceLastUpload", 19);
        return data;
    }

    private static void assertGate(GTswnCoverWirelessBase cover, boolean allowed) {
        assertEquals(allowed, cover.isInputAllowed());
        for (int slot : new int[] { -2, -1, 0, 31 }) {
            assertEquals(allowed, cover.letsItemsIn(slot));
            assertFalse(cover.letsItemsOut(slot));
        }
        assertEquals(allowed, cover.letsFluidIn(null));
        assertEquals(allowed, cover.letsFluidIn(new Fluid("wireless-input-test")));
        assertFalse(cover.letsFluidOut(null));
        assertFalse(cover.letsEnergyIn());
        assertFalse(cover.letsEnergyOut());
    }

    @Test
    public void newAndLegacyCoversAllowBothInputsIncludingReloadOverAClosedState() {
        for (boolean dynamo : new boolean[] { false, true }) {
            GTswnCoverWirelessBase cover = cover(dynamo, null);
            assertGate(cover, true);
            cover.readDataFromNbt(data(false));
            assertGate(cover, false);
            NBTTagCompound legacy = data(false);
            legacy.removeTag("allowInput");
            cover.readDataFromNbt(legacy);
            assertGate(cover, true);
        }
    }

    @Test
    public void nbtAndPacketRoundTripsPreserveTheGateWithoutChangingEnergyOrOutputRules() {
        for (boolean dynamo : new boolean[] { false, true }) {
            for (boolean allowed : new boolean[] { false, true }) {
                GTswnCoverWirelessBase source = cover(dynamo, null);
                source.readDataFromNbt(data(allowed));
                NBTTagCompound saved = (NBTTagCompound) source.saveDataToNbt();
                assertTrue(saved.hasKey("allowInput"));
                GTswnCoverWirelessBase restored = cover(dynamo, null);
                restored.readDataFromNbt(saved);
                assertGate(restored, allowed);
                GTswnCoverWirelessBase network = cover(dynamo, null);
                network.readDataFromPacket(ByteStreams.newDataInput(packet(source)));
                assertGate(network, allowed);
                assertEquals(123, network.dashboardStored());
                assertTrue(network.dashboardConfigured());
                assertEquals(0.5, network.getAmperage(), 0);
                assertEquals(
                    dynamo ? 19 : 17,
                    ((NBTTagCompound) network.saveDataToNbt())
                        .getLong(dynamo ? "ticksSinceLastUpload" : "ticksSinceLastRefill"));
            }
        }
    }

    @Test
    public void oldPacketWithoutTheNewTailDefaultsToAllowed() {
        for (boolean dynamo : new boolean[] { false, true }) {
            GTswnCoverWirelessBase source = cover(dynamo, null);
            source.readDataFromNbt(data(false));
            byte[] packet = packet(source);
            GTswnCoverWirelessBase target = cover(dynamo, null);
            target.readDataFromNbt(data(false));
            target.readDataFromPacket(ByteStreams.newDataInput(Arrays.copyOf(packet, packet.length - 1)));
            assertGate(target, true);
            assertEquals(123, target.dashboardStored());
        }
    }

    @Test(expected = IllegalStateException.class)
    public void truncatedEnergyFieldsAreStillRejected() {
        cover(false, null).readDataFromPacket(ByteStreams.newDataInput(new byte[0]));
    }

    @Test
    public void nativeAndMachineCopiesTransferTheGateAndPreserveTheirEnergyBoundaries() {
        for (boolean dynamo : new boolean[] { false, true }) {
            GTswnCoverWirelessBase source = cover(dynamo, null);
            source.readDataFromNbt(data(false));
            NBTTagCompound original = source.writeToNBT(new NBTTagCompound());
            GTswnCoverWirelessBase target = cover(dynamo, null);
            target.readFromNbt(WirelessCoverCopy.configuration(original));
            assertGate(target, false);
            assertEquals(0, target.dashboardStored());
            target.readDataFromNbt(data(true));
            target.readFromNbt(
                WirelessCoverCopy.configurationPreservingBuffer(original, target.writeToNBT(new NBTTagCompound())));
            assertGate(target, false);
            assertEquals(123, target.dashboardStored());
            assertFalse(
                original.getCompoundTag("d")
                    .getBoolean("allowInput"));
        }
    }

    @Test
    public void screwdriverAndUiSetterShareTheStateAndDirtyOnlyOnChanges() throws Exception {
        for (boolean dynamo : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(dynamo);
            fixture.cover.onCoverScrewdriverClick(fixture.player, 0.5f, 0.5f, 0.5f);
            assertGate(fixture.cover, false);
            assertEquals(1, fixture.host.dirty);
            assertEquals(1, fixture.player.messages);
            fixture.cover.setInputAllowed(fixture.player, false);
            assertEquals(1, fixture.host.dirty);
            fixture.cover.setInputAllowed(fixture.player, true);
            assertGate(fixture.cover, true);
            assertEquals(2, fixture.host.dirty);
        }
    }

    @Test
    public void clientUnauthorizedDistantAndDetachedMutationsAreRejected() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.host.locked = true;
        fixture.host.owner = UUID.randomUUID();
        fixture.reject();
        fixture.host.owner = fixture.player.id;
        fixture.player.posX = 20;
        fixture.reject();
        fixture.player.posX = 0.5;
        fixture.player.isDead = true;
        fixture.reject();
        fixture.player.isDead = false;
        field(World.class, "isRemote").setBoolean(fixture.world, true);
        fixture.reject();
        field(World.class, "isRemote").setBoolean(fixture.world, false);
        fixture.host.install(null);
        fixture.reject();
        fixture.host.install(fixture.cover);
        fixture.world.host = null;
        fixture.reject();
        fixture.world.host = fixture.host;
        fixture.cover.setInputAllowed(fixture.player, false);
        assertGate(fixture.cover, false);
        assertEquals(1, fixture.host.dirty);
    }

    private static byte[] packet(GTswnCoverWirelessBase cover) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            cover.writeDataToByteBuf(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static final class Fixture {

        final TestWorld world = (TestWorld) allocate(TestWorld.class);
        final Host host = (Host) allocate(Host.class);
        final Player player = (Player) allocate(Player.class);
        final GTswnCoverWirelessBase cover;

        Fixture(boolean dynamo) throws Exception {
            field(CoverableTileEntity.class, "covers").set(host, new Cover[6]);
            host.setWorldObj(world);
            world.host = host;
            player.worldObj = world;
            player.id = UUID.randomUUID();
            player.posX = player.posY = player.posZ = 0.5;
            host.owner = player.id;
            cover = cover(dynamo, host);
            host.install(cover);
        }

        void reject() {
            cover.setInputAllowed(player, false);
            cover.onCoverScrewdriverClick(player, 0.5f, 0.5f, 0.5f);
            assertGate(cover, true);
            assertEquals(0, host.dirty);
            assertEquals(0, player.messages);
        }
    }

    private static final class Host extends BaseMetaTileEntity {

        UUID owner;
        boolean locked;
        int dirty;

        void install(Cover cover) {
            covers[ForgeDirection.NORTH.ordinal()] = cover;
        }

        @Override
        public boolean privateAccess() {
            return locked;
        }

        @Override
        public UUID getOwnerUuid() {
            return owner;
        }

        @Override
        public void markDirty() {
            dirty++;
        }
    }

    private static final class TestWorld extends WorldServer {

        TileEntity host;

        private TestWorld() {
            super(null, null, null, 0, null, null);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return host;
        }
    }

    private static final class Player extends EntityPlayerMP {

        UUID id;
        int messages;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public boolean isEntityAlive() {
            return !isDead;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages++;
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
