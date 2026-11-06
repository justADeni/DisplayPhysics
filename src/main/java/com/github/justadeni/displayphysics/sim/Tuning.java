package com.github.justadeni.displayphysics.sim;

import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Fixed physics constants shared by the rigid and debris simulations. */
public final class Tuning {

    private Tuning() {}

    /** One server tick, in seconds. */
    public static final float TICK = 0.05f;
    public static final long TICK_MS = 50;
    /** Pieces float in liquids: they get slightly more than their weight back. */
    public static final float BUOYANCY = 1.25f;
    public static final float LIQUID_DRAG = 2.5f;
    /** Extra angular damping while touching something: rough surfaces dig in, so assemblies don't roll like balls. */
    public static final float ROLLING_RESISTANCE = 2.0f;
    // ── Debris: stepping ──
    public static final int SUBSTEPS = 4;
    /**
     * Solver iterations per substep. Small substeps do the converging (a few fast, light passes beat many slow
     * ones), so three are enough (two let a fast piece tunnel through the ground).
     */
    public static final int ITERATIONS = 3;
    /** Half-size of a cube for terrain probes, pulled in a hair so neighbours never start overlapping. */
    public static final float CORNER = 0.49f;
    /** Every corner of a cube is this far from its centre, squared, however the cube is turned. */
    public static final float CORNER_REACH_SQ = 3f * CORNER * CORNER;

    // ── Debris: contacts ──
    public static final float SLOP = 0.01f;
    public static final float BAUMGARTE = 0.4f;
    /** Most penetration (blocks) a single contact may correct per step. */
    public static final float MAX_CORRECTION = 0.3f;
    /** Below this approach speed contacts don't bounce, so resting bodies settle. */
    public static final float BOUNCE_THRESHOLD = 1.0f;
    /** A probe can't have entered a voxel through a face it is moving out of faster than this (blocks per second). */
    public static final float MAX_EXIT_SPEED = 0.5f;
    /** Nothing simulated should ever move faster than this; a hard stop for solver blow-ups. */
    public static final float MAX_SPEED = 40f, MAX_SPIN = 25f;

    /** Ticks a body must stay calm while touching something before it counts as settled. */
    public static final int CALM_TICKS_TO_SLEEP = 10;

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
