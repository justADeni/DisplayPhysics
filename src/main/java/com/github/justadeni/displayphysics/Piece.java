package com.github.justadeni.displayphysics;

import org.bukkit.block.data.BlockData;

/**
 * One block of an {@link Assembly}.
 *
 * @param x       block x of the display's corner, which is also where the block starts out
 * @param y       see {@code x}
 * @param z       see {@code x}
 * @param data    what the block looks like
 * @param hard    hard pieces (logs) collide with all their corners and hit hard; soft pieces (leaves)
 *                only collide with their centre when exposed, cushion impacts and catch the air
 * @param mass    relative mass; the whole assembly is one body with the pieces' combined mass
 * @param glowRgb glow colour as {@code 0xRRGGBB}, or {@code -1} for no glow
 */
public record Piece(int x, int y, int z, BlockData data, boolean hard, float mass, int glowRgb) {

    public Piece(int x, int y, int z, BlockData data, boolean hard, float mass) {
        this(x, y, z, data, hard, mass, -1);
    }

}
