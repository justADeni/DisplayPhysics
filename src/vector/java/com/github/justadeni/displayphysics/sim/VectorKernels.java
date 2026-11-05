package com.github.justadeni.displayphysics.sim;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

import static com.github.justadeni.displayphysics.sim.Tuning.*;

/**
 * Hybrid kernels: a scalar pass probes the corners of each piece (branchy byte lookups) and copies every piece
 * that has contacts, with its contacts, into dense per-group structure-of-arrays records; the SIMD solver then
 * streams over those with plain vector loads. Pieces are grouped by their number of contacts, so every lane of a
 * vector runs the same number of contact rounds. A final scalar pass writes the velocities back.
 */
public final class VectorKernels implements Kernels {

    private static final VectorSpecies<Float> SP = FloatVector.SPECIES_PREFERRED;
    private static final int L = SP.length();

    /** Pieces handled per chunk: keeps the scratch records in cache. */
    private static final int CH = 1024;
    private static final int GS = CH + L;           // one group's capacity (plus a vector of slack for the tail)
    private static final int CHS = CH + L;
    private static final int GROUPS = 9;            // groups of 0..8 contacts (0 unused)
    private static final int TOT = GROUPS * GS;

    // state fields
    private static final int VX = 0, VY = 1, VZ = 2, WX = 3, WY = 4, WZ = 5, VPX = 6, VPY = 7, VPZ = 8, WPX = 9, WPY = 10, WPZ = 11,
            IM = 12, II = 13, MU = 14, RE = 15, SF = 16;
    // contact fields
    private static final int RX = 0, RY = 1, RZ = 2, AX = 3, AY = 4, AZ = 5, SG = 6, TG = 7, KN = 8, K1 = 9, K2 = 10, BO = 11,
            ACN = 12, AC1 = 13, AC2 = 14, ACP = 15, CF = 16;

    private static final class Scratch {
        final float[] s = new float[SF * TOT];
        final float[] c = new float[8 * CF * TOT];
        final int[] id = new int[TOT];
        final int[] cursor = new int[GROUPS];
        final float[] tmp = new float[8 * 8];
        final int[] cornerCell = new int[8 * CHS];
        final int[] hits = new int[CH + 1], hitMask = new int[CH + 1];
        int hitCount;
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Debug: ns in probe+copy-in, solve, copy-out; pieces with contacts; pieces. */
    public static final long[] TIME = new long[7];

    private final Kernels scalar = Kernels.scalar();

    public VectorKernels() {}

    @Override
    public String name() {
        return "vector-hybrid";
    }

    @Override
    public void forces(Bodies b, int from, int to, Grid grid, float dt, float gravity) {
        scalar.forces(b, from, to, grid, dt, gravity);
    }

    @Override
    public void integrate(Bodies b, int from, int to, float dt) {
        scalar.integrate(b, from, to, dt);
    }

    @Override
    public void calm(Bodies b, int from, int to) {
        scalar.calm(b, from, to);
    }

    @Override
    public void terrain(Bodies b, int from, int to, Grid grid, float dt) {
        var sc = SCRATCH.get();
        for (int start = from; start < to; start += CH) {
            int end = Math.min(to, start + CH);
            long t0 = System.nanoTime();
            probe(sc, b, start, end, grid, BAUMGARTE / dt);
            long t1 = System.nanoTime();
            for (int g = 1; g < GROUPS; g++) {
                int n = sc.cursor[g];
                if (n > 0)
                    solve(sc, g, g * GS, g * GS + n);
            }
            long t2 = System.nanoTime();
            writeBack(sc, b);
            long t3 = System.nanoTime();
            TIME[0] += t1 - t0; TIME[1] += t2 - t1; TIME[2] += t3 - t2;
            int ct = 0; for (int g = 1; g < GROUPS; g++) ct += sc.cursor[g];
            TIME[3] += ct; TIME[4] += end - start;
        }
    }

    // ── Scalar probe + copy-in ───────────────────────────────────────────────

