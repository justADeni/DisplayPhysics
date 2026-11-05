package com.github.justadeni.displayphysics.sim;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.Arrays;

import static com.github.justadeni.displayphysics.sim.Tuning.*;

/**
 * The pieces of an assembly that fell apart: one small rigid body per block, colliding
 * with the terrain (8 cube corners each) and with each other.
 *
 * <p>Block-to-block contacts treat each block as a sphere of radius 0.5, so blocks
 * that start side by side are exactly touching, not overlapping. Pieces that stay
 * calm while touching something fall asleep: they stop simulating and act as solid
 * ground for the others until something hits them hard enough to wake them.
 */
final class Debris {

    private static final int SUBSTEPS = 4;
    private static final int ITERATIONS = 4;
    private static final float RESTITUTION = 0.1f;

    /** Half-size of a cube for terrain probes, pulled in a hair so neighbours never start overlapping. */
    private static final float C = 0.49f;

    final Body[] pieces;
    final boolean[] asleep;

    private final int count;
    private final boolean[] hard;
    private final boolean[] touching;
    private final int[] calm;
    private final Voxels terrain;
    private final float gravity, friction, bounciness, softBounciness;

    private final ContactSolver solver = new ContactSolver();

    // Neighbour list: pairs close enough to touch soon, built once per tick and reused
    // for every substep and both pair passes. Rebuilt early if anything moves more than
    // half the margin, so no contact can be missed.
    private static final float MARGIN = 0.5f;
    private static final float PAIR_RANGE_SQ = (1f + MARGIN) * (1f + MARGIN);
    private static final float REBUILD_MOVE_SQ = (MARGIN / 2f) * (MARGIN / 2f);
    /** Grid cell size; must cover the pair range so the 27 neighbouring cells hold every candidate. */
    private static final float CELL = 2f;
    private final Long2IntOpenHashMap grid = new Long2IntOpenHashMap();
    private final int[] gridNext;
    private final float[] builtX, builtY, builtZ;
    private int[] pairA = new int[1024], pairB = new int[1024];
    private int pairCount;
    private final Matrix3f rot = new Matrix3f();

    Debris(Voxels terrain, Body[] pieces, boolean[] hard, float gravity, float friction, float bounciness, float softBounciness) {
        this.terrain = terrain;
        this.pieces = pieces;
        this.hard = hard;
        this.gravity = gravity;
        this.friction = friction;
        this.bounciness = bounciness;
        this.softBounciness = softBounciness;
        this.count = pieces.length;
        this.asleep = new boolean[count];
        this.touching = new boolean[count];
        this.calm = new int[count];
        this.gridNext = new int[count];
        this.builtX = new float[count];
        this.builtY = new float[count];
        this.builtZ = new float[count];
        grid.defaultReturnValue(-1);
    }

    /** Turns {@code piece} into a unit cube of the given mass. */
    static void makeCube(Body piece, float mass) {
        piece.invMass = 1f / mass;
        // Solid unit cube: I = m/6 on every axis, so world and body inertia match.
        piece.invInertiaBody.identity().scale(6f / mass);
        piece.invInertia.set(piece.invInertiaBody);
    }

