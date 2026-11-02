package com.miaokatze.gtswn.common.device;

import java.util.Objects;

/** Counts only; neither coordinates nor a second node registry are retained by terminal records. */
public final class DeviceLinkSummary {

    public static final byte UNKNOWN = 0, KNOWN = 1, NOT_APPLICABLE = 2;
    public static final DeviceLinkSummary UNVERIFIED = new DeviceLinkSummary(UNKNOWN, 0, 0, 0, 0);
    public static final DeviceLinkSummary NONE = new DeviceLinkSummary(NOT_APPLICABLE, 0, 0, 0, 0);
    public final byte state;
    public final int energyConnected, energyTotal, dynamoConnected, dynamoTotal;

    public DeviceLinkSummary(byte state, int energyConnected, int energyTotal, int dynamoConnected, int dynamoTotal) {
        if (state < UNKNOWN || state > NOT_APPLICABLE
            || energyConnected < 0
            || energyTotal < energyConnected
            || dynamoConnected < 0
            || dynamoTotal < dynamoConnected
            || (long) energyTotal + dynamoTotal > Integer.MAX_VALUE
            || state != KNOWN && (energyConnected != 0 || energyTotal != 0 || dynamoConnected != 0 || dynamoTotal != 0))
            throw new IllegalArgumentException("Invalid link summary");
        this.state = state;
        this.energyConnected = energyConnected;
        this.energyTotal = energyTotal;
        this.dynamoConnected = dynamoConnected;
        this.dynamoTotal = dynamoTotal;
    }

    public int connected() {
        return energyConnected + dynamoConnected;
    }

    public int total() {
        return energyTotal + dynamoTotal;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DeviceLinkSummary)) return false;
        DeviceLinkSummary that = (DeviceLinkSummary) other;
        return state == that.state && energyConnected == that.energyConnected
            && energyTotal == that.energyTotal
            && dynamoConnected == that.dynamoConnected
            && dynamoTotal == that.dynamoTotal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, energyConnected, energyTotal, dynamoConnected, dynamoTotal);
    }
}
