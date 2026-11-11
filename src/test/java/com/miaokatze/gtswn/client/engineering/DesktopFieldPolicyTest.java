// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.util.Collections;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.desktop.DesktopLayout;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopStore;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;

public class DesktopFieldPolicyTest {

    @Test
    public void retiredRecipesAreNotOfferedButOldLayoutsRemainReadable() {
        WidgetSpec device = new WidgetSpec("device", WidgetKind.DEVICE, 0, 0, 3, 3);
        assertFalse(DesktopFieldPolicy.show(device, "recipeInput"));
        assertFalse(DesktopFieldPolicy.show(device, "recipeOutput"));
        assertFalse(
            WidgetSpec.availableFields(WidgetKind.DEVICE)
                .contains("recipeInput"));
        assertFalse(WidgetSpec.AVAILABLE_FIELDS.contains("recipeOutput"));
        DesktopLayout saved = new DesktopLayout();
        assertTrue(saved.add(device.configured("", java.util.Arrays.asList("recipeInput", "recipeOutput"), 1)));
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(saved))
            .find("device");
        assertEquals(java.util.Arrays.asList("recipeInput", "recipeOutput"), restored.fields);
        assertFalse(
            DesktopFieldPolicy.show(device.configured("", Collections.singletonList("status"), 1), "recipeInput"));
    }

    @Test
    public void explicitEmptySurvivesTitleBoundsAndPersistenceWithoutRestoringDefaults() {
        WidgetSpec original = new WidgetSpec("input", WidgetKind.INPUT, 0, 0, 4, 2);
        assertTrue(DesktopFieldPolicy.show(original, "input"));
        WidgetSpec empty = original.configured("", Collections.<String>emptyList(), 1)
            .titled("Custom")
            .withBounds(2, 3, 4, 2);
        assertTrue(empty.fieldsConfigured);
        assertTrue(
            DesktopFieldPolicy.fields(empty)
                .isEmpty());
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(empty));
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
            .find("input");
        assertTrue(restored.fieldsConfigured);
        assertTrue(
            DesktopFieldPolicy.fields(restored)
                .isEmpty());
    }

    @Test
    public void sourceCheckboxesNeverReplaceChartOrTableBodies() {
        for (WidgetKind kind : new WidgetKind[] { WidgetKind.HISTORY, WidgetKind.RESOURCES }) {
            WidgetSpec widget = new WidgetSpec("body", kind, 0, 0, 10, 7);
            assertTrue(DesktopFieldPolicy.keepsBody(widget.kind));
            widget = widget.configured("", Collections.singletonList("source"), 1);
            assertTrue(DesktopFieldPolicy.keepsBody(widget.kind));
            assertTrue(DesktopFieldPolicy.show(widget, "source"));
            assertFalse(DesktopFieldPolicy.show(widget, "updated"));
            widget = widget.configured("", Collections.<String>emptyList(), 1);
            assertTrue(DesktopFieldPolicy.keepsBody(widget.kind));
            assertFalse(DesktopFieldPolicy.show(widget, "source"));
        }
    }
}
