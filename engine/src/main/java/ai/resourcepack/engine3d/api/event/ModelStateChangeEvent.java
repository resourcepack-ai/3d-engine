package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A model has started playing a different state: fired the tick the new loop
 * begins, after any transition into it has played out.
 */
public class ModelStateChangeEvent extends ModelEvent {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String from, to;

    public ModelStateChangeEvent(ModelInstance model, String from, String to) {
        super(model);
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
