package com.github.justadeni.displayphysics.platform;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

public interface Platform {
    CompletableFuture<Location> locate(Player player);

    void runAt(World world, int blockX, int blockZ, Runnable task);

    static Platform detect(Plugin plugin) {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
            return (Platform) Class.forName("com.github.justadeni.displayphysics.platform.RegionizedPlatform")
                    .getConstructor(Plugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError e) {
            return new BukkitPlatform(plugin);
        }
    }
}
