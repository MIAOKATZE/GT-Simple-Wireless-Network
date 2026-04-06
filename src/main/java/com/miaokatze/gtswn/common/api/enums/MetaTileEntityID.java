package com.miaokatze.gtswn.common.api.enums;

import com.miaokatze.gtswn.config.Config;

/**
 * Enum of MetaTileEntity IDs mirroring NH-Utilities style.
 * Uses a fixed base of 25000 plus any configured metaIdOffset.
 */
public enum MetaTileEntityID {

    // Reduced to three MTETest variants: EV, IV, LuV
    MTETEST_EV(0),
    MTETEST_IV(1),
    MTETEST_LuV(2),

    ;

    public final int ID;

    private static final int BASE = 14600;

    MetaTileEntityID(int ID) {
        this.ID = BASE + Config.metaIdOffset + ID;
    }
}
