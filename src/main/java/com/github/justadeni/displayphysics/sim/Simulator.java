package com.github.justadeni.displayphysics.sim;

import com.github.justadeni.displayphysics.BreakMode;
import com.github.justadeni.displayphysics.Hinge;
import com.github.justadeni.displayphysics.Settings;
import com.github.justadeni.displayphysics.TerrainSource;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

import static com.github.justadeni.displayphysics.sim.Tuning.*;

/**
 * The physics of one assembly, one tick at a time. Knows nothing about Bukkit, packets or
 * threads: feed it blocks and a {@link TerrainSource}, call {@link #step()} until it returns
 * {@code true}, and read the poses.
 *
 * <ol>
 *   <li><b>Hinge</b> (optional) - the assembly tips around an edge of its base until it reaches
 *       the release angle or hits something.</li>
 *   <li><b>Rigid</b> - the whole assembly is one rigid body that falls, bounces, slides and rolls
 *       against the terrain.</li>
 *   <li><b>Debris</b> ({@link BreakMode#BREAK_ON_IMPACT} only) - after a hard impact every block
 *       becomes its own body, colliding with the terrain and with each other.</li>
 * </ol>
 *
 * Not thread-safe: one thread drives it.
 */
public final class Simulator {

    private static final int RIGID_SUBSTEPS = 8;
    private static final int RIGID_ITERATIONS = 8;
    private static final float GROUND_REACH = 0.55f;
    /** How often the terrain is re-read, in ticks. */
    private static final int TERRAIN_REFRESH_TICKS = 20;

    private enum Phase { HINGE, RIGID, DEBRIS, DONE }

    private final Settings settings;
    private final TerrainSource source;
    private final Hinge hinge;
    private final SplittableRandom random = new SplittableRandom();

    // The simulation works relative to this block, so floats stay precise.
    private final int ox, oy, oz;
    private final Voxels terrain;
    private CompletableFuture<?> terrainRefresh;
    private final ContactSolver solver = new ContactSolver();

    // Per piece
    private final int count;
    private final float[] offX, offY, offZ;     // block corner, relative to the origin
    private final boolean[] hard;
    private final float[] mass;

    // Rigid body
    private final Body body = new Body();
    private final Vector3f com0 = new Vector3f();
    private float[] probes;                      // body-space, relative to the centre of mass
    private boolean[] probeHard;
    private float[] probeHeight;                 // height above the base before the fall
    private int probeCount;
    private int rigidCalm = 0;
    private int rigidContacts = 0;

    // Hinge state
    private float theta, omega;
    private final Vector3f hingeAxis = new Vector3f();
    private final Vector3f hingePivot = new Vector3f();
    private final Vector3f hingeArm = new Vector3f();

    private Debris debris;
    private Phase phase;
    private int tick = 0;
    private int freeTicks = 0;
    private int splitAt = -1;
    private final int maxTicks, maxRigidTicks;

    // This tick's events
    private float impact;
    private final Vector3f impactAt = new Vector3f();
    private final Vector3f lastImpact = new Vector3f();
    private boolean brokeNow;

    // Per tick kinematics, computed on request
    private final float[] cx, cy, cz, speed;
    private int kinematicsTick = -1;

    // Scratch
    private final Vector3f r = new Vector3f();
    private final Vector3f vel = new Vector3f();
    private final Matrix3f rot = new Matrix3f();

    /**
     * @param x      block x of every piece's corner; {@code y} and {@code z} likewise
     * @param hinge  how it starts, or {@code null} to start as a free body
     */
    public Simulator(int[] x, int[] y, int[] z, boolean[] hard, float[] mass, Hinge hinge, Settings settings, TerrainSource source) {
        this.settings = settings;
        this.source = source;
        this.hinge = hinge;
        this.hard = hard;
        this.count = x.length;
        if (count == 0)
            throw new IllegalArgumentException("Nothing to simulate");

        long sx = 0, sz = 0;
        int minY = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            sx += x[i];
            sz += z[i];
            minY = Math.min(minY, y[i]);
        }
        ox = (int) Math.floor((double) sx / count);
        oz = (int) Math.floor((double) sz / count);
        oy = minY;

