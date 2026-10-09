package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A model was hit: punched by a player, or struck by a projectile or a mob.
 * A model has no health of its own, so nothing happens unless a plugin makes
 * it; cancelling only tells the plugins listening after you.
 */
public class ModelDamageEvent extends ModelEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Entity damager;
    private final Player player;
    private final double damage;
    private boolean cancelled;

    public ModelDamageEvent(ModelInstance model, @Nullable Entity damager, @Nullable Player player, double damage) {
        super(model);
        this.damager = damager;
        this.player = player;
        this.damage = damage;
    }

    /** What struck it: a player, an arrow, a mob. */
    public @Nullable Entity getDamager() {
        return damager;
    }

    /** The player behind it (who punched, or shot the arrow), if any. */
    public @Nullable Player getPlayer() {
        return player;
    }

    /** What the blow would have dealt a mob. */
    public double getDamage() {
        return damage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
