package com.github.justadeni.displayphysics.sim;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Line-of-sight for a viewer, a vector of pieces at a time. The cheap tests (distance, view cone) run for
 * {@code L} pieces at once and shrink the batch to the pieces that need a line. Those are marched together,
 * one grid sample per step for every lane, with the grid read through a small software gather (the bytes are
 * not gatherable). A lane drops out when its line is blocked or finished; the batch goes on until all have.
 *
 * <p>The sets of lanes are kept as bit sets ({@code long}), which is what {@link VectorMask#toLong()} and
 * {@link VectorMask#fromLong} convert to and from for free.
 */
final class VectorVisibility {

    private static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED;
    private static final int L = F.length();

    private static final class Scratch {
        final int[] index = new int[L], solid = new int[L], occupied = new int[L];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private VectorVisibility() {}

    static void run(Bodies b, int from, int to, Grid grid, byte[] occupancy, Sight sight, boolean[] out) {
        var scratch = SCRATCH.get();
        float ex = sight.ex(), ey = sight.ey(), ez = sight.ez();
        float maxSquared = sight.maxDistance() * sight.maxDistance();
        float nearSquared = Sight.NEAR * Sight.NEAR;

        for (int i = from; i < to; i += L) {
            var inRange = F.indexInRange(i, to);
            var cx = FloatVector.fromArray(F, b.px, i, inRange);
            var cy = FloatVector.fromArray(F, b.py, i, inRange);
            var cz = FloatVector.fromArray(F, b.pz, i, inRange);
            var dx = cx.sub(ex);
            var dy = cy.sub(ey);
            var dz = cz.sub(ez);
            var distanceSquared = dz.fma(dz, dy.fma(dy, dx.mul(dx)));

            var far = distanceSquared.compare(VectorOperators.GT, maxSquared, inRange);
            var near = distanceSquared.compare(VectorOperators.LT, nearSquared, inRange).and(far.not());
            var undecided = inRange.and(far.not()).and(near.not());

            if (sight.cone() && undecided.anyTrue()) {
                var distance = distanceSquared.sqrt();
                var inverse = FloatVector.broadcast(F, 1f).div(distance);
                var dot = dz.mul(sight.lz()).add(dy.mul(sight.ly()).add(dx.mul(sight.lx()))).mul(inverse);
                var spans = inverse.mul(Sight.PIECE_RADIUS).min(1f);
                var coversCos = spans.mul(spans).neg().add(1f).max(0f).sqrt();
                var threshold = coversCos.mul(sight.cosHalf()).sub(spans.mul(sight.sinHalf()));
                undecided = undecided.and(dot.compare(VectorOperators.GE, threshold));
            }

            long pending = undecided.toLong(), seen = 0;
            for (int point = 0; point < 5 && pending != 0; point++) {
                long reached = march(cx, cy, cz, point, pending, ex, ey, ez, grid, occupancy, sight.spacing(), scratch);
                seen |= reached;
                pending &= ~reached;
            }

            long visible = near.toLong() | seen;
            int count = Math.min(L, to - i);
            for (int l = 0; l < count; l++)
                out[i + l] = (visible >>> l & 1L) != 0;
        }
    }

    /** Marches the line to one sample point of every lane in {@code lanes}; returns the lanes whose line is clear. */
    private static long march(FloatVector cx, FloatVector cy, FloatVector cz, int point, long lanes,
                              float ex, float ey, float ez, Grid grid, byte[] occupancy, float spacing, Scratch scratch) {
        var mask = VectorMask.fromLong(F, lanes);
        var rx = cx.add(ScalarKernels.SIGHT_POINTS[3 * point]).sub(ex);
        var ry = cy.add(ScalarKernels.SIGHT_POINTS[3 * point + 1]).sub(ey);
        var rz = cz.add(ScalarKernels.SIGHT_POINTS[3 * point + 2]).sub(ez);
        var length = rz.fma(rz, ry.fma(ry, rx.mul(rx))).sqrt();

        // steps = max(1, ceil(length / spacing))
        var quotient = length.div(spacing);
        var truncated = (IntVector) quotient.convert(VectorOperators.F2I, 0);
        var below = ((FloatVector) truncated.convert(VectorOperators.I2F, 0)).compare(VectorOperators.LT, quotient);
        var steps = truncated.add(1, below.cast(I)).max(1);
        var stepsFloat = (FloatVector) steps.convert(VectorOperators.I2F, 0);
        var inverse = FloatVector.broadcast(F, 1f).div(stepsFloat);

        int maxSteps = steps.reduceLanes(VectorOperators.MAX, mask.cast(I));
        long alive = lanes;
        byte[] cells = grid.cells;
        int[] index = scratch.index, solid = scratch.solid, occupied = scratch.occupied;
        float gx = grid.x0, gy = grid.y0, gz = grid.z0, sx = grid.sx, sy = grid.sy, sz = grid.sz;

        for (int s = 0; s < maxSteps && alive != 0; s++) {
            var f = inverse.mul(s + 0.5f);
            var fx = rx.mul(f).add(ex).sub(gx);
            var fy = ry.mul(f).add(ey).sub(gy);
            var fz = rz.mul(f).add(ez).sub(gz);
            var stepping = VectorMask.fromLong(F, alive).and(steps.compare(VectorOperators.GT, s).cast(F));
            // Outside the tracked area nothing is known, so nothing blocks
            var inside = stepping
                    .and(fx.compare(VectorOperators.GE, 0f)).and(fx.compare(VectorOperators.LT, sx))
                    .and(fy.compare(VectorOperators.GE, 0f)).and(fy.compare(VectorOperators.LT, sy))
                    .and(fz.compare(VectorOperators.GE, 0f)).and(fz.compare(VectorOperators.LT, sz));
            long sample = inside.toLong();
            if (sample != 0) {
                var xi = (IntVector) fx.convert(VectorOperators.F2I, 0);
                var yi = (IntVector) fy.convert(VectorOperators.F2I, 0);
                var zi = (IntVector) fz.convert(VectorOperators.F2I, 0);
                yi.mul(grid.sz).add(zi).mul(grid.sx).add(xi).intoArray(index, 0);
                // Software gather: the grid is bytes
                for (long bits = sample; bits != 0; bits &= bits - 1) {
                    int l = Long.numberOfTrailingZeros(bits);
                    int at = index[l];
                    solid[l] = (cells[at] & Grid.TYPE_MASK) == Grid.SOLID ? 1 : 0;
                    occupied[l] = occupancy != null && occupancy[at] != 0 ? 1 : 0;
                }
                var sampleMask = VectorMask.fromLong(F, sample);
                var blockedBySolid = IntVector.fromArray(I, solid, 0).compare(VectorOperators.NE, 0).cast(F);
                long blocked = sampleMask.and(blockedBySolid).toLong();
                if (occupancy != null) {
                    var farFromEnd = f.neg().add(1f).mul(length).compare(VectorOperators.GT, ScalarKernels.SELF_CLEARANCE);
                    var blockedByPiece = IntVector.fromArray(I, occupied, 0).compare(VectorOperators.NE, 0).cast(F).and(farFromEnd);
                    blocked |= sampleMask.and(blockedByPiece).toLong();
                }
                alive &= ~blocked;
            }
        }
        return alive;
    }

}
