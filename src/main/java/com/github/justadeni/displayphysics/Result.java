package com.github.justadeni.displayphysics;

import org.joml.Quaternionf;

/**
 * Where every piece came to rest. Indices match the list the {@link Assembly} was created
 * from. The displays stay on screen until {@link Assembly#release()}.
 */
public final class Result {

    private final double[] positions;
    private final float[] rotations;
    private final boolean broken;
    private final int ticks;
    private final Throwable error;

    public Result(double[] positions, float[] rotations, boolean broken, int ticks, Throwable error) {
        this.positions = positions;
        this.rotations = rotations;
        this.broken = broken;
        this.ticks = ticks;
        this.error = error;
    }

    public int count() {
        return positions.length / 3;
    }

    /** World x of the centre of the piece. */
    public double x(int piece) {
        return positions[piece * 3];
    }

    public double y(int piece) {
        return positions[piece * 3 + 1];
    }

    public double z(int piece) {
        return positions[piece * 3 + 2];
    }

    /** The piece's orientation: rotate a direction it started with by this to get where it points now. */
    public Quaternionf rotation(int piece) {
        int o = piece * 4;
        return new Quaternionf(rotations[o], rotations[o + 1], rotations[o + 2], rotations[o + 3]);
    }

    /** Whether the assembly broke into individual pieces. */
    public boolean broken() {
        return broken;
    }

    /** How many ticks the simulation ran. */
    public int ticks() {
        return ticks;
    }

    /** Non-null if the simulation failed halfway: the positions are then where it got to. */
    public Throwable error() {
        return error;
    }

}
