package com.miaokatze.gtswn.common.testworld;

import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;
import com.miaokatze.gtswn.network.PanelActionQueue;

final class PanelStructureScenario implements TestScenario {

    private TileEntityNetworkInfoPanel panel;
    private UUID owner, portableId;
    private PanelOwnerScope scope;
    private FakePlayer queuePlayer;
    private NetHandlerPlayServer originalQueueHandler;
    private double originalQueueX, originalQueueY, originalQueueZ;
    private boolean queueActorCaptured;
    private boolean briefEnergyBeforeQueue;
    private long queuedAt, queuedWorldTick, enqueueBefore, executeBefore;

    @Override
    public String id() {
        return "panel.structure_nbt";
    }

    @Override
    public String description() {
        return "Natural panel action queue, 2x2 assembly, extender ownership, NBT replay and missing-cell rebuild";
    }

    @Override
    public void start(TestContext ctx) {
        queuePlayer = null;
        originalQueueHandler = null;
        queueActorCaptured = false;
        queuedAt = -1;
        owner = TestWorldFixtures.owner(id());
        scope = new PanelOwnerScope(ctx.world(), owner);
        TestWorldProbes.addOwner(owner);
        panel = TestWorldFixtures.panel(ctx.world(), owner);
        panel.setDisplayMode(1);
        panel.setCurrentTab(1);
        panel.setChartItem(new ItemStack(Items.iron_ingot));
        panel.toggleItemMonitor(new ItemStack(Items.gold_ingot));
        portableId = panel.getPortableMonitorId();
    }

