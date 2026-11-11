// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;

import org.junit.Rule;
import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;

import appeng.api.config.Actionable;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.HashBasedItemList;

/** Real source handlers with detached AE value objects; no Forge/AE mod bootstrap is required by JUnit. */
public class StressSupplyTileTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @Test
    public void simulationDoesNotCountTransfersAndOnlyConfiguredInputsCanBeExtracted() throws Exception {
        StressSupplyTile tile = tile(true);
        IAEItemStack raw = stack("raw", 1), product = stack("product", 1);
        rebuild(tile, raw);
        IMEInventoryHandler<IAEItemStack> inventory = items(tile);
        IAEItemStack request = raw.copy()
            .setStackSize(5000000000L);
        assertEquals(
            5000000000L,
            inventory.extractItems(request, Actionable.SIMULATE, null)
                .getStackSize());
        assertNull(inventory.extractItems(product, Actionable.SIMULATE, null));
        assertNull(inventory.injectItems(product, Actionable.SIMULATE, null));
        assertEquals(0L, tile.itemExtracted);
        assertEquals(0L, tile.itemInserted);
        inventory.extractItems(request, Actionable.MODULATE, null);
        inventory.injectItems(
            product.copy()
                .setStackSize(9000),
            Actionable.MODULATE,
            null);
        assertEquals(5000000000L, tile.itemExtracted);
        assertEquals(9000L, tile.itemInserted);
    }

    @Test
    public void rebuildingHandlerPaletteReplacesOldTypesAndExposedListsCannotMutateInfiniteSource() throws Exception {
        StressSupplyTile tile = tile(true);
        IAEItemStack old = stack("old", 1), current = stack("current", 1);
        rebuild(tile, old);
        IMEInventoryHandler<IAEItemStack> inventory = items(tile);
        rebuild(tile, current);
        assertNull(inventory.getAvailableItem(old, 1));
        long expected = (1L << 52) - 1;
        assertEquals(
            expected,
            inventory.getAvailableItem(current, 2)
                .getStackSize());
        IItemList<IAEItemStack> available = new HashBasedItemList();
        inventory.getAvailableItems(available, 3);
        available.getFirstItem()
            .setStackSize(1);
        assertEquals(
            expected,
            inventory.getAvailableItem(current, 4)
                .getStackSize());
        rebuild(tile, current);
        assertEquals(
            expected,
            inventory.getAvailableItem(current, 5)
                .getStackSize());
    }

    @Test
    public void ordinaryWorldAndDisabledHarnessRejectCreativeOperations() throws Exception {
        StressSupplyTile tile = tile(false);
        IAEItemStack request = stack("material", 1);
        rebuild(tile, request);
        IMEInventoryHandler<IAEItemStack> inventory = items(tile);
        assertSame(request, inventory.injectItems(request, Actionable.MODULATE, null));
        assertNull(inventory.extractItems(request, Actionable.MODULATE, null));
        tile = tile(true);
        rebuild(tile, request);
        inventory = items(tile);
        assertNotNull(inventory.extractItems(request, Actionable.SIMULATE, null));
        harness.restart(false);
        assertNull(inventory.extractItems(request, Actionable.MODULATE, null));
        assertSame(request, inventory.injectItems(request, Actionable.MODULATE, null));
        assertEquals(0L, tile.itemExtracted);
        assertEquals(0L, tile.itemInserted);
    }

    @Test
    public void wirelessClampHasSeparateLowerAndUpperBoundsWithoutChangingBalancesInsideThem() {
        assertEquals(-1, StressSupplyTile.clampDirection(StressSupplyTile.EU_LOW.subtract(java.math.BigInteger.ONE)));
        assertEquals(0, StressSupplyTile.clampDirection(StressSupplyTile.EU_LOW));
        assertEquals(0, StressSupplyTile.clampDirection(StressSupplyTile.EU_TARGET));
        assertEquals(0, StressSupplyTile.clampDirection(StressSupplyTile.EU_HIGH));
        assertEquals(1, StressSupplyTile.clampDirection(StressSupplyTile.EU_HIGH.add(java.math.BigInteger.ONE)));
    }

    @Test
    public void perHatchStockLimitMatchesSimulationAndCommitWithoutRestrictingOtherMachines() throws Exception {
        StressSupplyTile tile = tile(true), hatch = (StressSupplyTile) allocate(StressSupplyTile.class);
        hatch.setWorldObj(tile.getWorldObj());
        hatch.xCoord = 12;
        hatch.yCoord = 160;
        hatch.zCoord = 8;
        IAEItemStack request = stack("material", Integer.MAX_VALUE);
        rebuild(tile, request.copy());
        Class<?> limitType = Class.forName(StressSupplyTile.class.getName() + "$RequestLimit");
        Constructor<?> constructor = limitType.getDeclaredConstructor(long.class, Map.class);
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Long, Object> limits = (Map<Long, Object>) field(StressSupplyTile.class, "limits").get(tile);
        limits.put(
            QuantumControllerRegistry.pack(hatch.xCoord, hatch.yCoord, hatch.zCoord),
            constructor.newInstance(4096L, Collections.emptyMap()));
        IMEInventoryHandler<IAEItemStack> inventory = items(tile);
        MachineSource source = new MachineSource(hatch);
        assertEquals(
            4096,
            inventory.extractItems(request, Actionable.SIMULATE, source)
                .getStackSize());
        assertEquals(0, tile.itemExtracted);
        assertEquals(
            4096,
            inventory.extractItems(request, Actionable.MODULATE, source)
                .getStackSize());
        assertEquals(4096, tile.itemExtracted);
        hatch.xCoord++;
        assertEquals(
            Integer.MAX_VALUE,
            inventory.extractItems(request, Actionable.SIMULATE, source)
                .getStackSize());
    }

    @SuppressWarnings("unchecked")
    private static IMEInventoryHandler<IAEItemStack> items(StressSupplyTile tile) throws Exception {
        return (IMEInventoryHandler<IAEItemStack>) field(StressSupplyTile.class, "items").get(tile);
    }

    private static void rebuild(StressSupplyTile tile, IAEItemStack ingredient) throws Exception {
        Object handler = items(tile);
        Method clear = handler.getClass()
            .getDeclaredMethod("clearPalette");
        clear.setAccessible(true);
        clear.invoke(handler);
        Method add = StressSupplyTile.class.getDeclaredMethod("add", handler.getClass(), IAEStack.class);
        add.setAccessible(true);
        add.invoke(null, handler, ingredient);
    }

    private static StressSupplyTile tile(boolean test) throws Exception {
        // Native AE stack types bootstrap the entire mod; exercise its value/list boundary independently.
        StressSupplyTile tile = (StressSupplyTile) allocate(StressSupplyTile.class);
        World world = (World) allocate(WorldServer.class);
        WorldProvider provider = test ? new WorldProviderTest() : new WorldProviderSurface();
        provider.dimensionId = TestDimension.dimId();
        field(World.class, "provider").set(world, provider);
        tile.setWorldObj(world);
        field(StressSupplyTile.class, "limits").set(tile, new HashMap<>());
        IAEStackType<?> type = (IAEStackType<?>) Proxy.newProxyInstance(
            IAEStackType.class.getClassLoader(),
            new Class<?>[] { IAEStackType.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("createPrimitiveList")) return new HashBasedItemList();
                throw new UnsupportedOperationException("Unexpected AE stack type call: " + method.getName());
            });
        Class<?> handlerType = Class.forName(StressSupplyTile.class.getName() + "$Handler");
        Constructor<?> constructor = handlerType
            .getDeclaredConstructor(StressSupplyTile.class, IAEStackType.class, boolean.class);
        constructor.setAccessible(true);
        field(StressSupplyTile.class, "items").set(tile, constructor.newInstance(tile, type, false));
        return tile;
    }

    private static IAEItemStack stack(String identity, long amount) {
        return (IAEItemStack) Proxy.newProxyInstance(
            IAEItemStack.class.getClassLoader(),
            new Class<?>[] { IAEItemStack.class },
            new StackValue(identity, amount));
    }

    private static final class StackValue implements InvocationHandler {

        final String identity;
        long amount;

        StackValue(String identity, long amount) {
            this.identity = identity;
            this.amount = amount;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getStackSize":
                    return amount;
                case "setStackSize":
                    amount = (Long) args[0];
                    return proxy;
                case "copy":
                    return stack(identity, amount);
                case "isMeaningful":
                    return amount > 0;
                case "hashCode":
                    return identity.hashCode();
                case "equals":
                    if (args[0] == null || !Proxy.isProxyClass(args[0].getClass())) return false;
                    InvocationHandler other = Proxy.getInvocationHandler(args[0]);
                    return other instanceof StackValue && identity.equals(((StackValue) other).identity);
                case "toString":
                    return identity + "*" + amount;
                default:
                    throw new UnsupportedOperationException("Unexpected AE stack value call: " + method.getName());
            }
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
