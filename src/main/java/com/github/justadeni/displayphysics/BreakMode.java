package com.github.justadeni.displayphysics;

/** What happens to the assembly when it hits something. */
public enum BreakMode {

    /** It stays one solid unit until it comes to rest. */
    RIGID,

    /**
     * It stays one unit until it hits something solid faster than
     * {@link Settings#breakSpeed}, or has tumbled for {@link Settings#maxRigidSeconds}, or comes to rest
     * (whichever is first). Then every block becomes its own body and scatters.
     */
    BREAK_ON_IMPACT,

    /**
     * There is no solid stage at all: the blocks are separate bodies from the first tick and
     * simply collapse, falling, tumbling and piling up. A {@link Hinge} is ignored and
     * {@link Settings#scatter} only adds a small nudge (a fifth of its usual strength).
     */
    FALL_APART

}
