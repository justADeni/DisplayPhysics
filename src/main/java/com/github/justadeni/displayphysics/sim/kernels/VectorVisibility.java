package com.github.justadeni.displayphysics.sim.kernels;

import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.Coarse;
import com.github.justadeni.displayphysics.sim.Grid;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

final class VectorVisibility {
    private static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED;
    private static final int L = F.length();

    private VectorVisibility() {}

    static void run(Bodies b, int from, int to, Grid grid, byte[] occupancy, Coarse coarse, Sight sight, boolean[] out) {
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
            for (long bits = pending; bits != 0; bits &= bits - 1) {
                int l = Long.numberOfTrailingZeros(bits);
                if (ScalarKernels.clearLine(b.px[i + l], b.py[i + l], b.pz[i + l], grid, occupancy, coarse, sight))
                    seen |= 1L << l;
            }

            long visible = near.toLong() | seen;
            int count = Math.min(L, to - i);
            for (int l = 0; l < count; l++)
                out[i + l] = (visible >>> l & 1L) != 0;
        }
    }
}
