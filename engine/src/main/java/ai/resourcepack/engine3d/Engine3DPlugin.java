package ai.resourcepack.engine3d;

import ai.resourcepack.engine3d.api.Engine3D;
import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.engine3d.api.Mover;
import ai.resourcepack.engine3d.runtime.Engine;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/** The plugin: everything it does lives in {@link Engine}; this hands it the jar and the API. */
public final class Engine3DPlugin extends JavaPlugin implements Engine3D {

    private Engine runtime;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        runtime = new Engine(this, getFile().toPath());
        runtime.enable();
        Engine3D.Holder.set(this);
    }

    @Override
    public void onDisable() {
        Engine3D.Holder.set(null);
        if (runtime != null) runtime.disable();
    }

    @Override
    public boolean ready() {
        return runtime.ready();
    }

    @Override
    public void whenReady(Runnable task) {
        runtime.whenReady(task);
    }

    @Override
    public Optional<Model> model(String name) {
        return runtime.model(name);
    }

    @Override
    public Collection<Model> models() {
        return runtime.models();
    }

    @Override
    public ModelInstance spawn(String model, Location at) {
        return runtime.spawn(model, at, false);
    }

    @Override
    public ModelInstance place(String model, Location at) {
        return runtime.spawn(model, at, true);
    }

    @Override
    public Mover mover(ModelInstance body, EntityType brain) {
        return runtime.mover(body, brain);
    }

    @Override
    public org.bukkit.inventory.ItemStack item(String model, String state) {
        return runtime.item(model, state);
    }

    @Override
    public java.util.Set<String> effects() {
        return runtime.effectNames();
    }

    @Override
    public ai.resourcepack.engine3d.api.EffectInstance effect(String name, Location at, double size) {
        return runtime.effect(name, at, size);
    }

    @Override
    public ai.resourcepack.engine3d.api.EffectInstance effect(String name, Location at, double size, org.bukkit.entity.Player except) {
        return runtime.effect(name, at, size, except);
    }

    @Override
    public ai.resourcepack.engine3d.api.EffectInstance viewModelEffect(String name, org.bukkit.entity.Player viewer, Location at, double size) {
        return runtime.viewModelEffect(name, viewer, at, size);
    }

    @Override
    public ai.resourcepack.engine3d.api.Particle particle(String name, Location at, org.bukkit.util.Vector velocity,
                                                          ai.resourcepack.engine3d.api.Particle.Options options) {
        return runtime.particle(name, at, velocity, options);
    }

    @Override
    public Collection<ModelInstance> instances() {
        return runtime.instances();
    }

    @Override
    public Optional<ModelInstance> instance(Entity entity) {
        return runtime.instance(entity);
    }

    @Override
    public Path modelsFolder() {
        return runtime.modelsFolder();
    }
}
