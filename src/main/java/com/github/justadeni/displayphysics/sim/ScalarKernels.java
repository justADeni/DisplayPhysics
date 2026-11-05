package com.github.justadeni.displayphysics.sim;

import static com.github.justadeni.displayphysics.sim.Tuning.*;

/**
 * The reference kernels, plain loops over the {@link Bodies} arrays. They define what the SIMD kernels
 * must compute, and run wherever the Vector API is missing or the batch is too small to pay for it.
 *
 * <p>Terrain normals always run along an axis and cubes are isotropic, which removes almost all the general
 * machinery of a contact solver: a contact is an axis (one-hot), a sign and a depth; the two friction
 * directions are the other two axes; and the effective mass along an axis {@code d} is
 * {@code invMass + invInertia * (3C^2 - (r.d)^2)}, because every corner is {@code sqrt(3) C} from the centre.
 */
public final class ScalarKernels implements Kernels {

    static final ScalarKernels INSTANCE = new ScalarKernels();

    private ScalarKernels() {}

    @Override
    public String name() {
        return "scalar";
    }

    // ── Shared approximations ────────────────────────────────────────────────

    /**
     * {@code 1/sqrt(x)} to about 5e-6, from an integer-bit guess and two Newton steps: a handful of
     * multiply-adds instead of a square root and a division.
     */
    static float inverseSquareRoot(float x) {
        float half = 0.5f * x;
        float y = Float.intBitsToFloat(0x5f3759df - (Float.floatToRawIntBits(x) >> 1));
        y *= Math.fma(-half * y, y, 1.5f);
        y *= Math.fma(-half * y, y, 1.5f);
        return y;
    }

    // ── Forces ───────────────────────────────────────────────────────────────

    @Override
    public void forces(Bodies b, int from, int to, Grid grid, float dt, float gravity) {
        for (int i = from; i < to; i++) {
            float vx = b.vx[i], vy = b.vy[i] - gravity * dt, vz = b.vz[i];
            float wx = b.wx[i], wy = b.wy[i], wz = b.wz[i];
            float touch = b.touch[i];

            if ((grid.cell(Grid.floor(b.px[i]), Grid.floor(b.py[i]), Grid.floor(b.pz[i])) & Grid.TYPE_MASK) == Grid.LIQUID) {
                vy += gravity * BUOYANCY * dt;
                float k = 1f - LIQUID_DRAG * dt;
                vx *= k; vy *= k; vz *= k;
                wx *= k; wy *= k; wz *= k;
                touch = 1f;
            }

            float linear = 1f - b.drag[i] * dt;
            // Rough contact digs in, so a piece touching something resists rolling
            float angular = 1f - (touch > 0f ? ROLLING_RESISTANCE : 0.2f) * dt;
            b.vx[i] = vx * linear; b.vy[i] = vy * linear; b.vz[i] = vz * linear;
            b.wx[i] = wx * angular; b.wy[i] = wy * angular; b.wz[i] = wz * angular;
            b.touch[i] = touch;
        }
    }

    // ── Terrain contacts ─────────────────────────────────────────────────────

    // Per contact: offset from the centre, one-hot normal axis and its sign, depth, effective masses, bounce target and accumulated impulses
    private static final int RX = 0, RY = 1, RZ = 2, AX = 3, AY = 4, AZ = 5, SIGN = 6, DEPTH = 7, KN = 8, K1 = 9, K2 = 10,
            BOUNCE = 11, ACC_N = 12, ACC_1 = 13, ACC_2 = 14, ACC_P = 15, STRIDE = 16;

