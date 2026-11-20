// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.testworld.NativeSecurityStationSupport.RecordingActor;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.parts.IPart;
import appeng.parts.AEBasePart;
import appeng.parts.CableBusContainer;
import appeng.parts.PartPlacement;
import appeng.parts.networking.PartQuartzFiber;
import appeng.tile.networking.TileCableBus;
import appeng.tile.networking.TileController;

/** Issue #143: real server item callbacks, with no client packet, renderer or GUI coverage. */
final class QuantumNativeBusSwap143Scenario implements TestScenario {

    private static final int CONTROLLER_X = -24, REJECT_X = -20, ACCEPT_X = -16, Y = TestWorldFixtures.Y, Z = 24;
    private static final ForgeDirection CLICK_FACE = ForgeDirection.SOUTH;
    private RecordingActor actor;
    private TileController controller;
    private HostFixture reject, accept;
    private ItemStack terminal, heldBeforeSwap;
    private int stage;

    @Override
    public String id() {
        return "quantum.native_bus_swap_143";
    }

    @Override
    public String description() {
        return "Ordinary server item use rejects Quartz Fiber without changing the native bus, and still swaps a compatible bus";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public boolean preserveFixturesBeforeRun() {
        // No reset: refuse occupied cells instead of modifying pre-existing test fixtures.
        return true;
    }

    @Override
    public int timeoutTicks() {
        return 400;
    }

    @Override
    public void start(TestContext ctx) {
        WorldServer world = ctx.world();
        TestWorldFixtures.requireTestWorld(world);
        for (int x : new int[] { CONTROLLER_X, REJECT_X, ACCEPT_X }) {
            requireVacant(ctx, x, Y, Z);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                requireVacant(ctx, x + side.offsetX, Y + side.offsetY, Z + side.offsetZ);
            }
        }
        require(ctx, noDrops(world, REJECT_X) && noDrops(world, ACCEPT_X), "No pre-existing item drops near the buses");
        stage = 0;
        actor = NativeSecurityStationSupport.actor(ctx, "owner");
        actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "issue #143", actor);
        controller = (TileController) TestWorldFixtures.aeOwnedBlock(
            world,
            CONTROLLER_X,
            Y,
            Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            actor);
        reject = prepareBus(ctx, REJECT_X, true);
        accept = prepareBus(ctx, ACCEPT_X, false);
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        ctx.event("swap_scope", "Server item callbacks with a non-OP survival actor; no client, Netty or GUI proof");
    }

    @Override
    public void tick(TestContext ctx) {
        NativeSecurityStationSupport.power(controller);
        if (stage == 0) {
            if (!ItemNetworkQuantumTerminal.isControllerActive(controller) || !reject.container.isInWorld()
                || !accept.container.isInWorld()) return;
            useTerminal(ctx, CONTROLLER_X);
            require(ctx, ItemNetworkQuantumTerminal.isBound(terminal), "Ordinary controller click bound the terminal");
            stage = 1;
            return;
        }
        if (stage == 1) {
            require(ctx, QuantumTerminalAccess.validate(actor, terminal), "Original terminal remains usable");
            heldBeforeSwap = terminal.copy();
            useTerminal(ctx, REJECT_X);
            expectMessage(ctx, "gtswn.chat.quantum.swap_failed");
            assertRejected(ctx);
            stage = 2;
            return;
        }
        if (stage == 2) {
            assertRejected(ctx);
            useTerminal(ctx, ACCEPT_X);
            expectMessage(ctx, "gtswn.chat.quantum.swap_done", 1);
            assertAccepted(ctx);
            stage = 3;
            return;
        }
        assertRejected(ctx);
        assertAccepted(ctx);
        ctx.pass(
            "Quartz Fiber refusal preserved native host, all parts, held terminal and drops; compatible native bus swapped in place");
    }

    private HostFixture prepareBus(TestContext ctx, int x, boolean withQuartzFiber) {
        WorldServer world = ctx.world();
        ItemStack multipart = AEApi.instance()
            .definitions()
            .blocks()
            .multiPart()
            .maybeStack(1)
            .get();
        TileCableBus bus = (TileCableBus) TestWorldFixtures.aeOwnedBlock(world, x, Y, Z, multipart, actor);
        addPart(
            ctx,
            bus,
            x,
            CLICK_FACE,
            AEApi.instance()
                .definitions()
                .parts()
                .storageBus()
                .maybeStack(1)
                .get());
        if (withQuartzFiber) {
            addPart(
                ctx,
                bus,
                x,
                ForgeDirection.UP,
                AEApi.instance()
                    .definitions()
                    .parts()
                    .quartzFiber()
                    .maybeStack(1)
                    .get());
            require(
                ctx,
                bus.getPart(ForgeDirection.UP) instanceof PartQuartzFiber,
                "Rejected bus has real AE2 Quartz Fiber");
        }
        require(ctx, bus.getPart(ForgeDirection.UNKNOWN) == null, "Original native bus has no center cable");
        return new HostFixture(world, x, bus);
    }

    private void addPart(TestContext ctx, TileCableBus bus, int x, ForgeDirection side, ItemStack stack) {
        positionActor(x);
        actor.inventory.setInventorySlotContents(0, stack);
        require(
            ctx,
            PartPlacement.tryPlace(stack, actor, ctx.world(), x, Y, Z, side, bus) && stack.stackSize == 0
                && bus.getPart(side) instanceof AEBasePart,
            "Native AE2 part placed on " + side);
    }

    private void useTerminal(TestContext ctx, int x) {
        positionActor(x);
        actor.inventory.setInventorySlotContents(0, terminal);
        actor.clearMessages();
        // This enters the normal Forge right-click and onItemUseFirst path, not the swap helper directly.
        require(
            ctx,
            actor.theItemInWorldManager
                .activateBlockOrUseItem(actor, ctx.world(), terminal, x, Y, Z, CLICK_FACE.ordinal(), .5f, .5f, 1f),
            "Forge consumed the terminal interaction at x=" + x);
    }

    private void positionActor(int x) {
        actor.setPosition(x + .5, Y, Z + 2.5);
        actor.setSneaking(false);
    }

    private void expectMessage(TestContext ctx, String key, Object... args) {
        String expected = StatCollector.translateToLocalFormatted(key, args);
        ctx.event("swap_feedback", key + ": " + actor.messages());
        require(
            ctx,
            actor.messages()
                .stream()
                .anyMatch(message -> message.endsWith(" | " + expected)),
            "Actual item interaction reported " + key);
    }

    private void assertRejected(TestContext ctx) {
        WorldServer world = ctx.world();
        require(
            ctx,
            world.getBlock(REJECT_X, Y, Z) == reject.block && world.getBlockMetadata(REJECT_X, Y, Z) == reject.meta
                && world.getTileEntity(REJECT_X, Y, Z) == reject.bus
                && !reject.bus.isInvalid()
                && reject.bus.getCableBus() == reject.container
                && reject.container.getPart(ForgeDirection.UNKNOWN) == null,
            "Rejection kept the same native block, tile and container");
        assertPartsAndInventory(ctx, reject, reject.bus);
    }

    private void assertAccepted(TestContext ctx) {
        TileEntity tile = ctx.world()
            .getTileEntity(ACCEPT_X, Y, Z);
        require(
            ctx,
            ctx.world()
                .getBlock(ACCEPT_X, Y, Z) == BlockRegistrar.networkQuantumNode
                && tile instanceof TileEntityNetworkQuantumNode
                && !tile.isInvalid()
                && ((TileEntityNetworkQuantumNode) tile).getPartContainer() == accept.container,
            "Compatible bus became a valid quantum node owning the original native container");
        assertPartsAndInventory(ctx, accept, tile);
    }

    private void assertPartsAndInventory(TestContext ctx, HostFixture fixture, TileEntity host) {
        require(ctx, fixture.container.getTile() == host, "Native container is attached to the expected tile");
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            IPart part = fixture.parts[side.ordinal()];
            require(
                ctx,
                fixture.container.getPart(side) == part && (part == null || ((AEBasePart) part).getTile() == host),
                "Original part and expected host preserved at " + side);
        }
        require(
            ctx,
            actor.getHeldItem() == terminal && ItemStack.areItemStacksEqual(terminal, heldBeforeSwap),
            "Held terminal, count and NBT are unchanged");
        require(ctx, noDrops(ctx.world(), fixture.x), "No item drops appeared near the bus");
    }

    private static void requireVacant(TestContext ctx, int x, int y, int z) {
        require(
            ctx,
            ctx.world()
                .isAirBlock(x, y, z)
                && ctx.world()
                    .getTileEntity(x, y, z) == null,
            "Vacant issue #143 fixture space required at " + x + "," + y + "," + z);
    }

    private static boolean noDrops(WorldServer world, int x) {
        return world
            .getEntitiesWithinAABB(
                EntityItem.class,
                AxisAlignedBB.getBoundingBox(x - 1, Y - 1, Z - 1, x + 2, Y + 2, Z + 2))
            .isEmpty();
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (actor != null) actor.inventory.clearInventory(null, -1);
        actor = null;
        controller = null;
        terminal = heldBeforeSwap = null;
        reject = accept = null;
        // Retain only this run's fixtures for inspection; never clear the surrounding test world.
    }

    private static final class HostFixture {

        final int x, meta;
        final Block block;
        final TileCableBus bus;
        final CableBusContainer container;
        final IPart[] parts = new IPart[ForgeDirection.VALID_DIRECTIONS.length];

        HostFixture(WorldServer world, int x, TileCableBus bus) {
            this.x = x;
            this.bus = bus;
            this.block = world.getBlock(x, Y, Z);
            this.meta = world.getBlockMetadata(x, Y, Z);
            this.container = bus.getCableBus();
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                parts[side.ordinal()] = bus.getPart(side);
            }
        }
    }
}
