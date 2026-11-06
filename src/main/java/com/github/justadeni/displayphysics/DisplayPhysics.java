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
     * region, and wait for {@link Assembly#spawned()} before removing the real blocks.
     */
    public Assembly assemble(World world, List<Piece> pieces, Viewers viewers) {
        if (pieces.isEmpty())
            throw new IllegalArgumentException("An assembly needs at least one piece");
        return new Assembly(logger, platform, world, pieces, viewers);
    }

    /**
     * Like {@link #assemble}, with one piece per block: its current {@code BlockData}, hard, mass 1. All blocks
     * must be in the same world. Read the blocks on the thread that owns them.
     */
    public Assembly assembleBlocks(Collection<? extends Block> blocks, Viewers viewers) {
        if (blocks.isEmpty())
            throw new IllegalArgumentException("An assembly needs at least one piece");
        List<Piece> pieces = new ArrayList<>(blocks.size());
        World world = null;
        for (Block block : blocks) {
            if (world == null)
                world = block.getWorld();
            pieces.add(new Piece(block.getX(), block.getY(), block.getZ(), block.getBlockData(), true, 1f));
        }
        return assemble(world, pieces, viewers);
    }

    /** Like {@link #assembleBlocks}, for the blocks at these locations (all in the same world). */
    public Assembly assembleLocations(Collection<? extends Location> locations, Viewers viewers) {
        List<Block> blocks = new ArrayList<>(locations.size());
        for (Location location : locations)
            blocks.add(location.getBlock());
        return assembleBlocks(blocks, viewers);
    }

    static void later(Runnable task, long millis) {
        TIMER.schedule(task, millis, TimeUnit.MILLISECONDS);
    }

}
