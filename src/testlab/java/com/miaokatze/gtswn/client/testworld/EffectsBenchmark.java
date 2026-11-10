package com.miaokatze.gtswn.client.testworld;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.Teleporter;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import com.google.gson.GsonBuilder;
import com.miaokatze.gtswn.client.LinkNodeVisualHandler;
import com.miaokatze.gtswn.client.QuantumIncorporationClientState;
import com.miaokatze.gtswn.client.QuantumParticleDirections;
import com.miaokatze.gtswn.client.QuantumVoxelParticleHandler;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.testworld.TestDimension;
import com.miaokatze.gtswn.common.testworld.TestWorldFixtures;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.me.GridAccessException;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Explicit isolated-save real-GL benchmark. Never registered without both the debug gate and JVM opt-in. */
public final class EffectsBenchmark {

    private static final String[] SCENES = { "empty", "heavy_dynamic", "heavy_fixed" };
    private final Minecraft mc = Minecraft.getMinecraft();
    private final String label = System.getProperty("gtswn.effectsBenchmark");
    private final ThreadMXBean cpu = ManagementFactory.getThreadMXBean();
    private final List<String> frames = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> serverTicks = new ConcurrentLinkedQueue<>();
    private final List<TileEntityNetworkQuantumNode> nodes = new ArrayList<>();
    private final List<TileEntity> incorporated = new ArrayList<>();
    private final List<Map<String, Object>> windows = new ArrayList<>();
    private final File output;
    private final long warmupNanos = Long.getLong("gtswn.effectsWarmupSeconds", 20L) * 1000000000L;
    private final long sampleNanos = Long.getLong("gtswn.effectsSampleSeconds", 15L) * 1000000000L;
    private final int rounds = Integer.getInteger("gtswn.effectsRounds", 3);
    private final long launched = System.nanoTime();
    private volatile boolean prepared, requestedHeavy, serverHeavy, sampling, finished;
    private volatile String failure;
    private volatile int windowIndex = -1;
    private volatile long serverStarted, serverCpuStarted, serverPrevious;
    private boolean worldStarted, wrappersInstalled, fixedPrepared, samplingBeforeRender, captureRequested;
    private int serverStage, buildCursor, framesInWindow;
    private TileEntity controller;
    private ForgeChunkManager.Ticket constructionTicket;
    private FakePlayer owner;
    private ItemStack terminal;
    private long phaseStarted, frameStarted, frameCpu, previousFrame, previousFrameCpu, linkWall, quantumWall, linkCpu,
        quantumCpu;
    private long lastClientTickWall, lastClientTickCpu, clientTickIndex, linkAllocated, quantumAllocated;
    private int tickLinkParticles, tickQuantumParticles;
    private long clientTickStarted, clientTickCpu;
    private long frameNativeSwapWallNs = -1;

