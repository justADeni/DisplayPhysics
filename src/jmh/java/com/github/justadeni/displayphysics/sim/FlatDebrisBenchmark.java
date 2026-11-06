package com.github.justadeni.displayphysics.sim;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;

import org.openjdk.jmh.annotations.*;

/** The flat-array debris engine, scalar and SIMD kernels, on the same workload as the baseline. */
@State(Scope.Thread)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
public class FlatDebrisBenchmark {

    /** One second of simulation per operation. */
    private static final int TICKS = 20;

    @Param({"1000", "10000", "100000"})
    public int count;

    @Param({"scalar", "vector"})
    public String kernels;

    private FlatDebris debris;

    @Setup(Level.Invocation)
    public void setUp() {
        Kernels chosen = kernels.equals("vector") ? Kernels.vector() : Kernels.scalar();
        if (chosen == null)
            throw new IllegalStateException("The vector kernels are not available");
        debris = Scenario.flat(Scenario.cloud(count, 42), chosen);
    }

    @Benchmark
    public boolean oneSecond() {
        boolean asleep = false;
        for (int t = 0; t < TICKS; t++)
            asleep = debris.step();
        return asleep;
    }

}
