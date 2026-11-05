package com.github.justadeni.displayphysics;

/** Tuning for one simulation. Fluent; every value has a sensible default. */
public final class Settings {

    public float gravity = 9.81f;
    /** Coulomb friction against the terrain. */
    public float friction = 0.6f;
    /** Bounciness of hard pieces (0 to 1). */
    public float bounciness = 0.25f;
    /** Bounciness of soft pieces (0 to 1); 0 makes them cushion. */
    public float softBounciness = 0f;
    /** Whether soft pieces (leaves) collide with the terrain at all. */
    public boolean softCollides = true;
    /** Hard cap on the whole simulation, in seconds. */
    public float maxDuration = 30f;
    public BreakMode breakMode = BreakMode.RIGID;
    /** {@link BreakMode#BREAK_ON_IMPACT}: speed (blocks/s) of a hit that breaks the assembly. */
    public float breakSpeed = 6f;
    /** {@link BreakMode#BREAK_ON_IMPACT}: ticks between the trigger and the break. */
    public int breakDelay = 0;
    /** {@link BreakMode#BREAK_ON_IMPACT}: seconds of tumbling after which it breaks even without a hard hit. */
    public float maxRigidSeconds = 2f;
    /** How violently the pieces fly apart when it breaks. */
    public float scatter = 1f;
    /** Send a frame every this many ticks; the client interpolates between. Higher means fewer packets. */
    public int updateInterval = 1;
    /** {@link Listener#onImpact} fires when a hit is at least this fast (blocks/s). */
    public float impactReportSpeed = 2.5f;
    /** Seconds after the simulation ends before the displays are removed if nobody released them. */
    public float autoReleaseSeconds = 60f;

    public Settings gravity(float v) { gravity = v; return this; }
    public Settings friction(float v) { friction = v; return this; }
    public Settings bounciness(float v) { bounciness = v; return this; }
    public Settings softBounciness(float v) { softBounciness = v; return this; }
    public Settings softCollides(boolean v) { softCollides = v; return this; }
    public Settings maxDuration(float seconds) { maxDuration = seconds; return this; }
    public Settings breakMode(BreakMode v) { breakMode = v; return this; }
    public Settings breakSpeed(float v) { breakSpeed = v; return this; }
    public Settings breakDelay(int ticks) { breakDelay = ticks; return this; }
    public Settings maxRigidSeconds(float v) { maxRigidSeconds = v; return this; }
    public Settings scatter(float v) { scatter = v; return this; }
    public Settings updateInterval(int ticks) { updateInterval = Math.max(1, ticks); return this; }
    public Settings impactReportSpeed(float v) { impactReportSpeed = v; return this; }
    public Settings autoReleaseSeconds(float v) { autoReleaseSeconds = v; return this; }

}
