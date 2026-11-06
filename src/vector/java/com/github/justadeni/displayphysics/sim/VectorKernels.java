package com.github.justadeni.displayphysics.sim;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

import static com.github.justadeni.displayphysics.sim.Tuning.*;

/**
 * SIMD kernels. The terrain kernel works in small static passes over a chunk of pieces that talk through reusable
 * scratch rows only (no vector lives across a method boundary, which keeps C2 from boxing them):
 * <ol>
 *   <li>cull: a piece whose centre voxel has no terrain within one voxel cannot touch anything (flag
 *       {@link Grid#NEAR}); the others are compressed into a candidate list;</li>
 *   <li>probe: the eight corners of the candidates against the grid, one gather each; only the "corner is in a solid
 *       voxel" bit is kept, and the candidates are listed by how many corners that is;</li>
 *   <li>solve, per count: vectors of pieces that all have the same number of solid corners, so no lane idles. The
 *       way out of each voxel is chosen there, once per contact slot, on dense lanes.</li>
 * </ol>
 */
public final class VectorKernels implements Kernels {

    private static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED;
    private static final int L = F.length();

    /** Pieces per chunk. */
    private static final int CHUNK = 2048;
    private static final int CAP = CHUNK + 2 * L;

    // Scratch floats per contact slot (one vector each)
    private static final int RX = 0, RY = 1, RZ = 2, NX = 3, NY = 4, NZ = 5, AX = 6, AY = 7, AZ = 8, KN = 9, K1 = 10, K2 = 11,
            BOUNCE = 12, TARGET = 13, ACC_N = 14, ACC_1 = 15, ACC_2 = 16, ACC_P = 17, CN1 = 18, CN2 = 19, C12 = 20, NF = 21;

    // Per-vector state rows
    private static final int VX = 0, VY = 1, VZ = 2, WX = 3, WY = 4, WZ = 5, IM = 6, II = 7, MU = 8, VPX = 9, VPY = 10, VPZ = 11,
            WPX = 12, WPY = 13, WPZ = 14, RES = 15, PX = 16, PY = 17, PZ = 18, C0X = 19, C0Y = 20, C0Z = 21, C1X = 22, C1Y = 23,
            C1Z = 24, C2X = 25, C2Y = 26, C2Z = 27, NS = 28;

    /** Everything one group vector needs while it is solved. Two of them are solved side by side so their dependency chains overlap. */
    private static final class Ctx {
        final int[] idx = new int[L], pidx = new int[L], pos = new int[L];
        /** The corners still to turn into contacts, and whether a lane got any, per lane. */
        final int[] left = new int[L], got = new int[L];
        final int[] cell = new int[L], code = new int[L];
        final float[] f = new float[8 * NF * L];
        final float[] cs = new float[8 * L];
        final float[] st = new float[NS * L];
    }

    private static final class Scratch {
        /** Candidate piece indices. */
        final int[] cand = new int[CAP];
        /** Per candidate: bit c set when corner c is inside a solid voxel. */
        final int[] bits = new int[CAP];
        /** Candidates (positions in cand) with k solid corners: k * CAP + n. */
        final int[] hit = new int[9 * CAP];
        final int[] hitN = new int[9];
        final int[] idx = new int[L], row = new int[L], pi = new int[2 * L];
        final float[] pc = new float[18 * L], dn = new float[ND * CAP];
        final Ctx x0 = new Ctx(), x1 = new Ctx();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    public VectorKernels() {
        if (I.length() != L)
            throw new IllegalStateException("float and int lanes differ");
    }

    @Override
    public String name() {
        return "vector";
    }

    private static IntVector trunc(FloatVector v) {
        return (IntVector) v.convert(VectorOperators.F2I, 0);
    }

    private static FloatVector toFloat(IntVector v) {
        return (FloatVector) v.convert(VectorOperators.I2F, 0);
    }

    // ── Terrain ──────────────────────────────────────────────────────────────

    @Override
    public void terrain(Bodies b, int from, int to, Grid grid, float dt) {
        Scratch s = SCRATCH.get();
        for (int base = from; base < to; base += CHUNK) {
            int end = Math.min(base + CHUNK, to);
            long t0 = PROFILE ? System.nanoTime() : 0;
            int nc = cull(b, grid, s, base, end);
            if (PROFILE) { long t = System.nanoTime(); TIME[0] += t - t0; t0 = t; TIME[5] += nc; }
            if (nc == 0)
                continue;
            Arrays.fill(s.hitN, 0);
            for (int j = 0; j < nc; j += L)
                probe(grid, s, j, nc);
            if (PROFILE) { long t = System.nanoTime(); TIME[1] += t - t0; }
            for (int k = 1; k <= 8; k++) {
                int n = s.hitN[k];
                for (int h = 0; h < n; h += 2 * L)
                    solve(b, grid, s, k, h, n, dt);
            }
        }
    }

    /** floor(v) as ScalarKernels does it: truncation, minus one where that rounded up (negative values). */
    private static IntVector floor(FloatVector v) {
        IntVector t = trunc(v);
        return t.sub(1, v.compare(VectorOperators.LT, toFloat(t)).cast(I));
    }

    // Rows of the candidate arrays dn: the orientation and position of each candidate
    private static final int DQX = 0, DQY = 1, DQZ = 2, DQW = 3, DPX = 4, DPY = 5, DPZ = 6, ND = 7;

