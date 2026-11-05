package com.github.justadeni.displayphysics;

import java.util.concurrent.CompletableFuture;

/**
 * What the simulation collides with. The host implements this on top of whatever
 * thread-safe view of the world it has (chunk snapshots, a cache, ...): the simulation
 * runs on its own thread and <b>never</b> reads the live world.
 *
 * <p>Implementations must be thread-safe and fast; results are cached by the library
 * until {@link #refresh()} completes.
 */
public interface TerrainSource {

    /** Nothing there: pieces pass through. */
    byte AIR = 0;
    /** Pieces collide with it. */
    byte SOLID = 1;
    /** Pieces float in it and are slowed down. */
    byte LIQUID = 2;

    /** The type of the block at these world coordinates: {@link #AIR}, {@link #SOLID} or {@link #LIQUID}. */
    byte classify(int x, int y, int z);

    /** Same as {@link #classify(int, int, int)}, for a position packed with {@link XYZ#pack}. */
    default byte classify(long packed) {
        return classify(XYZ.x(packed), XYZ.y(packed), XYZ.z(packed));
    }

    /** Lowest block y; below it everything is solid. */
    int minY();

    /** One past the highest block y; above it everything is air. */
    int maxY();

    /**
     * Re-reads the world so blocks placed or broken mid-fall are noticed. Called about once
     * a second; the cache is dropped when the returned future completes.
     *
     * @return a future for the refresh, or {@code null} if the terrain never changes
     */
    default CompletableFuture<?> refresh() {
        return null;
    }

    /** How old the data is in milliseconds. If it is more than a second, the library refreshes before starting. */
    default long ageMillis() {
        return 0L;
    }

}
