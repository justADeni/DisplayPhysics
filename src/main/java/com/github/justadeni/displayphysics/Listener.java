package com.github.justadeni.displayphysics;

/**
 * Hooks for the host's own effects (sounds, particles, damage, ...). Every method runs on the
 * simulation thread, once per tick, and must not block. The {@link Tick} is only valid during
 * the call.
 */
public interface Listener {

    /** Called after every simulated tick. */
    default void onTick(Tick tick) {}

    /** The assembly hit something at least {@link Settings#impactReportSpeed} fast, at this world position. */
    default void onImpact(double x, double y, double z, float speed) {}

    /** The assembly just broke into individual pieces. */
    default void onBreak(Tick tick) {}

    /** Per-piece view of the current tick. */
    interface Tick {

        /** Ticks simulated so far, starting at 1. */
        int number();

        int count();

        /** World x of the piece's centre. */
        double x(int piece);

        double y(int piece);

        double z(int piece);

        /** The piece's speed in blocks per second. */
        float speed(int piece);

        /** Whether the piece is touching or sunk into solid terrain. */
        boolean touchingGround(int piece);

        /** Whether the piece has come to rest (only after breaking). */
        boolean asleep(int piece);

        /** Whether it has broken into individual pieces. */
        boolean broken();
    }

}