    /**
     * Pieces whose centre voxel has no exposed terrain within one voxel cannot touch anything. The others are
     * compressed into the candidate list: their index, orientation and position, densely, so the probe streams.
     */
    private static int cull(Bodies b, Grid grid, Scratch s, int from, int to) {
        int nc = 0;
        final int[] idx = s.idx, cand = s.cand, nbr = grid.nbr;
        final float[] dn = s.dn;
        final int sx = grid.sx, sz = grid.sz, base = (grid.y0 * sz + grid.z0) * sx + grid.x0;
        final IntVector iota = IntVector.zero(I).addIndex(1);
        for (int i = from; i < to; i += L) {
            VectorMask<Float> m = F.indexInRange(i, to);
            IntVector ix = floor(FloatVector.fromArray(F, b.px, i, m));
            IntVector iy = floor(FloatVector.fromArray(F, b.py, i, m));
            IntVector iz = floor(FloatVector.fromArray(F, b.pz, i, m));
            VectorMask<Integer> mi = m.cast(I);
            iy.mul(sz).add(iz).mul(sx).add(ix).sub(base).blend(0, mi.not()).intoArray(idx, 0);
            VectorMask<Integer> near = IntVector.fromArray(I, nbr, 0, idx, 0).compare(VectorOperators.NE, 0).and(mi);
            int count = near.trueCount();
            if (count == 0)
                continue;
            VectorMask<Float> nf = near.cast(F);
            iota.add(i).compress(near).intoArray(cand, nc);
            FloatVector.fromArray(F, b.qx, i, m).compress(nf).intoArray(dn, DQX * CAP + nc);
            FloatVector.fromArray(F, b.qy, i, m).compress(nf).intoArray(dn, DQY * CAP + nc);
            FloatVector.fromArray(F, b.qz, i, m).compress(nf).intoArray(dn, DQZ * CAP + nc);
            FloatVector.fromArray(F, b.qw, i, m).compress(nf).intoArray(dn, DQW * CAP + nc);
            FloatVector.fromArray(F, b.px, i, m).compress(nf).intoArray(dn, DPX * CAP + nc);
            FloatVector.fromArray(F, b.py, i, m).compress(nf).intoArray(dn, DPY * CAP + nc);
            FloatVector.fromArray(F, b.pz, i, m).compress(nf).intoArray(dn, DPZ * CAP + nc);
            nc += count;
        }
        // The tail of the last probe vector repeats the last candidate, so its gather stays in range
        for (int r = 0; r < ND && nc > 0; r++)
            Arrays.fill(dn, r * CAP + nc, r * CAP + nc + L, dn[r * CAP + nc - 1]);
        return nc;
    }

    private static void probe(Grid grid, Scratch s, int j0, int nc) {
        final int[] idx = s.idx, wide = grid.wide;
        final float[] pc = s.pc;
        VectorMask<Integer> mi = I.indexInRange(j0, nc);
        IntVector jv = IntVector.zero(I).addIndex(1).add(j0).blend(j0, mi.not());
        final int sx = grid.sx, sz = grid.sz, base = (grid.y0 * sz + grid.z0) * sx + grid.x0;
        corners(grid.nbr, s, j0, sx, sz, base);
        IntVector.zero(I).intoArray(s.row, 0);
        for (int c = 0; c < 8; c++)
            probeCorner(pc, c, sx, sz, base, s.pi, s.row);
        IntVector bits = IntVector.fromArray(I, s.row, 0);
        bits.intoArray(s.bits, j0);

        IntVector cnt = bits.lanewise(VectorOperators.BIT_COUNT);
        for (int k = 1; k <= 8; k++) {
            VectorMask<Integer> mk = cnt.compare(VectorOperators.EQ, k).and(mi);
            jv.compress(mk).intoArray(s.hit, k * CAP + s.hitN[k]);
            s.hitN[k] += mk.trueCount();
        }
    }

    // Rows of pc: 0..11 the sums (+-c0 +-c1) for the four sign pairs and three axes, 12..14 c2, 15..17 the position
    private static void corners(int[] nbr, Scratch s, int j0, int sx, int sz, int base) {
        final float[] dn = s.dn, pc = s.pc;
        FloatVector qx = FloatVector.fromArray(F, dn, DQX * CAP + j0), qy = FloatVector.fromArray(F, dn, DQY * CAP + j0);
        FloatVector qz = FloatVector.fromArray(F, dn, DQZ * CAP + j0), qw = FloatVector.fromArray(F, dn, DQW * CAP + j0);
        FloatVector xx = qx.mul(qx), yy = qy.mul(qy), zz = qz.mul(qz), xy = qx.mul(qy), xz = qx.mul(qz), yz = qy.mul(qz);
        FloatVector wxq = qw.mul(qx), wyq = qw.mul(qy), wzq = qw.mul(qz);
        FloatVector c0x = yy.add(zz).mul(-2f * CORNER).add(CORNER);
        FloatVector c0y = xy.add(wzq).mul(2f * CORNER), c0z = xz.sub(wyq).mul(2f * CORNER);
        FloatVector c1x = xy.sub(wzq).mul(2f * CORNER), c1y = xx.add(zz).mul(-2f * CORNER).add(CORNER), c1z = yz.add(wxq).mul(2f * CORNER);
        // (+-c0 +-c1) +- c2, added in the scalar kernel's order, so a corner on a voxel face falls the same way
        c0x.add(c1x).neg().intoArray(pc, 0); c0y.add(c1y).neg().intoArray(pc, L); c0z.add(c1z).neg().intoArray(pc, 2 * L);
        c0x.sub(c1x).intoArray(pc, 3 * L); c0y.sub(c1y).intoArray(pc, 4 * L); c0z.sub(c1z).intoArray(pc, 5 * L);
        c1x.sub(c0x).intoArray(pc, 6 * L); c1y.sub(c0y).intoArray(pc, 7 * L); c1z.sub(c0z).intoArray(pc, 8 * L);
        c0x.add(c1x).intoArray(pc, 9 * L); c0y.add(c1y).intoArray(pc, 10 * L); c0z.add(c1z).intoArray(pc, 11 * L);
        xz.add(wyq).mul(2f * CORNER).intoArray(pc, 12 * L);
        yz.sub(wxq).mul(2f * CORNER).intoArray(pc, 13 * L);
        xx.add(yy).mul(-2f * CORNER).add(CORNER).intoArray(pc, 14 * L);
        FloatVector px = FloatVector.fromArray(F, dn, DPX * CAP + j0), py = FloatVector.fromArray(F, dn, DPY * CAP + j0), pz = FloatVector.fromArray(F, dn, DPZ * CAP + j0);
        px.intoArray(pc, 15 * L); py.intoArray(pc, 16 * L); pz.intoArray(pc, 17 * L);

        // The centre voxel: one gather gives which of the 27 voxels around it can be touched
        IntVector ix = floor(px), iy = floor(py), iz = floor(pz);
        iy.mul(sz).add(iz).mul(sx).add(ix).sub(base).intoArray(s.idx, 0);
        IntVector.fromArray(I, nbr, 0, s.idx, 0).intoArray(s.pi, L);
        // bit of the voxel a corner lies in = 13 + (floor(corner) - floor(centre)) summed with weights 1, 3, 9
        IntVector.broadcast(I, 13).sub(ix.add(iy.mul(3)).add(iz.mul(9))).intoArray(s.pi, 0);
    }

