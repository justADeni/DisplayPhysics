package com.github.justadeni.displayphysics;

import java.util.Arrays;

/**
 * What one audience has been sent so far, and so which of the next frame's poses it still needs. Poses are
 * {@value #STRIDE} floats each ({@code qx qy qz qw tx ty tz} and one of padding, so two poses fill a 64-byte
 * cache line and none straddles two).
 *
 * <p>A piece is only selected if its pose differs from the one this audience last received (a repeat would only
 * restart the client's interpolation) and, when the audience can have pieces hidden from it, the piece is currently
 * visible. The last frame ignores visibility, so nothing stays where it was last seen.
 */
final class PoseFeed {

    static final int STRIDE = 8;
    static final int VALUES = 7;

    private final int count;
    private final float[] sent;
    private final boolean[] visible;

    /**
     * @param initial the poses the audience is assumed to know (copied)
     * @param culling whether pieces can be hidden from this audience
     */
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

    /** The poses this audience last received, one per piece. */
    float[] sent() {
        return sent;
    }

    /** Per piece, whether the audience can see it; written by the visibility pass. Null when nothing is ever hidden. */
    boolean[] visible() {
        return visible;
    }

    /**
     * Picks the pieces whose pose must be sent and records them as sent.
     *
     * @param out receives the piece indices; at least {@code count} long
     * @return how many were picked
     */
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
