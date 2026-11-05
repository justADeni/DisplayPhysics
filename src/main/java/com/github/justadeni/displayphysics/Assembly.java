package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.net.Packets;
import com.github.justadeni.displayphysics.platform.Platform;
import org.bukkit.Bukkit;
import com.github.justadeni.displayphysics.sim.Simulator;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.locks.LockSupport;

/**
 * A group of blocks shown as packet-only block displays and treated as one solid unit.
 *
 * <p>Creating it spawns the displays for the viewers right away (so the host can remove the real
 * blocks a tick later without a gap). {@link #simulate} then lets it fall; the displays are sent
 * their poses straight from the simulation thread, bundled so every client applies a frame at
 * once. They stay where they ended up until {@link #release()}.
 *
 * <p>Thread-safe. Nothing here touches the world, entities or chunks, so it can be used from any thread,
 * including a Folia region thread.
 */
public final class Assembly {

    private static final long TICK_NANOS = 50_000_000L;
    private static final int VIEWER_REFRESH_TICKS = 20;
    private static final int POSE_STRIDE = 7;
    private static final float VIEW_RANGE = 3f;

    private final Logger logger;
    private final Platform platform;
    private final CompletableFuture<Void> spawned;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final World world;
    private final List<Piece> pieces;
    private final Viewers viewers;
    private final int count;

    private final int[] ids;
    private final Object[][] spawnPlain, spawnGlow;
    private final boolean anyGlow;

    private final Set<Player> shown = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean released = new AtomicBoolean();
    private final double centerX, centerY, centerZ;

    /** The pose each display was last sent, {@code qx qy qz qw tx ty tz}; guarded by {@code this}. */
    private final float[] sent;
    private boolean anySent;

    Assembly(Logger logger, Platform platform, World world, List<Piece> pieces, Viewers viewers) {
        this.logger = logger;
        this.platform = platform;
        this.world = world;
        this.pieces = List.copyOf(pieces);
        this.viewers = viewers;
        this.count = this.pieces.size();
        this.sent = new float[count * POSE_STRIDE];
        for (int i = 0; i < count; i++)
            sent[i * POSE_STRIDE + 3] = 1f;

        ids = new int[count];
        spawnPlain = new Object[count][];
        spawnGlow = new Object[count][];
        boolean glow = false;
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < count; i++) {
            Piece piece = this.pieces.get(i);
            ids[i] = Packets.nextEntityId(world);
            UUID uuid = UUID.randomUUID();
            Object state = Packets.blockState(piece.data());
            spawnPlain[i] = Packets.spawn(ids[i], uuid, piece.x(), piece.y(), piece.z(), state, VIEW_RANGE, -1);
            if (piece.glowRgb() >= 0) {
                spawnGlow[i] = Packets.spawn(ids[i], uuid, piece.x(), piece.y(), piece.z(), state, VIEW_RANGE, piece.glowRgb());
                glow = true;
            } else {
                spawnGlow[i] = spawnPlain[i];
            }
            sx += piece.x() + 0.5;
            sy += piece.y() + 0.5;
            sz += piece.z() + 0.5;
        }
        anyGlow = glow;
        centerX = sx / count;
        centerY = sy / count;
        centerZ = sz / count;

