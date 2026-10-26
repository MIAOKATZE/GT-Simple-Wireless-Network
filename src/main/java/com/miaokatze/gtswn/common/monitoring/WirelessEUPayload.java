package com.miaokatze.gtswn.common.monitoring;

import java.math.BigInteger;
import java.util.UUID;

/** EU_SOURCE-negotiated envelope inside the existing response string. Legacy numeric responses stay unchanged. */
public final class WirelessEUPayload {

    public static final String PREFIX = "$gtswn:eu2:";
    public static final int MAX_AMOUNT = 4096;
    public final String sourceToken;
    public final long tick;
    public final BigInteger amount;

    private WirelessEUPayload(String sourceToken, long tick, BigInteger amount) {
        this.sourceToken = sourceToken;
        this.tick = tick;
        this.amount = amount;
    }

    public static String encode(NativeWirelessEUSnapshot sample) {
        if (sample == null || sample.amount.toString()
            .length() > MAX_AMOUNT) return null;
        return PREFIX + sample.epoch + ":" + sample.account + ":" + sample.tick + ":" + sample.amount;
    }

    /** Invalid, unknown-version and oversized messages are unavailable, never zero measurements. */
    public static WirelessEUPayload parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_AMOUNT + 128) return null;
        try {
            if (!text.startsWith(PREFIX)) {
                BigInteger amount = exactAmount(text);
                return amount == null ? null : new WirelessEUPayload(null, -1L, amount);
            }
            String[] fields = text.substring(PREFIX.length())
                .split(":", -1);
            if (fields.length != 4) return null;
            UUID epoch = UUID.fromString(fields[0]), account = UUID.fromString(fields[1]);
            if (!epoch.toString()
                .equals(fields[0])
                || !account.toString()
                    .equals(fields[1]))
                return null;
            long tick = Long.parseLong(fields[2]);
            if (tick < 0 || !Long.toString(tick)
                .equals(fields[2])) return null;
            BigInteger amount = exactAmount(fields[3]);
            return amount == null ? null : new WirelessEUPayload(epoch + ":" + account, tick, amount);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static BigInteger exactAmount(String text) {
        if (text.isEmpty() || text.length() > MAX_AMOUNT) return null;
        BigInteger amount = new BigInteger(text);
        return amount.toString()
            .equals(text) ? amount : null;
    }
}
