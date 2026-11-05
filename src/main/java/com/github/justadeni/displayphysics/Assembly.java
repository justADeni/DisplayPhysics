package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.net.Packets;
import com.github.justadeni.displayphysics.platform.Platform;
import org.bukkit.Bukkit;
import com.github.justadeni.displayphysics.sim.Simulator;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** Floats per pose: qx qy qz qw tx ty tz + 1 padding, so two poses fill a 64-byte cache line and none straddles two. */
    private static final int POSE_STRIDE = 8;
    private static final int POSE_VALUES = 7;
    private static final float VIEW_RANGE = 3f;
    /** Roughly where a standing player's eyes are above their feet. */
    private static final double EYE_HEIGHT = 1.62;

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

    /** A player the displays are shown to. */
    private static final class Watcher {
        volatile double eyeX, eyeY, eyeZ;
        volatile boolean located;
        /** With occlusion on: the pose this player was last sent for each piece, and which pieces they can see. */
        float[] sent;
        boolean[] visible;
    }

    private final Map<Player, Watcher> shown = new ConcurrentHashMap<>();
    /** Set when a simulation starts with occlusion culling on. */
    private volatile Occlusion occlusion;
    private Object[] frameCache = new Object[0];
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
        for (Player player : shown.keySet())
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
        enableOcclusion(settings.occlusion);
        int refreshTicks = settings.occlusion == null ? VIEWER_REFRESH_TICKS : Math.min(VIEWER_REFRESH_TICKS, settings.occlusion.everyTicks());
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
                if (settings.occlusion != null && sim.broken() && sim.tick() % settings.occlusion.everyTicks() == 0)
                    updateVisibility(sim, settings.occlusion);

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
                    publish(pose, unsent, done);
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

                if (sim.tick() % refreshTicks == 0)
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
            // Never leave pieces hanging in the air: hand over whatever state was reached
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

    /**
     * Sends the pieces that moved since the last frame, bundled. Without occlusion everyone gets the same
     * bundle; with it each player gets only the pieces they can see (all of them on the last frame, so nothing
     * is left where it was last seen).
     */
    private synchronized void publish(float[] pose, int ticks, boolean last) {
        if (occlusion != null) {
            publishCulled(pose, ticks, last);
            return;
        }
        List<Object> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int o = i * POSE_STRIDE;
            // Pieces at rest keep their pose: sending it again would only restart their interpolation
            boolean same = true;
            for (int k = o; k < o + POSE_VALUES; k++)
                if (pose[k] != sent[k]) {
                    same = false;
                    break;
                }
            if (same)
                continue;
            System.arraycopy(pose, o, sent, o, POSE_STRIDE);
            packets.add(posePacket(sent, i, ticks));
        }
        anySent = true;
        if (packets.isEmpty() || released.get())
            return;

        Object bundle = Packets.bundle(packets);
        for (Player player : shown.keySet())
            Packets.send(player, bundle);
    }

    private void publishCulled(float[] pose, int ticks, boolean last) {
        if (frameCache.length < count)
            frameCache = new Object[count];
        System.arraycopy(pose, 0, sent, 0, count * POSE_STRIDE); // the latest pose of everything, for whoever arrives next
        anySent = true;
        if (released.get())
            return;

        for (Map.Entry<Player, Watcher> entry : shown.entrySet()) {
            Watcher watcher = entry.getValue();
            if (watcher.sent == null)
                continue;
            List<Object> packets = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (!last && !watcher.visible[i])
                    continue;
                int o = i * POSE_STRIDE;
                boolean same = true;
                for (int k = o; k < o + POSE_VALUES; k++)
                    if (pose[k] != watcher.sent[k]) {
                        same = false;
                        break;
                    }
                if (same)
                    continue;
                System.arraycopy(pose, o, watcher.sent, o, POSE_STRIDE);
                // One packet per piece per frame, shared by everyone who gets it
                packets.add(frameCache[i] != null ? frameCache[i] : (frameCache[i] = posePacket(pose, i, ticks)));
            }
            if (!packets.isEmpty())
                Packets.send(entry.getKey(), Packets.bundle(packets));
        }
        java.util.Arrays.fill(frameCache, 0, count, null);
    }

    private Object posePacket(float[] source, int piece, int ticks) {
        int o = piece * POSE_STRIDE;
        return Packets.pose(ids[piece], ticks, new Vector3f(source[o + 4], source[o + 5], source[o + 6]),
                new Quaternionf(source[o], source[o + 1], source[o + 2], source[o + 3]));
    }

    // ── Occlusion ────────────────────────────────────────────────────────────

    /** Starts per-player bookkeeping for everyone already watching. */
    private synchronized void enableOcclusion(Occlusion occlusion) {
        this.occlusion = occlusion;
        if (occlusion != null)
            for (Watcher watcher : shown.values())
                prepare(watcher);
    }

    private void prepare(Watcher watcher) {
        watcher.sent = sent.clone();
        watcher.visible = new boolean[count];
        java.util.Arrays.fill(watcher.visible, true);
    }

    /** Recomputes which pieces each player can see from where they stand. */
    private void updateVisibility(Simulator sim, Occlusion occlusion) {
        for (Watcher watcher : shown.values())
            if (watcher.located && watcher.visible != null)
                sim.visibility(watcher.eyeX, watcher.eyeY, watcher.eyeZ, occlusion.maxDistance(), occlusion.spacing(), occlusion.pieces(), watcher.visible);
    }

    // ── Viewers ──────────────────────────────────────────────────────────────

    /**
     * Shows the displays to players who came into range and removes them from those who left.
     * Each player is located on the thread that owns them; the shown set is updated once all answered.
     */
    private CompletableFuture<Void> refreshViewers() {
        if (released.get() || !refreshing.compareAndSet(false, true))
            return CompletableFuture.completedFuture(null);

        record Seen(Player player, Location at) {}
        List<CompletableFuture<Seen>> checks = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(world))
                continue;
            checks.add(platform.locate(player).handle((at, error) ->
                    at != null && error == null && viewers.sees(player, at, centerX, centerY, centerZ) ? new Seen(player, at) : null));
        }

        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).handle((done, error) -> {
            try {
                Map<Player, Location> now = new java.util.HashMap<>();
                for (CompletableFuture<Seen> check : checks) {
                    Seen seen = check.getNow(null);
                    if (seen != null)
                        now.put(seen.player(), seen.at());
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

    private synchronized void apply(Map<Player, Location> now) {
        if (released.get())
            return;

        Object remove = null;
        for (Player player : shown.keySet()) {
            if (now.containsKey(player) && player.isOnline())
                continue;
            if (remove == null)
                remove = Packets.remove(ids);
            Packets.send(player, remove);
            shown.remove(player);
        }

        for (Map.Entry<Player, Location> entry : now.entrySet()) {
            Player player = entry.getKey();
            Location at = entry.getValue();
            Watcher watcher = shown.get(player);
            boolean arriving = watcher == null;
            if (arriving)
                watcher = new Watcher();
            // Where their eyes are, for occlusion
            watcher.eyeX = at.getX();
            watcher.eyeY = at.getY() + EYE_HEIGHT;
            watcher.eyeZ = at.getZ();
            watcher.located = true;
            if (!arriving)
                continue;

            if (occlusion != null)
                prepare(watcher);
            shown.put(player, watcher);
            boolean glow = anyGlow && viewers.seesGlow(player);
            List<Object> packets = new ArrayList<>(count * 3);
            for (int i = 0; i < count; i++)
                packets.addAll(List.of(glow ? spawnGlow[i] : spawnPlain[i]));
            // Someone arriving mid-fall sees the pieces where they are now
            if (anySent)
                for (int i = 0; i < count; i++)
                    packets.add(posePacket(sent, i, 0));
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
