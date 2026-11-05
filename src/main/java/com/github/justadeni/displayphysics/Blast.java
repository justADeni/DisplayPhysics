package com.github.justadeni.displayphysics;

import org.bukkit.Location;

import java.util.Objects;

/**
 * Describes an explosion for {@link Mode.Explode}: where it is centred, how far it reaches, which
 * way the pieces fly and how hard.
 *
 * <pre>{@code
 * Blast.everything()                                                       // all pieces, away from their centre
 * Blast.at(x, y, z, 4f)                                                    // only pieces within 4 blocks of a point
 * Blast.at(x, y, z, 3f).aim(Blast.Aim.cone(0, 0, 1, 25f)).force(18f)      // a shot into a wall: forwards, 25 degrees of spread
 * }</pre>
 *
 * @param epicenter where the blast is centred
 * @param radius    pieces further than this from the epicenter are not thrown ({@link Float#POSITIVE_INFINITY} reaches all)
 * @param aim       the direction pieces are thrown in
 * @param force     launch speed in blocks per second for a piece at the epicenter; it fades towards the edge of the radius
 * @param lift      extra upward speed as a fraction of the launch speed
 * @param remainder what happens to the pieces the blast did not reach
 */
public record Blast(Epicenter epicenter, float radius, Aim aim, float force, float lift, Remainder remainder) {

    public Blast {
        Objects.requireNonNull(epicenter);
        Objects.requireNonNull(aim);
        Objects.requireNonNull(remainder);
        if (!(radius > 0f))
            throw new IllegalArgumentException("radius must be positive: " + radius);
    }

    /** Where the blast is centred. */
    public sealed interface Epicenter {

        /** The centre of mass of the whole assembly. */
        Centre CENTRE = new Centre();

        record Centre() implements Epicenter {}

        record Point(double x, double y, double z) implements Epicenter {}
    }

    /** The direction pieces are thrown in. */
    public sealed interface Aim {

        /** Straight away from the epicenter. */
        Outward OUTWARD = new Outward();

        /**
         * Along one direction with some spread, as if hit by a projectile.
         *
         * @param spread half-angle of the cone the pieces are scattered in, in radians
         */
        static Aim cone(float x, float y, float z, float spread) {
            return new Along(x, y, z, spread);
        }

        record Outward() implements Aim {}

        /** The direction is normalised on construction. */
        record Along(float x, float y, float z, float spread) implements Aim {
            public Along {
                float length = (float) Math.sqrt(x * x + y * y + z * z);
                if (!(length > 1e-6f))
                    throw new IllegalArgumentException("direction must not be zero");
                x /= length;
                y /= length;
                z /= length;
                spread = Math.clamp(spread, 0f, (float) Math.PI);
            }
        }
    }

    /** What happens to pieces outside the radius. */
    public enum Remainder {
        /** They stay where they are, still solid enough to stop what is thrown at them, until something hits them hard. */
        STAY,
        /** They are released without a push and drop. */
        FALL
    }

    /** Throws every piece away from the assembly's centre. */
    public static Blast everything() {
        return new Blast(Epicenter.CENTRE, Float.POSITIVE_INFINITY, Aim.OUTWARD, 12f, 0.3f, Remainder.STAY);
    }

    /** Throws the pieces within {@code radius} blocks of a point. */
    public static Blast at(double x, double y, double z, float radius) {
        return new Blast(new Epicenter.Point(x, y, z), radius, Aim.OUTWARD, 12f, 0.3f, Remainder.STAY);
    }

    public static Blast at(Location epicenter, float radius) {
        return at(epicenter.getX(), epicenter.getY(), epicenter.getZ(), radius);
    }

    public Blast radius(float radius) {
        return new Blast(epicenter, radius, aim, force, lift, remainder);
    }

    public Blast aim(Aim aim) {
        return new Blast(epicenter, radius, aim, force, lift, remainder);
    }

    public Blast force(float force) {
        return new Blast(epicenter, radius, aim, force, lift, remainder);
    }

    public Blast lift(float lift) {
        return new Blast(epicenter, radius, aim, force, lift, remainder);
    }

    public Blast remainder(Remainder remainder) {
        return new Blast(epicenter, radius, aim, force, lift, remainder);
    }

}
