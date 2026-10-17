package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.implementations.parts.IPartCable;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartItemStack;

public class AEPartCopyTest {

    @Test
    public void quantumCableIsExcludedBeforeItsAbsentItemCanBeRead() {
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        assertTrue(node.getPart(ForgeDirection.UNKNOWN) instanceof IPartCable);
        assertNull(
            node.getPart(ForgeDirection.UNKNOWN)
                .getItemStack(PartItemStack.Break));
        assertNull(AEPartCopy.transferablePart(node, ForgeDirection.UNKNOWN));
        // Filtering is a view for MM; it does not mutate the node or its AE cable protocol.
        assertSame(node.getCablePart(), node.getPart(ForgeDirection.UNKNOWN));
    }

    @Test
    public void ordinaryCablesAndSidePartsRemainTransferableWithoutReadingTheirItems() {
        AtomicInteger itemReads = new AtomicInteger();
        IPartCable cable = (IPartCable) Proxy.newProxyInstance(
            IPartCable.class.getClassLoader(),
            new Class<?>[] { IPartCable.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getItemStack")) itemReads.incrementAndGet();
                return null;
            });
        AtomicReference<ForgeDirection> requested = new AtomicReference<>();
        IPartHost host = host(cable, requested);
        for (ForgeDirection side : ForgeDirection.values()) {
            assertSame(cable, AEPartCopy.transferablePart(host, side));
            assertSame(side, requested.get());
        }
        assertEquals(0, itemReads.get());
    }

    @Test
    public void onlyTheVirtualCenterIsExcludedAndMissingPartsStayMissing() {
        IPart virtual = new TileEntityNetworkQuantumNode().getCablePart();
        IPartHost host = host(virtual, new AtomicReference<>());
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            assertSame(virtual, AEPartCopy.transferablePart(host, side));
        }
        IPartHost empty = host(null, new AtomicReference<>());
        for (ForgeDirection side : ForgeDirection.values()) {
            assertNull(AEPartCopy.transferablePart(empty, side));
        }
    }

    private static IPartHost host(IPart part, AtomicReference<ForgeDirection> requested) {
        return (IPartHost) Proxy.newProxyInstance(
            IPartHost.class.getClassLoader(),
            new Class<?>[] { IPartHost.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getPart")) {
                    requested.set((ForgeDirection) args[0]);
                    return part;
                }
                return null;
            });
    }
}
