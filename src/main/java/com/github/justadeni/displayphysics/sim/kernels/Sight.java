package com.github.justadeni.displayphysics.sim;

/**
 * One viewer's eye, relative to the simulation's origin, and what they can see.
 *
 * @param lx,ly,lz     the unit vector the viewer looks along
 * @param cone         whether pieces outside the view cone are hidden
 * @param cosHalf      cosine of the cone's half angle
 * @param sinHalf      sine of the cone's half angle
 * @param maxDistance  pieces further away are hidden
 * @param spacing      distance between samples along a line of sight
 */
public record Sight(float ex, float ey, float ez, float lx, float ly, float lz,
                    boolean cone, float cosHalf, float sinHalf, float maxDistance, float spacing) {

    /** Pieces closer than this to the eye are always visible: their direction means little and they fill the view. */
    public static final float NEAR = 2f;
    /** Radius of the sphere around a piece's centre that holds the whole piece. */
    public static final float PIECE_RADIUS = 0.9f;

    /**
     * Whether a piece at offset {@code (dx, dy, dz)} from the eye ({@code distance} away, at least {@link #NEAR})
     * could be inside the view cone, allowing for the angle the piece itself spans.
     */
    public boolean inCone(float dx, float dy, float dz, float distance) {
        float inverse = 1f / distance;
        float dot = (dx * lx + dy * ly + dz * lz) * inverse;
        float spans = Math.min(1f, PIECE_RADIUS * inverse);       // sine of the angle the piece covers
        float coversCos = (float) Math.sqrt(1f - spans * spans);
        // cos(half + spans) = cos(half) cos(spans) - sin(half) sin(spans)
        return dot >= cosHalf * coversCos - sinHalf * spans;
    }

}
