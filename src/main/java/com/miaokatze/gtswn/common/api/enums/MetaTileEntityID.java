package com.miaokatze.gtswn.common.api.enums;

import com.miaokatze.gtswn.Config;

/**
 * Enum of MetaTileEntity IDs mirroring NH-Utilities style.
 * Uses a fixed base of 25000 plus any configured metaIdOffset.
 */
public enum MetaTileEntityID {

    MTETEST_EV(0),
    MTETEST_IV(1),
    MTETEST_LuV(2),
    MTETEST_ZPM(3),
    MTETEST_UV(4),
    MTETEST_UHV(5),
    MTETEST_UEV(6),
    MTETEST_UIV(7),
    MTETEST_UMV(8),
    MTETEST_UXV(9),
    MTETEST_MAX(10),

    ;

    public final int ID;

    private static final int BASE = 14600;

    MetaTileEntityID(int ID) {
        this.ID = BASE + Config.metaIdOffset + ID;
    }
}
