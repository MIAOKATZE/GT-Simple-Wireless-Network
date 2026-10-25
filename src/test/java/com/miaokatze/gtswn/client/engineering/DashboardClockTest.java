package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;

public class DashboardClockTest {

    @Test
    public void noonAndMidnightPointToTwelveAndHandsMoveWithSubsecondTime() {
        DashboardClock midnight = DashboardClock.local(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
        DashboardClock noon = DashboardClock.local(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC);
        assertEquals(0, midnight.hour, 0);
        assertEquals(0, noon.hour, 0);
        assertEquals(0, noon.minute, 0);
        assertEquals(0, noon.second, 0);
        DashboardClock time = DashboardClock.local(Instant.parse("2026-10-25T03:15:30.500Z"), ZoneOffset.UTC);
        assertEquals((3 * 3600 + 15 * 60 + 30.5) / 43200, time.hour, 1e-12);
        assertEquals((15 * 60 + 30.5) / 3600, time.minute, 1e-12);
        assertEquals(30.5 / 60, time.second, 1e-12);
    }

    @Test
    public void civilTimeUsesLocalZoneIncludingDaylightSavingRatherThanUtc() {
        Instant instant = Instant.parse("2026-10-25T00:00:00Z");
        DashboardClock shanghai = DashboardClock.local(instant, ZoneId.of("Asia/Shanghai"));
        assertEquals(8 / 12.0, shanghai.hour, 1e-12);
        DashboardClock summer = DashboardClock.local(Instant.parse("2026-07-01T12:00:00Z"), ZoneId.of("Europe/Berlin"));
        DashboardClock winter = DashboardClock.local(Instant.parse("2026-01-01T12:00:00Z"), ZoneId.of("Europe/Berlin"));
        assertEquals(2 / 12.0, summer.hour, 1e-12);
        assertEquals(1 / 12.0, winter.hour, 1e-12);
    }

    @Test
    public void gameClockUsesMinecraftDaylightTicksAndIsIndependentOfWallTime() {
        assertEquals(.5, DashboardClock.game(0L).hour, 0);
        assertEquals(0, DashboardClock.game(6000L).hour, 0);
        assertEquals(0, DashboardClock.game(18000L).hour, 0);
        assertEquals(.5, DashboardClock.game(24000L).hour, 0);
        DashboardClock negative = DashboardClock.game(-1L);
        assertEquals((6 * 3600 - 3.6) / 43200, negative.hour, 1e-12);
        assertEquals(
            DashboardClock.game(1L).second,
            DashboardClock.read("gameTime", Instant.EPOCH, ZoneOffset.UTC, 1L).second,
            0);
    }

    @Test
    public void missingTimeSourceRemainsUnknownInsteadOfInventingTwelveOClock() {
        assertNull(DashboardClock.game(null));
        assertNull(DashboardClock.local(null, ZoneOffset.UTC));
        assertNull(DashboardClock.read(null, Instant.EPOCH, ZoneOffset.UTC, 0L));
        assertNull(DashboardClock.read("localDate", Instant.EPOCH, ZoneOffset.UTC, 0L));
        assertNull(DashboardClock.read("gameTime", Instant.EPOCH, ZoneOffset.UTC, null));
    }

    @Test
    public void circularFaceKeepsItsAspectRatioAndAllDetailsRemainReachableByScrolling() {
        for (int columns = 1; columns <= 4; columns++) {
            ClockFaceLayout small = new ClockFaceLayout(new Rect(0, 0, 120, 192), columns, 4);
            ClockFaceLayout tall = new ClockFaceLayout(new Rect(0, 0, 120, 512), columns, 4);
            assertEquals(small.radius, tall.radius, 0);
            assertEquals(small.contentHeight, tall.contentHeight);
            assertTrue(small.radius >= 44);
            assertEquals(4, small.details.size());
            Rect last = small.details.get(3);
            assertEquals(last.y + last.h + 8, 30 + small.contentHeight);
            assertTrue(small.centerY + small.radius < small.source.y);
        }
    }
}
