package com.github.justadeni.displayphysics.sim;
import com.github.justadeni.displayphysics.sim.kernels.Sight;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;

import com.github.justadeni.displayphysics.TerrainSource;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;

import static com.github.justadeni.displayphysics.sim.Tuning.*;

public final class FlatDebris {
    private static final float RESTITUTION = 0.1f;

    private static final float MARGIN = 0.5f;
    private static final float PAIR_RANGE_SQ = (1f + MARGIN) * (1f + MARGIN);
    private static final float REBUILD_MOVE_SQ = (MARGIN / 2f) * (MARGIN / 2f);
    private static final float CELL = 1f + MARGIN;
    private static final int REORDER_TICKS = 16, REORDER_MIN_PIECES = 2048;
    private int ticks;

    private static final int GRID_MARGIN = 4;

    private final Bodies b;
    private final Grid grid;
    private static final int SIMD_TERRAIN_MIN = 100;

    private final Kernels kernels;
    private final float gravity, friction, softBounciness;

    private static final int MAX_DENSE_CELLS = 1 << 22;
    public static int denseCellLimit = MAX_DENSE_CELLS;

    private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
    private int[] cellStart = new int[0];
    private int[] cellCursor = new int[0];
    private final int[] inCell;
    private final int[] sortedByCell;
    private final float[] sortedX, sortedY, sortedZ;
    private int[] occupied = new int[256];
    private final int[] cellNext;
    private final float[] builtX, builtY, builtZ;
    private int[] pairA = new int[1024], pairB = new int[1024];
    private int pairCount;

    public FlatDebris(Bodies bodies, TerrainSource source, int ox, int oy, int oz, float gravity, float friction, float softBounciness, Kernels kernels) {
        this.b = bodies;
        this.grid = new Grid(source, ox, oy, oz);
        this.kernels = kernels;
        this.gravity = gravity;
        this.friction = friction;
        this.softBounciness = softBounciness;
        this.cellNext = new int[bodies.capacity];
        this.inCell = new int[bodies.capacity];
        this.sortedByCell = new int[bodies.capacity];
        this.sortedX = new float[bodies.capacity];
        this.sortedY = new float[bodies.capacity];
        this.sortedZ = new float[bodies.capacity];
        this.builtX = new float[bodies.capacity];
        this.builtY = new float[bodies.capacity];
        this.builtZ = new float[bodies.capacity];
        cells.defaultReturnValue(-1);
    }

    public static void initCube(Bodies b, int i, float mass, boolean hard, float friction, float bounciness, float softBounciness) {
        b.invMass[i] = 1f / mass;
        b.invInertia[i] = 6f / mass;
        b.hard[i] = hard;
        b.drag[i] = hard ? 0.05f : 1.0f;
        b.mu[i] = hard ? friction : friction * 1.5f;
        b.restitution[i] = hard ? bounciness : softBounciness;
        b.qw[i] = 1f;
    }

    public Bodies bodies() {
        return b;
    }

    public Kernels kernels() {
        return kernels;
    }

    public void invalidate() {
        grid.invalidate();
    }

    public void sleep(int piece) {
        int s = b.slot[piece];
        if (s >= b.awake)
            return;
        sleepSlot(s);
    }

    private void sleepSlot(int s) {
        grid.markResting(Grid.floor(b.px[s]), Grid.floor(b.py[s]), Grid.floor(b.pz[s]));
        b.vx[s] = b.vy[s] = b.vz[s] = b.wx[s] = b.wy[s] = b.wz[s] = 0f;
        b.swap(s, --b.awake);
    }

    public int awakeCount() { return b.awake; }

    public boolean asleep(int piece) {
        return b.slot[piece] >= b.awake;
    }

    public float x(int piece) { return b.px[b.slot[piece]]; }
    public float y(int piece) { return b.py[b.slot[piece]]; }
    public float z(int piece) { return b.pz[b.slot[piece]]; }
    public float qx(int piece) { return b.qx[b.slot[piece]]; }
    public float qy(int piece) { return b.qy[b.slot[piece]]; }
    public float qz(int piece) { return b.qz[b.slot[piece]]; }
    public float qw(int piece) { return b.qw[b.slot[piece]]; }