    @Override
    public void tick(TestContext ctx) {
        if (ctx.tick() == 1) {
            queuePlayer = TestActors.player(ctx.world(), "owner");
            originalQueueHandler = queuePlayer.playerNetServerHandler;
            originalQueueX = queuePlayer.posX;
            originalQueueY = queuePlayer.posY;
            originalQueueZ = queuePlayer.posZ;
            queueActorCaptured = true;
            // Actual native handler, with no socket, channel, login or online-player registration.
            // Construction only sets the manager listener and the actor's handler reference.
            NetworkManager connection = new NetworkManager(false);
            queuePlayer.playerNetServerHandler = new NetHandlerPlayServer(
                MinecraftServer.getServer(),
                connection,
                queuePlayer);
            queuePlayer.setPosition(panel.xCoord + .5, panel.yCoord, panel.zCoord + 2.5);
            TestWorldFixtures.require(
                ctx,
                queuePlayer.playerNetServerHandler != null
                    && queuePlayer.playerNetServerHandler.playerEntity == queuePlayer
                    && connection.getNetHandler() == queuePlayer.playerNetServerHandler
                    && !connection.isChannelOpen(),
                "Synthetic actor has an actual isolated server handler without a socket for production queue validation");
            ctx.event(
                "fixture",
                "Queue actor uses native NetHandlerPlayServer/NetworkManager only; no client, C2S or login is simulated");
            briefEnergyBeforeQueue = panel.isShowBriefEnergy();
            enqueueBefore = TestWorldProbes.count(TestWorldProbes.Type.QUEUE_ENQUEUE);
            executeBefore = TestWorldProbes.count(TestWorldProbes.Type.QUEUE_EXECUTE);
            queuedAt = ctx.tick();
            queuedWorldTick = ctx.world()
                .getTotalWorldTime();
            // CommonProxy registers the production queue before this runner, so its END drain has already run.
            PanelActionQueue.enqueueConfig(queuePlayer, panel.xCoord, panel.yCoord, panel.zCoord, 0, null);
            TestWorldFixtures.require(
                ctx,
                panel.isShowBriefEnergy() == briefEnergyBeforeQueue,
                "Production enqueue leaves panel configuration unchanged until a natural queue drain");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUEUE_ENQUEUE) == enqueueBefore + 1
                    && TestWorldProbes.count(TestWorldProbes.Type.QUEUE_EXECUTE) == executeBefore,
                "Production queue emitted exactly one enqueue and no synchronous execution");
        }
        if (ctx.tick() == 2) {
            TestWorldFixtures.require(
                ctx,
                queuedAt >= 0 && ctx.tick() > queuedAt
                    && ctx.world()
                        .getTotalWorldTime() > queuedWorldTick
                    && panel.isShowBriefEnergy() != briefEnergyBeforeQueue,
                "Natural ServerTick queue drain applied the independently observable brief-energy toggle");
            TestWorldFixtures.require(
                ctx,
                TestWorldProbes.count(TestWorldProbes.Type.QUEUE_ENQUEUE) == enqueueBefore + 1
                    && TestWorldProbes.count(TestWorldProbes.Type.QUEUE_EXECUTE) == executeBefore + 1,
                "Production queue emitted one execution only after crossing a natural server tick");
        }
        if (ctx.tick() == 5) {
            assertRectangle(ctx, 2, 2);
            TileEntityNetworkInfoPanelExtender extender = extender(
                ctx,
                TestWorldFixtures.PANEL_X + 1,
                TestWorldFixtures.Y + 1);
            TestWorldFixtures.require(
                ctx,
                extender.isPartOfScreen() && extender.getCore() == panel,
                "Natural screen build attached real extender to this core");
            panel = (TileEntityNetworkInfoPanel) TestWorldFixtures.reload(ctx.world(), panel);
            TestWorldFixtures.require(ctx, owner.equals(panel.getOwnerUUID()), "Panel NBT preserves isolated owner");
            TestWorldFixtures.require(
                ctx,
                portableId.equals(panel.getPortableMonitorId()),
                "Panel NBT preserves portable binding identity");
            TestWorldFixtures.require(
                ctx,
                panel.getDisplayMode() == 1 && panel.getCurrentTab() == 1,
                "Panel NBT preserves display mode and AE tab");
            TestWorldFixtures.require(
                ctx,
                panel.isShowBriefEnergy() != briefEnergyBeforeQueue,
                "Panel NBT preserves the configuration applied by the production action queue");
            TestWorldFixtures.require(
                ctx,
                panel.getChartItem() != null && panel.getChartItem()
                    .getItem() == Items.iron_ingot,
                "Panel NBT preserves AE chart selection");
            TestWorldFixtures.require(
                ctx,
                panel.getMonitoredItems()
                    .size() == 1,
                "Panel NBT preserves selected AE monitor");
        }
        if (ctx.tick() == 10) {
            assertRectangle(ctx, 2, 2);
            TestWorldFixtures.require(
                ctx,
                extender(ctx, TestWorldFixtures.PANEL_X + 1, TestWorldFixtures.Y).getCore() == panel,
                "Natural post-reload rebuild attaches extenders to restored core");
            ctx.world()
                .setBlockToAir(TestWorldFixtures.PANEL_X + 1, TestWorldFixtures.Y + 1, TestWorldFixtures.PANEL_Z);
        }
        if (ctx.tick() != 15) return;
        NetworkScreen screen = panel.getScreen();
        TestWorldFixtures.require(
            ctx,
            screen != null && screen.getWidth() * screen.getHeight() == 2,
            "Missing real screen cell shrinks maximal filled rectangle; air is excluded");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.PANEL_REBUILD) > 0,
            "Production screen hook observed actual structure rebuilding");
        ctx.pass("Natural screen assembly/removal and tile NBT assertions passed; render/GUI remain manual");
    }

    private void assertRectangle(TestContext ctx, int width, int height) {
        NetworkScreen screen = panel.getScreen();
        TestWorldFixtures.require(
            ctx,
            screen != null && screen.getWidth() == width && screen.getHeight() == height,
            "Real screen rectangle is " + width + "x" + height);
    }

    private TileEntityNetworkInfoPanelExtender extender(TestContext ctx, int x, int y) {
        return (TileEntityNetworkInfoPanelExtender) ctx.world()
            .getTileEntity(x, y, TestWorldFixtures.PANEL_Z);
    }

    @Override
    public void cleanup(TestContext ctx) {
        // Pending work from an early stop must fail the production distance check, even after panel replacement.
        if (queuePlayer != null) {
            queuePlayer.setPosition(.5, TestWorldFixtures.Y, .5);
            if (queueActorCaptured) {
                queuePlayer.playerNetServerHandler = originalQueueHandler;
                // TestActors.player resets the captured position to the platform entrance, outside panel range.
                queuePlayer.setPosition(originalQueueX, originalQueueY, originalQueueZ);
            }
        }
        try {
            TestWorldFixtures.panel(ctx.world(), null);
        } finally {
            try {
                if (scope != null) scope.close();
            } finally {
                scope = null;
                queuePlayer = null;
                originalQueueHandler = null;
                queueActorCaptured = false;
            }
        }
    }
}
