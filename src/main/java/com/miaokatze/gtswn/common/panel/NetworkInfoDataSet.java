package com.miaokatze.gtswn.common.panel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

public class NetworkInfoDataSet {

    public static final int WINDOW_5_MIN = 0;
    public static final int WINDOW_1_HOUR = 1;
    public static final int WINDOW_8_HOUR = 2;
    public static final int WINDOW_24_HOUR = 3;

    private static final long FIVE_MIN_MS = 5L * 60L * 1000L;
    private static final long ONE_HOUR_MS = 60L * 60L * 1000L;
    private static final long EIGHT_HOUR_MS = 8L * 60L * 60L * 1000L;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private final List<NetworkInfoSample> samples = new ArrayList<>();
    private long lastSeenMs;

    public void add(BigInteger eu, long tick, long timeMs) {
        NetworkInfoSample previous = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        double eut = calculateEut(previous, eu, tick);
        samples.add(new NetworkInfoSample(timeMs, tick, eu, eut));
        lastSeenMs = timeMs;
        compress(timeMs);
    }

    public List<NetworkInfoSample> query(int window) {
        long now = samples.isEmpty() ? System.currentTimeMillis() : samples.get(samples.size() - 1).timeMs;
        long cutoff = now - windowToMillis(window);
        List<NetworkInfoSample> result = new ArrayList<>();
        for (NetworkInfoSample sample : samples) {
            if (sample.timeMs >= cutoff) {
                result.add(sample);
            }
        }
        return result;
    }

    public NetworkInfoSample newest() {
        if (samples.isEmpty()) {
            return null;
        }
        return samples.get(samples.size() - 1);
    }

    public long getLastSeenMs() {
        return lastSeenMs;
    }

    private void compress(long nowMs) {
        List<NetworkInfoSample> compressed = new ArrayList<>();
        appendBucketed(compressed, nowMs - FIVE_MIN_MS, nowMs, 5L * 1000L);
        appendBucketed(compressed, nowMs - ONE_HOUR_MS, nowMs - FIVE_MIN_MS, 30L * 1000L);
        appendBucketed(compressed, nowMs - EIGHT_HOUR_MS, nowMs - ONE_HOUR_MS, 5L * 60L * 1000L);
        appendBucketed(compressed, nowMs - DAY_MS, nowMs - EIGHT_HOUR_MS, 15L * 60L * 1000L);
        samples.clear();
        samples.addAll(compressed);
    }

    private void appendBucketed(List<NetworkInfoSample> target, long fromInclusive, long toExclusive, long bucketMs) {
        NetworkInfoSample currentBucketLast = null;
        long currentBucket = Long.MIN_VALUE;
        for (NetworkInfoSample sample : samples) {
            if (sample.timeMs < fromInclusive || sample.timeMs >= toExclusive) {
                continue;
            }
            long bucket = (sample.timeMs - fromInclusive) / bucketMs;
            if (bucket != currentBucket) {
                if (currentBucketLast != null) {
                    appendUnique(target, currentBucketLast);
                }
                currentBucket = bucket;
            }
            currentBucketLast = sample;
        }
        if (currentBucketLast != null) {
            appendUnique(target, currentBucketLast);
        }
    }

    private void appendUnique(List<NetworkInfoSample> target, NetworkInfoSample sample) {
        if (target.isEmpty() || target.get(target.size() - 1).timeMs != sample.timeMs) {
            target.add(sample);
        }
    }

    private static double calculateEut(NetworkInfoSample previous, BigInteger eu, long tick) {
        if (previous == null || eu == null) {
            return 0.0D;
        }
        long tickDiff = tick - previous.tick;
        if (tickDiff <= 0L) {
            return 0.0D;
        }
        BigInteger diff = eu.subtract(previous.eu);
        return new BigDecimal(diff).divide(new BigDecimal(tickDiff), 6, RoundingMode.HALF_UP)
            .doubleValue();
    }

    public static long windowToMillis(int window) {
        switch (window) {
            case WINDOW_1_HOUR:
                return ONE_HOUR_MS;
            case WINDOW_8_HOUR:
                return EIGHT_HOUR_MS;
            case WINDOW_24_HOUR:
                return DAY_MS;
            case WINDOW_5_MIN:
            default:
                return FIVE_MIN_MS;
        }
    }

    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("lastSeenMs", lastSeenMs);
        NBTTagList list = new NBTTagList();
        for (NetworkInfoSample sample : samples) {
            list.appendTag(sample.toNBT());
        }
        tag.setTag("samples", list);
        return tag;
    }

    public void readFromNBT(NBTTagCompound tag) {
        samples.clear();
        lastSeenMs = tag.getLong("lastSeenMs");
        NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            samples.add(NetworkInfoSample.fromNBT(list.getCompoundTagAt(i)));
        }
    }
}
