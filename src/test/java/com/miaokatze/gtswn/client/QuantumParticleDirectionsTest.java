package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.Test;

import com.miaokatze.gtswn.client.QuantumParticleDirections.Direction;

public class QuantumParticleDirectionsTest {

    @Test
    public void faceSamplesSpreadEvenlyWhileStayingInTheAllowedOutwardSector() {
        Random random = new Random(872346L);
        for (Direction direction : QuantumParticleDirections.openFaces(63)) {
            double sumX = 0, sumY = 0, sumZ = 0;
            double spread = 0;
            for (int i = 0; i < 2000; i++) {
                double[] vector = QuantumParticleDirections.sample(direction, random);
                assertEquals(1D, vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2], 1e-12D);
                double forward = vector[0] * direction.x + vector[1] * direction.y + vector[2] * direction.z;
                assertTrue(forward >= .72D);
                assertTrue(forward > Math.sqrt(1 - forward * forward));
                spread += 1 - forward * forward;
                sumX += vector[0];
                sumY += vector[1];
                sumZ += vector[2];
            }
            assertTrue(spread / 2000 > .15D);
            assertEquals(direction.x * .86D, sumX / 2000, .025D);
            assertEquals(direction.y * .86D, sumY / 2000, .025D);
            assertEquals(direction.z * .86D, sumZ / 2000, .025D);
        }
    }

    @Test
    public void diagonalSamplesAvoidConnectedAxesAndRemainInTheirSelectedCone() {
        Random random = new Random(142L);
        for (Direction direction : QuantumParticleDirections.nodeDirections(63)) {
            double length = Math
                .sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
            for (int i = 0; i < 100; i++) {
                double[] vector = QuantumParticleDirections.sample(direction, random);
                double dot = (vector[0] * direction.x + vector[1] * direction.y + vector[2] * direction.z) / length;
                assertTrue(dot >= .94D);
                for (double component : vector) assertTrue(Math.abs(component) < .92D);
            }
        }
    }

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
