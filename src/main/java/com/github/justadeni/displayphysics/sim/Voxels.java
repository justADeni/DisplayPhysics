package com.github.justadeni.displayphysics.sim;

import com.github.justadeni.displayphysics.TerrainSource;
import com.github.justadeni.displayphysics.XYZ;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import org.joml.Vector3f;

/**
 * Cached voxel view of the world around the assembly.
 *
 * <p>All coordinates passed in are relative to an integer block origin, so the
 * simulation can work in small floats while voxel lookups stay exact.
 * Not thread-safe: only the simulation thread uses it.
 */
final class Voxels {

    static final byte AIR = TerrainSource.AIR, SOLID = TerrainSource.SOLID, LIQUID = TerrainSource.LIQUID;
    private static final byte UNKNOWN = -1;

    private final TerrainSource source;
    private final int ox, oy, oz;
    private final int minY, maxY;
    private final Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();

    Voxels(TerrainSource source, int ox, int oy, int oz) {
        this.source = source;
        this.minY = source.minY();
        this.maxY = source.maxY();
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        cache.defaultReturnValue(UNKNOWN);
    }

    /** Drops cached voxels so blocks placed or broken mid-fall are picked up. */
    void invalidate() {
        cache.clear();
        lastKey = NO_KEY;
    }

    // Neighbouring probes usually land in the same voxel, so remember the last one
    // and skip the hash map for repeats.
    private static final long NO_KEY = Long.MIN_VALUE;
    private long lastKey = NO_KEY;
    private byte lastType;

    /** Voxel type at a cell given relative to the origin. */
    byte type(int rx, int ry, int rz) {
        int x = rx + ox, y = ry + oy, z = rz + oz;
        // Outside the world's height nothing needs looking up
        if (y < minY)
            return SOLID;
        if (y >= maxY)
            return AIR;

        long key = XYZ.pack(x, y, z);
        if (key == lastKey)
            return lastType;

        byte type = cache.get(key);
        if (type == UNKNOWN) {
            type = source.classify(x, y, z);
            cache.put(key, type);
        }
        lastKey = key;
        lastType = type;
        return type;
    }

    boolean solid(int rx, int ry, int rz) {
        return type(rx, ry, rz) == SOLID;
    }

    boolean liquid(float px, float py, float pz) {
        return type(floor(px), floor(py), floor(pz)) == LIQUID;
    }

    /**
     * A point can't have entered a voxel through a face it is moving out of faster
     * than this (blocks per second), so such faces are never used to push it back.
     */
    private static final float MAX_EXIT_SPEED = 0.5f;

    /**
     * If the point is inside a solid voxel, writes the shortest way back out into
     * {@code normal} and returns the depth. Returns a negative value when the point
     * is in open space, or when there is no believable way out.
     *
     * <p>Only faces whose neighbour is open and that the point could have come in
     * through (it isn't already moving out through them) count. Without the second
     * rule, a protrusion rising into a thick slab (only the top face open) would be
     * pushed up through it block after block and launch the whole assembly.
     *
     * @param velX velocity of the point, used to tell the entry face from the exit face
     */
    float penetration(float px, float py, float pz, float velX, float velY, float velZ, Vector3f normal) {
        int vx = floor(px), vy = floor(py), vz = floor(pz);
        if (!solid(vx, vy, vz))
            return -1f;

        float fx = px - vx, fy = py - vy, fz = pz - vz;
        float best = Float.MAX_VALUE;

        // Slight preference for pushing up, so things resting on flat ground
        // never get shoved sideways off a block edge.
        if (velY < MAX_EXIT_SPEED && !solid(vx, vy + 1, vz) && (1f - fy) - 0.05f < best) { best = (1f - fy) - 0.05f; normal.set(0, 1, 0); }
        if (velX < MAX_EXIT_SPEED && !solid(vx + 1, vy, vz) && (1f - fx) < best) { best = 1f - fx; normal.set(1, 0, 0); }
        if (-velX < MAX_EXIT_SPEED && !solid(vx - 1, vy, vz) && fx < best) { best = fx; normal.set(-1, 0, 0); }
        if (velZ < MAX_EXIT_SPEED && !solid(vx, vy, vz + 1) && (1f - fz) < best) { best = 1f - fz; normal.set(0, 0, 1); }
        if (-velZ < MAX_EXIT_SPEED && !solid(vx, vy, vz - 1) && fz < best) { best = fz; normal.set(0, 0, -1); }
        if (-velY < MAX_EXIT_SPEED && !solid(vx, vy - 1, vz) && fy < best) { best = fy; normal.set(0, -1, 0); }

        if (best == Float.MAX_VALUE) {
            // Buried, or only exits it's already heading through: leave this point
            // alone rather than invent a push. The probes around it will catch the body.
            return -1f;
        }
        if (normal.y == 1)
            best += 0.05f;
        return Math.max(best, 0f);
    }

    static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

}
