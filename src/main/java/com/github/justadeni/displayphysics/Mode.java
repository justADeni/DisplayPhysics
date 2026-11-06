package com.github.justadeni.displayphysics;

/** How an assembly behaves once it is let go. */
public sealed interface Mode {

    /** One solid unit until it comes to rest. */
    Rigid RIGID = new Rigid();

    /** No solid stage: the pieces are separate bodies from the first tick and collapse. */
    FallApart FALL_APART = new FallApart();

    /** A solid unit that shatters when it hits something solid at {@code speed} blocks per second. */
    static BreakOnImpact breakOnImpact(float speed) {
        return new BreakOnImpact(speed, 0, 2f);
    }

    /** Pieces are thrown apart by a {@link Blast}. */
    static Explode explode(Blast blast) {
        return new Explode(blast);
    }

    record Rigid() implements Mode {}

    /**
     * One unit until it hits something solid faster than {@code speed}, or has tumbled for
     * {@code maxRigidSeconds}, or comes to rest (whichever is first). Then every piece becomes its
     * own body and scatters.
     *
     * @param speed           speed (blocks per second) of a hit that shatters it
     * @param delayTicks      ticks between the trigger and the shattering
     * @param maxRigidSeconds seconds of tumbling after which it shatters even without a hard hit
     */
    record BreakOnImpact(float speed, int delayTicks, float maxRigidSeconds) implements Mode {}

    record FallApart() implements Mode {}

    record Explode(Blast blast) implements Mode {
        public Explode {
            java.util.Objects.requireNonNull(blast);
        }
    }

}
