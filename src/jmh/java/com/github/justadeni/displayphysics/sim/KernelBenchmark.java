package com.github.justadeni.displayphysics.sim;
import com.github.justadeni.displayphysics.sim.kernels.Sight;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;

import org.openjdk.jmh.annotations.*;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
public class KernelBenchmark {
    @Param({"64", "256", "1024", "4096", "16384", "65536", "262144"})
    public int count;

    @Param({"mixed", "flying", "resting"})
    public String scene;

    @Param({"flat"})
    public String terrain;

    @Param({"scalar", "vector"})
    public String kernels;

    private Kernels k;
    private Bodies b;
    private Grid grid;

    @Setup(Level.Trial)
    public void setUp() {
        k = switch (kernels) {
            case "scalar" -> Kernels.scalar();
            case "vector" -> Kernels.vector();
            default -> load(kernels);
        };
        if (k == null)
            throw new IllegalStateException("The vector kernels are not available");

        var random = new SplittableRandom(3);
        b = new Bodies(count);
        double side = Math.sqrt(count / 0.35);
        for (int i = 0; i < count; i++) {
            b.px[i] = (float) random.nextDouble(0, side);
            b.py[i] = (float) switch (scene) {
                case "flying" -> random.nextDouble(3.0, 6.0);
                case "resting" -> random.nextDouble(0.40, 0.48);
                default -> random.nextDouble(0.2, 2.0);
            };
            b.pz[i] = (float) random.nextDouble(0, side);
            b.vx[i] = (float) random.nextDouble(-4, 4);
            b.vy[i] = (float) random.nextDouble(-4, 4);
            b.vz[i] = (float) random.nextDouble(-4, 4);
            b.wx[i] = (float) random.nextDouble(-3, 3);
            b.wy[i] = (float) random.nextDouble(-3, 3);
            b.wz[i] = (float) random.nextDouble(-3, 3);
            float qx = (float) random.nextDouble(-1, 1), qy = (float) random.nextDouble(-1, 1), qz = (float) random.nextDouble(-1, 1), qw = (float) random.nextDouble(-1, 1);
            float length = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            FlatDebris.initCube(b, i, 1f, i % 5 != 0, 0.6f, 0.25f, 0f);
            b.qx[i] = qx / length; b.qy[i] = qy / length; b.qz[i] = qz / length; b.qw[i] = qw / length;
            if (scene.equals("resting")) {
                b.qx[i] = qx * 0.02f; b.qy[i] = qy * 0.02f; b.qz[i] = qz * 0.02f; b.qw[i] = 1f;
                b.vx[i] *= 0.05f; b.vy[i] *= 0.05f; b.vz[i] *= 0.05f; b.wx[i] *= 0.05f; b.wy[i] *= 0.05f; b.wz[i] *= 0.05f;
            }
        }
        grid = new Grid(switch (terrain) { case "forest" -> Scenario.FOREST; case "hills" -> Scenario.HILLS; default -> Scenario.FLAT; }, Scenario.ORIGIN_X, Scenario.ORIGIN_Y, Scenario.ORIGIN_Z);
        int s = (int) side + 8;
        grid.ensure(-6, -6, -6, s, 12, s);
    }

    Bodies bodies() { return b; }

    Grid grid() { return grid; }

    Kernels kernels() { return k; }

    private static Kernels load(String variant) {
        try {
            String name = "com.github.justadeni.displayphysics.sim.kernels.VectorKernels" + Character.toUpperCase(variant.charAt(0)) + variant.substring(1);
            return (Kernels) Class.forName(name).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Benchmark
    public void forces() {
        k.forces(b, 0, count, grid, 0.0125f, 9.81f);
    }

    @Benchmark
    public void terrain() {
        k.terrain(b, 0, count, grid, 0.0125f);
    }

    @Benchmark
    public void integrate() {
        k.integrate(b, 0, count, 0.0001f);
    }

    @Benchmark
    public void calm() {
        k.calm(b, 0, count);
    }

    private boolean[] seen;
    private Sight sight;

    private void prepareSight() {
        if (seen == null) {
            seen = new boolean[count];
            float middle = (float) Math.sqrt(count / 0.35) / 2f;
            sight = new Sight(middle, 12f, -4f, 0f, -0.3f, 0.95f, true, 0.6f, 0.8f, 400f, 1f);
        }
    }

    @Benchmark
    public boolean[] visibility() {
        prepareSight();
        k.visibility(b, 0, count, grid, null, grid.coarse, sight, seen);
        return seen;
    }

    @Benchmark
    public boolean[] visibilityFlat() {
        prepareSight();
        k.visibility(b, 0, count, grid, null, null, sight, seen);
        return seen;
    }
}
