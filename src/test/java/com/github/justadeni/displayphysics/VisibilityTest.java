package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.Bodies;
import com.github.justadeni.displayphysics.sim.FlatDebris;
import com.github.justadeni.displayphysics.sim.Kernels;
import com.github.justadeni.displayphysics.sim.Sight;
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

    /** A viewer at (0, 1.6, 0), looking along x (+1 or -1), with the standard view cone or none. */
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
        debris.step(); // builds the terrain grid around the pieces

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
                new float[]{8, 1.6f, 0},      // 0: straight ahead
                new float[]{-8, 1.6f, 0},     // 1: straight behind
                new float[]{3, 1.6f, 7},      // 2: well off to the side but inside the cone
                new float[]{0.5f, 1.6f, -1f}); // 3: next to the eye (and off the line to the others), whatever way they look

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
        // 110 degrees vertical at 16:9: the half angle to a corner is atan(sqrt(tan(55)^2 * (1 + (16/9)^2))) = 71.1 degrees
        assertEquals(71.1 + 15, Math.toDegrees(Occlusion.standard().coneHalfAngle()), 0.1);
        // A narrower window and field of view give a narrower cone; the cone can be turned off
        assertTrue(Occlusion.standard().withView(70f, 4f / 3f, 5f).coneHalfAngle() < Occlusion.standard().coneHalfAngle());
        assertEquals(Math.PI, Occlusion.standard().withoutViewCone().coneHalfAngle());
        // A very wide window needs more than half the sphere
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
