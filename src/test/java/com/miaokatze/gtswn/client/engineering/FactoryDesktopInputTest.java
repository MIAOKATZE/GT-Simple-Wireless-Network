package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import org.junit.After;
import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPreset;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopPresetCode;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopStore;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetResources;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;

/** Real Core pointer capture/bubbling into production desktop handlers, without rendering or starting Minecraft. */
public class FactoryDesktopInputTest {

    private static boolean controlPressed;
    private DesktopLayout layout;
    private UiScene scene;

    private void prepare(boolean editing, boolean selected) throws Exception {
        prepare(editing, selected, WidgetKind.DEVICE);
    }

    private void prepare(boolean editing, boolean selected, WidgetKind kind) throws Exception {
        layout = new DesktopLayout();
        WidgetSpec card = new WidgetSpec("card", kind, 0, 0, Math.max(6, kind.minColumns), Math.max(5, kind.minRows));
        assertTrue(layout.add(card));
        DesktopViewport viewport = new DesktopViewport();
        viewport.scale = 1;
        Rect bounds = DesktopCardGeometry.bounds(card, viewport.transform(0, 0));
        UiNode canvas = UiNode.panel("canvas")
            .bounds(0, 0, 1400, 1000)
            .overflowClip(true)
            .on(UiEvent.Type.DOWN, e -> dispatch("down", e))
            .on(UiEvent.Type.MOVE, e -> dispatch("move", e))
            .on(UiEvent.Type.UP, e -> dispatch("up", e))
            .on(UiEvent.Type.CANCEL, e -> FactoryDesktop.blur());
        canvas.add(
            UiNode.panel("card-card")
                .bounds(bounds.x, bounds.y, bounds.w, bounds.h)
                .overflowClip(true)
                .add(
                    UiNode.panel("selection-card")
                        .bounds(0, 0, bounds.w, bounds.h)
                        .pointerTransparent(true)));
        scene = new UiScene(canvas);
        scene.layout(1400, 1000);
        controlPressed = false;
        set("layout", layout);
        set("saves", new com.miaokatze.gtswn.client.engineering.desktop.DesktopSaveSession(layout));
        ((com.miaokatze.gtswn.client.engineering.desktop.DesktopSelection) get("selection")).only("");
        set("pendingExit", null);
        set("shareOpen", false);
        set("libraryOpen", false);
        set("view", viewport);
        set("canvas", new Rect(0, 0, 1400, 1000));
        set("area", new Rect(0, 0, 1400, 1000));
        set("scene", scene);
        set("selected", selected ? "card" : "");
        set("edit", editing);
        set("bindingOpen", false);
        set("pendingPreset", null);
        set("pendingImport", null);
        set("presetDraft", "");
        set("importingPreset", false);
        set("initialView", false);
        set("notice", "");
        set("screenBindingOpen", false);
        set("resourceAdding", false);
        set("choosingDeviceSource", false);
        set("propertiesOpen", false);
        set("store", new DesktopStore(null));
        set("profile", DesktopStore.profile("headless", "player"));
        set("screenWidth", 1400);
        set("screenHeight", 1000);
        set("pickerFilter", FactoryMachineFilter.ALL);
        set("pickerPower", com.miaokatze.gtswn.client.DevicePowerFilter.ALL);
        set("pickerReason", "");
        ((FactoryTextSearch) get("deviceSearch")).value = "";
    }

    @After
    public void resetCapture() throws Exception {
        if (scene != null) scene.blur();
        set("scene", null);
    }

