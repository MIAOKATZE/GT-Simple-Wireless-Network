package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;

public class DesktopEditorScrollTest {

    private UiScene editor() {
        UiNode root = UiNode.panel("root");
        for (String id : new String[] { "library-list", "properties-body" }) {
            UiNode list = UiNode.panel(id)
                .bounds(0, 0, 150, 151)
                .scrollable(true);
            list.add(
                UiNode.panel(id + "-content")
                    .size(150, 500));
            root.add(list);
        }
        UiScene scene = new UiScene(root);
        scene.layout(640, 360);
        return scene;
    }

    @Test
    public void rebuildingFieldAndColumnControlsKeepsBothEditorPositions() {
        DesktopEditorScroll positions = new DesktopEditorScroll();
        UiScene old = editor();
        positions.capture(null, "device");
        old.find("library-list")
            .scrollTo(92);
        old.find("properties-body")
            .scrollTo(270);
        positions.capture(old, "device");
        old.close();
        UiScene rebuilt = editor();
        positions.restore(rebuilt);
        assertEquals(
            92,
            rebuilt.find("library-list")
                .scrollY(),
            0);
        assertEquals(
            270,
            rebuilt.find("properties-body")
                .scrollY(),
            0);
    }

    @Test
    public void selectingAnotherComponentResetsOnlyItsProperties() {
        DesktopEditorScroll positions = new DesktopEditorScroll();
        UiScene old = editor();
        positions.capture(null, "first");
        old.find("library-list")
            .scrollTo(92);
        old.find("properties-body")
            .scrollTo(270);
        positions.capture(old, "second");
        UiScene rebuilt = editor();
        positions.restore(rebuilt);
        assertEquals(
            92,
            rebuilt.find("library-list")
                .scrollY(),
            0);
        assertEquals(
            0,
            rebuilt.find("properties-body")
                .scrollY(),
            0);
    }
}
