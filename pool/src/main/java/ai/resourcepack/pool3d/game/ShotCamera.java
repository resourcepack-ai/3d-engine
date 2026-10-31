package ai.resourcepack.pool3d.game;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

/**
 * The shooter's eyes, and Shooterspool's orbit camera.
 *
 * <p>Minecraft has no camera a server can place, and one moved from the
 * server trails the mouse by a round trip, which is what made the first
 * version feel laggy. So the camera is the player's own third-person view
 * (F5): the client keeps it {@code camera_distance} behind their eyes along
 * the way they look, turning it with every frame of the mouse. The player
 * rides an invisible seat that puts their eyes just above the cue ball, and
 * from there the client orbits the camera round it, left and right with the
 * aim and up and down with the tilt, with nothing on the server in the way.
 * The seat only moves when the cue ball does, between shots.
 *
 * <p>The seat is an item display with nothing in it, because a display's
 * moves are smoothed on the client over its teleport duration, and a rider is
 * carried along with that.
 */
final class ShotCamera {

    /**
     * How far a rider's eyes are above their seat: a seated player's feet are
     * 0.6 below it and their eyes 1.62 above their feet. Corrected from where
     * the server says their eyes are, once they sit.
     */
    private double eyeAbove = 1.02;
    private boolean measured;

    final Player player;
    private ItemDisplay seat;
    private Location eye;
    /** Set while the seat is being swapped or moved, so that is not taken for the player getting off. */
    boolean moving;

    ShotCamera(Player player) {
        this.player = player;
    }

    /** Whether {@code e} is this camera's seat. */
    boolean seat(Entity e) {
        return seat != null && seat.equals(e);
    }

