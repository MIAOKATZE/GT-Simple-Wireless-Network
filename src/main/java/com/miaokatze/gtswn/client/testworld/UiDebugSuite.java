package com.miaokatze.gtswn.client.testworld;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.Language;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;

import org.lwjgl.input.Keyboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.miaokatze.gtswn.common.testworld.TestDimension;
import com.miaokatze.gtswn.common.testworld.TestUiKit;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in real-client runner: one transition per tick, one framebuffer capture per rendered case. */
public final class UiDebugSuite {

    public static final UiDebugSuite INSTANCE = new UiDebugSuite();
    private static final Gson JSON = new GsonBuilder().serializeNulls()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final int TIMEOUT = 400;
    private static final String PREFIX = "com.miaokatze.gtswn.client.gui.Gui";
    private static boolean registered;
    private final Deque<String> presets = new ArrayDeque<>();
    private final Deque<Case> cases = new ArrayDeque<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<Field, Object> baseline = new LinkedHashMap<>();
    private Minecraft mc;
    private World world;
    private GuiScreen target;
    private File directory;
    private String preset, originalLanguage, phase = "IDLE", action, actionValue;
    private String dataStatus, dataReason, dataResumePhase;
    private String terminalReason, restorationStatus, restorationDetail;
    private int originalScale, originalSlot, ticks, deadline, captureAt, actionIndex;
    private float originalYaw, originalPitch;
    private boolean matrix, screenshots, kitRequested, kitOwned, stopping;
    private boolean waitForData;
    private boolean metricsTouched, navigationTouched;
    private long started;
    private long renderStartedNanos, dataWaitStartedNanos, presetDataWaitNanos;
    private Case current;
    private Map<String, Object> explicitInput;
    private ItemStack[] originalTools;

    private UiDebugSuite() {}

    public static void register() {
        if (!DebugTestGate.isEnabled()) return;
        if (registered) return;
        registered = true;
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        ClientCommandHandler.instance.registerCommand(new CommandUiDebug());
    }

    public boolean running() {
        return !"IDLE".equals(phase);
    }

    /** Public automation entry point; must be called on the normal Minecraft client thread. */
    public void start(String selection, boolean useMatrix) {
        start(selection, useMatrix, true, null, null);
    }

