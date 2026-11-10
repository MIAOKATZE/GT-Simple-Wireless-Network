package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

/** Synthetic server actors, never online humans. UUIDs and unique <=16-character names are hash-derived. */
public final class TestActors {

    private static final String[] ROLES = { "owner", "member", "outsider" };

    private TestActors() {}

    public static UUID id(String role) {
        for (String known : ROLES) if (known.equals(role)) return TestWorldFixtures.owner("actor:" + role);
        throw new IllegalArgumentException("Unknown synthetic role " + role);
    }

    public static FakePlayer player(WorldServer world, String role) {
        return TestWorldFixtures.player(world, id(role));
    }

    public static List<String> describe() {
        List<String> result = new ArrayList<>();
        for (String role : ROLES) {
            UUID uuid = id(role);
            result.add(role + ": " + TestWorldFixtures.actorName(uuid) + " UUID=" + uuid + " (server FakePlayer)");
        }
        return result;
    }
}
