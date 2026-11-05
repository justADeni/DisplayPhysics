package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.FlatDebris;
import com.github.justadeni.displayphysics.sim.Kernels;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisibilityTest {

    /** Flat ground, and a wall across x = 10 that is 16 blocks high and 21 wide. */
    private static final TerrainSource WALLED = new TerrainSource() {
        public byte classify(int x, int y, int z) {
            if (y < 64)
                return SOLID;
            return x == 10 && y < 80 && Math.abs(z) <= 10 ? SOLID : AIR;
        }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    private static final TerrainSource OPEN = new TerrainSource() {
        public byte classify(int x, int y, int z) { return y < 64 ? SOLID : AIR; }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    private static final float[][] PIECES = {
            {6, 2, 0},      // 0: in front of the wall
            {14, 2, 0},     // 1: behind the wall
            {14, 40, 0},    // 2: behind it but high above: the line clears the top
            {14, 2, 30},    // 3: behind it but off to the side: the line goes around the wall
            {120, 2, 0},    // 4: too far away
            {1.2f, 1.5f, 0.3f}, // 5: right next to the eye
    };

    private static boolean[] visible(TerrainSource terrain, List<float[]> positions, Kernels kernels, boolean piecesOcclude) {
        var bodies = new Bodies(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            bodies.px[i] = positions.get(i)[0]; bodies.py[i] = positions.get(i)[1]; bodies.pz[i] = positions.get(i)[2];
            FlatDebris.initCube(bodies, i, 1f, true, 0.6f, 0.25f, 0f);
        }
        var debris = new FlatDebris(bodies, terrain, 0, 64, 0, 9.81f, 0.6f, 0f, kernels);
        debris.step(); // builds the terrain grid around the pieces

        var out = new boolean[positions.size()];
        debris.visibility(0f, 1.6f, 0f, 96f, 0.6f, piecesOcclude, out);
        return out;
    }

    @Test
    void aWallHidesWhatIsBehindIt() {
        var seen = visible(WALLED, List.of(PIECES), Kernels.scalar(), true);
        assertTrue(seen[0], "in front of the wall");
        assertFalse(seen[1], "behind the wall");
        assertTrue(seen[2], "over the top of the wall");
        assertTrue(seen[3], "around the side of the wall");
        assertFalse(seen[4], "beyond the maximum distance");
        assertTrue(seen[5], "next to the eye");
    }

    /** A plug of pieces, 3 wide and 5 high, across the line to a piece further on. */
    private static List<float[]> plugAndTarget() {
        var positions = new ArrayList<float[]>();
        for (int y = 0; y < 5; y++)
            for (int z = -1; z <= 1; z++)
                positions.add(new float[]{10.5f, y + 0.5f, z + 0.5f});
        positions.add(new float[]{16.5f, 2.5f, 0.5f}); // the target, right behind it
        positions.add(new float[]{16.5f, 12.5f, 0.5f}); // clear of it, high above
        return positions;
    }

    @Test
    void movingPiecesHideWhatIsBehindThemWhenAsked() {
        var positions = plugAndTarget();
        int target = positions.size() - 2, above = positions.size() - 1;

        var terrainOnly = visible(OPEN, positions, Kernels.scalar(), false);
        assertTrue(terrainOnly[target], "only terrain occludes: nothing is in the way");

        var withPieces = visible(OPEN, positions, Kernels.scalar(), true);
        assertFalse(withPieces[target], "the plug of pieces hides it");
        assertTrue(withPieces[above], "a piece the plug does not cover is still seen");
        assertTrue(withPieces[1] || withPieces[0] || withPieces[2], "the near face of the plug itself is seen");
    }

    @Test
    void aPieceDoesNotHideItself() {
        var seen = visible(OPEN, List.of(new float[]{12.5f, 2.5f, 0.5f}), Kernels.scalar(), true);
        assertTrue(seen[0]);
    }

    @Test
    void vectorKernelsAgree() {
        var vector = Kernels.vector();
        Assumptions.assumeTrue(vector != null, "no SIMD kernels in this JVM");
        assertArrayEquals(visible(WALLED, List.of(PIECES), Kernels.scalar(), true), visible(WALLED, List.of(PIECES), vector, true));
        var plug = plugAndTarget();
        assertArrayEquals(visible(OPEN, plug, Kernels.scalar(), true), visible(OPEN, plug, vector, true));
    }

}
