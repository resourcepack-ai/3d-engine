package ai.resourcepack.engine3d;

import ai.resourcepack.engine3d.api.Engine3D;
import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.engine3d.api.Mover;
import ai.resourcepack.engine3d.runtime.Engine;
import ai.resourcepack.common.update.UpdateCheck;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/** The plugin: everything it does lives in {@link Engine}; this hands it the jar and the API. */
public final class Engine3DPlugin extends JavaPlugin implements Engine3D {

    private Engine runtime;

    private ai.resourcepack.common.telemetry.Heartbeat heartbeat;

    /** This plugin's SpigotMC resource id: the number in its page's URL. */
    private static final int SPIGOT_RESOURCE = 139510;

    private UpdateCheck updates;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Running on " + ai.resourcepack.engine3d.runtime.platform.Platform.CURRENT.name() + ".");
        runtime = new Engine(this, getFile().toPath());
        runtime.enable();
        Engine3D.Holder.set(this);
        // Anonymous usage counting, the only telemetry this plugin has: a
        // random id, the online player count and the names of plugins that
        // depend on this one (3DPets and the rest). See Heartbeat for exactly
        // what goes.
        heartbeat = ai.resourcepack.common.telemetry.Heartbeat.of(this, "3d-engine")
                .enabledWhen(() -> getConfig().getBoolean("telemetry", true))
                .start();
        updates = UpdateCheck.spigot(this, SPIGOT_RESOURCE)
                .name("3D Engine")
                .permission("3dengine.update")
                .enabledWhen(() -> getConfig().getBoolean("update-check", true))
                .player(this::tellUpdate)
                .start();
    }

    /** The join nudge: the sentence, then the link as one clickable piece. */
    private void tellUpdate(Player player, UpdateCheck.Update update) {
        BaseComponent words = TextComponent.fromLegacy(
                ChatColor.AQUA + "3D " + ChatColor.GRAY + "3D Engine " + ChatColor.WHITE + update.latest()
                        + ChatColor.GRAY + " is out (you have " + update.running() + "). ");
        TextComponent link = new TextComponent("Get it on SpigotMC");
        link.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        link.setUnderlined(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, update.url()));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.hover.content.Text(update.url())));
        // Both go in a fresh component: a deserialised one's extras can't be added to.
        player.spigot().sendMessage(new TextComponent(words, link));
    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (heartbeat != null) heartbeat.stop();
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
