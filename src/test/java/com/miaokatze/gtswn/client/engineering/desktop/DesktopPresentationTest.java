package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import org.junit.Test;

public class DesktopPresentationTest {

    @Test
    public void presetsAreIndependentValidCandidatesWithinInitialAreaWithoutFabricatedTargetsOrBindings() {
        DesktopLayout existing = DesktopLayout.defaultLayout();
        assertTrue(existing.move("machines", -20, -20));
        for (DesktopPreset preset : DesktopPreset.values()) {
            DesktopLayout layout = preset.createLayout();
            assertTrue(
                layout.widgets()
                    .size() <= DesktopLayout.MAX_WIDGETS);
            assertFalse(
                layout.widgets()
                    .isEmpty());
            for (WidgetSpec widget : layout.widgets()) {
                assertTrue(layout.fits(widget, widget.id));
                assertTrue(widget.column >= 0 && widget.row >= 0);
                assertTrue(widget.x() + widget.width() <= DesktopLayout.DEFAULT_SIZE);
                assertTrue(widget.y() + widget.height() <= DesktopLayout.DEFAULT_SIZE);
                assertNull(widget.userTarget);
                assertNull(widget.device);
            }
            int count = layout.widgets()
                .size();
            assertTrue(
                layout.remove(
                    layout.widgets()
                        .get(0).id));
            assertEquals(
                count,
                preset.createLayout()
                    .widgets()
                    .size());
        }
        assertEquals(-20, existing.find("machines").column);
    }

    @Test
    public void formsAndMetricsComeOnlyFromSupportedDataAndGoalsMustBePositiveFinite() {
        assertEquals(
            Arrays.asList(WidgetPresentation.DEFAULT, WidgetPresentation.TREND),
            WidgetSpec.availablePresentations(WidgetKind.HISTORY));
        assertFalse(
            WidgetSpec.availablePresentations(WidgetKind.DEVICE)
                .contains(WidgetPresentation.TREND));
        assertTrue(
            WidgetSpec.availableMetrics(WidgetKind.RESOURCES)
                .isEmpty());
        WidgetSpec input = new WidgetSpec("input", WidgetKind.INPUT, 0, 0, 3, 2);
        for (double target : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            try {
                input.presented(WidgetPresentation.GAUGE, "input", target);
                fail("Invalid target accepted");
            } catch (IllegalArgumentException expected) {}
        }
        try {
            input.presented(WidgetPresentation.TREND, "input", null);
            fail("Instantaneous data is not a historical series");
        } catch (IllegalArgumentException expected) {}
        try {
            input.presented(WidgetPresentation.KPI, "capacity", 100.0);
            fail("No capacity telemetry exists");
        } catch (IllegalArgumentException expected) {}
        assertNull(input.presented(WidgetPresentation.GAUGE, "input", null).userTarget);
    }

    @Test
    public void compactFormMinimumAndExtraFieldRowsDoNotForceAllCardsToBeLarge() {
        WidgetSpec region = new WidgetSpec("region", WidgetKind.REGION, 0, 0, 4, 3)
            .presented(WidgetPresentation.KPI, "loadedChunks", null);
        assertEquals(3, region.minColumns());
        assertEquals(2, region.minRows());
        WidgetSpec gauge = region.presented(WidgetPresentation.GAUGE, "loadedChunks", 16.0);
        assertEquals(3, gauge.minRows());
        assertEquals(
            4,
            region.presented(WidgetPresentation.BAR, "monitored", null)
                .minColumns());
        WidgetSpec fields = gauge.configured("", WidgetSpec.availableFields(WidgetKind.REGION), 1);
        assertTrue(fields.minRows() > gauge.minRows());
        WidgetSpec one = region.configured("", Arrays.asList("source"), 4);
        assertEquals(3, one.minColumns());
    }

    @Test
    public void copiesDragAndPersistenceKeepFormMetricGoalAndExplicitEmptySelection() {
        WidgetSpec spec = new WidgetSpec("running", WidgetKind.MACHINES, 0, 0, 4, 3)
            .presented(WidgetPresentation.GAUGE, "running", 24.0)
            .configured("goal", Collections.<String>emptyList(), 1)
            .titled("daily goal")
            .withBounds(-4, -4, 4, 3);
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(spec));
        DesktopDrag drag = new DesktopDrag();
        assertTrue(drag.begin(layout, spec.id, DesktopDrag.Edge.MOVE, 0, 0));
        assertTrue(drag.update(64, 64));
        assertTrue(drag.finish());
        WidgetSpec restored = DesktopStore.decode(DesktopStore.encode(layout))
            .find(spec.id);
        assertEquals(WidgetPresentation.GAUGE, restored.presentation);
        assertEquals("running", restored.metricField);
        assertEquals(Double.valueOf(24), restored.userTarget);
        assertTrue(restored.fieldsConfigured);
        assertTrue(restored.fields.isEmpty());
        assertEquals(-3, restored.column);
        assertEquals("daily goal", restored.title);
    }

    @Test
    public void legacySchemaAndMalformedOptionalPreferencesKeepValidLayout() {
        Properties p = DesktopStore.encode(DesktopLayout.defaultLayout());
        p.remove("widget.0.fieldsConfigured");
        WidgetSpec legacy = DesktopStore.decode(p)
            .find("machines");
        assertEquals(WidgetPresentation.KPI, legacy.presentation);
        assertFalse(legacy.fieldsConfigured);
        assertNull(legacy.userTarget);
        assertEquals("count", legacy.metricField);
        p.setProperty("widget.0.presentation", "FUTURE_FORM");
        p.setProperty("widget.0.metricField", "capacity");
        p.setProperty("widget.0.userTarget", "999");
        WidgetSpec restored = DesktopStore.decode(p)
            .find("machines");
        assertNotNull(restored);
        assertEquals(WidgetPresentation.DEFAULT, restored.presentation);
        assertEquals("count", restored.metricField);
        assertNull(restored.userTarget);
        p.setProperty("widget.0.userTarget", "NaN");
        assertNull(
            DesktopStore.decode(p)
                .find("machines").userTarget);
        assertEquals(
            DesktopLayout.defaultLayout()
                .widgets()
                .size(),
            DesktopStore.decode(p)
                .widgets()
                .size());
    }
}
