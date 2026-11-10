package com.miaokatze.gtswn.common.testworld;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumMigrationJournal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkMigration;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/** Each mode creates its own real AE grids. No mocked grid, accelerated tick or global registry reset. */
final class QuantumWorldScenario implements TestScenario {

    enum Mode {
        BRIDGE,
        MIGRATION,
        INCORPORATION,
        SAFETY
    }

    private final Mode mode;
    private FakePlayer player, outsider;
    private ItemStack terminal;
    private TileEntity controller, target, incorporated;
    private TileEntityNetworkQuantumNode node;
    private int stage;
    private long stageTick;
    private String incorporationIdentity;
    private long incorporationRejectsBefore;
    private NBTTagCompound incorporationBindingBeforeReject, terminalBeforeReject;
    private boolean incorporationSafetyRaised;
    private ItemStack terminalBeforePause;
    private NBTTagCompound terminalBindingBeforePause, incorporationBindingBeforePause;
    private long canonicalBeforePause;
    private String generationBeforePause;
    private int nodeOwnerBeforePause;

    QuantumWorldScenario(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String id() {
        switch (mode) {
            case MIGRATION:
                return "quantum.migration";
            case INCORPORATION:
                return "quantum.incorporation_nbt";
            case SAFETY:
                return "quantum.safety";
            default:
                return "quantum.bridge_nbt";
        }
    }

    @Override
    public String description() {
        return "Real AE controller/terminal natural-tick workflow: " + mode.name()
            .toLowerCase();
    }

    @Override
    public int timeoutTicks() {
        return 1600;
    }

    @Override
    public void start(TestContext ctx) {
        stage = 0;
        stageTick = 0;
        player = null;
        outsider = null;
        incorporationSafetyRaised = false;
        incorporationBindingBeforeReject = null;
        terminalBeforeReject = null;
        terminalBeforePause = null;
        terminalBindingBeforePause = null;
        incorporationBindingBeforePause = null;
        if (mode == Mode.SAFETY && !Config.quantumSafetyEnabled) {
            ctx.skip("Optional quantum safety is disabled by current Config; test does not change global Config");
            return;
        }
        player = TestActors.player(ctx.world(), "owner");
        outsider = TestActors.player(ctx.world(), "outsider");
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        player.inventory.setInventorySlotContents(0, terminal);
        controller = controller(ctx, TestWorldFixtures.CONTROLLER_X);
        target = controller(ctx, TestWorldFixtures.TARGET_X);
        TestWorldFixtures.place(
            ctx.world(),
            TestWorldFixtures.NODE_X,
            TestWorldFixtures.Y,
            TestWorldFixtures.NODE_Z,
            BlockRegistrar.networkQuantumNode,
            0);
        node = (TileEntityNetworkQuantumNode) ctx.world()
            .getTileEntity(TestWorldFixtures.NODE_X, TestWorldFixtures.Y, TestWorldFixtures.NODE_Z);
        incorporated = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            TestWorldFixtures.INCORPORATION_X,
            TestWorldFixtures.Y,
            TestWorldFixtures.INCORPORATION_Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCell()
                .maybeStack(1)
                .get(),
            player);
        ((IGridProxyable) incorporated).getProxy()
            .setOwner(player);
        ctx.event("fixture", "Two isolated real AE controllers use bounded injected fixture power through IEnergyGrid");
    }

