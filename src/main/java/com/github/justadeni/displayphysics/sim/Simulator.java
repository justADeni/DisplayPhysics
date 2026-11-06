package com.github.justadeni.displayphysics.sim;
import com.github.justadeni.displayphysics.sim.kernels.Sight;
import com.github.justadeni.displayphysics.sim.kernels.Kernels;

import com.github.justadeni.displayphysics.Blast;
import com.github.justadeni.displayphysics.Hinge;
import com.github.justadeni.displayphysics.Mode;
import com.github.justadeni.displayphysics.Occlusion;
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

public final class Simulator {
    private static final int RIGID_SUBSTEPS = 8;
    private static final int RIGID_ITERATIONS = 8;
    private static final float GROUND_REACH = 0.55f;
    private static final int TERRAIN_REFRESH_TICKS = 20;

    private enum Phase { HINGE, RIGID, DEBRIS, DONE }

    private final Settings settings;
    private final TerrainSource source;
    private final Hinge hinge;
    private final SplittableRandom random = new SplittableRandom();

    private final int ox, oy, oz;
    private final Voxels terrain;
    private CompletableFuture<?> terrainRefresh;
    private final ContactSolver solver = new ContactSolver();

    private final int count;
    private final float[] off;
    private final boolean[] hard;
    private final float[] mass;

    private final Body body = new Body();
    private final Vector3f com0 = new Vector3f();
    private float[] probes;
    private boolean[] probeHard;
    private float[] probeHeight;
    private int probeCount;
    private int rigidCalm = 0;
    private int rigidContacts = 0;

    private float theta, omega;
    private final Vector3f hingeAxis = new Vector3f();
    private final Vector3f hingePivot = new Vector3f();
    private final Vector3f hingeArm = new Vector3f();

    private FlatDebris debris;
    private final Kernels kernels;
    private Phase phase;
    private int tick = 0;
    private int freeTicks = 0;
    private int splitAt = -1;
    private final int maxTicks, maxRigidTicks;
    private final Mode.BreakOnImpact breakOn;

    private float impact;
    private final Vector3f impactAt = new Vector3f();
    private final Vector3f lastImpact = new Vector3f();
    private boolean brokeNow;
    private boolean pendingBreak;

    private final float[] centre, speed;
    private int kinematicsTick = -1;

    private final Vector3f r = new Vector3f();
    private final Vector3f vel = new Vector3f();
    private final Matrix3f rot = new Matrix3f();

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

        off = new float[count * 3];
        this.mass = new float[count];
        centre = new float[count * 3];
        speed = new float[count];
        for (int i = 0; i < count; i++) {
            off[3 * i] = x[i] - ox;
            off[3 * i + 1] = y[i] - oy;
            off[3 * i + 2] = z[i] - oz;
            this.mass[i] = Math.max(mass[i], 0.01f);
        }

        terrain = new Voxels(source, ox, oy, oz);
        kernels = settings.simd ? Kernels.best() : Kernels.scalar();
        if (source.ageMillis() >= TERRAIN_REFRESH_TICKS * TICK_MS)
            terrainRefresh = source.refresh();

        maxTicks = Math.max(20, (int) (settings.maxDuration * 20f));
        breakOn = settings.mode instanceof Mode.BreakOnImpact b ? b : null;
        maxRigidTicks = breakOn == null ? 0 : Math.max(1, (int) (breakOn.maxRigidSeconds() * 20f));

        buildRigidBody();