        spawned = refreshViewers();
        // Safety net: displays nobody ever released would stay on clients until they reload the chunk
        DisplayPhysics.later(this::release, 600_000L);
    }

    /**
     * Completes once the displays have been sent to the first viewers. Remove the real blocks
     * after this, so nobody sees a gap. Immediate except on Folia, where players owned by
     * other regions are reached through their own thread.
     */
    public CompletableFuture<Void> spawned() {
        return spawned;
    }

    /** The pieces this assembly was created from, in order. */
    public List<Piece> pieces() {
        return pieces;
    }

    /**
     * Lets the assembly fall. Runs on its own virtual thread, and completes with where every
     * piece came to rest. The displays stay where they are until {@link #release()}.
     *
     * @param hinge    how it starts to tip, or {@code null} to start as a free body
     * @param terrain  what it collides with
     * @param listener the host's own effects, or {@code null}
     */
    public CompletableFuture<Result> simulate(Hinge hinge, Settings settings, TerrainSource terrain, Listener listener) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Thread.ofVirtual().name("DisplayPhysics").start(() -> {
            try {
                future.complete(run(hinge, settings, terrain, listener));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Removes the displays from every client and stops a running simulation. Safe to call twice. */
    public void release() {
        if (!released.compareAndSet(false, true))
            return;
        Object remove = Packets.remove(ids);
        for (Player player : shown)
            Packets.send(player, remove);
        shown.clear();
    }

    public boolean released() {
        return released.get();
    }

    // ── The simulation loop ──────────────────────────────────────────────────

    private Result run(Hinge hinge, Settings settings, TerrainSource terrain, Listener listener) {
        int[] x = new int[count], y = new int[count], z = new int[count];
        boolean[] hard = new boolean[count];
        float[] mass = new float[count];
        for (int i = 0; i < count; i++) {
            Piece piece = pieces.get(i);
            x[i] = piece.x();
            y[i] = piece.y();
            z[i] = piece.z();
            hard[i] = piece.hard();
            mass[i] = piece.mass();
        }

        Simulator sim = new Simulator(x, y, z, hard, mass, hinge, settings, terrain);
        TickView view = new TickView(sim);
        float[] pose = new float[count * POSE_STRIDE];
        Quaternionf q = new Quaternionf();
        Vector3f t = new Vector3f();
        double[] at = new double[3];

        int unsent = 0;
        Throwable error = null;
        long next = System.nanoTime();

        try {
            boolean done = false;
            while (!done && !released.get()) {
                done = sim.step();

                for (int i = 0; i < count; i++) {
                    sim.pose(i, q, t);
                    int o = i * POSE_STRIDE;
                    pose[o] = q.x; pose[o + 1] = q.y; pose[o + 2] = q.z; pose[o + 3] = q.w;
                    pose[o + 4] = t.x; pose[o + 5] = t.y; pose[o + 6] = t.z;
                }
                // Only every updateInterval-th frame is sent, the client interpolating in between;
                // the last pose is always sent.
                unsent++;
                if (unsent >= settings.updateInterval || done) {
                    publish(pose, unsent);
                    unsent = 0;
                }

                if (listener != null) {
                    if (sim.impactSpeed() >= settings.impactReportSpeed) {
                        sim.impactPoint(at);
                        listener.onImpact(at[0], at[1], at[2], sim.impactSpeed());
                    }
                    if (sim.brokeThisTick())
                        listener.onBreak(view);
                    listener.onTick(view);
                }

                if (sim.tick() % VIEWER_REFRESH_TICKS == 0)
                    refreshViewers();

                if (!done) {
                    next += TICK_NANOS;
                    long wait = next - System.nanoTime();
                    if (wait > 0)
                        LockSupport.parkNanos(wait);
                    else if (wait < -5 * TICK_NANOS)
                        next = System.nanoTime(); // far behind: skip ahead instead of rushing to catch up
                }
            }
        } catch (Throwable e) {
            // Never leave a tree hanging in the air: hand over whatever state was reached
            error = e;
            logger.log(Level.WARNING, "Simulation failed, using the state it reached", e);
        }

        double[] positions = new double[count * 3];
        float[] rotations = new float[count * 4];
        for (int i = 0; i < count; i++) {
            sim.restingCenter(i, at);
            positions[i * 3] = at[0];
            positions[i * 3 + 1] = at[1];
            positions[i * 3 + 2] = at[2];
            sim.restingRotation(i, q);
            rotations[i * 4] = q.x; rotations[i * 4 + 1] = q.y; rotations[i * 4 + 2] = q.z; rotations[i * 4 + 3] = q.w;
        }

        DisplayPhysics.later(this::release, (long) (settings.autoReleaseSeconds * 1000f));
        return new Result(positions, rotations, sim.broken(), sim.tick(), error);
    }

    /** Sends the pieces that moved since the last frame, bundled, to everyone watching. */
    private synchronized void publish(float[] pose, int ticks) {
        List<Object> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int o = i * POSE_STRIDE;
            // Pieces at rest keep their pose: sending it again would only restart their interpolation
            boolean same = true;
            for (int k = o; k < o + POSE_STRIDE; k++)
                if (pose[k] != sent[k]) {
                    same = false;
                    break;
                }
            if (same)
                continue;
            System.arraycopy(pose, o, sent, o, POSE_STRIDE);
            packets.add(posePacket(i, ticks));
        }
        anySent = true;
        if (packets.isEmpty() || released.get())
            return;

        Object bundle = Packets.bundle(packets);
        for (Player player : shown)
            Packets.send(player, bundle);
    }

    private Object posePacket(int piece, int ticks) {
        int o = piece * POSE_STRIDE;
        return Packets.pose(ids[piece], ticks, new Vector3f(sent[o + 4], sent[o + 5], sent[o + 6]),
                new Quaternionf(sent[o], sent[o + 1], sent[o + 2], sent[o + 3]));
    }

    // ── Viewers ──────────────────────────────────────────────────────────────

    /**
     * Shows the displays to players who came into range and removes them from those who left.
     * Each player is located on the thread that owns them; the shown set is updated once all answered.
     */
    private CompletableFuture<Void> refreshViewers() {
        if (released.get() || !refreshing.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);

        List<CompletableFuture<Player>> checks = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world))
                continue;
            checks.add(platform.locate(player).handle((at, error) ->
                    at != null && error == null && viewers.sees(player, at, centerX, centerY, centerZ) ? player : null));
        }

        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).handle((done, error) -> {
            try {
                Set<Player> now = ConcurrentHashMap.newKeySet();
                for (CompletableFuture<Player> check : checks) {
                    Player player = check.getNow(null);
                    if (player != null)
                        now.add(player);
                }
                apply(now);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not update the viewers", e);
            } finally {
                refreshing.set(false);
            }
            return null;
        });
    }

    private synchronized void apply(Set<Player> now) {
        if (released.get())
            return;

        Object remove = null;
        for (Player player : shown) {
            if (now.contains(player) && player.isOnline())
                continue;
            if (remove == null)
                remove = Packets.remove(ids);
            Packets.send(player, remove);
            shown.remove(player);
        }

        for (Player player : now) {
            if (!shown.add(player))
                continue;
            boolean glow = anyGlow && viewers.seesGlow(player);
            List<Object> packets = new ArrayList<>(count * 3);
            for (int i = 0; i < count; i++)
                packets.addAll(List.of(glow ? spawnGlow[i] : spawnPlain[i]));
            // Someone arriving mid-fall sees the pieces where they are now
            if (anySent)
                for (int i = 0; i < count; i++)
                    packets.add(posePacket(i, 0));
            Packets.send(player, Packets.bundle(packets));
        }
    }

    // ── Listener view ────────────────────────────────────────────────────────

    private record TickView(Simulator sim) implements Listener.Tick {
        @Override public int number() { return sim.tick(); }
        @Override public int count() { return sim.count(); }
        @Override public double x(int piece) { return sim.centerX(piece); }
        @Override public double y(int piece) { return sim.centerY(piece); }
        @Override public double z(int piece) { return sim.centerZ(piece); }
        @Override public float speed(int piece) { return sim.speed(piece); }
        @Override public boolean touchingGround(int piece) { return sim.touchingGround(piece); }
        @Override public boolean asleep(int piece) { return sim.asleep(piece); }
        @Override public boolean broken() { return sim.broken(); }
    }

}
