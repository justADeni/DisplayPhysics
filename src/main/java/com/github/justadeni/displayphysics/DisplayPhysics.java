package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.net.Packets;
import com.github.justadeni.displayphysics.platform.Platform;
import org.bukkit.plugin.Plugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collection;
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
 * DisplayPhysics physics = DisplayPhysics.create(plugin);
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
    private final Platform platform;

    private DisplayPhysics(Plugin plugin) {
        this.logger = plugin.getLogger();
        this.platform = Platform.detect(plugin);
    }

    /** Whether this server can show the packet displays. When false, {@link #create} fails. */
    public static boolean isSupported() {
        return Packets.available();
    }

    /** Why {@link #isSupported()} is false, or {@code null}. */
    public static Throwable unsupportedReason() {
        return Packets.failure();
    }

    /** @param plugin the owner; problems go to its logger and Folia tasks run as it */
    public static DisplayPhysics create(Plugin plugin) {
        if (!Packets.available())
            throw new UnsupportedOperationException("DisplayPhysics needs a server 26.1 or newer", Packets.failure());
        return new DisplayPhysics(plugin);
    }

    /**
     * Spawns the pieces as displays for the viewers and groups them into one assembly. Nothing
     * moves until {@link Assembly#simulate}. On Folia call it from the thread that owns the pieces'
     * region.
     *
     * @param things {@link Piece}s, {@link Block}s or {@link Location}s, in any list or set. A block or location
     *               becomes a hard piece of mass 1 with the block's current {@code BlockData}; read them on the thread
     *               that owns them.
     */
    public Assembly assemble(World world, Collection<?> things, Viewers viewers) {
        if (things.isEmpty())
            throw new IllegalArgumentException("An assembly needs at least one piece");
        List<Piece> pieces = new ArrayList<>(things.size());
        for (Object thing : things) {
            switch (thing) {
                case Piece piece -> pieces.add(piece);
                case Block block -> pieces.add(new Piece(block.getX(), block.getY(), block.getZ(), block.getBlockData(), true, 1f));
                case Location location -> {
                    Block block = location.getBlock();
                    pieces.add(new Piece(block.getX(), block.getY(), block.getZ(), block.getBlockData(), true, 1f));
                }
                default -> throw new IllegalArgumentException("Expected Piece, Block or Location, got " + thing);
            }
        }
        return new Assembly(logger, platform, world, pieces, viewers);
    }

    static void later(Runnable task, long millis) {
        TIMER.schedule(task, millis, TimeUnit.MILLISECONDS);
    }

}