    private EffectsBenchmark() {
        output = new File(mc.mcDataDir, "gtswn-effects-benchmark/" + label.replaceAll("[^a-zA-Z0-9_.-]", "_"));
        if (output.exists()) throw new IllegalStateException("Benchmark output already exists: " + output);
        if (!output.mkdirs()) throw new IllegalStateException("Cannot create " + output);
        if (cpu.isThreadCpuTimeSupported() && !cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        if (cpu instanceof com.sun.management.ThreadMXBean) {
            com.sun.management.ThreadMXBean allocated = (com.sun.management.ThreadMXBean) cpu;
            if (allocated.isThreadAllocatedMemorySupported() && !allocated.isThreadAllocatedMemoryEnabled())
                allocated.setThreadAllocatedMemoryEnabled(true);
        }
    }

    public static void registerIfRequested() {
        if (!DebugTestGate.isEnabled() || System.getProperty("gtswn.effectsBenchmark") == null) return;
        EffectsBenchmark benchmark = new EffectsBenchmark();
        FMLCommonHandler.instance()
            .bus()
            .register(benchmark);
        MinecraftForge.EVENT_BUS.register(benchmark);
    }

    private long cpuTime() {
        return cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : -1;
    }

    @SubscribeEvent
    public void client(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        try {
            if (failure != null) throw new IllegalStateException(failure);
            if (System.nanoTime() - launched > 900000000000L) throw new IllegalStateException("900 second timeout");
            if (!worldStarted && mc.currentScreen != null
                && (mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu || mc.currentScreen.getClass()
                    .getName()
                    .equals("lumien.custommainmenu.gui.GuiCustom"))) {
                worldStarted = true;
                if (new File(mc.mcDataDir, "saves/gtswn-effects-" + label).exists())
                    throw new IllegalStateException("Benchmark save already exists; choose a new label");
                mc.gameSettings.pauseOnLostFocus = false;
                mc.gameSettings.limitFramerate = 260;
                mc.gameSettings.enableVsync = false;
                org.lwjgl.opengl.Display.setVSyncEnabled(false);
                mc.gameSettings.renderDistanceChunks = 6;
                mc.gameSettings.fancyGraphics = true;
                mc.gameSettings.particleSetting = 0;
                mc.gameSettings.hideGUI = true;
                mc.gameSettings.thirdPersonView = 0;
                mc.gameSettings.fovSetting = 70;
                Config.tapMaterial = "high";
                Config.tapParticlesEnabled = Config.quantumParticlesEnabled = true;
                Config.tapParticleDensityPercent = Config.quantumParticleDensityPercent = 100;
                Config.tapRenderDistance = Config.quantumRenderDistance = 64;
                mc.launchIntegratedServer(
                    "gtswn-effects-" + label,
                    "GTSWN effects benchmark " + label,
                    new WorldSettings(81470219L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT)
                        .enableCommands());
                return;
            }
            if (!prepared || mc.theWorld == null || !TestDimension.isTest(mc.theWorld)) return;
            clientTickIndex++;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = requestedHeavy ? 0 : 180;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0;
            mc.gameSettings.pauseOnLostFocus = false;
            mc.inGameHasFocus = false;
            mc.mouseHelper.ungrabMouseCursor();
            if (!wrappersInstalled) {
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(LinkNodeVisualHandler.INSTANCE);
                FMLCommonHandler.instance()
                    .bus()
                    .unregister(QuantumVoxelParticleHandler.INSTANCE);
                MinecraftForge.EVENT_BUS.unregister(LinkNodeVisualHandler.INSTANCE);
                MinecraftForge.EVENT_BUS.unregister(QuantumVoxelParticleHandler.INSTANCE);
                wrappersInstalled = true;
                nextWindow();
            }
            boolean fixed = windowIndex >= 0 && SCENES[windowIndex % SCENES.length].equals("heavy_fixed")
                && fixedPrepared;
            if (!fixed) {
                long start = System.nanoTime(), cpuStart = cpuTime();
                LinkNodeVisualHandler.INSTANCE.tick(event);
                QuantumVoxelParticleHandler.INSTANCE.tick(event);
                lastClientTickWall = System.nanoTime() - start;
                lastClientTickCpu = difference(cpuTime(), cpuStart);
            } else {
                lastClientTickWall = lastClientTickCpu = 0;
            }
            tickLinkParticles = size(LinkNodeVisualHandler.INSTANCE, "particles");
            tickQuantumParticles = size(QuantumVoxelParticleHandler.INSTANCE, "particles");
            if (windowIndex >= 0 && serverHeavy == requestedHeavy) {
                long elapsed = System.nanoTime() - phaseStarted;
                if (!sampling && elapsed >= warmupNanos) {
                    if (requestedHeavy) requireClientScene();
                    if (SCENES[windowIndex % SCENES.length].equals("heavy_fixed")) {
                        fixedParticles();
                        fixedPrepared = true;
                    }
                    framesInWindow = 0;
                    previousFrame = 0;
                    previousFrameCpu = -1;
                    phaseStarted = System.nanoTime();
                    sampling = true;
                } else if (sampling && elapsed >= sampleNanos) {
                    sampling = false;
                    Map<String, Object> result = sceneMetadata();
                    result.put("window", windowIndex);
                    result.put("scene", SCENES[windowIndex % SCENES.length]);
                    result.put("frameSamples", framesInWindow);
                    result.put("sampleWallNanos", elapsed);
                    windows.add(result);
                    captureRequested = true;
                }
            }
        } catch (Throwable error) {
            failure = error.toString();
            GTSimpleWirelessNetwork.LOG.error("Effects benchmark failed", error);
            finish();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void clientTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || finished) return;
        clientTickStarted = System.nanoTime();
        clientTickCpu = cpuTime();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void renderStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        samplingBeforeRender = sampling && !finished;
        frameStarted = System.nanoTime();
        frameCpu = cpuTime();
        Object nativeSwap = System.getProperties()
            .get("gtswn.hiddenSwapLastNs");
        frameNativeSwapWallNs = nativeSwap instanceof AtomicLong ? ((AtomicLong) nativeSwap).get() : -1;
        linkWall = quantumWall = linkCpu = quantumCpu = 0;
        if ((samplingBeforeRender || captureRequested) && fixedPrepared && mc.theWorld != null) {
            // Deterministic repeating 60-frame animation schedule; same draw workload in both variants.
            mc.theWorld.func_82738_a(captureRequested ? 6000L : 6000L + framesInWindow / 3 % 20);
        }
    }

    @SubscribeEvent
    public void effects(RenderWorldLastEvent event) {
        if (!wrappersInstalled || finished) return;
        if (captureRequested) {
            try {
                invalidateCaptureTextureFrames();
            } catch (Exception error) {
                failure = error.toString();
                GTSimpleWirelessNetwork.LOG.error("Cannot reset capture texture phase", error);
                finish();
                return;
            }
        }
        RenderWorldLastEvent sample = fixedPrepared && (sampling || captureRequested)
            ? new RenderWorldLastEvent(mc.renderGlobal, captureRequested ? .5F : (framesInWindow % 3) / 3F)
            : event;
        int primitiveQuery = captureRequested ? GL15.glGenQueries() : 0;
        if (captureRequested) GL15.glBeginQuery(0x8C87, primitiveQuery); // GL_PRIMITIVES_GENERATED, GL 3.0+
        long start = System.nanoTime(), cpuStart = cpuTime(), allocationStart = allocatedBytes();
        LinkNodeVisualHandler.INSTANCE.render(sample);
        linkWall = System.nanoTime() - start;
        linkCpu = difference(cpuTime(), cpuStart);
        linkAllocated = difference(allocatedBytes(), allocationStart);
        if (captureRequested) {
            GL15.glEndQuery(0x8C87);
            Map<String, Object> result = windows.get(windows.size() - 1);
            result.put("linkSubmittedPrimitives", GL15.glGetQueryObjecti(primitiveQuery, GL15.GL_QUERY_RESULT));
            result.put("eligibleHighCoverSources", eligibleHighSources(sample.partialTicks));
            GL15.glBeginQuery(0x8C87, primitiveQuery);
        }
        start = System.nanoTime();
        cpuStart = cpuTime();
        allocationStart = allocatedBytes();
        QuantumVoxelParticleHandler.INSTANCE.render(sample);
        quantumWall = System.nanoTime() - start;
        quantumCpu = difference(cpuTime(), cpuStart);
        quantumAllocated = difference(allocatedBytes(), allocationStart);
        if (captureRequested) {
            GL15.glEndQuery(0x8C87);
            windows.get(windows.size() - 1)
                .put("quantumSubmittedPrimitives", GL15.glGetQueryObjecti(primitiveQuery, GL15.GL_QUERY_RESULT));
            GL15.glDeleteQueries(primitiveQuery);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void renderEnd(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || failure != null) return;
        if (captureRequested) {
            // Readback/encoding are outside the sampling window; fixed screenshots always use phase 6000.5.
            ScreenShotHelper.saveScreenshot(
                mc.mcDataDir,
                "effects-" + label + "-" + windowIndex + ".png",
                mc.displayWidth,
                mc.displayHeight,
                mc.getFramebuffer());
            captureRequested = false;
            nextWindow();
            return;
        }
        if (!samplingBeforeRender || !sampling) return;
        long now = System.nanoTime(), endCpu = cpuTime();
        long interval = previousFrame == 0 ? -1 : frameStarted - previousFrame;
        frames.add(
            windowIndex + ","
                + SCENES[windowIndex % SCENES.length]
                + ","
                + framesInWindow
                + ","
                + interval
                + ","
                + (now - frameStarted)
                + ","
                + difference(endCpu, frameCpu)
                + ","
                + linkWall
                + ","
                + linkCpu
                + ","
                + quantumWall
                + ","
                + quantumCpu
                + ","
                + tickLinkParticles
                + ","
                + tickQuantumParticles
                + ","
                + lastClientTickWall
                + ","
                + lastClientTickCpu
                + ","
                + (System.nanoTime() - clientTickStarted)
                + ","
                + difference(cpuTime(), clientTickCpu)
                + ","
                + clientTickIndex
                + ","
                + linkAllocated
                + ","
                + quantumAllocated
                + ","
                + difference(frameCpu, previousFrameCpu)
                + ","
                + frameNativeSwapWallNs);
        previousFrame = frameStarted;
        previousFrameCpu = frameCpu;
        framesInWindow++;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void serverStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || finished) return;
        serverStarted = System.nanoTime();
        serverCpuStarted = cpuTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void server(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished || failure != null) return;
        long end = System.nanoTime(), endCpu = cpuTime();
        if (sampling) serverTicks.add(
            windowIndex + ","
                + SCENES[windowIndex % SCENES.length]
                + ","
                + serverStarted
                + ","
                + (end - serverStarted)
                + ","
                + difference(endCpu, serverCpuStarted)
                + ","
                + (serverPrevious == 0 ? -1 : serverStarted - serverPrevious));
        serverPrevious = serverStarted;
        try {
            prepareServer();
        } catch (Throwable error) {
            failure = error.toString();
            GTSimpleWirelessNetwork.LOG.error("Effects benchmark scene failed", error);
        }
    }

    private void prepareServer() throws Exception {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || mc.thePlayer == null) return;
        WorldServer world = TestDimension.ensureWorld();
        EntityPlayerMP player = server.getConfigurationManager()
            .func_152612_a(mc.thePlayer.getCommandSenderName());
        if (player == null) return;
        if (serverStage > 0 && world.theChunkProviderServer.chunkExists(-2, 1)) {
            controller = world.getTileEntity(-28, 65, 20);
        }
        if (controller != null && !controller.isInvalid()) {
            try {
                ((IGridProxyable) controller).getProxy()
                    .getEnergy()
                    .injectPower(100000, Actionable.MODULATE);
            } catch (GridAccessException ignored) { /* natural AE boot-up */ }
        }
        if (serverStage == 0) {
            // Build while nobody watches these chunks: no intermediate AE placement/description packets can bind
            // incorporation state to a transient client TE. Only these three authored chunks are held temporarily.
            position(player, world, 256.5, 0);
            constructionTicket = ForgeChunkManager
                .requestTicket(GTSimpleWirelessNetwork.instance, world, ForgeChunkManager.Type.NORMAL);
            if (constructionTicket == null) throw new IllegalStateException("Construction chunk ticket refused");
            constructionTicket.getModData()
                .setBoolean("GTSWNTestRun", true);
            for (ChunkCoordIntPair chunk : new ChunkCoordIntPair[] { new ChunkCoordIntPair(-2, 1),
                new ChunkCoordIntPair(-1, 0), new ChunkCoordIntPair(0, 0) }) {
                ForgeChunkManager.forceChunk(constructionTicket, chunk);
                world.theChunkProviderServer.loadChunk(chunk.chunkXPos, chunk.chunkZPos);
            }
            world.theChunkProviderServer.loadChunk(-2, 1);
            owner = TestWorldFixtures.player(world, TestWorldFixtures.owner("effects-benchmark"));
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            controller = TestWorldFixtures.aeOwnedBlock(
                world,
                -28,
                65,
                20,
                AEApi.instance()
                    .definitions()
                    .blocks()
                    .controller()
                    .maybeStack(1)
                    .get(),
                owner);
            if (!(controller instanceof TileController))
                throw new IllegalStateException("Real controller placement failed: " + controller);
            serverStage = 1;
        } else if (serverStage == 1) {
            if (!ItemNetworkQuantumTerminal.isControllerActive((TileController) controller)) return;
            ((ItemNetworkQuantumTerminal) terminal.getItem())
                .onItemUseFirst(terminal, owner, world, -28, 65, 20, 1, .5F, .5F, .5F);
            if (!ItemNetworkQuantumTerminal.isBound(terminal))
                throw new IllegalStateException("Controller bind failed");
            serverStage = 2;
        } else if (serverStage == 2) {
            // Four actual placements per natural tick; never a large synchronous reset scan.
            for (int budget = 0; budget < 4 && buildCursor < 256; budget++, buildCursor++) {
                int index = buildCursor;
                if (index < 128) {
                    int x = -15 + index % 16 * 2, y = 65 + index / 16 * 2;
                    world.theChunkProviderServer.loadChunk(x >> 4, 0);
                    BaseMetaTileEntity tile = TestWorldFixtures
                        .machine(world, x, y, 0, ItemList.Hatch_Energy_LV.get(1), owner.getUniqueID());
                    tile.attachCover(
                        CoverRegistry
                            .buildCover(GTSWNItemList.GTswn_Cover_Energy_Wireless.get(1), ForgeDirection.NORTH, tile));
                } else if (index < 192) {
                    int local = index - 128, x = -14 + local % 8 * 4, y = 65 + local / 8 * 2;
                    world.theChunkProviderServer.loadChunk(x >> 4, 0);
                    TestWorldFixtures.place(world, x, y, 5, BlockRegistrar.networkQuantumNode, 0);
                    TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) world.getTileEntity(x, y, 5);
                    ItemNetworkQuantumTerminal.applyAnchorToNode(terminal, node, owner);
                    nodes.add(node);
                } else {
                    int local = index - 192, x = -14 + local % 8 * 4, y = 65 + local / 8 * 2;
                    world.theChunkProviderServer.loadChunk(x >> 4, 0);
                    TileEntity tile = TestWorldFixtures.aeOwnedBlock(
                        world,
                        x,
                        y,
                        10,
                        AEApi.instance()
                            .definitions()
                            .blocks()
                            .iface()
                            .maybeStack(1)
                            .get(),
                        owner);
                    if (!QuantumIncorporationRegistry.get(world)
                        .add(world, x, y, 10, terminal.getTagCompound(), owner))
                        throw new IllegalStateException("Incorporation failed");
                    incorporated.add(tile);
                }
            }
            if (buildCursor == 256) serverStage = 3;
        } else if (serverStage == 3) {
            int linked = 0;
            for (TileEntityNetworkQuantumNode node : nodes) if (node.isLinked()) linked++;
            if (linked != 64) return;
            int connected = 0;
            for (QuantumIncorporationRegistry.Entry entry : QuantumIncorporationRegistry.get(world)
                .snapshot()) if (entry.isConnected()) connected++;
            if (connected != 64) return;
            transfer(player, world, false);
            serverHeavy = false;
            prepared = true;
            serverStage = 4;
        } else if (serverStage == 4) {
            // PlayerManager has now observed the one initial move into the completed scene.
            if (constructionTicket != null) {
                ForgeChunkManager.releaseTicket(constructionTicket);
                constructionTicket = null;
            }
            if (serverHeavy != requestedHeavy) {
                transfer(player, world, requestedHeavy);
                serverHeavy = requestedHeavy;
            }
        }
    }

    private static void transfer(EntityPlayerMP player, WorldServer world, boolean heavy) {
        position(player, world, .5, heavy ? 0 : 180);
    }

    private static void position(EntityPlayerMP player, WorldServer world, final double x, final float yaw) {
        final double y = 72, z = -24;
        world.theChunkProviderServer.loadChunk(((int) x) >> 4, ((int) z) >> 4);
        if (player.dimension != world.provider.dimensionId) MinecraftServer.getServer()
            .getConfigurationManager()
            .transferPlayerToDimension(player, world.provider.dimensionId, new Teleporter(world) {

                @Override
                public void placeInPortal(Entity entity, double px, double py, double pz, float rotation) {
                    entity.setLocationAndAngles(x, y, z, yaw, 0);
                }

                @Override
                public boolean placeInExistingPortal(Entity entity, double px, double py, double pz, float rotation) {
                    return true;
                }

                @Override
                public boolean makePortal(Entity entity) {
                    return false;
                }
            });
        player.capabilities.allowFlying = player.capabilities.isFlying = true;
        player.sendPlayerAbilities();
        player.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, 0);
        world.getPlayerManager()
            .updatePlayerPertinentChunks(player);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (!event.world.isRemote && constructionTicket != null) {
            ForgeChunkManager.releaseTicket(constructionTicket);
            constructionTicket = null;
        }
    }

    private void nextWindow() {
        windowIndex++;
        if (windowIndex >= rounds * SCENES.length) {
            finish();
            return;
        }
        requestedHeavy = !SCENES[windowIndex % SCENES.length].equals("empty");
        fixedPrepared = false;
        phaseStarted = System.nanoTime();
        GTSimpleWirelessNetwork.LOG
            .info("[Effects benchmark] window={} scene={} warmup", windowIndex, SCENES[windowIndex % SCENES.length]);
    }

    private void requireClientScene() throws Exception {
        Map<String, Object> counts = sceneMetadata();
        if (((Number) counts.get("linkedClientNodes")).intValue() != 64
            || ((Number) counts.get("incorporatedClientTiles")).intValue() != 64
            || size(LinkNodeVisualHandler.INSTANCE, "visible") != 128)
            throw new IllegalStateException("Client scene not fully synchronized: " + counts);
    }

    private int eligibleHighSources(float partial) {
        if (!"high".equals(Config.tapMaterial)) return 0;
        try {
            Entity camera = mc.renderViewEntity;
            double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partial;
            double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partial;
            double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partial;
            Frustrum frustum = new Frustrum();
            frustum.setPosition(x, y, z);
            int count = 0;
            for (Object source : (Collection<?>) field(LinkNodeVisualHandler.INSTANCE, "visible")) {
                TileEntity host = (TileEntity) field(source, "host");
                Method valid = source.getClass()
                    .getDeclaredMethod("valid");
                valid.setAccessible(true);
                Method front = LinkNodeVisualHandler.class.getDeclaredMethod("front", source.getClass(), Entity.class);
                front.setAccessible(true);
                Method inView = LinkNodeVisualHandler.class
                    .getDeclaredMethod("inView", source.getClass(), Frustrum.class);
                inView.setAccessible(true);
                if (Boolean.TRUE.equals(valid.invoke(source))
                    && host.getDistanceFrom(x, y, z) <= (double) Config.tapRenderDistance * Config.tapRenderDistance
                    && Boolean.TRUE.equals(front.invoke(LinkNodeVisualHandler.INSTANCE, source, camera))
                    && Boolean.TRUE.equals(inView.invoke(null, source, frustum))) count++;
            }
            return count;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot audit actual eligible High sources", error);
        }
    }

    @SuppressWarnings("unchecked")
    private void fixedParticles() throws Exception {
        mc.theWorld.rand.setSeed(48157029L);
        List<Object> visible = (List<Object>) field(LinkNodeVisualHandler.INSTANCE, "visible");
        visible.sort(Comparator.comparingInt(source -> {
            try {
                TileEntity tile = (TileEntity) field(source, "host");
                return tile.yCoord * 128 + tile.xCoord;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }));
        List<Object> linkParticles = (List<Object>) field(LinkNodeVisualHandler.INSTANCE, "particles");
        linkParticles.clear();
        Class<?> linkParticle = Class.forName("com.miaokatze.gtswn.client.LinkNodeVisualHandler$Particle");
        Constructor<?> makeLink = linkParticle.getDeclaredConstructor(
            LinkNodeVisualHandler.class,
            visible.get(0)
                .getClass());
        makeLink.setAccessible(true);
        for (int i = 0; i < 192; i++) {
            Object particle = makeLink.newInstance(LinkNodeVisualHandler.INSTANCE, visible.get(i % visible.size()));
            setField(particle, "age", 8 + i % 8);
            linkParticles.add(particle);
        }
        List<TileEntity> sources = new ArrayList<>();
        for (Object object : mc.theWorld.loadedTileEntityList) {
            if (object instanceof TileEntityNetworkQuantumNode) sources.add((TileEntity) object);
        }
        for (TileEntity tile : QuantumIncorporationClientState.tiles(mc.theWorld)) sources.add(tile);
        sources.sort(Comparator.comparingInt(tile -> tile.zCoord * 65536 + tile.yCoord * 128 + tile.xCoord));
        List<Object> quantumParticles = (List<Object>) field(QuantumVoxelParticleHandler.INSTANCE, "particles");
        quantumParticles.clear();
        Constructor<?> makeQuantum = Class.forName("com.miaokatze.gtswn.client.QuantumVoxelParticleHandler$Particle")
            .getDeclaredConstructor(
                net.minecraft.world.World.class,
                TileEntity.class,
                QuantumParticleDirections.Direction.class,
                boolean.class);
        makeQuantum.setAccessible(true);
        for (int i = 0; i < 512; i++) {
            TileEntity tile = sources.get(i % sources.size());
            Object particle = makeQuantum.newInstance(
                mc.theWorld,
                tile,
                QuantumParticleDirections.openFaces(63)
                    .get(i % 6),
                tile instanceof TileEntityNetworkQuantumNode);
            setField(particle, "age", 8 + i % 8);
            int age = 8 + i % 8;
            for (String axis : new String[] { "X", "Y", "Z" }) {
                String coordinate = axis.toLowerCase(java.util.Locale.ROOT);
                double initial = ((Number) field(particle, coordinate)).doubleValue();
                double velocity = ((Number) field(particle, "v" + coordinate)).doubleValue();
                setField(particle, coordinate, initial + velocity * age);
                setField(particle, "previous" + axis, initial + velocity * (age - 1));
            }
            quantumParticles.add(particle);
        }
        tickLinkParticles = 192;
        tickQuantumParticles = 512;
    }

    private Map<String, Object> sceneMetadata() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        int linked = 0, incorporatedCount = 0;
        for (Object object : mc.theWorld.loadedTileEntityList) {
            if (object instanceof TileEntityNetworkQuantumNode
                && ((TileEntityNetworkQuantumNode) object).isLinkedClient()) linked++;
        }
        for (TileEntity ignored : QuantumIncorporationClientState.tiles(mc.theWorld)) incorporatedCount++;
        map.put("loadedClientTileEntities", mc.theWorld.loadedTileEntityList.size());
        map.put("linkedClientNodes", linked);
        map.put("incorporatedClientTiles", incorporatedCount);
        Field chunksField = QuantumIncorporationClientState.class.getDeclaredField("CHUNKS");
        chunksField.setAccessible(true);
        int cacheEntries = 0, invalidStates = 0, unboundStates = 0, actualIncorporationTiles = 0;
        for (Object chunk : ((Map<?, ?>) chunksField.get(null)).values()) {
            for (Object state : ((Map<?, ?>) chunk).values()) {
                cacheEntries++;
                if (Boolean.TRUE.equals(field(state, "invalid"))) invalidStates++;
                if (field(state, "bound") == null) unboundStates++;
            }
        }
        for (int index = 0; index < 64; index++) {
            if (mc.theWorld.getTileEntity(-14 + index % 8 * 4, 65 + index / 8 * 2, 10) != null)
                actualIncorporationTiles++;
        }
        map.put("incorporationCacheEntries", cacheEntries);
        map.put("incorporationInvalidStates", invalidStates);
        map.put("incorporationUnboundStates", unboundStates);
        map.put("actualClientIncorporationTiles", actualIncorporationTiles);
        map.put("visibleCoverSources", size(LinkNodeVisualHandler.INSTANCE, "visible"));
        map.put("linkParticles", size(LinkNodeVisualHandler.INSTANCE, "particles"));
        map.put("quantumParticles", size(QuantumVoxelParticleHandler.INSTANCE, "particles"));
        map.put("packedPath", packedStatistics());
        map.put(
            "camera",
            new double[] { mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.rotationYaw,
                mc.thePlayer.rotationPitch });
        return map;
    }

    private static void invalidateCaptureTextureFrames() throws Exception {
        for (Object[] material : (Object[][]) field(LinkNodeVisualHandler.INSTANCE, "textures")) {
            for (Object texture : material) {
                if (texture != null) setField(texture, "frame", Long.MIN_VALUE);
            }
        }
        for (Object texture : ((Map<?, ?>) field(LinkNodeVisualHandler.INSTANCE, "voltageTextures")).values()) {
            if (texture != null) setField(texture, "frame", Long.MIN_VALUE);
        }
    }

    private static Map<String, Object> packedStatistics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "legacy");
        result.put("acceptedPasses", 0L);
        result.put("submittedBatches", 0L);
        result.put("submittedVertices", 0L);
        result.put("retainedBytes", 0L);
        result.put("lastRejection", "legacy");
        try {
            Object packed = field(LinkNodeVisualHandler.INSTANCE, "packed");
            result.put("status", "no-accepted-passes");
            result.put("lastRejection", "not-initialized");
            if (packed == null) return result;
            for (String name : new String[] { "acceptedPasses", "submittedBatches", "submittedVertices",
                "retainedBytes" }) {
                Method getter = packed.getClass()
                    .getMethod(name);
                getter.setAccessible(true);
                result.put(name, ((Number) getter.invoke(packed)).longValue());
            }
            Method rejection = packed.getClass()
                .getMethod("lastRejection");
            rejection.setAccessible(true);
            result.put("lastRejection", String.valueOf(rejection.invoke(packed)));
            if (((Number) result.get("acceptedPasses")).longValue() > 0) result.put("status", "accepted");
        } catch (NoSuchFieldException legacy) {
            // The frozen production baseline has no packed stream field.
        } catch (Exception error) {
            result.put("status", "unavailable");
            result.put("lastRejection", error.toString());
        }
        return result;
    }

    private void finish() {
        if (finished) return;
        sampling = false;
        finished = true;
        try {
            Files.write(
                new File(output, "frames.csv").toPath(),
                ("window,scene,frame,frameIntervalNs,renderWindowWallNs,renderWindowCpuNs,linkRenderWallNs,linkRenderCpuNs,quantumRenderWallNs,quantumRenderCpuNs,linkParticles,quantumParticles,effectTickWallNs,effectTickCpuNs,clientTickToRenderEndWallNs,clientTickToRenderEndCpuNs,clientTick,linkRenderAllocatedBytes,quantumRenderAllocatedBytes,mainThreadFrameCpuNs,nativeSwapWallNs\n"
                    + String.join("\n", frames)
                    + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(
                new File(output, "server-ticks.csv").toPath(),
                ("window,scene,startNs,serverTickWallNs,serverTickCpuNs,startIntervalNs\n"
                    + String.join("\n", serverTicks)
                    + "\n").getBytes(StandardCharsets.UTF_8));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", failure == null ? "COMPLETED" : "FAILED");
            report.put("failure", failure);
            report.put("label", label);
            report.put("runId", label);
            report.put("taskId", "jvm.effects_benchmark");
            report.put("lane", "jvm");
            report.put("artifact", com.miaokatze.gtswn.common.testworld.TestLabIdentity.ARTIFACT);
            report.put("java", System.getProperty("java.version"));
            report.put(
                "jvmArguments",
                ManagementFactory.getRuntimeMXBean()
                    .getInputArguments());
            report.put(
                "heapMaxBytes",
                Runtime.getRuntime()
                    .maxMemory());
            report.put("glVendor", GL11.glGetString(GL11.GL_VENDOR));
            report.put("glRenderer", GL11.glGetString(GL11.GL_RENDERER));
            report.put("glVersion", GL11.glGetString(GL11.GL_VERSION));
            report.put("swapIntervalObservation", swapIntervalObservation());
            report.put("displayWidth", mc.displayWidth);
            report.put("displayHeight", mc.displayHeight);
            report.put("warmupSeconds", warmupNanos / 1000000000L);
            report.put("sampleSeconds", sampleNanos / 1000000000L);
            report.put("rounds", rounds);
            report.put(
                "nativeSwapWallNsScope",
                "nativeSwapWallNs is read at RenderTick START from the bootstrap shared last completed actual SDL_GL_SwapWindow timer. It belongs to the previous frame's present between adjacent START events, not the current START-to-END render window. Missing observer data is -1; the first sample in each window has no preceding sampled START and should not be paired with frameIntervalNs. The native call, return value and frame pacing are unchanged.");
            report.put(
                "packedPathScope",
                "Each window snapshots cumulative acceptedPasses/submittedBatches/submittedVertices, current retainedBytes and lastRejection before screenshot capture. Legacy baseline fields are absent and reported as legacy/0. A candidate with no accepted passes is explicitly no-accepted-passes; fallback results do not establish packed-path performance.");
            String packedPathStatus = "legacy";
            for (Map<String, Object> window : windows) {
                Map<?, ?> packed = (Map<?, ?>) window.get("packedPath");
                if (packed != null && "accepted".equals(packed.get("status"))) {
                    packedPathStatus = "accepted";
                    break;
                }
                if (packed != null && !"legacy".equals(packed.get("status"))) packedPathStatus = "no-accepted-passes";
            }
            report.put("packedPathStatus", packedPathStatus);
            report.put(
                "controlSceneScope",
                "empty means an empty view from the same observation position, yaw 180; heavy uses yaw 0. All measured windows keep the same chunks, physical fixtures and server load. This is not a physically empty world. Construction is completed while the player is far away, with three temporary authored chunk tickets; only one initial move into the completed scene occurs, then subsequent windows rotate without unloading tiles.");
            report.put("initialServerLinkedNodes", prepared ? 64 : 0);
            report.put("initialServerConnectedIncorporations", prepared ? 64 : 0);
            report.put(
                "incorporationFixture",
                "64 real full-block ME Interfaces. Energy cells were rejected as benchmark fixtures: charging metadata updates replace their client TE, which the production incorporation identity guard deliberately refuses to rebind. Production cache semantics are unchanged.");
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("renderDistanceChunks", mc.gameSettings.renderDistanceChunks);
            settings.put("particleSetting", mc.gameSettings.particleSetting);
            settings.put("enableVsync", mc.gameSettings.enableVsync);
            settings.put("limitFramerate", mc.gameSettings.limitFramerate);
            settings.put("fancyGraphics", mc.gameSettings.fancyGraphics);
            settings.put("fov", mc.gameSettings.fovSetting);
            settings.put("tapMaterial", Config.tapMaterial);
            settings.put("tapRenderDistance", Config.tapRenderDistance);
            settings.put("quantumRenderDistance", Config.quantumRenderDistance);
            settings.put("tapParticleDensityPercent", Config.tapParticleDensityPercent);
            settings.put("quantumParticleDensityPercent", Config.quantumParticleDensityPercent);
            report.put("settings", settings);
            report.put(
                "scope",
                "Real Minecraft world and OpenGL rendering. Frame intervals include presentation/scheduling; CPU wall timers do not measure GPU cost. RenderTick START to END excludes display swap outside that boundary. Server HIGHEST START to LOWEST END is integrated-server observed MSPT, excluding benchmark fixture maintenance after END observation. Integrated server shares JVM/GC/CPU with client; not dedicated-server TPS. effectTick values are repeated on frames until the next client tick; aggregate once per distinct client tick for tick-cost inference. heavy_fixed uses deterministically initialized real particle objects at production caps (192 cover + 512 quantum), held simulation and a repeating per-frame phase schedule; heavy_dynamic retains natural production emission/tick behavior at natural production caps. Screenshots are outside sampling. No forced GC or glFinish.");
            report.put("windows", windows);
            Files.write(
                new File(output, "report.json").toPath(),
                new GsonBuilder().setPrettyPrinting()
                    .serializeNulls()
                    .create()
                    .toJson(report)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            GTSimpleWirelessNetwork.LOG.error("Effects benchmark report failed", error);
        } finally {
            if (wrappersInstalled) {
                MinecraftForge.EVENT_BUS.register(LinkNodeVisualHandler.INSTANCE);
                MinecraftForge.EVENT_BUS.register(QuantumVoxelParticleHandler.INSTANCE);
                FMLCommonHandler.instance()
                    .bus()
                    .register(LinkNodeVisualHandler.INSTANCE);
                FMLCommonHandler.instance()
                    .bus()
                    .register(QuantumVoxelParticleHandler.INSTANCE);
            }
            mc.shutdown();
        }
    }

    /** Read-only, after sampling, on the context-owning client thread. Never changes swap or current context. */
    private static Map<String, Object> swapIntervalObservation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            "thread",
            Thread.currentThread()
                .getName());
        try {
            Class<?> video = Class.forName("org.lwjgl.sdl.SDLVideo");
            Class<?> display = Class.forName("org.lwjglx.opengl.Display");
            long context = ((Number) video.getMethod("SDL_GL_GetCurrentContext")
                .invoke(null)).longValue();
            long window = ((Number) video.getMethod("SDL_GL_GetCurrentWindow")
                .invoke(null)).longValue();
            result.put("currentContextPresent", context != 0);
            result.put("currentWindowPresent", window != 0);
            result.put(
                "displayContextMatches",
                context == ((Number) display.getMethod("getGlContext")
                    .invoke(null)).longValue());
            result.put(
                "displayWindowMatches",
                window == ((Number) display.getMethod("getWindow")
                    .invoke(null)).longValue());
            IntBuffer interval = ByteBuffer.allocateDirect(4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
            interval.put(0, Integer.MIN_VALUE);
            boolean success = Boolean.TRUE.equals(
                video.getMethod("SDL_GL_GetSwapInterval", IntBuffer.class)
                    .invoke(null, interval));
            result.put("getterSucceeded", success);
            result.put("interval", success ? Integer.valueOf(interval.get(0)) : null);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            result.put("getterSucceeded", false);
            result.put("unavailable", unavailable.toString());
        }
        return result;
    }

    private static long difference(long end, long start) {
        return start < 0 || end < 0 ? -1 : end - start;
    }

    private long allocatedBytes() {
        if (!(cpu instanceof com.sun.management.ThreadMXBean)) return -1;
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) cpu;
        return bean.isThreadAllocatedMemorySupported() ? bean.getThreadAllocatedBytes(
            Thread.currentThread()
                .getId())
            : -1;
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static int size(Object object, String name) throws Exception {
        return ((Collection<?>) field(object, name)).size();
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
