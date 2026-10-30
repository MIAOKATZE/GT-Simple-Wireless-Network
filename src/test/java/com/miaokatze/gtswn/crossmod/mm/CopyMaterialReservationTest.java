package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.recursive_pineapple.matter_manipulator.common.building.IPseudoInventory;
import com.recursive_pineapple.matter_manipulator.common.utils.BigFluidStack;
import com.recursive_pineapple.matter_manipulator.common.utils.BigItemStack;

import it.unimi.dsi.fastutil.booleans.BooleanObjectImmutablePair;

public class CopyMaterialReservationTest {

    private static sun.misc.Unsafe unsafe;
    private static Object featherBase;
    private static long featherOffset;
    private static boolean installedFeather;
    private final Item material = new Item();

    @BeforeClass
    public static void initializeNativeDamageAccessor() throws Exception {
        Field feather = Items.class.getDeclaredField("feather");
        if (feather.get(null) != null) return;
        // MM reads raw metadata through vanilla feather's inherited Item.getDamage, not stack.getItemDamage.
        // Plain JUnit has no vanilla registry bootstrap; supply the same default Item only for this class.
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        unsafe = (sun.misc.Unsafe) singleton.get(null);
        featherBase = unsafe.staticFieldBase(feather);
        featherOffset = unsafe.staticFieldOffset(feather);
        unsafe.putObjectVolatile(featherBase, featherOffset, new Item());
        installedFeather = true;
        assertNotNull(Items.feather);
    }

    @AfterClass
    public static void restoreNativeDamageAccessor() {
        if (installedFeather) {
            unsafe.putObjectVolatile(featherBase, featherOffset, null);
            installedFeather = false;
        }
    }

    @Test
    public void successfulSimulationWithNoRealExtractionCannotInstallOrMintARefund() {
        Inventory inventory = new Inventory(true, Collections.emptyList());
        assertFalse(CopyMaterialTransaction.execute(() -> CopyMaterialReservation.reserve(inventory, stack(1)), () -> {
            fail("An optimistic boolean is not payment");
            return true;
        }, () -> fail("No installation occurred"), () -> fail("No material was actually reserved")));
        assertEquals(1, inventory.extractions);
        assertEquals(0, inventory.refunds);
    }

    @Test
    public void partialExtractionReturnsOnlyTheActualQuantityOnceWithDetachedNbt() {
        BigItemStack received = BigItemStack.create(stack(1));
        Inventory inventory = new Inventory(true, Collections.singletonList(received));
        assertFalse(CopyMaterialReservation.reserve(inventory, stack(2)));
        assertEquals(1, inventory.extractions);
        assertEquals(1, inventory.refunds);
        assertEquals(
            1,
            inventory.returned.get(0)
                .getStackSize());
        assertNotSame(received, inventory.returned.get(0));
        inventory.returned.get(0).tag.setString("owner", "changed");
        assertEquals("original", received.tag.getString("owner"));
        assertEquals(1, received.getStackSize());
    }

    @Test
    public void failedResultWithAReceiptReturnsOnlyItsActualStacks() {
        Inventory inventory = new Inventory(false, Collections.singletonList(BigItemStack.create(stack(1))));
        assertFalse(CopyMaterialReservation.reserve(inventory, stack(2)));
        assertEquals(1, inventory.refunds);
        assertEquals(
            1,
            inventory.returned.get(0)
                .getStackSize());
    }

    @Test
    public void absentReceiptsNeverInventRefunds() {
        for (boolean success : new boolean[] { false, true }) {
            Inventory inventory = new Inventory(success, null);
            assertFalse(CopyMaterialReservation.reserve(inventory, stack(1)));
            assertEquals(0, inventory.refunds);
        }
    }

    @Test
    public void extraOrDifferentMaterialCannotCountAsExactPayment() {
        for (BigItemStack received : Arrays.asList(
            BigItemStack.create(stack(2)),
            BigItemStack.create(new ItemStack(new Item(), 1, 7)),
            BigItemStack.create(new ItemStack(material, 1, 8)))) {
            Inventory inventory = new Inventory(true, Collections.singletonList(received));
            assertFalse(CopyMaterialReservation.reserve(inventory, stack(1)));
            assertEquals(1, inventory.refunds);
            assertTrue(received.isSameType(inventory.returned.get(0)));
            assertEquals(
                received.getStackSize(),
                inventory.returned.get(0)
                    .getStackSize());
        }
    }

