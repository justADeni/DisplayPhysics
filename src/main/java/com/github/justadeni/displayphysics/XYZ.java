package com.github.justadeni.displayphysics;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Packs a block position into one {@code long}, so positions can live in primitive collections
 * and travel without allocating.
 *
 * <p>Layout: 27 bits of x, 9 bits of y (offset by 64) and 27 bits of z. That covers
 * x and z from {@value #MIN_XZ} to {@value #MAX_XZ} and y from {@value #MIN_Y} to {@value #MAX_Y},
 * which holds every position in a world (coordinates outside those ranges wrap around).
 *
 * <p>The constants {@link #X}, {@link #Y} and {@link #Z} select a coordinate for {@link #get};
 * the per-axis {@link #x}, {@link #y} and {@link #z} do the same without a switch.
 */
public enum XYZ {
    X,
    Y,
    Z;

    public static final int MIN_XZ = -(1 << 26), MAX_XZ = (1 << 26) - 1;
    public static final int MIN_Y = -64, MAX_Y = MIN_Y + 0x1FF;

    private static final int XZ_MASK = 0x7FFFFFF;
    private static final int Y_MASK = 0x1FF;

    public static long pack(int x, int y, int z) {
        return ((long) (x & XZ_MASK) << 36)
                | ((long) (y - MIN_Y & Y_MASK) << 27)
                | (z & XZ_MASK);
    }

    public static long pack(Location location) {
        return pack(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static int get(XYZ coordinate, long packed) {
        return switch (coordinate) {
            case X -> x(packed);
            case Y -> y(packed);
            case Z -> z(packed);
        };
    }

    /** The x coordinate, sign-extended from its 27 bits (bit 63 of the key is never set, so shift it into place first). */
    public static int x(long packed) {
        return (int) (packed << 1 >> 37);
    }

    public static int y(long packed) {
        return (int) (packed >> 27 & Y_MASK) + MIN_Y;
    }

    public static int z(long packed) {
        return (int) (packed << 37 >> 37);
    }

    /** The position as a new {@link Location}, decoding all three coordinates in one go. */
    public static Location location(World world, long packed) {
        return new Location(world, x(packed), y(packed), z(packed));
    }

    public static Block block(World world, long packed) {
        return world.getBlockAt(x(packed), y(packed), z(packed));
    }

    /** The position moved by {@code dx, dy, dz}. */
    public static long offset(long packed, int dx, int dy, int dz) {
        return pack(x(packed) + dx, y(packed) + dy, z(packed) + dz);
    }
}
