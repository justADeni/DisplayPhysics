package com.github.justadeni.displayphysics.sim;

/**
 * The per-piece passes of a debris tick, each over a range of awake slots. They are independent per
 * piece, so a range can be handed to any thread, and every implementation computes the same thing: a
 * scalar reference, and (where the JVM has the incubating Vector API) a SIMD one.
 */
public interface Kernels {

    String name();

    /** Gravity, buoyancy and drag. */
    void forces(Bodies b, int from, int to, Grid grid, float dt, float gravity);

    /** Probes the eight corners of each piece against the terrain and solves the contacts. */
    void terrain(Bodies b, int from, int to, Grid grid, float dt);

    /** Moves the pieces by their velocities. */
    void integrate(Bodies b, int from, int to, float dt);

    /** Counts how long each piece has been calm. */
    void calm(Bodies b, int from, int to);

    static Kernels scalar() {
        return ScalarKernels.INSTANCE;
    }

    /**
     * The SIMD kernels, or {@code null} if this JVM was not started with
     * {@code --add-modules jdk.incubator.vector}. Loaded by name, so nothing here needs the module.
     */
    static Kernels vector() {
        return VectorHolder.KERNELS;
    }

    /** The fastest kernels this JVM can run. */
    static Kernels best() {
        Kernels vector = vector();
        return vector != null ? vector : scalar();
    }

    /** Loads the SIMD kernels once, only if the module is there. */
    final class VectorHolder {

        private VectorHolder() {}

        static final Kernels KERNELS = load();

        private static Kernels load() {
            if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty())
                return null;
            try {
                return (Kernels) Class.forName("com.github.justadeni.displayphysics.sim.VectorKernels")
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }
    }

}
