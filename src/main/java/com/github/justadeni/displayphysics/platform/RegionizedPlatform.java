package com.github.justadeni.displayphysics.platform;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/** Paper and Folia: a player may only be read on the thread that owns them. */
public final class RegionizedPlatform implements Platform {

    private final Plugin plugin;

    public RegionizedPlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAt(World world, int blockX, int blockZ, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, world, blockX >> 4, blockZ >> 4, scheduled -> task.run());
    }

    @Override
    public CompletableFuture<Location> locate(Player player) {
        // Already on the right thread: no hop, the answer is there at once
        if (Bukkit.isOwnedByCurrentRegion(player))
            return CompletableFuture.completedFuture(player.getLocation());

        CompletableFuture<Location> future = new CompletableFuture<>();
        var task = player.getScheduler().run(plugin, scheduled -> future.complete(player.getLocation()), () -> future.complete(null));
        if (task == null)
            future.complete(null);
        return future;
    }

}
