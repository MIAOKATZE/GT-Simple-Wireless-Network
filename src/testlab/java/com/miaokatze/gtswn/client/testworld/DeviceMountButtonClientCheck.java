// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.testworld;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.Display;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPresetPlacement;
import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Explicit live-button probe. Reads product state; all edits go through actual UI input dispatch. */
public final class DeviceMountButtonClientCheck {

    private final Minecraft mc;
    private final UUID terminal;
    private final String run;
    private final File directory, control;
    private final Class<?> desktop, workbench;
    private final Map<String, Object> facts = new LinkedHashMap<>();
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private DeviceBinding binding;
    private Object expectedPlacement;
    private String stage = "WAIT_NON_OP";
    private int ticks, stageTicks, draws, baseline, round;
    private boolean drawn;
    private String shot;
    private Exception pendingFailure;

    DeviceMountButtonClientCheck(Minecraft mc, UUID terminal, String run, File parent) throws Exception {
        this.mc = mc;
        this.terminal = terminal;
        this.run = run;
        control = new File(System.getProperty("miaotest.host.receiptDir"));
        directory = new File(control, "mount-button");
        Files.createDirectories(directory.toPath());
        desktop = Class.forName("com.miaokatze.gtswn.client.engineering.FactoryDesktop");
        workbench = Class.forName("com.miaokatze.gtswn.client.engineering.WorkbenchHud");
        facts.put(
            "scope",
            "Live terminal row and mount button; ordinary GUI host, natural render frames and production WorkbenchHud input; no OS input");
        facts.put("hostRunId", System.getProperty("miaotest.host.runId"));
        facts.put("businessRunId", run);
        facts.put(
            "actor",
            mc.thePlayer.getUniqueID()
                .toString());
        facts.put("status", "RUNNING");
        requestRole(false);
        MinecraftForge.EVENT_BUS.register(this);
    }

    Map<String, Object> result() {
        facts.put("stage", stage);
        facts.put("trace", trace);
        return facts;
    }

    private void require(boolean pass, String message) {
        if (!pass) throw new IllegalStateException("Mount button " + stage + ": " + message);
    }