    @Override
    public void terrain(Bodies b, int from, int to, Grid grid, float dt) {
        var c = new float[8 * STRIDE];

        for (int i = from; i < to; i++) {
            float qx = b.qx[i], qy = b.qy[i], qz = b.qz[i], qw = b.qw[i];
            float xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wxq = qw * qx, wyq = qw * qy, wzq = qw * qz;
            // Columns of the rotation matrix, already scaled to the corner reach
            float c0x = CORNER * (1f - 2f * (yy + zz)), c0y = CORNER * 2f * (xy + wzq), c0z = CORNER * 2f * (xz - wyq);
            float c1x = CORNER * 2f * (xy - wzq), c1y = CORNER * (1f - 2f * (xx + zz)), c1z = CORNER * 2f * (yz + wxq);
            float c2x = CORNER * 2f * (xz + wyq), c2y = CORNER * 2f * (yz - wxq), c2z = CORNER * (1f - 2f * (xx + yy));

            float vx = b.vx[i], vy = b.vy[i], vz = b.vz[i], wx = b.wx[i], wy = b.wy[i], wz = b.wz[i];
            float px = b.px[i], py = b.py[i], pz = b.pz[i];

            int n = 0;
            for (int corner = 0; corner < 8; corner++) {
                float sa = (corner & 1) == 0 ? -1f : 1f, sb = (corner & 2) == 0 ? -1f : 1f, sc = (corner & 4) == 0 ? -1f : 1f;
                float rx = sa * c0x + sb * c1x + sc * c2x, ry = sa * c0y + sb * c1y + sc * c2y, rz = sa * c0z + sb * c1z + sc * c2z;
                float cpx = px + rx, cpy = py + ry, cpz = pz + rz;

                int ix = Grid.floor(cpx), iy = Grid.floor(cpy), iz = Grid.floor(cpz);
                int cell = grid.cell(ix, iy, iz);
                if ((cell & Grid.TYPE_MASK) != Grid.SOLID)
                    continue;

                // The way out: the nearest open face the point could have come in through (it isn't already leaving
                // through it), so a protrusion sinking into a slab is never pushed up through it block after block
                float velX = vx + (wy * rz - wz * ry), velY = vy + (wz * rx - wx * rz), velZ = vz + (wx * ry - wy * rx);
                float fx = cpx - ix, fy = cpy - iy, fz = cpz - iz;
                float best = Float.MAX_VALUE;
                float ax = 0, ay = 0, az = 0, sign = 1;

                // A slight preference for pushing up, so things resting on flat ground are never shoved off a block edge
                if ((cell & Grid.OPEN_PY) != 0 && velY < MAX_EXIT_SPEED && (1f - fy) - 0.05f < best) { best = (1f - fy) - 0.05f; ax = 0; ay = 1; az = 0; sign = 1; }
                if ((cell & Grid.OPEN_PX) != 0 && velX < MAX_EXIT_SPEED && 1f - fx < best) { best = 1f - fx; ax = 1; ay = 0; az = 0; sign = 1; }
                if ((cell & Grid.OPEN_NX) != 0 && -velX < MAX_EXIT_SPEED && fx < best) { best = fx; ax = 1; ay = 0; az = 0; sign = -1; }
                if ((cell & Grid.OPEN_PZ) != 0 && velZ < MAX_EXIT_SPEED && 1f - fz < best) { best = 1f - fz; ax = 0; ay = 0; az = 1; sign = 1; }
                if ((cell & Grid.OPEN_NZ) != 0 && -velZ < MAX_EXIT_SPEED && fz < best) { best = fz; ax = 0; ay = 0; az = 1; sign = -1; }
                if ((cell & Grid.OPEN_NY) != 0 && -velY < MAX_EXIT_SPEED && fy < best) { best = fy; ax = 0; ay = 1; az = 0; sign = -1; }
                if (best == Float.MAX_VALUE)
                    continue; // buried, or only exits it is already heading through

                int o = n++ * STRIDE;
                c[o + RX] = rx; c[o + RY] = ry; c[o + RZ] = rz;
                c[o + AX] = ax; c[o + AY] = ay; c[o + AZ] = az; c[o + SIGN] = sign;
                c[o + DEPTH] = Math.max(best + (ay == 1f && sign == 1f ? 0.05f : 0f), 0f);
            }
            if (n == 0)
                continue;

            float invMass = b.invMass[i], invInertia = b.invInertia[i], mu = b.mu[i], restitution = b.restitution[i];
            float vpx = b.vpx[i], vpy = b.vpy[i], vpz = b.vpz[i], wpx = b.wpx[i], wpy = b.wpy[i], wpz = b.wpz[i];

            for (int k = 0; k < n; k++) {
                int o = k * STRIDE;
                float rx = c[o + RX], ry = c[o + RY], rz = c[o + RZ], ax = c[o + AX], ay = c[o + AY], az = c[o + AZ];
                // The friction directions are the other two axes: (az, ax, ay) and (ay, az, ax) when the normal is (ax, ay, az)
                float ra = rx * ax + ry * ay + rz * az;
                float r1 = rx * az + ry * ax + rz * ay;
                float r2 = rx * ay + ry * az + rz * ax;
                c[o + KN] = 1f / Math.fma(invInertia, CORNER_REACH_SQ - ra * ra, invMass);
                c[o + K1] = 1f / Math.fma(invInertia, CORNER_REACH_SQ - r1 * r1, invMass);
                c[o + K2] = 1f / Math.fma(invInertia, CORNER_REACH_SQ - r2 * r2, invMass);

                float vn = c[o + SIGN] * ((vx + (wy * rz - wz * ry)) * ax + (vy + (wz * rx - wx * rz)) * ay + (vz + (wx * ry - wy * rx)) * az);
                c[o + BOUNCE] = vn < -BOUNCE_THRESHOLD ? -restitution * vn : 0f;
                c[o + ACC_N] = c[o + ACC_1] = c[o + ACC_2] = c[o + ACC_P] = 0f;
            }

            for (int it = 0; it < ITERATIONS; it++) {
                for (int k = 0; k < n; k++) {
                    int o = k * STRIDE;
                    float rx = c[o + RX], ry = c[o + RY], rz = c[o + RZ], ax = c[o + AX], ay = c[o + AY], az = c[o + AZ], sign = c[o + SIGN];

                    // Normal impulse first, so friction has something to be a fraction of
                    float vd = sign * ((vx + (wy * rz - wz * ry)) * ax + (vy + (wz * rx - wx * rz)) * ay + (vz + (wx * ry - wy * rx)) * az);
                    float accN = c[o + ACC_N];
                    float nextN = Math.max(Math.fma(c[o + BOUNCE] - vd, c[o + KN], accN), 0f);
                    c[o + ACC_N] = nextN;
                    float m = sign * (nextN - accN);
                    float ix = ax * m, iy = ay * m, iz = az * m;
                    vx = Math.fma(ix, invMass, vx); vy = Math.fma(iy, invMass, vy); vz = Math.fma(iz, invMass, vz);
                    wx = Math.fma(ry * iz - rz * iy, invInertia, wx); wy = Math.fma(rz * ix - rx * iz, invInertia, wy); wz = Math.fma(rx * iy - ry * ix, invInertia, wz);

                    float maxFriction = mu * nextN;
                    for (int tangent = 0; tangent < 2; tangent++) {
                        float dx = tangent == 0 ? az : ay, dy = tangent == 0 ? ax : az, dz = tangent == 0 ? ay : ax;
                        float vt = (vx + (wy * rz - wz * ry)) * dx + (vy + (wz * rx - wx * rz)) * dy + (vz + (wx * ry - wy * rx)) * dz;
                        int accSlot = tangent == 0 ? ACC_1 : ACC_2;
                        float acc = c[o + accSlot];
                        float next = Math.min(Math.max(Math.fma(-vt, c[o + (tangent == 0 ? K1 : K2)], acc), -maxFriction), maxFriction);
                        c[o + accSlot] = next;
                        float jx = dx * (next - acc), jy = dy * (next - acc), jz = dz * (next - acc);
                        vx = Math.fma(jx, invMass, vx); vy = Math.fma(jy, invMass, vy); vz = Math.fma(jz, invMass, vz);
                        wx = Math.fma(ry * jz - rz * jy, invInertia, wx); wy = Math.fma(rz * jx - rx * jz, invInertia, wy); wz = Math.fma(rx * jy - ry * jx, invInertia, wz);
                    }
                }
            }

            // Split impulse: push out of the terrain with pseudo velocities, which add no energy
            for (int it = 0; it < ITERATIONS; it++) {
                for (int k = 0; k < n; k++) {
                    int o = k * STRIDE;
                    float target = BAUMGARTE * Math.clamp(c[o + DEPTH] - SLOP, 0f, MAX_CORRECTION) / dt;
                    if (target <= 0f && c[o + ACC_P] == 0f)
                        continue;

                    float rx = c[o + RX], ry = c[o + RY], rz = c[o + RZ], ax = c[o + AX], ay = c[o + AY], az = c[o + AZ], sign = c[o + SIGN];
                    float vn = sign * ((vpx + (wpy * rz - wpz * ry)) * ax + (vpy + (wpz * rx - wpx * rz)) * ay + (vpz + (wpx * ry - wpy * rx)) * az);
                    float accP = c[o + ACC_P];
                    float next = Math.max(Math.fma(target - vn, c[o + KN], accP), 0f);
                    c[o + ACC_P] = next;

                    float m = sign * (next - accP);
                    float ix = ax * m, iy = ay * m, iz = az * m;
                    vpx = Math.fma(ix, invMass, vpx); vpy = Math.fma(iy, invMass, vpy); vpz = Math.fma(iz, invMass, vpz);
                    wpx = Math.fma(ry * iz - rz * iy, invInertia, wpx); wpy = Math.fma(rz * ix - rx * iz, invInertia, wpy); wpz = Math.fma(rx * iy - ry * ix, invInertia, wpz);
                }
            }

            b.vx[i] = vx; b.vy[i] = vy; b.vz[i] = vz; b.wx[i] = wx; b.wy[i] = wy; b.wz[i] = wz;
            b.vpx[i] = vpx; b.vpy[i] = vpy; b.vpz[i] = vpz; b.wpx[i] = wpx; b.wpy[i] = wpy; b.wpz[i] = wpz;
            b.touch[i] = 1f;
        }
    }

