package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.sim.Simulator;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Counts the pose updates three viewers would be sent while a cube blows up beside a wall, under three culling
 * settings, all from one and the same simulation, frame for frame, so every number is directly comparable:
 * <ul>
 *   <li>a viewer in the open, looking at the blast,</li>
 *   <li>the same spot, looking the other way,</li>
 *   <li>a viewer on the far side of the wall, looking at it.</li>
 * </ul>
 */
class OcclusionPacketCountTest {

    /** Flat ground at y = 64 and a wall across x = 12: 8 blocks high, 51 wide. */
    private static final TerrainSource WALLED = new TerrainSource() {
        public byte classify(int x, int y, int z) {
            if (y < 64)
                return SOLID;
            return x == 12 && y < 72 && z >= -20 && z <= 30 ? SOLID : AIR;
        }
        public int minY() { return -64; }
        public int maxY() { return 320; }
    };

    private static final String[] VIEWERS = {"watching", "looking away", "behind the wall"};
    private static final double[][] EYES = {{-10, 65.62, 3.5}, {-10, 65.62, 3.5}, {18, 65.62, 3.5}};
    private static final float[][] LOOKS = {{1, 0, 0}, {-1, 0, 0}, {-1, 0, 0}};

    private static final String[] SETTINGS = {"terrain occludes", "+ pieces occlude", "+ view cone"};
    private static final Occlusion[] OCCLUSION = {
            Occlusion.standard().withPieces(false).withoutViewCone(),
            Occlusion.standard().withoutViewCone(),
            Occlusion.standard()};

    /** Updates sent over the blast: {@code sent[setting][viewer]}, and what an unculled feed sends. */
    private record Counts(long[][] sent, long everyone, int pieces, int ticks, boolean flushedAtTheEnd) {}

    private static Counts run() {
        var cells = new ArrayList<int[]>();
        for (int x = 0; x < 7; x++)
            for (int y = 64; y < 71; y++)
                for (int z = 0; z < 7; z++)
                    cells.add(new int[]{x, y, z});
        int n = cells.size();

        var mass = new float[n];
        java.util.Arrays.fill(mass, 1f);
        var sim = new Simulator(
                cells.stream().mapToInt(c -> c[0]).toArray(), cells.stream().mapToInt(c -> c[1]).toArray(), cells.stream().mapToInt(c -> c[2]).toArray(),
                new boolean[n], mass, null, new Settings().mode(Mode.explode(Blast.everything().force(14f))), WALLED);

        var identity = new float[n * PoseFeed.STRIDE];
        for (int i = 0; i < n; i++)
            identity[i * PoseFeed.STRIDE + 3] = 1f;
        var everyone = new PoseFeed(identity, n, false);
        var feeds = new PoseFeed[SETTINGS.length][VIEWERS.length];
        for (var row : feeds)
            for (int v = 0; v < row.length; v++)
                row[v] = new PoseFeed(identity, n, true);

        var pose = new float[n * PoseFeed.STRIDE];
        var selection = new int[n];
        var q = new Quaternionf();
        var t = new Vector3f();
        var sent = new long[SETTINGS.length][VIEWERS.length];
        long all = 0;
        int ticks = 0;

        for (boolean done = false; !done && ticks < 1500; ) {
            done = sim.step();
            ticks++;
            for (int i = 0; i < n; i++) {
                sim.pose(i, q, t);
                int o = i * PoseFeed.STRIDE;
                pose[o] = q.x; pose[o + 1] = q.y; pose[o + 2] = q.z; pose[o + 3] = q.w;
                pose[o + 4] = t.x; pose[o + 5] = t.y; pose[o + 6] = t.z;
            }
            all += everyone.select(pose, done, selection);
            for (int s = 0; s < SETTINGS.length; s++)
                for (int v = 0; v < VIEWERS.length; v++) {
                    if (sim.broken() && ticks % OCCLUSION[s].everyTicks() == 0)
                        sim.visibility(OCCLUSION[s], EYES[v][0], EYES[v][1], EYES[v][2], LOOKS[v][0], LOOKS[v][1], LOOKS[v][2], feeds[s][v].visible());
                    sent[s][v] += feeds[s][v].select(pose, done, selection);
                }
        }

        // On the last frame everything is sent, so every viewer ends up with the true final poses
        boolean flushed = true;
        for (var row : feeds)
            for (var feed : row)
                for (int i = 0; i < n * PoseFeed.STRIDE; i++)
                    if (i % PoseFeed.STRIDE < PoseFeed.VALUES && feed.sent()[i] != pose[i])
                        flushed = false;
        return new Counts(sent, all, n, ticks, flushed);
    }

    private static String share(long part, long whole) {
        return String.format("%d (%.0f%%)", part, 100.0 * part / whole);
    }

    @Test
    void aWallAndTheViewConeCutWhatEachViewerIsSent() {
        for (int attempt = 0; attempt < 3; attempt++) {
            var c = run(); // a different blast each time: the properties below hold for all of them
            var sent = c.sent();

            System.out.printf("%d pieces, %d ticks; pose updates sent over the whole blast (no culling: %d to each viewer):%n", c.pieces(), c.ticks(), c.everyone());
            for (int s = 0; s < SETTINGS.length; s++)
                System.out.printf("  %-18s %s %s | %s %s | %s %s%n", SETTINGS[s],
                        VIEWERS[0], share(sent[s][0], c.everyone()), VIEWERS[1], share(sent[s][1], c.everyone()), VIEWERS[2], share(sent[s][2], c.everyone()));

            assertTrue(c.everyone() > 1000, "the blast sent something");
            assertTrue(c.flushedAtTheEnd(), "every viewer ends with the true final poses");
            for (var row : sent)
                for (long count : row)
                    assertTrue(count <= c.everyone(), "culling never sends more than everyone gets");

            // The wall alone hides most of the blast from the viewer behind it
            assertTrue(sent[0][2] < 0.5 * c.everyone(), "behind the wall: " + share(sent[0][2], c.everyone()));
            // Pieces hiding each other cuts what the viewer in the open is sent
            assertTrue(sent[1][0] < 0.95 * sent[0][0], "pieces occluding: " + share(sent[1][0], c.everyone()) + " vs " + share(sent[0][0], c.everyone()));
            // Looking away: the cone leaves almost nothing, where without it the blast behind them is still sent
            assertTrue(sent[2][1] < 0.5 * sent[1][1], "the cone cuts what a viewer looking away is sent");
            assertTrue(sent[2][1] < 0.35 * c.everyone(), "looking away: " + share(sent[2][1], c.everyone()));
            // The cone can only remove updates, and the viewer who looks at the blast keeps most of them
            assertTrue(sent[2][0] <= sent[1][0] && sent[2][2] <= sent[1][2]);
            assertTrue(sent[2][0] > 0.4 * sent[1][0], "watching with the cone: " + share(sent[2][0], c.everyone()));
        }
    }

}