    private void next(String value) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tick", ticks);
        event.put("stage", stage);
        event.put("next", value);
        event.put("displayActive", Display.isActive());
        trace.add(event);
        stage = value;
        stageTicks = draws = 0;
    }

    boolean tick() throws Exception {
        if (pendingFailure != null) {
            require(++stageTicks < 200, "control role restoration timed out after failure: " + pendingFailure);
            if (!roleReady(true)) return false;
            throw pendingFailure;
        }
        try {
            return advance();
        } catch (Exception failure) {
            fail(failure);
            return false;
        }
    }

    void fail(Exception failure) throws Exception {
        if (pendingFailure != null) return;
        pendingFailure = failure;
        facts.put("status", "FAIL");
        facts.put("failure", failure.toString());
        requestRole(true);
        next("RESTORE_AFTER_FAILURE");
    }

    private boolean advance() throws Exception {
        ticks++;
        stageTicks++;
        require(ticks < 1000, "timeout");
        if (stage.startsWith("WAIT_")) {
            boolean restore = stage.equals("WAIT_OP_RESTORE");
            if (!roleReady(restore)) return false;
            if (restore) {
                facts.put("status", "PASS");
                facts.put("originalControlRoleRestored", true);
                next("DONE");
                writeResult();
                reopen();
                return true;
            }
            facts.put("businessActorLevel2", false);
            facts.put("businessActorLevel4", false);
            next("SELECT");
        }
        if (stage.equals("SELECT")) {
            if (!UiModularSnapshot.supports(mc.currentScreen) || stageTicks < 8) return false;
            String path = UiModularSnapshot.namedPath(mc, mc.currentScreen, "device.filter.search");
            require(path != null, "actual search field absent");
            Map<String, Object> input = UiModularInput.type(mc, mc.currentScreen, path, "QA_TAIL_");
            facts.put("filterInput" + round, input);
            require(Boolean.TRUE.equals(input.get("observedTextMatchesRequest")), "real filter text input failed");
            next("FILTERED_SELECTION");
            return false;
        }
        if (stage.equals("FILTERED_SELECTION")) {
            if (stageTicks < 8) return false;
            if (UiModularSnapshot.namedPath(mc, mc.currentScreen, "device.row.0") == null) {
                require(stageTicks < 250, "fresh native terminal rows did not arrive");
                return false;
            }
            Object screen = UiModularSnapshot.wrappedScreen(mc.currentScreen);
            require(
                screen.getClass()
                    .getSimpleName()
                    .equals("GuiDeviceInfoTerminal"),
                "actual terminal required");
            Map<String, Object> input = click("device.row.0");
            facts.put("rowInput" + round, input);
            Entry entry = (Entry) UiDebugSnapshot.read(screen, "selectedDesktopEntry");
            require(entry != null, "real row input did not select device");
            DeviceBinding nextBinding = DesktopDeviceSources.binding(terminal, entry);
            require(
                nextBinding != null && (binding == null || binding.equals(nextBinding)),
                "selected binding mismatch");
            binding = nextBinding;
            facts.put("deviceKey", binding.key);
            facts.put("deviceSource", binding.source.toString());
            shot = "terminal-selected-" + round;
            next("BUTTON");
            return false;
        }
        if (stage.equals("BUTTON")) {
            if (shot != null || stageTicks < 3) return false;
            Map<String, Object> input = click("device.desktop.mount");
            facts.put("mountInput" + round, input);
            require(
                mc.currentScreen != null && mc.currentScreen.getClass()
                    .getSimpleName()
                    .equals("FactoryDashboardScreen"),
                "normal-view production host not installed");
            require(!EngineeringModeController.isActive(), "mount acquired a world camera");
            expectedPlacement = read("placement");
            require(expectedPlacement != null, "button callback lost placement synchronously");
            if (round == 0) baseline = layout().widgets()
                .size();
            require(
                layout().widgets()
                    .size() == baseline + (round == 0 ? 0 : 1),
                "mount inserted before click");
            next("PREVIEW");
            return false;
        }
        if (stage.equals("PREVIEW")) {
            require(read("placement") == expectedPlacement, "preview cancelled before natural first frame");
            return false; // First natural frame captures the ghost before application-event input.
        }
        if (stage.equals("AFTER")) {
            require(
                layout().widgets()
                    .size() == baseline + 1,
                "duplicate insertion or cancellation changed count");
            require(read("placement") == null, "old preview remained");
            if (draws < 3 || shot != null) return false;
            require(
                invoke(read("scene"), "find", "preset-placement") == null,
                "stale preview node remained after rendering");
            facts.put("previewNodeAbsent" + round, true);
            if (++round < 3) {
                reopen();
                next("SELECT");
            } else {
                facts.put("rightClickCancelledWithoutAddition", true);
                facts.put("escapeCancelledWithoutAddition", true);
                facts.put("previewAbsentAfterCancellation", true);
                invoke(workbench, "escape");
                next("DISCARD");
            }
            return false;
        }
        if (stage.equals("DISCARD")) {
            if (draws < 2) return false;
            Object scene = read("scene");
            Object button = invoke(scene, "find", "discard-exit");
            require(button != null, "real discard dialog absent");
            Object bounds = invoke(button, "bounds");
            int x = (int) (number(bounds, "x") + number(bounds, "width") / 2);
            int y = (int) (number(bounds, "y") + number(bounds, "height") / 2);
            mouse(x, y, 0, true);
            mouse(x, y, 0, false);
            require(
                layout().widgets()
                    .size() == baseline,
                "actual discard did not restore original layout");
            facts.put("draftDiscardRestoredCount", baseline);
            mc.displayGuiScreen(null);
            requestRole(true);
            next("WAIT_OP_RESTORE");
        }
        return false;
    }

    private void previewInput() throws Exception {
        require(roleReady(false), "ordinary actor role not retained");
        if (round == 0) {
            int[] point = emptyPoint();
            mouse(point[0], point[1], 0, true);
            mouse(point[0], point[1], 0, false);
            require(read("placement") == null, "one real complete canvas click did not commit");
            require(
                layout().widgets()
                    .size() == baseline + 1,
                "one click must add exactly one");
            WidgetSpec added = layout().find(String.valueOf(read("selected")));
            require(added != null && binding.equals(added.device), "committed card lost selected device binding");
            facts.put("committedWidget", added.id);
            facts.put("singleClickAdded", 1);
            shot = "committed-card";
        } else if (round == 1) {
            int[] point = center();
            mouse(point[0], point[1], 1, true);
            mouse(point[0], point[1], 1, false);
            require(read("placement") == null, "right click did not cancel");
            shot = "right-cancelled";
        } else {
            // Exact production handler used by FactoryDashboardScreen.keyTyped(Escape).
            invoke(workbench, "escape");
            require(read("placement") == null, "Escape did not cancel");
            shot = "escape-cancelled";
        }
        next("AFTER");
    }

    @SubscribeEvent
    public void drawn(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (event.gui == mc.currentScreen) drawn = true;
    }

    void render() throws Exception {
        if (!drawn) return;
        drawn = false;
        if (mc.currentScreen != null && mc.currentScreen.getClass()
            .getSimpleName()
            .equals("FactoryDashboardScreen")) {
            draws++;
            if (stage.equals("PREVIEW")) {
                if (draws == 1) capture("preview-first-frame-" + round);
                require(read("placement") == expectedPlacement, "first naturally drawn frame lost preview");
                DesktopPresetPlacement placement = (DesktopPresetPlacement) expectedPlacement;
                require(
                    placement.widgets()
                        .size() == 1 && binding.equals(
                            placement.widgets()
                                .get(0).device),
                    "rendered preview lost exact bound device");
                facts.put("previewFrames" + round, draws);
            }
        }
        if (shot != null) {
            capture(shot);
            shot = null;
        }
        if (stage.equals("PREVIEW") && draws >= 1) previewInput();
    }

    private void capture(String name) {
        ScreenShotHelper
            .saveScreenshot(directory, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        require(new File(directory, "screenshots/" + name + ".png").isFile(), "actual framebuffer PNG absent");
    }

    private Map<String, Object> click(String name) {
        String path = UiModularSnapshot.namedPath(mc, mc.currentScreen, name);
        require(path != null, "visible named button absent: " + name);
        Map<String, Object> input = UiModularInput.click(mc, mc.currentScreen, path);
        require("SENT".equals(input.get("status")), "screen dispatch denied: " + input);
        return input;
    }

    private void reopen() {
        mc.displayGuiScreen(null);
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
    }

    private int[] center() throws Exception {
        Object canvas = read("canvas");
        return new int[] { (int) (number(canvas, "x") + number(canvas, "w") / 2),
            (int) (number(canvas, "y") + number(canvas, "h") / 2) };
    }

    private int[] emptyPoint() throws Exception {
        Object canvas = read("canvas");
        int x = (int) number(canvas, "x"), y = (int) number(canvas, "y");
        int w = (int) number(canvas, "w"), h = (int) number(canvas, "h");
        for (int pan = 0; pan < 4; pan++) {
            for (int py = y + 96; py < y + h - 128; py += 24) for (int px = x + 96; px < x + w - 128; px += 24) {
                mouse(px, py, -1, false);
                if (((DesktopPresetPlacement) expectedPlacement).valid(layout())) return new int[] { px, py };
            }
            int[] c = center();
            mouse(c[0], c[1], 2, true);
            mouse(c[0] + w / 2, c[1] + h / 2, -1, false);
            mouse(c[0] + w / 2, c[1] + h / 2, 2, false);
        }
        throw new IllegalStateException("No valid visible placement after native pan");
    }

    private void mouse(int x, int y, int button, boolean pressed) throws Exception {
        require(EngineeringModeController.dashboardHostVisible(), "production dashboard host absent");
        facts.put(
            "applicationInputBoundary",
            "FactoryDashboard.mouse after OS focus sampling; no global input or Display mutation");
        facts.put("physicalOsInput", "NOT_RUN");
        invoke(
            Class.forName("com.miaokatze.gtswn.client.engineering.FactoryDashboard"),
            "mouse",
            x,
            y,
            button,
            pressed,
            0);
    }

    private void requestRole(boolean restore) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hostRunId", facts.get("hostRunId"));
        value.put("businessRunId", run);
        value.put(
            "actorUuid",
            mc.thePlayer.getUniqueID()
                .toString());
        value.put("restore", restore);
        Files.write(
            new File(control, "mount-control.json").toPath(),
            new Gson().toJson(value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private boolean roleReady(boolean restore) throws Exception {
        File file = new File(control, restore ? "mount-role-restored.json" : "mount-role-ordinary.json");
        if (!file.isFile()) return false;
        @SuppressWarnings("unchecked")
        Map<String, Object> value = new Gson()
            .fromJson(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), Map.class);
        require(
            facts.get("hostRunId")
                .equals(value.get("hostRunId")) && run.equals(value.get("businessRunId"))
                && mc.thePlayer.getUniqueID()
                    .toString()
                    .equals(value.get("actorUuid")),
            "role receipt identity mismatch");
        require(
            Boolean.valueOf(restore)
                .equals(value.get("actorLevel2")) && Boolean.FALSE.equals(value.get("actorLevel4")),
            "role receipt permission mismatch");
        facts.put(restore ? "roleRestorationReceipt" : "businessRoleReceipt", value);
        return true;
    }

    private DesktopLayout layout() throws Exception {
        return (DesktopLayout) read("layout");
    }

    private Object read(String name) throws Exception {
        return field(desktop, name);
    }

    private static double number(Object object, String name) throws Exception {
        return ((Number) field(object, name)).doubleValue();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = (owner instanceof Class<?> ? (Class<?>) owner : owner.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner instanceof Class<?> ? null : owner);
    }

    private static Object invoke(Object owner, String name, Object... args) throws Exception {
        Class<?> type = owner instanceof Class<?> ? (Class<?>) owner : owner.getClass();
        for (Method method : type.getDeclaredMethods()) if (method.getName()
            .equals(name) && method.getParameterTypes().length == args.length) {
                method.setAccessible(true);
                return method.invoke(owner instanceof Class<?> ? null : owner, args);
            }
        throw new NoSuchMethodException(name);
    }

    private void writeResult() throws Exception {
        Files.write(
            new File(directory, "result.json").toPath(),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(result())
                .getBytes(StandardCharsets.UTF_8));
    }

    void close() {
        MinecraftForge.EVENT_BUS.unregister(this);
        if (!"PASS".equals(facts.get("status"))) facts.put("status", "FAIL");
        try {
            requestRole(true);
            writeResult();
        } catch (Exception failure) {
            facts.put("recoveryRequestError", failure.toString());
        }
    }
}
