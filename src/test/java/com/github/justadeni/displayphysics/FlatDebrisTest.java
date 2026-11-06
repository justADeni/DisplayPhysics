package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.FlatDebris;
import com.github.justadeni.displayphysics.sim.Kernels;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class FlatDebrisTest {

    private static final TerrainSource FLAT = new TerrainSource() {
        public byte classify(int x, int y, int z) { return y < 64 ? SOLID : AIR; }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    /** A block of pieces thrown outward above the ground (relative coordinates: y = 0 is the ground surface). */
    private static FlatDebris cloud(int count, Kernels kernels) {
        var random = new SplittableRandom(7);
        var bodies = new Bodies(count);
        int side = (int) Math.ceil(Math.cbrt(count));
        for (int i = 0; i < count; i++) {
            float x = i % side - side / 2f, y = 5 + i / (side * side), z = i / side % side - side / 2f;
            bodies.px[i] = x + 0.5f; bodies.py[i] = y + 0.5f; bodies.pz[i] = z + 0.5f;
            float speed = (float) random.nextDouble(3, 9);
            float length = Math.max(1e-3f, (float) Math.sqrt(x * x + z * z));
            bodies.vx[i] = x / length * speed; bodies.vy[i] = 3f; bodies.vz[i] = z / length * speed;
            FlatDebris.initCube(bodies, i, 1f, i % 5 != 0, 0.6f, 0.25f, 0f);
        }
        return new FlatDebris(bodies, FLAT, 0, 64, 0, 9.81f, 0.6f, 0f, kernels);
    }

    private static int settle(FlatDebris debris) {
        int ticks = 0;
        while (!debris.step() && ++ticks < 1500) { }
        return ticks;
    }

    @Test
    void aCloudLandsAndComesToRestOnTheGround() {
        var debris = cloud(2000, Kernels.scalar());
        int ticks = settle(debris);
        // A piece balanced on a corner of its neighbours may keep rolling for a long time (the assembly's maximum duration ends that), so a straggler is fine
        assertTrue(debris.awakeCount() <= 2, "still awake after " + ticks + " ticks: " + debris.awakeCount());
        for (int i = 0; i < 2000; i++) {
            float y = debris.y(i);
            assertTrue(Float.isFinite(debris.x(i)) && Float.isFinite(y) && Float.isFinite(debris.z(i)), "piece " + i + " is not finite");
            assertTrue(y > -0.1f, "piece " + i + " sank into the ground: " + y);
        }
    }

    @Test
    void resultingStackStaysUpright() {
        var debris = cloud(500, Kernels.scalar());
        settle(debris);
        double mean = 0;
        for (int i = 0; i < 500; i++)
            mean += debris.y(i);
        mean /= 500;
        // Pieces rest on the ground or on each other: centres are near block height, and none float high up
        assertTrue(mean > 0.3 && mean < 3, "mean resting height " + mean);
    }
}
