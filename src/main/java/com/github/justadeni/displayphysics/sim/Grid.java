package com.github.justadeni.displayphysics.sim;

import com.github.justadeni.displayphysics.TerrainSource;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.Arrays;

/**
 * The terrain around the moving pieces as one dense {@code byte} array, x fastest. A voxel lookup is an
 * array index (no hashing, no pointer chasing), and a SIMD kernel can gather from it.
 *
 * <p>Each byte holds the voxel's type in its low two bits and, for a solid voxel, one bit per face that
 * opens onto a non-solid neighbour. A contact probe therefore needs one fetch to know both that it is inside
 * a solid voxel and which ways out exist, instead of seven.
 *
 * <p>Coordinates are relative to the simulation's origin block. The grid covers a box around the pieces and
 * is rebuilt, with some slack, when they leave it; it is refilled when the host's world copy changes.
 * Pieces that came to rest are written in as solid voxels, so they keep stopping what lands on them
 * without costing anything per tick.
 */
public final class Grid {

    public static final byte AIR = TerrainSource.AIR, SOLID = TerrainSource.SOLID, LIQUID = TerrainSource.LIQUID;
    public static final int TYPE_MASK = 3;
    /** The face bits of a solid voxel: set when the neighbour on that side is not solid. */
    public static final int OPEN_PX = 4, OPEN_NX = 8, OPEN_PY = 16, OPEN_NY = 32, OPEN_PZ = 64, OPEN_NZ = 128;

    private final TerrainSource source;
    private final int ox, oy, oz;
    private final int minY, maxY;
    private final IntArrayList resting = new IntArrayList();
    private boolean dirty = true;

    public byte[] cells = new byte[0];
    /**
     * The same voxels as ints (unsigned bytes) for SIMD gathers, which have no byte form; kept in step with
     * {@link #cells}. Do not write to it.
     */
    public int[] wide = new int[0];
    /**
     * Per voxel, which of the 27 voxels around it (itself included) are solid with at least one open face: bit
     * {@code (dx + 1) + 3 * (dy + 1) + 9 * (dz + 1)} for the voxel at offset (dx, dy, dz). Only such voxels can give a
     * corner a contact, so a SIMD probe reads one entry per piece instead of one per corner, and zero means "nothing
     * within one voxel". It is kept conservative: filling sets it, {@link #markResting} only ever adds bits.
     */
    public int[] nbr = new int[0];
    /** The lowest covered voxel and the extent of the box, in voxels. */
    public int x0, y0, z0, sx, sy, sz;

    public Grid(TerrainSource source, int ox, int oy, int oz) {
        this.source = source;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.minY = source.minY();
        this.maxY = source.maxY();
    }

    /** The host's copy of the world changed: refill before the next use. */
    public void invalidate() {
        dirty = true;
    }

    /** Makes sure voxels {@code [minX, maxX] x [minY, maxY] x [minZ, maxZ]} are covered and up to date. */
    public void ensure(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean covered = sx > 0 && minX >= x0 && minY >= y0 && minZ >= z0
                && maxX < x0 + sx && maxY < y0 + sy && maxZ < z0 + sz;
        if (covered && !dirty)
            return;
        if (covered) {
            fill();
            return;
        }

        // Grow with slack, so a cloud that keeps spreading doesn't rebuild every tick
        int padX = 4 + (maxX - minX) / 4, padY = 4 + (maxY - minY) / 4, padZ = 4 + (maxZ - minZ) / 4;
        x0 = minX - padX;
        y0 = minY - padY;
        z0 = minZ - padZ;
        sx = maxX - minX + 1 + 2 * padX;
        sy = maxY - minY + 1 + 2 * padY;
        sz = maxZ - minZ + 1 + 2 * padZ;
        long size = (long) sx * sy * sz;
        if (size > Integer.MAX_VALUE - 8)
            throw new IllegalStateException("The pieces spread over " + size + " voxels");
        if (cells.length != size)
            cells = new byte[(int) size];
        fill();
    }

    private void fill() {
        int index = 0;
        for (int y = y0; y < y0 + sy; y++) {
            int worldY = y + oy;
            if (worldY < minY || worldY >= maxY) {
                // Below the world is solid, above it is open: the whole layer at once
                Arrays.fill(cells, index, index + sx * sz, worldY < minY ? SOLID : AIR);
                index += sx * sz;
                continue;
            }
            for (int z = z0; z < z0 + sz; z++)
                for (int x = x0; x < x0 + sx; x++)
                    cells[index++] = source.classify(x + ox, worldY, z + oz);
        }
        addOpenFaces();
        for (int k = 0; k < resting.size(); k += 3)
            setSolid(resting.getInt(k), resting.getInt(k + 1), resting.getInt(k + 2));
        if (wide.length != cells.length) {
            wide = new int[cells.length];
            nbr = new int[cells.length];
        } else {
            Arrays.fill(nbr, 0);
        }
        boolean anyLiquid = false;
        for (int k = 0; k < cells.length; k++) {
            wide[k] = cells[k] & 0xFF;
            anyLiquid |= (cells[k] & TYPE_MASK) == LIQUID;
        }
        liquid = anyLiquid;
        markExposed();
        dirty = false;
    }

