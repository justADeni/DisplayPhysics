package com.github.justadeni.displayphysics.sim;

import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
public class DebrisBaselineBenchmark {
    private static final int TICKS = 20;

    @Param({"1000", "10000", "100000"})
    public int count;

    private Debris debris;

    @Setup(Level.Invocation)
    public void setUp() {
        debris = Scenario.debris(Scenario.cloud(count, 42));
    }

    @Benchmark
    public boolean oneSecond() {
        boolean asleep = false;
        for (int t = 0; t < TICKS; t++)
            asleep = debris.step();
        return asleep;
    }
}
