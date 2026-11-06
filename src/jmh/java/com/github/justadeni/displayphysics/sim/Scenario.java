package com.github.justadeni.displayphysics.sim;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;

import com.github.justadeni.displayphysics.TerrainSource;

import java.util.SplittableRandom;

/** The workload every benchmark measures: a cloud of pieces blown outward above flat ground, then landing and settling. */
public final class Scenario {

    private Scenario() {}

    /** Solid below y = 64, air above. */
    public static final TerrainSource FLAT = new TerrainSource() {
        @Override
        public byte classify(int x, int y, int z) {
            return y < 64 ? SOLID : AIR;
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    };

    /** Where the cloud starts, relative to the origin block (which sits on the ground at y = 64). */
    public static final int ORIGIN_X = 0, ORIGIN_Y = 64, ORIGIN_Z = 0;

    /** Piece positions (block corners, relative to the origin) and launch velocities for {@code count} pieces. */
    public record Cloud(float[] x, float[] y, float[] z, float[] vx, float[] vy, float[] vz, boolean[] hard) {

        public int count() {
            return x.length;
        }
    }

    /** A lattice of roughly cubic shape, 6 blocks above the ground, thrown away from its centre. */
    public static Cloud cloud(int count, long seed) {
        int side = (int) Math.ceil(Math.cbrt(count));
        var random = new SplittableRandom(seed);
        var cloud = new Cloud(new float[count], new float[count], new float[count],
                new float[count], new float[count], new float[count], new boolean[count]);

        float half = side / 2f;
        for (int i = 0; i < count; i++) {
            int lx = i % side, lz = i / side % side, ly = i / (side * side);
            cloud.x[i] = lx - half;
            cloud.y[i] = 6 + ly;
            cloud.z[i] = lz - half;

            float dx = cloud.x[i], dy = ly - half, dz = cloud.z[i];
            float length = Math.max(1e-3f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
            float speed = (float) random.nextDouble(4, 12);
            cloud.vx[i] = dx / length * speed;
            cloud.vy[i] = dy / length * speed + 3f;
            cloud.vz[i] = dz / length * speed;
            cloud.hard[i] = random.nextInt(5) != 0;
        }
        return cloud;
    }

    /** The cloud as the existing object-based debris engine wants it. */
    public static Debris debris(Cloud cloud) {
        int n = cloud.count();
        var pieces = new Body[n];
        for (int i = 0; i < n; i++) {
            var piece = new Body();
            piece.x.set(cloud.x[i] + 0.5f, cloud.y[i] + 0.5f, cloud.z[i] + 0.5f);
            piece.v.set(cloud.vx[i], cloud.vy[i], cloud.vz[i]);
            Debris.makeCube(piece, 1f);
            pieces[i] = piece;
        }
        var terrain = new Voxels(FLAT, ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        return new Debris(terrain, pieces, cloud.hard(), 9.81f, 0.6f, 0.25f, 0f);
    }

    /** The cloud as the flat-array debris engine wants it. */
    public static FlatDebris flat(Cloud cloud, Kernels kernels) {
        int n = cloud.count();
        var bodies = new Bodies(n);
        for (int i = 0; i < n; i++) {
            bodies.px[i] = cloud.x[i] + 0.5f;
            bodies.py[i] = cloud.y[i] + 0.5f;
            bodies.pz[i] = cloud.z[i] + 0.5f;
            bodies.vx[i] = cloud.vx[i];
            bodies.vy[i] = cloud.vy[i];
            bodies.vz[i] = cloud.vz[i];
            FlatDebris.initCube(bodies, i, 1f, cloud.hard[i], 0.6f, 0.25f, 0f);
        }
        return new FlatDebris(bodies, FLAT, ORIGIN_X, ORIGIN_Y, ORIGIN_Z, 9.81f, 0.6f, 0f, kernels);
    }

}