        offX = new float[count];
        offY = new float[count];
        offZ = new float[count];
        this.mass = new float[count];
        cx = new float[count];
        cy = new float[count];
        cz = new float[count];
        speed = new float[count];
        for (int i = 0; i < count; i++) {
            offX[i] = x[i] - ox;
            offY[i] = y[i] - oy;
            offZ[i] = z[i] - oz;
            this.mass[i] = Math.max(mass[i], 0.01f);
        }

        terrain = new Voxels(source, ox, oy, oz);
        // The host's copy can be old: catch up on what changed meanwhile right away
        // instead of at the first regular refresh.
        if (source.ageMillis() >= TERRAIN_REFRESH_TICKS * TICK_MS)
            terrainRefresh = source.refresh();

        maxTicks = Math.max(20, (int) (settings.maxDuration * 20f));
        maxRigidTicks = Math.max(1, (int) (settings.maxRigidSeconds * 20f));

        buildRigidBody();

        if (hinge != null) {
            hingeAxis.set(hinge.axis());
            hingeAxis.y = 0;
            if (hingeAxis.lengthSquared() < 1e-6f)
                hingeAxis.set(1, 0, 0);
            hingeAxis.normalize();
            hingePivot.set((float) (hinge.pivotX() - ox), (float) (hinge.pivotY() - oy), (float) (hinge.pivotZ() - oz));
            hingeArm.set(com0).sub(hingePivot);
            theta = hinge.initialAngle();
            phase = Phase.HINGE;
        } else {
            phase = Phase.RIGID;
        }
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private void buildRigidBody() {
        float totalMass = 0f;
        for (int i = 0; i < count; i++) {
            com0.add((offX[i] + 0.5f) * mass[i], (offY[i] + 0.5f) * mass[i], (offZ[i] + 0.5f) * mass[i]);
            totalMass += mass[i];
        }
        com0.div(totalMass);

        // Inertia of unit cubes around the centre of mass (parallel axis theorem)
        float ixx = 0, iyy = 0, izz = 0, ixy = 0, ixz = 0, iyz = 0;
        for (int i = 0; i < count; i++) {
            float dx = offX[i] + 0.5f - com0.x, dy = offY[i] + 0.5f - com0.y, dz = offZ[i] + 0.5f - com0.z;
            float m = mass[i], cube = m / 6f;
            ixx += cube + m * (dy * dy + dz * dz);
            iyy += cube + m * (dx * dx + dz * dz);
            izz += cube + m * (dx * dx + dy * dy);
            ixy -= m * dx * dy;
            ixz -= m * dx * dz;
            iyz -= m * dy * dz;
        }
        body.invInertiaBody.set(ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz).invert();
        body.invMass = 1f / totalMass;
        body.x.set(com0);
        body.q.identity();
        body.updateInertia();

        // Collision probes: every corner of every hard piece, plus the centre of each
        // exposed soft piece (soft pieces sink in half a block, like a squashed canopy).
        Set<Long> occupied = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++)
            occupied.add(key(Math.round(offX[i]), Math.round(offY[i]), Math.round(offZ[i])));