    private static void probeCorner(float[] pc, int c, int sx, int sz, int base, int[] pi, int[] bitsRow) {
        final FloatVector sc = FloatVector.broadcast(F, (c & 4) == 0 ? -1f : 1f);
        final int ab = (c & 3) * 3 * L;
        FloatVector cx = FloatVector.fromArray(F, pc, 12 * L).fma(sc, FloatVector.fromArray(F, pc, ab)).add(FloatVector.fromArray(F, pc, 15 * L));
        FloatVector cy = FloatVector.fromArray(F, pc, 13 * L).fma(sc, FloatVector.fromArray(F, pc, ab + L)).add(FloatVector.fromArray(F, pc, 16 * L));
        FloatVector cz = FloatVector.fromArray(F, pc, 14 * L).fma(sc, FloatVector.fromArray(F, pc, ab + 2 * L)).add(FloatVector.fromArray(F, pc, 17 * L));
        IntVector ix = trunc(cx), iy = trunc(cy), iz = trunc(cz);
        ix = ix.sub(1, cx.compare(VectorOperators.LT, toFloat(ix)).cast(I));
        iy = iy.sub(1, cy.compare(VectorOperators.LT, toFloat(iy)).cast(I));
        iz = iz.sub(1, cz.compare(VectorOperators.LT, toFloat(iz)).cast(I));
        IntVector bit = ix.add(iy.mul(3)).add(iz.mul(9)).add(IntVector.fromArray(I, pi, 0));
        VectorMask<Integer> solid = IntVector.fromArray(I, pi, L).lanewise(VectorOperators.LSHR, bit).and(1).compare(VectorOperators.NE, 0);
        IntVector.fromArray(I, bitsRow, 0).lanewise(VectorOperators.OR, 1 << c, solid).intoArray(bitsRow, 0);
    }

    /** Debug: ns in cull, probe, load, contacts, iterate, finish; candidates; pieces solved. */
    public static final boolean PROFILE = Boolean.getBoolean("dp.profile");
    public static final long[] TIME = new long[9];
    private static final int TYPE_MASK = Grid.TYPE_MASK;

    private static void solve(Bodies b, Grid grid, Scratch s, int k, int h0, int n, float dt) {
        final Ctx x = s.x0, y = s.x1;
        final boolean two = h0 + L < n;
        long t0 = PROFILE ? System.nanoTime() : 0;
        load(b, s, x, k, h0, n);
        if (two)
            load(b, s, y, k, h0 + L, n);
        if (PROFILE) { long t = System.nanoTime(); TIME[2] += t - t0; t0 = t; }
        final float scale = BAUMGARTE / dt;
        for (int c = 0; c < k; c++) {
            contact(grid, x, c, scale);
            if (two)
                contact(grid, y, c, scale);
        }
        if (PROFILE) { long t = System.nanoTime(); TIME[3] += t - t0; t0 = t; }
        for (int it = 0; it < ITERATIONS; it++)
            for (int c = 0; c < k; c++) {
                iterateStep(x, c);
                if (two)
                    iterateStep(y, c);
            }
        if (PROFILE) { long t = System.nanoTime(); TIME[4] += t - t0; t0 = t; }
        for (int it = 0; it < ITERATIONS; it++)
            for (int c = 0; c < k; c++) {
                splitStep(x, c);
                if (two)
                    splitStep(y, c);
            }
        store(b, x);
        if (two)
            store(b, y);
        if (PROFILE) { long t = System.nanoTime(); TIME[6] += t - t0; TIME[7] += Math.min(2 * L, n - h0) * 1L; TIME[8] += k; }
    }

    /** Gathers the pieces of one group vector and the rotation columns into the state rows. */
    private static void load(Bodies b, Scratch sc, Ctx s, int k, int h0, int n) {
        final int[] pidx = s.pidx, pos = s.pos;
        final float[] st = s.st;
        final int base = k * CAP + h0;
        VectorMask<Integer> mi = I.indexInRange(h0, n);
        IntVector jv = IntVector.fromArray(I, sc.hit, base, mi).blend(IntVector.broadcast(I, sc.hit[base]), mi.not());
        jv.intoArray(pos, 0);
        IntVector.fromArray(I, sc.cand, 0, pos, 0).intoArray(pidx, 0);
        IntVector.fromArray(I, sc.bits, 0, pos, 0).intoArray(s.left, 0);
        IntVector.zero(I).intoArray(s.got, 0);

        FloatVector qx = FloatVector.fromArray(F, b.qx, 0, pidx, 0), qy = FloatVector.fromArray(F, b.qy, 0, pidx, 0);
        FloatVector qz = FloatVector.fromArray(F, b.qz, 0, pidx, 0), qw = FloatVector.fromArray(F, b.qw, 0, pidx, 0);
        FloatVector xx = qx.mul(qx), yy = qy.mul(qy), zz = qz.mul(qz), xy = qx.mul(qy), xz = qx.mul(qz), yz = qy.mul(qz);
        FloatVector wxq = qw.mul(qx), wyq = qw.mul(qy), wzq = qw.mul(qz);
        yy.add(zz).mul(-2f * CORNER).add(CORNER).intoArray(st, C0X * L);
        xy.add(wzq).mul(2f * CORNER).intoArray(st, C0Y * L);
        xz.sub(wyq).mul(2f * CORNER).intoArray(st, C0Z * L);
        xy.sub(wzq).mul(2f * CORNER).intoArray(st, C1X * L);
        xx.add(zz).mul(-2f * CORNER).add(CORNER).intoArray(st, C1Y * L);
        yz.add(wxq).mul(2f * CORNER).intoArray(st, C1Z * L);
        xz.add(wyq).mul(2f * CORNER).intoArray(st, C2X * L);
        yz.sub(wxq).mul(2f * CORNER).intoArray(st, C2Y * L);
        xx.add(yy).mul(-2f * CORNER).add(CORNER).intoArray(st, C2Z * L);

        FloatVector.fromArray(F, b.px, 0, pidx, 0).intoArray(st, PX * L);
        FloatVector.fromArray(F, b.py, 0, pidx, 0).intoArray(st, PY * L);
        FloatVector.fromArray(F, b.pz, 0, pidx, 0).intoArray(st, PZ * L);
        FloatVector.fromArray(F, b.vx, 0, pidx, 0).intoArray(st, VX * L);
        FloatVector.fromArray(F, b.vy, 0, pidx, 0).intoArray(st, VY * L);
        FloatVector.fromArray(F, b.vz, 0, pidx, 0).intoArray(st, VZ * L);
        FloatVector.fromArray(F, b.wx, 0, pidx, 0).intoArray(st, WX * L);
        FloatVector.fromArray(F, b.wy, 0, pidx, 0).intoArray(st, WY * L);
        FloatVector.fromArray(F, b.wz, 0, pidx, 0).intoArray(st, WZ * L);
        FloatVector.fromArray(F, b.invMass, 0, pidx, 0).intoArray(st, IM * L);
        FloatVector.fromArray(F, b.invInertia, 0, pidx, 0).intoArray(st, II * L);
        FloatVector.fromArray(F, b.mu, 0, pidx, 0).intoArray(st, MU * L);
        FloatVector.fromArray(F, b.restitution, 0, pidx, 0).intoArray(st, RES * L);
        FloatVector.fromArray(F, b.vpx, 0, pidx, 0).intoArray(st, VPX * L);
        FloatVector.fromArray(F, b.vpy, 0, pidx, 0).intoArray(st, VPY * L);
        FloatVector.fromArray(F, b.vpz, 0, pidx, 0).intoArray(st, VPZ * L);
        FloatVector.fromArray(F, b.wpx, 0, pidx, 0).intoArray(st, WPX * L);
        FloatVector.fromArray(F, b.wpy, 0, pidx, 0).intoArray(st, WPY * L);
        FloatVector.fromArray(F, b.wpz, 0, pidx, 0).intoArray(st, WPZ * L);
    }

