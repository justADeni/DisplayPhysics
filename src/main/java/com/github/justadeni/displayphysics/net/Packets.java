package com.github.justadeni.displayphysics.net;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds and sends the block-display packets straight through the server's own classes.
 *
 * <p>Needs a Mojang-mapped server (Paper 1.20.5 or newer, where the NMS classes keep their
 * real names). Everything is resolved once through {@link MethodHandle}s; if any of it is
 * missing, {@link #available()} is {@code false} and nothing else may be called.
 *
 * <p>Packets are plain objects that can be sent to any number of players, from any thread:
 * the server's connection hands them to the network thread itself.
 */
public final class Packets {

    private Packets() {}

    private static final String NMS = "net.minecraft.";
    /** CraftBukkit's package: unversioned on current servers, but read from the running server to be safe. */
    private static final String CRAFT = Bukkit.getServer() != null
            ? Bukkit.getServer().getClass().getPackageName() + "."
            : "org.bukkit.craftbukkit.";

    private static final Throwable FAILURE;

    private static final MethodHandle BLOCK_STATE, PLAYER_HANDLE, CONNECTION, SEND;
    private static final MethodHandle ADD_ENTITY, SET_DATA, REMOVE, BUNDLE, DATA_VALUE;
    private static final Object BLOCK_DISPLAY_TYPE, VEC3_ZERO;
    private static final Object BLOCK_STATE_KEY, VIEW_RANGE_KEY, SHARED_FLAGS_KEY, GLOW_COLOR_KEY;
    private static final Object START_DELTA_KEY, DURATION_KEY, TRANSLATION_KEY, ROTATION_KEY;

    static {
        MethodHandle blockState = null, playerHandle = null, connection = null, send = null;
        MethodHandle addEntity = null, setData = null, remove = null, bundle = null, dataValue = null;
        Object blockDisplayType = null, vec3Zero = null;
        Object blockStateKey = null, viewRangeKey = null, sharedFlagsKey = null, glowColorKey = null;
        Object startDeltaKey = null, durationKey = null, translationKey = null, rotationKey = null;
        Throwable failure = null;

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Class<?> craftBlockData = Class.forName(CRAFT + "block.data.CraftBlockData");
            blockState = lookup.unreflect(craftBlockData.getMethod("getState"));

            Class<?> craftPlayer = Class.forName(CRAFT + "entity.CraftPlayer");
            Class<?> serverPlayer = Class.forName(NMS + "server.level.ServerPlayer");
            Class<?> packet = Class.forName(NMS + "network.protocol.Packet");
            playerHandle = lookup.unreflect(craftPlayer.getMethod("getHandle"));
            connection = lookup.unreflectGetter(serverPlayer.getField("connection"));
            send = lookup.unreflect(connection.type().returnType().getMethod("send", packet));

            Class<?> entityType = Class.forName(NMS + "world.entity.EntityType");
            Class<?> vec3 = Class.forName(NMS + "world.phys.Vec3");
            blockDisplayType = staticField(entityType, "BLOCK_DISPLAY", NMS + "world.entity.EntityTypes");
            vec3Zero = staticField(vec3, "ZERO");

            Class<?> addPacket = Class.forName(NMS + "network.protocol.game.ClientboundAddEntityPacket");
            addEntity = lookup.unreflectConstructor(addPacket.getConstructor(int.class, UUID.class,
                    double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class));

            Class<?> dataPacket = Class.forName(NMS + "network.protocol.game.ClientboundSetEntityDataPacket");
            setData = lookup.unreflectConstructor(dataPacket.getConstructor(int.class, List.class));
            Class<?> removePacket = Class.forName(NMS + "network.protocol.game.ClientboundRemoveEntitiesPacket");
            remove = lookup.unreflectConstructor(removePacket.getConstructor(int[].class));
            Class<?> bundlePacket = Class.forName(NMS + "network.protocol.game.ClientboundBundlePacket");
            bundle = lookup.unreflectConstructor(bundlePacket.getConstructor(Iterable.class));

            Class<?> accessor = Class.forName(NMS + "network.syncher.EntityDataAccessor");
            Class<?> dataValueClass = Class.forName(NMS + "network.syncher.SynchedEntityData$DataValue");
            dataValue = lookup.unreflect(dataValueClass.getMethod("create", accessor, Object.class));

            Class<?> entity = Class.forName(NMS + "world.entity.Entity");
            Class<?> display = Class.forName(NMS + "world.entity.Display");
            Class<?> blockDisplay = Class.forName(NMS + "world.entity.Display$BlockDisplay");
            blockStateKey = staticField(blockDisplay, "DATA_BLOCK_STATE_ID");
            viewRangeKey = staticField(display, "DATA_VIEW_RANGE_ID");
            glowColorKey = staticField(display, "DATA_GLOW_COLOR_OVERRIDE_ID");
            sharedFlagsKey = staticField(entity, "DATA_SHARED_FLAGS_ID");
            startDeltaKey = staticField(display, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID", "DATA_INTERPOLATION_START_DELTA_TICKS_ID");
            durationKey = staticField(display, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID", "DATA_INTERPOLATION_DURATION_ID");
            translationKey = staticField(display, "DATA_TRANSLATION_ID");
            rotationKey = staticField(display, "DATA_LEFT_ROTATION_ID");
        } catch (Throwable t) {
            failure = t;
        }

        FAILURE = failure;
        BLOCK_STATE = blockState;
        PLAYER_HANDLE = playerHandle;
        CONNECTION = connection;
        SEND = send;
        ADD_ENTITY = addEntity;
        SET_DATA = setData;
        REMOVE = remove;
        BUNDLE = bundle;
        DATA_VALUE = dataValue;
        BLOCK_DISPLAY_TYPE = blockDisplayType;
        VEC3_ZERO = vec3Zero;
        BLOCK_STATE_KEY = blockStateKey;
        VIEW_RANGE_KEY = viewRangeKey;
        SHARED_FLAGS_KEY = sharedFlagsKey;
        GLOW_COLOR_KEY = glowColorKey;
        START_DELTA_KEY = startDeltaKey;
        DURATION_KEY = durationKey;
        TRANSLATION_KEY = translationKey;
        ROTATION_KEY = rotationKey;
    }

    /**
     * Reads a static field, trying each name in turn; the first name may also be looked up in
     * the extra holder class given as the last name when it contains a dot.
     */
    private static Object staticField(Class<?> owner, String... names) throws Exception {
        Class<?> holder = owner;
        List<String> candidates = new ArrayList<>();
        for (String name : names) {
            if (name.contains(".")) {
                try {
                    holder = Class.forName(name);
                } catch (ClassNotFoundException ignored) {
                    // not on this version
                }
            } else {
                candidates.add(name);
            }
        }
        for (Class<?> from : holder == owner ? List.of(owner) : List.of(owner, holder)) {
            for (String name : candidates) {
                Field field = findField(from, name);
                if (field != null) {
                    field.setAccessible(true);
                    return field.get(null);
                }
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + candidates);
    }

    private static Field findField(Class<?> from, String name) {
        for (Class<?> c = from; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        return null;
    }

    /** Whether this server can be talked to. If not, nothing else in this class may be called. */
    public static boolean available() {
        return FAILURE == null;
    }

    /** Why {@link #available()} is false, or {@code null}. */
    public static Throwable failure() {
        return FAILURE;
    }

    // ── Building ─────────────────────────────────────────────────────────────

    /**
     * A fresh entity id that can't clash with any real entity. Taken from an entity the API
     * creates without adding it to the world, so it comes from the server's own counter.
     */
    public static int nextEntityId(World world) {
        return world.createEntity(new Location(world, 0, 0, 0), BlockDisplay.class).getEntityId();
    }

    /** The server's own representation of the block, which the display packets carry. */
    public static Object blockState(BlockData data) {
        try {
            return BLOCK_STATE.invoke(data);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Spawns a block display at the given position, showing {@code state}, standing still. */
    public static Object[] spawn(int id, UUID uuid, double x, double y, double z, Object state, float viewRange, int glowRgb) {
        try {
            Object add = ADD_ENTITY.invoke(id, uuid, x, y, z, 0f, 0f, BLOCK_DISPLAY_TYPE, 0, VEC3_ZERO, 0d);

            List<Object> values = new ArrayList<>(4);
            values.add(value(BLOCK_STATE_KEY, state));
            values.add(value(VIEW_RANGE_KEY, viewRange));
            if (glowRgb >= 0) {
                values.add(value(SHARED_FLAGS_KEY, (byte) 0x40));
                values.add(value(GLOW_COLOR_KEY, glowRgb & 0xFFFFFF));
            }
            return new Object[]{add, SET_DATA.invoke(id, values)};
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Moves a display: it interpolates to this pose over {@code ticks} client ticks. The start
     * delay is sent every time, which is what makes the client restart its interpolation even
     * when the new pose follows an identical one.
     */
    public static Object pose(int id, int ticks, Vector3fc translation, Quaternionfc rotation) {
        try {
            List<Object> values = new ArrayList<>(4);
            values.add(value(START_DELTA_KEY, 0));
            values.add(value(DURATION_KEY, ticks));
            values.add(value(TRANSLATION_KEY, translation));
            values.add(value(ROTATION_KEY, rotation));
            return SET_DATA.invoke(id, values);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static Object remove(int[] ids) {
        try {
            return REMOVE.invoke(ids);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Packets the client applies together, within the same tick. */
    public static Object bundle(List<Object> packets) {
        try {
            return BUNDLE.invoke(packets);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static Object value(Object key, Object value) throws Throwable {
        return DATA_VALUE.invoke(key, value);
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    public static void send(Player player, Object packet) {
        try {
            Object connection = CONNECTION.invoke(PLAYER_HANDLE.invoke(player));
            if (connection != null)
                SEND.invoke(connection, packet);
        } catch (Throwable t) {
            // A player who just disconnected has no connection any more; nothing to show them.
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e)
            return e;
        if (t instanceof Error e)
            throw e;
        return new IllegalStateException(t);
    }

}
