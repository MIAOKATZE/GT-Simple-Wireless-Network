// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.testworld;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ScreenShotHelper;

import com.google.gson.GsonBuilder;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.testworld.TestDimension;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/** Observes actual decoded Netty input and GUI/cache state. Never writes a product cache or GUI field. */
public final class DevicePagedClientCheck {

    private static final DevicePagedClientCheck INSTANCE = new DevicePagedClientCheck();
    private static final String HANDLER = "gtswn-paged-qa-observer";
    private static final long STALE_VERSION = Long.MAX_VALUE - 17;
    private static boolean registered;
    private final ArrayBlockingQueue<long[]> observed = new ArrayBlockingQueue<>(128);
    private final Set<Integer> pages = new HashSet<>();
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private Minecraft mc;
    private Object connection, world;
    private UUID terminal;
    private volatile UUID wireTerminal;
    private String run, phase = "IDLE";
    private File directory;
    private boolean reportWritten;
    private long firstEpoch, newEpoch, staleSeenAt = -1;
    private int ticks, stable, captureWait;
    private boolean opened, capturePending, captured;
    private FMLEmbeddedChannel channel;

    static void register() {
        if (registered || !DebugTestGate.isEnabled()) return;
        registered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    /** Read-only client-thread state; the host must also verify the matching server report and cleanup. */
    public static Map<String, Object> automationSnapshot() {
        Map<String, Object> facts = new LinkedHashMap<>(INSTANCE.result);
        facts.put("runId", INSTANCE.run);
        facts.put("phase", INSTANCE.phase);
        facts.put("running", !"IDLE".equals(INSTANCE.phase));
        facts.put("authorizationPending", ClientTestPermission.pending());
        facts.put("reportDirectory", INSTANCE.directory == null ? null : INSTANCE.directory.getAbsolutePath());
        facts.put("reportWritten", INSTANCE.reportWritten);
        return facts;
    }

    public static void start() {
        DebugTestGate.requireEnabled();
        ClientTestPermission.requireReceipt();
        register();
        DevicePagedClientCheck run = INSTANCE;
        if (!"IDLE".equals(run.phase)) throw new IllegalStateException("Paged check already running");
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null || !TestDimension.isTest(mc.theWorld))
            throw new IllegalStateException("Use /gtswn test enter before /gtswnui paged; dedicated test actor only");
        if (UiDebugSuite.INSTANCE.running()) throw new IllegalStateException("Stop other UI test runs first");
        run.mc = mc;
        run.connection = mc.getNetHandler();
        run.world = mc.theWorld;
        run.reportWritten = false;
        run.result.clear();
        run.result.put("actorLevel2", ClientTestPermission.actorLevel2());
        run.result.put("actorLevel4", ClientTestPermission.actorLevel4());
        run.events.clear();
        run.pages.clear();
        run.observed.clear();
        run.terminal = null;
        run.wireTerminal = null;
        run.run = null;
        run.firstEpoch = run.newEpoch = 0;
        run.staleSeenAt = -1;
        run.ticks = run.stable = run.captureWait = 0;
        run.opened = run.capturePending = run.captured = false;
        run.directory = null;
        run.phase = "WAIT_FIXTURE";
        run.result.put("status", "RUNNING");
        run.result.put(
            "scope",
            "Real dedicated/integrated client GUI polling and actual decoded Netty pages; old-peer matrix remains separate wire-contract evidence");
        run.result.put(
            "actor",
            mc.thePlayer.getUniqueID()
                .toString());
        run.channel = NetworkRegistry.INSTANCE.getChannel("gtswn", Side.CLIENT);
        if (run.channel == null) throw new IllegalStateException("Native gtswn client channel unavailable");
        run.result.put(
            "pipelineBeforeObserver",
            new ArrayList<>(
                run.channel.pipeline()
                    .names()));
        String codec = attachDecodedObserver(run.channel.pipeline(), HANDLER, new ChannelInboundHandlerAdapter() {

            @Override
            public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                if (message instanceof PacketSyncDeviceTerminalDelta) {
                    PacketSyncDeviceTerminalDelta page = (PacketSyncDeviceTerminalDelta) message;
                    UUID id = run.wireTerminal;
                    if (id != null && id.equals(page.getTerminalId())
                        && page.getKind() < PacketSyncDeviceTerminalDelta.KEEPALIVE)
                        run.observed.offer(
                            new long[] { page.getEpoch(), page.getVersion(), page.getPageIndex(), page.getPageTotal(),
                                page.getEntryTotal(), page.getWireVersion() });
                }
                context.fireChannelRead(message); // Observe only: the unmodified production handler still
                                                  // consumes it.
            }
        });
        run.result.put("decoderContext", codec);
        run.result.put(
            "pipelineWithObserver",
            new ArrayList<>(
                run.channel.pipeline()
                    .names()));
        mc.thePlayer.sendChatMessage("/gtswn test run device.paged_client");
    }

    /** The codec emits decoded IMessage objects; observe before any business wrapper consumes them. */
    static String attachDecodedObserver(ChannelPipeline pipeline, String name, ChannelInboundHandlerAdapter observer) {
        ChannelHandlerContext codec = pipeline.context(FMLIndexedMessageToMessageCodec.class);
        if (codec == null)
            throw new IllegalStateException("Native FML message decoder unavailable; pipeline=" + pipeline.names());
        pipeline.addAfter(codec.name(), name, observer);
        return codec.name();
    }

    public static void stop() {
        DevicePagedClientCheck run = INSTANCE;
        if ("IDLE".equals(run.phase)) return;
        if (run.run != null && run.mc.thePlayer != null && run.mc.getNetHandler() == run.connection) run.send("abort");
        run.finish("ABORTED", "Explicit client stop; server persistent fixture recovery requested");
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || "IDLE".equals(phase)) return;
        try {
            ticks++;
            if (mc.thePlayer == null || mc.theWorld != world || mc.getNetHandler() != connection)
                throw new IllegalStateException("Actor world/connection changed");
            if (ticks > 1800) throw new IllegalStateException("Paged client timeout in " + phase);
            if ("WAIT_FIXTURE".equals(phase)) {
                ItemStack held = mc.thePlayer.inventory.mainInventory[0];
                if (held == null || !(held.getItem() instanceof ItemDeviceInfoTerminal)
                    || !held.hasTagCompound()
                    || !held.stackTagCompound.hasKey("GTSWN_PagedQA_Run")) return;
                run = held.stackTagCompound.getString("GTSWN_PagedQA_Run");
                UUID.fromString(run);
                terminal = ItemDeviceInfoTerminal.readTerminalId(held);
                wireTerminal = terminal;
                directory = new File(mc.mcDataDir, "gtswn-ui-reports/paged-" + run);
                Files.createDirectories(directory.toPath());
                result.put("runId", run);
                result.put("taskId", "device.paged_client");
                result.put("lane", "client-server");
                result.put("artifact", com.miaokatze.gtswn.common.testworld.TestLabIdentity.ARTIFACT);
                result.put("terminalId", terminal.toString());
                result.put("tailKey", mc.thePlayer.dimension + ":20001024:64:20000000");
                mc.thePlayer.inventory.currentItem = 0;
                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(0));
                boolean sneak = mc.thePlayer.isSneaking();
                mc.thePlayer.setSneaking(false);
                try {
                    mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
                } finally {
                    mc.thePlayer.setSneaking(sneak);
                }
                opened = true;
                phase = "FIRST_SNAPSHOT";
                observe("fixture", "Synchronized actual server-issued run terminal; production sendUseItem dispatched");
            }
            long[] page;
            while ((page = observed.poll()) != null) {
                if (page[5] != 2 || page[3] != 9 || page[4] != 1025)
                    throw new IllegalStateException("Unexpected native page capacity/schema");
                if (page[1] == STALE_VERSION) {
                    staleSeenAt = ticks;
                    observe("stale_packet_received", "Actual decoded native page oldEpoch=" + page[0]);
                } else {
                    if (firstEpoch == 0) firstEpoch = page[0];
                    if (page[0] > firstEpoch) newEpoch = page[0];
                    if ("FIRST_SNAPSHOT".equals(phase) && page[0] == firstEpoch
                        || "SECOND_SNAPSHOT".equals(phase) && page[0] > firstEpoch) pages.add((int) page[2]);
                }
            }
            if ("FIRST_SNAPSHOT".equals(phase) || "SECOND_SNAPSHOT".equals(phase)) {
                if ("SECOND_SNAPSHOT".equals(phase)
                    && DeviceTerminalClientCache.authorizationEpoch(terminal) <= firstEpoch) {
                    stable = 0;
                    return;
                }
                if (!completeGui()) {
                    stable = 0;
                    return;
                }
                if (++stable < 35) return; // Permit the GUI's normal 20-tick poll to ACK the complete snapshot.
                if (pages.size() != 9)
                    throw new IllegalStateException("Not all 9 pages were observed at native client decoder");
                result.put(
                    phase.equals("FIRST_SNAPSHOT") ? "firstEpoch" : "renewedEpoch",
                    DeviceTerminalClientCache.authorizationEpoch(terminal));
                if ("FIRST_SNAPSHOT".equals(phase)) {
                    send("renew");
                    phase = "SECOND_SNAPSHOT";
                    pages.clear();
                    stable = 0;
                    observe("first_complete", "1025 cache/UI rows and unique tail; all 9 native pages observed");
                } else {
                    if (DeviceTerminalClientCache.authorizationEpoch(terminal) <= firstEpoch) {
                        stable = 0;
                        return;
                    }
                    send("stale");
                    phase = "WAIT_STALE";
                    observe("renewed_complete", "New epoch complete; stale input requested from server");
                }
            } else if ("WAIT_STALE".equals(phase) && staleSeenAt >= 0 && ticks - staleSeenAt >= 20) {
                if (!completeGui() || DeviceTerminalClientCache.authorizationEpoch(terminal) <= firstEpoch
                    || DeviceTerminalClientCache.getSnapshot(terminal).version == STALE_VERSION)
                    throw new IllegalStateException("Old epoch changed the actual cache/GUI");
                result.put("staleEpochDropped", true);
                result.put("nativePagesPerEpoch", 9);
                capturePending = true;
                phase = "CAPTURE";
            } else if ("CAPTURE".equals(phase)) {
                if (++captureWait > 100) throw new IllegalStateException("No rendered frame for screenshot");
                if (!captured) return;
                mc.displayGuiScreen(null);
                send("complete");
                phase = "WAIT_RESTORE";
            } else if ("WAIT_RESTORE".equals(phase)) {
                ItemStack item = mc.thePlayer.inventory.mainInventory[0];
                if (item != null && item.hasTagCompound()
                    && run.equals(item.stackTagCompound.getString("GTSWN_PagedQA_Run"))) return;
                result.put("slotRestoredFromServer", true);
                finish(
                    "PASS",
                    "1025 real native Netty/cache/UI rows, unique tail, renewed epoch, stale page dropped and synchronized server restoration");
            }
        } catch (Exception | LinkageError failure) {
            try {
                if (run != null && mc.thePlayer != null && mc.getNetHandler() == connection) send("abort");
            } catch (RuntimeException ignored) {}
            finish("FAIL", failure.toString());
        }
    }

    private boolean completeGui() {
        if (!MonitorCapabilities.clientSupports(MonitorCapabilities.DIT_PAGED) || terminal == null
            || !UiModularSnapshot.supports(mc.currentScreen)) return false;
        Object screen = UiModularSnapshot.wrappedScreen(mc.currentScreen);
        if (!screen.getClass()
            .getSimpleName()
            .equals("GuiDeviceInfoTerminal") || !terminal.equals(UiDebugSnapshot.read(screen, "anchor"))) return false;
        DeviceTerminalClientCache.Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(terminal);
        Object displayed = UiDebugSnapshot.read(screen, "displayedSnapshot"),
            rows = UiDebugSnapshot.read(screen, "sortedEntries");
        if (snapshot == null || displayed != snapshot
            || snapshot.entries.size() != 1025
            || !(rows instanceof List<?>)
            || ((List<?>) rows).size() != 1025) return false;
        String tail = String.valueOf(result.get("tailKey"));
        int matches = 0;
        for (com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry row : snapshot.entries)
            if (tail.equals(row.key)) matches++;
        if (matches != 1) throw new IllegalStateException("Unique fixture tail missing/duplicated");
        result.put("cacheEntries", snapshot.entries.size());
        result.put("guiSortedEntries", ((List<?>) rows).size());
        result.put("guiDisplayedSameSnapshot", true);
        result.put("tailMatches", matches);
        return true;
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !capturePending || !"CAPTURE".equals(phase)) return;
        try {
            ScreenShotHelper
                .saveScreenshot(directory, "paged-client.png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File png = new File(directory, "screenshots/paged-client.png");
            if (!png.isFile()) throw new IllegalStateException("Framebuffer screenshot absent");
            result.put("screenshot", png.getAbsolutePath());
            captured = true;
            capturePending = false;
        } catch (Exception failure) {
            if (run != null && mc.thePlayer != null && mc.getNetHandler() == connection) send("abort");
            finish("FAIL", failure.toString());
        }
    }

    private void send(String action) {
        mc.thePlayer.sendChatMessage("/gtswn test paged " + action + " " + run);
    }

    private void observe(String kind, String detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tick", ticks);
        event.put("kind", kind);
        event.put("detail", detail);
        events.add(event);
    }

    private void finish(String status, String detail) {
        result.put("status", status);
        result.put("detail", detail);
        result.put("ticks", ticks);
        result.put("events", events);
        try {
            if (directory == null)
                directory = new File(mc.mcDataDir, "gtswn-ui-reports/paged-failed-" + System.currentTimeMillis());
            Files.createDirectories(directory.toPath());
            Files.write(
                new File(directory, "result.json").toPath(),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(result)
                    .getBytes(StandardCharsets.UTF_8));
            reportWritten = true;
        } catch (Exception failure) {
            reportWritten = false;
            detail += "; report write failed=" + failure;
        }
        try {
            if (channel != null && channel.pipeline()
                .get(HANDLER) != null) channel.pipeline()
                    .remove(HANDLER);
        } finally {
            channel = null;
            wireTerminal = null;
            phase = "IDLE";
        }
        if (mc != null && mc.thePlayer != null)
            mc.thePlayer.addChatMessage(new ChatComponentText("[GTSWN-PAGED-CLIENT] " + status + " " + detail));
    }
}
