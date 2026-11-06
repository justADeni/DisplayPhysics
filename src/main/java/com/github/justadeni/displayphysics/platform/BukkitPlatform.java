package com.github.justadeni.displayphysics.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Plain Bukkit/Spigot. Packets can be sent from any thread, so the main thread is only used to change
 * blocks; a player's location is read where the caller is.
 */
public final class BukkitPlatform implements Platform {

    private final Plugin plugin;

    public BukkitPlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAt(World world, int blockX, int blockZ, Runnable task) {
        if (Bukkit.isPrimaryThread())
            task.run();
        else
            Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public CompletableFuture<Location> locate(Player player) {
        try {
            return CompletableFuture.completedFuture(player.getLocation());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

}
