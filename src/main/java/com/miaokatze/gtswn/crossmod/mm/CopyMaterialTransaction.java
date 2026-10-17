package com.miaokatze.gtswn.crossmod.mm;

import java.util.function.BooleanSupplier;

/** A material reservation commits only after installation; failed rollback must not mint a refund. */
public final class CopyMaterialTransaction {

    private CopyMaterialTransaction() {}

    public static boolean execute(BooleanSupplier reserve, BooleanSupplier install, Runnable rollback,
        Runnable refund) {
        if (!reserve.getAsBoolean()) return false;
        boolean committed = false;
        try {
            committed = install.getAsBoolean();
            return committed;
        } finally {
            if (!committed) {
                rollback.run();
                refund.run();
            }
        }
    }
}
