package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

public class QuantumConnectionMetadataTest {

    @Test
    public void incorporationOwnerNameAndUuidSurviveSaveRoundTrip() {
        QuantumIncorporationRegistry registry = registry("placing-player");
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        QuantumIncorporationRegistry restored = new QuantumIncorporationRegistry("restored");
        restored.readFromNBT(saved);
        QuantumIncorporationRegistry.Entry entry = restored.snapshot()
            .iterator()
            .next();
        assertEquals("owner-uuid", entry.owner());
        assertEquals("placing-player", entry.ownerName());
        assertFalse(entry.isConnected());
        assertEquals(0, entry.usedChannels());
    }

    @Test
    public void legacyIncorporationKeepsUuidWithoutInventingAName() {
        QuantumIncorporationRegistry.Entry entry = registry(null).snapshot()
            .iterator()
            .next();
        assertEquals("owner-uuid", entry.owner());
        assertEquals("", entry.ownerName());
    }

    @Test
    public void nodePlacementMetadataSurvivesSaveWithoutReplacingNativeOwner() {
        net.minecraft.tileentity.TileEntity.addMapping(TileEntityNetworkQuantumNode.class, "gtswn.test.metadata_node");
        NBTTagCompound saved = new NBTTagCompound(), nativeProxy = new NBTTagCompound();
        saved.setString("placerUUID", "owner-uuid");
        saved.setString("placerName", "placing-player");
        nativeProxy.setInteger("p", 42);
        nativeProxy.setLong("k", 123456789L);
        nativeProxy.setLong("g", 87654321L);
        saved.setTag("proxy", nativeProxy);
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.readFromNBT(saved);
        assertEquals("owner-uuid", node.getPlacerUUID());
        assertEquals("placing-player", node.getPlacerName());
        assertEquals(42, node.getNativeOwnerID());
        NBTTagCompound output = new NBTTagCompound();
        node.writeToNBT(output);
        assertEquals("owner-uuid", output.getString("placerUUID"));
        assertEquals("placing-player", output.getString("placerName"));
        assertEquals(nativeProxy, output.getCompoundTag("proxy"));
        assertNotSame(nativeProxy, output.getCompoundTag("proxy"));
        TileEntityNetworkQuantumNode reloaded = new TileEntityNetworkQuantumNode();
        reloaded.readFromNBT(output);
        assertEquals("owner-uuid", reloaded.getPlacerUUID());
        assertEquals("placing-player", reloaded.getPlacerName());
        assertEquals(42, reloaded.getNativeOwnerID());
        assertEquals(
            123456789L,
            output.getCompoundTag("proxy")
                .getLong("k"));
        assertEquals(
            87654321L,
            output.getCompoundTag("proxy")
                .getLong("g"));
    }

    @Test
    public void legacyNodeKeepsNativeOwnerAndReportsMissingMetadata() {
        NBTTagCompound saved = new NBTTagCompound(), nativeProxy = new NBTTagCompound();
        nativeProxy.setInteger("p", 7);
        saved.setTag("proxy", nativeProxy);
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.readFromNBT(saved);
        assertEquals("", node.getPlacerUUID());
        assertEquals("", node.getPlacerName());
        assertEquals(7, node.getNativeOwnerID());
    }

    private static QuantumIncorporationRegistry registry(String ownerName) {
        NBTTagCompound saved = new NBTTagCompound(), entry = new NBTTagCompound();
        entry.setString("identity", "incorporation-identity");
        entry.setString("owner", "owner-uuid");
        entry.setString("block", "test:block");
        entry.setString("tile", "test.Tile");
        if (ownerName != null) entry.setString("ownerName", ownerName);
        NBTTagList entries = new NBTTagList();
        entries.appendTag(entry);
        saved.setTag("entries", entries);
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("test");
        registry.readFromNBT(saved);
        return registry;
    }
}
