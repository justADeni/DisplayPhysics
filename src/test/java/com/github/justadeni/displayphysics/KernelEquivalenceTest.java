package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.kernels.Kernels;
import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.FlatDebris;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class KernelEquivalenceTest {
    private static final TerrainSource TERRAIN = new TerrainSource() {
        public byte classify(int x, int y, int z) {
            if (y < 64)
                return x > 6 && x < 12 && z > -4 && z < 4 && y >= 62 ? LIQUID : SOLID;
            return x == 5 && y < 67 && z > -3 && z < 3 ? SOLID : AIR;
        }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    private static FlatDebris scene(int count, Kernels kernels) {
        var random = new SplittableRandom(11);
        var bodies = new Bodies(count);
        for (int i = 0; i < count; i++) {
            bodies.px[i] = (float) random.nextDouble(-6, 14);
            bodies.py[i] = (float) random.nextDouble(0.6, 6);
            bodies.pz[i] = (float) random.nextDouble(-6, 6);
            bodies.vx[i] = (float) random.nextDouble(-8, 8);
            bodies.vy[i] = (float) random.nextDouble(-6, 6);
            bodies.vz[i] = (float) random.nextDouble(-8, 8);
            bodies.wx[i] = (float) random.nextDouble(-5, 5);
            bodies.wy[i] = (float) random.nextDouble(-5, 5);
            bodies.wz[i] = (float) random.nextDouble(-5, 5);
            float qx = (float) random.nextDouble(-1, 1), qy = (float) random.nextDouble(-1, 1), qz = (float) random.nextDouble(-1, 1), qw = (float) random.nextDouble(-1, 1);
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            bodies.qx[i] = qx / length; bodies.qy[i] = qy / length; bodies.qz[i] = qz / length; bodies.qw[i] = qw / length;
            FlatDebris.initCube(bodies, i, 1f, i % 4 != 0, 0.6f, 0.25f, 0f);
            bodies.qw[i] = qw / length;
        }
        return new FlatDebris(bodies, TERRAIN, 0, 64, 0, 9.81f, 0.6f, 0f, kernels);
    }

    @Test
    void vectorKernelsMatchScalarOnAnOddNumberOfPieces() {
        var vector = Kernels.vector();
        Assumptions.assumeTrue(vector != null, "the JVM was started without jdk.incubator.vector");

        var scalar = scene(1003, Kernels.scalar());
        var simd = scene(1003, vector);
        scalar.step();
        simd.step();

        double worst = 0;
        for (int i = 0; i < 1003; i++) {
            worst = Math.max(worst, Math.abs(scalar.x(i) - simd.x(i)));
            worst = Math.max(worst, Math.abs(scalar.y(i) - simd.y(i)));
            worst = Math.max(worst, Math.abs(scalar.z(i) - simd.z(i)));
            worst = Math.max(worst, Math.abs(scalar.qw(i) - simd.qw(i)));
            worst = Math.max(worst, Math.abs(scalar.speed(i) - simd.speed(i)) * 0.01);
        }
        assertTrue(worst < 1e-3, "after one tick the engines differ by " + worst + " (" + vector.name() + ")");
    }

    @Test
    void bothKernelSetsSettleTheSameScene() {
        var vector = Kernels.vector();
        Assumptions.assumeTrue(vector != null, "the JVM was started without jdk.incubator.vector");

        for (Kernels kernels : new Kernels[]{Kernels.scalar(), vector}) {
            var debris = scene(400, kernels);
            int ticks = 0;
            while (!debris.step() && ++ticks < 1500) { }
            assertTrue(ticks < 1500, kernels.name() + " never settled");
            for (int i = 0; i < 400; i++) {
                assertTrue(Float.isFinite(debris.y(i)) && debris.y(i) > -1.95f, kernels.name() + ": piece " + i + " at " + debris.y(i));
            }
        }
    }
}
