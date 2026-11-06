package com.github.justadeni.displayphysics;

import java.util.Arrays;

final class PoseFeed {
    static final int STRIDE = 8;
    static final int VALUES = 7;

    private final int count;
    private final float[] sent;
    private final boolean[] visible;

    PoseFeed(float[] initial, int count, boolean culling) {
        this.count = count;
        this.sent = initial.clone();
        this.visible = culling ? filled(count) : null;
    }

    private static boolean[] filled(int count) {
        var all = new boolean[count];
        Arrays.fill(all, true);
        return all;
    }

    float[] sent() {
        return sent;
    }

    boolean[] visible() {
        return visible;
    }

    int select(float[] pose, boolean last, int[] out) {
        int picked = 0;
        for (int i = 0; i < count; i++) {
            if (visible != null && !last && !visible[i])
                continue;
            int o = i * STRIDE;
            boolean same = true;
            for (int k = o; k < o + VALUES; k++)
                if (pose[k] != sent[k]) {
                    same = false;
                    break;
                }
            if (same)
                continue;
            System.arraycopy(pose, o, sent, o, STRIDE);
            out[picked++] = i;
        }
        return picked;
    }
}
