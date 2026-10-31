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
    private static Constructor<?> profileNew, entryNew, infoNew, addNew, teleportNew, moveRotNew, attrNew, attributesNew,
            dataNew, cameraNew, markerNew, removeNew, infoRemoveNew;
    private static Object addPlayer, survival, playerType, markerType, vec3Zero, sharedFlags, scaleAttr, cameraAttr, speedAttr;
    private static Class<?> vec3Class;

    static synchronized boolean available(Logger log) {
        if (looked) return works;
        looked = true;
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
            playerType = entityType.getField("PLAYER").get(null);
            markerType = entityType.getField("MARKER").get(null);
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
            markerNew = cl.loadClass("net.minecraft.world.entity.Marker").getConstructor(entityType, level);
            removeNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket").getConstructor(int[].class);
            infoRemoveNew = cl.loadClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket").getConstructor(List.class);
            works = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.info("The still stroke camera needs Paper; the view is put back each tick instead (" + e + ").");
        }
        return works;
    }

    private final Player viewer;
    private final int id = 0x7F000000 | ThreadLocalRandom.current().nextInt(0x00FFFFFF);
    private final UUID uuid = UUID.randomUUID();
    private boolean shown;

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
     * Shows the stand-in with its eyes at {@code eye}, turned to {@code yaw}
     * and {@code pitch}, and makes it the viewer's camera: {@code scale} and
     * {@code cameraDistance} are the shooter's own, so its third-person view
     * is theirs.
     */
    void show(Location eye, float yaw, float pitch, double scale, double cameraDistance) {
        if (shown) hide(null);
        try {
            Object handle = getHandle.invoke(viewer);
            Object profile = profileNew.newInstance(uuid, "pool-camera");
            Object entry = entryNew.newInstance(uuid, profile, false, 0, survival, null, false, 0, null);
            send(handle, infoNew.newInstance(enumSet(addPlayer), entry));
            double feet = eye.getY() - 1.62 * scale;
            // Spawned out of the way and then moved into place: a move that
            // long is taken at once and exactly, where a short one is eased
            // and a spawn's turn is rounded to 1.4 degrees.
            send(handle, addNew.newInstance(id, uuid, eye.getX(), feet + 100, eye.getZ(), pitch, yaw, playerType, 0, vec3Zero, (double) yaw));
            send(handle, dataNew.newInstance(id, List.of(dataCreate.invoke(null, sharedFlags, (byte) 0x20))));
            List<Object> attrs = new ArrayList<>();
            attrs.add(attribute(scaleAttr, scale));
            attrs.add(attribute(cameraAttr, cameraDistance));
            attrs.add(attribute(speedAttr, 0));
            send(handle, attributesNew.newInstance(id, attrs));
            Object to = vec3Class.getConstructor(double.class, double.class, double.class).newInstance(eye.getX(), feet, eye.getZ());
            send(handle, teleportNew.newInstance(id, moveRotNew.newInstance(to, vec3Zero, yaw, pitch), Set.of(), false));
            Object marker = markerNew.newInstance(markerType, levelOf.invoke(handle));
            setId.invoke(marker, id);
            send(handle, cameraNew.newInstance(marker));
            shown = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            works = false;
        }
    }

    /**
     * The camera back on the viewer, and the stand-in gone. When {@code view}
     * is given ({yaw, pitch}), the viewer's own view is put there first, so
     * the camera comes back to exactly the picture it left.
     */
    void hide(float[] view) {
        if (!shown) return;
        shown = false;
        if (view != null) viewer.setRotation(view[0], view[1]);
        try {
            Object handle = getHandle.invoke(viewer);
            send(handle, cameraNew.newInstance(handle));
            send(handle, removeNew.newInstance((Object) new int[]{id}));
            send(handle, infoRemoveNew.newInstance(List.of(uuid)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            works = false;
        }
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
