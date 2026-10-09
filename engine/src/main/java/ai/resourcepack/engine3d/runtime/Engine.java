package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.engine3d.api.Mover;
import ai.resourcepack.engine3d.pack.PackBuilder;
import ai.resourcepack.engine3d.pack.PackServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The running engine: bakes the models folder, serves the pack, keeps every
 * model instance animated and every mover walking, and adopts placed models as
 * their chunks load.
 */
public final class Engine implements Listener {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("3DEngine pack".getBytes(StandardCharsets.UTF_8));

    private final JavaPlugin plugin;
    private final Path jar;
    private final Path folder;
    private final Registry registry;
    private final Effects effects;
    private PackServer server;
    private PackBuilder.Pack pack;
    private String packUrl;

    private volatile Map<String, LoadedModel> models = Map.of();
    private final Map<UUID, Instance> instances = new LinkedHashMap<>();
    /** Heads, hitboxes and seat mounts, to the model they belong to. */
    private final Map<UUID, Instance> parts = new java.util.HashMap<>();
    /** Barrier blocks of solid models, by world and block key. */
    private final Map<UUID, Map<Long, Instance>> solidBlocks = new java.util.HashMap<>();
    private final List<Steering> movers = new ArrayList<>();
    private final List<Runnable> readyTasks = new ArrayList<>();
    private boolean ready, loading;
    private List<String> lastErrors = List.of();

    public Engine(JavaPlugin plugin, Path jar) {
        this.plugin = plugin;
        this.jar = jar;
        this.folder = plugin.getDataFolder().toPath().resolve("models");
        this.registry = new Registry(folder, plugin.getDataFolder().toPath().resolve("cache"), plugin.getLogger());
        this.effects = new Effects(plugin.getDataFolder().toPath().resolve("effects"), plugin.getLogger());
    }

