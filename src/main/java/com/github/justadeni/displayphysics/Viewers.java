package com.github.justadeni.displayphysics;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Decides who is sent the displays. Asked when an {@link Assembly} is created and about once
 * a second after: players who show up get the displays, players who leave lose them.
 * Called from the library's own threads, so keep it thread-safe.
 */
@FunctionalInterface
public interface Viewers {

    Collection<? extends Player> find(World world, double x, double y, double z);

    /** Whether this viewer sees the glow of pieces that have one. */
    default boolean seesGlow(Player viewer) {
        return true;
    }

    /** Everyone in the world within {@code radius} blocks. */
    static Viewers within(double radius) {
        double radiusSquared = radius * radius;
        return (world, x, y, z) -> {
            Collection<Player> found = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    if (!player.getWorld().equals(world))
                        continue;
                    var at = player.getLocation();
                    double dx = at.getX() - x, dy = at.getY() - y, dz = at.getZ() - z;
                    if (dx * dx + dy * dy + dz * dz <= radiusSquared)
                        found.add(player);
                } catch (RuntimeException ignored) {
                    // a player mid-teleport or mid-disconnect: skip until the next check
                }
            }
            return found;
        };
    }

    /** Everyone in the world within the server's view distance. */
    static Viewers nearby() {
        return within(Math.max(64, Bukkit.getViewDistance() * 16));
    }

}