        float baseY = hinge != null ? (float) (hinge.pivotY() - oy) : minOffY();
        Set<Long> corners = new HashSet<>();
        List<float[]> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int bx = Math.round(offX[i]), by = Math.round(offY[i]), bz = Math.round(offZ[i]);
            if (hard[i]) {
                for (int cx = 0; cx <= 1; cx++)
                    for (int cy = 0; cy <= 1; cy++)
                        for (int cz = 0; cz <= 1; cz++)
                            if (corners.add(key(bx + cx, by + cy, bz + cz)))
                                list.add(new float[]{bx + cx, by + cy, bz + cz, 1f});
            } else if (settings.softCollides) {
                boolean exposed = !occupied.contains(key(bx + 1, by, bz)) || !occupied.contains(key(bx - 1, by, bz))
                        || !occupied.contains(key(bx, by + 1, bz)) || !occupied.contains(key(bx, by - 1, bz))
                        || !occupied.contains(key(bx, by, bz + 1)) || !occupied.contains(key(bx, by, bz - 1));
                if (exposed)
                    list.add(new float[]{bx + 0.5f, by + 0.5f, bz + 0.5f, 0f});
            }
        }

        probeCount = list.size();
        probes = new float[probeCount * 3];
        probeHard = new boolean[probeCount];
        probeHeight = new float[probeCount];
        for (int p = 0; p < probeCount; p++) {
            float[] e = list.get(p);
            probes[p * 3] = e[0] - com0.x;
            probes[p * 3 + 1] = e[1] - com0.y;
            probes[p * 3 + 2] = e[2] - com0.z;
            probeHard[p] = e[3] > 0f;
            probeHeight[p] = e[1] - baseY;
        }
    }

    private float minOffY() {
        float min = Float.MAX_VALUE;
        for (float v : offY)
            min = Math.min(min, v);
        return min;
    }

    // ── Stepping ─────────────────────────────────────────────────────────────

    /** Advances one tick. Returns {@code true} when the simulation is over. */
    public boolean step() {
        if (phase == Phase.DONE)
            return true;
        if (tick >= maxTicks) {
            phase = Phase.DONE;
            return true;
        }

        tick++;
        impact = 0f;
        brokeNow = false;
        refreshTerrain();

        switch (phase) {
            case HINGE -> stepHinge();
            case RIGID -> stepRigidPhase();
            case DEBRIS -> {
                if (debris.step())
                    phase = Phase.DONE;
            }
            case DONE -> {}
        }
        return phase == Phase.DONE;
    }

    /**
     * Re-reads the ground every second so blocks placed or broken mid-fall are noticed.
     * The cache is only dropped once the host's copy is in.
     */
    private void refreshTerrain() {
        if (terrainRefresh != null && terrainRefresh.isDone()) {
            terrain.invalidate();
            terrainRefresh = null;
        }
        if (terrainRefresh == null && tick % TERRAIN_REFRESH_TICKS == 0)
            terrainRefresh = source.refresh();
    }

    // ── Hinge phase ──────────────────────────────────────────────────────────

    private void stepHinge() {
        float height = Math.max(1f, hinge.height());
        float alpha = 3f * settings.gravity / (2f * height) * (float) Math.sin(theta);
        omega = Math.fma(alpha, TICK, omega);
        theta = Math.fma(omega, TICK, theta);

        body.q.fromAxisAngleRad(hingeAxis, theta);
        body.q.transform(hingeArm, body.x).add(hingePivot);

        // Angular velocity about the hinge and the matching linear velocity of the
        // centre of mass: handed to the free body on release.
        body.w.set(hingeAxis).mul(omega);
        body.w.cross(r.set(body.x).sub(hingePivot), body.v);

        if (theta >= hinge.releaseAngle() || hingeBlocked()) {
            body.updateInertia();
            phase = Phase.RIGID;
        }
    }

    /** Whether anything above the bottom layer of the assembly has run into terrain. */
    private boolean hingeBlocked() {
        body.q.get(rot);
        for (int p = 0; p < probeCount; p++) {
            if (probeHeight[p] < 1.5f)
                continue;
            rotate(rot, probes[p * 3], probes[p * 3 + 1], probes[p * 3 + 2], r).add(body.x);
            if (terrain.solid(Voxels.floor(r.x), Voxels.floor(r.y), Voxels.floor(r.z)))
                return true;
        }
        return false;
    }

    // ── Rigid phase ──────────────────────────────────────────────────────────

    private void stepRigidPhase() {
        freeTicks++;
        impact = stepRigid();
        boolean settled = updateRigidCalm();

        if (settings.breakMode == BreakMode.BREAK_ON_IMPACT) {
            if (splitAt < 0 && (impact >= settings.breakSpeed || freeTicks >= maxRigidTicks || settled))
                splitAt = tick + (settled ? 0 : settings.breakDelay);
            if (splitAt >= 0 && tick >= splitAt)
                splitIntoDebris();
        } else if (settled) {
            phase = Phase.DONE;
        }
    }

    /** Advances one tick; returns the hardest impact speed (any block) during it. */
    private float stepRigid() {
        float dt = TICK / RIGID_SUBSTEPS;
        float maxImpact = 0f;
        float g = settings.gravity;

        for (int s = 0; s < RIGID_SUBSTEPS; s++) {
            body.v.y -= g * dt;
            if (terrain.liquid(body.x.x, body.x.y, body.x.z)) {
                body.v.y += g * BUOYANCY * dt;
                body.v.mul(1f - LIQUID_DRAG * dt);
                body.w.mul(1f - LIQUID_DRAG * dt);
            }
            body.v.mul(1f - 0.02f * dt);
            // Branches dig in, so an assembly touching the ground resists rolling
            body.w.mul(1f - (rigidContacts > 0 ? ROLLING_RESISTANCE : 0.1f) * dt);

            solver.begin();
            body.q.get(rot);
            for (int p = 0; p < probeCount; p++) {
                rotate(rot, probes[p * 3], probes[p * 3 + 1], probes[p * 3 + 2], r);
                solver.probe(body, terrain, r.x, r.y, r.z, probeHard[p]);
            }
            solver.solve(body, dt, RIGID_ITERATIONS, settings.friction, settings.bounciness, settings.softBounciness);
            rigidContacts = solver.count();
            if (solver.maxImpact > maxImpact) {
                maxImpact = solver.maxImpact;
                lastImpact.set(solver.impactPoint);
            }

            body.integrate(dt);
        }
        impactAt.set(lastImpact);
        return maxImpact;
    }

    private boolean updateRigidCalm() {
        boolean calmNow = rigidContacts > 0 && body.v.lengthSquared() < 0.15f * 0.15f && body.w.lengthSquared() < 0.15f * 0.15f;
        rigidCalm = calmNow ? rigidCalm + 1 : 0;
        return rigidCalm >= CALM_TICKS_TO_SLEEP;
    }

    // ── Debris phase ─────────────────────────────────────────────────────────

    private void splitIntoDebris() {
        Body[] pieces = new Body[count];

        float scatter = settings.scatter;
        Vector3f out = new Vector3f();

        for (int i = 0; i < count; i++) {
            Body piece = new Body();
            body.q.transform(offX[i] + 0.5f - com0.x, offY[i] + 0.5f - com0.y, offZ[i] + 0.5f - com0.z, r);

            piece.x.set(body.x).add(r);
            piece.q.set(body.q);
            body.velocityAt(r, piece.v);
            piece.w.set(body.w);

            // Burst outward from the middle and a little upward
            out.set(r.x, 0, r.z);
            if (out.lengthSquared() < 1e-4f)
                out.set((float) random.nextDouble(-1, 1), 0, (float) random.nextDouble(-1, 1));
            out.normalize();
            float strength = scatter * (float) random.nextDouble(0.4, 1.0) * (hard[i] ? 1f : 1.5f);
            piece.v.add(out.x * 0.7f * strength, 0.5f * strength, out.z * 0.7f * strength);
            piece.v.add((float) random.nextDouble(-0.3, 0.3) * strength,
                    (float) random.nextDouble(-0.3, 0.3) * strength,
                    (float) random.nextDouble(-0.3, 0.3) * strength);
            piece.w.add((float) random.nextDouble(-1.5, 1.5) * scatter,
                    (float) random.nextDouble(-1.5, 1.5) * scatter,
                    (float) random.nextDouble(-1.5, 1.5) * scatter);

            Debris.makeCube(piece, mass[i]);
            pieces[i] = piece;
        }

        debris = new Debris(terrain, pieces, hard, settings.gravity, settings.friction, settings.bounciness, settings.softBounciness);
        phase = Phase.DEBRIS;
        brokeNow = true;
    }

    // ── Reading the state ────────────────────────────────────────────────────

    public int count() {
        return count;
    }

    public int tick() {
        return tick;
    }

    public boolean broken() {
        return debris != null;
    }

    /** Whether the assembly broke apart during the last {@link #step()}. */
    public boolean brokeThisTick() {
        return brokeNow;
    }

    /** The hardest impact of the last {@link #step()} in blocks per second, or 0. */
    public float impactSpeed() {
        return impact;
    }

    /** World position of the last step's hardest impact. */
    public void impactPoint(double[] out) {
        out[0] = ox + impactAt.x;
        out[1] = oy + impactAt.y;
        out[2] = oz + impactAt.z;
    }

    /**
     * The pose of piece {@code i} for a display spawned at the piece's block corner: the
     * rotation to apply and the translation (blocks) from the corner.
     */
    public void pose(int i, Quaternionf rotation, Vector3f translation) {
        if (debris == null) {
            body.q.get(rot);
            // Display origin = its block corner; rotate that corner with the body.
            rotate(rot, offX[i] - com0.x, offY[i] - com0.y, offZ[i] - com0.z, r).add(body.x);
            rotation.set(body.q);
            translation.set(r.x - offX[i], r.y - offY[i], r.z - offZ[i]);
        } else {
            Body p = debris.pieces[i];
            // Display origin sits at the cube's corner: centre - q * (0.5, 0.5, 0.5)
            p.q.transform(0.5f, 0.5f, 0.5f, r);
            rotation.set(p.q);
            translation.set(p.x.x - r.x - offX[i], p.x.y - r.y - offY[i], p.x.z - r.z - offZ[i]);
        }
    }

    /** World position of the centre of piece {@code i}. */
    public void restingCenter(int i, double[] out) {
        computeKinematics();
        out[0] = ox + cx[i];
        out[1] = oy + cy[i];
        out[2] = oz + cz[i];
    }

    public void restingRotation(int i, Quaternionf out) {
        out.set(debris == null ? body.q : debris.pieces[i].q);
    }

    public double centerX(int i) { computeKinematics(); return ox + cx[i]; }
    public double centerY(int i) { computeKinematics(); return oy + cy[i]; }
    public double centerZ(int i) { computeKinematics(); return oz + cz[i]; }

    public float speed(int i) {
        computeKinematics();
        return speed[i];
    }

    public boolean asleep(int i) {
        return debris != null && debris.asleep[i];
    }

    /** Whether the centre of piece {@code i} is touching or sunk into solid terrain. */
    public boolean touchingGround(int i) {
        computeKinematics();
        int x = Voxels.floor(cx[i]), y = Voxels.floor(cy[i]), z = Voxels.floor(cz[i]);
        return terrain.solid(x, y, z) || terrain.solid(x, Voxels.floor(cy[i] - GROUND_REACH), z);
    }

    /** Fills in every piece's centre and speed for the current tick, once. */
    private void computeKinematics() {
        if (kinematicsTick == tick)
            return;
        kinematicsTick = tick;

        if (debris == null) {
            body.q.get(rot);
            for (int i = 0; i < count; i++) {
                rotate(rot, offX[i] + 0.5f - com0.x, offY[i] + 0.5f - com0.y, offZ[i] + 0.5f - com0.z, r);
                speed[i] = body.velocityAt(r, vel).length();
                r.add(body.x);
                cx[i] = r.x;
                cy[i] = r.y;
                cz[i] = r.z;
            }
        } else {
            for (int i = 0; i < count; i++) {
                Body p = debris.pieces[i];
                cx[i] = p.x.x;
                cy[i] = p.x.y;
                cz[i] = p.x.z;
                speed[i] = p.v.length();
            }
        }
    }

}