    /** Advances one tick; returns whether every piece has come to rest. */
    boolean step() {
        float dt = TICK / SUBSTEPS;
        Arrays.fill(touching, false);
        buildPairs();

        for (int s = 0; s < SUBSTEPS; s++) {
            for (int i = 0; i < count; i++) {
                if (asleep[i])
                    continue;
                Body p = pieces[i];
                p.v.y -= gravity * dt;
                if (terrain.liquid(p.x.x, p.x.y, p.x.z)) {
                    p.v.y += gravity * BUOYANCY * dt;
                    p.v.mul(1f - LIQUID_DRAG * dt);
                    p.w.mul(1f - LIQUID_DRAG * dt);
                    touching[i] = true;
                }
                // Soft pieces catch the air
                float drag = hard[i] ? 0.05f : 1.0f;
                p.v.mul(1f - drag * dt);
                p.w.mul(1f - (touching[i] ? ROLLING_RESISTANCE : 0.2f) * dt);
            }

            // Blocks push each other apart first, so the terrain pass gets the last
            // word and nothing is left shoved into the ground.
            if (s > 0 && movedTooFar())
                buildPairs();
            solvePairs(true);
            solvePairs(false);

            for (int i = 0; i < count; i++) {
                if (asleep[i])
                    continue;
                Body p = pieces[i];
                solver.begin();
                // The 8 corners are every +/- combination of the cube's three rotated half-axes
                p.q.get(rot);
                float ax = rot.m00 * C, ay = rot.m01 * C, az = rot.m02 * C;
                float bx = rot.m10 * C, by = rot.m11 * C, bz = rot.m12 * C;
                float cx = rot.m20 * C, cy = rot.m21 * C, cz = rot.m22 * C;
                for (int corner = 0; corner < 8; corner++) {
                    float sa = (corner & 1) == 0 ? -1f : 1f, sb = (corner & 2) == 0 ? -1f : 1f, sc = (corner & 4) == 0 ? -1f : 1f;
                    solver.probe(p, terrain,
                            sa * ax + sb * bx + sc * cx,
                            sa * ay + sb * by + sc * cy,
                            sa * az + sb * bz + sc * cz,
                            hard[i]);
                }
                solver.solve(p, dt, ITERATIONS, friction, bounciness, softBounciness);
                if (solver.count() > 0)
                    touching[i] = true;
            }

            for (int i = 0; i < count; i++)
                if (!asleep[i])
                    pieces[i].integrate(dt);
        }

        boolean allAsleep = true;
        for (int i = 0; i < count; i++) {
            if (asleep[i])
                continue;
            Body p = pieces[i];
            boolean calmNow = touching[i] && p.v.lengthSquared() < 0.25f * 0.25f && p.w.lengthSquared() < 0.5f * 0.5f;
            calm[i] = calmNow ? calm[i] + 1 : 0;
            if (calm[i] >= CALM_TICKS_TO_SLEEP) {
                asleep[i] = true;
                p.v.zero();
                p.w.zero();
            } else {
                allAsleep = false;
            }
        }
        return allAsleep;
    }