        switch (settings.mode) {
            case Mode.FallApart _ -> {
                shatter(false, scatter(0.2f));
                pendingBreak = true;
            }
            case Mode.Explode explode -> {
                explode(explode.blast());
                pendingBreak = true;
            }
            case Mode.Rigid _, Mode.BreakOnImpact _ -> startSolid();
        }
    }

    private void startSolid() {
        if (hinge == null) {
            phase = Phase.RIGID;
            return;
        }
        hingeAxis.set(hinge.axis());
        hingeAxis.y = 0;
        if (hingeAxis.lengthSquared() < 1e-6f)
            hingeAxis.set(1, 0, 0);
        hingeAxis.normalize();
        hingePivot.set((float) (hinge.pivotX() - ox), (float) (hinge.pivotY() - oy), (float) (hinge.pivotZ() - oz));
        hingeArm.set(com0).sub(hingePivot);
        theta = hinge.initialAngle();
        phase = Phase.HINGE;
    }

    private void buildRigidBody() {
        float totalMass = 0f;
        for (int i = 0; i < count; i++) {
            com0.add((off[3 * i] + 0.5f) * mass[i], (off[3 * i + 1] + 0.5f) * mass[i], (off[3 * i + 2] + 0.5f) * mass[i]);
            totalMass += mass[i];
        }
        com0.div(totalMass);

        float ixx = 0, iyy = 0, izz = 0, ixy = 0, ixz = 0, iyz = 0;
        for (int i = 0; i < count; i++) {
            float dx = off[3 * i] + 0.5f - com0.x, dy = off[3 * i + 1] + 0.5f - com0.y, dz = off[3 * i + 2] + 0.5f - com0.z;
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

        Set<Long> occupied = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++)
            occupied.add(key(Math.round(off[3 * i]), Math.round(off[3 * i + 1]), Math.round(off[3 * i + 2])));

        float baseY = hinge != null ? (float) (hinge.pivotY() - oy) : minOffY();
        Set<Long> corners = new HashSet<>();
        List<float[]> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int bx = Math.round(off[3 * i]), by = Math.round(off[3 * i + 1]), bz = Math.round(off[3 * i + 2]);
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
        for (int i = 1; i < off.length; i += 3)
            min = Math.min(min, off[i]);
        return min;
    }

    public boolean step() {
        if (phase == Phase.DONE)
            return true;
        if (tick >= maxTicks) {
            phase = Phase.DONE;
            return true;
        }

        tick++;
        impact = 0f;
        brokeNow = pendingBreak;
        pendingBreak = false;
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

    private void refreshTerrain() {
        if (terrainRefresh != null && terrainRefresh.isDone()) {
            terrain.invalidate();
            if (debris != null)
                debris.invalidate();
            terrainRefresh = null;
        }
        if (terrainRefresh == null && tick % TERRAIN_REFRESH_TICKS == 0)
            terrainRefresh = source.refresh();
    }

    private void stepHinge() {
        float height = Math.max(1f, hinge.height());
        float alpha = 3f * settings.gravity / (2f * height) * (float) Math.sin(theta);
        omega = Math.fma(alpha, TICK, omega);
        theta = Math.fma(omega, TICK, theta);

        body.q.fromAxisAngleRad(hingeAxis, theta);
        body.q.transform(hingeArm, body.x).add(hingePivot);

        body.w.set(hingeAxis).mul(omega);
        body.w.cross(r.set(body.x).sub(hingePivot), body.v);

        if (theta >= hinge.releaseAngle() || hingeBlocked()) {
            body.updateInertia();
            phase = Phase.RIGID;
        }
    }

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

    private void stepRigidPhase() {
        freeTicks++;
        impact = stepRigid();
        boolean settled = updateRigidCalm();

        if (breakOn != null) {
            if (splitAt < 0 && (impact >= breakOn.speed() || freeTicks >= maxRigidTicks || settled))
                splitAt = tick + (settled ? 0 : breakOn.delayTicks());
            if (splitAt >= 0 && tick >= splitAt)
                shatter(false, scatter(1f));
        } else if (settled) {
            phase = Phase.DONE;
        }
    }

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

    @FunctionalInterface
    private interface Kick {
        boolean apply(int piece, Body body, Vector3f fromCentre);
    }

    private void shatter(boolean sleepUnthrown, Kick kick) {
        var bodies = new Bodies(count);
        var thrown = new boolean[count];
        var piece = new Body();

        for (int i = 0; i < count; i++) {
            body.q.transform(off[3 * i] + 0.5f - com0.x, off[3 * i + 1] + 0.5f - com0.y, off[3 * i + 2] + 0.5f - com0.z, r);

            piece.x.set(body.x).add(r);
            piece.q.set(body.q);
            body.velocityAt(r, piece.v);
            piece.w.set(body.w);

            thrown[i] = kick.apply(i, piece, r);

            FlatDebris.initCube(bodies, i, mass[i], hard[i], settings.friction, settings.bounciness, settings.softBounciness);
            bodies.px[i] = piece.x.x; bodies.py[i] = piece.x.y; bodies.pz[i] = piece.x.z;
            bodies.vx[i] = piece.v.x; bodies.vy[i] = piece.v.y; bodies.vz[i] = piece.v.z;
            bodies.wx[i] = piece.w.x; bodies.wy[i] = piece.w.y; bodies.wz[i] = piece.w.z;
            bodies.qx[i] = piece.q.x; bodies.qy[i] = piece.q.y; bodies.qz[i] = piece.q.z; bodies.qw[i] = piece.q.w;
        }

        debris = new FlatDebris(bodies, source, ox, oy, oz, settings.gravity, settings.friction, settings.softBounciness, kernels);
        if (sleepUnthrown)
            for (int i = 0; i < count; i++)
                if (!thrown[i])
                    debris.sleep(i);
        phase = Phase.DEBRIS;
        brokeNow = true;
        pendingBreak = false;
    }

    private Kick scatter(float scale) {
        float scatter = settings.scatter * scale;
        var out = new Vector3f();
        return (i, piece, fromCentre) -> {
            out.set(fromCentre.x, 0, fromCentre.z);
            if (out.lengthSquared() < 1e-4f)
                randomHorizontal(out);
            out.normalize();
            float strength = scatter * (float) random.nextDouble(0.4, 1.0) * (hard[i] ? 1f : 1.5f);
            piece.v.add(out.x * 0.7f * strength, 0.5f * strength, out.z * 0.7f * strength);
            piece.v.add(jitter(0.3f * strength), jitter(0.3f * strength), jitter(0.3f * strength));
            piece.w.add(jitter(1.5f * scatter), jitter(1.5f * scatter), jitter(1.5f * scatter));
            return true;
        };
    }

    private void explode(Blast blast) {
        var centre = switch (blast.epicenter()) {
            case Blast.Epicenter.Point(double x, double y, double z) -> new Vector3f((float) (x - ox), (float) (y - oy), (float) (z - oz));
            case Blast.Epicenter.Centre _ -> new Vector3f(com0);
        };
        float radius = blast.radius();
        float radiusSquared = radius * radius;
        boolean bounded = Float.isFinite(radius);
        var offset = new Vector3f();
        var direction = new Vector3f();

        shatter(blast.remainder() == Blast.Remainder.STAY, (i, piece, _) -> {
            offset.set(piece.x).sub(centre);
            float distanceSquared = offset.lengthSquared();
            if (bounded && distanceSquared > radiusSquared)
                return false;

            float falloff = bounded ? 1f - 0.5f * (float) Math.sqrt(distanceSquared) / radius : 1f;
            float speed = blast.force() * falloff * (float) random.nextDouble(0.6, 1.0) * (hard[i] ? 1f : 1.3f);
            aim(blast.aim(), offset, direction);
            piece.v.set(direction).mul(speed).add(0, blast.lift() * speed, 0);
            piece.w.set(jitter(0.3f * speed), jitter(0.3f * speed), jitter(0.3f * speed));
            return true;
        });
    }

    private void aim(Blast.Aim aim, Vector3f offset, Vector3f out) {
        switch (aim) {
            case Blast.Aim.Outward _ -> {
                out.set(offset);
                if (out.lengthSquared() < 1e-6f)
                    randomDirection(out);
                out.normalize();
            }
            case Blast.Aim.Along(float x, float y, float z, float spread) -> {
                float cosine = 1f - (float) random.nextDouble() * (1f - (float) Math.cos(spread));
                float sine = (float) Math.sqrt(1f - cosine * cosine);
                double azimuth = random.nextDouble(0, 2 * Math.PI);

                var axis = new Vector3f(x, y, z);
                var u = (Math.abs(x) < 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0)).cross(axis).normalize();
                var v = new Vector3f(axis).cross(u);
                out.set(axis).mul(cosine)
                        .add(u.mul(sine * (float) Math.cos(azimuth)))
                        .add(v.mul(sine * (float) Math.sin(azimuth)));
            }
        }
    }

    private float jitter(float amplitude) {
        return (float) random.nextDouble(-amplitude, amplitude);
    }

    private void randomHorizontal(Vector3f out) {
        out.set(jitter(1f), 0, jitter(1f));
        if (out.lengthSquared() < 1e-6f)
            out.set(1, 0, 0);
    }

    private void randomDirection(Vector3f out) {
        out.set(jitter(1f), jitter(1f), jitter(1f));
        if (out.lengthSquared() < 1e-6f)
            out.set(0, 1, 0);
    }

    public int count() {
        return count;
    }

    public int tick() {
        return tick;
    }

    public boolean broken() {
        return debris != null;
    }

    public boolean brokeThisTick() {
        return brokeNow;
    }

    public float impactSpeed() {
        return impact;
    }

    public void impactPoint(double[] out) {
        out[0] = ox + impactAt.x;
        out[1] = oy + impactAt.y;
        out[2] = oz + impactAt.z;
    }

    public void pose(int i, Quaternionf rotation, Vector3f translation) {
        if (debris == null) {
            body.q.get(rot);
            rotate(rot, off[3 * i] - com0.x, off[3 * i + 1] - com0.y, off[3 * i + 2] - com0.z, r).add(body.x);
            rotation.set(body.q);
            translation.set(r.x - off[3 * i], r.y - off[3 * i + 1], r.z - off[3 * i + 2]);
        } else {
            rotation.set(debris.qx(i), debris.qy(i), debris.qz(i), debris.qw(i));
            rotation.transform(0.5f, 0.5f, 0.5f, r);
            translation.set(debris.x(i) - r.x - off[3 * i], debris.y(i) - r.y - off[3 * i + 1], debris.z(i) - r.z - off[3 * i + 2]);
        }
    }

    public void visibility(Occlusion occlusion, double ex, double ey, double ez, float lx, float ly, float lz, boolean[] visibleByPiece) {
        if (debris == null) {
            java.util.Arrays.fill(visibleByPiece, 0, count, true);
            return;
        }
        double half = occlusion.coneHalfAngle();
        var sight = new Sight((float) (ex - ox), (float) (ey - oy), (float) (ez - oz), lx, ly, lz,
                half < Math.PI, (float) Math.cos(half), (float) Math.sin(half), occlusion.maxDistance(), occlusion.spacing());
        debris.visibility(sight, occlusion.pieces(), visibleByPiece);
    }

    public void restingCenter(int i, double[] out) {
        computeKinematics();
        out[0] = ox + centre[3 * i];
        out[1] = oy + centre[3 * i + 1];
        out[2] = oz + centre[3 * i + 2];
    }

    public void restingRotation(int i, Quaternionf out) {
        if (debris == null)
            out.set(body.q);
        else
            out.set(debris.qx(i), debris.qy(i), debris.qz(i), debris.qw(i));
    }

    public double centerX(int i) { computeKinematics(); return ox + centre[3 * i]; }
    public double centerY(int i) { computeKinematics(); return oy + centre[3 * i + 1]; }
    public double centerZ(int i) { computeKinematics(); return oz + centre[3 * i + 2]; }

    public float speed(int i) {
        computeKinematics();
        return speed[i];
    }

    public boolean asleep(int i) {
        return debris != null && debris.asleep(i);
    }

    public boolean touchingGround(int i) {
        computeKinematics();
        int x = Voxels.floor(centre[3 * i]), y = Voxels.floor(centre[3 * i + 1]), z = Voxels.floor(centre[3 * i + 2]);
        return terrain.solid(x, y, z) || terrain.solid(x, Voxels.floor(centre[3 * i + 1] - GROUND_REACH), z);
    }

    private void computeKinematics() {
        if (kinematicsTick == tick)
            return;
        kinematicsTick = tick;

        if (debris == null) {
            body.q.get(rot);
            for (int i = 0; i < count; i++) {
                rotate(rot, off[3 * i] + 0.5f - com0.x, off[3 * i + 1] + 0.5f - com0.y, off[3 * i + 2] + 0.5f - com0.z, r);
                speed[i] = body.velocityAt(r, vel).length();
                r.add(body.x);
                centre[3 * i] = r.x;
                centre[3 * i + 1] = r.y;
                centre[3 * i + 2] = r.z;
            }
        } else {
            for (int i = 0; i < count; i++) {
                centre[3 * i] = debris.x(i);
                centre[3 * i + 1] = debris.y(i);
                centre[3 * i + 2] = debris.z(i);
                speed[i] = debris.speed(i);
            }
        }
    }
}
