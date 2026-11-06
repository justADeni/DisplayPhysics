package com.github.justadeni.displayphysics;

/**
 * Stops sending a piece to players who can't see it. A piece gets no updates for a player while it is
 * too far away, outside the cone they could be looking through, or hidden from their eye by terrain or
 * other pieces; the moment it comes into view again it is brought up to date. It cuts the packets of a big
 * collapse roughly in proportion to how much of it is out of sight, and costs CPU instead, so it only pays
 * when the network is the bottleneck.
 *
 * <p><b>View cone.</b> The server knows where a player faces, not how wide their window is, so the cone is
 * built for the worst case: Minecraft's FOV setting is the <i>vertical</i> field of view (up to 110), and the
 * horizontal one grows with the window's aspect ratio (a 110 degree vertical FOV is about 137 degrees wide at
 * 16:9 and about 158 at 32:9; the screen's corners reach further still). The cone's half angle is the angle
 * to a screen corner plus a {@code margin}, which covers the player turning faster than the server hears about it.
 *
 * <p><b>Line of sight.</b> The library marches lines from the eye to a handful of points of each piece. The
 * terrain occludes, and so do the other moving pieces (each is treated as filling the block its centre is in;
 * pieces at rest are part of the terrain). Lines that leave the area the simulation tracks count as clear,
 * so the result errs on the side of sending too much, never too little.
 *
 * @param maxDistance   pieces further than this from a player (in blocks) are not sent to them
 * @param spacing       distance between the samples along each line (blocks); smaller misses fewer thin walls and costs more
 * @param everyTicks    how often visibility is recomputed
 * @param pieces        whether pieces hide the pieces behind them, or only the terrain does
 * @param verticalFov   the widest vertical field of view to support, in degrees (Minecraft allows up to 110);
 *                      0 or less turns the view cone off
 * @param aspectRatio   the widest window shape to support, width divided by height
 * @param margin        extra degrees around the cone, for head turns the server has not seen yet
 */
public record Occlusion(float maxDistance, float spacing, int everyTicks, boolean pieces,
                        float verticalFov, float aspectRatio, float margin) {
    public Occlusion {
        if (!(maxDistance > 0f))
            throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
        if (!(spacing > 0f))
            throw new IllegalArgumentException("spacing must be positive: " + spacing);
        if (everyTicks < 1)
            throw new IllegalArgumentException("everyTicks must be at least 1: " + everyTicks);
        if (!(aspectRatio > 0f))
            throw new IllegalArgumentException("aspectRatio must be positive: " + aspectRatio);
    }

    /** 96 blocks, samples every 0.6 blocks, recomputed every 4 ticks, pieces occlude, 110 degrees vertical at 16:9 plus 15 degrees. */
    public static Occlusion standard() {
        return new Occlusion(96f, 0.6f, 4, true, 110f, 16f / 9f, 15f);
    }

    public Occlusion withPieces(boolean pieces) {
        return new Occlusion(maxDistance, spacing, everyTicks, pieces, verticalFov, aspectRatio, margin);
    }

    /** The same, without the view cone: only distance and line of sight decide. */
    public Occlusion withoutViewCone() {
        return new Occlusion(maxDistance, spacing, everyTicks, pieces, 0f, aspectRatio, margin);
    }

    /** The view cone with another field of view. */
    public Occlusion withView(float verticalFov, float aspectRatio, float margin) {
        return new Occlusion(maxDistance, spacing, everyTicks, pieces, verticalFov, aspectRatio, margin);
    }

    /**
     * Half the angle of the cone around a player's look direction that contains everything they could see,
     * in radians; {@code Math.PI} (everything) when the cone is off or wide enough to cover it all.
     */
    public double coneHalfAngle() {
        if (!(verticalFov > 0f))
            return Math.PI;
        double vertical = Math.tan(Math.toRadians(verticalFov) / 2);
        double horizontal = vertical * aspectRatio;
        double toCorner = Math.atan(Math.sqrt(vertical * vertical + horizontal * horizontal));
        return Math.min(Math.PI, toCorner + Math.toRadians(Math.max(0f, margin)));
    }
}