    private static void contact(Grid grid, Ctx s, int c, float scale) {
        locate(grid, s, c);
        select(s);
        derive(s, c, scale);
    }

    // Rows of cs: 0..2 position inside the voxel, 3..5 corner velocity, 6 best distance
    private static final int FX = 0, FY = 1, FZ = 2, UX = 3, UY = 4, UZ = 5, BEST = 6;

    /** The next solid corner of every lane: its offset, the voxel it lies in, where in the voxel, and how fast it moves. */
    private static void locate(Grid grid, Ctx s, int c) {
        final int[] idx = s.idx;
        final float[] st = s.st, f = s.f, cs = s.cs;
        final int o = c * NF * L;

        IntVector left = IntVector.fromArray(I, s.left, 0);
        left.and(left.sub(1)).intoArray(s.left, 0);
        // log2 of the lowest set bit through the float exponent
        IntVector cid = toFloat(left.and(left.neg())).reinterpretAsInts().lanewise(VectorOperators.LSHR, 23).sub(127);
        final FloatVector two = FloatVector.broadcast(F, 2f), minusOne = FloatVector.broadcast(F, -1f);
        FloatVector sa = toFloat(cid.and(1)).fma(two, minusOne);
        FloatVector sb = toFloat(cid.lanewise(VectorOperators.LSHR, 1).and(1)).fma(two, minusOne);
        FloatVector sc = toFloat(cid.lanewise(VectorOperators.LSHR, 2).and(1)).fma(two, minusOne);

        FloatVector rx = FloatVector.fromArray(F, st, C0X * L).mul(sa).add(FloatVector.fromArray(F, st, C1X * L).mul(sb)).add(FloatVector.fromArray(F, st, C2X * L).mul(sc));
        FloatVector ry = FloatVector.fromArray(F, st, C0Y * L).mul(sa).add(FloatVector.fromArray(F, st, C1Y * L).mul(sb)).add(FloatVector.fromArray(F, st, C2Y * L).mul(sc));
        FloatVector rz = FloatVector.fromArray(F, st, C0Z * L).mul(sa).add(FloatVector.fromArray(F, st, C1Z * L).mul(sb)).add(FloatVector.fromArray(F, st, C2Z * L).mul(sc));
        rx.intoArray(f, o + RX * L); ry.intoArray(f, o + RY * L); rz.intoArray(f, o + RZ * L);
        FloatVector cpx = FloatVector.fromArray(F, st, PX * L).add(rx);
        FloatVector cpy = FloatVector.fromArray(F, st, PY * L).add(ry);
        FloatVector cpz = FloatVector.fromArray(F, st, PZ * L).add(rz);

        IntVector ix = trunc(cpx), iy = trunc(cpy), iz = trunc(cpz);
        ix = ix.sub(1, cpx.compare(VectorOperators.LT, toFloat(ix)).cast(I));
        iy = iy.sub(1, cpy.compare(VectorOperators.LT, toFloat(iy)).cast(I));
        iz = iz.sub(1, cpz.compare(VectorOperators.LT, toFloat(iz)).cast(I));
        iy.mul(grid.sz).add(iz).mul(grid.sx).add(ix).sub((grid.y0 * grid.sz + grid.z0) * grid.sx + grid.x0).intoArray(idx, 0);
        IntVector.fromArray(I, grid.wide, 0, idx, 0).intoArray(s.cell, 0);

        cpx.sub(toFloat(ix)).intoArray(cs, FX * L);
        cpy.sub(toFloat(iy)).intoArray(cs, FY * L);
        cpz.sub(toFloat(iz)).intoArray(cs, FZ * L);
        FloatVector vx = FloatVector.fromArray(F, st, VX * L), vy = FloatVector.fromArray(F, st, VY * L), vz = FloatVector.fromArray(F, st, VZ * L);
        FloatVector wx = FloatVector.fromArray(F, st, WX * L), wy = FloatVector.fromArray(F, st, WY * L), wz = FloatVector.fromArray(F, st, WZ * L);
        wy.fma(rz, wz.neg().fma(ry, vx)).intoArray(cs, UX * L);
        wz.fma(rx, wx.neg().fma(rz, vy)).intoArray(cs, UY * L);
        wx.fma(ry, wy.neg().fma(rx, vz)).intoArray(cs, UZ * L);
    }