    public float speed(int piece) {
        return (float) Math.sqrt(b.speedSquared(b.slot[piece]));
    }

    private boolean[] visibleBySlot = new boolean[0];
    private byte[] occupancy = new byte[0];
    private final Coarse coarse = new Coarse();

    public void visibility(Sight sight, boolean piecesOcclude, boolean[] visibleByPiece) {
        int awake = b.awake;
        if (visibleBySlot.length < awake)
            visibleBySlot = new boolean[b.capacity];
        if (awake > 0) {
            byte[] occupied = piecesOcclude ? occupancyOfMovingPieces(awake) : null;
            kernels.visibility(b, 0, awake, grid, occupied, occupied != null ? coarse : grid.coarse, sight, visibleBySlot);
        }
        for (int s = 0; s < awake; s++)
            visibleByPiece[b.id[s]] = visibleBySlot[s];
        for (int s = awake; s < b.capacity; s++)
            visibleByPiece[b.id[s]] = true;
    }

    private byte[] occupancyOfMovingPieces(int awake) {
        if (occupancy.length != grid.cells.length)
            occupancy = new byte[grid.cells.length];
        else
            Arrays.fill(occupancy, (byte) 0);
        coarse.copyFrom(grid.coarse);
        for (int s = 0; s < awake; s++) {
            int x = Grid.floor(b.px[s]), y = Grid.floor(b.py[s]), z = Grid.floor(b.pz[s]);
            if (x >= grid.x0 && x < grid.x0 + grid.sx && y >= grid.y0 && y < grid.y0 + grid.sy && z >= grid.z0 && z < grid.z0 + grid.sz) {
                int index = grid.index(x, y, z);
                if (occupancy[index] < Byte.MAX_VALUE)
                    occupancy[index]++;
                coarse.mark(x - grid.x0, y - grid.y0, z - grid.z0);
            }
        }
        return occupancy;
    }

    public final long[] stageNanos = new long[12];
    public long builds, candidates, kept;
    public static final String[] STAGES = {"grid", "pair search", "forces", "pair solve", "terrain", "integrate", "calm + sleep", "total"};

    public boolean step() {
        int awake = b.awake;
        if (awake == 0)
            return true;

        long start = System.nanoTime(), t = start;
        prepareGrid(awake);
        Arrays.fill(b.touch, 0, awake, 0f);
        t = lap(0, t);
        if (awake >= REORDER_MIN_PIECES && ticks++ % REORDER_TICKS == 0 && sortIntoCells(awake))
            b.permute(sortedByCell, awake);
        t = lap(1, t);
        buildPairs(awake);
        t = lap(1, t);

        float dt = TICK / SUBSTEPS;
        for (int s = 0; s < SUBSTEPS; s++) {
            kernels.forces(b, 0, awake, grid, dt, gravity);
            t = lap(2, t);
            if (s > 0 && movedTooFar(awake))
                buildPairs(awake);
            t = lap(1, t);
            solvePairs(true);
            solvePairs(false);
            t = lap(3, t);
            (awake < SIMD_TERRAIN_MIN ? Kernels.scalar() : kernels).terrain(b, 0, awake, grid, dt);
            t = lap(4, t);
            kernels.integrate(b, 0, awake, dt);
            t = lap(5, t);
        }
        rescueBuried(awake);
        kernels.calm(b, 0, awake);
        sweepSleepers();
        t = lap(6, t);
        stageNanos[7] += t - start;
        return b.awake == 0;
    }

    private long lap(int stage, long since) {
        long now = System.nanoTime();
        stageNanos[stage] += now - since;
        return now;
    }

