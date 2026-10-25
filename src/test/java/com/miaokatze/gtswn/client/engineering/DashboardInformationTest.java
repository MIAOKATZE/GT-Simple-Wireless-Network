package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com.gtnewhorizon.gtnhlib.teams.Team;

public class DashboardInformationTest {

    @Test
    public void worldClockUsesMinecraftSixAmOffsetAndWrapsAtMidnight() {
        assertEquals("06:00", DashboardInformation.gameTime(0));
        assertEquals("12:00", DashboardInformation.gameTime(6000));
        assertEquals("00:00", DashboardInformation.gameTime(18000));
        assertEquals("05:59", DashboardInformation.gameTime(-1));
        assertEquals("06:00", DashboardInformation.gameTime(24000));
    }

    @Test
    public void sessionUsesMonotonicConnectionTimeAcrossWorldAndPlayerChanges() {
        DashboardInformation.Session clock = new DashboardInformation.Session();
        Object connection = new Object();
        clock.observe(connection, 10_000_000_000L);
        clock.observe(connection, 40_000_000_000L);
        assertEquals(30, clock.elapsedSeconds(40_000_000_000L));
        assertEquals("00:00:30", DashboardInformation.duration(clock.elapsedSeconds(40_000_000_000L)));
        Object next = new Object();
        clock.observe(next, 41_000_000_000L);
        assertFalse(clock.disconnect(connection));
        assertEquals(2, clock.elapsedSeconds(43_000_000_000L));
        assertTrue(clock.disconnect(next));
        assertEquals(0, clock.elapsedSeconds(50_000_000_000L));
        assertEquals("123:00:01", DashboardInformation.duration(123 * 3600 + 1));
    }

    @Test
    public void teamProjectionNeverTreatsUnsynchronizedRosterSetsAsMemberCounts() {
        UUID id = UUID.randomUUID();
        Team team = new Team("Authorized", id, true);
        team.addMember(UUID.randomUUID());
        Map<String, String> projection = new LinkedHashMap<>();
        DashboardInformation.putTeam(projection, team, null);
        assertEquals(2, projection.size());
        assertEquals("Authorized", projection.get("teamName"));
        assertEquals(id.toString(), projection.get("teamId"));
        assertFalse(projection.containsKey("members"));
        assertFalse(projection.containsKey("online"));
        projection.clear();
        DashboardInformation.putTeam(projection, null, null);
        assertTrue(projection.isEmpty());
    }
}
