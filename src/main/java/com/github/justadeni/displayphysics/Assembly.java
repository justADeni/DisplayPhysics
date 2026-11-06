package com.github.justadeni.displayphysics;

import com.github.justadeni.displayphysics.net.Packets;
import com.github.justadeni.displayphysics.platform.Platform;
import org.bukkit.Bukkit;
import com.github.justadeni.displayphysics.sim.Simulator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.locks.LockSupport;

/**
 * A group of blocks shown as packet-only block displays and treated as one solid unit.
 *
 * <p>Creating it spawns the displays for the viewers right away (so the real
 * blocks can be removed afterwards without a gap; {@link Settings#removeBlocks} does that when the simulation starts). {@link #simulate} then lets it fall; the displays are sent
 * their poses straight from the simulation thread, bundled so every client applies a frame at
 * once. They stay where they ended up until {@link #release()}.
 *
 * <p>Thread-safe. Nothing here touches the world, entities or chunks, so it can be used from any thread,
 * including a Folia region thread.
 */
public final class Assembly {

    private static final long TICK_NANOS = 50_000_000L;
    private static final int VIEWER_REFRESH_TICKS = 20;
    /** Floats per pose, see {@link PoseFeed}. */
    private static final int POSE_STRIDE = PoseFeed.STRIDE;
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
        /** The unit vector they look along. */
        volatile float lookX, lookY = 0f, lookZ = 1f;
        volatile boolean located;
        /** With occlusion on: what this player has been sent, and which pieces they can see. */
        PoseFeed feed;
    }

    private final Map<Player, Watcher> shown = new ConcurrentHashMap<>();
    /** Set when a simulation starts with occlusion culling on. */
    private volatile Occlusion occlusion;
    private Object[] frameCache = new Object[0];
    private final AtomicBoolean released = new AtomicBoolean();
    private final double centerX, centerY, centerZ;

    /** What everyone has been sent when nothing is culled; guarded by {@code this}. */
    private final PoseFeed shared;
    /** The newest pose of every piece, when culling means there is no single "what everyone has". */
    private final float[] latest;
    private final int[] selection;
    private boolean anySent;

    Assembly(Logger logger, Platform platform, World world, List<Piece> pieces, Viewers viewers) {
        this.logger = logger;
        this.platform = platform;
        this.world = world;
        this.pieces = List.copyOf(pieces);
        this.viewers = viewers;
        this.count = this.pieces.size();
        var identity = new float[count * POSE_STRIDE];
        for (int i = 0; i < count; i++)
            identity[i * POSE_STRIDE + 3] = 1f;
        this.shared = new PoseFeed(identity, count, false);
        this.latest = identity.clone();
        this.selection = new int[count];

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
                if (settings.removeBlocks) {
                    spawned.join(); // the displays are up, so removing the blocks leaves no gap
                    removeBlocks();
                }
                future.complete(run(hinge, settings, terrain, listener));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Sets the real blocks to air, one task per chunk on the thread that owns it. */
    private void removeBlocks() {
        Map<Long, List<Piece>> byChunk = new HashMap<>();
        for (Piece piece : pieces)
            byChunk.computeIfAbsent(((long) (piece.x() >> 4) << 32) | ((piece.z() >> 4) & 0xFFFFFFFFL), k -> new ArrayList<>()).add(piece);
        for (List<Piece> group : byChunk.values()) {
            Piece first = group.get(0);
            platform.runAt(world, first.x(), first.z(), () -> {
                for (Piece piece : group)
                    world.getBlockAt(piece.x(), piece.y(), piece.z()).setType(Material.AIR, false);
            });
        }
    }

    /**
     * Places each piece's block where it came to rest, one task per chunk on the thread that owns it, and releases
     * the displays once every chunk is done. Only air and replaceable blocks are overwritten.
     */
    private void placeBlocks(double[] positions) {
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        int[] bx = new int[count], by = new int[count], bz = new int[count];
        for (int i = 0; i < count; i++) {
            bx[i] = (int) Math.floor(positions[i * 3]);
            by[i] = (int) Math.floor(positions[i * 3 + 1]);
            bz[i] = (int) Math.floor(positions[i * 3 + 2]);
            byChunk.computeIfAbsent(((long) (bx[i] >> 4) << 32) | ((bz[i] >> 4) & 0xFFFFFFFFL), k -> new ArrayList<>()).add(i);
        }
        AtomicInteger remaining = new AtomicInteger(byChunk.size());
        for (List<Integer> group : byChunk.values()) {
            int first = group.get(0);
            platform.runAt(world, bx[first], bz[first], () -> {
                try {
                    for (int i : group) {
                        var block = world.getBlockAt(bx[i], by[i], bz[i]);
                        if (block.getType().isAir() || block.isReplaceable())
                            block.setBlockData(pieces.get(i).data(), false);
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0)
                        release();
                }
            });
        }
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

        if (settings.turnIntoBlocks)
            placeBlocks(positions);
        else
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
        int picked = shared.select(pose, last, selection);
        anySent = true;
        if (picked == 0 || released.get())
            return;

        List<Object> packets = new ArrayList<>(picked);
        for (int k = 0; k < picked; k++)
            packets.add(posePacket(pose, selection[k], ticks));
        Object bundle = Packets.bundle(packets);
        for (Player player : shown.keySet())
            Packets.send(player, bundle);
    }

    private void publishCulled(float[] pose, int ticks, boolean last) {
        if (frameCache.length < count)
            frameCache = new Object[count];
        System.arraycopy(pose, 0, latest, 0, count * POSE_STRIDE); // for whoever arrives next
        anySent = true;
        if (released.get())
            return;

        for (Map.Entry<Player, Watcher> entry : shown.entrySet()) {
            PoseFeed feed = entry.getValue().feed;
            if (feed == null)
                continue;
            int picked = feed.select(pose, last, selection);
            if (picked == 0)
                continue;
            List<Object> packets = new ArrayList<>(picked);
            for (int k = 0; k < picked; k++) {
                int piece = selection[k];
                // One packet per piece per frame, shared by everyone who gets it
                packets.add(frameCache[piece] != null ? frameCache[piece] : (frameCache[piece] = posePacket(pose, piece, ticks)));
            }
            Packets.send(entry.getKey(), Packets.bundle(packets));
        }
        java.util.Arrays.fill(frameCache, 0, count, null);
    }

    private Object posePacket(float[] source, int piece, int ticks) {
        int o = piece * POSE_STRIDE;
        return Packets.pose(ids[piece], ticks, new Vector3f(source[o + 4], source[o + 5], source[o + 6]),
                new Quaternionf(source[o], source[o + 1], source[o + 2], source[o + 3]));
    }

    /** The newest pose of every piece. */
    private float[] newest() {
        return occlusion != null ? latest : shared.sent();
    }

    // ── Occlusion ────────────────────────────────────────────────────────────

    /** Starts per-player bookkeeping for everyone already watching. */
    private synchronized void enableOcclusion(Occlusion occlusion) {
        this.occlusion = occlusion;
        if (occlusion != null) {
            System.arraycopy(shared.sent(), 0, latest, 0, latest.length);
            for (Watcher watcher : shown.values())
                watcher.feed = new PoseFeed(latest, count, true);
        }
    }

    /** Recomputes which pieces each player can see from where they stand. */
    private void updateVisibility(Simulator sim, Occlusion occlusion) {
        for (Watcher watcher : shown.values())
            if (watcher.located && watcher.feed != null)
                sim.visibility(occlusion, watcher.eyeX, watcher.eyeY, watcher.eyeZ, watcher.lookX, watcher.lookY, watcher.lookZ, watcher.feed.visible());
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
            double yaw = Math.toRadians(at.getYaw()), pitch = Math.toRadians(at.getPitch());
            watcher.lookX = (float) (-Math.sin(yaw) * Math.cos(pitch));
            watcher.lookY = (float) -Math.sin(pitch);
            watcher.lookZ = (float) (Math.cos(yaw) * Math.cos(pitch));
            watcher.located = true;
            if (!arriving)
                continue;

            if (occlusion != null)
                watcher.feed = new PoseFeed(latest, count, true);
            shown.put(player, watcher);
            boolean glow = anyGlow && viewers.seesGlow(player);
            List<Object> packets = new ArrayList<>(count * 3);
            for (int i = 0; i < count; i++)
                packets.addAll(List.of(glow ? spawnGlow[i] : spawnPlain[i]));
            // Someone arriving mid-fall sees the pieces where they are now
            if (anySent)
                for (int i = 0; i < count; i++)
                    packets.add(posePacket(newest(), i, 0));
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