    void start(String selection, boolean useMatrix, boolean withScreenshots, String explicitAction, String value) {
        DebugTestGate.requireEnabled();
        if (running()) throw new IllegalStateException("UI run already active; use /gtswnui stop");
        mc = Minecraft.getMinecraft();
        requireDimension();
        if (!Arrays
            .asList(
                "all",
                "current",
                "portable",
                "device",
                "settings",
                "quantum",
                "link",
                "panel",
                "monitor",
                "cover-input",
                "cover-output")
            .contains(selection)) throw new IllegalArgumentException("Unknown preset " + selection);
        if (!selection.equals("current") && (kitPresent() || TestUiKit.hasBackup(mc.thePlayer)))
            throw new IllegalStateException(
                "A pre-existing UI kit/backup is present. Run /gtswn test kit restore before an automatic-kit batch; current/capture/inspect may inspect it without taking ownership.");
        originalLanguage = mc.gameSettings.language;
        originalScale = mc.gameSettings.guiScale;
        originalSlot = mc.thePlayer.inventory.currentItem;
        originalYaw = mc.thePlayer.rotationYaw;
        originalPitch = mc.thePlayer.rotationPitch;
        originalTools = new ItemStack[5];
        for (int slot = 0; slot < originalTools.length; slot++)
            originalTools[slot] = ItemStack.copyItemStack(mc.thePlayer.inventory.mainInventory[slot]);
        world = mc.theWorld;
        matrix = useMatrix;
        screenshots = withScreenshots;
        action = explicitAction;
        actionValue = value;
        explicitInput = null;
        waitForData = !selection.equals("current");
        kitRequested = !selection.equals("current");
        kitOwned = false;
        stopping = false;
        metricsTouched = false;
        navigationTouched = false;
        terminalReason = null;
        restorationStatus = "NOT_REQUESTED";
        restorationDetail = null;
        presets.clear();
        cases.clear();
        results.clear();
        baseline.clear();
        target = null;
        current = null;
        preset = null;
        dataStatus = null;
        dataReason = null;
        renderStartedNanos = 0;
        dataWaitStartedNanos = 0;
        presetDataWaitNanos = 0;
        ticks = 0;
        started = System.currentTimeMillis();
        directory = new File(
            mc.mcDataDir,
            "gtswn-ui-debug/" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date())
                + "-"
                + UUID.randomUUID()
                    .toString()
                    .substring(0, 8));
        if (!directory.mkdirs()) throw new IllegalStateException("Cannot create " + directory);
        if (selection.equals("all")) presets.addAll(
            Arrays.asList(
                "portable",
                "device",
                "settings",
                "quantum",
                "link",
                "panel",
                "panel-ae-chart",
                "panel-ae-list",
                "monitor",
                "cover-input",
                "cover-output"));
        else presets.add(selection);
        phase = kitRequested ? "WAIT_KIT" : "NEXT_PRESET";
        deadline = TIMEOUT;
        writeIndex(false, null);
        if (kitRequested) {
            // Client never creates inventory stacks or container instances. OP4 server command owns the kit.
            mc.thePlayer.sendChatMessage("/gtswn test kit ui");
        }
        message(
            "START run=" + directory.getAbsolutePath()
                + (kitRequested ? "; requesting persistent server kit"
                    : "; open the production GUI within 20 seconds"));
    }

    public void stop() {
        if (!running()) return;
        stopping = true;
    }

    private void requireDimension() {
        if (mc.thePlayer == null || !TestDimension.isTest(mc.theWorld))
            throw new IllegalStateException("UI diagnostics require a real player inside the GTSWN test dimension");
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !running()) return;
        UiPerformance measured = current == null ? null : current.performance;
        long handlerStarted = measured == null ? 0 : System.nanoTime();
        ticks++;
        try {
            if (stopping && !phase.equals("WAIT_RESTORE")) {
                finish("STOPPED");
                return;
            }
            if (mc.theWorld != world || mc.thePlayer == null || !TestDimension.isTest(mc.theWorld)) {
                finish("ABORTED_WORLD_CHANGE; server kit lifecycle recovery owns inventory restoration");
                return;
            }
            switch (phase) {
                case "WAIT_KIT":
                    if (TestUiKit.isReady(mc.thePlayer)) {
                        kitOwned = true;
                        message("KIT_READY synchronized=true; server-issued temporary tools observed");
                        phase = "NEXT_PRESET";
                    } else if (ticks >= deadline) {
                        record("NOT_RUN", "Server kit not received (OP4 required)");
                        finish("KIT_TIMEOUT");
                    }
                    break;
                case "NEXT_PRESET":
                    nextPreset();
                    break;
                case "WAIT_SCREEN":
                    if (matches(mc.currentScreen, preset)) {
                        target = mc.currentScreen;
                        rememberState();
                        if (waitForData) beginDataWait("NEXT_CASE");
                        else {
                            dataStatus = "NOT_REQUESTED_CURRENT_GUI";
                            dataReason = "Current GUI capture does not assert server effects";
                            planCases();
                            phase = "NEXT_CASE";
                        }
                    } else if (ticks >= deadline) {
                        record("NOT_RUN", "Production GUI did not open before timeout; no GUI was synthesized");
                        phase = "NEXT_PRESET";
                    }
                    break;
                case "WAIT_CASE_SCREEN":
                    if (matches(mc.currentScreen, preset)) {
                        target = mc.currentScreen;
                        captureAt = ticks + 8;
                        actionIndex = 0;
                        phase = "ACTIONS";
                    } else if (ticks >= deadline) {
                        record("NOT_RUN", "Production GUI did not reopen for isolated case");
                        phase = "NEXT_CASE";
                    }
                    break;
                case "WAIT_DATA":
                    if (mc.currentScreen != target) {
                        record("NOT_RUN", "Screen changed while waiting for actual server data");
                        phase = "NEXT_PRESET";
                        break;
                    }
                    dataReason = UiDebugSnapshot.pendingDataReason(mc, target, preset);
                    if (dataReason == null || ticks >= deadline) {
                        finishDataWait();
                        dataStatus = dataReason == null ? "SERVER_RESPONSE_READY" : "DATA_TIMEOUT";
                        if (dataReason != null) record(
                            "DATA_TIMEOUT",
                            dataReason + "; layout and PNG capture continues; server effects NOT_VERIFIED");
                        if (dataResumePhase.equals("NEXT_CASE")) planCases();
                        captureAt = ticks + 4;
                        phase = dataResumePhase;
                        deadline = ticks + TIMEOUT;
                    }
                    break;
                case "NEXT_CASE":
                    nextCase();
                    break;
                case "ACTIONS":
                    if (mc.currentScreen != target) {
                        record("NOT_RUN", "Screen changed during case");
                        phase = "NEXT_CASE";
                        break;
                    }
                    if (ticks < captureAt) break;
                    if (actionIndex < current.clicks.length) {
                        String id = current.clicks[actionIndex++];
                        if (id.startsWith("assert:")) {
                            if (!assertObserved(id.substring(7))) {
                                if (ticks >= deadline) {
                                    record("FAIL", "Server-synchronized assertion timed out: " + id);
                                    phase = "NEXT_CASE";
                                } else {
                                    actionIndex--;
                                    captureAt = ticks + 2;
                                }
                            } else captureAt = ticks + 2;
                            break;
                        }
                        if (!navigate(id)) {
                            boolean undispatched = explicitInput != null
                                && Boolean.FALSE.equals(explicitInput.get("pressAttempted"))
                                && Boolean.FALSE.equals(explicitInput.get("releaseAttempted"));
                            if (undispatched && ticks < deadline) {
                                explicitInput.put("retryBeforeDispatch", true);
                                explicitInput.put("guardObservedAtTick", ticks);
                                explicitInput.put("deadlineTick", deadline);
                                actionIndex--;
                                captureAt = ticks + 2;
                            } else {
                                record(
                                    "NOT_RUN",
                                    "Whitelisted navigation control " + id
                                        + " unavailable or disabled: "
                                        + (explicitInput == null ? "no input evidence" : explicitInput.get("reason")));
                                phase = "NEXT_CASE";
                            }
                        } else {
                            navigationTouched = true;
                            captureAt = ticks + 3;
                        }
                    } else {
                        captureAt = ticks + 4;
                        deadline = ticks + TIMEOUT;
                        if (waitForData && preset.equals("portable") && !"DATA_TIMEOUT".equals(dataStatus))
                            beginDataWait("WAIT_RENDER");
                        else phase = "WAIT_RENDER";
                    }
                    break;
                case "WAIT_RENDER":
                    if (ticks >= deadline) {
                        record("NOT_RUN", "No actual render frame before timeout");
                        phase = "NEXT_CASE";
                    }
                    break;
                case "WAIT_RESTORE":
                    if (!kitPresent() && inventoryRestored()) {
                        restorationStatus = "ORIGINAL_TOOL_SLOTS_VERIFIED";
                        restorationDetail = "Original tool slots restored and verified against synchronized pre-run stacks; persistent backup restoration owned by server";
                        writeIndex(true, terminalReason);
                        complete();
                    } else if (ticks >= deadline) {
                        restorationStatus = "RESTORE_NOT_VERIFIED";
                        restorationDetail = "Server lifecycle recovery or /gtswn test kit restore required";
                        writeIndex(true, terminalReason);
                        complete();
                    }
                    break;
                default:
                    throw new IllegalStateException(phase);
            }
        } catch (Exception failure) {
            GTSimpleWirelessNetwork.LOG.error("[GTSWN-UI] case failure", failure);
            record(
                "FAIL",
                failure.getClass()
                    .getSimpleName() + ": "
                    + failure.getMessage());
            if (phase.equals("WAIT_RESTORE")) {
                restorationStatus = "RESTORE_NOT_VERIFIED";
                restorationDetail = failure.toString();
                writeIndex(true, terminalReason);
                complete();
            } else if (target != null) phase = "NEXT_CASE";
            else finish("RUN_ERROR");
        } finally {
            if (measured != null) measured.add("clientTickHandlerWallNanos", System.nanoTime() - handlerStarted);
        }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (!running() || current == null || current.performance == null || mc.currentScreen != target) return;
        if (event.phase == TickEvent.Phase.START) {
            renderStartedNanos = System.nanoTime();
            return;
        }
        long handlerStarted = System.nanoTime();
        if (renderStartedNanos != 0)
            current.performance.add("naturalRenderWindowWallNanos", handlerStarted - renderStartedNanos);
        renderStartedNanos = 0;
        if (!phase.equals("WAIT_RENDER") || ticks < captureAt) return;
        if (mc.currentScreen != target) return;
        try {
            Map<String, Object> snapshot = snapshot();
            snapshot.put("case", current.key);
            snapshot.put("dataStatus", dataStatus);
            snapshot.put("dataWaitReason", dataReason);
            snapshot.put("renderEvidence", "NORMAL_RENDER_TICK_END");
            snapshot.put("elapsedMillis", System.currentTimeMillis() - started);
            snapshot.put(
                "inputEvidence",
                current.clicks.length != 0 ? "PRODUCTION_MOUSE_CLICK_DISPATCH"
                    : current.key.equals("action-after") ? "EXPLICIT_PRODUCTION_INPUT_DISPATCH" : "NONE");
            snapshot.put(
                "source",
                "CURRENT_OPERATOR_READ_ONLY: portable EU access uses the operator's normal owner credentials; fixture panels use isolated test owners");
            snapshot.put("before", current.before);
            snapshot.put("inputDispatches", current.inputs);
            if (action != null && current.key.equals("action-after")) snapshot.put("explicitAction", action);
            if (explicitInput != null && current.key.equals("action-after")) {
                snapshot.put("explicitInput", explicitInput);
                snapshot.put("inputEvidence", explicitInput.get("inputEvidence"));
            }
            String file = String.format("%03d-%s", results.size(), current.key.replaceAll("[^a-zA-Z0-9_.-]", "_"));
            if (screenshots) {
                long pngStarted = System.nanoTime();
                try {
                    ScreenShotHelper.saveScreenshot(
                        directory,
                        file + ".png",
                        mc.displayWidth,
                        mc.displayHeight,
                        mc.getFramebuffer());
                } finally {
                    current.performance.add("pngCaptureWallNanos", System.nanoTime() - pngStarted);
                }
                File png = new File(directory, "screenshots/" + file + ".png");
                snapshot.put("png", png.exists() ? "screenshots/" + png.getName() : "NOT_WRITTEN");
                if (!png.exists()) throw new IOException("Framebuffer screenshot was not written");
            } else snapshot.put("png", "NOT_REQUESTED");
            current.performance.add("captureHandlerBeforeJsonWriteWallNanos", System.nanoTime() - handlerStarted);
            Map<String, Object> performance = current.performance.finish();
            snapshot.put("performance", performance);
            write(new File(directory, file + ".json"), snapshot);
            List<?> findings = (List<?>) snapshot.get("findings");
            long failures = findings.stream()
                .filter(item -> "FAIL".equals(((Map<?, ?>) item).get("severity")))
                .count();
            results.add(
                UiDebugSnapshot.map(
                    "preset",
                    preset,
                    "case",
                    current.key,
                    "status",
                    failures > 0 ? "LAYOUT_FAIL" : "LAYOUT_CAPTURED",
                    "json",
                    file + ".json",
                    "png",
                    snapshot.get("png"),
                    "findingCount",
                    findings.size(),
                    "failures",
                    failures,
                    "functionalStatus",
                    "MANUAL_SERVER_ASSERTION",
                    "dataStatus",
                    dataStatus,
                    "performance",
                    performance));
            writeIndex(false, null);
            phase = "NEXT_CASE";
        } catch (Exception failure) {
            record("FAIL", "Capture failed: " + failure.getMessage());
            phase = "NEXT_CASE";
        }
    }

    private boolean kitPresent() {
        if (mc.thePlayer == null) return false;
        for (net.minecraft.item.ItemStack stack : mc.thePlayer.inventory.mainInventory)
            if (TestUiKit.isKit(stack)) return true;
        for (net.minecraft.item.ItemStack stack : mc.thePlayer.inventory.armorInventory)
            if (TestUiKit.isKit(stack)) return true;
        if (TestUiKit.isKit(mc.thePlayer.inventory.getItemStack())) return true;
        return false;
    }

    private void beginDataWait(String resume) {
        dataWaitStartedNanos = System.nanoTime();
        dataResumePhase = resume;
        dataStatus = "WAITING_FOR_SERVER_RESPONSE";
        dataReason = null;
        phase = "WAIT_DATA";
        deadline = ticks + TIMEOUT;
    }

    private void finishDataWait() {
        if (dataWaitStartedNanos == 0) return;
        long elapsed = System.nanoTime() - dataWaitStartedNanos;
        if (current != null && current.performance != null && !"NEXT_CASE".equals(dataResumePhase))
            current.performance.dataWait(elapsed);
        else presetDataWaitNanos = elapsed;
        dataWaitStartedNanos = 0;
    }

    private boolean inventoryRestored() {
        for (int slot = 0; slot < originalTools.length; slot++)
            if (!ItemStack.areItemStacksEqual(originalTools[slot], mc.thePlayer.inventory.mainInventory[slot]))
                return false;
        return true;
    }

    private Map<String, Object> snapshot() {
        long inspectStarted = System.nanoTime();
        try {
            Map<String, Object> snapshot = UiModularSnapshot.supports(target) ? UiModularSnapshot.capture(mc, target)
                : UiDebugSnapshot.capture(mc, target);
            snapshot.put("expectedFramework", "MODULAR_UI_2");
            snapshot.put("requestedGuiScale", mc.gameSettings.guiScale);
            snapshot
                .put("effectiveGuiScale", new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor());
            snapshot
                .put("observedFramework", UiModularSnapshot.supports(target) ? "MODULAR_UI_2" : "VANILLA_OR_LEGACY");
            if (!"current".equals(preset) && !UiModularSnapshot.supports(target)) {
                Object value = snapshot.get("findings");
                List<Object> findings = new ArrayList<>();
                if (value instanceof List<?>) findings.addAll((List<?>) value);
                findings.add(
                    UiDebugSnapshot.map(
                        "severity",
                        "FAIL",
                        "code",
                        "LEGACY_GUI_ACTIVE",
                        "message",
                        "All production presets must use ModularUI 2"));
                snapshot.put("findings", findings);
            }
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(
                        JSON.toJson(snapshot)
                            .getBytes(StandardCharsets.UTF_8));
                StringBuilder fingerprint = new StringBuilder();
                for (byte value : bytes) fingerprint.append(String.format("%02x", value & 255));
                snapshot.put("fingerprintSha256", fingerprint.toString());
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            return snapshot;
        } finally {
            if (current != null && current.performance != null)
                current.performance.add("layoutInspectWallNanos", System.nanoTime() - inspectStarted);
        }
    }

    private boolean clickTarget(String path) throws Exception {
        if (UiModularSnapshot.supports(target)) {
            if (path.startsWith("name:")) path = UiModularSnapshot.namedPath(mc, target, path.substring(5));
            explicitInput = UiModularInput.click(mc, target, path);
            record((String) explicitInput.get("status"), String.valueOf(explicitInput.get("reason")));
            // NOT_RUN is captured as such; any attempted press is SENT even when a callback throws.
            // Do not throw/retry or mislabel a possibly executed action as unavailable.
            return true;
        }
        try {
            return click(Integer.parseInt(path));
        } catch (NumberFormatException notAnId) {
            Object value;
            int bracket = path.indexOf('[');
            if (bracket >= 0 && path.endsWith("]")) {
                Object list = UiDebugSnapshot.read(target, path.substring(0, bracket));
                int index = Integer.parseInt(path.substring(bracket + 1, path.length() - 1));
                value = list instanceof List<?> && index >= 0 && index < ((List<?>) list).size()
                    ? ((List<?>) list).get(index)
                    : null;
            } else value = UiDebugSnapshot.read(target, path);
            return value instanceof GuiButton && clickButton((GuiButton) value);
        }
    }

    private void nextPreset() throws Exception {
        if (presets.isEmpty()) {
            finish(null);
            return;
        }
        restoreState();
        target = null;
        current = null;
        dataWaitStartedNanos = 0;
        presetDataWaitNanos = 0;
        preset = presets.removeFirst();
        phase = "WAIT_SCREEN";
        deadline = ticks + TIMEOUT;
        openPreset();
    }

    private void openPreset() throws Exception {
        if (preset.equals("current")) return;
        if (mc.currentScreen != null) mc.thePlayer.closeScreen();
        if (preset.startsWith("panel") || preset.equals("monitor") || preset.startsWith("cover-")) {
            int x = preset.equals("panel-ae-chart") || preset.equals("cover-input") ? 3
                : preset.equals("panel-ae-list") || preset.equals("cover-output") ? 4 : 2;
            int z = preset.startsWith("panel") ? 1 : -1;
            if (mc.thePlayer.getDistanceSq(x + .5, 65.5, z + .5)
                > Math.pow(mc.playerController.getBlockReachDistance(), 2)) {
                record("NOT_RUN", "UI fixture outside native reach; no remote activation or teleport");
                phase = "NEXT_PRESET";
                return;
            }
            mc.thePlayer.inventory.currentItem = 4;
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(4));
            boolean oldSneak = mc.thePlayer.isSneaking();
            boolean fixtureSneak = preset.startsWith("cover-");
            mc.thePlayer.setSneaking(fixtureSneak);
            mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, fixtureSneak ? 1 : 2));
            try {
                mc.playerController.onPlayerRightClick(
                    mc.thePlayer,
                    mc.theWorld,
                    mc.thePlayer.getHeldItem(),
                    x,
                    65,
                    z,
                    1,
                    Vec3.createVectorHelper(x + .5, 66, z + .5));
            } finally {
                mc.thePlayer.setSneaking(oldSneak);
                mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, oldSneak ? 1 : 2));
            }
            return;
        }
        int slot = preset.equals("portable") ? 0 : preset.equals("quantum") ? 2 : preset.equals("link") ? 3 : 1;
        if (!TestUiKit.isKit(mc.thePlayer.inventory.mainInventory[slot])) {
            record("NOT_RUN", "Expected server kit stack missing from slot " + slot);
            phase = "NEXT_PRESET";
            return;
        }
        mc.thePlayer.inventory.currentItem = slot;
        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
        boolean sneak = !preset.equals("device") && !preset.equals("link");
        boolean oldSneak = mc.thePlayer.movementInput.sneak;
        mc.thePlayer.rotationPitch = -90;
        mc.thePlayer.sendQueue.addToSendQueue(
            new C03PacketPlayer.C05PacketPlayerLook(mc.thePlayer.rotationYaw, -90, mc.thePlayer.onGround));
        mc.thePlayer.movementInput.sneak = sneak;
        mc.thePlayer.setSneaking(sneak);
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, sneak ? 1 : 2));
        try {
            if (preset.equals("link")) {
                // The inspected Alt-air production gesture sends this exact validated C2S intent.
                com.miaokatze.gtswn.network.GTSWNPacketHandler.NETWORK.sendToServer(
                    new com.miaokatze.gtswn.network.PacketTerminalGesture(
                        com.miaokatze.gtswn.network.PacketTerminalGesture.OPEN_LINK));
            } else mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
        } finally {
            mc.thePlayer.movementInput.sneak = oldSneak;
            mc.thePlayer.setSneaking(oldSneak);
            mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, oldSneak ? 1 : 2));
        }
    }

    private static boolean matches(GuiScreen screen, String preset) {
        if (screen == null) return false;
        if (preset.equals("current")) return true;
        if (UiModularSnapshot.supports(screen)) {
            com.cleanroommc.modularui.screen.ModularScreen actual = UiModularSnapshot.wrappedScreen(screen);
            String name = actual.getName();
            if (preset.equals("monitor")) return "gregtech".equals(actual.getOwner()) && actual.getMainPanel()
                .getName()
                .toLowerCase()
                .contains("monitor");
            if (preset.startsWith("cover-")) return "gregtech".equals(actual.getOwner()) && actual.getMainPanel()
                .getName()
                .startsWith("cover.gtswn_wireless");
            if (!"gtswn".equals(actual.getOwner())) return preset.equals("current");
            if (preset.equals("current")) return true;
            if (preset.startsWith("panel")) return "network_info_panel".equals(name);
            if (preset.equals("portable")) return "portable_monitor".equals(name);
            if (preset.equals("device")) return "device_info_terminal".equals(name);
            if (preset.equals("settings")) return "device_terminal_settings".equals(name);
            if (preset.equals("quantum")) return "quantum_terminal".equals(name);
            if (preset.equals("link")) return "link_terminal".equals(name);
            if (preset.startsWith("cover-")) return name.startsWith("cover.gtswn_wireless.");
            return preset.equals("monitor") && name.contains("monitor");
        }
        if (!screen.getClass()
            .getName()
            .startsWith(PREFIX)) return false;
        if (preset.startsWith("panel")) return screen.getClass()
            .getSimpleName()
            .equals("GuiNetworkInfoPanel");
        String name = screen.getClass()
            .getSimpleName();
        switch (preset) {
            case "current":
                return true;
            case "portable":
                return name.equals("GuiPortableMonitor");
            case "device":
                return name.equals("GuiDeviceInfoTerminal");
            case "settings":
                return name.equals("GuiDeviceTerminalSettings");
            case "quantum":
                return name.equals("GuiQuantumTerminal");
            case "panel":
                return name.equals("GuiNetworkInfoPanel");
            default:
                return false;
        }
    }

    private void planCases() {
        cases.clear();
        if (action != null) {
            if (action.equals("capture")) {
                cases.add(new Case("capture", originalLanguage, originalScale, new int[0]));
                return;
            }
            cases.add(new Case("action-before", originalLanguage, originalScale, new int[0]));
            cases.add(new Case("action-after", originalLanguage, originalScale, new int[0]));
            return;
        }
        List<String> languages = new ArrayList<>();
        languages.add(originalLanguage);
        if (matrix) for (String language : Arrays.asList("en_US", "zh_CN"))
            if (!languages.contains(language)) languages.add(language);
        int[] scales = matrix ? new int[] { 1, 2, 3 } : new int[] { originalScale };
        if (UiModularSnapshot.supports(target)) {
            List<String[]> paths = new ArrayList<>();
            paths.add(new String[0]);
            if (preset.equals("settings")) {
                paths.add(new String[] { "name:device.settings.0" });
                paths.add(new String[] { "name:device.settings.4" });
                paths.add(new String[] { "name:device.settings.1" });
            } else if (preset.equals("portable")) {
                paths.add(new String[] { "name:portable.tab.eu" });
                paths.add(new String[] { "name:portable.tab.ae" });
                paths.add(new String[] { "name:portable.tab.list" });
                paths.add(new String[] { "name:portable.pins" });
                paths.add(new String[] { "name:portable.settings" });
                paths.add(new String[] { "name:portable.settings", "name:portable.settings.effects" });
            } else if (preset.startsWith("panel")) {
                paths.add(new String[] { "name:panel.tab.eu" });
                paths.add(new String[] { "name:panel.tab.ae" });
                paths.add(new String[] { "name:panel.tab.monitor" });
                paths.add(new String[] { "name:panel.tab.eu", "name:panel.palette" });
                paths.add(new String[] { "name:panel.tab.ae", "name:panel.action.12" });
                paths.add(new String[] { "name:panel.tab.monitor", "name:panel.action.33" });
            } else if (preset.equals("quantum")) {
                paths.add(new String[] { "name:quantum.tab.details" });
                paths.add(new String[] { "name:quantum.tab.effects" });
                paths.add(new String[] { "name:quantum.rename" });
                paths.add(
                    new String[] { "name:quantum.rename", "type:quantum.rename.field=GTSWN_UI_Probe",
                        "name:quantum.rename.confirm", "assert:held-name=GTSWN_UI_Probe" });
            } else if (preset.equals("link")) {
                paths.add(new String[] { "name:link.tab.details" });
                paths.add(new String[] { "name:link.tab.effects" });
            } else if (preset.equals("monitor")) {
                paths.add(new String[] { "name:wireless_monitor.control.1" });
                paths.add(new String[] { "name:wireless_monitor.control.1", "name:wireless_monitor.control.1" });
                paths.add(new String[] { "name:wireless_monitor.control.5" });
                paths.add(new String[] { "type:wireless_monitor.param1=12345", "key:enter" });
            } else if (preset.startsWith("cover-")) {
                paths.add(new String[] { "type:wireless_cover.amperage=2.5", "key:enter" });
            } else if (preset.equals("device")) {
                paths.add(new String[] { "name:device.count" });
                paths.add(new String[] { "name:device.recipe" });
                paths.add(new String[] { "name:device.power" });
                paths.add(new String[] { "name:device.power", "name:device.power.option.0" });
                paths.add(new String[] { "name:device.row.0.name@right" });
                paths.add(
                    new String[] { "name:device.row.0.name@right", "type:device.rename.name=GTSWN_UI_Device",
                        "name:device.rename.save", "assert:device-name=GTSWN_UI_Device" });
                for (int i = 0; i < 5; i++) paths.add(new String[] { "name:device.sort." + i });
            }
            for (String language : languages) for (int scale : scales) for (int i = 0; i < paths.size(); i++) cases.add(
                new Case(preset + "-" + language + "-scale" + scale + "-state" + i, language, scale, paths.get(i)));
            return;
        }
        String name = target.getClass()
            .getSimpleName();
        List<int[]> paths = new ArrayList<>();
        paths.add(new int[0]);
        if (name.equals("GuiQuantumTerminal")) {
            paths.add(new int[] { 100 });
            paths.add(new int[] { 101 });
        }
        if (name.equals("GuiDeviceTerminalSettings")) {
            paths.add(new int[] { 0 });
            paths.add(new int[] { 4 });
            paths.add(new int[] { 1 });
        }
        if (name.equals("GuiPortableMonitor")) {
            paths.add(new int[] { 6 });
            paths.add(new int[] { 7 });
            paths.add(new int[] { 8 });
            paths.add(new int[] { 45, 10 });
            paths.add(new int[] { 2 });
            paths.add(new int[] { 2, 31 });
        }
        if (name.equals("GuiDeviceInfoTerminal")) {
            paths.add(new int[] { 1 });
            paths.add(new int[] { 2 });
            paths.add(new int[] { 3 });
            paths.add(new int[] { 3, 3 });
            paths.add(new int[] { 3, 3, 3 });
            paths.add(new int[] { 3, 3, 3, 3 });
        }
        for (String language : languages)
            for (int scale : scales) for (int index = 0; index < paths.size(); index++) cases.add(
                new Case(
                    preset + "-" + language + "-scale" + scale + "-state" + index,
                    language,
                    scale,
                    paths.get(index)));
        // Panel tabs persist server state; no unverified rollback is disguised as safe local navigation.
        if (name.equals("GuiNetworkInfoPanel")) results.add(
            UiDebugSnapshot.map(
                "preset",
                preset,
                "case",
                "server-tabs",
                "status",
                "MANUAL",
                "reason",
                "Panel tabs and display modes persist server state; explicit click commands may exercise them"));
    }

    private void nextCase() throws Exception {
        if (cases.isEmpty()) {
            phase = "NEXT_PRESET";
            return;
        }
        current = cases.removeFirst();
        current.performance = new UiPerformance(presetDataWaitNanos);
        renderStartedNanos = 0;
        if (mc.currentScreen != target) {
            record("NOT_RUN", "Original production screen no longer active");
            return;
        }
        if (action == null && UiModularSnapshot.supports(target) && !preset.equals("current")) {
            if (!current.language.equals(mc.gameSettings.language) || current.scale != mc.gameSettings.guiScale) {
                metricsTouched = true;
                applyLanguage(current.language);
                mc.gameSettings.guiScale = current.scale;
            }
            current.before = snapshot();
            openPreset();
            phase = "WAIT_CASE_SCREEN";
            deadline = ticks + TIMEOUT;
            return;
        }
        if (action == null) {
            boolean navigationRestored = restoreState();
            boolean metricsChanged = !current.language.equals(mc.gameSettings.language)
                || current.scale != mc.gameSettings.guiScale;
            if (metricsChanged) {
                metricsTouched = true;
                applyLanguage(current.language);
                mc.gameSettings.guiScale = current.scale;
            }
            if (navigationRestored || metricsChanged) {
                ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
                target.setWorldAndResolution(mc, resolution.getScaledWidth(), resolution.getScaledHeight());
            }
        }
        current.before = snapshot();
        if (action != null && current.key.equals("action-after")) {
            if (action.equals("click")) {
                if (!clickTarget(actionValue))
                    throw new IllegalStateException("Button unavailable/disabled: " + actionValue);
            } else if (action.equals("type")) {
                if (UiModularSnapshot.supports(target)) {
                    int split = actionValue.indexOf(' ');
                    if (split < 0) throw new IllegalArgumentException("type requires field-path and text");
                    String path = actionValue.substring(0, split);
                    if (path.startsWith("name:")) path = UiModularSnapshot.namedPath(mc, target, path.substring(5));
                    explicitInput = UiModularInput.type(mc, target, path, actionValue.substring(split + 1));
                    if (!"SENT".equals(explicitInput.get("status")))
                        record("NOT_RUN", String.valueOf(explicitInput.get("reason")));
                } else type(actionValue);
            }
            // Explicit actions may open a child screen; observe the actual resulting screen.
            if (matches(mc.currentScreen, "current")) target = mc.currentScreen;
        }
        actionIndex = 0;
        captureAt = ticks + 2;
        phase = "ACTIONS";
    }

    private void rememberState() throws IllegalAccessException {
        baseline.clear();
        if (UiModularSnapshot.supports(target)) return;
        for (Field field : UiDebugSnapshot.fields(target.getClass())) {
            if (field.getName()
                .matches(
                    "tab|page|selected|settings|settingsPage|pinManager|effectsPage|palettePage|showRecipe|powerFilterOpen|stateFilter|powerFilter|manualTab|chartZoom|panX|panY")) {
                field.setAccessible(true);
                baseline.put(field, field.get(target));
            }
        }
    }

    private boolean restoreState() throws IllegalAccessException {
        if (target == null || action != null || !navigationTouched) return false;
        boolean changed = false;
        for (Map.Entry<Field, Object> entry : baseline.entrySet()) if (entry.getKey()
            .getDeclaringClass()
            .isInstance(target)) {
                if (!java.util.Objects.equals(
                    entry.getKey()
                        .get(target),
                    entry.getValue())) {
                    entry.getKey()
                        .set(target, entry.getValue());
                    changed = true;
                }
            }
        navigationTouched = false;
        return changed;
    }

    private boolean navigate(String control) throws Exception {
        if (!UiModularSnapshot.supports(target)) return click(Integer.parseInt(control));
        if (control.startsWith("type:")) {
            int split = control.indexOf('=');
            String name = control.substring(5, split);
            String path = UiModularSnapshot.namedPath(mc, target, name);
            explicitInput = UiModularInput.type(mc, target, path, control.substring(split + 1));
            current.inputs.add(explicitInput);
            return "SENT".equals(explicitInput.get("status"))
                && Boolean.TRUE.equals(explicitInput.get("observedTextMatchesRequest"));
        }
        if (control.startsWith("key:")) {
            com.cleanroommc.modularui.screen.ModularScreen screen = UiModularSnapshot.wrappedScreen(target);
            int key = control.equals("key:enter") ? Keyboard.KEY_RETURN : Keyboard.KEY_ESCAPE;
            screen.onKeyPressed('\0', key);
            screen.onKeyRelease('\0', key);
            current.inputs
                .add(UiDebugSnapshot.map("status", "SENT", "inputEvidence", "PRODUCTION_MUI2_KEY_EVENT", "key", key));
            return true;
        }
        int mouseButton = control.endsWith("@right") ? 1 : 0;
        if (mouseButton == 1) control = control.substring(0, control.length() - 6);
        String path = control.startsWith("name:") ? UiModularSnapshot.namedPath(mc, target, control.substring(5))
            : control;
        Map<String, Object> input = UiModularInput.click(mc, target, path, mouseButton);
        current.inputs.add(input);
        if (!"SENT".equals(input.get("status"))) {
            explicitInput = input;
            return false;
        }
        explicitInput = input;
        return true;
    }

    private boolean assertObserved(String assertion) {
        String observed = null;
        int split = assertion.indexOf('=');
        String kind = assertion.substring(0, split), expected = assertion.substring(split + 1);
        ItemStack held = mc.thePlayer.getHeldItem();
        if (kind.equals("held-name")) observed = held == null ? null : held.getDisplayName();
        if (kind.equals("device-name") && held != null) {
            com.miaokatze.gtswn.client.DeviceTerminalClientCache.Snapshot state = com.miaokatze.gtswn.client.DeviceTerminalClientCache
                .getSnapshot(com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(held));
            if (state != null && !state.entries.isEmpty()) observed = state.entries.get(0).displayName;
        }
        if (!expected.equals(observed)) return false;
        explicitInput = UiDebugSnapshot.map(
            "status",
            "OBSERVED",
            "inputEvidence",
            "SERVER_SYNCHRONIZED_RESPONSE",
            "assertion",
            kind,
            "expected",
            expected,
            "observed",
            observed);
        current.inputs.add(explicitInput);
        return true;
    }

    private boolean click(int id) throws Exception {
        for (GuiButton button : UiDebugSnapshot.buttons(target)) if (button.id == id) return clickButton(button);
        return false;
    }

    private boolean clickButton(GuiButton button) throws Exception {
        if (!button.visible || !button.enabled) return false;
        // GuiScreen input callbacks already consume logical layout coordinates.
        int x = button.xPosition + button.width / 2;
        int y = button.yPosition + button.height / 2;
        invoke(
            target,
            new String[] { "mouseClicked", "func_73864_a" },
            new Class<?>[] { int.class, int.class, int.class },
            x,
            y,
            0);
        invoke(
            target,
            new String[] { "mouseMovedOrUp", "func_146286_b" },
            new Class<?>[] { int.class, int.class, int.class },
            x,
            y,
            0);
        return true;
    }

    private void type(String request) throws Exception {
        int split = request.indexOf(' ');
        if (split < 0) throw new IllegalArgumentException("type requires field-path and text");
        String path = request.substring(0, split), text = request.substring(split + 1);
        if (text.length() > 256) throw new IllegalArgumentException("Text limited to 256 characters");
        Object value;
        int bracket = path.indexOf('[');
        if (bracket >= 0 && path.endsWith("]")) {
            Object list = UiDebugSnapshot.read(target, path.substring(0, bracket));
            int index = Integer.parseInt(path.substring(bracket + 1, path.length() - 1));
            value = list instanceof List<?> && index >= 0 && index < ((List<?>) list).size()
                ? ((List<?>) list).get(index)
                : null;
        } else value = UiDebugSnapshot.read(target, path);
        if (!(value instanceof GuiTextField)) throw new IllegalArgumentException("No real text field at " + path);
        GuiTextField field = (GuiTextField) value;
        if (!field.getVisible()) throw new IllegalStateException("Text field is not visible");
        UiTextInput.Plan textInput;
        try {
            textInput = UiTextInput.prepare();
        } catch (UiTextInput.Refused refusal) {
            refuseText(refusal.getMessage());
            return;
        }
        for (Field member : UiDebugSnapshot.fields(target.getClass())) {
            member.setAccessible(true);
            Object sibling = member.get(target);
            if (sibling instanceof GuiTextField && sibling != field) ((GuiTextField) sibling).setFocused(false);
            if (sibling instanceof List<?>) for (Object item : (List<?>) sibling)
                if (item instanceof GuiTextField && item != field) ((GuiTextField) item).setFocused(false);
        }
        field.setFocused(true);
        try {
            textInput.validateBeforeDeletion(field);
        } catch (UiTextInput.Refused refusal) {
            refuseText(refusal.getMessage());
            return;
        }
        field.setCursorPositionZero();
        field.setSelectionPos(
            field.getText()
                .length());
        invoke(
            target,
            new String[] { "keyTyped", "func_73869_a" },
            new Class<?>[] { char.class, int.class },
            '\b',
            Keyboard.KEY_BACK);
        GuiScreen editingScreen = target;
        for (char character : text.toCharArray()) textInput.send(
            character,
            typed -> invoke(
                editingScreen,
                new String[] { "keyTyped", "func_73869_a" },
                new Class<?>[] { char.class, int.class },
                typed,
                0),
            () -> mc.currentScreen == editingScreen && field.isFocused());
        explicitInput = textInput.evidence();
        explicitInput.put("field", path);
        explicitInput.put("observedTextMatchesRequest", text.equals(field.getText()));
    }

    private void refuseText(String reason) {
        explicitInput = UiDebugSnapshot
            .map("status", "NOT_RUN", "inputEvidence", "NONE", "functionalStatus", "NOT_VERIFIED", "reason", reason);
        record("NOT_RUN", reason);
    }

    private static void invoke(Object owner, String[] names, Class<?>[] signature, Object... arguments)
        throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) for (String name : names) {
            try {
                Method method = type.getDeclaredMethod(name, signature);
                method.setAccessible(true);
                method.invoke(owner, arguments);
                return;
            } catch (NoSuchMethodException ignored) { /* Try the SRG alias or superclass. */ }
        }
        throw new NoSuchMethodException(Arrays.toString(names));
    }

    private void applyLanguage(String code) {
        if (code.equals(mc.gameSettings.language)) return;
        Language selected = null;
        for (Object language : mc.getLanguageManager()
            .getLanguages())
            if (((Language) language).getLanguageCode()
                .equals(code)) selected = (Language) language;
        if (selected == null) throw new IllegalArgumentException("Language not installed: " + code);
        mc.getLanguageManager()
            .setCurrentLanguage(selected);
        mc.gameSettings.language = code;
        long begin = System.currentTimeMillis();
        mc.refreshResources();
        mc.fontRenderer.setUnicodeFlag(
            mc.getLanguageManager()
                .isCurrentLocaleUnicode() || mc.gameSettings.forceUnicodeFont);
        mc.fontRenderer.setBidiFlag(
            mc.getLanguageManager()
                .isCurrentLanguageBidirectional());
        GTSimpleWirelessNetwork.LOG
            .info("[GTSWN-UI] language={} resourceReloadMillis={}", code, System.currentTimeMillis() - begin);
    }

    private void finish(String reason) {
        finishDataWait();
        if (phase.equals("WAIT_RESTORE") && (mc.thePlayer == null || mc.theWorld != world))
            restorationStatus = "SERVER_LIFECYCLE_RECOVERY_REQUIRED";
        if (terminalReason == null) {
            terminalReason = reason == null ? "COMPLETED" : reason;
            if (reason != null) recordUnexecuted(reason);
        }
        try {
            boolean navigationRestored = restoreState();
            boolean metricsChanged = metricsTouched
                && (!originalLanguage.equals(mc.gameSettings.language) || mc.gameSettings.guiScale != originalScale);
            if (metricsChanged) {
                applyLanguage(originalLanguage);
                mc.gameSettings.guiScale = originalScale;
            }
            if (mc.thePlayer != null && mc.theWorld == world) {
                if (waitForData) {
                    mc.thePlayer.rotationYaw = originalYaw;
                    mc.thePlayer.rotationPitch = originalPitch;
                    mc.thePlayer.inventory.currentItem = originalSlot;
                    mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(originalSlot));
                    mc.thePlayer.sendQueue.addToSendQueue(
                        new C03PacketPlayer.C05PacketPlayerLook(originalYaw, originalPitch, mc.thePlayer.onGround));
                }
                if (target != null && mc.currentScreen == target
                    && !kitOwned
                    && (navigationRestored || metricsChanged)) {
                    ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
                    target.setWorldAndResolution(mc, resolution.getScaledWidth(), resolution.getScaledHeight());
                }
            }
        } catch (Exception failure) {
            results.add(UiDebugSnapshot.map("status", "RESTORE_FAILED", "reason", failure.toString()));
        } finally {
            if (metricsTouched) mc.gameSettings.guiScale = originalScale;
            if (kitRequested && mc.thePlayer != null && mc.theWorld == world && TestDimension.isTest(mc.theWorld)) {
                mc.thePlayer.closeScreen();
                mc.thePlayer.sendChatMessage("/gtswn test kit restore");
                phase = "WAIT_RESTORE";
                deadline = ticks + TIMEOUT;
                kitRequested = false;
                restorationStatus = "WAITING_FOR_SERVER_RESTORE";
                writeIndex(false, terminalReason);
            } else {
                if (kitRequested) restorationStatus = "SERVER_LIFECYCLE_RECOVERY_REQUIRED";
                writeIndex(true, terminalReason);
                complete();
            }
        }
    }

    private void recordUnexecuted(String reason) {
        if (current != null && !caseRecorded(preset, current.key)) {
            Map<String, Object> unexecuted = UiDebugSnapshot.map(
                "preset",
                preset,
                "case",
                current.key,
                "status",
                "NOT_RUN",
                "reason",
                reason,
                "functionalStatus",
                "NOT_VERIFIED");
            if (current.performance != null) unexecuted.put("performance", current.performance.finish());
            results.add(unexecuted);
        }
        if (current == null && preset != null
            && (phase.equals("WAIT_SCREEN") || phase.equals("WAIT_DATA") && "NEXT_CASE".equals(dataResumePhase)))
            results.add(
                UiDebugSnapshot.map(
                    "preset",
                    preset,
                    "case",
                    null,
                    "status",
                    "NOT_RUN",
                    "reason",
                    reason + "; preset did not reach case planning",
                    "functionalStatus",
                    "NOT_VERIFIED"));
        while (!cases.isEmpty()) {
            Case skipped = cases.removeFirst();
            results.add(
                UiDebugSnapshot.map(
                    "preset",
                    preset,
                    "case",
                    skipped.key,
                    "status",
                    "NOT_RUN",
                    "reason",
                    reason,
                    "functionalStatus",
                    "NOT_VERIFIED"));
        }
        while (!presets.isEmpty()) results.add(
            UiDebugSnapshot.map(
                "preset",
                presets.removeFirst(),
                "case",
                null,
                "status",
                "NOT_RUN",
                "reason",
                reason + "; preset was not opened/planned",
                "functionalStatus",
                "NOT_VERIFIED"));
    }

    private boolean caseRecorded(String selectedPreset, String key) {
        for (Map<String, Object> result : results)
            if (java.util.Objects.equals(selectedPreset, result.get("preset")) && key.equals(result.get("case"))
                && ("LAYOUT_CAPTURED".equals(result.get("status")) || "LAYOUT_FAIL".equals(result.get("status"))
                    || "FAIL".equals(result.get("status"))
                    || "NOT_RUN".equals(result.get("status"))))
                return true;
        return false;
    }

    private void record(String status, String reason) {
        Map<String, Object> result = UiDebugSnapshot.map(
            "preset",
            preset,
            "case",
            current == null ? null : current.key,
            "status",
            status,
            "reason",
            reason,
            "functionalStatus",
            "NOT_VERIFIED");
        if (current != null && current.performance != null && ("FAIL".equals(status) || "NOT_RUN".equals(status)))
            result.put("performance", current.performance.finish());
        if (current != null) result.put("inputDispatches", new ArrayList<>(current.inputs));
        results.add(result);
        writeIndex(false, null);
    }

    private void complete() {
        phase = "IDLE";
        target = null;
        baseline.clear();
        message("COMPLETE run=" + directory.getAbsolutePath() + " cases=" + results.size());
    }

    private void writeIndex(boolean completed, String reason) {
        if (directory == null) return;
        try {
            write(new File(directory, "results.json"), results);
            write(
                new File(directory, "index.json"),
                UiDebugSnapshot.map(
                    "schema",
                    1,
                    "completed",
                    completed,
                    "phase",
                    phase,
                    "reason",
                    terminalReason == null ? reason : terminalReason,
                    "terminalReason",
                    terminalReason,
                    "restorationStatus",
                    restorationStatus,
                    "restorationDetail",
                    restorationDetail,
                    "startedEpochMillis",
                    started,
                    "elapsedMillis",
                    System.currentTimeMillis() - started,
                    "dimension",
                    world == null ? null : world.provider.dimensionId,
                    "originalLanguage",
                    originalLanguage,
                    "originalScale",
                    originalScale,
                    "matrix",
                    matrix,
                    "caseCount",
                    results.size(),
                    "results",
                    "results.json",
                    "functionalStatus",
                    "MANUAL_SERVER_ASSERTION",
                    "coverage",
                    "Real GUI objects + actual rendered framebuffer; navigation dispatch is observed, server effects are not asserted. Custom drawing and native window-size variants require manual review.",
                    "restoration",
                    "Language/scale restored without saving settings; current-screen local navigation restored; explicit actions are retained; kit restoration is server-owned and acknowledged by removal of synchronized marked stacks."));
        } catch (IOException failure) {
            GTSimpleWirelessNetwork.LOG.error("[GTSWN-UI] cannot write run index", failure);
        }
    }

    private static void write(File file, Object value) throws IOException {
        Files.write(
            file.toPath(),
            JSON.toJson(value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void message(String text) {
        GTSimpleWirelessNetwork.LOG.info("[GTSWN-UI] {}", text);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[GTSWN-UI] " + text));
    }

    private static final class Case {

        final String key, language;
        final int scale;
        final String[] clicks;
        Map<String, Object> before;
        final List<Map<String, Object>> inputs = new ArrayList<>();
        UiPerformance performance;

        Case(String key, String language, int scale, int[] clicks) {
            this.key = key;
            this.language = language;
            this.scale = scale;
            this.clicks = new String[clicks.length];
            for (int i = 0; i < clicks.length; i++) this.clicks[i] = Integer.toString(clicks[i]);
        }

        Case(String key, String language, int scale, String[] clicks) {
            this.key = key;
            this.language = language;
            this.scale = scale;
            this.clicks = clicks;
        }
    }
}