    @Test
    public void nbtMustMatchAndTheSubmittedRequestCannotRewriteTheExpectedPayment() {
        ItemStack changed = stack(1);
        changed.getTagCompound()
            .setString("owner", "different");
        Inventory inventory = new Inventory(true, Collections.singletonList(BigItemStack.create(changed)));
        inventory.mutateRequest = true;
        ItemStack original = stack(1);
        assertFalse(CopyMaterialReservation.reserve(inventory, original));
        assertEquals(
            "original",
            original.getTagCompound()
                .getString("owner"));
        assertEquals(1, original.stackSize);
        assertEquals(1, inventory.refunds);
    }

    @Test
    public void exactSplitReceiptsCommitWithoutASecondExtractionOrEarlyRefund() {
        Inventory inventory = new Inventory(
            true,
            Arrays.asList(BigItemStack.create(stack(1)), BigItemStack.create(stack(1))));
        assertTrue(CopyMaterialReservation.reserve(inventory, stack(2)));
        assertEquals(1, inventory.extractions);
        assertEquals(0, inventory.refunds);
    }

    @Test
    public void verifiedFullReservationUsesOnlyTheExistingTransactionRefundOnInstallFailure() {
        Inventory inventory = new Inventory(true, Collections.singletonList(BigItemStack.create(stack(1))));
        assertFalse(
            CopyMaterialTransaction.execute(
                () -> CopyMaterialReservation.reserve(inventory, stack(1)),
                () -> false,
                () -> {},
                () -> inventory.givePlayerItems(stack(1))));
        assertEquals(1, inventory.extractions);
        assertEquals(1, inventory.refunds);
        assertEquals(
            1,
            inventory.returned.get(0)
                .getStackSize());
    }

    @Test
    public void shortAnchorReceiptNeverReachesIncorporationOrItsRefund() {
        Inventory inventory = new Inventory(true, Collections.emptyList());
        IncorporationCopyCost.Result result = IncorporationCopyCost.apply(
            false,
            false,
            () -> CopyMaterialReservation.reserve(inventory, stack(1)),
            () -> fail("No anchor was extracted"),
            () -> {
                fail("No registration occurred");
                return false;
            },
            () -> fail("A missing anchor cannot be refunded"));
        assertFalse(result.successful());
        assertTrue(result.missingMaterial());
        assertEquals(1, inventory.extractions);
        assertEquals(0, inventory.refunds);
    }

    @Test
    public void verifiedAnchorHasOnlyOneRefundWhenRegistrationFails() {
        Inventory inventory = new Inventory(true, Collections.singletonList(BigItemStack.create(stack(1))));
        IncorporationCopyCost.Result result = IncorporationCopyCost.apply(
            false,
            false,
            () -> CopyMaterialReservation.reserve(inventory, stack(1)),
            () -> { throw new IllegalStateException("before registration"); },
            () -> false,
            () -> inventory.givePlayerItems(stack(1)));
        assertFalse(result.successful());
        assertNotNull(result.failure());
        assertEquals(1, inventory.extractions);
        assertEquals(1, inventory.refunds);
        assertEquals(
            1,
            inventory.returned.get(0)
                .getStackSize());
    }

    private ItemStack stack(int size) {
        ItemStack stack = new ItemStack(material, size, 7);
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setString("owner", "original");
        return stack;
    }

    private static final class Inventory implements IPseudoInventory {

        final boolean success;
        final List<BigItemStack> receipt;
        final List<BigItemStack> returned = new ArrayList<>();
        int extractions, refunds;
        boolean mutateRequest;

        Inventory(boolean success, List<BigItemStack> receipt) {
            this.success = success;
            this.receipt = receipt;
        }

        @Override
        public BooleanObjectImmutablePair<List<BigItemStack>> tryConsumeItems(List<BigItemStack> items, int flags) {
            extractions++;
            assertEquals(0, flags);
            if (mutateRequest) {
                items.get(0)
                    .setStackSize(100);
                items.get(0).tag.setString("owner", "different");
            }
            return BooleanObjectImmutablePair.of(success, receipt);
        }

        @Override
        public void givePlayerItems(List<BigItemStack> items) {
            refunds++;
            returned.addAll(items);
        }

        @Override
        public void givePlayerFluids(List<BigFluidStack> fluids) {
            fail("Item reservations cannot affect fluids");
        }
    }
}