    /**
     * Collects every pair of pieces within reach of touching. Only awake pieces search;
     * sleeping ones are found as their neighbours (they still block, but cost nothing).
     */
    private void buildPairs() {
        grid.clear();
        for (int i = 0; i < count; i++) {
            Vector3f x = pieces[i].x;
            long k = key(cell(x.x), cell(x.y), cell(x.z));
            gridNext[i] = grid.get(k);
            grid.put(k, i);
            builtX[i] = x.x;
            builtY[i] = x.y;
            builtZ[i] = x.z;
        }

        pairCount = 0;
        for (int i = 0; i < count; i++) {
            if (asleep[i])
                continue;
            Vector3f xi = pieces[i].x;
            int cx = cell(xi.x), cy = cell(xi.y), cz = cell(xi.z);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int j = grid.get(key(cx + dx, cy + dy, cz + dz));
                        for (; j != -1; j = gridNext[j]) {
                            // Each awake-awake pair once; awake-asleep pairs from the awake side
                            if (j == i || (!asleep[j] && j < i))
                                continue;
                            Vector3f xj = pieces[j].x;
                            float ddx = xj.x - xi.x, ddy = xj.y - xi.y, ddz = xj.z - xi.z;
                            if (ddx * ddx + ddy * ddy + ddz * ddz >= PAIR_RANGE_SQ)
                                continue;
                            if (pairCount == pairA.length) {
                                pairA = Arrays.copyOf(pairA, pairCount * 2);
                                pairB = Arrays.copyOf(pairB, pairCount * 2);
                            }
                            pairA[pairCount] = i;
                            pairB[pairCount] = j;
                            pairCount++;
                        }
                    }
        }
    }

    /** Whether any awake piece has moved far enough that the neighbour list may miss a contact. */
    private boolean movedTooFar() {
        for (int i = 0; i < count; i++) {
            if (asleep[i])
                continue;
            Vector3f x = pieces[i].x;
            float dx = x.x - builtX[i], dy = x.y - builtY[i], dz = x.z - builtZ[i];
            if (dx * dx + dy * dy + dz * dz > REBUILD_MOVE_SQ)
                return true;
        }
        return false;
    }

    private static int cell(float v) {
        return Voxels.floor(v / CELL);
    }

    /** @param positional false: velocity impulses with friction; true: push overlapping pairs apart */
    private void solvePairs(boolean positional) {
        for (int p = 0; p < pairCount; p++)
            resolvePair(pairA[p], pairB[p], positional);
    }

    private void resolvePair(int i, int j, boolean positional) {
        if (asleep[i] && asleep[j])
            return;

        Body a = pieces[i], b = pieces[j];
        float nx = b.x.x - a.x.x, ny = b.x.y - a.x.y, nz = b.x.z - a.x.z;
        float distSq = nx * nx + ny * ny + nz * nz;
        if (distSq >= 1f || distSq < 1e-8f)
            return;
        float dist = (float) Math.sqrt(distSq);
        nx /= dist; ny /= dist; nz /= dist;

        touching[i] = true;
        touching[j] = true;

        // A sleeping piece acts as immovable ground
        float wa = asleep[i] ? 0f : a.invMass;
        float wb = asleep[j] ? 0f : b.invMass;

        if (positional) {
            float overlap = 1f - dist - 0.01f;
            if (overlap <= 0f || wa + wb == 0f)
                return;
            float corr = overlap * 0.8f / (wa + wb);
            a.x.sub(nx * corr * wa, ny * corr * wa, nz * corr * wa);
            b.x.add(nx * corr * wb, ny * corr * wb, nz * corr * wb);
            return;
        }

        // Contact offsets: half a block towards each other (r_b = -r_a)
        float rax = nx * 0.5f, ray = ny * 0.5f, raz = nz * 0.5f;
        float vax = a.v.x + (a.w.y * raz - a.w.z * ray), vay = a.v.y + (a.w.z * rax - a.w.x * raz), vaz = a.v.z + (a.w.x * ray - a.w.y * rax);
        float vbx = b.v.x - (b.w.y * raz - b.w.z * ray), vby = b.v.y - (b.w.z * rax - b.w.x * raz), vbz = b.v.z - (b.w.x * ray - b.w.y * rax);
        float rvx = vbx - vax, rvy = vby - vay, rvz = vbz - vaz;
        float vn = rvx * nx + rvy * ny + rvz * nz;
        if (vn >= 0f)
            return;

        // A fast hit wakes a sleeping piece
        if (-vn > 1f) {
            if (asleep[i]) { asleep[i] = false; calm[i] = 0; wa = a.invMass; }
            if (asleep[j]) { asleep[j] = false; calm[j] = 0; wb = b.invMass; }
        }
        if (wa + wb == 0f)
            return;

        // Bouncy soft pieces spring off whatever they hit; otherwise blocks barely rebound off each other
        float restitution = (!hard[i] || !hard[j]) ? Math.max(RESTITUTION, softBounciness) : RESTITUTION;
        float jn = -(1f + restitution) * vn / (wa + wb);
        a.v.sub(nx * jn * wa, ny * jn * wa, nz * jn * wa);
        b.v.add(nx * jn * wb, ny * jn * wb, nz * jn * wb);

        // Friction: touching blocks drag and spin each other
        float tx = rvx - vn * nx, ty = rvy - vn * ny, tz = rvz - vn * nz;
        float vt = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (vt < 1e-5f)
            return;
        tx /= vt; ty /= vt; tz /= vt;
        float ia = wa == 0f ? 0f : 6f * a.invMass, ib = wb == 0f ? 0f : 6f * b.invMass;
        float kt = wa + wb + (ia + ib) * 0.25f;
        float jt = Math.min(vt / kt, friction * jn);

        // Impulse +P on a at r_a, -P on b at r_b: both give (n/2) x P
        float px = tx * jt, py = ty * jt, pz = tz * jt;
        a.v.add(px * wa, py * wa, pz * wa);
        b.v.sub(px * wb, py * wb, pz * wb);
        float cxp = ray * pz - raz * py, cyp = raz * px - rax * pz, czp = rax * py - ray * px;
        a.w.add(cxp * ia, cyp * ia, czp * ia);
        b.w.add(cxp * ib, cyp * ib, czp * ib);
    }

}