    @Test
    public void everyVisibleHandleResizesImmediatelyThroughActualSceneCapture() throws Exception {
        for (boolean editing : new boolean[] { false, true }) for (DesktopDrag.Edge edge : DesktopDrag.Edge.values()) {
            if (edge == DesktopDrag.Edge.MOVE) continue;
            prepare(editing, true);
            Rect bounds = DesktopCardGeometry
                .bounds(layout.find("card"), ((DesktopViewport) get("view")).transform(0, 0));
            String name = edge.name();
            double x = name.contains("LEFT") ? bounds.x + 3
                : name.contains("RIGHT") ? bounds.x + bounds.w - 3 : bounds.x + bounds.w / 2.0;
            double y = name.contains("TOP") ? bounds.y + 3
                : name.contains("BOTTOM") ? bounds.y + bounds.h - 3 : bounds.y + bounds.h / 2.0;
            double dx = name.contains("LEFT") ? -64 : name.contains("RIGHT") ? 64 : 0;
            double dy = name.contains("TOP") ? -64 : name.contains("BOTTOM") ? 64 : 0;
            assertEquals(edge, DesktopCardGeometry.edge(bounds, x, y));
            assertTrue(scene.pointerDown(x, y, 0));
            assertEquals("canvas", scene.captured().id);
            assertTrue(scene.pointerMove(x + dx, y + dy));
            assertTrue(scene.pointerUp(x + dx, y + dy, 0));
            assertNull(scene.captured());
            assertEquals(edge.name(), dx == 0 ? 6 : 7, layout.find("card").columns);
            assertEquals(edge.name(), dy == 0 ? 5 : 6, layout.find("card").rows);
            scene.close();
        }
    }

    @Test
    public void firstClickSelectsThenTitleDragMovesAndCaptureLossRestoresGeometry() throws Exception {
        prepare(true, false);
        assertTrue(scene.pointerDown(100, 14, 0));
        assertTrue(scene.pointerUp(100, 14, 0));
        assertEquals("card", get("selected"));
        WidgetSpec original = layout.find("card");
        assertTrue(scene.pointerDown(100, 14, 0));
        assertTrue(scene.pointerMove(228, 14));
        assertEquals(2, layout.find("card").column);
        scene.blur();
        assertSame(original, layout.find("card"));
        assertNull(scene.captured());
        scene.pointerUp(228, 14, 0);
        assertSame(original, layout.find("card"));
    }

    @Test
    public void deviceSelectorBuildsAtMostSixteenRowsAndClampsPageWhenTheLiveListShrinks() throws Exception {
        prepare(true, true);
        set("bindingOpen", true);
        set("pickerSource", UUID.randomUUID());
        Entry entry = new Entry(
            "key",
            "Machine",
            (byte) 1,
            (byte) 0,
            1,
            2,
            1,
            2,
            0,
            1,
            2,
            3,
            "",
            "",
            4,
            (byte) 2,
            (byte) 0);
        set("pickerEntries", Collections.nCopies(4096, entry));
        set("bindingPage", 255);
        UiNode body = UiNode.panel("body");
        Method method = FactoryDesktop.class
            .getDeclaredMethod("addDeviceBinding", UiNode.class, WidgetSpec.class, int.class);
        method.setAccessible(true);
        method.invoke(null, body, layout.find("card"), 210);
        assertEquals(
            16,
            body.children()
                .stream()
                .filter(node -> node.id.startsWith("binding-device-"))
                .count());
        assertTrue(
            body.children()
                .stream()
                .anyMatch(node -> node.id.equals("binding-device-4095")));
        UiNode pages = body.children()
            .stream()
            .filter(node -> node.id.equals("binding-pages"))
            .findFirst()
            .get();
        assertTrue(
            pages.children()
                .stream()
                .filter(node -> node.id.equals("binding-previous"))
                .findFirst()
                .get()
                .enabled());
        assertFalse(
            pages.children()
                .stream()
                .filter(node -> node.id.equals("binding-next"))
                .findFirst()
                .get()
                .enabled());
        set("pickerEntries", Collections.nCopies(3, entry));
        UiNode smaller = UiNode.panel("smaller");
        method.invoke(null, smaller, layout.find("card"), 210);
        assertEquals(0, get("bindingPage"));
        assertEquals(
            3,
            smaller.children()
                .stream()
                .filter(node -> node.id.startsWith("binding-device-"))
                .count());
    }

