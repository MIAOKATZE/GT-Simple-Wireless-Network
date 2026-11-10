package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class QuantumSafetyFixtureSnapshotTest {

    @Test
    public void snapshotLeavesHeldMarkerIntactAndFreezesNestedBusinessNbt() throws Exception {
        ItemStack held = new ItemStack(new Item());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(QuantumSafetyClientScenario.PHASE, "revoked");
        NBTTagCompound business = new NBTTagCompound();
        business.setString("nativeKey", "before");
        tag.setTag("business", business);
        held.setTagCompound(tag);

        NBTTagCompound baseline = QuantumSafetyClientScenario.businessTerminalNbt(held);
        assertEquals(
            "revoked",
            held.getTagCompound()
                .getString(QuantumSafetyClientScenario.PHASE));
        assertFalse(
            baseline.getCompoundTag("tag")
                .hasKey(QuantumSafetyClientScenario.PHASE));
        assertNotSame(held.getTagCompound(), baseline.getCompoundTag("tag"));
        assertNotSame(
            business,
            baseline.getCompoundTag("tag")
                .getCompoundTag("business"));

        NBTTagCompound matrixState = new NBTTagCompound();
        matrixState.setTag("terminal", held.writeToNBT(new NBTTagCompound()));
        Class<?> matrixSnapshot = Class.forName(NativeSecurityMatrix.class.getName() + "$Snapshot");
        Constructor<?> construct = matrixSnapshot.getDeclaredConstructor(NBTTagCompound.class, double.class);
        construct.setAccessible(true);
        Method same = matrixSnapshot.getDeclaredMethod("same", matrixSnapshot);
        same.setAccessible(true);
        Field state = matrixSnapshot.getDeclaredField("state");
        state.setAccessible(true);
        Object matrixBaseline = construct.newInstance(matrixState, 10D);
        NBTTagCompound frozenMatrix = (NBTTagCompound) state.get(matrixBaseline);
        assertNotSame(matrixState, frozenMatrix);

        business.setString("nativeKey", "after");
        assertEquals(
            "before",
            baseline.getCompoundTag("tag")
                .getCompoundTag("business")
                .getString("nativeKey"));
        assertFalse(baseline.equals(QuantumSafetyClientScenario.businessTerminalNbt(held)));
        assertEquals(
            "before",
            frozenMatrix.getCompoundTag("terminal")
                .getCompoundTag("tag")
                .getCompoundTag("business")
                .getString("nativeKey"));
        assertFalse((Boolean) same.invoke(matrixBaseline, construct.newInstance(matrixState, 10D)));
    }
}
