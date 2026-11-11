package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import org.junit.Test;

/** Storage-scope composition only. Real paired native authority is covered by Access tests and the server case. */
public class QuantumIncorporationRemovalScopeTest {

    @Test
    public void queryPreservesCreatorAndCreativeCompatibilityAndUsesExactTargetNativeAuthorityWithoutMutation()
        throws Exception {
        UUID creator = UUID.randomUUID();
        FixtureRegistry registry = registry(creator);
        Actor a = actor(creator), b = actor(UUID.randomUUID());
        NBTTagCompound before = save(registry);
        assertTrue(registry.canRemoveEntry(null, 14, 65, 20, a));
        assertEquals(0, registry.nativeChecks);
        b.capabilities.isCreativeMode = true;
        assertTrue(registry.canRemoveEntry(null, 14, 65, 20, b));
        assertEquals(0, registry.nativeChecks);
        b.capabilities.isCreativeMode = false;
        registry.nativeAllowed = false;
        assertFalse(registry.canRemoveEntry(null, 14, 65, 20, b));
        assertEquals(1, registry.nativeChecks);
        registry.nativeAllowed = true;
        assertTrue(registry.canRemoveEntry(null, 14, 65, 20, b));
        assertEquals(2, registry.nativeChecks);
        assertSame(b, registry.checkedActor);
        assertEquals(14, registry.checkedX);
        assertEquals(65, registry.checkedY);
        assertEquals(20, registry.checkedZ);
        assertFalse(registry.canRemoveEntry(null, 15, 65, 20, b));
        assertFalse(registry.canRemoveEntry(null, 14, 65, 20, null));
        assertEquals(2, registry.nativeChecks);
        assertEquals(before, save(registry)); // The decision cannot refund supplies, remove an entry, or transfer
                                              // creator metadata.
        assertEquals(
            creator.toString(),
            save(registry).getTagList("entries", 10)
                .getCompoundTagAt(0)
                .getString("owner"));
    }

    private static FixtureRegistry registry(UUID creator) {
        FixtureRegistry registry = new FixtureRegistry();
        NBTTagCompound entry = new NBTTagCompound(), root = new NBTTagCompound();
        entry.setLong("pos", QuantumControllerRegistry.pack(14, 65, 20));
        entry.setString("identity", "scope-test-entry");
        entry.setString("owner", creator.toString());
        entry.setInteger("dim", 19871);
        entry.setInteger("x", 8);
        entry.setInteger("y", 65);
        entry.setInteger("z", 20);
        entry.setString("anchorGeneration", "scope-test-generation");
        NBTTagList entries = new NBTTagList();
        entries.appendTag(entry);
        root.setTag("entries", entries);
        registry.readFromNBT(root);
        return registry;
    }

    private static NBTTagCompound save(QuantumIncorporationRegistry registry) {
        NBTTagCompound n = new NBTTagCompound();
        registry.writeToNBT(n);
        return (NBTTagCompound) n.copy();
    }

    private static Actor actor(UUID id) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field f = unsafe.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Actor actor = (Actor) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(f.get(null), Actor.class);
        actor.id = id;
        actor.capabilities = new PlayerCapabilities();
        return actor;
    }

    private static final class Actor extends EntityPlayerMP {

        private UUID id;

        private Actor() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    private static final class FixtureRegistry extends QuantumIncorporationRegistry {

        private boolean nativeAllowed;
        private int nativeChecks, checkedX, checkedY, checkedZ;
        private EntityPlayer checkedActor;

        private FixtureRegistry() {
            super("removal-scope-test");
        }

        @Override
        public boolean canManageEntry(World world, int x, int y, int z, EntityPlayer player) {
            nativeChecks++;
            checkedActor = player;
            checkedX = x;
            checkedY = y;
            checkedZ = z;
            return nativeAllowed;
        }
    }
}
