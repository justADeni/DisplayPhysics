package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.Simulator;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorTest {

    /** Solid below y = 64, air above. */
    private static final TerrainSource FLAT = new TerrainSource() {
        public byte classify(int x, int y, int z) { return y < 64 ? SOLID : AIR; }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    /** A 1x8 trunk with a leaf blob on top, standing on the ground. */
    private static Simulator tree(BreakMode mode, Hinge hinge) {
        int n = 8 + 9;
        int[] x = new int[n], y = new int[n], z = new int[n];
        boolean[] hard = new boolean[n];
        float[] mass = new float[n];
        for (int i = 0; i < 8; i++) { x[i] = 0; y[i] = 64 + i; z[i] = 0; hard[i] = true; mass[i] = 1; }
        for (int i = 0; i < 9; i++) { x[8 + i] = i % 3 - 1; y[8 + i] = 72; z[8 + i] = i / 3 - 1; mass[8 + i] = 0.3f; }
        return new Simulator(x, y, z, hard, mass, hinge, new Settings().breakMode(mode), FLAT);
    }

    private static Hinge hinge() {
        return new Hinge(1.0, 64, 0.5, new Vector3f(0, 0, 1), 9f, 0.05f, (float) Math.toRadians(40));
    }

    private static int run(Simulator sim) {
        int guard = 0;
        while (!sim.step() && guard++ < 2000) { }
        assertTrue(guard < 2000, "never settled");
        return sim.tick();
    }

    @Test
    void rigidTreeFallsOverAndRestsOnTheGround() {
        Simulator sim = tree(BreakMode.RIGID, hinge());
        run(sim);
        assertFalse(sim.broken());
        double[] at = new double[3];
        for (int i = 0; i < sim.count(); i++) {
            sim.restingCenter(i, at);
            assertTrue(at[1] > 63.4, "piece " + i + " sank into the ground: " + at[1]);
            assertTrue(at[1] < 66, "piece " + i + " is still standing: " + at[1]);
        }
    }

    @Test
    void breakOnImpactScattersIntoBlocks() {
        Simulator sim = tree(BreakMode.BREAK_ON_IMPACT, hinge());
        run(sim);
        assertTrue(sim.broken());
        double[] at = new double[3];
        for (int i = 0; i < sim.count(); i++) {
            sim.restingCenter(i, at);
            assertTrue(at[1] > 63.3 && at[1] < 66, "piece " + i + " ended at " + at[1]);
        }
    }

    @Test
    void freeBodyWithoutHingeDropsAndLands() {
        Simulator sim = tree(BreakMode.RIGID, null);
        run(sim);
        double[] at = new double[3];
        sim.restingCenter(0, at);
        assertTrue(at[1] > 63.4 && at[1] < 66);
    }
}