    @Test
    public void existingDeviceBindingDoesNotForceBrowseActivationIntoTheEditor() throws Exception {
        prepare(false, false);
        assertTrue(
            layout.replace(
                layout.find("card")
                    .boundDevice(new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4))));
        scene.pointerDown(100, 100, 0);
        scene.pointerUp(100, 100, 0);
        scene.pointerDown(100, 100, 0);
        scene.pointerUp(100, 100, 0);
        assertFalse((Boolean) get("edit"));
    }

    @Test
    public void searchResetsLastPageAndKeepsTypingFocusAcrossActualDesktopRebuild() throws Exception {
        prepare(true, true);
        set("bindingOpen", true);
        set("propertiesOpen", true);
        set("libraryOpen", false);
        set("pickerSource", UUID.randomUUID());
        set(
            "pickerEntries",
            Collections.singletonList(
                new Entry(
                    "machine",
                    "Machine",
                    (byte) 1,
                    (byte) 0,
                    1,
                    0,
                    1,
                    0,
                    0,
                    1,
                    2,
                    3,
                    "",
                    "",
                    4,
                    (byte) 0,
                    (byte) 0)));
        Method build = FactoryDesktop.class.getDeclaredMethod("build");
        build.setAccessible(true);
        build.invoke(null);
        scene = (UiScene) get("scene");
        scene.focus(scene.find("device-search"));
        set("bindingPage", 255);
        assertTrue(scene.keyTyped('m', 50, false, false));
        assertEquals(0, get("bindingPage"));
        build.invoke(null);
        scene = (UiScene) get("scene");
        assertEquals("device-search", scene.focused().id);
        assertTrue(FactoryDesktop.typing());
        assertTrue(FactoryDesktop.key(org.lwjgl.input.Keyboard.KEY_DELETE, '\0', false));
        assertNotNull(layout.find("card"));
        assertTrue(scene.keyTyped('a', 30, false, false));
        assertEquals("ma", ((FactoryTextSearch) get("deviceSearch")).value);
        scene.keyTyped('z', 44, false, false);
        build.invoke(null);
        scene = (UiScene) get("scene");
        assertNotNull(scene.find("device-search-empty"));
        assertNull(scene.find("binding-device-0"));
        assertEquals("device-search", scene.focused().id);
    }

    @Test
    public void emptyResourceCardFirstClickSelectsAndSecondClickOpensItsBindingEditor() throws Exception {
        WidgetResources.update(null, Collections.emptyList());
        prepare(false, false, WidgetKind.RESOURCES);
        set("propertiesOpen", false);
        set("screenBindingOpen", false);
        assertTrue(scene.pointerDown(100, 100, 0));
        assertTrue(scene.pointerUp(100, 100, 0));
        assertEquals("card", get("selected"));
        assertFalse((Boolean) get("edit"));
        assertFalse((Boolean) get("screenBindingOpen"));
        assertTrue(scene.pointerDown(100, 100, 0));
        assertTrue(scene.pointerUp(100, 100, 0));
        assertTrue((Boolean) get("edit"));
        assertTrue((Boolean) get("propertiesOpen"));
        assertTrue((Boolean) get("screenBindingOpen"));
        assertEquals("card", get("selected"));
    }

    @Test
    public void browseModeResizesFromSelectedHandles() throws Exception {
        prepare(false, true);
        assertTrue(scene.pointerDown(377, 313, 0));
        assertTrue(scene.pointerMove(441, 377));
        assertTrue(scene.pointerUp(441, 377, 0));
        assertEquals(7, layout.find("card").columns);
        assertEquals(6, layout.find("card").rows);
    }

    @Test
    public void handleTapDoesNotActivateTheEmptyMachineBinding() throws Exception {
        prepare(false, true);
        scene.pointerDown(377, 313, 0);
        scene.pointerUp(377, 313, 0);
        assertFalse((Boolean) get("edit"));
        assertFalse((Boolean) get("bindingOpen"));
        assertEquals(6, layout.find("card").columns);
        assertEquals(5, layout.find("card").rows);
    }

    @Test
    public void terminalGridAndEuTrendDoNotOpenScreenSelectionOnSecondClick() throws Exception {
        for (WidgetKind kind : new WidgetKind[] { WidgetKind.WIRELESS, WidgetKind.HISTORY }) {
            prepare(false, false, kind);
            scene.pointerDown(100, 100, 0);
            scene.pointerUp(100, 100, 0);
            scene.pointerDown(100, 100, 0);
            scene.pointerUp(100, 100, 0);
            assertFalse((Boolean) get("screenBindingOpen"));
            assertFalse((Boolean) get("edit"));
            scene.close();
        }
    }

    @Test
    public void presetClickSplitsOnlyItsOriginalRowWithoutChangingDesktop() throws Exception {
        prepare(true, true);
        set("libraryOpen", true);
        rebuild();
        UiNode original = scene.find("preset-POWER");
        double originalY = original.bounds().y;
        double originalHeight = original.bounds().height;
        activate("preset-POWER");
        assertSame(layout, get("layout"));
        assertEquals(
            1,
            layout.widgets()
                .size());
        rebuild();
        assertNull(scene.find("preset-POWER"));
        assertNull(scene.find("preset-confirm"));
        assertNull(scene.find("apply-preset"));
        UiNode left = scene.find("preset-POWER-replace"), right = scene.find("preset-POWER-append");
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(originalY, left.bounds().y, 0);
        assertEquals(originalY, right.bounds().y, 0);
        assertEquals(originalHeight, left.bounds().height, 0);
        assertTrue(
            left.bounds()
                .right() < right.bounds().x);
        assertNotNull(scene.find("preset-PRODUCTION"));
    }

    @Test
    public void importedCodeValidationIsReadOnlyAndAppendPreservesExistingCardAndZoom() throws Exception {
        prepare(true, true, WidgetKind.WIRELESS);
        DesktopLayout template = new DesktopLayout();
        assertNotNull(template.add(WidgetKind.WIRELESS));
        set("shareOpen", true);
        set("importingPreset", true);
        set("presetDraft", DesktopPresetCode.export(template));
        rebuild();
        WidgetSpec original = layout.find("card");
        activate("preset-validate");
        assertSame(layout, get("layout"));
        assertSame(original, layout.find("card"));
        DesktopLayout pending = (DesktopLayout) get("pendingImport");
        assertNotNull(pending);
        for (WidgetSpec widget : pending.widgets()) {
            assertNull(widget.device);
            assertNull(widget.screen);
            assertNull(widget.resource);
            assertFalse(widget.autoDevicePending);
            assertFalse(widget.autoResourcePending);
        }
        rebuild();
        activate("preset-import-append");
        assertSame(layout, get("layout"));
        assertSame(original, layout.find("card"));
        assertEquals(
            2,
            layout.widgets()
                .size());
        assertEquals(1, ((DesktopViewport) get("view")).scale, 0);
        assertNull(get("pendingImport"));
        assertFalse((Boolean) get("importingPreset"));
    }

    @Test
    public void invalidImportAndCancelKeepTheCurrentDesktop() throws Exception {
        prepare(true, true);
        set("shareOpen", true);
        set("importingPreset", true);
        set("presetDraft", "invalid");
        rebuild();
        activate("preset-validate");
        assertSame(layout, get("layout"));
        assertEquals(
            1,
            layout.widgets()
                .size());
        assertNull(get("pendingImport"));
        rebuild();
        assertNotNull(scene.find("preset-validate"));
        assertNull(scene.find("preset-import-replace"));
        activate("preset-import-cancel");
        assertFalse((Boolean) get("importingPreset"));
        assertEquals("", get("presetDraft"));
        assertSame(layout, get("layout"));
    }

    @Test
    public void presetCodeAllowsLongSingleLineDraftAndRetainsFocusAcrossRebuild() throws Exception {
        prepare(true, true);
        set("shareOpen", true);
        set("importingPreset", true);
        rebuild();
        UiNode input = scene.find("preset-code");
        scene.focus(input);
        String longCode = String.join("", Collections.nCopies(4096, "A"));
        assertTrue(scene.insertText(longCode));
        assertEquals(longCode, get("presetDraft"));
        assertFalse((Boolean) get("dirty"));
        rebuild();
        assertEquals("preset-code", scene.focused().id);
        assertEquals(
            longCode,
            scene.focused()
                .text());
        assertEquals(
            23,
            scene.focused()
                .bounds().height,
            0);
        assertTrue(FactoryDesktop.escape());
        assertFalse(FactoryDesktop.typing());
        assertTrue((Boolean) get("importingPreset"));
        assertTrue(FactoryDesktop.escape());
        assertFalse((Boolean) get("importingPreset"));
        assertTrue((Boolean) get("shareOpen"));
    }

    @Test
    public void importedOverwriteReplacesTheDesktopOnlyAfterTheExplicitChoice() throws Exception {
        prepare(true, true);
        set("shareOpen", true);
        set("importingPreset", true);
        set("presetDraft", DesktopPresetCode.export(DesktopPreset.POWER.createLayout()));
        rebuild();
        activate("preset-validate");
        assertNotNull(layout.find("card"));
        rebuild();
        activate("preset-import-replace");
        DesktopLayout replaced = (DesktopLayout) get("layout");
        assertNotSame(layout, replaced);
        assertNull(replaced.find("card"));
        assertEquals(
            DesktopPreset.POWER.createLayout()
                .widgets()
                .size(),
            replaced.widgets()
                .size());
    }

    @Test
    public void effectsEditorCyclesStyleAndAnimationAndValidatesAllParametersTogether() throws Exception {
        prepare(true, true, WidgetKind.WIRELESS);
        set("propertiesOpen", true);
        set("libraryOpen", false);
        rebuild();
        activate("effects-style");
        assertEquals(WidgetEffects.Style.SIGNAL, layout.find("card").effects.style);
        rebuild();
        activate("effects-animation");
        assertFalse(layout.find("card").effects.animated);
        rebuild();
        scene.find("effects-speed")
            .text("4");
        scene.find("effects-intensity")
            .text("0.5");
        scene.find("effects-accent")
            .text("FFAA66");
        scene.find("effects-glow")
            .text("1.6");
        activate("effects-apply");
        assertEquals(1, layout.find("card").effects.speed, 0);
        scene.find("effects-speed")
            .text("2.5");
        activate("effects-apply");
        WidgetEffects applied = layout.find("card").effects;
        assertEquals(2.5, applied.speed, 0);
        assertEquals(0.5, applied.intensity, 0);
        assertEquals(0xFFFFAA66, applied.accentColor);
        assertEquals(1.6, applied.glow, 0);
        assertEquals(WidgetEffects.Style.SIGNAL, applied.style);
        assertFalse(applied.animated);
    }

    @Test
    public void toolbarWrapsWithinTheShellAndReservesSpaceForItsTitle() throws Exception {
        for (boolean editing : new boolean[] { false, true }) {
            for (int width : new int[] { 80, 160, 240, 320, 640, 1400 }) {
                DesktopToolbarGeometry geometry = new DesktopToolbarGeometry(width, editing);
                for (UiRect control : geometry.controls.values()) {
                    assertTrue(control.x >= 0);
                    assertTrue(control.right() <= width);
                    assertTrue(control.bottom() <= geometry.height);
                    assertTrue(control.width >= 24);
                    assertTrue(control.height >= 24);
                    assertTrue(control.y >= geometry.title.bottom() || control.x >= geometry.title.right());
                    for (UiRect other : geometry.controls.values()) {
                        if (control == other) continue;
                        assertTrue(
                            control.intersect(other)
                                .empty());
                    }
                }
            }
        }
        prepare(true, true);
        set("area", new Rect(0, 0, 240, 300));
        set("screenWidth", 240);
        set("screenHeight", 300);
        rebuild();
        UiNode back = scene.find("back-world");
        assertEquals("", back.text());
        assertEquals(24, back.bounds().width, 0);
        assertTrue(
            ((Rect) get("canvas")).y >= back.bounds()
                .bottom());
        assertNotNull(scene.find("theme"));
    }

    @Test
    public void themeButtonCyclesEveryPaletteAndPreservesBindingsAndViewport() throws Exception {
        prepare(true, true);
        WidgetSpec widget = layout.find("card")
            .boundDevice(new DeviceBinding(UUID.randomUUID(), "machine", 0, 1, 2, 3, 4));
        layout.replace(widget);
        DesktopViewport viewport = (DesktopViewport) get("view");
        viewport.panX = 123;
        viewport.panY = -45;
        viewport.scale = .75;
        set("libraryOpen", false);
        for (DashboardTheme expected : new DashboardTheme[] { DashboardTheme.RETRO, DashboardTheme.INDUSTRIAL,
            DashboardTheme.MINIMAL, DashboardTheme.STANDARD }) {
            rebuild();
            activate("theme");
            assertEquals(expected, layout.theme());
            assertSame(layout, get("layout"));
            assertSame(widget, layout.find("card"));
            assertEquals(123, viewport.panX, 0);
            assertEquals(-45, viewport.panY, 0);
            assertEquals(.75, viewport.scale, 0);
        }
    }

    @Test
    public void componentLibraryAndBindingActionsKeepTheirStableInputIdsAndHoverDescriptions() throws Exception {
        prepare(true, true);
        set("libraryOpen", true);
        rebuild();
        assertEquals(
            "",
            scene.find("add-WIRELESS")
                .text());
        assertTrue(((java.util.Map<?, ?>) get("tooltips")).containsKey("add-WIRELESS"));
        int before = layout.widgets()
            .size();
        activate("add-WIRELESS");
        assertEquals(
            before + 1,
            layout.widgets()
                .size());
        set("selected", "card");
        set("libraryOpen", false);
        set("propertiesOpen", true);
        rebuild();
        assertEquals(
            "",
            scene.find("device-binding")
                .text());
        assertTrue(((java.util.Map<?, ?>) get("tooltips")).containsKey("device-binding"));
        // The real binding action queries carried terminals. Supply a no-player client holder only
        // for this headless event test; no Minecraft constructor, display or network is started.
        Field minecraftSingleton = Minecraft.class.getDeclaredField("theMinecraft");
        minecraftSingleton.setAccessible(true);
        Object previousMinecraft = minecraftSingleton.get(null);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeSingleton = unsafeClass.getDeclaredField("theUnsafe");
        unsafeSingleton.setAccessible(true);
        Object emptyMinecraft = unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafeSingleton.get(null), Minecraft.class);
        try {
            minecraftSingleton.set(null, emptyMinecraft);
            activate("device-binding");
            assertTrue((Boolean) get("bindingOpen"));
            rebuild();
            assertTrue(
                scene.find("device-binding")
                    .selected());
            assertNotNull(scene.find("binding-no-source"));
            assertTrue(((java.util.Map<?, ?>) get("tooltips")).containsKey("device-binding"));
        } finally {
            minecraftSingleton.set(null, previousMinecraft);
        }
    }

    @Test
    public void ctrlMarqueeAndClickSelectThenMoveTheWholeGroupAndCancelRestoresAll() throws Exception {
        prepare(true, false);
        WidgetSpec second = new WidgetSpec("second", WidgetKind.DEVICE, 7, 0, 3, 3);
        assertTrue(layout.add(second));
        ((com.miaokatze.gtswn.client.engineering.desktop.DesktopSaveSession) get("saves")).accept(layout);
        controlPressed = true;
        assertTrue(scene.pointerDown(800, 400, 0));
        assertTrue(scene.pointerMove(0, 0));
        assertTrue(scene.pointerUp(0, 0, 0));
        com.miaokatze.gtswn.client.engineering.desktop.DesktopSelection selected = (com.miaokatze.gtswn.client.engineering.desktop.DesktopSelection) get(
            "selection");
        assertEquals(
            2,
            selected.ids()
                .size());
        controlPressed = false;
        WidgetSpec first = layout.find("card");
        assertTrue(scene.pointerDown(100, 100, 0));
        assertTrue(scene.pointerMove(164, 164));
        assertEquals(1, layout.find("card").column);
        assertEquals(8, layout.find("second").column);
        scene.blur();
        assertSame(first, layout.find("card"));
        assertSame(second, layout.find("second"));
        assertFalse(FactoryDesktop.hasUnsavedChanges());
        controlPressed = true;
        assertTrue(scene.pointerDown(100, 100, 0));
        assertTrue(scene.pointerUp(100, 100, 0));
        assertFalse(
            selected.ids()
                .contains("card"));
        assertTrue(
            selected.ids()
                .contains("second"));
    }

    @Test
    public void unsavedExitModalBlocksUnderlyingToolsAndSupportsCancelDiscardAndSave() throws Exception {
        prepare(true, true);
        assertTrue(layout.move("card", 2, 0));
        int[] exits = { 0 };
        FactoryDesktop.requestExit(() -> exits[0]++);
        rebuild();
        assertNotNull(scene.find("save-modal"));
        assertEquals(0, exits[0]);
        UiNode theme = scene.find("theme");
        scene.focus(theme);
        assertNull(scene.focused());
        activate("cancel-exit");
        assertNull(get("pendingExit"));
        assertTrue(FactoryDesktop.hasUnsavedChanges());
        FactoryDesktop.requestExit(() -> exits[0]++);
        rebuild();
        activate("discard-exit");
        assertEquals(1, exits[0]);
        assertEquals(0, ((DesktopLayout) get("layout")).find("card").column);
        assertFalse(FactoryDesktop.hasUnsavedChanges());
        DesktopLayout draft = (DesktopLayout) get("layout");
        assertTrue(draft.move("card", 3, 0));
        FactoryDesktop.requestExit(() -> exits[0]++);
        rebuild();
        activate("save-exit");
        assertEquals(2, exits[0]);
        assertFalse(FactoryDesktop.hasUnsavedChanges());
        assertEquals(
            3,
            ((DesktopStore) get("store")).load((String) get("profile"))
                .find("card").column);
    }

    @Test
    public void failedExplicitSaveKeepsTheModalDraftAndCallbackPending() throws Exception {
        prepare(true, true);
        java.io.File blockedDirectory = java.io.File.createTempFile("gtswn-save-", ".blocked");
        try {
            set("store", new DesktopStore(blockedDirectory));
            assertTrue(layout.move("card", 2, 0));
            int[] exits = { 0 };
            FactoryDesktop.requestExit(() -> exits[0]++);
            rebuild();
            activate("save-exit");
            assertEquals(0, exits[0]);
            assertTrue(FactoryDesktop.hasUnsavedChanges());
            assertNotNull(get("pendingExit"));
            assertTrue(((DesktopStore) get("store")).saveIfDue(System.nanoTime()));
            assertEquals(2, layout.find("card").column);
            rebuild();
            assertNotNull(scene.find("save-feedback"));
        } finally {
            assertTrue(blockedDirectory.delete());
        }
    }

    @Test
    public void shareHasItsOwnToolbarPanelAndViewportAutosaveNeverStoresADraft() throws Exception {
        prepare(true, true);
        DesktopStore store = (DesktopStore) get("store");
        String profile = (String) get("profile");
        store.put(profile, layout);
        store.flush();
        assertTrue(layout.move("card", 3, 0));
        rebuild();
        activate("share");
        rebuild();
        assertNotNull(scene.find("share-panel"));
        assertNotNull(scene.find("preset-export"));
        assertNull(scene.find("library"));
        FactoryDesktop.flush();
        assertTrue(store.saveIfDue(System.nanoTime()));
        assertEquals(
            0,
            store.load(profile)
                .find("card").column);
        assertTrue(FactoryDesktop.hasUnsavedChanges());
    }

    private void rebuild() throws Exception {
        Method build = FactoryDesktop.class.getDeclaredMethod("build");
        build.setAccessible(true);
        build.invoke(null);
        scene = (UiScene) get("scene");
    }

    private void activate(String id) {
        UiNode button = scene.find(id);
        assertNotNull(id, button);
        scene.focus(button);
        assertTrue(scene.keyTyped('\0', 28, false, false));
    }

    private static void dispatch(String name, UiEvent event) {
        try {
            Method method = controlPressed && name.equals("down")
                ? FactoryDesktop.class.getDeclaredMethod(name, UiEvent.class, boolean.class)
                : FactoryDesktop.class.getDeclaredMethod(name, UiEvent.class);
            method.setAccessible(true);
            if (controlPressed && name.equals("down")) method.invoke(null, event, true);
            else method.invoke(null, event);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object get(String name) throws Exception {
        Field field = FactoryDesktop.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = FactoryDesktop.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
