package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.common.host.PackUploader;
import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.engine3d.api.Mover;
import ai.resourcepack.engine3d.effects.Effects;
import ai.resourcepack.engine3d.pack.PackBuilder;
import ai.resourcepack.engine3d.pack.PackServer;
import ai.resourcepack.engine3d.runtime.movement.Steering;
import ai.resourcepack.engine3d.runtime.platform.Blocks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
    /**
     * The pack server's settings as it was started with: it is bound once, in
     * {@link #enable}, so a change to these in config.yml needs a restart.
     */
    private boolean startedHost;
    private int startedPort;
    private String startedBind;
    /** {@code pack.host: upload}: the pack goes to ResourcePack AI's pack host, and players are sent its link there. */
    private boolean uploading;
    private java.util.concurrent.ScheduledExecutorService uploads;
    private PackUploader uploader;
    /** Upload: the hex SHA-1 of the pack the pack host has. */
    private volatile String hosted;
    /** Upload: the hex SHA-1 whose upload failed, which this server serves meanwhile. */
    private volatile String failed;

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
        String mode = hostMode(c);
        uploading = mode.equals("upload");
        startedHost = mode.equals("serve");
        startedPort = c.getInt("pack.port", 8163);
        startedBind = c.getString("pack.bind", "");
        if (uploading) {
            uploader = new PackUploader(PackUploader.HOST, "3DEngine/" + plugin.getDescription().getVersion());
            uploads = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "3DEngine pack upload");
                t.setDaemon(true);
                return t;
            });
            // Keeps the pack on the host, which forgets one nobody has touched for 30 days, and tries a failed upload again.
            uploads.scheduleWithFixedDelay(() -> {
                PackBuilder.Pack now = pack;
                if (now != null) ensure(now);
            }, PackUploader.TOUCH_HOURS, PackUploader.TOUCH_HOURS, java.util.concurrent.TimeUnit.HOURS);
        }
        if (startedHost) {
            try {
                server = new PackServer(startedBind, startedPort);
            } catch (Exception e) {
                plugin.getLogger().severe("Couldn't serve the pack on port " + startedPort + " (" + e.getMessage() + "); set pack.port to a free one");
            }
        }
        packUrl = packUrl(c, true);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Commands commands = new Commands(this, plugin);
        var cmd = plugin.getCommand("3d");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        }
        Bukkit.getPluginManager().registerEvents(commands, plugin);
        Bukkit.getPluginManager().registerEvents(new Interactions(this, plugin), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        // On the first tick, so a plugin depending on this one has had its
        // onEnable to copy its own models in.
        Bukkit.getScheduler().runTask(plugin, () -> reload(Bukkit.getConsoleSender()));
    }

    public void disable() {
        if (uploads != null) uploads.shutdownNow();
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
        // Sent once the pack host has it (see ensure), not before.
        if (uploading && !built.hex().equals(hosted)) {
            changed = false;
            uploads.execute(() -> ensure(built));
        }
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

    /**
     * {@code pack.host}: {@code upload}, {@code serve} or {@code none}. Before
     * there was a choice it was true or false, and true was the default: a
     * server that set {@code pack.url} meant to serve it, and any other is
     * better off uploading.
     */
    private static String hostMode(FileConfiguration c) {
        String host = String.valueOf(c.get("pack.host", "upload")).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (host) {
            case "serve", "upload" -> host;
            case "false", "none", "off" -> "none";
            case "true" -> c.getString("pack.url", "").isBlank() ? "upload" : "serve";
            default -> "upload";
        };
    }

    /** Makes sure the pack host has [p] (off the server thread), then sends it to the players online. */
    private void ensure(PackBuilder.Pack p) {
        try {
            uploader.ensure(p.hex(), p.zip(), "the 3D Engine pack");
            boolean first = !p.hex().equals(hosted);
            boolean wasFailing = p.hex().equals(failed);
            hosted = p.hex();
            if (wasFailing) {
                failed = null;
                plugin.getLogger().info("The pack is on packs.resourcepack.ai now: players are sent it from there.");
            }
            if (first && !wasFailing) sendAllLater(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | RuntimeException e) {
            if (p.hex().equals(hosted) || p.hex().equals(failed)) return; // there for weeks yet, or already served from here
            failed = p.hex();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (server == null) {
                    try {
                        server = new PackServer(startedBind, startedPort);
                    } catch (Exception bind) {
                        plugin.getLogger().severe("Couldn't put the pack on packs.resourcepack.ai (" + e.getMessage() + "), nor serve it on port "
                                + startedPort + " (" + bind.getMessage() + "), so players are not sent it. The upload is tried again every "
                                + PackUploader.TOUCH_HOURS + " hours.");
                        return;
                    }
                }
                server.serve(p);
                // Too big is not going to change on a retry: say what to do instead.
                plugin.getLogger().warning(p.zip().length > PackUploader.MAX_BYTES
                        ? "The pack is " + (p.zip().length / 1048576) + " MB, and packs.resourcepack.ai takes at most " + (PackUploader.MAX_BYTES / 1048576)
                                + " MB, so this server serves it as " + packUrl + ". Set pack.url to the address players reach port " + startedPort
                                + " on, or make the pack smaller with /3d quality."
                        : "Couldn't put the pack on packs.resourcepack.ai (" + e.getMessage() + "), so this server serves it as "
                                + packUrl + " meanwhile. The upload is tried again every " + PackUploader.TOUCH_HOURS + " hours.");
                sendAll(p);
            });
        }
    }

    private void sendAllLater(PackBuilder.Pack p) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> sendAll(p));
        } catch (IllegalStateException | IllegalArgumentException disabled) {
            // 3D Engine stopped meanwhile.
        }
    }

    private void sendAll(PackBuilder.Pack p) {
        if (pack != p || !plugin.getConfig().getBoolean("pack.send", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) send(player);
    }

    /**
     * Where players download the pack from: {@code pack.url}, or this server's
     * IP and the port the pack server was started on.
     */
    private String packUrl(FileConfiguration c, boolean warn) {
        String url = c.getString("pack.url", "");
        if (url == null || url.isBlank()) {
            String ip = Bukkit.getIp().isBlank() ? "127.0.0.1" : Bukkit.getIp();
            url = "http://" + ip + ":" + startedPort;
            if (warn && server != null)
                plugin.getLogger().warning("pack.url isn't set, so players are sent " + url + ": set it to the address players reach this server's port " + startedPort + " on");
        }
        return url.replaceAll("/+$", "");
    }

    /**
     * Reads config.yml from disk again, for {@code /3d reload} and before
     * {@code /3d quality} saves. Everything in it takes effect at once (the
     * quality settings and the pack's format and description on the bake that
     * follows) except {@code pack.host}, {@code pack.port} and
     * {@code pack.bind}: the pack server is bound when the plugin starts, so
     * those wait for a restart, and {@code to} is told when one of them has
     * changed.
     *
     * <p>False, keeping the settings already loaded, when the file does not
     * parse: Bukkit would otherwise load it as empty, and a {@code /3d quality}
     * saved after that would write the near-empty result over the file.
     */
    boolean rereadConfig(CommandSender to) {
        plugin.saveDefaultConfig(); // only if it was deleted: it comes back as shipped, comments and all
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        try {
            YamlConfiguration check = new YamlConfiguration();
            check.options().parseComments(true);
            check.load(file);
        } catch (java.io.IOException | InvalidConfigurationException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            plugin.getLogger().warning("config.yml can't be read, so the settings already loaded stay: " + e.getMessage());
            to.sendMessage("3D Engine: config.yml can't be read (" + why + "), so the settings already loaded stay. Fix it and run /3d reload.");
            return false;
        }
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        // Bukkit's default, made explicit: saveConfig keeps the file's comments only while this is on.
        c.options().parseComments(true);
        packUrl = packUrl(c, false);
        List<String> restart = new ArrayList<>();
        if (!hostMode(c).equals(uploading ? "upload" : startedHost ? "serve" : "none")) restart.add("pack.host");
        if (c.getInt("pack.port", 8163) != startedPort) restart.add("pack.port");
        if (!java.util.Objects.equals(c.getString("pack.bind", ""), startedBind)) restart.add("pack.bind");
        if (!restart.isEmpty())
            to.sendMessage("3D Engine: " + String.join(", ", restart) + " changed, and the pack server only picks that up when the server restarts.");
        return true;
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

    /**
     * Sets (or, with zeros, clears) a model's own override, saves it and
     * re-bakes. config.yml is read from disk first, so the save writes back
     * what is in the file now, edits made by hand since the server started
     * included, with only this model's keys changed.
     */
    void setQuality(String model, int triangles, int textureWidth, CommandSender to) {
        if (!rereadConfig(to)) {
            to.sendMessage("3D Engine: " + model + "'s quality wasn't saved, so the file isn't overwritten.");
            return;
        }
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
        if (pack == null) return;
        String url;
        if (uploading && pack.hex().equals(hosted)) url = uploader.linkFor(pack.hex());
        else if (uploading && !pack.hex().equals(failed)) return; // still going up: sent once it is there
        else if (server == null && plugin.getConfig().getString("pack.url", "").isBlank()) return;
        else url = packUrl + "/pack/" + pack.hex() + ".zip";
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
        inst.mover = s;
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
        Instance i = world == null ? null : world.get(Blocks.key(b));
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
