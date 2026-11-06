package com.github.justadeni.displayphysics.sim.kernels;

import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.Coarse;
import com.github.justadeni.displayphysics.sim.Grid;

public interface Kernels {
    String name();

    void forces(Bodies b, int from, int to, Grid grid, float dt, float gravity);

    void terrain(Bodies b, int from, int to, Grid grid, float dt);

    void integrate(Bodies b, int from, int to, float dt);

    void calm(Bodies b, int from, int to);

    default void visibility(Bodies b, int from, int to, Grid grid, byte[] occupancy, Coarse coarse, Sight sight, boolean[] out) {
        ScalarKernels.lineOfSight(b, from, to, grid, occupancy, coarse, sight, out);
    }

    default int near(float[] xs, float[] ys, float[] zs, int from, int to, float ax, float ay, float az, float rangeSq, int[] out) {
        return ScalarKernels.nearScan(xs, ys, zs, from, to, ax, ay, az, rangeSq, out);
    }

    static Kernels scalar() {
        return ScalarKernels.INSTANCE;
    }

    static Kernels vector() {
        return VectorHolder.KERNELS;
    }

    static Kernels best() {
        Kernels vector = vector();
        return vector != null ? vector : scalar();
    }

    final class VectorHolder {
        private VectorHolder() {}

        static final Kernels KERNELS = load();

        private static Kernels load() {
            if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty())
                return null;
            try {
                return (Kernels) Class.forName("com.github.justadeni.displayphysics.sim.kernels.VectorKernels")
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }
    }
}