    /**
     * The way out of the voxel: the nearest open face the corner could have entered through (it is not already leaving
     * through it), in the scalar kernel's order and with its preference for pushing up. Code bits: 1 = x axis,
     * 2 = y axis, 4 = z axis, 8 = negative direction; no way out leaves code 0.
     */
    private static void select(Ctx s) {
        final float[] cs = s.cs;
        IntVector cell = IntVector.fromArray(I, s.cell, 0);
        FloatVector fx = FloatVector.fromArray(F, cs, FX * L), fy = FloatVector.fromArray(F, cs, FY * L), fz = FloatVector.fromArray(F, cs, FZ * L);
        FloatVector velX = FloatVector.fromArray(F, cs, UX * L), velY = FloatVector.fromArray(F, cs, UY * L), velZ = FloatVector.fromArray(F, cs, UZ * L);

        FloatVector best = FloatVector.broadcast(F, Float.MAX_VALUE);
        IntVector code = IntVector.zero(I);
        FloatVector d;
        VectorMask<Float> ok;

        d = fy.neg().add(0.95f);
        ok = cell.and(Grid.OPEN_PY).compare(VectorOperators.NE, 0).cast(F).and(velY.compare(VectorOperators.LT, MAX_EXIT_SPEED)).and(d.compare(VectorOperators.LT, best));
        best = best.blend(d, ok); code = code.blend(2, ok.cast(I));

        d = fx.neg().add(1f);
        ok = cell.and(Grid.OPEN_PX).compare(VectorOperators.NE, 0).cast(F).and(velX.compare(VectorOperators.LT, MAX_EXIT_SPEED)).and(d.compare(VectorOperators.LT, best));
        best = best.blend(d, ok); code = code.blend(1, ok.cast(I));

        ok = cell.and(Grid.OPEN_NX).compare(VectorOperators.NE, 0).cast(F).and(velX.compare(VectorOperators.GT, -MAX_EXIT_SPEED)).and(fx.compare(VectorOperators.LT, best));
        best = best.blend(fx, ok); code = code.blend(9, ok.cast(I));

        d = fz.neg().add(1f);
        ok = cell.and(Grid.OPEN_PZ).compare(VectorOperators.NE, 0).cast(F).and(velZ.compare(VectorOperators.LT, MAX_EXIT_SPEED)).and(d.compare(VectorOperators.LT, best));
        best = best.blend(d, ok); code = code.blend(4, ok.cast(I));

        ok = cell.and(Grid.OPEN_NZ).compare(VectorOperators.NE, 0).cast(F).and(velZ.compare(VectorOperators.GT, -MAX_EXIT_SPEED)).and(fz.compare(VectorOperators.LT, best));
        best = best.blend(fz, ok); code = code.blend(12, ok.cast(I));

        ok = cell.and(Grid.OPEN_NY).compare(VectorOperators.NE, 0).cast(F).and(velY.compare(VectorOperators.GT, -MAX_EXIT_SPEED)).and(fy.compare(VectorOperators.LT, best));
        best = best.blend(fy, ok); code = code.blend(10, ok.cast(I));

        best.intoArray(cs, BEST * L);
        code.intoArray(s.code, 0);
        IntVector.fromArray(I, s.got, 0).or(code).intoArray(s.got, 0);
    }

    /** Contact frame, effective masses, bounce and split-impulse targets of the slot; a lane without a way out gets a dead slot. */
    private static void derive(Ctx s, int c, float scale) {
        final float[] st = s.st, f = s.f, cs = s.cs;
        final int o = c * NF * L;
        final FloatVector zero = FloatVector.zero(F), one = FloatVector.broadcast(F, 1f);
        IntVector code = IntVector.fromArray(I, s.code, 0);
        VectorMask<Float> dead = code.compare(VectorOperators.EQ, 0).cast(F);

        FloatVector ax = zero.blend(1f, code.and(1).compare(VectorOperators.NE, 0).cast(F));
        FloatVector ay = zero.blend(1f, code.and(2).compare(VectorOperators.NE, 0).cast(F));
        FloatVector az = zero.blend(1f, code.and(4).compare(VectorOperators.NE, 0).cast(F));
        FloatVector sign = one.blend(-1f, code.and(8).compare(VectorOperators.NE, 0).cast(F));
        FloatVector nx = ax.mul(sign), ny = ay.mul(sign), nz = az.mul(sign);

        FloatVector rx = FloatVector.fromArray(F, f, o + RX * L), ry = FloatVector.fromArray(F, f, o + RY * L), rz = FloatVector.fromArray(F, f, o + RZ * L);
        final FloatVector reach = FloatVector.broadcast(F, CORNER_REACH_SQ);
        FloatVector invMass = FloatVector.fromArray(F, st, IM * L), invInertia = FloatVector.fromArray(F, st, II * L);
        FloatVector ra = rx.fma(ax, ry.fma(ay, rz.mul(az)));
        FloatVector r1 = rx.fma(az, ry.fma(ax, rz.mul(ay)));
        FloatVector r2 = rx.fma(ay, ry.fma(az, rz.mul(ax)));
        FloatVector kn = one.div(invInertia.fma(ra.neg().fma(ra, reach), invMass)).blend(zero, dead);
        FloatVector k1 = one.div(invInertia.fma(r1.neg().fma(r1, reach), invMass)).blend(zero, dead);
        FloatVector k2 = one.div(invInertia.fma(r2.neg().fma(r2, reach), invMass)).blend(zero, dead);

        FloatVector vn = FloatVector.fromArray(F, cs, UX * L).fma(nx, FloatVector.fromArray(F, cs, UY * L).fma(ny, FloatVector.fromArray(F, cs, UZ * L).mul(nz)));
        FloatVector bounce = FloatVector.fromArray(F, st, RES * L).neg().mul(vn).blend(zero, vn.compare(VectorOperators.GE, -BOUNCE_THRESHOLD).or(dead));
        // The +y preference was baked into the distance, so it is added back to the depth
        FloatVector depth = FloatVector.fromArray(F, cs, BEST * L).add(0.05f, code.compare(VectorOperators.EQ, 2).cast(F));
        FloatVector target = depth.sub(SLOP).max(0f).min(MAX_CORRECTION).mul(scale).blend(zero, dead);

        // Coupling of the impulses through the lever arm: -invInertia * (r.e) * (r.f)
        FloatVector rn = rx.fma(nx, ry.fma(ny, rz.mul(nz)));
        FloatVector ni = invInertia.neg();
        ni.mul(rn).mul(r1).intoArray(f, o + CN1 * L);
        ni.mul(rn).mul(r2).intoArray(f, o + CN2 * L);
        ni.mul(r1).mul(r2).intoArray(f, o + C12 * L);
        nx.intoArray(f, o + NX * L); ny.intoArray(f, o + NY * L); nz.intoArray(f, o + NZ * L);
        ax.intoArray(f, o + AX * L); ay.intoArray(f, o + AY * L); az.intoArray(f, o + AZ * L);
        kn.intoArray(f, o + KN * L); k1.intoArray(f, o + K1 * L); k2.intoArray(f, o + K2 * L);
        bounce.intoArray(f, o + BOUNCE * L);
        target.intoArray(f, o + TARGET * L);
        Arrays.fill(f, o + ACC_N * L, o + (ACC_P + 1) * L, 0f);
    }

