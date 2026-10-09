package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Something is about to sit in one of a model's seats. Cancel to keep them off. */
public class ModelMountEvent extends ModelEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Entity rider;
    private final String seat;
    private boolean cancelled;

    public ModelMountEvent(ModelInstance model, Entity rider, String seat) {
        super(model);
        this.rider = rider;
        this.seat = seat;
    }

    public Entity getRider() {
        return rider;
    }

    public String getSeat() {
        return seat;
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
