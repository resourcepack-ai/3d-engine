package ai.resourcepack.pool3d.game;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Holds the shot camera perfectly still while the mouse strokes the cue.
 *
 * <p>The camera is the player's own third-person view, so it turns with
 * every movement of their mouse, and putting the view back from the server
 * arrives a tick late: the picture shook. Instead, while S (or W) is held,
 * the client is told to look through a stand-in, a player that exists only
 * on that one client, invisible, standing exactly where the shooter's eyes
 * are and turned exactly as they were. The client keeps turning the real
 * player with the mouse and keeps sending those turns (a seated player sends
 * them every tick whatever it looks through), which is what strokes the cue;
 * nothing it does moves the stand-in. Letting go puts the real player's view
 * back where it was and the camera back on them, in one packet each, so the
 * picture never moves.
 *
 * <p>It has to be a player, because the client narrows the field of view
 * (the lens) only for a player camera: for a stand-in that is not one it
 * would widen to the player's own setting. A remote player's walking speed
 * cannot be set, so its lens can be no narrower than a half; the shot
 * camera uses exactly a half, which is also where this one sits with a
 * speed of 0.
 *
 * <p>All of it is packets the API has no words for, sent with Paper's own
 * (Mojang-named) classes. Elsewhere {@link #available()} is false and the
 * table falls back to putting the view back each tick.
 */
final class StandIn {

    private static boolean looked, works;
    private static Method getHandle, send, levelOf, setId, dataCreate;
    private static Field connection;
    private static Constructor<?> headNew, profileNew, entryNew, infoNew, addNew, teleportNew, moveRotNew, attrNew, attributesNew,
            dataNew, cameraNew, markerNew, removeNew, infoRemoveNew;
    private static Object addPlayer, survival, playerType, markerType, vec3Zero, sharedFlags, scaleAttr, cameraAttr, speedAttr;
    private static Class<?> vec3Class;

    static synchronized boolean available(Logger log) {
        if (looked) return works;
        looked = true;
        StandIn.log = log;
        try {
            ClassLoader cl = Class.forName("net.minecraft.server.level.ServerPlayer").getClassLoader();
            Class<?> serverPlayer = cl.loadClass("net.minecraft.server.level.ServerPlayer");
            Class<?> entity = cl.loadClass("net.minecraft.world.entity.Entity");
            Class<?> level = cl.loadClass("net.minecraft.world.level.Level");
            Class<?> packet = cl.loadClass("net.minecraft.network.protocol.Packet");
            Class<?> entityType = cl.loadClass("net.minecraft.world.entity.EntityType");
            vec3Class = cl.loadClass("net.minecraft.world.phys.Vec3");
            Class<?> profile = cl.loadClass("com.mojang.authlib.GameProfile");
            Class<?> gameType = cl.loadClass("net.minecraft.world.level.GameType");
            Class<?> component = cl.loadClass("net.minecraft.network.chat.Component");
            Class<?> chat = cl.loadClass("net.minecraft.network.chat.RemoteChatSession$Data");
            Class<?> info = cl.loadClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Class<?> entry = cl.loadClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            Class<?> action = cl.loadClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            Class<?> moveRot = cl.loadClass("net.minecraft.world.entity.PositionMoveRotation");
            Class<?> holder = cl.loadClass("net.minecraft.core.Holder");
            Class<?> attrInstance = cl.loadClass("net.minecraft.world.entity.ai.attributes.AttributeInstance");
            Class<?> attributes = cl.loadClass("net.minecraft.world.entity.ai.attributes.Attributes");
            Class<?> accessor = cl.loadClass("net.minecraft.network.syncher.EntityDataAccessor");
            Class<?> dataValue = cl.loadClass("net.minecraft.network.syncher.SynchedEntityData$DataValue");

            getHandle = Class.forName(org.bukkit.Bukkit.getServer().getClass().getPackageName() + ".entity.CraftPlayer").getMethod("getHandle");
            connection = serverPlayer.getField("connection");
            send = connection.getType().getMethod("send", packet);
            levelOf = entity.getMethod("level");
            setId = entity.getMethod("setId", int.class);

            profileNew = profile.getConstructor(UUID.class, String.class);
            entryNew = entry.getConstructor(UUID.class, profile, boolean.class, int.class, gameType, component, boolean.class, int.class, chat);
            infoNew = info.getConstructor(EnumSet.class, entry);
            addPlayer = action.getField("ADD_PLAYER").get(null);
            survival = gameType.getField("SURVIVAL").get(null);

            addNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundAddEntityPacket").getConstructor(int.class, UUID.class,
                    double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3Class, double.class);
            // The types' constants moved from EntityType to EntityTypes in 26.1.
            playerType = constant(cl, "PLAYER", "net.minecraft.world.entity.EntityTypes", "net.minecraft.world.entity.EntityType");
            markerType = constant(cl, "MARKER", "net.minecraft.world.entity.EntityTypes", "net.minecraft.world.entity.EntityType");
            vec3Zero = vec3Class.getField("ZERO").get(null);
            moveRotNew = moveRot.getConstructor(vec3Class, vec3Class, float.class, float.class);
            teleportNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket")
                    .getConstructor(int.class, moveRot, Set.class, boolean.class);

            attrNew = attrInstance.getConstructor(holder, Consumer.class);
            attributesNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket")
                    .getConstructor(int.class, java.util.Collection.class);
            scaleAttr = attributes.getField("SCALE").get(null);
            cameraAttr = attributes.getField("CAMERA_DISTANCE").get(null);
            speedAttr = attributes.getField("MOVEMENT_SPEED").get(null);

            Field flags = entity.getDeclaredField("DATA_SHARED_FLAGS_ID");
            flags.setAccessible(true);
            sharedFlags = flags.get(null);
            dataCreate = dataValue.getMethod("create", accessor, Object.class);
            dataNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket").getConstructor(int.class, List.class);

            cameraNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundSetCameraPacket").getConstructor(entity);
            headNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket").getConstructor(entity, byte.class);
            markerNew = cl.loadClass("net.minecraft.world.entity.Marker").getConstructor(entityType, level);
            removeNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
            infoRemoveNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket").getConstructor(List.class);
            works = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.info("The still stroke camera needs Paper; the view is put back each tick instead (" + e + ").");
        }
        return works;
    }

    private static Object constant(ClassLoader cl, String field, String... classes) throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (String c : classes) {
            try {
                return cl.loadClass(c).getField(field).get(null);
            } catch (ReflectiveOperationException e) {
                last = e;
            }
        }
        throw last;
    }

    private static Logger log;

    private final Player viewer;
    private final int id = 0x7F000000 | ThreadLocalRandom.current().nextInt(0x00FFFFFF);
    private final UUID uuid = UUID.randomUUID();
    private boolean spawned, shown;
    private double sentDistance = -1;
    private int sentHead = Integer.MIN_VALUE;
    /** How far above the camera the stand-in waits: far enough that a move to it, or from it, is taken at once. */
    private static final double PARKED = 100;

    StandIn(Player viewer) {
        this.viewer = viewer;
    }

    boolean shown() {
        return shown;
    }

    Player viewer() {
        return viewer;
    }

    /**
     * Makes the stand-in on the viewer's client, parked out of sight above
     * {@code eye}, ahead of the stroke that needs it. The client only takes a
     * new size on its next tick, so a stand-in made on the spot is seen for a
     * frame at a player's full size, the camera flung back by sixteen times
     * its distance; made early, it is ready.
     */
    void prepare(Location eye, double scale, double cameraDistance) {
        if (spawned) return;
        try {
            Object handle = getHandle.invoke(viewer);
            Object profile = profileNew.newInstance(uuid, "pool-camera");
            Object entry = entryNew.newInstance(uuid, profile, false, 0, survival, null, false, 0, null);
            send(handle, infoNew.newInstance(enumSet(addPlayer), entry));
            int head = headStep(viewer.getLocation().getYaw());
            send(handle, addNew.newInstance(id, uuid, eye.getX(), eye.getY() + PARKED, eye.getZ(), 0f, 0f, playerType, 0, vec3Zero,
                    head * STEP + STEP / 4));
            sentHead = head;
            send(handle, dataNew.newInstance(id, List.of(dataCreate.invoke(null, sharedFlags, (byte) 0x20))));
            List<Object> attrs = new ArrayList<>();
            attrs.add(attribute(scaleAttr, scale));
            attrs.add(attribute(cameraAttr, cameraDistance));
            attrs.add(attribute(speedAttr, 0));
            send(handle, attributesNew.newInstance(id, attrs));
            sentDistance = cameraDistance;
            spawned = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /**
     * Brings the stand-in to {@code eye}, turned to {@code yaw} and
     * {@code pitch}, and makes it the viewer's camera: {@code scale} and
     * {@code cameraDistance} are the shooter's own, so its third-person view
     * is theirs. From where it waits the move is long, so the client takes it
     * at once and to the exact turn (a short one it would ease, and a spawn's
     * turn is rounded to 1.4 degrees).
     */
    void show(Location eye, float yaw, float pitch, double scale, double cameraDistance) {
        if (shown) hide(null);
        prepare(eye, scale, cameraDistance);
        if (!spawned) return;
        try {
            Object handle = getHandle.invoke(viewer);
            if (Math.abs(cameraDistance - sentDistance) > 1e-6) {
                send(handle, attributesNew.newInstance(id, List.of(attribute(cameraAttr, cameraDistance))));
                sentDistance = cameraDistance;
            }
            move(handle, eye.getX(), eye.getY() - 1.62 * scale, eye.getZ(), yaw, pitch);
            Object marker = markerNew.newInstance(markerType, levelOf.invoke(handle));
            setId.invoke(marker, id);
            send(handle, cameraNew.newInstance(marker));
            shown = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /**
     * The camera back on the viewer, and the stand-in parked again. When
     * {@code view} is given ({yaw, pitch}), the viewer's own view is put
     * there first, so the camera comes back to exactly the picture it left.
     */
    void hide(float[] view) {
        if (!shown) return;
        shown = false;
        if (view != null) viewer.setRotation(view[0], view[1]);
        try {
            Object handle = getHandle.invoke(viewer);
            send(handle, cameraNew.newInstance(handle));
            Location at = viewer.getEyeLocation();
            move(handle, at.getX(), at.getY() + PARKED, at.getZ(), 0f, 0f);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /** Takes the stand-in off the viewer's client altogether. */
    void remove() {
        if (shown) hide(null);
        if (!spawned) return;
        spawned = false;
        try {
            Object handle = getHandle.invoke(viewer);
            send(handle, removeNew.newInstance((Object) new int[]{id}));
            send(handle, infoRemoveNew.newInstance(List.of(uuid)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /**
     * How a player camera faces left and right is its head's turn, and the
     * game sends a head's turn only in 256ths of a circle (a move's own turn,
     * exact as it is, does not reach the head). So the stand-in's head is
     * kept on the step nearest the shooter's view while they aim: the head
     * eases onto a new step over three ticks, and this way it is there before
     * S is pressed. Off by at most 0.7 degrees, the camera turns about the eye
     * directly over the cue ball, so the cue ball and the cue stay exactly
     * where they were and only the far end of the table moves, by less.
     */
    void face(float yaw) {
        if (!spawned || shown) return;
        int head = headStep(yaw);
        if (head == sentHead) return;
        try {
            Object handle = getHandle.invoke(viewer);
            Object marker = markerNew.newInstance(markerType, levelOf.invoke(handle));
            setId.invoke(marker, id);
            send(handle, headNew.newInstance(marker, (byte) head));
            sentHead = head;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    private static final double STEP = 360.0 / 256;

    private static int headStep(float yaw) {
        return (int) Math.round(yaw / STEP) & 0xFF;
    }

    private void move(Object handle, double x, double y, double z, float yaw, float pitch) throws ReflectiveOperationException {
        Object to = vec3Class.getConstructor(double.class, double.class, double.class).newInstance(x, y, z);
        send(handle, teleportNew.newInstance(id, moveRotNew.newInstance(to, vec3Zero, yaw, pitch), Set.of(), false));
    }

    private static void fail(Exception e) {
        works = false;
        if (log != null) log.warning("The still stroke camera failed, so the view is put back each tick instead: "
                + (e instanceof java.lang.reflect.InvocationTargetException t && t.getCause() != null ? t.getCause() : e));
    }

    private static Object attribute(Object holder, double value) throws ReflectiveOperationException {
        Consumer<Object> none = a -> {
        };
        Object a = attrNew.newInstance(holder, none);
        a.getClass().getMethod("setBaseValue", double.class).invoke(a, value);
        return a;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EnumSet enumSet(Object value) {
        return EnumSet.of((Enum) value);
    }

    private static void send(Object handle, Object packet) throws ReflectiveOperationException {
        send.invoke(connection.get(handle), packet);
    }
}
