// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerRenamer;
import appeng.core.sync.packets.PacketValueConfig;
import appeng.helpers.ICustomNameObject;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import io.netty.buffer.Unpooled;

/** Real AE rename submission and GT .265 tile delegation; no client GUI or Netty claim. */
public class MatrixRenamingTest {

    private static final Class<?>[] TYPES = { MatrixItemInputBus.class, MatrixFluidInputHatch.class };

    @Test
    public void bothRenameSubmissionPathsReachAllFourPhysicalMemberTypes() throws Exception {
        for (Class<?> type : TYPES) for (boolean mirror : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(type, mirror);
            assertTrue(fixture.member instanceof ICustomNameObject);
            assertFalse(fixture.tile.hasCustomName());
            ContainerRenamer renamer = (ContainerRenamer) allocate(ContainerRenamer.class);
            set(ContainerRenamer.class, renamer, "namedObject", fixture.tile);
            renamer.setNewName("矩阵仓 A / " + mirror);
            fixture.assertName("矩阵仓 A / " + mirror);

            QuietPlayer player = (QuietPlayer) allocate(QuietPlayer.class);
            player.openContainer = renamer;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream wire = new DataOutputStream(bytes);
            wire.writeUTF("QuartzKnife.ReName");
            wire.writeUTF("终端重命名 B");
            PacketValueConfig packet = new PacketValueConfig(Unpooled.wrappedBuffer(bytes.toByteArray()));
            packet.serverPacketData(null, packet, player);
            fixture.assertName("终端重命名 B");
            assertTrue(player.closed);
            assertTrue(fixture.tile.dirtyCalls > 0);
        }
    }

    @Test
    public void namesRoundTripAndClearingRestoresTheNormalMemberName() throws Exception {
        for (Class<?> type : TYPES) for (boolean mirror : new boolean[] { false, true }) {
            Fixture original = new Fixture(type, mirror);
            original.tile.setCustomName("装配线 · α");
            NBTTagCompound saved = new NBTTagCompound();
            original.port.save(saved);
            Fixture restored = new Fixture(type, mirror);
            restored.port.load(saved);
            restored.assertName("装配线 · α");
            assertEquals(original.port.memberId(), restored.port.memberId());
            restored.tile.setCustomName("");
            assertFalse(restored.tile.hasCustomName());
            assertNull(restored.tile.getCustomName());
            assertEquals(
                ((MetaTileEntity) restored.member).getLocalName(),
                ((IInterfaceViewable) restored.member).getName());
            assertFalse(new MatrixPatternInventoryAdapter(restored.port).hasCustomInventoryName());
            restored.port.save(saved);
            assertFalse(
                saved.getCompoundTag("gtswnMatrix")
                    .hasKey("customName"));
            original.port.load(saved);
            assertFalse(original.tile.hasCustomName());
        }
    }

    @Test
    public void anOldUnnamedSaveDoesNotRetainAnEarlierInMemoryName() throws Exception {
        Fixture fixture = new Fixture(MatrixItemInputBus.class, false);
        NBTTagCompound unnamed = new NBTTagCompound();
        fixture.port.save(unnamed);
        fixture.tile.setCustomName("old in-memory value");
        fixture.port.load(unnamed);
        assertFalse(fixture.member.hasCustomName());
        assertEquals(((MetaTileEntity) fixture.member).getLocalName(), ((IInterfaceViewable) fixture.member).getName());
    }

    @Test
    public void renamingABusyMemberDoesNotChangeItsBatchOrAnotherMembersName() throws Exception {
        Fixture first = new Fixture(MatrixItemInputBus.class, false);
        Fixture second = new Fixture(MatrixFluidInputHatch.class, true);
        OrderedAssemblyGroup group = (OrderedAssemblyGroup) allocate(OrderedAssemblyGroup.class);
        UUID batch = UUID.randomUUID();
        set(OrderedAssemblyGroup.class, group, "stage", OrderedAssemblyGroup.Stage.STAGED);
        set(OrderedAssemblyGroup.class, group, "batch", batch);
        set(OrderedAssemblyGroup.class, group, "epoch", 42L);
        set(MatrixAssemblyPort.class, first.port, "group", group);
        set(MatrixAssemblyPort.class, second.port, "group", group);
        set(MatrixAssemblyPort.class, first.port, "patternChange", false);
        first.tile.setCustomName("working source");
        first.assertName("working source");
        assertFalse(second.tile.hasCustomName());
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, field(OrderedAssemblyGroup.class, "stage").get(group));
        assertSame(batch, field(OrderedAssemblyGroup.class, "batch").get(group));
        assertEquals(42L, field(OrderedAssemblyGroup.class, "epoch").getLong(group));
        assertFalse(field(MatrixAssemblyPort.class, "patternChange").getBoolean(first.port));
    }

    @Test
    public void unknownNamePayloadRemainsRecoverableAndCannotBeRewrittenByRenaming() throws Exception {
        Fixture fixture = new Fixture(MatrixItemInputBus.class, false);
        NBTTagCompound saved = new NBTTagCompound();
        fixture.port.save(saved);
        saved.getCompoundTag("gtswnMatrix")
            .setInteger("customName", 42);
        NBTTagCompound original = (NBTTagCompound) saved.copy();
        fixture.port.load(saved);
        assertTrue(fixture.port.quarantined());
        fixture.tile.setCustomName("do not overwrite unknown data");
        fixture.port.save(saved);
        assertEquals(original, saved);
    }

    private static final class Fixture {

        final MatrixAssemblyMember member;
        final MatrixAssemblyPort port;
        final QuietTile tile = new QuietTile();

        Fixture(Class<?> type, boolean mirror) throws Exception {
            member = (MatrixAssemblyMember) allocate(type);
            port = new MatrixAssemblyPort(member);
            set(type, member, "matrix", port);
            set(type, member, "mirror", mirror);
            set(
                CommonMetaTileEntity.class,
                member,
                "mInventory",
                new ItemStack[type == MatrixItemInputBus.class ? 1 : 0]);
            set(CommonMetaTileEntity.class, member, "mName", "gtswn.matrix.rename.fixture");
            ((MetaTileEntity) member).setBaseMetaTileEntity(tile);
        }

        void assertName(String name) {
            assertTrue(tile.hasCustomName());
            assertEquals(name, tile.getCustomName());
            assertEquals(name, ((IInterfaceViewable) member).getName());
            assertEquals(name, ((IInterfaceViewable) member).getRawName());
            MatrixPatternInventoryAdapter view = new MatrixPatternInventoryAdapter(port);
            assertEquals(name, view.getInventoryName());
            assertTrue(view.hasCustomInventoryName());
        }
    }

    private static final class QuietTile extends BaseMetaTileEntity {

        int dirtyCalls;

        @Override
        public void markDirty() {
            dirtyCalls++;
        }
    }

    private static final class QuietPlayer extends EntityPlayerMP {

        boolean closed;

        private QuietPlayer() {
            super(null, null, null, null);
        }

        @Override
        public void closeScreen() {
            closed = true;
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        return ((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        field(type, name).set(object, value);
    }
}