    // ── Integration ──────────────────────────────────────────────────────────

    @Override
    public void integrate(Bodies b, int from, int to, float dt) {
        float h = 0.5f * dt;
        for (int i = from; i < to; i++) {
            float vx = b.vx[i], vy = b.vy[i], vz = b.vz[i], wx = b.wx[i], wy = b.wy[i], wz = b.wz[i];

            float speedSquared = Math.fma(vx, vx, Math.fma(vy, vy, vz * vz));
            if (speedSquared > MAX_SPEED * MAX_SPEED) {
                float k = MAX_SPEED * inverseSquareRoot(speedSquared);
                vx *= k; vy *= k; vz *= k;
            }
            float spinSquared = Math.fma(wx, wx, Math.fma(wy, wy, wz * wz));
            if (spinSquared > MAX_SPIN * MAX_SPIN) {
                float k = MAX_SPIN * inverseSquareRoot(spinSquared);
                wx *= k; wy *= k; wz *= k;
            }

            b.px[i] = Math.fma(vx + b.vpx[i], dt, b.px[i]);
            b.py[i] = Math.fma(vy + b.vpy[i], dt, b.py[i]);
            b.pz[i] = Math.fma(vz + b.vpz[i], dt, b.pz[i]);

            // q += dt/2 * (w, 0) * q, renormalised
            float ax = wx + b.wpx[i], ay = wy + b.wpy[i], az = wz + b.wpz[i];
            float qx = b.qx[i], qy = b.qy[i], qz = b.qz[i], qw = b.qw[i];
            float nqx = Math.fma(Math.fma(ax, qw, Math.fma(ay, qz, -az * qy)), h, qx);
            float nqy = Math.fma(Math.fma(-ax, qz, Math.fma(ay, qw, az * qx)), h, qy);
            float nqz = Math.fma(Math.fma(ax, qy, Math.fma(-ay, qx, az * qw)), h, qz);
            float nqw = Math.fma(Math.fma(-ax, qx, Math.fma(-ay, qy, -az * qz)), h, qw);
            // The quaternion is within a few percent of unit length, where 1/sqrt(n) is 1.5 - n/2 to better than 1e-3
            float inv = Math.fma(-0.5f, Math.fma(nqx, nqx, Math.fma(nqy, nqy, Math.fma(nqz, nqz, nqw * nqw))), 1.5f);
            b.qx[i] = nqx * inv; b.qy[i] = nqy * inv; b.qz[i] = nqz * inv; b.qw[i] = nqw * inv;

            b.vx[i] = vx; b.vy[i] = vy; b.vz[i] = vz; b.wx[i] = wx; b.wy[i] = wy; b.wz[i] = wz;
            b.vpx[i] = 0f; b.vpy[i] = 0f; b.vpz[i] = 0f; b.wpx[i] = 0f; b.wpy[i] = 0f; b.wpz[i] = 0f;
        }
    }

    // ── Calm ─────────────────────────────────────────────────────────────────

    @Override
    public void calm(Bodies b, int from, int to) {
        for (int i = from; i < to; i++) {
            boolean calm = b.touch[i] > 0f
                    && b.vx[i] * b.vx[i] + b.vy[i] * b.vy[i] + b.vz[i] * b.vz[i] < 0.25f * 0.25f
                    && b.wx[i] * b.wx[i] + b.wy[i] * b.wy[i] + b.wz[i] * b.wz[i] < 0.5f * 0.5f;
            b.calm[i] = calm ? b.calm[i] + 1 : 0;
        }
    }

}
