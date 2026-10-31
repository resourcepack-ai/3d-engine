package ai.resourcepack.pool3d;

import ai.resourcepack.engine3d.api.Engine3D;
import ai.resourcepack.pool3d.command.PoolCommand;
import ai.resourcepack.pool3d.game.Tables;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 3D Pool: a 9 ft pool table with real physics, built on 3D Engine.
 *
 * <ul>
 *   <li>{@code physics}: the balls, the cushions and the pockets, stepped 2000
 *   times a second, and nothing of Minecraft in it;</li>
 *   <li>{@code game}: tables in the world, the cue and the aiming guide, the
 *   keys, the HUD, and 8-ball's rules;</li>
 *   <li>{@link Assets}: the table, balls and cue (made by
 *   {@code tools/make_models.py}) and the sounds and HUD font
 *   ({@code tools/make_pack.py}), installed into 3D Engine.</li>
 * </ul>
 */
public final class Pool3D extends JavaPlugin {

    private Tables tables;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Engine3D engine = Engine3D.get();
        Assets.install(this, engine, getFile());
        tables = new Tables(this, engine);
        tables.load();
        getServer().getPluginManager().registerEvents(tables, this);
        getServer().getScheduler().runTaskTimer(this, tables::tick, 1L, 1L);
        // A reload re-bakes the models: draw every table again on the new ones.
        engine.whenReady(tables::spawnAll);
        PluginCommand pool = getCommand("pool");
        if (pool != null) {
            PoolCommand command = new PoolCommand(tables);
            pool.setExecutor(command);
            pool.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (tables != null) tables.shutdown();
    }
}
