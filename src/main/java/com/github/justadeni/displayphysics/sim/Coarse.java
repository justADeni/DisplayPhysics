package com.github.justadeni.displayphysics.sim;

import java.util.Arrays;

/**
 * A coarse summary of a {@link Grid} that lets a line of sight skip empty space, in the spirit of the conservative
 * depth tiles of hierarchical occlusion culling. For footprints of 16x16 and 64x64 voxels it keeps the highest voxel
 * that can block. Everything above that height is clear, so a line well above the ground crosses a whole footprint
 * in one jump. Finer levels cost more to consult than the samples they save, and measured slower on rough terrain.
 * Coordinates are relative to the grid's origin.
 */
public final class Coarse {
    private static final int[] LEVEL_SHIFT = {4, 6};
    private static final float MARGIN = 1e-3f;

    private final int[][] tops = {new int[0], new int[0]};
    private final int[] width = new int[2];

    public void reset(int sx, int sy, int sz) {
        for (int level = 0; level < 2; level++) {
            int shift = LEVEL_SHIFT[level];
            width[level] = (sx + (1 << shift) - 1) >> shift;
            int size = width[level] * ((sz + (1 << shift) - 1) >> shift);
            if (tops[level].length != size)
                tops[level] = new int[size];
            Arrays.fill(tops[level], -1);
        }
    }

    public void copyFrom(Coarse other) {
        for (int level = 0; level < 2; level++) {
            width[level] = other.width[level];
            if (tops[level].length != other.tops[level].length)
                tops[level] = new int[other.tops[level].length];
            System.arraycopy(other.tops[level], 0, tops[level], 0, tops[level].length);
        }
    }

    public boolean isEmpty() {
        return tops[0].length == 0;
    }

    /** Records that the voxel (x, y, z) can block. */
    public void mark(int x, int y, int z) {
        for (int level = 0; level < 2; level++) {
            int shift = LEVEL_SHIFT[level];
            int column = (z >> shift) * width[level] + (x >> shift);
            if (tops[level][column] < y)
                tops[level][column] = y;
        }
    }

    /**
     * Where a line can continue sampling after the voxel (x, y, z): the index of the first sample past the largest
     * empty stretch around it, or -1 if the voxel is below the tallest blocker of both footprints and has to be
     * looked at. The line is {@code origin + r * f} for {@code f} in [0, 1], sampled at {@code (s + 0.5) / steps};
     * {@code ix, iy, iz} are the reciprocals of {@code rx, ry, rz} (any value where the component is 0).
     */
    public int skipTo(int x, int y, int z, float ox, float oy, float oz, float rx, float ry, float rz,
                      float ix, float iy, float iz, float length, int steps) {
        float exit = Float.NEGATIVE_INFINITY;
        boolean any = false;
        for (int level = 1; level >= 0; level--) {
            int shift = LEVEL_SHIFT[level];
            int top = tops[level][(z >> shift) * width[level] + (x >> shift)];
            if (y <= top)
                continue;
            int size = 1 << shift;
            float down = ry < 0f ? (top + 1 - oy) * iy : Float.POSITIVE_INFINITY;
            float leaves = Math.min(Math.min(
                    leave(x >> shift << shift, size, ox, rx, ix),
                    leave(z >> shift << shift, size, oz, rz, iz)), down);
            if (leaves > exit)
                exit = leaves;
            any = true;
        }
        if (!any)
            return -1;
        float sample = Math.min((exit - MARGIN / length) * steps - 0.5f, (float) steps);
        return (int) Math.ceil(sample);
    }

    private static float leave(int low, int size, float origin, float direction, float inverse) {
        if (direction > 0f)
            return (low + size - origin) * inverse;
        if (direction < 0f)
            return (low - origin) * inverse;
        return Float.POSITIVE_INFINITY;
    }
}