    private TileEntity controller(TestContext ctx, int x) {
        TileEntity tile = TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            x,
            TestWorldFixtures.Y,
            TestWorldFixtures.CONTROLLER_Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get(),
            player);
        ((IGridProxyable) tile).getProxy()
            .setOwner(player);
        return tile;
    }

    @Override
    public void tick(TestContext ctx) {
        power(controller);
        power(target);
        if (ctx.tick() == 3) {
            TestWorldFixtures.require(
                ctx,
                !node.hasAnchor() && !node.isLinked()
                    && node.getOfflineReason() == TileEntityNetworkQuantumNode.OfflineReason.NO_ANCHOR,
                "Natural blank-node tick remains offline with NO_ANCHOR");
        }
        if (stage == 0) {
            if (ctx.tick() < 5 || !ItemNetworkQuantumTerminal.isControllerActive((TileController) controller)
                || !ItemNetworkQuantumTerminal.isControllerActive((TileController) target)) return;
            TestWorldFixtures.require(
                ctx,
                ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                    .getPlayerID()
                    == AEApi.instance()
                        .registries()
                        .players()
                        .getID(player),
                "Native AE item placement assigned the actual controller grid owner to the synthetic actor");
            clickController(ctx, false);
            TestWorldFixtures.require(
                ctx,
                registry(ctx)
                    .isQuantized(TestWorldFixtures.CONTROLLER_X, TestWorldFixtures.Y, TestWorldFixtures.CONTROLLER_Z),
                "Real terminal click quantized controller");
            TestWorldFixtures
                .require(ctx, ItemNetworkQuantumTerminal.isBound(terminal), "Real terminal click bound anchor");
            ItemNetworkQuantumTerminal.applyAnchorToNode(terminal, node, player);
            next(ctx, 1);
            return;
        }
        if (stage == 1) {
            if (!node.isLinked()) return;
            TestWorldFixtures.require(
                ctx,
                node.getNativeOwnerID() == AEApi.instance()
                    .registries()
                    .players()
                    .getID(player),
                "Production node placer owns the actual AE grid node");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_CONTROLLER_ADD) > 0
                    && TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_CONNECT) > 0,
                "Production controller and node bridge hooks observed real natural connection");
            TestWorldFixtures.require(
                ctx,
                node.getCoreGridNode()
                    .getGrid()
                    == ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                        .getGrid(),
                "Natural quantum bridge joins the actual controller grid");
            if (mode == Mode.INCORPORATION) {
                TestWorldFixtures.require(
                    ctx,
                    QuantumIncorporationRegistry
                        .isEligible(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord),
                    "Real AE energy cell is eligible for incorporation");
                TestWorldFixtures.require(
                    ctx,
                    incorporations(ctx).add(
                        ctx.world(),
                        incorporated.xCoord,
                        incorporated.yCoord,
                        incorporated.zCoord,
                        terminal.getTagCompound(),
                        player),
                    "Production incorporation transaction registers the real AE cell");
                incorporationIdentity = entry(ctx).identity();
                next(ctx, 4);
            } else if (mode == Mode.MIGRATION) {
                rejectInvalidMigration(ctx);
                TestWorldFixtures.require(
                    ctx,
                    QuantumNetworkMigration.migrate(
                        player,
                        terminal,
                        ctx.world(),
                        TestWorldFixtures.TARGET_X,
                        TestWorldFixtures.Y,
                        TestWorldFixtures.CONTROLLER_Z),
                    "Production migration transaction accepted real active target controller");
                TestWorldFixtures.require(
                    ctx,
                    !registry(ctx).isQuantized(
                        TestWorldFixtures.CONTROLLER_X,
                        TestWorldFixtures.Y,
                        TestWorldFixtures.CONTROLLER_Z),
                    "Migration dequantized source controller");
                TestWorldFixtures.require(
                    ctx,
                    ItemNetworkQuantumTerminal.getAnchor(terminal)[1] == TestWorldFixtures.TARGET_X,
                    "Migration rebound real held terminal");
                next(ctx, 6);
            } else if (mode == Mode.SAFETY) {
                TestWorldFixtures.require(
                    ctx,
                    QuantumTerminalAccess.canChangeNetworkSafety(player, terminal),
                    "Native controller owner can manage safety");
                TestWorldFixtures.require(
                    ctx,
                    !QuantumTerminalAccess.canChangeNetworkSafety(outsider, terminal.copy()),
                    "Unrelated synthetic actor cannot manage controller safety");
                TestWorldFixtures.require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(player, terminal, true),
                    "Owner enables real controller safety policy");
                TestWorldFixtures.require(
                    ctx,
                    QuantumTerminalAccess
                        .isSecurityRequired(ctx.world(), controller.xCoord, controller.yCoord, controller.zCoord),
                    "Controller safety policy is authoritative");
                TestWorldFixtures.require(
                    ctx,
                    !QuantumTerminalAccess.validate(outsider, terminal.copy()),
                    "Unpaired terminal is rejected by actual server authorization path");
                TestWorldFixtures.require(
                    ctx,
                    !QuantumTerminalAccess.setNetworkSafety(outsider, terminal.copy(), false),
                    "Unauthorized actor cannot disable safety");
                TestWorldFixtures.require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(player, terminal, false),
                    "Native owner can disable safety even with an unpaired terminal");
                ctx.event(
                    "coverage",
                    "Native encoded security-station permissions require a separately provisioned station; this case verifies controller ownership and unpaired rejection");
                ctx.pass("Real controller safety authority and unpaired terminal rejection passed");
            } else {
                node = (TileEntityNetworkQuantumNode) TestWorldFixtures.reload(ctx.world(), node);
                TestWorldFixtures.require(
                    ctx,
                    node.hasAnchor() && node.getAnchorX() == TestWorldFixtures.CONTROLLER_X,
                    "Node NBT reload preserves real anchor");
                next(ctx, 2);
            }
            return;
        }
        if (stage == 2 && node.isLinked()) {
            capturePause(ctx);
            clickController(ctx, true);
            TestWorldFixtures.require(
                ctx,
                !registry(ctx).isQuantized(controller.xCoord, controller.yCoord, controller.zCoord),
                "Real Shift terminal click dequantized controller");
            next(ctx, 3);
        } else if (stage == 3 && ctx.tick() - stageTick >= 50) {
            TestWorldFixtures.require(ctx, !node.isLinked(), "Natural node maintenance disconnects dequantized anchor");
            TestWorldFixtures.require(ctx, node.hasAnchor(), "Disconnect retains node anchor for recovery");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_DISCONNECT) > 0
                    && registry(ctx).isSuspendedAnchor(canonicalBeforePause, generationBeforePause),
                "Production hooks observed actual bridge disconnection while the same generation is suspended");
            requirePausedBinding(ctx);
            clickController(ctx, false);
            next(ctx, 10);
        } else if (stage == 10 && node.isLinked()) {
            requireResumedBinding(ctx);
            ctx.pass(
                "Real terminal quantization, node NBT reconnect, pause and same-generation natural bridge recovery passed");
        } else if (stage == 4 && entry(ctx) != null && entry(ctx).isConnected()) {
            TestWorldFixtures.require(
                ctx,
                incorporationIdentity.equals(entry(ctx).identity()),
                "Real incorporation identity is stable");
            if (Config.quantumSafetyEnabled) {
                incorporationRejectsBefore = TestWorldProbes.count(TestWorldProbes.Type.INCORPORATION_REJECT);
                incorporationBindingBeforeReject = incorporations(ctx)
                    .copyBinding(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord);
                TestWorldFixtures.require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(player, terminal, true),
                    "Native owner enables actual local safety before the unpaired incorporation retry");
                incorporationSafetyRaised = true;
                terminalBeforeReject = (NBTTagCompound) terminal.getTagCompound()
                    .copy();
                next(ctx, 8);
            } else {
                ctx.event(
                    "SKIP",
                    "Incorporation authorization rejection requires enabled optional quantum safety; global Config unchanged");
                incorporated = TestWorldFixtures.reload(ctx.world(), incorporated);
                next(ctx, 5);
            }
        } else if (stage == 5 && entry(ctx) != null && entry(ctx).isConnected()) {
            TestWorldFixtures.require(
                ctx,
                incorporationIdentity.equals(entry(ctx).identity()),
                "Tile NBT and natural incorporation lifecycle recover the same registration");
            capturePause(ctx);
            incorporationBindingBeforePause = incorporations(ctx)
                .copyBinding(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord);
            clickController(ctx, true);
            next(ctx, 11);
        } else if (stage == 11 && ctx.tick() - stageTick >= 50) {
            requirePausedBinding(ctx);
            TestWorldFixtures.require(
                ctx,
                !node.isLinked() && entry(ctx) != null
                    && !entry(ctx).isConnected()
                    && incorporationIdentity.equals(entry(ctx).identity())
                    && player.getUniqueID()
                        .toString()
                        .equals(entry(ctx).owner())
                    && incorporationBindingBeforePause.equals(
                        incorporations(ctx)
                            .copyBinding(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord)),
                "Paused incorporation keeps identity, owner and exact binding while both natural bridges are offline");
            clickController(ctx, false);
            next(ctx, 12);
        } else if (stage == 12 && node.isLinked() && entry(ctx) != null && entry(ctx).isConnected()) {
            requireResumedBinding(ctx);
            TestWorldFixtures.require(
                ctx,
                incorporationIdentity.equals(entry(ctx).identity()) && player.getUniqueID()
                    .toString()
                    .equals(entry(ctx).owner())
                    && incorporationBindingBeforePause.equals(
                        incorporations(ctx)
                            .copyBinding(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord)),
                "Same incorporation naturally reconnects after real terminal re-quantization without re-adding it");
            TestWorldFixtures.require(
                ctx,
                !incorporations(ctx)
                    .remove(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord, outsider),
                "Unrelated actor cannot remove incorporation");
            TestWorldFixtures.require(
                ctx,
                incorporations(ctx)
                    .remove(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord, player),
                "Owner can remove actual incorporation");
            TestWorldFixtures.require(
                ctx,
                !QuantumIncorporationRegistry
                    .isIncorporated(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord),
                "Removal clears authoritative registration");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.INCORPORATION_ADD) > 0
                    && TestWorldProbes.count(TestWorldProbes.Type.INCORPORATION_CONNECT) > 0
                    && TestWorldProbes.count(TestWorldProbes.Type.INCORPORATION_REMOVE) > 0,
                "Production hooks observed actual incorporation add/connect/remove");
            ctx.pass(
                "Actual incorporation recovered after NBT reload and same-generation pause/resume, then enforced removal ownership");
        } else if (stage == 6 && node.getAnchorX() == TestWorldFixtures.TARGET_X && node.isLinked()) {
            node = (TileEntityNetworkQuantumNode) TestWorldFixtures.reload(ctx.world(), node);
            TestWorldFixtures
                .require(ctx, node.getAnchorX() == TestWorldFixtures.TARGET_X, "Node NBT persists migrated anchor");
            next(ctx, 7);
        } else if (stage == 7 && node.isLinked()) {
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_MIGRATE) > 0,
                "Production migration hook observed committed real transaction");
            ctx.pass(
                "Real migration transaction rebound terminal/node through durable journal; natural bridge recovered after NBT reload");
        } else if (stage == 8 && entry(ctx) != null
            && !entry(ctx).isConnected()
            && TestWorldProbes.count(TestWorldProbes.Type.INCORPORATION_REJECT) > incorporationRejectsBefore) {
                TestWorldFixtures.require(
                    ctx,
                    incorporationIdentity.equals(entry(ctx).identity()) && player.getUniqueID()
                        .toString()
                        .equals(entry(ctx).owner())
                        && incorporationBindingBeforeReject.equals(
                            incorporations(ctx).copyBinding(
                                ctx.world(),
                                incorporated.xCoord,
                                incorporated.yCoord,
                                incorporated.zCoord)),
                    "Natural authorization rejection preserves incorporation identity, owner and anchor binding");
                TestWorldFixtures.require(
                    ctx,
                    terminalBeforeReject.equals(terminal.getTagCompound())
                        && registry(ctx).isQuantized(controller.xCoord, controller.yCoord, controller.zCoord)
                        && ctx.world()
                            .getTileEntity(incorporated.xCoord, incorporated.yCoord, incorporated.zCoord)
                            == incorporated,
                    "Rejected unpaired bridge leaves terminal binding, source controller and actual device unchanged");
                TestWorldFixtures.require(
                    ctx,
                    QuantumTerminalAccess.setNetworkSafety(player, terminal, false),
                    "Native owner restores local policy after actual incorporation authorization rejection");
                incorporationSafetyRaised = false;
                next(ctx, 9);
            } else if (stage == 9 && entry(ctx) != null && entry(ctx).isConnected()) {
                TestWorldFixtures.require(
                    ctx,
                    incorporationIdentity.equals(entry(ctx).identity()),
                    "Natural retry reconnects the same incorporation after authorized policy recovery");
                incorporated = TestWorldFixtures.reload(ctx.world(), incorporated);
                next(ctx, 5);
            }
        if (ctx.tick() - stageTick > 600) ctx.fail(
            "Natural AE workflow did not reach next state; stage=" + stage + ", nodeReason=" + node.getOfflineReason());
    }

    private void rejectInvalidMigration(TestContext ctx) {
        QuantumTerminalAccess.synchronize(player, terminal);
        NBTTagCompound binding = (NBTTagCompound) terminal.getTagCompound()
            .copy();
        long revision = registry(ctx).getRevision();
        long journalSequence = QuantumMigrationJournal.get(ctx.world())
            .currentSequence();
        long rejectedBefore = TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_MIGRATE_REJECT);
        TestWorldFixtures.require(
            ctx,
            !QuantumNetworkMigration
                .migrate(player, terminal, ctx.world(), controller.xCoord, controller.yCoord, controller.zCoord),
            "Production migration rejects an already quantized controller as its destination");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_MIGRATE_REJECT) > rejectedBefore,
            "Production migration rejection hook observed the actual invalid destination request");
        TestWorldFixtures.require(
            ctx,
            binding.equals(terminal.getTagCompound()) && registry(ctx).getRevision() == revision
                && registry(ctx).isQuantized(controller.xCoord, controller.yCoord, controller.zCoord)
                && !registry(ctx).isQuantized(target.xCoord, target.yCoord, target.zCoord)
                && QuantumMigrationJournal.get(ctx.world())
                    .currentSequence() == journalSequence
                && node.isLinked()
                && node.getAnchorX() == controller.xCoord
                && node.getAnchorY() == controller.yCoord
                && node.getAnchorZ() == controller.zCoord,
            "Rejected migration preserves terminal NBT, controller registrations, journal and live node anchor");
    }

    private static void power(TileEntity tile) {
        if (tile == null || tile.isInvalid()) return;
        AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
        if (!proxy.isReady()) return;
        try {
            proxy.getEnergy()
                .injectPower(200, Actionable.MODULATE);
        } catch (GridAccessException ignored) { /* Ordinary AE boot-up is observed on following natural ticks. */ }
    }

    private void clickController(TestContext ctx, boolean sneak) {
        player.setSneaking(sneak);
        player.inventory.currentItem = 0;
        player.inventory.setInventorySlotContents(0, terminal);
        try {
            TestWorldFixtures.require(
                ctx,
                player.theItemInWorldManager.activateBlockOrUseItem(
                    player,
                    ctx.world(),
                    terminal,
                    TestWorldFixtures.CONTROLLER_X,
                    TestWorldFixtures.Y,
                    TestWorldFixtures.CONTROLLER_Z,
                    ForgeDirection.UP.ordinal(),
                    .5f,
                    .5f,
                    .5f),
                "Real ItemInWorldManager terminal controller gesture handled");
        } finally {
            player.setSneaking(false);
        }
    }

    private void capturePause(TestContext ctx) {
        terminalBeforePause = terminal.copy();
        terminalBindingBeforePause = (NBTTagCompound) terminalBeforePause.getTagCompound()
            .copy();
        QuantumControllerRegistry.CanonicalAnchor anchor = registry(ctx)
            .canonicalAnchor(QuantumControllerRegistry.pack(controller.xCoord, controller.yCoord, controller.zCoord));
        TestWorldFixtures
            .require(ctx, anchor != null, "Live fixture has an authoritative canonical anchor before pause");
        canonicalBeforePause = anchor.position;
        generationBeforePause = anchor.generation;
        nodeOwnerBeforePause = node.getNativeOwnerID();
    }

    private void requirePausedBinding(TestContext ctx) {
        TestWorldFixtures.require(
            ctx,
            !registry(ctx).isQuantized(controller.xCoord, controller.yCoord, controller.zCoord) && ctx.world()
                .getTileEntity(controller.xCoord, controller.yCoord, controller.zCoord) == controller
                && node.hasAnchor()
                && generationBeforePause.equals(node.getAnchorGeneration())
                && node.getNativeOwnerID() == nodeOwnerBeforePause
                && terminalBindingBeforePause.equals(terminalBeforePause.getTagCompound()),
            "Real pause retains controller object, old terminal binding and node generation/owner");
    }

    private void requireResumedBinding(TestContext ctx) {
        QuantumControllerRegistry.CanonicalAnchor anchor = registry(ctx)
            .canonicalAnchor(QuantumControllerRegistry.pack(controller.xCoord, controller.yCoord, controller.zCoord));
        TestWorldFixtures.require(
            ctx,
            anchor != null && anchor.position == canonicalBeforePause
                && generationBeforePause.equals(anchor.generation)
                && ctx.world()
                    .getTileEntity(controller.xCoord, controller.yCoord, controller.zCoord) == controller
                && generationBeforePause.equals(node.getAnchorGeneration())
                && node.getNativeOwnerID() == nodeOwnerBeforePause
                && node.getCoreGridNode()
                    .getGrid()
                    == ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                        .getGrid()
                && terminalBindingBeforePause.equals(terminalBeforePause.getTagCompound())
                && QuantumTerminalAccess.validate(player, terminalBeforePause.copy()),
            "Same controller naturally reconnects the original node and accepts untouched old terminal with same canonical/generation/owner");
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("stage", id() + ":" + stage);
    }

    private QuantumControllerRegistry registry(TestContext ctx) {
        return QuantumControllerRegistry.get(ctx.world());
    }

    private QuantumIncorporationRegistry incorporations(TestContext ctx) {
        return QuantumIncorporationRegistry.get(ctx.world());
    }

    private QuantumIncorporationRegistry.Entry entry(TestContext ctx) {
        return incorporations(ctx).find(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (player == null) return;
        if (incorporationSafetyRaised) {
            TestWorldFixtures.require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(player, terminal, false),
                "Cleanup restores only this fixture's local safety policy after interrupted negative test");
            incorporationSafetyRaised = false;
        }
        if (incorporated != null) incorporations(ctx)
            .remove(ctx.world(), incorporated.xCoord, incorporated.yCoord, incorporated.zCoord, player);
        if (node != null && !node.isInvalid()) node.disconnectFromDequantizedAnchor();
        registry(ctx).dequantize(TestWorldFixtures.CONTROLLER_X, TestWorldFixtures.Y, TestWorldFixtures.CONTROLLER_Z);
        registry(ctx).dequantize(TestWorldFixtures.TARGET_X, TestWorldFixtures.Y, TestWorldFixtures.CONTROLLER_Z);
        player.inventory.clearInventory(null, -1);
        if (outsider != null) outsider.inventory.clearInventory(null, -1);
        player = null;
        outsider = null;
    }
}
