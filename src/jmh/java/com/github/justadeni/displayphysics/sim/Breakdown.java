package com.github.justadeni.displayphysics.sim;

import java.util.SplittableRandom;

/** Phase timing of the hybrid terrain kernel (plain main, run pinned). */
public final class Breakdown {
    public static void main(String[] args) throws Exception {
        for (String a : args) {
            int count = Integer.parseInt(a);
            var b = new Bodies(count);
            var random = new SplittableRandom(3);
            double side = Math.sqrt(count / 0.35);
            for (int i = 0; i < count; i++) {
                b.px[i] = (float) random.nextDouble(0, side); b.py[i] = (float) random.nextDouble(0.2, 2.0); b.pz[i] = (float) random.nextDouble(0, side);
                b.vx[i] = (float) random.nextDouble(-4, 4); b.vy[i] = (float) random.nextDouble(-4, 4); b.vz[i] = (float) random.nextDouble(-4, 4);
                b.wx[i] = (float) random.nextDouble(-3, 3); b.wy[i] = (float) random.nextDouble(-3, 3); b.wz[i] = (float) random.nextDouble(-3, 3);
                float qx = (float) random.nextDouble(-1, 1), qy = (float) random.nextDouble(-1, 1), qz = (float) random.nextDouble(-1, 1), qw = (float) random.nextDouble(-1, 1);
                float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
                FlatDebris.initCube(b, i, 1f, i % 5 != 0, 0.6f, 0.25f, 0f);
                b.qx[i] = qx / length; b.qy[i] = qy / length; b.qz[i] = qz / length; b.qw[i] = qw / length;
            }
            var grid = new Grid(Scenario.FLAT, Scenario.ORIGIN_X, Scenario.ORIGIN_Y, Scenario.ORIGIN_Z);
            int s = (int) side + 8;
            grid.ensure(-6, -6, -6, s, 12, s);
            for (var name : new String[]{"scalar", "vector"}) {
                var k = name.equals("vector") ? Kernels.vector() : Kernels.scalar();
                int reps = Math.max(5, 4_000_000 / count);
                for (int round = 0; round < 5; round++) {
                    for (int r = 0; r < reps; r++) k.terrain(b, 0, count, grid, 0.0125f);
                    java.util.Arrays.fill(com.github.justadeni.displayphysics.sim.Hook.times(), 0);
                    long t = System.nanoTime();
                    for (int r = 0; r < reps; r++) k.terrain(b, 0, count, grid, 0.0125f);
                    long dt = System.nanoTime() - t;
                    double per = dt / (double) reps / count;
                    if (round == 4) {
                        long[] T = Hook.times();
                        double n = (double) reps * count;
                        System.out.printf("%-6s %7d: %6.1f ns/piece", name, count, per);
                        if (name.equals("vector"))
                            System.out.printf("   prefilter %5.1f (%.0f%% pass) probe+in %5.1f  solve %5.1f  out %5.1f   (contacts in %.0f%% of pieces)", T[5] / n, 100.0 * T[6] / T[4], T[0] / n, T[1] / n, T[2] / n, 100.0 * T[3] / T[4]);
                        System.out.println();
                    }
                }
            }
        }
    }
}
