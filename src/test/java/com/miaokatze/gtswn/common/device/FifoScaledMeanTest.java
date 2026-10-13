package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

public class FifoScaledMeanTest {

    @Test
    public void sixtyMaximumFiniteRatesAndEvictionNeverCreateInfinityOrNaN() {
        double[] fifo = new double[60];
        double sum = 0;
        int[] nonzero = new int[4];
        int count = 0, index = 0;
        for (int i = 0; i < 120; i++) {
            double next = i < 60 ? Double.MAX_VALUE : 0;
            sum = FifoMath.pushScaled(fifo, index, count, sum, next, nonzero, 0);
            fifo[index] = next;
            index = (index + 1) % 60;
            count = Math.min(60, count + 1);
            double average = FifoMath.scaledAvg(sum, count, fifo.length);
            assertTrue(Double.isFinite(average));
            assertTrue(average >= 0);
        }
        assertEquals(0, FifoMath.scaledAvg(sum, count, fifo.length), 0);
    }

    @Test
    public void rollingFractionalAndOrdinaryRatesMatchARebuiltMean() {
        double[] fifo = new double[60];
        double sum = 0;
        int[] nonzero = new int[4];
        int count = 0, index = 0;
        for (int i = 0; i < 180; i++) {
            double next = i % 2 == 0 ? .08 : i * 100;
            sum = FifoMath.pushScaled(fifo, index, count, sum, next, nonzero, 0);
            fifo[index] = next;
            index = (index + 1) % 60;
            count = Math.min(60, count + 1);
            double expected = FifoMath.avg(FifoMath.rebuildSum(fifo, count), count);
            assertEquals(expected, FifoMath.scaledAvg(sum, count, fifo.length), 1e-8);
            assertEquals(FifoMath.rebuildScaledSum(fifo, count), sum, 1e-8);
        }
    }

    @Test
    public void removingDominantSampleRecoversTheRemainingOrdinaryRateAndIncomingSample() {
        double[] fifo = new double[60];
        fifo[0] = 1e300;
        fifo[1] = 120;
        int[] nonzero = new int[4];
        double sum = FifoMath.rebuildScaledSum(fifo, 60, nonzero, 0);
        sum = FifoMath.pushScaled(fifo, 0, 60, sum, 0, nonzero, 0);
        assertEquals(1, nonzero[0]);
        assertEquals(2, FifoMath.scaledAvg(sum, 60, 60), 0);
        fifo[0] = 0;
        sum = FifoMath.pushScaled(fifo, 2, 60, sum, 0, nonzero, 0);
        assertEquals(2, FifoMath.scaledAvg(sum, 60, 60), 0);
        sum = FifoMath.pushScaled(fifo, 1, 60, sum, 0, nonzero, 0);
        assertEquals(0, FifoMath.scaledAvg(sum, 60, 60), 0);
        assertEquals(0, nonzero[0]);

        fifo[0] = 1e300;
        fifo[1] = 120;
        sum = FifoMath.rebuildScaledSum(fifo, 60, nonzero, 0);
        sum = FifoMath.pushScaled(fifo, 0, 60, sum, 60, nonzero, 0);
        assertEquals(2, nonzero[0]);
        assertEquals(3, FifoMath.scaledAvg(sum, 60, 60), 0);
    }
}
