package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

public class TerminalAcquisitionHintTest {

    @Test
    public void nativeCallbacksUseInitializedCapabilityWithoutStartingFml() throws Exception {
        Field available = TerminalBaubles.class.getDeclaredField("baublesAvailable");
        available.setAccessible(true);
        boolean original = available.getBoolean(null);
        try {
            World world = (World) allocate(WorldServer.class);
            available.setBoolean(null, false);
            FixturePlayer beforeInit = player();
            for (ItemStack terminal : terminals()) {
                terminal.getItem()
                    .onUpdate(terminal, world, beforeInit, 17, false);
            }
            assertTrue(beforeInit.messages.isEmpty());
            assertTrue(beforeInit.data.hasNoTags());

            available.setBoolean(null, true);
            for (ItemStack terminal : terminals()) {
                FixturePlayer inventoryOwner = player();
                terminal.getItem()
                    .onUpdate(terminal, world, inventoryOwner, 17, false);
                ((baubles.api.IBauble) terminal.getItem()).onWornTick(terminal, inventoryOwner);
                assertEquals(1, inventoryOwner.messages.size());

                FixturePlayer wornOwner = player();
                ((baubles.api.IBauble) terminal.getItem()).onWornTick(terminal, wornOwner);
                terminal.getItem()
                    .onUpdate(terminal, world, wornOwner, 0, true);
                assertEquals(1, wornOwner.messages.size());
            }
        } finally {
            available.setBoolean(null, original);
        }
    }

    @Test
    public void everyTerminalCanBeFirstAndAllTypesShareOneHintPerPlayer() throws Exception {
        ItemStack[] terminals = terminals();
        for (ItemStack first : terminals) {
            FixturePlayer player = player();
            TerminalAcquisitionHint.observe(first, player, true);
            assertEquals(1, player.messages.size());
            assertEquals(
                "gtswn.chat.terminal.baubles_hint",
                ((ChatComponentTranslation) player.messages.get(0)).getKey());
            // Native inventory/worn callbacks may repeat and can encounter different terminal types together.
            for (int tick = 0; tick < 40; tick++) {
                for (ItemStack terminal : terminals) TerminalAcquisitionHint.observe(terminal, player, true);
            }
            assertEquals(1, player.messages.size());
        }
        FixturePlayer other = player();
        TerminalAcquisitionHint.observe(terminals[0], other, true);
        assertEquals(1, other.messages.size());
    }

    @Test
    public void diskReloadAndForgeRespawnCopyRetainHintWithoutChangingOtherData() throws Exception {
        FixturePlayer original = player();
        original.data.setString("otherRoot", "retain");
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setString("otherMod", "retain");
        original.data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        TerminalAcquisitionHint.observe(terminals()[0], original, true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(original.data, bytes);
        FixturePlayer reconnected = player();
        reconnected.data = CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray()));
        TerminalAcquisitionHint.observe(terminals()[1], reconnected, true);
        assertTrue(reconnected.messages.isEmpty());
        assertEquals("retain", reconnected.data.getString("otherRoot"));

        FixturePlayer respawned = player();
        respawned.data.setTag(
            EntityPlayer.PERSISTED_NBT_TAG,
            reconnected.data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                .copy());
        respawned.dimension = 7;
        TerminalAcquisitionHint.observe(terminals()[2], respawned, true);
        respawned.dimension = -1;
        TerminalAcquisitionHint.observe(terminals()[3], respawned, true);
        assertTrue(respawned.messages.isEmpty());
        assertEquals(
            "retain",
            respawned.data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                .getString("otherMod"));
    }

    @Test
    public void absentBaublesAndNonTerminalsDoNotConsumeTheFutureHint() throws Exception {
        FixturePlayer player = player();
        TerminalAcquisitionHint.observe(terminals()[0], player, false);
        TerminalAcquisitionHint.observe(null, player, true);
        TerminalAcquisitionHint.observe(new ItemStack(new Item()), player, true);
        assertTrue(player.messages.isEmpty());
        assertTrue(player.data.hasNoTags());
        TerminalAcquisitionHint.observe(terminals()[3], player, true);
        assertEquals(1, player.messages.size());
    }

    @Test
    public void fakePlayersAndNonPlayerCallbacksAreIgnored() throws Exception {
        TerminalAcquisitionHint.observe(terminals()[0], null, true);
        TerminalAcquisitionHint.observe(terminals()[0], (FakePlayer) allocate(FakePlayer.class), true);
        TerminalAcquisitionHint.observe(terminals()[0], null);
    }

    private static ItemStack[] terminals() {
        return new ItemStack[] { new ItemStack(new PortableWirelessNetworkMonitor()),
            new ItemStack(new WirelessEnergyTap()), new ItemStack(new ItemDeviceInfoTerminal()),
            new ItemStack(new ItemNetworkQuantumTerminal()) };
    }

    private static FixturePlayer player() throws Exception {
        FixturePlayer player = (FixturePlayer) allocate(FixturePlayer.class);
        player.data = new NBTTagCompound();
        player.messages = new ArrayList<>();
        return player;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static class FixturePlayer extends EntityPlayerMP {

        private NBTTagCompound data;
        private List<IChatComponent> messages;

        private FixturePlayer() {
            super(null, null, null, null);
        }

        @Override
        public NBTTagCompound getEntityData() {
            return data;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message);
        }
    }
}
