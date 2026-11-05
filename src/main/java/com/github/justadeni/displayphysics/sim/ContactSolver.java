package com.github.justadeni.displayphysics.sim;

import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Resolves contacts between one {@link Body} and the static terrain.
 *
 * <p>Sequential impulses with accumulated clamping: a non-penetration impulse
 * along the contact normal, two Coulomb friction impulses along the tangents,
 * then a split-impulse pass that removes penetration without adding energy.
 * One instance is reused across steps; call {@link #begin()}, add probes, then
 * {@link #solve}.
 */
final class ContactSolver {

    private static final float SLOP = 0.01f;
    private static final float BAUMGARTE = 0.4f;
    /** Most penetration (blocks) a single contact may correct per step. */
    private static final float MAX_CORRECTION = 0.3f;
    /** Below this approach speed contacts don't bounce, so resting bodies settle. */
    private static final float BOUNCE_THRESHOLD = 1.0f;

    private int n;
    private int capacity = 64;
    private float[] rx = new float[capacity], ry = new float[capacity], rz = new float[capacity];
    private float[] nx = new float[capacity], ny = new float[capacity], nz = new float[capacity];
    private float[] depth = new float[capacity];
    private boolean[] hard = new boolean[capacity];

    private float[] t1x = new float[capacity], t1y = new float[capacity], t1z = new float[capacity];
    private float[] t2x = new float[capacity], t2y = new float[capacity], t2z = new float[capacity];
    private float[] kn = new float[capacity], k1 = new float[capacity], k2 = new float[capacity];
    private float[] bounce = new float[capacity];
    private float[] accN = new float[capacity], acc1 = new float[capacity], acc2 = new float[capacity], accP = new float[capacity];

    /** Fastest approach speed of any probe this step, and where it happened (relative coords). */
    float maxImpact;
    final Vector3f impactPoint = new Vector3f();

    private final Vector3f r = new Vector3f();
    private final Vector3f nrm = new Vector3f();
    private final Vector3f vel = new Vector3f();
    private final Vector3f dir = new Vector3f();

    void begin() {
        n = 0;
        maxImpact = 0f;
    }

    int count() {
        return n;
    }

    /**
     * Tests the point at offset {@code (ox, oy, oz)} (world-space, from the centre of mass)
     * and records a contact if it is inside solid terrain.
     */
    void probe(Body body, Voxels terrain, float ox, float oy, float oz, boolean isHard) {
        float px = body.x.x + ox, py = body.x.y + oy, pz = body.x.z + oz;
        r.set(ox, oy, oz);
        body.velocityAt(r, vel);
        float d = terrain.penetration(px, py, pz, vel.x, vel.y, vel.z, nrm);
        if (d < 0f)
            return;

        if (n == capacity)
            grow();

        rx[n] = ox; ry[n] = oy; rz[n] = oz;
        nx[n] = nrm.x; ny[n] = nrm.y; nz[n] = nrm.z;
        depth[n] = d;
        hard[n] = isHard;
        n++;
    }

    /**
     * @param restitution     bounciness of hard contacts
     * @param softRestitution bounciness of soft contacts (0 = soft pieces cushion)
     */
    void solve(Body body, float dt, int iterations, float friction, float restitution, float softRestitution) {
        if (n == 0)
            return;

        body.updateInertia();

        for (int i = 0; i < n; i++) {
            r.set(rx[i], ry[i], rz[i]);
            nrm.set(nx[i], ny[i], nz[i]);

            // Tangent basis
            if (Math.abs(nrm.x) < 0.9f) nrm.cross(1, 0, 0, dir); else nrm.cross(0, 1, 0, dir);
            dir.normalize();
            t1x[i] = dir.x; t1y[i] = dir.y; t1z[i] = dir.z;
            k1[i] = 1f / body.inverseEffectiveMass(r, dir);
            nrm.cross(dir, dir);
            t2x[i] = dir.x; t2y[i] = dir.y; t2z[i] = dir.z;
            k2[i] = 1f / body.inverseEffectiveMass(r, dir);

            kn[i] = 1f / body.inverseEffectiveMass(r, nrm);

            float vn = body.velocityAt(r, vel).dot(nrm);
            bounce[i] = vn < -BOUNCE_THRESHOLD ? -(hard[i] ? restitution : softRestitution) * vn : 0f;
            if (-vn > maxImpact) {
                maxImpact = -vn;
                impactPoint.set(body.x).add(r);
            }

            accN[i] = acc1[i] = acc2[i] = accP[i] = 0f;
        }

        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < n; i++) {
                r.set(rx[i], ry[i], rz[i]);
                float mu = hard[i] ? friction : friction * 1.5f;
                float maxFriction = mu * accN[i];

                body.velocityAt(r, vel);
                float lambda = -(vel.x * t1x[i] + vel.y * t1y[i] + vel.z * t1z[i]) * k1[i];
                float next = clamp(acc1[i] + lambda, -maxFriction, maxFriction);
                lambda = next - acc1[i];
                acc1[i] = next;
                body.applyImpulse(r, t1x[i] * lambda, t1y[i] * lambda, t1z[i] * lambda);

                body.velocityAt(r, vel);
                lambda = -(vel.x * t2x[i] + vel.y * t2y[i] + vel.z * t2z[i]) * k2[i];
                next = clamp(acc2[i] + lambda, -maxFriction, maxFriction);
                lambda = next - acc2[i];
                acc2[i] = next;
                body.applyImpulse(r, t2x[i] * lambda, t2y[i] * lambda, t2z[i] * lambda);

                body.velocityAt(r, vel);
                float vn = vel.x * nx[i] + vel.y * ny[i] + vel.z * nz[i];
                lambda = (bounce[i] - vn) * kn[i];
                next = Math.max(accN[i] + lambda, 0f);
                lambda = next - accN[i];
                accN[i] = next;
                body.applyImpulse(r, nx[i] * lambda, ny[i] * lambda, nz[i] * lambda);
            }
        }

        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < n; i++) {
                // Capped so one deep overlap can only ever nudge the body, never fling it
                float target = BAUMGARTE * Math.clamp(depth[i] - SLOP, 0f, MAX_CORRECTION) / dt;
                if (target <= 0f && accP[i] == 0f)
                    continue;

                r.set(rx[i], ry[i], rz[i]);
                body.pseudoVelocityAt(r, vel);
                float vn = vel.x * nx[i] + vel.y * ny[i] + vel.z * nz[i];
                float lambda = (target - vn) * kn[i];
                float next = Math.max(accP[i] + lambda, 0f);
                lambda = next - accP[i];
                accP[i] = next;
                body.applyPseudoImpulse(r, nx[i] * lambda, ny[i] * lambda, nz[i] * lambda);
            }
        }
    }

    private void grow() {
        capacity *= 2;
        rx = Arrays.copyOf(rx, capacity); ry = Arrays.copyOf(ry, capacity); rz = Arrays.copyOf(rz, capacity);
        nx = Arrays.copyOf(nx, capacity); ny = Arrays.copyOf(ny, capacity); nz = Arrays.copyOf(nz, capacity);
        depth = Arrays.copyOf(depth, capacity);
        hard = Arrays.copyOf(hard, capacity);
        t1x = Arrays.copyOf(t1x, capacity); t1y = Arrays.copyOf(t1y, capacity); t1z = Arrays.copyOf(t1z, capacity);
        t2x = Arrays.copyOf(t2x, capacity); t2y = Arrays.copyOf(t2y, capacity); t2z = Arrays.copyOf(t2z, capacity);
        kn = Arrays.copyOf(kn, capacity); k1 = Arrays.copyOf(k1, capacity); k2 = Arrays.copyOf(k2, capacity);
        bounce = Arrays.copyOf(bounce, capacity);
        accN = Arrays.copyOf(accN, capacity); acc1 = Arrays.copyOf(acc1, capacity);
        acc2 = Arrays.copyOf(acc2, capacity); accP = Arrays.copyOf(accP, capacity);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (Math.min(v, max));
    }

}
