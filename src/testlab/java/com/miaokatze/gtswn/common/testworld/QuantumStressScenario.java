package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/** Explicit-only stress: bounded registry sections followed by natural-tick, real multi-node recovery. */
final class QuantumStressScenario implements TestScenario {

    private static final int X = 8, Y = TestWorldFixtures.Y, Z = 8, NODE_Z = 16;
    private static final int NODES = 8, WARMUPS = 2, SAMPLES = 5, CALLS = 4;
    private final List<TileEntityNetworkQuantumNode> nodes = new ArrayList<>();
    private final Set<Long> positions = new HashSet<>();
    private final long[] aliases = new long[4];
    private QuantumControllerRegistry isolated;
    private FakePlayer owner;
    private ItemStack terminal;
    private TileEntity controller, second;
    private int stage, sizeIndex, prepareCursor, section, sample, placedNodes;
    private long stageTick;
    private boolean safetyRaised;

    @Override
    public String id() {
        return "quantum.explicit_stress";
    }

    @Override
    public String description() {
        return "Explicit stress: 343/4096 saved graph cold/warm/change and eight natural alias-node OFF/ON recoveries";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int timeoutTicks() {
        return 2400;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        nodes.clear();
        positions.clear();
        owner = null;
        terminal = null;
        controller = second = null;
        isolated = null;
        stage = sizeIndex = prepareCursor = section = sample = placedNodes = 0;
        stageTick = ctx.tick();
        safetyRaised = false;
        ctx.event(
            "stress_contract",
            "Explicit only; 256 coordinate additions/tick, four queries/tick, one node placement/tick; 2 warmups/5 samples. Registry graph is isolated in memory, not 4096 real AE controllers.");
    }

    @Override
    public void tick(TestContext ctx) {
        power();
        if (ctx.tick() - stageTick > 650) {
            ctx.fail("Explicit stress transition timed out: stage=" + stage + ", linked=" + linked());
            return;
        }
        if (stage == 0) {
            prepareGraph(ctx);
        } else if (stage == 1) {
            graphSample(ctx);
        } else if (stage == 2) {
            prepareNative(ctx);
        } else if (stage == 3) {
            if (ctx.tick() - stageTick < 5 || !active(controller) || !active(second)) return;
            long started = ctx.operationStart();
            ((ItemNetworkQuantumTerminal) terminal.getItem())
                .onItemUseFirst(terminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
            ctx.operationEnd("stress.native_bind_controller", started);
            TestWorldFixtures.require(
                ctx,
                ItemNetworkQuantumTerminal.isBound(terminal),
                "Real first controller click binds stress terminal");
            next(ctx, 4);
        } else if (stage == 4) {
            placeNode(ctx);
        } else if (stage == 5) {
            if (linked() != NODES) return;
            ctx.event(
                "stress_state",
                "All eight actual nodes linked through two controller aliases; starting 80 natural OFF ticks");
            next(ctx, 6);
        } else if (stage == 6) {
            TestWorldFixtures
                .require(ctx, linked() == NODES, "Eight nodes remain linked during natural OFF steady state");
            if (ctx.tick() - stageTick < 80) return;
            if (!Config.quantumSafetyEnabled) {
                ctx.event(
                    "stress_skip",
                    "ON revocation subsection skipped: global quantum safety is disabled; Config unchanged");
                ctx.pass(
                    "Explicit registry stress and eight real alias-node OFF steady-state passed; ON subsection skipped");
                return;
            }
            long started = ctx.operationStart();
            safetyRaised = QuantumTerminalAccess.setNetworkSafety(owner, terminal, true);
            ctx.operationEnd("stress.native_policy_on", started);
            TestWorldFixtures.require(ctx, safetyRaised, "Native owner enables local ON for all stress aliases");
            next(ctx, 7);
        } else if (stage == 7) {
            if (linked() != 0) return;
            ctx.event(
                "stress_state",
                "Local ON withdrew providerless OFF admission for all eight actual nodes; this is policy revocation, not biometric BUILD revocation");
            long started = ctx.operationStart();
            TestWorldFixtures.require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, terminal, false),
                "Native owner restores local OFF after policy revocation");
            safetyRaised = false;
            ctx.operationEnd("stress.native_policy_off", started);
            next(ctx, 8);
        } else if (stage == 8 && linked() == NODES) {
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_CONNECT) >= NODES,
                "Production probes observed real native stress node connections");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_DISCONNECT) >= NODES,
                "Production probes observed local ON revoking every stress node bridge");
            ctx.pass(
                "Explicit 343/4096 graph samples and eight real alias-node OFF steady state, ON revocation and natural recovery passed");
        }
    }

    private void prepareGraph(TestContext ctx) {
        int side = sizeIndex == 0 ? 7 : 16;
        int limit = Math.min(side * side * side, prepareCursor + 256);
        for (; prepareCursor < limit; prepareCursor++) {
            int x = prepareCursor / (side * side), y = prepareCursor / side % side, z = prepareCursor % side;
            positions.add(QuantumControllerRegistry.pack(-28 + x, 80 + y, -28 + z));
        }
        if (prepareCursor != side * side * side) return;
        isolated = new QuantumControllerRegistry("gtswn-explicit-stress-local");
        isolated.quantizeAll(positions);
        aliases[0] = QuantumControllerRegistry.pack(-28, 80, -28);
        aliases[1] = QuantumControllerRegistry.pack(-29 + side, 80, -28);
        aliases[2] = QuantumControllerRegistry.pack(-28, 79 + side, -29 + side);
        aliases[3] = QuantumControllerRegistry.pack(-29 + side, 79 + side, -29 + side);
        section = 0;
        sample = -WARMUPS;
        next(ctx, 1);
    }

    private void graphSample(TestContext ctx) {
        int side = sizeIndex == 0 ? 7 : 16;
        if (section != 1) {
            // Same final graph; mutation and setup are deliberately outside the measured query interval.
            isolated.dequantize(aliases[0]);
            isolated.quantize(-28, 80, -28);
        } else isolated.registeredStructure(aliases[0]);
        long started = sample < 0 ? 0 : ctx.operationStart();
        int calls = section == 0 ? 1 : CALLS;
        int checksum = 0;
        for (int call = 0; call < calls; call++) checksum += isolated.registeredStructure(aliases[call])
            .size();
        if (sample >= 0) ctx.operationEnd(
            "stress.graph_" + side + "_" + (section == 0 ? "cold" : section == 1 ? "warm_alias" : "changed"),
            started);
        TestWorldFixtures.require(
            ctx,
            checksum == side * side * side * calls,
            "Stress graph membership remains complete across aliases and revision changes");
        if (++sample < SAMPLES) return;
        sample = -WARMUPS;
        if (++section < 3) return;
        ctx.event(
            "stress_graph",
            "side=" + side
                + "; members="
                + side * side * side
                + "; measuredSamples=5/section; warmupSamples=2/section; coldCalls=1; aliasCalls=4");
        positions.clear();
        isolated = null;
        prepareCursor = 0;
        next(ctx, ++sizeIndex < 2 ? 0 : 2);
    }

    private void prepareNative(TestContext ctx) {
        owner = TestActors.player(ctx.world(), "owner");
        owner.setPosition(X + .5, Y, Z + 2.5);
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        owner.inventory.setInventorySlotContents(0, terminal);
        controller = TestWorldFixtures.aeOwnedBlock(ctx.world(), X, Y, Z, controllerItem(), owner);
        second = TestWorldFixtures.aeOwnedBlock(ctx.world(), X + 1, Y, Z, controllerItem(), owner);
        next(ctx, 3);
    }

    private static ItemStack controllerItem() {
        return AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeStack(1)
            .get();
    }

    private void placeNode(TestContext ctx) {
        long started = ctx.operationStart();
        int x = -8 + placedNodes * 3;
        TestWorldFixtures.place(ctx.world(), x, Y, NODE_Z, BlockRegistrar.networkQuantumNode, 0);
        TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) ctx.world()
            .getTileEntity(x, Y, NODE_Z);
        // Public legacy binding entrypoint deliberately alternates member aliases; production canonicalizes it.
        node.setAnchor(ctx.world().provider.dimensionId, X + placedNodes % 2, Y, Z);
        node.setPlacer(owner);
        nodes.add(node);
        ctx.operationEnd("stress.native_place_alias_node", started);
        if (++placedNodes == NODES) next(ctx, 5);
    }

    private int linked() {
        int count = 0;
        for (TileEntityNetworkQuantumNode node : nodes) if (node.isLinked()) count++;
        return count;
    }

    private static boolean active(TileEntity tile) {
        return tile instanceof TileController && ItemNetworkQuantumTerminal.isControllerActive((TileController) tile);
    }

    private void power() {
        if (controller == null || controller.isInvalid()) return;
        try {
            if (((IGridProxyable) controller).getProxy()
                .isReady())
                ((IGridProxyable) controller).getProxy()
                    .getEnergy()
                    .injectPower(2000, Actionable.MODULATE);
        } catch (GridAccessException ignored) { /* Natural AE startup is observed on the next server tick. */ }
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("stage", "explicit_stress:" + value);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (safetyRaised && owner != null && terminal != null) {
            TestWorldFixtures.require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, terminal, false),
                "Stress cleanup restores its local OFF policy");
            safetyRaised = false;
        }
        for (TileEntityNetworkQuantumNode node : nodes) if (!node.isInvalid()) {
            node.disconnectFromDequantizedAnchor();
            ctx.world()
                .setBlockToAir(node.xCoord, node.yCoord, node.zCoord);
        }
        if (controller != null) {
            QuantumControllerRegistry.get(ctx.world())
                .dequantize(X, Y, Z);
            ctx.world()
                .setBlockToAir(X, Y, Z);
        }
        if (second != null) {
            QuantumControllerRegistry.get(ctx.world())
                .dequantize(X + 1, Y, Z);
            ctx.world()
                .setBlockToAir(X + 1, Y, Z);
        }
        if (owner != null) owner.inventory.clearInventory(null, -1);
        nodes.clear();
        positions.clear();
        isolated = null;
    }
}
