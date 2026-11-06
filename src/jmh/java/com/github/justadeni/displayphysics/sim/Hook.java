package com.github.justadeni.displayphysics.sim;

/** Reaches the vector kernels' debug counters by reflection (they live in another source set). */
final class Hook {
    static long[] times() {
        try {
            return (long[]) Class.forName("com.github.justadeni.displayphysics.sim.kernels.VectorKernels").getField("TIME").get(null);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
}
