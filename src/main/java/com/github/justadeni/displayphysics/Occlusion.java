package com.github.justadeni.displayphysics;

/**
 * Stops sending a piece to players who can't see it. For each player the library marches a line from their
 * eye to a handful of points of the piece through the terrain; a piece whose every line is blocked, or that
 * is too far away, gets no updates for that player until it comes into view again (and is brought up to date
 * the moment it does). It cuts the packets of a big collapse roughly in proportion to how much of it is
 * hidden, and costs CPU instead, so it only pays when the network is the bottleneck.
 *
 * <p>The terrain occludes, and so do the other moving pieces (each is treated as filling the block its centre is
 * in; pieces at rest are part of the terrain). Lines that leave the area the simulation tracks are counted as
 * clear, so the result errs on the side of sending too much, never too little.
 *
 * @param maxDistance pieces further than this from a player (in blocks) are not sent to them
 * @param spacing     distance between the samples along each line (blocks); smaller misses fewer thin walls and costs more
 * @param everyTicks  how often visibility is recomputed
 * @param pieces      whether pieces hide the pieces behind them, or only the terrain does
 */
public record Occlusion(float maxDistance, float spacing, int everyTicks, boolean pieces) {

    public Occlusion {
        if (!(maxDistance > 0f))
            throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
        if (!(spacing > 0f))
            throw new IllegalArgumentException("spacing must be positive: " + spacing);
        if (everyTicks < 1)
            throw new IllegalArgumentException("everyTicks must be at least 1: " + everyTicks);
    }

    /** 96 blocks, samples every 0.6 blocks, recomputed every 4 ticks, pieces occlude each other. */
    public static Occlusion standard() {
        return new Occlusion(96f, 0.6f, 4, true);
    }

}