    /**
     * One sequential-impulse step of one contact slot: the normal, then both friction directions. The velocity of the
     * contact point is computed once; an impulse along axis e changes its component along another axis f by
     * {@code -invInertia * j * (r.e) * (r.f)} (the invMass and r.r terms cancel for orthogonal axes), which the
     * precomputed couplings CN1, CN2 and C12 carry, so v and w are updated once at the end.
     */
    private static void iterateStep(Ctx s, int c) {
        final float[] f = s.f, st = s.st;
        final int o = c * NF * L;
        FloatVector vx = FloatVector.fromArray(F, st, VX * L), vy = FloatVector.fromArray(F, st, VY * L), vz = FloatVector.fromArray(F, st, VZ * L);
        FloatVector wx = FloatVector.fromArray(F, st, WX * L), wy = FloatVector.fromArray(F, st, WY * L), wz = FloatVector.fromArray(F, st, WZ * L);
        FloatVector rx = FloatVector.fromArray(F, f, o + RX * L), ry = FloatVector.fromArray(F, f, o + RY * L), rz = FloatVector.fromArray(F, f, o + RZ * L);
        FloatVector nx = FloatVector.fromArray(F, f, o + NX * L), ny = FloatVector.fromArray(F, f, o + NY * L), nz = FloatVector.fromArray(F, f, o + NZ * L);
        FloatVector ax = FloatVector.fromArray(F, f, o + AX * L), ay = FloatVector.fromArray(F, f, o + AY * L), az = FloatVector.fromArray(F, f, o + AZ * L);

        // Velocity of the contact point
        FloatVector ux = wy.fma(rz, wz.neg().fma(ry, vx));
        FloatVector uy = wz.fma(rx, wx.neg().fma(rz, vy));
        FloatVector uz = wx.fma(ry, wy.neg().fma(rx, vz));

        // Normal impulse first, so friction has something to be a fraction of
        FloatVector vd = ux.fma(nx, uy.fma(ny, uz.mul(nz)));
        FloatVector accN = FloatVector.fromArray(F, f, o + ACC_N * L);
        FloatVector nextN = FloatVector.fromArray(F, f, o + BOUNCE * L).sub(vd).fma(FloatVector.fromArray(F, f, o + KN * L), accN).max(0f);
        nextN.intoArray(f, o + ACC_N * L);
        FloatVector dn = nextN.sub(accN);
        FloatVector maxF = FloatVector.fromArray(F, st, MU * L).mul(nextN), minF = maxF.neg();

        // Tangent 1: (az, ax, ay)
        FloatVector vt = ux.fma(az, uy.fma(ax, uz.mul(ay))).add(dn.mul(FloatVector.fromArray(F, f, o + CN1 * L)));
        FloatVector acc = FloatVector.fromArray(F, f, o + ACC_1 * L);
        FloatVector next = vt.neg().fma(FloatVector.fromArray(F, f, o + K1 * L), acc).max(minF).min(maxF);
        next.intoArray(f, o + ACC_1 * L);
        FloatVector d1 = next.sub(acc);

        // Tangent 2: (ay, az, ax)
        vt = ux.fma(ay, uy.fma(az, uz.mul(ax))).add(dn.fma(FloatVector.fromArray(F, f, o + CN2 * L), d1.mul(FloatVector.fromArray(F, f, o + C12 * L))));
        acc = FloatVector.fromArray(F, f, o + ACC_2 * L);
        next = vt.neg().fma(FloatVector.fromArray(F, f, o + K2 * L), acc).max(minF).min(maxF);
        next.intoArray(f, o + ACC_2 * L);
        FloatVector d2 = next.sub(acc);

        // The total impulse, applied once
        FloatVector jx = nx.fma(dn, az.fma(d1, ay.mul(d2)));
        FloatVector jy = ny.fma(dn, ax.fma(d1, az.mul(d2)));
        FloatVector jz = nz.fma(dn, ay.fma(d1, ax.mul(d2)));
        FloatVector invMass = FloatVector.fromArray(F, st, IM * L), invInertia = FloatVector.fromArray(F, st, II * L);
        jx.fma(invMass, vx).intoArray(st, VX * L); jy.fma(invMass, vy).intoArray(st, VY * L); jz.fma(invMass, vz).intoArray(st, VZ * L);
        ry.fma(jz, rz.neg().mul(jy)).fma(invInertia, wx).intoArray(st, WX * L);
        rz.fma(jx, rx.neg().mul(jz)).fma(invInertia, wy).intoArray(st, WY * L);
        rx.fma(jy, ry.neg().mul(jx)).fma(invInertia, wz).intoArray(st, WZ * L);
    }

    private static void splitStep(Ctx s, int c) {
        final float[] f = s.f, st = s.st;
        final FloatVector zero = FloatVector.zero(F);
        FloatVector invMass = FloatVector.fromArray(F, st, IM * L), invInertia = FloatVector.fromArray(F, st, II * L);
        final int o = c * NF * L;
        FloatVector vpx = FloatVector.fromArray(F, st, VPX * L), vpy = FloatVector.fromArray(F, st, VPY * L), vpz = FloatVector.fromArray(F, st, VPZ * L);
        FloatVector wpx = FloatVector.fromArray(F, st, WPX * L), wpy = FloatVector.fromArray(F, st, WPY * L), wpz = FloatVector.fromArray(F, st, WPZ * L);
        FloatVector target = FloatVector.fromArray(F, f, o + TARGET * L);
        FloatVector accP = FloatVector.fromArray(F, f, o + ACC_P * L);
        VectorMask<Float> active = target.compare(VectorOperators.GT, 0f).or(accP.compare(VectorOperators.NE, 0f));
        if (!active.anyTrue())
            return;
        FloatVector rx = FloatVector.fromArray(F, f, o + RX * L), ry = FloatVector.fromArray(F, f, o + RY * L), rz = FloatVector.fromArray(F, f, o + RZ * L);
        FloatVector nx = FloatVector.fromArray(F, f, o + NX * L), ny = FloatVector.fromArray(F, f, o + NY * L), nz = FloatVector.fromArray(F, f, o + NZ * L);

        FloatVector velX = wpy.fma(rz, wpz.neg().fma(ry, vpx));
        FloatVector velY = wpz.fma(rx, wpx.neg().fma(rz, vpy));
        FloatVector velZ = wpx.fma(ry, wpy.neg().fma(rx, vpz));
        FloatVector vn = velX.fma(nx, velY.fma(ny, velZ.mul(nz)));
        FloatVector next = target.sub(vn).fma(FloatVector.fromArray(F, f, o + KN * L), accP).max(0f);
        FloatVector dn = next.sub(accP).blend(zero, active.not());
        dn.add(accP).intoArray(f, o + ACC_P * L);
        FloatVector ix = nx.mul(dn), iy = ny.mul(dn), iz = nz.mul(dn);
        vpx = ix.fma(invMass, vpx); vpy = iy.fma(invMass, vpy); vpz = iz.fma(invMass, vpz);
        wpx = ry.fma(iz, rz.neg().mul(iy)).fma(invInertia, wpx);
        wpy = rz.fma(ix, rx.neg().mul(iz)).fma(invInertia, wpy);
        wpz = rx.fma(iy, ry.neg().mul(ix)).fma(invInertia, wpz);
        vpx.intoArray(st, VPX * L); vpy.intoArray(st, VPY * L); vpz.intoArray(st, VPZ * L);
        wpx.intoArray(st, WPX * L); wpy.intoArray(st, WPY * L); wpz.intoArray(st, WPZ * L);
    }