    public void enable() {
        FileConfiguration c = plugin.getConfig();
        try {
            Files.createDirectories(folder);
        } catch (Exception e) {
            plugin.getLogger().warning("Couldn't make the models folder: " + e.getMessage());
        }
        int port = c.getInt("pack.port", 8163);
        if (c.getBoolean("pack.host", true)) {
            try {
                server = new PackServer(c.getString("pack.bind", ""), port);
            } catch (Exception e) {
                plugin.getLogger().severe("Couldn't serve the pack on port " + port + " (" + e.getMessage() + "); set pack.port to a free one");
            }
        }
        String url = c.getString("pack.url", "");
        if (url == null || url.isBlank()) {
            String ip = Bukkit.getIp().isBlank() ? "127.0.0.1" : Bukkit.getIp();
            url = "http://" + ip + ":" + port;
            if (server != null) plugin.getLogger().warning("pack.url isn't set, so players are sent " + url + ": set it to the address players reach this server's port " + port + " on");
        }
        packUrl = url.replaceAll("/+$", "");

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Commands commands = new Commands(this, plugin);
        var cmd = plugin.getCommand("3d");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
        Bukkit.getPluginManager().registerEvents(commands, plugin);
        Bukkit.getPluginManager().registerEvents(new Interactions(this), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        // On the first tick, so a plugin depending on this one has had its
        // onEnable to copy its own models in.
        Bukkit.getScheduler().runTask(plugin, () -> reload(Bukkit.getConsoleSender()));
    }

    public void disable() {
        if (server != null) server.stop();
        effects.clear();
        for (Instance i : new ArrayList<>(instances.values())) {
            i.removeParts();
            if (i.mover != null) i.mover.removeBrain();
            if (!i.display.isPersistent()) i.display.remove();
        }
        instances.clear();
        parts.clear();
        solidBlocks.clear();
        movers.clear();
    }

    // ---- loading ------------------------------------------------------------------

    void reload(CommandSender to) {
        if (loading) {
            to.sendMessage("3D Engine is already baking.");
            return;
        }
        loading = true;
        Registry.Quality quality = quality();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Registry.Loaded loaded = registry.load(quality);
                Effects.Linked linked = effects.link(plugin.getClass().getClassLoader());
                FileConfiguration c = plugin.getConfig();
                List<Integer> f = c.getIntegerList("pack.format");
                int[] format = f.size() >= 2 ? new int[]{f.get(0), f.get(1)} : new int[]{97, 1};
                PackBuilder.Pack built = PackBuilder.build(jar, loaded.entries(), linked.files(), format, c.getString("pack.description", "3D Engine models"));
                Files.write(plugin.getDataFolder().toPath().resolve("pack.zip"), built.zip());
                Bukkit.getScheduler().runTask(plugin, () -> finishLoad(loaded, linked, built, to));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Loading models failed", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading = false;
                    to.sendMessage("3D Engine: loading failed, see the console: " + e.getMessage());
                });
            }
        });
    }

    private void finishLoad(Registry.Loaded loaded, Effects.Linked linked, PackBuilder.Pack built, CommandSender to) {
        models = Collections.unmodifiableMap(new LinkedHashMap<>(loaded.models()));
        List<String> errors = new ArrayList<>(loaded.errors());
        errors.addAll(linked.errors());
        lastErrors = errors;
        effects.clear();
        effects.use(linked.index());
        boolean changed = pack == null || !pack.hex().equals(built.hex());
        pack = built;
        if (server != null) server.serve(built);
        long now = Bukkit.getWorlds().get(0).getGameTime();
        for (Instance i : new ArrayList<>(instances.values())) {
            LoadedModel fresh = models.get(i.model.name());
            if (fresh == null) {
                forget(i);
                i.removeParts();
                if (!i.display.isPersistent()) i.remove();
            } else i.rebind(fresh, now);
        }
        for (World w : Bukkit.getWorlds()) adopt(w.getEntitiesByClass(ItemDisplay.class));
        if (changed && plugin.getConfig().getBoolean("pack.send", true)) for (Player p : Bukkit.getOnlinePlayers()) send(p);
        ready = true;
        loading = false;
        to.sendMessage(String.format("3D Engine: %d model%s and %d effect%s ready%s (pack %.1f MB)", models.size(), models.size() == 1 ? "" : "s",
                linked.index().size(), linked.index().size() == 1 ? "" : "s",
                lastErrors.isEmpty() ? "" : ", " + lastErrors.size() + " failed", built.zip().length / 1048576.0));
        for (String e : lastErrors) to.sendMessage("  " + e);
        for (Runnable r : new ArrayList<>(readyTasks)) {
            try {
                r.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "A ready task failed", e);
            }
        }
    }

    /** The quality settings as they are now, read on the main thread for a bake to use. */
    Registry.Quality quality() {
        FileConfiguration c = plugin.getConfig();
        Map<String, int[]> models = new LinkedHashMap<>();
        var section = c.getConfigurationSection("quality.models");
        if (section != null)
            for (String key : section.getKeys(false)) {
                var m = section.getConfigurationSection(key);
                if (m != null) models.put(key.replace(':', '.'), new int[]{m.getInt("triangles", 0), m.getInt("texture-width", 0)});
            }
        return new Registry.Quality(c.getInt("quality.triangles", 20000), c.getInt("quality.max-triangles", 200000),
                c.getInt("quality.texture-width", 512), c.getInt("quality.max-texture-width", 2048), models);
    }

    /** Sets (or, with zeros, clears) a model's own override, saves it and re-bakes. */
    void setQuality(String model, int triangles, int textureWidth, CommandSender to) {
        String key = "quality.models." + model.replace('.', ':');
        if (triangles <= 0 && textureWidth <= 0) plugin.getConfig().set(key, null);
        else {
            plugin.getConfig().set(key + ".triangles", triangles > 0 ? triangles : null);
            plugin.getConfig().set(key + ".texture-width", textureWidth > 0 ? textureWidth : null);
        }
        plugin.saveConfig();
        reload(to);
    }

    void send(Player p) {
        if (pack == null || server == null && plugin.getConfig().getString("pack.url", "").isBlank()) return;
        String url = packUrl + "/pack/" + pack.hex() + ".zip";
        String prompt = plugin.getConfig().getString("pack.prompt", "");
        p.addResourcePack(PACK_ID, url, pack.sha1(), prompt == null || prompt.isEmpty() ? null : prompt, plugin.getConfig().getBoolean("pack.required", false));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (ready && plugin.getConfig().getBoolean("pack.send", true)) send(e.getPlayer());
    }

    // ---- instances ----------------------------------------------------------------

    public boolean ready() {
        return ready;
    }

    public void whenReady(Runnable task) {
        readyTasks.add(task);
        if (ready) task.run();
    }

    public Optional<Model> model(String name) {
        return Optional.ofNullable(models.get(name));
    }

    public Collection<Model> models() {
        return Collections.unmodifiableCollection(models.values());
    }

    Map<String, LoadedModel> loadedModels() {
        return models;
    }

    List<String> errors() {
        return lastErrors;
    }

    public Path modelsFolder() {
        return folder;
    }

    PackBuilder.Pack pack() {
        return pack;
    }

    public ModelInstance spawn(String name, Location at, boolean persistent) {
        LoadedModel m = models.get(name);
        if (m == null) throw new IllegalArgumentException("No model called " + name + (ready ? "" : " (3D Engine is still loading)"));
        Location to = at.clone();
        to.setPitch(0);
        ItemDisplay d = to.getWorld().spawn(to, ItemDisplay.class, x -> {
            Instance.setUp(x, persistent);
            if (persistent) x.getPersistentDataContainer().set(Instance.MODEL, PersistentDataType.STRING, name);
        });
        Instance i = new Instance(this, m, d, m.defaultState(), to.getWorld().getGameTime());
        instances.put(d.getUniqueId(), i);
        i.build();
        callEvent(new ai.resourcepack.engine3d.api.event.ModelSpawnEvent(i));
        return i;
    }

    public Mover mover(ModelInstance body, EntityType brainType) {
        if (!(body instanceof Instance inst)) throw new IllegalArgumentException("Not a 3D Engine instance");
        if (inst.mover != null && inst.mover.valid()) return inst.mover;
        Class<? extends Entity> type = brainType.getEntityClass();
        if (type == null || !Mob.class.isAssignableFrom(type)) throw new IllegalArgumentException(brainType + " can't walk: pick a mob");
        Location at = inst.location();
        Mob brain = (Mob) at.getWorld().spawn(at, type, e -> ((Mob) e).setInvisible(true));
        Steering s = new Steering(inst, brain);
        Steering.prepare(brain, s, plugin);
        movers.add(s);
        return s;
    }

    public org.bukkit.inventory.ItemStack item(String name, String state) {
        LoadedModel m = models.get(name);
        if (m == null) throw new IllegalArgumentException("No model called " + name);
        var s = m.manifest.states.get(state != null && m.manifest.states.containsKey(state) ? state : m.defaultState());
        // A model whose head is drawn apart shows whole as an item.
        return Animator.item(name, m.manifest.icon != null ? m.manifest.icon : s.model, 0);
    }

    public java.util.Set<String> effectNames() {
        return effects.names();
    }

    public ai.resourcepack.engine3d.api.EffectInstance effect(String name, Location at, double size) {
        return effects.spawn(name, at, size);
    }

    public ai.resourcepack.engine3d.api.EffectInstance effect(String name, Location at, double size, org.bukkit.entity.Player except) {
        return effects.spawn(name, at, size, false, t -> except.hideEntity(plugin, t));
    }

    public ai.resourcepack.engine3d.api.EffectInstance viewModelEffect(String name, org.bukkit.entity.Player viewer, Location at, double size) {
        return effects.spawn(name, at, size, true, t -> {
            t.setVisibleByDefault(false);
            viewer.showEntity(plugin, t);
        });
    }

    public ai.resourcepack.engine3d.api.Particle particle(String name, Location at, org.bukkit.util.Vector velocity,
                                                          ai.resourcepack.engine3d.api.Particle.Options options) {
        return effects.particle(name, at, velocity, options == null ? new ai.resourcepack.engine3d.api.Particle.Options() : options);
    }

    public Collection<ModelInstance> instances() {
        return Collections.unmodifiableCollection(new ArrayList<>(instances.values()));
    }

    public Optional<ModelInstance> instance(Entity e) {
        return Optional.ofNullable(owner(e));
    }

    /** The model {@code e} is, or is part of (its display, head, a hitbox, a seat, its brain); null if none. */
    Instance owner(Entity e) {
        Instance i = instances.get(e.getUniqueId());
        if (i == null) i = parts.get(e.getUniqueId());
        if (i == null) for (Steering s : movers) if (s.brain().equals(e)) i = (Instance) s.body();
        return i == null || i.removed() ? null : i;
    }

    void own(Entity part, Instance owner) {
        parts.put(part.getUniqueId(), owner);
    }

    void disown(Entity part) {
        parts.remove(part.getUniqueId());
    }

    /** Records which blocks are {@code owner}'s barriers (replacing what it had). */
    void solid(Instance owner, long[] keys) {
        Map<Long, Instance> world = solidBlocks.computeIfAbsent(owner.display.getWorld().getUID(), k -> new java.util.HashMap<>());
        world.values().removeIf(x -> x == owner);
        for (long k : keys) world.put(k, owner);
    }

    /** The solid model a block is part of, or null. */
    Instance solidOwner(org.bukkit.block.Block b) {
        Map<Long, Instance> world = solidBlocks.get(b.getWorld().getUID());
        Instance i = world == null ? null : world.get(b.getBlockKey());
        return i == null || i.removed() ? null : i;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    /** A server's own nudge to where riders sit, in blocks (seats.height-offset). */
    double seatNudge() {
        return plugin.getConfig().getDouble("seats.height-offset", 0);
    }

    void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    void callEvent(org.bukkit.event.Event e) {
        Bukkit.getPluginManager().callEvent(e);
    }

    /** Calls {@code e}; true unless a listener cancelled it. */
    boolean call(org.bukkit.event.Event e) {
        Bukkit.getPluginManager().callEvent(e);
        return !(e instanceof org.bukkit.event.Cancellable c) || !c.isCancelled();
    }

    /** The mob that walks a driven model: as its spec says, or by its size. */
    EntityType brainFor(Instance i, ai.resourcepack.engine3d.bake.Spec.Drive d) {
        if (d.brain != null) {
            try {
                EntityType t = EntityType.valueOf(d.brain.toUpperCase(java.util.Locale.ROOT));
                if (t.getEntityClass() != null && Mob.class.isAssignableFrom(t.getEntityClass())) return t;
            } catch (IllegalArgumentException ignored) {
            }
            plugin.getLogger().warning(i.model.name() + ": drive brain " + d.brain + " is not a mob; picking one by size");
        }
        double w = i.model.radius() * 2 * i.scale(), h = i.model.height() * i.scale();
        return w <= 0.9 && h <= 1.0 ? EntityType.WOLF : EntityType.HORSE;
    }

    Collection<Instance> all() {
        return instances.values();
    }

    private void forget(Instance i) {
        instances.remove(i.display.getUniqueId());
        if (i.mover != null) {
            movers.remove(i.mover);
            i.mover.removeBrain();
            i.mover = null;
        }
        parts.values().removeIf(x -> x == i);
        solid(i, new long[0]);
    }

    private void tick() {
        effects.tick();
        for (Steering s : new ArrayList<>(movers)) {
            if (!s.valid()) {
                movers.remove(s);
                if (s.brain().isValid()) s.brain().remove();
                if (s.body() instanceof Instance b && b.mover == s) b.mover = null;
                continue;
            }
            s.tick();
        }
        for (Instance i : new ArrayList<>(instances.values())) {
            if (!i.valid()) {
                forget(i);
                i.removeParts();
                if (i.display.isDead()) i.clearSolid();
                continue;
            }
            i.tick(i.display.getWorld().getGameTime());
        }
    }

    /** Wraps placed models' displays as they load. */
    private void adopt(Collection<? extends Entity> entities) {
        if (!ready && models.isEmpty()) return;
        for (Entity e : entities) {
            if (!(e instanceof ItemDisplay d) || instances.containsKey(d.getUniqueId())) continue;
            String name = d.getPersistentDataContainer().get(Instance.MODEL, PersistentDataType.STRING);
            if (name == null) continue;
            LoadedModel m = models.get(name);
            if (m == null) continue;
            String state = d.getPersistentDataContainer().get(Instance.STATE, PersistentDataType.STRING);
            Instance i = new Instance(this, m, d, state, d.getWorld().getGameTime());
            instances.put(d.getUniqueId(), i);
            solid(i, i.solidBlocks());
            i.build();
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        adopt(e.getEntities());
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity x : e.getEntities()) {
            Instance i = instances.get(x.getUniqueId());
            if (i != null && x.isPersistent()) {
                forget(i);
                i.removeParts();
            }
        }
    }

    // A brain is a costume rack: nothing hurts it.
    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains("3dengine")) e.setCancelled(true);
    }
}