    /** SIMD: the cell index of each of the eight corners of every piece; scalar: which pieces have a corner in a solid voxel. */
    private static void prefilter(Scratch sc, Bodies b, int from, int to, Grid grid) {
        final int[] cc = sc.cornerCell, hits = sc.hits, hitMask = sc.hitMask;
        final byte[] cells = grid.cells;
        for (int i = from; i < to; i += L)
            corners(cc, i - from, b, i, SP.indexInRange(i, to), grid.x0, grid.y0, grid.z0, grid.sx, grid.sz);
        int nh = 0;
        final int n = to - from;
        for (int j = 0; j < n; j++) {
            int t0 = cells[cc[j]], t1 = cells[cc[CHS + j]], t2 = cells[cc[2 * CHS + j]], t3 = cells[cc[3 * CHS + j]];
            int t4 = cells[cc[4 * CHS + j]], t5 = cells[cc[5 * CHS + j]], t6 = cells[cc[6 * CHS + j]], t7 = cells[cc[7 * CHS + j]];
            // solid is type 1: bit 0 set and bit 1 clear
            int m = (t0 & ~(t0 >> 1) & 1) | ((t1 & ~(t1 >> 1) & 1) << 1) | ((t2 & ~(t2 >> 1) & 1) << 2) | ((t3 & ~(t3 >> 1) & 1) << 3)
                    | ((t4 & ~(t4 >> 1) & 1) << 4) | ((t5 & ~(t5 >> 1) & 1) << 5) | ((t6 & ~(t6 >> 1) & 1) << 6) | ((t7 & ~(t7 >> 1) & 1) << 7);
            hits[nh] = from + j;
            hitMask[nh] = m;
            nh += (m | -m) >>> 31;
        }
        sc.hitCount = nh;
    }

    private static void corners(int[] cc, int j, Bodies b, int i, VectorMask<Float> in, int x0, int y0, int z0, int sx, int sz) {
        FloatVector qx = FloatVector.fromArray(SP, b.qx, i, in), qy = FloatVector.fromArray(SP, b.qy, i, in);
        FloatVector qz = FloatVector.fromArray(SP, b.qz, i, in), qw = FloatVector.fromArray(SP, b.qw, i, in);
        FloatVector xx = qx.mul(qx), yy = qy.mul(qy), zz = qz.mul(qz), xy = qx.mul(qy), xz = qx.mul(qz), yz = qy.mul(qz);
        FloatVector wx = qw.mul(qx), wy = qw.mul(qy), wz = qw.mul(qz);
        FloatVector k = FloatVector.broadcast(SP, CORNER), k2 = FloatVector.broadcast(SP, 2f * CORNER), one = FloatVector.broadcast(SP, 1f);
        FloatVector two = FloatVector.broadcast(SP, 2f);
        FloatVector c0x = k.mul(one.sub(two.mul(yy.add(zz)))), c0y = k2.mul(xy.add(wz)), c0z = k2.mul(xz.sub(wy));
        FloatVector c1x = k2.mul(xy.sub(wz)), c1y = k.mul(one.sub(two.mul(xx.add(zz)))), c1z = k2.mul(yz.add(wx));
        FloatVector c2x = k2.mul(xz.add(wy)), c2y = k2.mul(yz.sub(wx)), c2z = k.mul(one.sub(two.mul(xx.add(yy))));
        FloatVector px = FloatVector.fromArray(SP, b.px, i, in).sub((float) x0);
        FloatVector py = FloatVector.fromArray(SP, b.py, i, in).sub((float) y0);
        FloatVector pz = FloatVector.fromArray(SP, b.pz, i, in).sub((float) z0);
        // corner = sa*c0 + sb*c1 + sc*c2, bit 0 = sa, bit 1 = sb, bit 2 = sc
        FloatVector ABx0 = c0x.add(c1x), ABx1 = c1x.sub(c0x), ABx2 = c0x.sub(c1x);
        FloatVector ABy0 = c0y.add(c1y), ABy1 = c1y.sub(c0y), ABy2 = c0y.sub(c1y);
        FloatVector ABz0 = c0z.add(c1z), ABz1 = c1z.sub(c0z), ABz2 = c0z.sub(c1z);
        for (int corner = 0; corner < 8; corner++) {
            int ab = corner & 3;
            FloatVector ax = ab == 0 ? ABx0.neg() : ab == 1 ? ABx2 : ab == 2 ? ABx1 : ABx0;
            FloatVector ay = ab == 0 ? ABy0.neg() : ab == 1 ? ABy2 : ab == 2 ? ABy1 : ABy0;
            FloatVector az = ab == 0 ? ABz0.neg() : ab == 1 ? ABz2 : ab == 2 ? ABz1 : ABz0;
            boolean up = corner >= 4;
            FloatVector cx = px.add(up ? ax.add(c2x) : ax.sub(c2x));
            FloatVector cy = py.add(up ? ay.add(c2y) : ay.sub(c2y));
            FloatVector cz = pz.add(up ? az.add(c2z) : az.sub(c2z));
            IntVector ix = cx.convert(VectorOperators.F2I, 0).reinterpretAsInts();
            IntVector iy = cy.convert(VectorOperators.F2I, 0).reinterpretAsInts();
            IntVector iz = cz.convert(VectorOperators.F2I, 0).reinterpretAsInts();
            iy.mul(sz).add(iz).mul(sx).add(ix).intoArray(cc, corner * CHS + j);
        }
    }