    private static void store(Bodies b, Ctx s) {
        final float[] st = s.st;
        final int[] pidx = s.pidx;
        FloatVector.fromArray(F, st, VX * L).intoArray(b.vx, 0, pidx, 0); FloatVector.fromArray(F, st, VY * L).intoArray(b.vy, 0, pidx, 0); FloatVector.fromArray(F, st, VZ * L).intoArray(b.vz, 0, pidx, 0);
        FloatVector.fromArray(F, st, WX * L).intoArray(b.wx, 0, pidx, 0); FloatVector.fromArray(F, st, WY * L).intoArray(b.wy, 0, pidx, 0); FloatVector.fromArray(F, st, WZ * L).intoArray(b.wz, 0, pidx, 0);
        FloatVector.fromArray(F, st, VPX * L).intoArray(b.vpx, 0, pidx, 0); FloatVector.fromArray(F, st, VPY * L).intoArray(b.vpy, 0, pidx, 0); FloatVector.fromArray(F, st, VPZ * L).intoArray(b.vpz, 0, pidx, 0);
        FloatVector.fromArray(F, st, WPX * L).intoArray(b.wpx, 0, pidx, 0); FloatVector.fromArray(F, st, WPY * L).intoArray(b.wpy, 0, pidx, 0); FloatVector.fromArray(F, st, WPZ * L).intoArray(b.wpz, 0, pidx, 0);
        // Only a piece that got a real contact counts as touching
        FloatVector.broadcast(F, 1f).intoArray(b.touch, 0, pidx, 0, IntVector.fromArray(I, s.got, 0).compare(VectorOperators.NE, 0).cast(F));
    }

    // ── Forces ───────────────────────────────────────────────────────────────

    private static FloatVector ld(float[] a, int i, VectorMask<Float> m) {
        return m == null ? FloatVector.fromArray(F, a, i) : FloatVector.fromArray(F, a, i, m);
    }

    private static void st(FloatVector v, float[] a, int i, VectorMask<Float> m) {
        if (m == null)
            v.intoArray(a, i);
        else
            v.intoArray(a, i, m);
    }

    @Override
    public void forces(Bodies b, int from, int to, Grid grid, float dt, float gravity) {
        int[] idx = SCRATCH.get().idx;
        int bound = from + F.loopBound(to - from);
        for (int i = from; i < bound; i += L)
            forcesVector(b, grid, idx, i, null, dt, gravity);
        if (bound < to)
            forcesVector(b, grid, idx, bound, F.indexInRange(bound, to), dt, gravity);
    }

    private static void forcesVector(Bodies b, Grid grid, int[] idx, int i, VectorMask<Float> m, float dt, float gravity) {
        FloatVector vx = ld(b.vx, i, m), vy = ld(b.vy, i, m), vz = ld(b.vz, i, m);
        FloatVector wx = ld(b.wx, i, m), wy = ld(b.wy, i, m), wz = ld(b.wz, i, m);
        FloatVector linear = ld(b.drag, i, m).fma(FloatVector.broadcast(F, -dt), FloatVector.broadcast(F, 1f));
        vy = vy.sub(gravity * dt);

        if (grid.liquid) {
            FloatVector touch = ld(b.touch, i, m);
            FloatVector px = ld(b.px, i, m), py = ld(b.py, i, m), pz = ld(b.pz, i, m);
            IntVector ix = trunc(px.sub((float) grid.x0)), iy = trunc(py.sub((float) grid.y0)), iz = trunc(pz.sub((float) grid.z0));
            iy.mul(grid.sz).add(iz).mul(grid.sx).add(ix).intoArray(idx, 0);
            VectorMask<Integer> mi = m == null ? I.maskAll(true) : m.cast(I);
            IntVector cell = IntVector.fromArray(I, grid.wide, 0, idx, 0, mi);
            VectorMask<Float> liquid = cell.and(TYPE_MASK).compare(VectorOperators.EQ, (int) Grid.LIQUID).cast(F);
            vy = vy.add(gravity * BUOYANCY * dt, liquid);
            FloatVector k = FloatVector.broadcast(F, 1f).blend(1f - LIQUID_DRAG * dt, liquid);
            vx = vx.mul(k); vy = vy.mul(k); vz = vz.mul(k);
            wx = wx.mul(k); wy = wy.mul(k); wz = wz.mul(k);
            touch = touch.blend(1f, liquid);
            st(touch, b.touch, i, m);
            FloatVector angular = FloatVector.broadcast(F, 1f - 0.2f * dt).blend(1f - ROLLING_RESISTANCE * dt, touch.compare(VectorOperators.GT, 0f));
            st(vx.mul(linear), b.vx, i, m); st(vy.mul(linear), b.vy, i, m); st(vz.mul(linear), b.vz, i, m);
            st(wx.mul(angular), b.wx, i, m); st(wy.mul(angular), b.wy, i, m); st(wz.mul(angular), b.wz, i, m);
            return;
        }

        FloatVector angular = FloatVector.broadcast(F, 1f - 0.2f * dt).blend(1f - ROLLING_RESISTANCE * dt, ld(b.touch, i, m).compare(VectorOperators.GT, 0f));
        st(vx.mul(linear), b.vx, i, m); st(vy.mul(linear), b.vy, i, m); st(vz.mul(linear), b.vz, i, m);
        st(wx.mul(angular), b.wx, i, m); st(wy.mul(angular), b.wy, i, m); st(wz.mul(angular), b.wz, i, m);
    }

    // ── Integration ──────────────────────────────────────────────────────────

    @Override
    public void integrate(Bodies b, int from, int to, float dt) {
        int bound = from + F.loopBound(to - from);
        for (int i = from; i < bound; i += L)
            integrateVector(b, i, null, dt);
        if (bound < to)
            integrateVector(b, bound, F.indexInRange(bound, to), dt);
    }

