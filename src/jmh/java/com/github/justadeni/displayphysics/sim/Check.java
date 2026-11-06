package com.github.justadeni.displayphysics.sim;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;

/** Runs scalar and a SIMD kernel set on the benchmark scenes and prints the largest difference per kernel. */
public class Check {

    public static void main(String[] args) {
        String variant = args.length > 0 ? args[0] : "vector";
        for (String scene : new String[]{"mixed", "flying", "resting"})
            for (int count : new int[]{1003, 20011}) {
                double[] worst = new double[3];
                for (int kernel = 0; kernel < 3; kernel++) {
                    var a = make(scene, count, "scalar");
                    var b = make(scene, count, variant);
                    for (int round = 0; round < 3; round++) {
                        run(kernel, a);
                        run(kernel, b);
                        worst[kernel] = Math.max(worst[kernel], diff(a.bodies, b.bodies));
                    }
                }
                System.out.printf("%-8s %6d  forces %.2e  terrain %.2e  integrate %.2e%n", scene, count, worst[0], worst[1], worst[2]);
            }
    }

    record Setup(Bodies bodies, Grid grid, Kernels kernels) {}

    static Setup make(String scene, int count, String kernels) {
        var kb = new KernelBenchmark();
        kb.count = count;
        kb.scene = scene;
        kb.kernels = kernels;
        kb.setUp();
        return new Setup(kb.bodies(), kb.grid(), kb.kernels());
    }

    static void run(int kernel, Setup s) {
        switch (kernel) {
            case 0 -> s.kernels.forces(s.bodies, 0, s.bodies.capacity, s.grid, 0.0125f, 9.81f);
            case 1 -> s.kernels.terrain(s.bodies, 0, s.bodies.capacity, s.grid, 0.0125f);
            default -> s.kernels.integrate(s.bodies, 0, s.bodies.capacity, 0.0125f);
        }
    }

    static double diff(Bodies a, Bodies b) {
        double worst = 0;
        float[][] x = {a.px, a.py, a.pz, a.vx, a.vy, a.vz, a.wx, a.wy, a.wz, a.qx, a.qy, a.qz, a.qw, a.vpx, a.vpy, a.vpz, a.wpx, a.wpy, a.wpz, a.touch};
        float[][] y = {b.px, b.py, b.pz, b.vx, b.vy, b.vz, b.wx, b.wy, b.wz, b.qx, b.qy, b.qz, b.qw, b.vpx, b.vpy, b.vpz, b.wpx, b.wpy, b.wpz, b.touch};
        for (int r = 0; r < x.length; r++)
            for (int i = 0; i < x[r].length; i++) {
                double d = Math.abs(x[r][i] - y[r][i]);
                if (Double.isNaN(d))
                    return Double.NaN;
                worst = Math.max(worst, d / (1 + Math.abs(x[r][i])));
            }
        return worst;
    }
}
