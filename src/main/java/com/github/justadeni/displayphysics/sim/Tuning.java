package com.github.justadeni.displayphysics.sim;

import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Fixed physics constants shared by the rigid and debris simulations. */
final class Tuning {

    private Tuning() {}

    /** One server tick, in seconds. */
    static final float TICK = 0.05f;
    static final long TICK_MS = 50;
    /** Pieces float in liquids: they get slightly more than their weight back. */
    static final float BUOYANCY = 1.25f;
    static final float LIQUID_DRAG = 2.5f;
    /** Extra angular damping while touching something: rough surfaces dig in, so assemblies don't roll like balls. */
    static final float ROLLING_RESISTANCE = 2.0f;
    /** Ticks a body must stay calm while touching something before it counts as settled. */
    static final int CALM_TICKS_TO_SLEEP = 10;

    /** {@code out = m * (x, y, z)}. Build {@code m} once per step with {@code q.get(m)}, then rotate many points cheaply. */
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
