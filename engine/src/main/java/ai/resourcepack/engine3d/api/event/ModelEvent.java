package ai.resourcepack.engine3d.api.event;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.event.Event;

/** Something that happened to a model in the world. */
public abstract class ModelEvent extends Event {

    private final ModelInstance model;

    protected ModelEvent(ModelInstance model) {
        this.model = model;
    }

    public ModelInstance getModel() {
        return model;
    }
}
