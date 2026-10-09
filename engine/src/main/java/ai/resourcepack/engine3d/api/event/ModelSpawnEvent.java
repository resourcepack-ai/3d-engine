package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A model has been put in the world, by a command, a plugin or a placing item.
 * Not fired for placed models coming back as their chunk loads.
 */
public class ModelSpawnEvent extends ModelEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public ModelSpawnEvent(ModelInstance model) {
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