    /** Puts the player's eyes at {@code at}, smoothed over {@code ticks}. */
    void to(Location at, int ticks) {
        Location s = at.clone().subtract(0, eyeAbove, 0);
        s.setYaw(0);
        s.setPitch(0);
        if (seat == null || !seat.isValid()) {
            if (seat != null) seat.remove();
            moving = true;
            try {
                if (player.isInsideVehicle()) player.leaveVehicle();
                Location feet = at.clone().subtract(0, 1.62, 0);
                feet.setYaw(player.getLocation().getYaw());
                feet.setPitch(player.getLocation().getPitch());
                player.teleport(feet);
                // Flying widens the view by a tenth, on top of the narrowing.
                if (player.isFlying()) player.setFlying(false);
                seat = s.getWorld().spawn(s, ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.addScoreboardTag("pool3d");
                    d.setTeleportDuration(2);
                });
                seat.addPassenger(player);
            } finally {
                moving = false;
            }
            measured = false;
            eye = at.clone();
            hide(true);
            return;
        }
        if (!seat.getPassengers().contains(player)) {
            moving = true;
            try {
                if (player.isInsideVehicle()) player.leaveVehicle();
                seat.addPassenger(player);
            } finally {
                moving = false;
            }
        }
        if (!measured && player.getVehicle() == seat) {
            // The server has them seated: read off where it puts their eyes.
            double off = player.getEyeLocation().getY() - seat.getLocation().getY();
            if (off > 0.01 && off < 2.5) {
                measured = Math.abs(off - eyeAbove) < 1e-4;
                if (!measured) eye = null; // the seat has to move for the same eyes
                eyeAbove = off;
                s = at.clone().subtract(0, eyeAbove, 0);
            }
        }
        PotionEffect hidden = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (hidden == null || hidden.getDuration() < 40) hide(true);
        if (eye != null && eye.distanceSquared(at) < 1e-10) return;
        eye = at.clone();
        int duration = Math.max(1, Math.min(59, ticks));
        if (seat.getTeleportDuration() != duration) seat.setTeleportDuration(duration);
        moveWithRider(seat, s);
    }

    private double lensDistance = -1, lensFov = 1;
    private Double savedDistance, savedSpeed, savedScale;

    /**
     * The player shrunk as small as the game allows while seated. In third
     * person 26.3 draws an invisible player faintly to themselves, and their
     * head is where the camera looks; at a sixteenth it is a speck. The
     * camera distance is in the player's own size, so it is set 16 times
     * longer to match.
     */
    static final double SCALE = 0.0625;

    /**
     * The orbit's radius, metres, and its field of view, as a share of the
     * player's own ({@code 30 / 70.0} is Shooterspool's default 30 degrees
     * against Minecraft's 70).
     *
     * <p>The radius is the {@code camera_distance} attribute, which is what
     * the third-person view keeps between camera and eyes. The field of view
     * cannot be set from a server at all; but the client scales it by the
     * player's speed over their walking speed (as sprinting widens it), so a
     * walking speed of -1 and a matching speed narrow it instead. The client
     * clamps it to between a tenth and one and a half, eases it over a few
     * frames, and only applies it with the FOV Effects option on, which it is
     * by default. Seated, neither speed moves anybody.
     */
    void lens(double distance, double fov) {
        fov = Math.max(0.12, Math.min(1.4, fov));
        if (Math.abs(distance - lensDistance) > 1e-4) {
            var size = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (size != null && savedScale == null) {
                savedScale = size.getBaseValue();
                size.setBaseValue(SCALE);
                measured = false;
            }
            double scale = size == null ? 1 : SCALE;
            var cam = player.getAttribute(org.bukkit.attribute.Attribute.CAMERA_DISTANCE);
            if (cam != null) {
                if (savedDistance == null) savedDistance = cam.getBaseValue();
                cam.setBaseValue(Math.min(32, distance / scale));
            }
            lensDistance = distance;
        }
        if (Math.abs(fov - lensFov) < 1e-4) return;
        lensFov = fov;
        var speed = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (speed == null) return;
        if (savedSpeed == null) savedSpeed = speed.getBaseValue();
        // The client's share is (speed / walk + 1) / 2, its walk half of Bukkit's.
        if (fov < 0.5) {
            player.setWalkSpeed(-1f);
            speed.setBaseValue(0.5 * (1 - 2 * fov));
        } else {
            player.setWalkSpeed(1f);
            speed.setBaseValue(0.5 * (2 * fov - 1));
        }
    }

    /** Gives the player back their own camera distance, speed and walking speed. */
    private void unlens() {
        var cam = player.getAttribute(org.bukkit.attribute.Attribute.CAMERA_DISTANCE);
        if (cam != null && savedDistance != null) cam.setBaseValue(savedDistance);
        var speed = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (speed != null && savedSpeed != null) speed.setBaseValue(savedSpeed);
        var size = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (size != null && savedScale != null) size.setBaseValue(savedScale);
        if (lensFov != 1 || savedSpeed != null) player.setWalkSpeed(0.2f);
        savedDistance = savedSpeed = savedScale = null;
        lensDistance = -1;
        lensFov = 1;
    }

    /** Where the eyes were last put. */
    Location eye() {
        return eye;
    }

    /** Gets the player off and takes the seat away; they stand at {@code standAt}. */
    void close(Location standAt) {
        moving = true;
        try {
            if (seat != null) {
                seat.eject();
                seat.remove();
            }
            seat = null;
            if (standAt != null && player.isOnline()) {
                Location l = standAt.clone();
                l.setYaw(player.getLocation().getYaw());
                l.setPitch(player.getLocation().getPitch());
                player.teleport(l);
            }
        } finally {
            moving = false;
        }
        hide(false);
        unlens();
    }

    /**
     * The seated body is sunk into the table, and only the eyes are where
     * they should be: nobody else needs to see it.
     */
    private void hide(boolean on) {
        // A few seconds at a time, renewed while seated, so that it wears off
        // by itself if the server stops with somebody in the seat.
        if (on) player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, false));
        else player.removePotionEffect(PotionEffectType.INVISIBILITY);
    }

    // ---- moving a seat with somebody in it --------------------------------------------------

    private static Method paperTeleport;
    private static Object retainPassengers;
    private static Class<?> flagClass;
    private static Method setPos;
    private static boolean looked;

    /**
     * Spigot and Paper both refuse to teleport an entity somebody is riding.
     * Paper does it when asked to keep the passengers; elsewhere the game's
     * own position setter is used, which carries the rider on the next tick;
     * failing both, the rider is lifted off and put back.
     */
    private void moveWithRider(Entity e, Location to) {
        look(e);
        try {
            if (paperTeleport != null) {
                Object flags = Array.newInstance(flagClass, 1);
                Array.set(flags, 0, retainPassengers);
                paperTeleport.invoke(e, to, flags);
                return;
            }
            if (setPos != null) {
                Object handle = e.getClass().getMethod("getHandle").invoke(e);
                setPos.invoke(handle, to.getX(), to.getY(), to.getZ());
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the slow way, below
        }
        moving = true;
        try {
            e.removePassenger(player);
            e.teleport(to);
            e.addPassenger(player);
        } finally {
            moving = false;
        }
    }

    private static synchronized void look(Entity e) {
        if (looked) return;
        looked = true;
        try {
            Class<?> state = Class.forName("io.papermc.paper.entity.TeleportFlag$EntityState");
            Class<?> flag = Class.forName("io.papermc.paper.entity.TeleportFlag");
            retainPassengers = state.getField("RETAIN_PASSENGERS").get(null);
            flagClass = flag;
            paperTeleport = Entity.class.getMethod("teleport", Location.class, flag.arrayType());
            return;
        } catch (ReflectiveOperationException ignored) {
            // not Paper
        }
        try {
            Object handle = e.getClass().getMethod("getHandle").invoke(e);
            for (Class<?> c = handle.getClass(); c != null && setPos == null; c = c.getSuperclass()) {
                for (String name : new String[]{"setPos", "absSnapTo"}) {
                    try {
                        setPos = c.getDeclaredMethod(name, double.class, double.class, double.class);
                        setPos.setAccessible(true);
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
