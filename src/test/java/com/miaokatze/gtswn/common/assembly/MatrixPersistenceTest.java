package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyBinding;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;

public class MatrixPersistenceTest {

    private static Item item;

    @BeforeClass
    public static void registerMaterial() throws Exception {
        item = new Item();
        Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31231, "gtswn_matrix_test:material", item);
    }

    @Test
    public void realItemPayloadSurvivesWithIntegerQuantityAndUnchangedNbt() {
        Member before = new Member(false);
        before.item = stack(513);
        NBTTagCompound output = new NBTTagCompound();
        before.port.save(output);
        assertEquals(
            0,
            output.getTagList("Inventory", 10)
                .tagCount());
        assertEquals(
            513,
            output.getCompoundTag("gtswnMatrix")
                .getCompoundTag("item")
                .getInteger("count"));
        NBTTagCompound saved = (NBTTagCompound) output.copy();
        Member restored = new Member(false);
        restored.port.load(output);
        assertFalse(restored.port.quarantined());
        assertTrue(ItemStack.areItemStacksEqual(before.item, restored.item));
        assertEquals(before.port.memberId(), restored.port.memberId());
        assertEquals(saved, output);
        restored.item.getTagCompound()
            .setString("label", "changed");
        assertEquals(
            "original",
            before.item.getTagCompound()
                .getString("label"));
        assertEquals(saved, output);
    }

    @Test
    public void legacyNativeSlotMigratesOnceWithoutAddingADuplicateCopy() {
        ItemStack expected = stack(513);
        NBTTagCompound input = legacy(expected);
        Member member = new Member(false);
        member.port.load(input);
        assertFalse(member.port.quarantined());
        assertTrue(ItemStack.areItemStacksEqual(expected, member.item));
        member.port.save(input);
        assertEquals(
            0,
            input.getTagList("Inventory", 10)
                .tagCount());
        Member restored = new Member(false);
        restored.port.load(input);
        assertEquals(513, restored.item.stackSize);
        assertTrue(ItemStack.areItemStacksEqual(expected, restored.item));
    }

    @Test
    public void agreeingOldAndNewViewsAreOneSlotAndConflictsArePreserved() {
        Member source = new Member(false);
        source.item = stack(7);
        NBTTagCompound input = legacy(source.item);
        NBTTagCompound own = new NBTTagCompound();
        source.port.save(own);
        input.setTag(
            "gtswnMatrix",
            own.getCompoundTag("gtswnMatrix")
                .copy());
        Member agreeing = new Member(false);
        agreeing.port.load(input);
        assertFalse(agreeing.port.quarantined());
        assertEquals(7, agreeing.item.stackSize);
        input.getTagList("Inventory", 10)
            .getCompoundTagAt(0)
            .setInteger("Count", 8);
        assertPreserved(input, false);
    }

    @Test
    public void consumingPayloadThenReusingTheNbtRootCannotResurrectLegacyStock() {
        Member member = new Member(false);
        member.item = stack(9);
        NBTTagCompound root = legacy(member.item);
        member.port.save(root);
        member.item = null;
        member.port.save(root);
        Member restored = new Member(false);
        restored.port.load(root);
        assertNull(restored.item);
        Member liquid = new Member(true);
        liquid.fluid = FluidRegistryTestBootstrap.water(144);
        liquid.port.save(root);
        liquid.fluid = null;
        liquid.port.save(root);
        assertFalse(root.hasKey("mFluid"));
        Member dry = new Member(true);
        dry.port.load(root);
        assertNull(dry.fluid);
    }

    @Test
    public void unknownSchemaAndUnexpectedRootTypeRemainByteForByteNbtEquivalent() {
        NBTTagCompound root = new NBTTagCompound();
        root.setString("gtswnMatrix", "future opaque value");
        assertPreserved(root, false);
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("schema", 42);
        data.setString("future", "keep this");
        root.setTag("gtswnMatrix", data);
        assertPreserved(root, false);
    }

    @Test
    public void unknownItemAndMalformedPatternOrQuantityFieldsAreNotReadAsEmpty() {
        Member source = new Member(false);
        source.item = stack(1);
        NBTTagCompound root = new NBTTagCompound();
        source.port.save(root);
        NBTTagCompound unknown = (NBTTagCompound) root.copy();
        unknown.getCompoundTag("gtswnMatrix")
            .getCompoundTag("item")
            .setString("registry", "missing_mod:retired_material");
        assertPreserved(unknown, false);
        NBTTagCompound badCount = (NBTTagCompound) root.copy();
        badCount.getCompoundTag("gtswnMatrix")
            .getCompoundTag("item")
            .setLong("count", 1L << 32);
        assertPreserved(badCount, false);
        NBTTagCompound badPatterns = (NBTTagCompound) root.copy();
        badPatterns.getCompoundTag("gtswnMatrix")
            .setString("patterns", "opaque pattern inventory");
        assertPreserved(badPatterns, false);
        NBTTagCompound badItem = (NBTTagCompound) root.copy();
        badItem.getCompoundTag("gtswnMatrix")
            .setString("item", "opaque material inventory");
        assertPreserved(badItem, false);
    }

    @Test
    public void fluidsKeepActualAmountAndTagsAndUnknownFluidsRemainRecoverable() {
        Member source = new Member(true);
        source.fluid = FluidRegistryTestBootstrap.water(144);
        source.fluid.tag = new NBTTagCompound();
        source.fluid.tag.setString("grade", "stored");
        NBTTagCompound input = new NBTTagCompound();
        source.port.save(input);
        Member restored = new Member(true);
        restored.port.load(input);
        assertFalse(restored.port.quarantined());
        assertEquals(144, restored.fluid.amount);
        assertTrue(restored.fluid.isFluidEqual(source.fluid));
        NBTTagCompound unknown = (NBTTagCompound) input.copy();
        unknown.getCompoundTag("mFluid")
            .setString("FluidName", "missing_mod:retired_fluid");
        assertPreserved(unknown, true);
        NBTTagCompound oversized = (NBTTagCompound) input.copy();
        oversized.getCompoundTag("mFluid")
            .setLong("Amount", (1L << 32) + 144);
        assertPreserved(oversized, true);
    }

    @Test
    public void anUnknownLedgerStageIsPreservedRatherThanReplacedByAnIdleBatch() {
        Member source = new Member(false);
        NBTTagCompound input = new NBTTagCompound();
        source.port.save(input);
        NBTTagCompound ledger = new NBTTagCompound();
        ledger.setString("stage", "FUTURE_PHASE");
        ledger.setString("futureEvidence", "still owed");
        input.getCompoundTag("gtswnMatrix")
            .setTag("ledger", ledger);
        assertPreserved(input, false);
    }

    @Test
    public void zeroItemSlotFluidHatchCanPerformNativeCleanupWithoutIndexingSlotZero() throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
        MatrixFluidInputHatch hatch = (MatrixFluidInputHatch) unsafe.allocateInstance(MatrixFluidInputHatch.class);
        Field inventory = CommonMetaTileEntity.class.getDeclaredField("mInventory");
        inventory.setAccessible(true);
        inventory.set(hatch, new ItemStack[0]);
        hatch.mFluid = FluidRegistryTestBootstrap.water(144);
        assertFalse(hatch.doesFillContainers());
        assertFalse(hatch.doesEmptyContainers());
        gregtech.api.metatileentity.BaseMetaTileEntity serverTile = new gregtech.api.metatileentity.BaseMetaTileEntity();
        serverTile.setWorldObj(
            (net.minecraft.world.WorldServer) unsafe.allocateInstance(net.minecraft.world.WorldServer.class));
        hatch.onPreTick(serverTile, 1);
        hatch.updateSlots();
        assertEquals(144, hatch.mFluid.amount);
        hatch.mFluid.amount = 0;
        hatch.updateSlots();
        assertNull(hatch.mFluid);
    }

    private static ItemStack stack(int count) {
        ItemStack stack = new ItemStack(item, count, 3);
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setString("label", "original");
        return stack;
    }

    private static NBTTagCompound legacy(ItemStack stack) {
        NBTTagCompound root = new NBTTagCompound(), entry = stack.writeToNBT(new NBTTagCompound());
        entry.setInteger("IntSlot", 0);
        entry.setInteger("Count", stack.stackSize);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(entry);
        root.setTag("Inventory", inventory);
        return root;
    }

    private static void assertPreserved(NBTTagCompound input, boolean fluid) {
        NBTTagCompound before = (NBTTagCompound) input.copy();
        Member member = new Member(fluid);
        member.port.load(input);
        assertTrue(member.port.quarantined());
        NBTTagCompound saved = new NBTTagCompound();
        saved.setString("parentSaveAdded", "must not contaminate the unknown root");
        member.port.save(saved);
        assertEquals(before, saved);
        assertEquals(before, input);
    }

    private static final class Member implements MatrixAssemblyMember {

        final boolean liquid;
        final MatrixAssemblyPort port = new MatrixAssemblyPort(this);
        ItemStack item;
        FluidStack fluid;

        Member(boolean liquid) {
            this.liquid = liquid;
        }

        @Override
        public MatrixAssemblyPort matrixPort() {
            return port;
        }

        @Override
        public IGregTechTileEntity getBaseMetaTileEntity() {
            return null;
        }

        @Override
        public boolean matrixFluid() {
            return liquid;
        }

        @Override
        public ItemStack matrixItem() {
            return item == null ? null : item.copy();
        }

        @Override
        public FluidStack matrixFluidStack() {
            return fluid == null ? null : fluid.copy();
        }

        @Override
        public void matrixSetItem(ItemStack item) {
            this.item = item;
        }

        @Override
        public void matrixSetFluid(FluidStack fluid) {
            this.fluid = fluid;
        }

        @Override
        public UUID orderedMemberId() {
            return port.memberId();
        }

        @Override
        public NativeAssemblyBinding orderedAssemblyBinding() {
            return null;
        }

        @Override
        public void orderedAssemblyStructureChecked(NativeAssemblySession session, boolean formed) {}
    }
}
