package com.miaokatze.gtswn.assembly.nativeintegration;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class NativeAssemblySessionPersistenceTest {

    @Test
    public void identitySurvivesSaveLoadWithoutBindingToCoordinates() {
        NativeAssemblySession before = new NativeAssemblySession(null);
        UUID id = before.controllerId();
        NBTTagCompound root = new NBTTagCompound();
        before.save(root);
        NativeAssemblySession restored = new NativeAssemblySession(null);
        restored.load(root);
        assertEquals(id, restored.controllerId());
        assertEquals(0, restored.runSequence());
        assertEquals("IDLE", restored.lastNativeEvent());
    }

    @Test
    public void unknownControllerSchemaIsPreservedUnchanged() {
        NBTTagCompound future = new NBTTagCompound();
        future.setInteger("schema", 42);
        future.setString("futureField", "preserve me");
        NBTTagCompound input = new NBTTagCompound();
        input.setTag("gtswnOrderedAssemblyController", future);
        NativeAssemblySession session = new NativeAssemblySession(null);
        session.load(input);
        NBTTagCompound output = new NBTTagCompound();
        session.save(output);
        assertEquals(input, output);
    }

    @Test
    public void malformedIdentityDoesNotGetReplacedWithAnInventedUuid() {
        NBTTagCompound corrupt = new NBTTagCompound();
        corrupt.setInteger("schema", 1);
        corrupt.setString("uuidMost", "not a long");
        NBTTagCompound input = new NBTTagCompound();
        input.setTag("gtswnOrderedAssemblyController", corrupt);
        NativeAssemblySession session = new NativeAssemblySession(null);
        session.load(input);
        NBTTagCompound output = new NBTTagCompound();
        session.save(output);
        assertEquals(input, output);
    }

    @Test
    public void wrongControllerTagTypeIsPreservedWithoutTurningIntoAnEmptyCompound() {
        NBTTagCompound input = new NBTTagCompound();
        input.setString("gtswnOrderedAssemblyController", "unknown payload");
        NativeAssemblySession session = new NativeAssemblySession(null);
        session.load(input);
        NBTTagCompound output = new NBTTagCompound();
        session.save(output);
        assertEquals(input, output);
        assertFalse(session.readyForNewWork());
    }

    @Test
    public void observedDeltasRetainUnexpectedGrowthRatherThanHidingIt() {
        assertArrayEquals(
            new long[] { 64, 0, -1, 2147483647L },
            NativeAssemblySession.difference(new long[] { 64, 8, 2, 2147483647L }, new long[] { 0, 8, 3, 0 }));
    }

    @Test
    public void nativeAccessScopesRestoreThePreviousScope() {
        NativeAssemblySession outer = new NativeAssemblySession(null);
        NativeAssemblySession inner = new NativeAssemblySession(null);
        NativeAssemblySession original = NativeAssemblyAdapter.enter(outer);
        try {
            assertNull(original);
            assertSame(outer, NativeAssemblyAdapter.enter(inner));
            NativeAssemblyAdapter.leave(outer);
            assertSame(outer, NativeAssemblyAdapter.enter(inner));
        } finally {
            NativeAssemblyAdapter.leave(original);
        }
        assertNull(NativeAssemblyAdapter.enter(null));
        NativeAssemblyAdapter.leave(null);
    }
}
