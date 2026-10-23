package com.miaokatze.gtswn.client.engineering;

import com.miaokatze.miaokatzecore.api.camera.HeightSlice;

/** Factory intent only; Core continues to own slicing, its toolbar and rendering. */
final class FactoryXrayState {

    private HeightSlice requested = HeightSlice.disabled();
    private HeightSlice remembered;

    HeightSlice requested() {
        return requested;
    }

    void request(HeightSlice slice) {
        requested = slice;
        if (slice.enabled()) remembered = slice;
    }

    void toggle(double anchorY) {
        if (requested.enabled()) request(HeightSlice.disabled());
        else if (remembered != null) request(remembered);
        else request(HeightSlice.below((int) Math.max(0, Math.min(255, Math.floor(anchorY))) + 1, .25));
    }

    HeightSlice remembered() {
        return remembered;
    }

    void restore(HeightSlice requested, HeightSlice remembered) {
        this.remembered = remembered != null && remembered.enabled() ? remembered : null;
        request(requested == null ? HeightSlice.disabled() : requested);
    }

    void reset() {
        requested = HeightSlice.disabled();
        remembered = null;
    }
}
