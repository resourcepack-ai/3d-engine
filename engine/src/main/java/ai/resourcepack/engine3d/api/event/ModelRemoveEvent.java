package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A model is about to be taken out of the world for good. Not fired when its
 * chunk merely unloads.
 */
public class ModelRemoveEvent extends ModelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public ModelRemoveEvent(ModelInstance model) {
        super(model);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
