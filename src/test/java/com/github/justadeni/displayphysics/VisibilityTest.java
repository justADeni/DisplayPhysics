package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.kernels.Sight;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;
import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.FlatDebris;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;
import com.github.justadeni.displayphysics.sim.kernels.Sight;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisibilityTest {
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
            {6, 2, 0},
            {14, 2, 0},
            {14, 40, 0},
            {14, 2, 30},
            {120, 2, 0},
            {1.2f, 1.5f, 0.3f},
    };

    private static Sight sight(float lookX, boolean cone) {
        double half = Occlusion.standard().coneHalfAngle();
        return new Sight(0f, 1.6f, 0f, lookX, 0f, 0f, cone, (float) Math.cos(half), (float) Math.sin(half), 96f, 0.6f);
    }

    private static boolean[] visible(TerrainSource terrain, List<float[]> positions, Kernels kernels, boolean piecesOcclude, Sight sight) {
        var bodies = new Bodies(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            bodies.px[i] = positions.get(i)[0]; bodies.py[i] = positions.get(i)[1]; bodies.pz[i] = positions.get(i)[2];
            FlatDebris.initCube(bodies, i, 1f, true, 0.6f, 0.25f, 0f);
        }
        var debris = new FlatDebris(bodies, terrain, 0, 64, 0, 9.81f, 0.6f, 0f, kernels);
        debris.step();

        var out = new boolean[positions.size()];
        debris.visibility(sight, piecesOcclude, out);
        return out;
    }

    @Test
    void aWallHidesWhatIsBehindIt() {
        var seen = visible(WALLED, List.of(PIECES), Kernels.scalar(), true, sight(1f, false));
        assertTrue(seen[0], "in front of the wall");
        assertFalse(seen[1], "behind the wall");
        assertTrue(seen[2], "over the top of the wall");
        assertTrue(seen[3], "around the side of the wall");
        assertFalse(seen[4], "beyond the maximum distance");
        assertTrue(seen[5], "next to the eye");
    }

    private static List<float[]> plugAndTarget() {
        var positions = new ArrayList<float[]>();
        for (int y = 0; y < 5; y++)
            for (int z = -1; z <= 1; z++)
                positions.add(new float[]{10.5f, y + 0.5f, z + 0.5f});
        positions.add(new float[]{16.5f, 2.5f, 0.5f});
        positions.add(new float[]{16.5f, 12.5f, 0.5f});
        return positions;
    }

    @Test
    void movingPiecesHideWhatIsBehindThemWhenAsked() {
        var positions = plugAndTarget();
        int target = positions.size() - 2, above = positions.size() - 1;

        var terrainOnly = visible(OPEN, positions, Kernels.scalar(), false, sight(1f, false));
        assertTrue(terrainOnly[target], "only terrain occludes: nothing is in the way");

        var withPieces = visible(OPEN, positions, Kernels.scalar(), true, sight(1f, false));
        assertFalse(withPieces[target], "the plug of pieces hides it");
        assertTrue(withPieces[above], "a piece the plug does not cover is still seen");
    }

    @Test
    void aPieceDoesNotHideItself() {
        var seen = visible(OPEN, List.of(new float[]{12.5f, 2.5f, 0.5f}), Kernels.scalar(), true, sight(1f, false));
        assertTrue(seen[0]);
    }

    @Test
    void whatIsBehindThePlayerIsHidden() {
        var positions = List.of(
                new float[]{8, 1.6f, 0},
                new float[]{-8, 1.6f, 0},
                new float[]{3, 1.6f, 7},
                new float[]{0.5f, 1.6f, -1f});

        var facingIn = visible(OPEN, positions, Kernels.scalar(), true, sight(1f, true));
        assertTrue(facingIn[0]);
        assertFalse(facingIn[1], "behind the viewer");
        assertTrue(facingIn[2], "the cone is wide: 110 degrees vertical at 16:9 reaches far to the sides");
        assertTrue(facingIn[3]);

        var facingOut = visible(OPEN, positions, Kernels.scalar(), true, sight(-1f, true));
        assertFalse(facingOut[0], "the same piece is behind them now");
        assertTrue(facingOut[1]);

        assertTrue(visible(OPEN, positions, Kernels.scalar(), true, sight(-1f, false))[0], "without the cone, only distance and walls decide");
    }

    @Test
    void theConeIsAsWideAsTheScreenCornersPlusTheMargin() {
        assertEquals(71.1 + 15, Math.toDegrees(Occlusion.standard().coneHalfAngle()), 0.1);
        assertTrue(Occlusion.standard().withView(70f, 4f / 3f, 5f).coneHalfAngle() < Occlusion.standard().coneHalfAngle());
        assertEquals(Math.PI, Occlusion.standard().withoutViewCone().coneHalfAngle());
        assertTrue(Math.toDegrees(Occlusion.standard().withView(110f, 32f / 9f, 15f).coneHalfAngle()) > 90);
    }

    @Test
    void vectorKernelsAgree() {
        var vector = Kernels.vector();
        Assumptions.assumeTrue(vector != null, "no SIMD kernels in this JVM");
        for (boolean cone : new boolean[]{false, true}) {
            assertArrayEquals(visible(WALLED, List.of(PIECES), Kernels.scalar(), true, sight(1f, cone)), visible(WALLED, List.of(PIECES), vector, true, sight(1f, cone)));
            var plug = plugAndTarget();
            assertArrayEquals(visible(OPEN, plug, Kernels.scalar(), true, sight(1f, cone)), visible(OPEN, plug, vector, true, sight(1f, cone)));
        }
    }
}
