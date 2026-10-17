package com.miaokatze.gtswn.crossmod.mm;

import java.util.function.BooleanSupplier;

/** Commits an anchor against the actual registration, including installs that throw after registration. */
final class IncorporationCopyCost {

    static final class Result {

        private final boolean successful, created, missingMaterial;
        private final RuntimeException failure;

        Result(boolean successful, boolean created, boolean missingMaterial, RuntimeException failure) {
            this.successful = successful;
            this.created = created;
            this.missingMaterial = missingMaterial;
            this.failure = failure;
        }

        boolean successful() {
            return successful;
        }

        boolean created() {
            return created;
        }

        boolean missingMaterial() {
            return missingMaterial;
        }

        RuntimeException failure() {
            return failure;
        }
    }

    private IncorporationCopyCost() {}

    static Result apply(boolean existing, boolean creative, BooleanSupplier reserve, Runnable install,
        BooleanSupplier committed, Runnable refund) {
        if (existing) return new Result(true, false, false, null);
        if (!creative && !reserve.getAsBoolean()) return new Result(false, false, true, null);
        RuntimeException failure = null;
        try {
            install.run();
        } catch (RuntimeException e) {
            failure = e;
        }
        // The registry may publish an entry before updating visual/bridge state. Never refund a live entry.
        // If checking the registration itself throws, retain the reservation rather than guess and duplicate it.
        if (committed.getAsBoolean()) return new Result(true, true, false, failure);
        if (!creative) refund.run();
        return new Result(false, false, false, failure);
    }
}
