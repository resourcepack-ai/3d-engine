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
    private static Constructor<?> bundleNew, profileNew, entryNew, infoNew, addNew, teleportNew, moveRotNew, attrNew, attributesNew,
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
            bundleNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundBundlePacket").getConstructor(Iterable.class);
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
     * {@code eye}, ahead of the stroke that needs it.
     */
    void prepare(Location eye, double scale, double cameraDistance) {
        if (spawned) return;
        try {
            Object handle = getHandle.invoke(viewer);
            List<Object> out = new ArrayList<>();
            Object profile = profileNew.newInstance(uuid, "pool-camera");
            Object entry = entryNew.newInstance(uuid, profile, false, 0, survival, null, false, 0, null);
            out.add(infoNew.newInstance(enumSet(addPlayer), entry));
            spawn(out, eye, headStep(viewer.getLocation().getYaw()), scale, cameraDistance);
            bundle(handle, out);
            spawned = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /**
     * How a player camera faces left and right is its head's turn, and the
     * game sends a head's turn only in 256ths of a circle; a new turn it
     * eases into over three ticks, and only a new entity takes its turn at
     * once. So while the shooter aims, the stand-in is made again each time
     * their view crosses onto another step, and is facing it already when S
     * goes down.
     */
    void face(Location eye, float yaw, double scale, double cameraDistance) {
        if (!spawned || shown) return;
        int head = headStep(yaw);
        if (head == sentHead) return;
        try {
            List<Object> out = new ArrayList<>();
            out.add(removeNew.newInstance((Object) new int[]{id}));
            spawn(out, eye, head, scale, cameraDistance);
            bundle(getHandle.invoke(viewer), out);
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /** The stand-in, parked, facing head step {@code head}, invisible and the shooter's size. */
    private void spawn(List<Object> out, Location eye, int head, double scale, double cameraDistance) throws ReflectiveOperationException {
        out.add(addNew.newInstance(id, uuid, eye.getX(), eye.getY() + PARKED, eye.getZ(), 0f, 0f, playerType, 0, vec3Zero,
                head * STEP + STEP / 4));
        out.add(dataNew.newInstance(id, List.of(dataCreate.invoke(null, sharedFlags, (byte) 0x20))));
        List<Object> attrs = new ArrayList<>();
        attrs.add(attribute(scaleAttr, scale));
        attrs.add(attribute(cameraAttr, cameraDistance));
        attrs.add(attribute(speedAttr, 0));
        out.add(attributesNew.newInstance(id, attrs));
        sentHead = head;
        sentDistance = cameraDistance;
    }

    /**
     * Makes the stand-in the viewer's camera, standing in for eyes at
     * {@code eye} turned to {@code yaw} and {@code pitch}: {@code scale} and
     * {@code cameraDistance} are the shooter's own, so its third-person view
     * is theirs. From where it waits the move is long, so the client takes it
     * at once and to the exact tilt. All of it goes in one bundle, which the
     * client takes between two frames.
     *
     * <p>It can face only its head's step, up to 0.7 degrees off the view.
     * Turned about the eye over the cue ball, the far end of the table would
     * swing past the cue, so the aim would look off. Instead the camera is
     * put exactly where the shooter's was, so the turn is all that differs:
     * the whole picture slides that hair sideways and everything in it, the
     * cue on the line of the shot and the ball it is aimed at, stays lined up.
     */
    void show(Location eye, float yaw, float pitch, double scale, double cameraDistance) {
        if (shown) hide(null);
        prepare(eye, scale, cameraDistance);
        if (!spawned) return;
        try {
            Object handle = getHandle.invoke(viewer);
            List<Object> out = new ArrayList<>();
            int head = headStep(yaw);
            if (head != sentHead) {
                out.add(removeNew.newInstance((Object) new int[]{id}));
                spawn(out, eye, head, scale, cameraDistance);
            } else if (Math.abs(cameraDistance - sentDistance) > 1e-6) {
                out.add(attributesNew.newInstance(id, List.of(attribute(cameraAttr, cameraDistance))));
                sentDistance = cameraDistance;
            }
            float faced = (float) (head * STEP);
            double d = scale * cameraDistance;
            double[] want = forward(yaw, pitch), got = forward(faced, pitch);
            out.add(move(eye.getX() + d * (got[0] - want[0]), eye.getY() + d * (got[1] - want[1]) - 1.62 * scale,
                    eye.getZ() + d * (got[2] - want[2]), faced, pitch));
            Object marker = markerNew.newInstance(markerType, levelOf.invoke(handle));
            setId.invoke(marker, id);
            out.add(cameraNew.newInstance(marker));
            bundle(handle, out);
            shown = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail(e);
        }
    }

    /** Where a camera turned to (yaw, pitch) looks, as the game works it out. */
    private static double[] forward(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
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
            Location at = viewer.getEyeLocation();
            bundle(handle, List.of(cameraNew.newInstance(handle),
                    move(at.getX(), at.getY() + PARKED, at.getZ(), (float) (sentHead * STEP), 0f)));
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

    private static final double STEP = 360.0 / 256;

    private static int headStep(float yaw) {
        return (int) Math.round(yaw / STEP) & 0xFF;
    }

    private Object move(double x, double y, double z, float yaw, float pitch) throws ReflectiveOperationException {
        Object to = vec3Class.getConstructor(double.class, double.class, double.class).newInstance(x, y, z);
        return teleportNew.newInstance(id, moveRotNew.newInstance(to, vec3Zero, yaw, pitch), Set.of(), false);
    }

    private static void bundle(Object handle, List<Object> packets) throws ReflectiveOperationException {
        send(handle, bundleNew.newInstance(packets));
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
