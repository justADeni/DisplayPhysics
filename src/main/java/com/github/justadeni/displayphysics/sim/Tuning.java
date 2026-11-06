package com.github.justadeni.displayphysics.sim;

import org.joml.Matrix3f;
import org.joml.Vector3f;

public final class Tuning {
    private Tuning() {}

    public static final float TICK = 0.05f;
    public static final long TICK_MS = 50;
    public static final float BUOYANCY = 1.25f;
    public static final float LIQUID_DRAG = 2.5f;
    public static final float ROLLING_RESISTANCE = 2.0f;
    public static final int SUBSTEPS = 4;
    public static final int ITERATIONS = 3;
    public static final float CORNER = 0.49f;
    public static final float CORNER_REACH_SQ = 3f * CORNER * CORNER;

    public static final float SLOP = 0.01f;
    public static final float BAUMGARTE = 0.4f;
    public static final float MAX_CORRECTION = 0.3f;
    public static final float BOUNCE_THRESHOLD = 1.0f;
    public static final float MAX_EXIT_SPEED = 0.5f;
    public static final float MAX_SPEED = 40f, MAX_SPIN = 25f;

    public static final int CALM_TICKS_TO_SLEEP = 10;

    static Vector3f rotate(Matrix3f m, float x, float y, float z, Vector3f out) {
        return out.set(
                m.m00 * x + m.m10 * y + m.m20 * z,
                m.m01 * x + m.m11 * y + m.m21 * z,
                m.m02 * x + m.m12 * y + m.m22 * z);
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
