package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.client.QuantumParticleDirections.Direction;

public class QuantumParticleDirectionsTest {

    @Test(expected = UnsupportedOperationException.class)
    public void callersCannotCorruptSharedDirectionTables() {
        QuantumParticleDirections.nodeDirections(63)
            .clear();
    }

    @Test
    public void everyConnectionMaskEmitsOnlyTowardMissingConnections() {
        for (int mask = 0; mask < 63; mask++) {
            List<Direction> directions = QuantumParticleDirections.nodeDirections(mask);
            assertEquals(6 - Integer.bitCount(mask), directions.size());
            for (Direction direction : directions) {
                int side = direction.side;
                assertTrue(side >= 0);
                assertEquals(0, mask & (1 << side));
            }
        }
    }

    @Test
    public void fullyConnectedNodeUsesEveryEdgeAndCornerAndNoAxes() {
        List<Direction> directions = QuantumParticleDirections.nodeDirections(63);
        assertEquals(20, directions.size());
        for (Direction direction : directions) {
            assertTrue(Math.abs(direction.x) + Math.abs(direction.y) + Math.abs(direction.z) >= 2);
            assertEquals(-1, direction.side);
            boolean inverseFound = false;
            for (Direction inverse : directions) {
                if (inverse.x == -direction.x && inverse.y == -direction.y && inverse.z == -direction.z)
                    inverseFound = true;
            }
            assertTrue(inverseFound);
        }
    }

    @Test
    public void incorporatedBlockGetsOneIndependentDirectionPerAirFace() {
        for (int mask = 0; mask < 64; mask++) {
            List<Direction> directions = QuantumParticleDirections.openFaces(mask);
            assertEquals(Integer.bitCount(mask), directions.size());
            for (Direction direction : directions) assertTrue((mask & (1 << direction.side)) != 0);
        }
    }
}
