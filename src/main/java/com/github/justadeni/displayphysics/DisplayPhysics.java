package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.net.Packets;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Entry point. Turns a group of blocks into packet-only block displays that move as one solid
 * body, optionally breaking apart when they hit something too hard.
 *
 * <pre>{@code
 * DisplayPhysics physics = DisplayPhysics.create(plugin.getLogger());
 * Assembly assembly = physics.assemble(world, pieces, Viewers.nearby());
 * // ... remove the real blocks a tick later ...
 * assembly.simulate(hinge, settings, terrain, listener).thenAccept(result -> {
 *     // result.x(i), result.y(i), result.z(i), result.rotation(i): where piece i settled
 *     assembly.release();
 * });
 * }</pre>
 *
 * <p>Runs on Spigot, Paper and Folia 26.1 and newer, which all use Mojang's class names; only
 * the Bukkit API is used otherwise. Check {@link #isSupported()} on older servers.
 */
public final class DisplayPhysics {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DisplayPhysics-timer");
        thread.setDaemon(true);
        return thread;
    });

    private final Logger logger;

    private DisplayPhysics(Logger logger) {
        this.logger = logger;
    }

    /** Whether this server can show the packet displays. When false, {@link #create} fails. */
    public static boolean isSupported() {
        return Packets.available();
    }

    /** Why {@link #isSupported()} is false, or {@code null}. */
    public static Throwable unsupportedReason() {
        return Packets.failure();
    }

    /** @param logger where problems are reported */
    public static DisplayPhysics create(Logger logger) {
        if (!Packets.available())
            throw new UnsupportedOperationException("DisplayPhysics needs a server 26.1 or newer", Packets.failure());
        return new DisplayPhysics(logger);
    }

    /**
     * Spawns the pieces as displays for the viewers and groups them into one assembly. Nothing
     * moves until {@link Assembly#simulate}.
     */
    public Assembly assemble(World world, List<Piece> pieces, Viewers viewers) {
        if (pieces.isEmpty())
            throw new IllegalArgumentException("An assembly needs at least one piece");
        return new Assembly(logger, world, pieces, viewers);
    }

    static void later(Runnable task, long millis) {
        TIMER.schedule(task, millis, TimeUnit.MILLISECONDS);
    }

}
