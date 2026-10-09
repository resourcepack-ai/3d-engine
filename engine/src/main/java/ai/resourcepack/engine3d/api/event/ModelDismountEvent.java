package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Something is getting out of one of a model's seats. Cancelling keeps them in
 * when they asked to get out (by sneaking); when they are leaving regardless
 * (quitting, dying, teleporting, the model going) {@link #isCancellable()} is
 * false and cancelling does nothing.
 */
public class ModelDismountEvent extends ModelEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Entity rider;
    private final String seat;
    private final boolean cancellable;
    private boolean cancelled;

    public ModelDismountEvent(ModelInstance model, Entity rider, String seat, boolean cancellable) {
        super(model);
        this.rider = rider;
        this.seat = seat;
        this.cancellable = cancellable;
    }

    public Entity getRider() {
        return rider;
    }

    public String getSeat() {
        return seat;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel && cancellable;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
