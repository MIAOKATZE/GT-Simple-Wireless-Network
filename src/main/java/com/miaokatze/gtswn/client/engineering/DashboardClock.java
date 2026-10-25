package com.miaokatze.gtswn.client.engineering;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Clock hand positions, in clockwise turns from twelve. No formatted text is parsed as a time source. */
final class DashboardClock {

    final double hour, minute, second;

    private DashboardClock(double seconds) {
        double daySeconds = seconds - Math.floor(seconds / 86400) * 86400;
        hour = daySeconds % 43200 / 43200;
        minute = daySeconds % 3600 / 3600;
        second = daySeconds % 60 / 60;
    }

    static DashboardClock local(Instant instant, ZoneId zone) {
        if (instant == null || zone == null) return null;
        ZonedDateTime civil = instant.atZone(zone);
        return new DashboardClock(
            civil.toLocalTime()
                .toSecondOfDay() + civil.getNano() / 1_000_000_000.0);
    }

    /** Minecraft ticks describe a twenty-minute day, with tick zero at 06:00; never server wall time. */
    static DashboardClock game(Long ticks) {
        return ticks == null ? null : new DashboardClock(Math.floorMod(ticks, 24000L) * 3.6 + 21600);
    }

    static DashboardClock read(String source, Instant localNow, ZoneId zone, Long gameTicks) {
        if ("localTime".equals(source)) return local(localNow, zone);
        if ("gameTime".equals(source)) return game(gameTicks);
        return null;
    }
}
