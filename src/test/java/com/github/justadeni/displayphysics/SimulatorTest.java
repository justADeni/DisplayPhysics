package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.Simulator;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SimulatorTest {
    private static final TerrainSource FLAT = new TerrainSource() {
        public byte classify(int x, int y, int z) { return y < 64 ? SOLID : AIR; }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    private record Cell(int x, int y, int z, boolean hard, float mass) {}

    private static List<Cell> tower() {
        var cells = new ArrayList<Cell>();
        for (int i = 0; i < 8; i++)
            cells.add(new Cell(0, 64 + i, 0, true, 1f));
        for (int i = 0; i < 9; i++)
            cells.add(new Cell(i % 3 - 1, 72, i / 3 - 1, false, 0.3f));
        return cells;
    }

    private static List<Cell> cube(int size, int y) {
        var cells = new ArrayList<Cell>();
        for (int x = 0; x < size; x++)
            for (int h = 0; h < size; h++)
                for (int z = 0; z < size; z++)
                    cells.add(new Cell(x, y + h, z, true, 1f));
        return cells;
    }

    private static Simulator simulator(List<Cell> cells, Hinge hinge, Mode mode) {
        return new Simulator(
                cells.stream().mapToInt(Cell::x).toArray(),
                cells.stream().mapToInt(Cell::y).toArray(),
                cells.stream().mapToInt(Cell::z).toArray(),
                booleans(cells),
                floats(cells),
                hinge, new Settings().mode(mode), FLAT);
    }

    private static boolean[] booleans(List<Cell> cells) {
        var out = new boolean[cells.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = cells.get(i).hard();
        return out;
    }

    private static float[] floats(List<Cell> cells) {
        var out = new float[cells.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = cells.get(i).mass();
        return out;
    }

    private static Hinge hinge() {
        return new Hinge(1.0, 64, 0.5, new Vector3f(0, 0, 1), 9f, 0.05f, (float) Math.toRadians(40));
    }

    private static void run(Simulator sim) {
        int guard = 0;
        while (!sim.step() && guard++ < 2000) { }
        assertTrue(guard < 2000, "never settled");
    }

    private static double[] centre(Simulator sim, int piece) {
        var at = new double[3];
        sim.restingCenter(piece, at);
        return at;
    }

    @Test
    void packedPositionsRoundTripAcrossTheWholeWorld() {
        int[] horizontal = {XYZ.MIN_XZ, -30_000_000, -1_000, -17, -1, 0, 1, 16, 1_000, 30_000_000, XYZ.MAX_XZ};
        int[] vertical = {XYZ.MIN_Y, -1, 0, 63, 64, 319, 320, XYZ.MAX_Y};
        for (int x : horizontal)
            for (int y : vertical)
                for (int z : horizontal) {
                    long packed = XYZ.pack(x, y, z);
                    assertEquals(x, XYZ.get(XYZ.X, packed), "x of " + x + "," + y + "," + z);
                    assertEquals(y, XYZ.get(XYZ.Y, packed), "y of " + x + "," + y + "," + z);
                    assertEquals(z, XYZ.get(XYZ.Z, packed), "z of " + x + "," + y + "," + z);
                }
    }

    @Test
    void offsetMovesAPackedPosition() {
        long moved = XYZ.offset(XYZ.pack(-5, 70, 9), 2, -3, -10);
        assertEquals(-3, XYZ.get(XYZ.X, moved));
        assertEquals(67, XYZ.get(XYZ.Y, moved));
        assertEquals(-1, XYZ.get(XYZ.Z, moved));
    }

    @Test
    void rigidAssemblyTipsOverAndRestsOnTheGround() {
        var sim = simulator(tower(), hinge(), Mode.RIGID);
        run(sim);
        assertFalse(sim.broken());
        for (int i = 0; i < sim.count(); i++) {
            double y = centre(sim, i)[1];
            assertTrue(y > 63.4 && y < 66, "piece " + i + " ended at " + y);
        }
    }

    @Test
    void breakOnImpactShattersIntoSeparatePieces() {
        var sim = simulator(tower(), hinge(), Mode.breakOnImpact(6f));
        run(sim);
        assertTrue(sim.broken());
        for (int i = 0; i < sim.count(); i++) {
            double y = centre(sim, i)[1];
            assertTrue(y > 63.3 && y < 66, "piece " + i + " ended at " + y);
        }
    }

    @Test
    void freeBodyWithoutHingeDropsAndLands() {
        var sim = simulator(tower(), null, Mode.RIGID);
        run(sim);
        double y = centre(sim, 0)[1];
        assertTrue(y > 63.4 && y < 66);
    }

    @Test
    void fallApartStartsAsSeparatePiecesAndCollapses() {
        var sim = simulator(tower(), hinge(), Mode.FALL_APART);
        assertTrue(sim.broken(), "should be separate bodies from the start");
        sim.step();
        assertTrue(sim.brokeThisTick(), "the break is reported on the first tick");
        run(sim);
        for (int i = 0; i < sim.count(); i++) {
            double y = centre(sim, i)[1];
            assertTrue(y > 63.3 && y < 66, "piece " + i + " ended at " + y);
        }
    }

    @Test
    void explosionThrowsEveryPieceAwayFromTheCentre() {
        var cells = cube(3, 64);
        var sim = simulator(cells, null, Mode.explode(Blast.everything().force(14f)));
        assertTrue(sim.broken());
        run(sim);

        double before = IntStream.range(0, cells.size()).mapToDouble(i -> distance(cells.get(i).x() + 0.5, cells.get(i).z() + 0.5, 1.5, 1.5)).average().orElseThrow();
        double after = IntStream.range(0, cells.size()).mapToDouble(i -> distance(centre(sim, i)[0], centre(sim, i)[2], 1.5, 1.5)).average().orElseThrow();
        assertTrue(after > before + 2, "pieces spread from " + before + " to " + after);
    }

    @Test
    void explosionOnlyThrowsPiecesNearTheEpicenter() {
        var sim = simulator(tower(), null, Mode.explode(Blast.at(0.5, 64.5, 0.5f, 2f).force(14f)));
        assertFalse(sim.asleep(0), "a piece at the epicenter is thrown");
        assertFalse(sim.asleep(2), "a piece within the radius is thrown");
        assertTrue(sim.asleep(7), "a piece out of reach starts asleep, as solid ground");

        run(sim);
        double farthest = IntStream.range(0, 3)
                .mapToDouble(i -> Math.hypot(centre(sim, i)[0] - 0.5, centre(sim, i)[2] - 0.5))
                .max().orElseThrow();
        assertTrue(farthest > 1, "pieces in reach were thrown sideways: " + farthest);
    }

    @Test
    void piecesOutsideTheRadiusCanBeReleasedInstead() {
        var blast = Blast.at(0.5, 64.5, 0.5f, 2f).remainder(Blast.Remainder.FALL);
        var sim = simulator(tower(), null, Mode.explode(blast));
        assertFalse(sim.asleep(7), "unreached pieces are awake and fall");
        run(sim);
        assertTrue(centre(sim, 7)[1] < 70, "the unreached top came down");
    }

    @Test
    void directionalExplosionFliesTheWayItIsAimed() {
        var cells = cube(3, 64);
        var blast = Blast.everything().aim(Blast.Aim.cone(1, 0, 0, (float) Math.toRadians(20))).force(16f).lift(0f);
        var sim = simulator(cells, null, Mode.explode(blast));
        run(sim);

        double dx = IntStream.range(0, cells.size()).mapToDouble(i -> centre(sim, i)[0] - (cells.get(i).x() + 0.5)).average().orElseThrow();
        double dz = IntStream.range(0, cells.size()).mapToDouble(i -> centre(sim, i)[2] - (cells.get(i).z() + 0.5)).average().orElseThrow();
        assertTrue(dx > 3, "pieces travelled along the aim: " + dx);
        assertTrue(Math.abs(dz) < dx / 3, "and stayed near the line: dz=" + dz + " dx=" + dx);
    }

    @Test
    void wideningTheSpreadWidensTheFan() {
        double narrow = sidewaysSpread(Blast.Aim.cone(1, 0, 0, (float) Math.toRadians(5)));
        double wide = sidewaysSpread(Blast.Aim.cone(1, 0, 0, (float) Math.toRadians(60)));
        assertTrue(wide > narrow * 1.5, "narrow " + narrow + " vs wide " + wide);
    }

    private static double sidewaysSpread(Blast.Aim aim) {
        var cells = cube(3, 64);
        var sim = simulator(cells, null, Mode.explode(Blast.everything().aim(aim).force(16f).lift(0f)));
        run(sim);
        return IntStream.range(0, cells.size()).mapToDouble(i -> Math.abs(centre(sim, i)[2] - (cells.get(i).z() + 0.5))).average().orElseThrow();
    }

    private static double distance(double x, double z, double cx, double cz) {
        return Math.hypot(x - cx, z - cz);
    }
}