    private static void probe(Scratch sc, Bodies b, int from, int to, Grid grid, float targetScale) {
        long q0 = System.nanoTime();
        prefilter(sc, b, from, to, grid);
        TIME[5] += System.nanoTime() - q0; TIME[6] += sc.hitCount;
        final int[] cursor = sc.cursor;
        java.util.Arrays.fill(cursor, 0);
        final float[] s = sc.s, c = sc.c, t = sc.tmp;
        final int[] ids = sc.id;
        final byte[] cells = grid.cells;
        final int x0 = grid.x0, y0 = grid.y0, z0 = grid.z0, sx = grid.sx, sz = grid.sz;

        final int[] hits = sc.hits, hitMask = sc.hitMask;
        for (int h = 0; h < sc.hitCount; h++) {
            int i = hits[h];
            float qx = b.qx[i], qy = b.qy[i], qz = b.qz[i], qw = b.qw[i];
            float xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wxq = qw * qx, wyq = qw * qy, wzq = qw * qz;
            float c0x = CORNER * (1f - 2f * (yy + zz)), c0y = CORNER * 2f * (xy + wzq), c0z = CORNER * 2f * (xz - wyq);
            float c1x = CORNER * 2f * (xy - wzq), c1y = CORNER * (1f - 2f * (xx + zz)), c1z = CORNER * 2f * (yz + wxq);
            float c2x = CORNER * 2f * (xz + wyq), c2y = CORNER * 2f * (yz - wxq), c2z = CORNER * (1f - 2f * (xx + yy));

            float vx = b.vx[i], vy = b.vy[i], vz = b.vz[i], wx = b.wx[i], wy = b.wy[i], wz = b.wz[i];
            float px = b.px[i], py = b.py[i], pz = b.pz[i];

            int n = 0;
            for (int mask = hitMask[h]; mask != 0; mask &= mask - 1) {
                int corner = Integer.numberOfTrailingZeros(mask);
                float sa = (corner & 1) == 0 ? -1f : 1f, sb = (corner & 2) == 0 ? -1f : 1f, sc2 = (corner & 4) == 0 ? -1f : 1f;
                float rx = sa * c0x + sb * c1x + sc2 * c2x, ry = sa * c0y + sb * c1y + sc2 * c2y, rz = sa * c0z + sb * c1z + sc2 * c2z;
                float cpx = px + rx, cpy = py + ry, cpz = pz + rz;

                int ix = Grid.floor(cpx), iy = Grid.floor(cpy), iz = Grid.floor(cpz);
                int cell = cells[((iy - y0) * sz + (iz - z0)) * sx + (ix - x0)] & 0xFF;

                float velX = vx + (wy * rz - wz * ry), velY = vy + (wz * rx - wx * rz), velZ = vz + (wx * ry - wy * rx);
                float fx = cpx - ix, fy = cpy - iy, fz = cpz - iz;
                float best = Float.MAX_VALUE;
                float ax = 0, ay = 0, az = 0, sign = 1;

                if ((cell & Grid.OPEN_PY) != 0 && velY < MAX_EXIT_SPEED && (1f - fy) - 0.05f < best) { best = (1f - fy) - 0.05f; ax = 0; ay = 1; az = 0; sign = 1; }
                if ((cell & Grid.OPEN_PX) != 0 && velX < MAX_EXIT_SPEED && 1f - fx < best) { best = 1f - fx; ax = 1; ay = 0; az = 0; sign = 1; }
                if ((cell & Grid.OPEN_NX) != 0 && -velX < MAX_EXIT_SPEED && fx < best) { best = fx; ax = 1; ay = 0; az = 0; sign = -1; }
                if ((cell & Grid.OPEN_PZ) != 0 && velZ < MAX_EXIT_SPEED && 1f - fz < best) { best = 1f - fz; ax = 0; ay = 0; az = 1; sign = 1; }
                if ((cell & Grid.OPEN_NZ) != 0 && -velZ < MAX_EXIT_SPEED && fz < best) { best = fz; ax = 0; ay = 0; az = 1; sign = -1; }
                if ((cell & Grid.OPEN_NY) != 0 && -velY < MAX_EXIT_SPEED && fy < best) { best = fy; ax = 0; ay = 1; az = 0; sign = -1; }
                if (best == Float.MAX_VALUE)
                    continue;

                int o = n++ * 8;
                t[o] = rx; t[o + 1] = ry; t[o + 2] = rz;
                t[o + 3] = ax; t[o + 4] = ay; t[o + 5] = az; t[o + 6] = sign;
                t[o + 7] = Math.max(best + (ay == 1f && sign == 1f ? 0.05f : 0f), 0f);
            }
            if (n == 0)
                continue;

            int pos = n * GS + cursor[n]++;
            ids[pos] = i;
            s[VX * TOT + pos] = vx; s[VY * TOT + pos] = vy; s[VZ * TOT + pos] = vz;
            s[WX * TOT + pos] = wx; s[WY * TOT + pos] = wy; s[WZ * TOT + pos] = wz;
            s[VPX * TOT + pos] = b.vpx[i]; s[VPY * TOT + pos] = b.vpy[i]; s[VPZ * TOT + pos] = b.vpz[i];
            s[WPX * TOT + pos] = b.wpx[i]; s[WPY * TOT + pos] = b.wpy[i]; s[WPZ * TOT + pos] = b.wpz[i];
            s[IM * TOT + pos] = b.invMass[i]; s[II * TOT + pos] = b.invInertia[i];
            s[MU * TOT + pos] = b.mu[i]; s[RE * TOT + pos] = b.restitution[i];
            for (int k = 0; k < n; k++) {
                int base = k * CF * TOT + pos, o = k * 8;
                c[base + RX * TOT] = t[o]; c[base + RY * TOT] = t[o + 1]; c[base + RZ * TOT] = t[o + 2];
                c[base + AX * TOT] = t[o + 3]; c[base + AY * TOT] = t[o + 4]; c[base + AZ * TOT] = t[o + 5];
                c[base + SG * TOT] = t[o + 6];
                c[base + TG * TOT] = Math.clamp(t[o + 7] - SLOP, 0f, MAX_CORRECTION) * targetScale;
            }
        }
    }

