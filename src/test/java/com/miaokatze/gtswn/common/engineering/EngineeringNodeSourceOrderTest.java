package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;

public class EngineeringNodeSourceOrderTest {

    @Test
    public void wirelessReadPrefixDoesNotDependOnSavedRegistrationOrder() {
        WirelessNodeRegistry first = wireless(new int[] { 3, 1, 2 });
        WirelessNodeRegistry second = wireless(new int[] { 2, 3, 1 });
        List<Long> expected = Arrays.asList(
            WirelessNodeIndexCodec.pack(1, 0, 0),
            WirelessNodeIndexCodec.pack(2, 0, 0),
            WirelessNodeIndexCodec.pack(3, 0, 0));
        assertEquals(
            expected,
            new ArrayList<>(
                first.readView()
                    .keySet()));
        assertEquals(
            expected,
            new ArrayList<>(
                second.readView()
                    .keySet()));
        first.unregister(null, 2, 0, 0);
        assertEquals(
            Arrays.asList(expected.get(0), expected.get(2)),
            new ArrayList<>(
                first.readView()
                    .keySet()));
    }

    @Test
    public void incorporationSortsAuthorizedSourcesAndPositionsBeforeApplyingLimit() {
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("stable");
        NBTTagList list = new NBTTagList();
        list.appendTag(incorporation(3, 2));
        list.appendTag(incorporation(9, 1));
        list.appendTag(incorporation(1, 3));
        list.appendTag(incorporation(7, 1));
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("entries", list);
        registry.readFromNBT(saved);
        Set<Long> members = new LinkedHashSet<>(
            Arrays.asList(QuantumControllerRegistry.pack(2, 0, 0), QuantumControllerRegistry.pack(1, 0, 0)));
        List<QuantumIncorporationRegistry.Entry> prefix = registry.snapshotSource(0, members, 2);
        assertEquals(2, prefix.size());
        assertEquals(QuantumControllerRegistry.pack(7, 0, 0), prefix.get(0).position);
        assertEquals(QuantumControllerRegistry.pack(9, 0, 0), prefix.get(1).position);
        assertEquals(
            3,
            registry.snapshotSource(0, members, 10)
                .size());
        assertTrue(
            registry.snapshotSource(1, members, 10)
                .isEmpty());
    }

    private static WirelessNodeRegistry wireless(int[] positions) {
        long[] packed = new long[positions.length];
        int[] flat = new int[positions.length * 2];
        byte[] types = new byte[positions.length];
        for (int i = 0; i < positions.length; i++) {
            packed[i] = WirelessNodeIndexCodec.pack(positions[i], 0, 0);
            flat[2 * i] = (int) (packed[i] >> 32);
            flat[2 * i + 1] = (int) packed[i];
            types[i] = WirelessNodeIndexCodec.TYPE_ENERGY;
        }
        NBTTagCompound saved = new NBTTagCompound();
        saved.setIntArray("positions", flat);
        saved.setByteArray("types", types);
        WirelessNodeRegistry registry = new WirelessNodeRegistry("stable");
        registry.readFromNBT(saved);
        return registry;
    }

    private static NBTTagCompound incorporation(int positionX, int anchorX) {
        NBTTagCompound entry = new NBTTagCompound();
        entry.setString("identity", "saved-" + positionX);
        entry.setLong("pos", QuantumControllerRegistry.pack(positionX, 0, 0));
        entry.setInteger("dim", 0);
        entry.setInteger("x", anchorX);
        return entry;
    }
}
