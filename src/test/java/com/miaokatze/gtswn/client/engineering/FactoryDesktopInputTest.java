package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

import org.junit.After;
import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDrag;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopStore;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopViewport;
import com.miaokatze.gtswn.client.engineering.desktop.DeviceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetResources;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.api.ui.component.UiEvent;
import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;

/** Real Core pointer capture/bubbling into production desktop handlers, without rendering or starting Minecraft. */
public class FactoryDesktopInputTest {

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
        set("layout", layout);
        set("view", viewport);
        set("canvas", new Rect(0, 0, 1400, 1000));
        set("area", new Rect(0, 0, 1400, 1000));
        set("scene", scene);
        set("selected", selected ? "card" : "");
        set("edit", editing);
        set("bindingOpen", false);
        set("screenBindingOpen", false);
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

    private static void dispatch(String name, UiEvent event) {
        try {
            Method method = FactoryDesktop.class.getDeclaredMethod(name, UiEvent.class);
            method.setAccessible(true);
            method.invoke(null, event);
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
