package com.github.justadeni.displayphysics;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Decides who is sent the displays. Asked when an {@link Assembly} is created and about once
 * a second after: players who show up get the displays, players who leave lose them.
 */
@FunctionalInterface
public interface Viewers {
    /**
     * Whether this player (of the assembly's world) should see it.
     *
     * @param at the player's location, read on the thread that owns the player
     * @param x  the assembly's centre
     */
    boolean sees(Player player, Location at, double x, double y, double z);

    /** Whether this viewer sees the glow of pieces that have one. */
    default boolean seesGlow(Player viewer) {
        return true;
    }

    /** Everyone within {@code radius} blocks. */
    static Viewers within(double radius) {
        double radiusSquared = radius * radius;
        return (player, at, x, y, z) -> {
            double dx = at.getX() - x, dy = at.getY() - y, dz = at.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radiusSquared;
        };
    }

    /** Everyone within the server's view distance. */
    static Viewers nearby() {
        return within(Math.max(64, Bukkit.getViewDistance() * 16));
    }
}