    private void prepareGrid(int awake) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < awake; i++) {
            minX = Math.min(minX, b.px[i]); maxX = Math.max(maxX, b.px[i]);
            minY = Math.min(minY, b.py[i]); maxY = Math.max(maxY, b.py[i]);
            minZ = Math.min(minZ, b.pz[i]); maxZ = Math.max(maxZ, b.pz[i]);
        }
        grid.ensure(Grid.floor(minX) - GRID_MARGIN, Grid.floor(minY) - GRID_MARGIN, Grid.floor(minZ) - GRID_MARGIN,
                Grid.floor(maxX) + GRID_MARGIN, Grid.floor(maxY) + GRID_MARGIN, Grid.floor(maxZ) + GRID_MARGIN);
    }

    private void rescueBuried(int awake) {
        int top = grid.y0 + grid.sy;
        for (int i = 0; i < awake; i++) {
            int x = Grid.floor(b.px[i]), y = Grid.floor(b.py[i]), z = Grid.floor(b.pz[i]);
            if (x < grid.x0 || x >= grid.x0 + grid.sx || y < grid.y0 || y >= top || z < grid.z0 || z >= grid.z0 + grid.sz)
                continue;
            int cell = grid.cell(x, y, z);
            if ((cell & Grid.TYPE_MASK) != Grid.SOLID || (cell & ~Grid.TYPE_MASK) != 0)
                continue;
            int free = y;
            while (free < top && (grid.cell(x, free, z) & Grid.TYPE_MASK) == Grid.SOLID)
                free++;
            if (free >= top)
                continue;
            b.py[i] = free + 0.5f;
            if (b.vy[i] < 0f)
                b.vy[i] = 0f;
        }
    }

    private void sweepSleepers() {
        int s = 0;
        while (s < b.awake) {
            if (b.calm[s] >= CALM_TICKS_TO_SLEEP)
                sleepSlot(s);
            else
                s++;
        }
    }

    private static int cell(float v) {
        return Grid.floor(v / CELL);
    }

    private final int[] runStart = new int[5], runEnd = new int[5];
    private int[] hits = new int[64];
    private int cellsX, cellsY, cellsZ, occupiedCount;

    private boolean sortIntoCells(int awake) {
        long t0 = System.nanoTime();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < awake; i++) {
            minX = Math.min(minX, b.px[i]); maxX = Math.max(maxX, b.px[i]);
            minY = Math.min(minY, b.py[i]); maxY = Math.max(maxY, b.py[i]);
            minZ = Math.min(minZ, b.pz[i]); maxZ = Math.max(maxZ, b.pz[i]);
        }
        int nx = (int) ((maxX - minX) / CELL) + 3, ny = (int) ((maxY - minY) / CELL) + 3, nz = (int) ((maxZ - minZ) / CELL) + 3;
        long total = (long) nx * ny * nz;
        if (total > denseCellLimit)
            return false;
        cellsX = nx;
        cellsY = ny;
        cellsZ = nz;
        stageNanos[8] += System.nanoTime() - t0;
        t0 = System.nanoTime();

        int size = (int) total;
        if (cellStart.length < size + 1) {
            cellStart = new int[size + 1];
            cellCursor = new int[size + 1];
        } else {
            Arrays.fill(cellStart, 0, size + 1, 0);
        }

        occupiedCount = 0;
        for (int i = 0; i < awake; i++) {
            int cx = (int) ((b.px[i] - minX) / CELL) + 1, cy = (int) ((b.py[i] - minY) / CELL) + 1, cz = (int) ((b.pz[i] - minZ) / CELL) + 1;
            int c = (cy * nz + cz) * nx + cx;
            inCell[i] = c;
            if (cellStart[c + 1]++ == 0) {
                if (occupiedCount == occupied.length)
                    occupied = Arrays.copyOf(occupied, occupiedCount * 2);
                occupied[occupiedCount++] = c;
            }
        }
        stageNanos[9] += System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int c = 0; c < size; c++)
            cellStart[c + 1] += cellStart[c];
        System.arraycopy(cellStart, 0, cellCursor, 0, size);
        for (int i = 0; i < awake; i++)
            sortedByCell[cellCursor[inCell[i]]++] = i;
        stageNanos[10] += System.nanoTime() - t0;
        return true;
    }

    private void buildPairs(int awake) {
        builds++;
        pairCount = 0;
        for (int i = 0; i < awake; i++) {
            builtX[i] = b.px[i];
            builtY[i] = b.py[i];
            builtZ[i] = b.pz[i];
        }
        if (!sortIntoCells(awake)) {
            buildPairsHashed(awake);
            return;
        }

        long t0 = System.nanoTime();
        for (int k = 0; k < awake; k++) {
            int slot = sortedByCell[k];
            sortedX[k] = b.px[slot];
            sortedY[k] = b.py[slot];
            sortedZ[k] = b.pz[slot];
        }

        int nx = cellsX, nz = cellsZ;
        int strideY = nz * nx;
        for (int k = 0; k < occupiedCount; k++) {
            int c = occupied[k];
            int from = cellStart[c], to = cellStart[c + 1];

            int[] rs = runStart, re = runEnd;
            rs[0] = from; re[0] = cellStart[c + 2];
            rs[1] = cellStart[c + nx - 1]; re[1] = cellStart[c + nx + 2];
            rs[2] = cellStart[c + strideY - nx - 1]; re[2] = cellStart[c + strideY - nx + 2];
            rs[3] = cellStart[c + strideY - 1]; re[3] = cellStart[c + strideY + 2];
            rs[4] = cellStart[c + strideY + nx - 1]; re[4] = cellStart[c + strideY + nx + 2];
            int span = 0;
            for (int r = 0; r < 5; r++)
                span += re[r] - rs[r];

            for (int a = from; a < to; a++) {
                if (pairCount + span > pairA.length) {
                    int grown = Math.max(pairA.length * 2, pairCount + span);
                    pairA = Arrays.copyOf(pairA, grown);
                    pairB = Arrays.copyOf(pairB, grown);
                }
                if (hits.length < span + 16)
                    hits = new int[span + 16];
                float ax = sortedX[a], ay = sortedY[a], az = sortedZ[a];
                int slotA = sortedByCell[a];
                int count = pairCount;
                for (int r = 0; r < 5; r++) {
                    int start = r == 0 ? a + 1 : rs[r];
                    int found = kernels.near(sortedX, sortedY, sortedZ, start, re[r], ax, ay, az, PAIR_RANGE_SQ, hits);
                    for (int h = 0; h < found; h++) {
                        pairA[count] = slotA;
                        pairB[count++] = sortedByCell[hits[h]];
                    }
                    candidates += re[r] - start;
                }
                pairCount = count;
            }
        }
        kept += pairCount;
        stageNanos[11] += System.nanoTime() - t0;
    }

    private void buildPairsHashed(int awake) {
        cells.clear();
        for (int i = 0; i < awake; i++) {
            long key = key(cell(b.px[i]), cell(b.py[i]), cell(b.pz[i]));
            cellNext[i] = cells.get(key);
            cells.put(key, i);
        }

        for (int i = 0; i < awake; i++) {
            int cx = cell(b.px[i]), cy = cell(b.py[i]), cz = cell(b.pz[i]);
            for (int j = cellNext[i]; j != -1; j = cellNext[j])
                addPair(i, j);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx < 0 || (dx == 0 && (dy < 0 || (dy == 0 && dz <= 0))))
                            continue;
                        for (int j = cells.get(key(cx + dx, cy + dy, cz + dz)); j != -1; j = cellNext[j])
                            addPair(i, j);
                    }
        }
    }

    private void addPair(int i, int j) {
        candidates++;
        float dx = b.px[j] - b.px[i], dy = b.py[j] - b.py[i], dz = b.pz[j] - b.pz[i];
        if (dx * dx + dy * dy + dz * dz >= PAIR_RANGE_SQ)
            return;
        if (pairCount == pairA.length) {
            pairA = Arrays.copyOf(pairA, pairCount * 2);
            pairB = Arrays.copyOf(pairB, pairCount * 2);
        }
        pairA[pairCount] = i;
        pairB[pairCount] = j;
        pairCount++;
        kept++;
    }

    private boolean movedTooFar(int awake) {
        for (int i = 0; i < awake; i++) {
            float dx = b.px[i] - builtX[i], dy = b.py[i] - builtY[i], dz = b.pz[i] - builtZ[i];
            if (dx * dx + dy * dy + dz * dz > REBUILD_MOVE_SQ)
                return true;
        }
        return false;
    }

    private void solvePairs(boolean positional) {
        for (int p = 0; p < pairCount; p++)
            resolvePair(pairA[p], pairB[p], positional);
    }

    private void resolvePair(int i, int j, boolean positional) {
        float nx = b.px[j] - b.px[i], ny = b.py[j] - b.py[i], nz = b.pz[j] - b.pz[i];
        float distSq = nx * nx + ny * ny + nz * nz;
        if (distSq >= 1f || distSq < 1e-8f)
            return;
        float dist = (float) Math.sqrt(distSq);
        nx /= dist; ny /= dist; nz /= dist;

        b.touch[i] = 1f;
        b.touch[j] = 1f;

        float wa = b.invMass[i], wb = b.invMass[j];

        if (positional) {
            float overlap = 1f - dist - 0.01f;
            if (overlap <= 0f)
                return;
            float corr = overlap * 0.8f / (wa + wb);
            b.px[i] -= nx * corr * wa; b.py[i] -= ny * corr * wa; b.pz[i] -= nz * corr * wa;
            b.px[j] += nx * corr * wb; b.py[j] += ny * corr * wb; b.pz[j] += nz * corr * wb;
            return;
        }

        float rax = nx * 0.5f, ray = ny * 0.5f, raz = nz * 0.5f;
        float vax = b.vx[i] + (b.wy[i] * raz - b.wz[i] * ray), vay = b.vy[i] + (b.wz[i] * rax - b.wx[i] * raz), vaz = b.vz[i] + (b.wx[i] * ray - b.wy[i] * rax);
        float vbx = b.vx[j] - (b.wy[j] * raz - b.wz[j] * ray), vby = b.vy[j] - (b.wz[j] * rax - b.wx[j] * raz), vbz = b.vz[j] - (b.wx[j] * ray - b.wy[j] * rax);
        float rvx = vbx - vax, rvy = vby - vay, rvz = vbz - vaz;
        float vn = rvx * nx + rvy * ny + rvz * nz;
        if (vn >= 0f)
            return;

        float restitution = (!b.hard[i] || !b.hard[j]) ? Math.max(RESTITUTION, softBounciness) : RESTITUTION;
        float jn = -(1f + restitution) * vn / (wa + wb);
        b.vx[i] -= nx * jn * wa; b.vy[i] -= ny * jn * wa; b.vz[i] -= nz * jn * wa;
        b.vx[j] += nx * jn * wb; b.vy[j] += ny * jn * wb; b.vz[j] += nz * jn * wb;

        float tx = rvx - vn * nx, ty = rvy - vn * ny, tz = rvz - vn * nz;
        float vt = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (vt < 1e-5f)
            return;
        tx /= vt; ty /= vt; tz /= vt;
        float ia = b.invInertia[i], ib = b.invInertia[j];
        float kt = wa + wb + (ia + ib) * 0.25f;
        float jt = Math.min(vt / kt, friction * jn);

        float px = tx * jt, py = ty * jt, pz = tz * jt;
        b.vx[i] += px * wa; b.vy[i] += py * wa; b.vz[i] += pz * wa;
        b.vx[j] -= px * wb; b.vy[j] -= py * wb; b.vz[j] -= pz * wb;
        float cxp = ray * pz - raz * py, cyp = raz * px - rax * pz, czp = rax * py - ray * px;
        b.wx[i] += cxp * ia; b.wy[i] += cyp * ia; b.wz[i] += czp * ia;
        b.wx[j] += cxp * ib; b.wy[j] += cyp * ib; b.wz[j] += czp * ib;
    }
}
