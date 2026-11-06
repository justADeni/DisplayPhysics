package com.github.justadeni.displayphysics.sim;

final class Hook {
    static long[] times() {
        try {
            return (long[]) Class.forName("com.github.justadeni.displayphysics.sim.kernels.VectorKernels").getField("TIME").get(null);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
}
