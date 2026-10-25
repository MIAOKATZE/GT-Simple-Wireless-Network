package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class DesktopClockPresentationTest {

    @Test
    public void compactInformationMinimumUsesEffectiveFieldsAndColumnsWithoutGrowingForOverflow() {
        for (WidgetKind kind : WidgetKind.values()) if (kind.isInformation()) {
            WidgetSpec compact = new WidgetSpec("info", kind, 0, 0, 2, 2);
            assertEquals(2, compact.minColumns());
            assertEquals(2, compact.minRows());
            WidgetSpec all = compact.configured("", WidgetSpec.availableFields(kind), 1);
            assertEquals(2, all.minRows());
            assertEquals(2, all.rows);
        }
        WidgetSpec clock = DesktopInformationTemplate.LOCAL_CLOCK.create("local");
        assertEquals(2, clock.columns);
        assertEquals(2, clock.rows);
        assertEquals(
            2,
            clock.configured("", Collections.singletonList("localTime"), 4)
                .minColumns());
        assertEquals(
            3,
            clock.configured("", Arrays.asList("localTime", "localDate"), 2)
                .minColumns());
        assertEquals(
            4,
            clock.configured("", Arrays.asList("localTime", "localDate", "timezone"), 3)
                .minColumns());
    }

    @Test
    public void analogIsClockOnlyAndSelectedFieldsChooseItsRealSource() {
        WidgetSpec analog = DesktopInformationTemplate.ANALOG_CLOCK.create("analog");
        assertEquals(WidgetPresentation.CLOCK_FACE, analog.presentation);
        assertEquals(2, analog.columns);
        assertEquals(3, analog.rows);
        assertEquals("localTime", analog.clockSourceField());
        assertEquals(
            "gameTime",
            analog.configured("", Arrays.asList("gameTime", "gameDay"), 1)
                .clockSourceField());
        assertEquals(
            "localTime",
            analog.configured("", Arrays.asList("gameTime", "localTime"), 1)
                .clockSourceField());
        assertNull(
            analog.configured("", Collections.singletonList("localDate"), 1)
                .clockSourceField());
        assertNull(
            analog.configured("", Collections.<String>emptyList(), 1)
                .clockSourceField());
        for (WidgetKind kind : WidgetKind.values()) assertEquals(
            kind == WidgetKind.CLOCK,
            WidgetSpec.availablePresentations(kind)
                .contains(WidgetPresentation.CLOCK_FACE));
    }

    @Test
    public void clockFaceAndAllPreferencesSurviveCopyStoreAndV3ShareWithoutShrinkingExistingBounds() {
        WidgetSpec analog = DesktopInformationTemplate.ANALOG_CLOCK.create("analog")
            .configured("Office", Arrays.asList("localTime", "localDate", "timezone", "gameTime", "gameDay"), 2)
            .styled(new WidgetEffects(WidgetEffects.Style.WAVE, false, 0x123456, .4, .3))
            .withBounds(-2, 4, 8, 6);
        DesktopLayout layout = new DesktopLayout();
        assertTrue(layout.add(analog));
        assertClock(
            analog,
            layout.copy()
                .find(analog.id));
        assertClock(
            analog,
            DesktopStore.decode(DesktopStore.encode(layout))
                .find(analog.id));
        assertClock(
            analog,
            DesktopPresetCode.decode(DesktopPresetCode.export(layout))
                .find("card1"));
        assertClock(analog, analog.asTemplate());
        WidgetSpec smallerSelection = analog.configured("Office", Collections.singletonList("localTime"), 1)
            .presented(WidgetPresentation.DEFAULT, "", null);
        assertEquals(8, smallerSelection.columns);
        assertEquals(6, smallerSelection.rows);
    }

    @Test
    public void persistedOlderInformationGeometryIsNotRepackedOrShrunk() {
        DesktopLayout previous = new DesktopLayout();
        int column = 0;
        for (WidgetKind kind : WidgetKind.values()) if (kind.isInformation()) {
            assertTrue(previous.add(new WidgetSpec("old" + kind.name(), kind, column, 7, 3, 3)));
            column += 3;
        }
        DesktopLayout restored = DesktopStore.decode(DesktopStore.encode(previous));
        for (WidgetSpec old : previous.widgets()) {
            WidgetSpec current = restored.find(old.id);
            assertEquals(old.column, current.column);
            assertEquals(old.row, current.row);
            assertEquals(3, current.columns);
            assertEquals(3, current.rows);
            assertEquals(WidgetPresentation.DEFAULT, current.presentation);
        }
    }

    private static void assertClock(WidgetSpec expected, WidgetSpec actual) {
        assertNotNull(actual);
        assertEquals(WidgetPresentation.CLOCK_FACE, actual.presentation);
        assertEquals(expected.fields, actual.fields);
        assertEquals(expected.displayColumns, actual.displayColumns);
        assertEquals(expected.columns, actual.columns);
        assertEquals(expected.rows, actual.rows);
        assertEquals(expected.effects.style, actual.effects.style);
        assertEquals(expected.effects.animated, actual.effects.animated);
        assertEquals(expected.effects.accentColor, actual.effects.accentColor);
    }
}
