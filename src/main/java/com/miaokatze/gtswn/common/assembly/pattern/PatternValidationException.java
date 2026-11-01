package com.miaokatze.gtswn.common.assembly.pattern;

/** Rejection before inventory ownership changes. Reasons are stable keys for the assembly status UI. */
public final class PatternValidationException extends IllegalArgumentException {

    public enum Reason {
        UNKNOWN_PATTERN,
        CRAFTING_PATTERN,
        SUBSTITUTE_PATTERN,
        INPUT_ONLY_PATTERN,
        TUNNEL_INPUT,
        INVALID_ENCODING,
        UNKNOWN_RESOURCE,
        INVALID_AMOUNT,
        AMOUNT_OVERFLOW,
        SPECIAL_FLUID_TAG,
        MULTIPLE_FLUID_PACKETS,
        UNSUPPORTED_OUTPUT,
        UNKNOWN_TABLE,
        TABLE_SHAPE,
        TABLE_RESOURCE,
        TABLE_AMOUNT,
        NO_AUTHORIZED_RECIPE,
        AMBIGUOUS_RECIPE,
        ZERO_CONSUMPTION,
        MULTIPLE_COPIES
    }

    private final Reason reason;
    private final int rawIndex;

    PatternValidationException(Reason reason, int rawIndex) {
        super(reason.name() + (rawIndex < 0 ? "" : ": raw=" + rawIndex));
        this.reason = reason;
        this.rawIndex = rawIndex;
    }

    public Reason reason() {
        return reason;
    }

    public int rawIndex() {
        return rawIndex;
    }
}