    /** Whether any covered voxel is liquid (so the SIMD forces kernel can skip the buoyancy lookup when not). */
    public boolean liquid;

    /** The int view for gathers (see {@link #wide}). */
    public int[] wide() {
        return wide;
    }

    private void markExposed() {
        int strideZ = sx, strideY = sx * sz;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++) {
                int row = y * strideY + z * strideZ;
                for (int x = 0; x < sx; x++)
                    if ((cells[row + x] & 0xFC) != 0)
                        markExposed(x, y, z);
            }
    }

    /** Tells the 27 voxels around the exposed solid voxel (grid-relative x, y, z) about it. */
    private void markExposed(int x, int y, int z) {
        for (int dy = -1; dy <= 1; dy++) {
            if (y + dy < 0 || y + dy >= sy)
                continue;
            for (int dz = -1; dz <= 1; dz++) {
                if (z + dz < 0 || z + dz >= sz)
                    continue;
                int row = (y + dy) * sx * sz + (z + dz) * sx;
                for (int dx = -1; dx <= 1; dx++)
                    if (x + dx >= 0 && x + dx < sx)
                        // From the neighbour's point of view the solid voxel is at offset (-dx, -dy, -dz)
                        nbr[row + x + dx] |= 1 << ((1 - dx) + 3 * (1 - dy) + 9 * (1 - dz));
            }
        }
    }

    /** Fills in the face bits of every solid voxel. Outside the box counts as open. */
    private void addOpenFaces() {
        int strideZ = sx, strideY = sx * sz;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++) {
                int row = y * strideY + z * strideZ;
                for (int x = 0; x < sx; x++) {
                    int i = row + x;
                    if (cells[i] != SOLID)
                        continue;
                    int open = 0;
                    if (x == sx - 1 || (cells[i + 1] & TYPE_MASK) != SOLID) open |= OPEN_PX;
                    if (x == 0 || (cells[i - 1] & TYPE_MASK) != SOLID) open |= OPEN_NX;
                    if (y == sy - 1 || (cells[i + strideY] & TYPE_MASK) != SOLID) open |= OPEN_PY;
                    if (y == 0 || (cells[i - strideY] & TYPE_MASK) != SOLID) open |= OPEN_NY;
                    if (z == sz - 1 || (cells[i + strideZ] & TYPE_MASK) != SOLID) open |= OPEN_PZ;
                    if (z == 0 || (cells[i - strideZ] & TYPE_MASK) != SOLID) open |= OPEN_NZ;
                    cells[i] = (byte) (SOLID | open);
                }
            }
    }

    public int index(int x, int y, int z) {
        return ((y - y0) * sz + (z - z0)) * sx + (x - x0);
    }

    /** The voxel's byte, unsigned: type in the low two bits, face bits above. The voxel must be inside the covered box. */
    public int cell(int x, int y, int z) {
        return cells[index(x, y, z)] & 0xFF;
    }

    /** Writes a piece that came to rest into the terrain, for good (it survives refills). */
    public void markResting(int x, int y, int z) {
        resting.add(x);
        resting.add(y);
        resting.add(z);
        setSolid(x, y, z);
    }

    /** Makes a voxel solid and closes the matching face of each solid neighbour. */
    private void setSolid(int x, int y, int z) {
        if (x < x0 || x >= x0 + sx || y < y0 || y >= y0 + sy || z < z0 || z >= z0 + sz)
            return;
        int i = index(x, y, z);
        int strideZ = sx, strideY = sx * sz;
        int open = 0;
        if (x == x0 + sx - 1 || (cells[i + 1] & TYPE_MASK) != SOLID) open |= OPEN_PX; else cells[i + 1] &= (byte) ~OPEN_NX;
        if (x == x0 || (cells[i - 1] & TYPE_MASK) != SOLID) open |= OPEN_NX; else cells[i - 1] &= (byte) ~OPEN_PX;
        if (y == y0 + sy - 1 || (cells[i + strideY] & TYPE_MASK) != SOLID) open |= OPEN_PY; else cells[i + strideY] &= (byte) ~OPEN_NY;
        if (y == y0 || (cells[i - strideY] & TYPE_MASK) != SOLID) open |= OPEN_NY; else cells[i - strideY] &= (byte) ~OPEN_PY;
        if (z == z0 + sz - 1 || (cells[i + strideZ] & TYPE_MASK) != SOLID) open |= OPEN_PZ; else cells[i + strideZ] &= (byte) ~OPEN_NZ;
        if (z == z0 || (cells[i - strideZ] & TYPE_MASK) != SOLID) open |= OPEN_NZ; else cells[i - strideZ] &= (byte) ~OPEN_PZ;
        cells[i] = (byte) (SOLID | open);
        if (wide.length == cells.length) {
            wide[i] = cells[i] & 0xFF;
            for (int j : new int[]{i + 1, i - 1, i + strideY, i - strideY, i + strideZ, i - strideZ})
                if (j >= 0 && j < cells.length)
                    wide[j] = cells[j] & 0xFF;
            if ((cells[i] & 0xFC) != 0)
                markExposed(x - x0, y - y0, z - z0);
        }
    }

    public static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

}
