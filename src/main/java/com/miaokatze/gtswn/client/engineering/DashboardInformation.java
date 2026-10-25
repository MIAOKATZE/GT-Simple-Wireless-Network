package com.miaokatze.gtswn.client.engineering;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.world.World;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManagerClient;
import com.gtnewhorizon.gtnhlib.teams.TeamRole;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** Client-only, once-per-second snapshots. No request, subscription or server-side team lookup. */
public final class DashboardInformation {

    private static final DashboardInformation INSTANCE = new DashboardInformation();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static volatile Map<String, String> values = Collections.emptyMap();
    private final Session session = new Session();
    private long nextRefresh;

    private DashboardInformation() {}

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    static String value(String field) {
        return values.get(field);
    }

    @SubscribeEvent
    public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        session.observe(event.manager, System.nanoTime());
        nextRefresh = 0;
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (session.disconnect(event.manager)) {
            values = Collections.emptyMap();
            nextRefresh = 0;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        Object connection = mc.getNetHandler() == null ? null
            : mc.getNetHandler()
                .getNetworkManager();
        long now = System.nanoTime();
        // The connection owns the clock: dimension travel and respawn do not restart it.
        session.observe(connection, now);
        if (now < nextRefresh) return;
        nextRefresh = now + 1_000_000_000L;
        Map<String, String> snapshot = new LinkedHashMap<>();
        ZonedDateTime local = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault());
        snapshot.put("localTime", CLOCK.format(local));
        snapshot.put("localDate", DATE.format(local));
        snapshot.put(
            "timezone",
            local.getZone()
                .getId());
        World world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        if (connection != null) {
            snapshot.put("sessionDuration", duration(session.elapsedSeconds(now)));
            snapshot.put("connection", translated(mc.isSingleplayer() ? "integrated" : "multiplayer"));
        }
        if (world != null && player != null) {
            snapshot.put("gameTime", gameTime(world.getWorldTime()));
            snapshot.put("gameDay", Long.toString(Math.floorDiv(world.getWorldTime(), 24000L) + 1));
            snapshot.put("playerName", player.getCommandSenderName());
            snapshot
                .put("health", String.format(Locale.ROOT, "%.0f / %.0f", player.getHealth(), player.getMaxHealth()));
            snapshot.put(
                "food",
                player.getFoodStats()
                    .getFoodLevel() + " / 20");
            snapshot.put("level", Integer.toString(player.experienceLevel));
            snapshot.put("dimension", world.provider.getDimensionName() + " (" + world.provider.dimensionId + ")");
            snapshot.put(
                "coordinates",
                String.format(Locale.ROOT, "%.0f / %.0f / %.0f", player.posX, player.posY, player.posZ));
            snapshot
                .put("weather", translated(world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear"));
            // TeamInfoSync contains the current player's team name/id/role only; roster sets are NOT synced.
            putTeam(snapshot, TeamManagerClient.GetTeam(), TeamManagerClient.getRole());
        }
        values = Collections.unmodifiableMap(snapshot);
    }

    static void putTeam(Map<String, String> snapshot, Team team, TeamRole role) {
        if (team == null) return;
        snapshot.put("teamName", team.getTeamName());
        snapshot.put(
            "teamId",
            team.getTeamId()
                .toString());
        if (role != null) snapshot.put(
            "teamRole",
            translated(
                "role." + role.name()
                    .toLowerCase(Locale.ROOT)));
    }

    private static String translated(String key) {
        return net.minecraft.util.StatCollector.translateToLocal("gtswn.factory.desktop.info." + key);
    }

    static String gameTime(long ticks) {
        long minutes = Math.floorMod(ticks, 24000L) / 1000 * 60 + Math.floorMod(ticks, 1000L) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", (minutes / 60 + 6) % 24, minutes % 60);
    }

    static String duration(long seconds) {
        long safe = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", safe / 3600, safe / 60 % 60, safe % 60);
    }

    /** Monotonic connection duration, intentionally neither player ticks nor total lifetime playtime. */
    static final class Session {

        private Object connection;
        private long started;

        synchronized void observe(Object current, long now) {
            if (current != connection) {
                connection = current;
                started = now;
            }
        }

        synchronized boolean disconnect(Object disconnected) {
            if (connection != disconnected) return false;
            connection = null;
            return true;
        }

        synchronized long elapsedSeconds(long now) {
            return connection == null ? 0 : Math.max(0, now - started) / 1_000_000_000L;
        }
    }
}