    /** Same bit trick and Newton steps as {@code ScalarKernels.inverseSquareRoot}. */
    private static FloatVector isqrt(FloatVector x) {
        FloatVector half = x.mul(0.5f), nhalf = half.neg();
        FloatVector y = IntVector.broadcast(I, 0x5f3759df).sub(x.reinterpretAsInts().lanewise(VectorOperators.ASHR, 1)).reinterpretAsFloats();
        y = y.mul(nhalf.mul(y).fma(y, FloatVector.broadcast(F, 1.5f)));
        y = y.mul(nhalf.mul(y).fma(y, FloatVector.broadcast(F, 1.5f)));
        return y;
    }

    private static void integrateVector(Bodies b, int i, VectorMask<Float> m, float dt) {
        final FloatVector one = FloatVector.broadcast(F, 1f);
        FloatVector vx = ld(b.vx, i, m), vy = ld(b.vy, i, m), vz = ld(b.vz, i, m);
        FloatVector wx = ld(b.wx, i, m), wy = ld(b.wy, i, m), wz = ld(b.wz, i, m);

        FloatVector speedSquared = vx.fma(vx, vy.fma(vy, vz.mul(vz)));
        FloatVector spinSquared = wx.fma(wx, wy.fma(wy, wz.mul(wz)));
        VectorMask<Float> fast = speedSquared.compare(VectorOperators.GT, MAX_SPEED * MAX_SPEED);
        VectorMask<Float> spinning = spinSquared.compare(VectorOperators.GT, MAX_SPIN * MAX_SPIN);
        if (fast.or(spinning).anyTrue()) {
            integrateClamped(b, i, m, dt);
            return;
        }

        final float h = 0.5f * dt;
        final FloatVector dtv = FloatVector.broadcast(F, dt), hh = FloatVector.broadcast(F, h);
        position(b, i, m, vx, vy, vz, dtv);
        rotate(b, i, m, wx.add(ld(b.wpx, i, m)), wy.add(ld(b.wpy, i, m)), wz.add(ld(b.wpz, i, m)), hh);
        clearPseudo(b, i, m);
    }

    private static void position(Bodies b, int i, VectorMask<Float> m, FloatVector vx, FloatVector vy, FloatVector vz, FloatVector dtv) {
        st(vx.add(ld(b.vpx, i, m)).fma(dtv, ld(b.px, i, m)), b.px, i, m);
        st(vy.add(ld(b.vpy, i, m)).fma(dtv, ld(b.py, i, m)), b.py, i, m);
        st(vz.add(ld(b.vpz, i, m)).fma(dtv, ld(b.pz, i, m)), b.pz, i, m);
    }

    private static void rotate(Bodies b, int i, VectorMask<Float> m, FloatVector ax, FloatVector ay, FloatVector az, FloatVector hh) {
        FloatVector nax = ax.neg(), nay = ay.neg(), naz = az.neg();
        FloatVector qx = ld(b.qx, i, m), qy = ld(b.qy, i, m), qz = ld(b.qz, i, m), qw = ld(b.qw, i, m);
        FloatVector nqx = ax.fma(qw, ay.fma(qz, naz.mul(qy))).fma(hh, qx);
        FloatVector nqy = nax.fma(qz, ay.fma(qw, az.mul(qx))).fma(hh, qy);
        FloatVector nqz = ax.fma(qy, nay.fma(qx, az.mul(qw))).fma(hh, qz);
        FloatVector nqw = nax.fma(qx, nay.fma(qy, naz.mul(qz))).fma(hh, qw);
        // The quaternion is within a few percent of unit length, where 1/sqrt(n) is 1.5 - n/2 to better than 1e-3
        FloatVector inv = FloatVector.broadcast(F, -0.5f).fma(nqx.fma(nqx, nqy.fma(nqy, nqz.fma(nqz, nqw.mul(nqw)))), FloatVector.broadcast(F, 1.5f));
        st(nqx.mul(inv), b.qx, i, m); st(nqy.mul(inv), b.qy, i, m); st(nqz.mul(inv), b.qz, i, m); st(nqw.mul(inv), b.qw, i, m);
    }

    private static void clearPseudo(Bodies b, int i, VectorMask<Float> m) {
        FloatVector zero = FloatVector.zero(F);
        st(zero, b.vpx, i, m); st(zero, b.vpy, i, m); st(zero, b.vpz, i, m);
        st(zero, b.wpx, i, m); st(zero, b.wpy, i, m); st(zero, b.wpz, i, m);
    }

    /** The rare case: some piece moves or spins faster than the limits, so velocities are clamped (and stored) first. */
    private static void integrateClamped(Bodies b, int i, VectorMask<Float> m, float dt) {
        FloatVector vx = ld(b.vx, i, m), vy = ld(b.vy, i, m), vz = ld(b.vz, i, m);
        FloatVector wx = ld(b.wx, i, m), wy = ld(b.wy, i, m), wz = ld(b.wz, i, m);
        FloatVector speedSquared = vx.fma(vx, vy.fma(vy, vz.mul(vz)));
        VectorMask<Float> fast = speedSquared.compare(VectorOperators.GT, MAX_SPEED * MAX_SPEED);
        FloatVector kv = isqrt(speedSquared).mul(MAX_SPEED);
        vx = vx.mul(kv, fast); vy = vy.mul(kv, fast); vz = vz.mul(kv, fast);
        FloatVector spinSquared = wx.fma(wx, wy.fma(wy, wz.mul(wz)));
        VectorMask<Float> spinning = spinSquared.compare(VectorOperators.GT, MAX_SPIN * MAX_SPIN);
        FloatVector kw = isqrt(spinSquared).mul(MAX_SPIN);
        wx = wx.mul(kw, spinning); wy = wy.mul(kw, spinning); wz = wz.mul(kw, spinning);
        st(vx, b.vx, i, m); st(vy, b.vy, i, m); st(vz, b.vz, i, m);
        st(wx, b.wx, i, m); st(wy, b.wy, i, m); st(wz, b.wz, i, m);
        position(b, i, m, vx, vy, vz, FloatVector.broadcast(F, dt));
        rotate(b, i, m, wx.add(ld(b.wpx, i, m)), wy.add(ld(b.wpy, i, m)), wz.add(ld(b.wpz, i, m)), FloatVector.broadcast(F, 0.5f * dt));
        clearPseudo(b, i, m);
    }

    // ── Calm ─────────────────────────────────────────────────────────────────

    @Override
    public void calm(Bodies b, int from, int to) {
        Kernels.scalar().calm(b, from, to);
    }

}
