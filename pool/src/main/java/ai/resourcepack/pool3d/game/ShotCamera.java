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
 * The shooter's eyes. Minecraft has no camera a server can place, so the
 * player rides an invisible seat and the seat is moved so that their eyes are
 * where the camera should be: low behind the cue ball, looking down the cue.
 * Their own mouse still turns their head, which is what aims.
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
            if (off > 0.3 && off < 2.5) {
                measured = Math.abs(off - eyeAbove) < 1e-4;
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

    private double fov = 1;

    /**
     * Narrows the player's view to {@code m} of their own field of view (a
     * telephoto at 0.34: Shooterspool's view down the cue is about 24
     * degrees). A server cannot set a player's field of view, but the client
     * scales it by their speed over their walking speed, as sprinting
     * widens it; a negative walking speed turns that the other way. It is
     * clamped to between 0.1 and 1.5 there, eased over a few frames, and
     * only felt with the FOV Effects option on (it is, by default).
     *
     * <p>Only between 0.12 and 0.4, or 1 (their own): nearer a half, the
     * walking speed it takes is beyond what the game allows.
     */
    void fov(double m) {
        m = m >= 0.7 ? 1 : Math.max(0.12, Math.min(0.4, m));
        if (Math.abs(m - fov) < 1e-4) return;
        fov = m;
        if (m == 1) {
            player.setWalkSpeed(0.2f);
            return;
        }
        var attr = player.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        double speed = attr == null ? 0.1 : attr.getValue();
        // The client divides by half of Bukkit's walking speed.
        float walk = (float) (2 * speed / (2 * m - 1));
        player.setWalkSpeed(Math.max(-1f, Math.min(1f, walk)));
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
        if (fov != 1) player.setWalkSpeed(0.2f);
        fov = 1;
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
