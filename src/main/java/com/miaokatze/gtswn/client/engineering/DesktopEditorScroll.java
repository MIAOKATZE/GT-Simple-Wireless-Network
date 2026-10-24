package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.miaokatzecore.api.ui.component.UiNode;
import com.miaokatze.miaokatzecore.api.ui.component.UiScene;

/** Transient editor positions survive rebuilding controls for the same selected component. */
final class DesktopEditorScroll {

    private double library, properties;
    private String selected = "";

    void capture(UiScene scene, String selection) {
        UiNode list = scene == null ? null : scene.find("library-list");
        if (list != null) library = list.scrollY();
        if (!selected.equals(selection)) properties = 0;
        else {
            UiNode body = scene == null ? null : scene.find("properties-body");
            if (body != null) properties = body.scrollY();
        }
        selected = selection;
    }

    /** The host lays out the new tree first so Core can clamp against its actual content extent. */
    void restore(UiScene scene) {
        UiNode list = scene.find("library-list"), body = scene.find("properties-body");
        if (list != null) list.scrollTo(library);
        if (body != null) body.scrollTo(properties);
    }
}
