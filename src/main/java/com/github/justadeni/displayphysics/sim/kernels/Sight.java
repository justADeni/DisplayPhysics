package com.github.justadeni.displayphysics.sim.kernels;

public record Sight(float ex, float ey, float ez, float lx, float ly, float lz,
                    boolean cone, float cosHalf, float sinHalf, float maxDistance, float spacing) {
    public static final float NEAR = 2f;
    public static final float PIECE_RADIUS = 0.9f;

    public boolean inCone(float dx, float dy, float dz, float distance) {
        float inverse = 1f / distance;
        float dot = (dx * lx + dy * ly + dz * lz) * inverse;
        float spans = Math.min(1f, PIECE_RADIUS * inverse);
        float coversCos = (float) Math.sqrt(1f - spans * spans);
        return dot >= cosHalf * coversCos - sinHalf * spans;
    }
}
