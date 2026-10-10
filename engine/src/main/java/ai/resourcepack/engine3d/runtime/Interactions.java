package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.event.ModelDamageEvent;
import ai.resourcepack.engine3d.api.event.ModelInteractEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns clicks and blows on any part of a model (its hitboxes, its brain, a
 * barrier of a solid one) into {@link ModelInteractEvent} and
 * {@link ModelDamageEvent}, seats a player who right-clicks a model with
 * seats, and lets riders out.
 */
final class Interactions implements Listener {

    private final Engine engine;
    /** The tick each player last clicked a model, so one click never counts twice. */
    private final Map<UUID, Long> lastClick = new HashMap<>();

    /** Whether a swing is heard before it lands (Paper); otherwise players are handled with the damage below. */
    private final boolean swings;
    /** Set while a seat's riders are lifted off and put back to move it, so that is not taken for getting out. */
    static boolean reseating;

    Interactions(Engine engine, org.bukkit.plugin.Plugin plugin) {
        this.engine = engine;
        this.swings = Platform.CURRENT.listenForSwings(plugin, (player, attacked) -> {
            Instance model = engine.owner(attacked);
            if (model == null) return false;
            hit(model, player, player, attack(player));
            return true;
        });
    }

    private void clicked(Player p, Instance model, EquipmentSlot hand) {
        long now = p.getWorld().getGameTime();
        Long last = lastClick.put(p.getUniqueId(), now);
        if (last != null && last == now) return;
        if (!engine.call(new ModelInteractEvent(model, p, hand))) return;
        if (!p.isSneaking() && !model.model.spec.seats.isEmpty() && p.hasPermission("3dengine.ride")) model.mount(p, null);
    }

    private void hit(Instance model, Entity damager, Player player, double damage) {
        engine.callEvent(new ModelDamageEvent(model, damager, player, damage));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent e) {
        Instance model = engine.owner(e.getRightClicked());
        if (model == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) clicked(e.getPlayer(), model, e.getHand());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlock(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getAction() == Action.PHYSICAL) return;
        Instance model = engine.solidOwner(b);
        if (model == null) return;
        e.setCancelled(true);
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) hit(model, e.getPlayer(), e.getPlayer(), attack(e.getPlayer()));
        else if (e.getHand() == EquipmentSlot.HAND) clicked(e.getPlayer(), model, EquipmentSlot.HAND);
    }

    // Creative players break barriers like anything else.
    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        if (engine.solidOwner(e.getBlock()) != null) e.setCancelled(true);
    }

    // Arrows and mobs, and on Spigot players too (Paper hears a player's swing before it lands).
    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (swings && e.getDamager() instanceof Player) return;
        Instance model = engine.owner(e.getEntity());
        if (model == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) {
            hit(model, p, p, attack(p));
            return;
        }
        Player shooter = null;
        if (e.getDamager() instanceof Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Player p) shooter = p;
        }
        hit(model, e.getDamager(), shooter, e.getDamage());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDismount(EntityDismountEvent e) {
        Instance model = engine.owner(e.getDismounted());
        if (model == null || reseating) return;
        if (model.leaving(e.getEntity(), Platform.CURRENT.cancellable(e))) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastClick.remove(e.getPlayer().getUniqueId());
        Entity vehicle = e.getPlayer().getVehicle();
        Instance model = vehicle == null ? null : engine.owner(vehicle);
        if (model != null) model.dismount(e.getPlayer());
    }

    private static double attack(Player p) {
        var a = p.getAttribute(Attribute.ATTACK_DAMAGE);
        return a == null ? 1 : a.getValue();
    }
}