    // ── SIMD solver over one group ───────────────────────────────────────────

    private static void solve(Scratch sc, int g, int from, int to) {
        for (int p = from; p < to; p += L)
            solveBlock(sc.s, sc.c, g, p);
    }

    private static void solveBlock(float[] s, float[] c, int g, int p) {
        final FloatVector reach = FloatVector.broadcast(SP, CORNER_REACH_SQ);
        final FloatVector zero = FloatVector.zero(SP), one = FloatVector.broadcast(SP, 1f);
        final FloatVector bt = FloatVector.broadcast(SP, -BOUNCE_THRESHOLD);

        {
            FloatVector vx = FloatVector.fromArray(SP, s, VX * TOT + p), vy = FloatVector.fromArray(SP, s, VY * TOT + p), vz = FloatVector.fromArray(SP, s, VZ * TOT + p);
            FloatVector wx = FloatVector.fromArray(SP, s, WX * TOT + p), wy = FloatVector.fromArray(SP, s, WY * TOT + p), wz = FloatVector.fromArray(SP, s, WZ * TOT + p);
            final FloatVector im = FloatVector.fromArray(SP, s, IM * TOT + p), ii = FloatVector.fromArray(SP, s, II * TOT + p);
            final FloatVector mu = FloatVector.fromArray(SP, s, MU * TOT + p), re = FloatVector.fromArray(SP, s, RE * TOT + p);

            for (int k = 0; k < g; k++) {
                int base = k * CF * TOT + p;
                FloatVector rx = FloatVector.fromArray(SP, c, base + RX * TOT), ry = FloatVector.fromArray(SP, c, base + RY * TOT), rz = FloatVector.fromArray(SP, c, base + RZ * TOT);
                FloatVector ax = FloatVector.fromArray(SP, c, base + AX * TOT), ay = FloatVector.fromArray(SP, c, base + AY * TOT), az = FloatVector.fromArray(SP, c, base + AZ * TOT);
                FloatVector sg = FloatVector.fromArray(SP, c, base + SG * TOT);
                FloatVector ra = rx.mul(ax).add(ry.mul(ay)).add(rz.mul(az));
                FloatVector r1 = rx.mul(az).add(ry.mul(ax)).add(rz.mul(ay));
                FloatVector r2 = rx.mul(ay).add(ry.mul(az)).add(rz.mul(ax));
                one.div(ii.fma(reach.sub(ra.mul(ra)), im)).intoArray(c, base + KN * TOT);
                one.div(ii.fma(reach.sub(r1.mul(r1)), im)).intoArray(c, base + K1 * TOT);
                one.div(ii.fma(reach.sub(r2.mul(r2)), im)).intoArray(c, base + K2 * TOT);
                FloatVector relx = wy.mul(rz).sub(wz.mul(ry)).add(vx), rely = wz.mul(rx).sub(wx.mul(rz)).add(vy), relz = wx.mul(ry).sub(wy.mul(rx)).add(vz);
                FloatVector vn = sg.mul(relx.mul(ax).add(rely.mul(ay)).add(relz.mul(az)));
                zero.blend(re.neg().mul(vn), vn.lt(bt)).intoArray(c, base + BO * TOT);
                zero.intoArray(c, base + ACN * TOT);
                zero.intoArray(c, base + AC1 * TOT);
                zero.intoArray(c, base + AC2 * TOT);
                zero.intoArray(c, base + ACP * TOT);
            }

            for (int it = 0; it < ITERATIONS; it++) {
                for (int k = 0; k < g; k++) {
                    int base = k * CF * TOT + p;
                    FloatVector rx = FloatVector.fromArray(SP, c, base + RX * TOT), ry = FloatVector.fromArray(SP, c, base + RY * TOT), rz = FloatVector.fromArray(SP, c, base + RZ * TOT);
                    FloatVector ax = FloatVector.fromArray(SP, c, base + AX * TOT), ay = FloatVector.fromArray(SP, c, base + AY * TOT), az = FloatVector.fromArray(SP, c, base + AZ * TOT);
                    FloatVector sg = FloatVector.fromArray(SP, c, base + SG * TOT);

                    FloatVector relx = wy.mul(rz).sub(wz.mul(ry)).add(vx), rely = wz.mul(rx).sub(wx.mul(rz)).add(vy), relz = wx.mul(ry).sub(wy.mul(rx)).add(vz);
                    FloatVector vd = sg.mul(relx.mul(ax).add(rely.mul(ay)).add(relz.mul(az)));
                    FloatVector accN = FloatVector.fromArray(SP, c, base + ACN * TOT);
                    FloatVector nextN = FloatVector.fromArray(SP, c, base + BO * TOT).sub(vd).fma(FloatVector.fromArray(SP, c, base + KN * TOT), accN).max(zero);
                    nextN.intoArray(c, base + ACN * TOT);
                    FloatVector m = sg.mul(nextN.sub(accN));
                    FloatVector ix = ax.mul(m), iy = ay.mul(m), iz = az.mul(m);
                    vx = ix.fma(im, vx); vy = iy.fma(im, vy); vz = iz.fma(im, vz);
                    wx = ry.mul(iz).sub(rz.mul(iy)).fma(ii, wx);
                    wy = rz.mul(ix).sub(rx.mul(iz)).fma(ii, wy);
                    wz = rx.mul(iy).sub(ry.mul(ix)).fma(ii, wz);

                    FloatVector maxF = mu.mul(nextN), minF = maxF.neg();
                    // tangent 1: (az, ax, ay)
                    {
                        FloatVector vt = wy.mul(rz).sub(wz.mul(ry)).add(vx).mul(az).add(wz.mul(rx).sub(wx.mul(rz)).add(vy).mul(ax)).add(wx.mul(ry).sub(wy.mul(rx)).add(vz).mul(ay));
                        FloatVector acc = FloatVector.fromArray(SP, c, base + AC1 * TOT);
                        FloatVector next = vt.neg().fma(FloatVector.fromArray(SP, c, base + K1 * TOT), acc).max(minF).min(maxF);
                        next.intoArray(c, base + AC1 * TOT);
                        FloatVector d = next.sub(acc);
                        FloatVector jx = az.mul(d), jy = ax.mul(d), jz = ay.mul(d);
                        vx = jx.fma(im, vx); vy = jy.fma(im, vy); vz = jz.fma(im, vz);
                        wx = ry.mul(jz).sub(rz.mul(jy)).fma(ii, wx);
                        wy = rz.mul(jx).sub(rx.mul(jz)).fma(ii, wy);
                        wz = rx.mul(jy).sub(ry.mul(jx)).fma(ii, wz);
                    }
                    // tangent 2: (ay, az, ax)
                    {
                        FloatVector vt = wy.mul(rz).sub(wz.mul(ry)).add(vx).mul(ay).add(wz.mul(rx).sub(wx.mul(rz)).add(vy).mul(az)).add(wx.mul(ry).sub(wy.mul(rx)).add(vz).mul(ax));
                        FloatVector acc = FloatVector.fromArray(SP, c, base + AC2 * TOT);
                        FloatVector next = vt.neg().fma(FloatVector.fromArray(SP, c, base + K2 * TOT), acc).max(minF).min(maxF);
                        next.intoArray(c, base + AC2 * TOT);
                        FloatVector d = next.sub(acc);
                        FloatVector jx = ay.mul(d), jy = az.mul(d), jz = ax.mul(d);
                        vx = jx.fma(im, vx); vy = jy.fma(im, vy); vz = jz.fma(im, vz);
                        wx = ry.mul(jz).sub(rz.mul(jy)).fma(ii, wx);
                        wy = rz.mul(jx).sub(rx.mul(jz)).fma(ii, wy);
                        wz = rx.mul(jy).sub(ry.mul(jx)).fma(ii, wz);
                    }
                }
            }

            FloatVector vpx = FloatVector.fromArray(SP, s, VPX * TOT + p), vpy = FloatVector.fromArray(SP, s, VPY * TOT + p), vpz = FloatVector.fromArray(SP, s, VPZ * TOT + p);
            FloatVector wpx = FloatVector.fromArray(SP, s, WPX * TOT + p), wpy = FloatVector.fromArray(SP, s, WPY * TOT + p), wpz = FloatVector.fromArray(SP, s, WPZ * TOT + p);
            for (int it = 0; it < ITERATIONS; it++) {
                for (int k = 0; k < g; k++) {
                    int base = k * CF * TOT + p;
                    FloatVector target = FloatVector.fromArray(SP, c, base + TG * TOT);
                    FloatVector accP = FloatVector.fromArray(SP, c, base + ACP * TOT);
                    VectorMask<Float> active = target.compare(VectorOperators.GT, 0f).or(accP.compare(VectorOperators.NE, 0f));
                    FloatVector rx = FloatVector.fromArray(SP, c, base + RX * TOT), ry = FloatVector.fromArray(SP, c, base + RY * TOT), rz = FloatVector.fromArray(SP, c, base + RZ * TOT);
                    FloatVector ax = FloatVector.fromArray(SP, c, base + AX * TOT), ay = FloatVector.fromArray(SP, c, base + AY * TOT), az = FloatVector.fromArray(SP, c, base + AZ * TOT);
                    FloatVector sg = FloatVector.fromArray(SP, c, base + SG * TOT);
                    FloatVector vn = sg.mul(wpy.mul(rz).sub(wpz.mul(ry)).add(vpx).mul(ax).add(wpz.mul(rx).sub(wpx.mul(rz)).add(vpy).mul(ay)).add(wpx.mul(ry).sub(wpy.mul(rx)).add(vpz).mul(az)));
                    FloatVector next = target.sub(vn).fma(FloatVector.fromArray(SP, c, base + KN * TOT), accP).max(zero);
                    next = accP.blend(next, active);
                    next.intoArray(c, base + ACP * TOT);
                    FloatVector m = sg.mul(next.sub(accP));
                    FloatVector ix = ax.mul(m), iy = ay.mul(m), iz = az.mul(m);
                    vpx = ix.fma(im, vpx); vpy = iy.fma(im, vpy); vpz = iz.fma(im, vpz);
                    wpx = ry.mul(iz).sub(rz.mul(iy)).fma(ii, wpx);
                    wpy = rz.mul(ix).sub(rx.mul(iz)).fma(ii, wpy);
                    wpz = rx.mul(iy).sub(ry.mul(ix)).fma(ii, wpz);
                }
            }

            vx.intoArray(s, VX * TOT + p); vy.intoArray(s, VY * TOT + p); vz.intoArray(s, VZ * TOT + p);
            wx.intoArray(s, WX * TOT + p); wy.intoArray(s, WY * TOT + p); wz.intoArray(s, WZ * TOT + p);
            vpx.intoArray(s, VPX * TOT + p); vpy.intoArray(s, VPY * TOT + p); vpz.intoArray(s, VPZ * TOT + p);
            wpx.intoArray(s, WPX * TOT + p); wpy.intoArray(s, WPY * TOT + p); wpz.intoArray(s, WPZ * TOT + p);
        }
    }

    // ── Scalar copy-out ──────────────────────────────────────────────────────

    private static void writeBack(Scratch sc, Bodies b) {
        final float[] s = sc.s;
        final int[] ids = sc.id;
        for (int g = 1; g < GROUPS; g++) {
            int base = g * GS, n = sc.cursor[g];
            for (int pos = base; pos < base + n; pos++) {
                int i = ids[pos];
                b.vx[i] = s[VX * TOT + pos]; b.vy[i] = s[VY * TOT + pos]; b.vz[i] = s[VZ * TOT + pos];
                b.wx[i] = s[WX * TOT + pos]; b.wy[i] = s[WY * TOT + pos]; b.wz[i] = s[WZ * TOT + pos];
                b.vpx[i] = s[VPX * TOT + pos]; b.vpy[i] = s[VPY * TOT + pos]; b.vpz[i] = s[VPZ * TOT + pos];
                b.wpx[i] = s[WPX * TOT + pos]; b.wpy[i] = s[WPY * TOT + pos]; b.wpz[i] = s[WPZ * TOT + pos];
                b.touch[i] = 1f;
            }
        }
    }
}
