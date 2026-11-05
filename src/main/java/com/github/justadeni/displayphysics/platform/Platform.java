package com.github.justadeni.displayphysics.platform;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * The few things that differ between a single-threaded server (Bukkit, Spigot) and a regionized
 * one (Paper's schedulers, Folia). The library's own threads never read an entity directly: they ask here.
 */
public interface Platform {

    /**
     * Where the player is, read on the thread that owns them. Completes with {@code null} if the
     * player is gone.
     */
    CompletableFuture<Location> locate(Player player);

    /** The implementation for this server. The regionized one is only loaded where its API exists. */
    static Platform detect(Plugin plugin) {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            return (Platform) Class.forName("com.github.justadeni.displayphysics.platform.RegionizedPlatform")
                    .getConstructor(Plugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError e) {
            return new BukkitPlatform();
        }
    }

    /** Plain Bukkit/Spigot: one main thread, entities are readable right away. */
    final class BukkitPlatform implements Platform {
        @Override
        public CompletableFuture<Location> locate(Player player) {
            try {
                return CompletableFuture.completedFuture(player.getLocation());
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(null);
            }
        }
    }

}
