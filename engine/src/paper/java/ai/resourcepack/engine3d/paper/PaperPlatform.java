package ai.resourcepack.engine3d.paper;

import ai.resourcepack.engine3d.runtime.Platform;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Paper's half of {@link Platform}: its mob goals and pathfinder, its
 * swing event and its teleport that keeps riders on. Compiled against
 * Paper's API and loaded only on a Paper server.
 */
public final class PaperPlatform implements Platform {

    @Override
    public String name() {
        return "Paper";
    }

    @Override
    public void prepareBrain(Mob brain, Plugin plugin, Legs legs) {
        Bukkit.getMobGoals().removeAllGoals(brain);
        Bukkit.getMobGoals().addGoal(brain, 1, new Walk(brain, new NamespacedKey(plugin, "legs"), legs));
    }

    @Override
    public List<Location> path(Mob brain, Location target) {
        var path = brain.getPathfinder().findPath(target);
        return path == null ? null : new ArrayList<>(path.getPoints());
    }

    @Override
    public void stopPathfinding(Mob brain) {
        brain.getPathfinder().stopPathfinding();
    }

    @Override
    public boolean listenForSwings(Plugin plugin, BiPredicate<Player, Entity> attacked) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            public void onSwing(PrePlayerAttackEntityEvent e) {
                if (attacked.test(e.getPlayer(), e.getAttacked())) e.setCancelled(true);
            }
        }, plugin);
        return true;
    }

    @Override
    public void moveWithRiders(Entity entity, Location to) {
        entity.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    @Override
    public boolean cancellable(EntityDismountEvent event) {
        return event.isCancellable();
    }

    @Override
    public TextDisplay spawnText(Location at, String text, String font, int rgb, Consumer<TextDisplay> before) {
        return at.getWorld().spawn(at, TextDisplay.class, t -> {
            before.accept(t);
            t.text(Component.text(text).font(Key.key(font)).color(TextColor.color(rgb)));
        });
    }

    /** The brain's only goal: keep the pathfinder off while steering, drive it when not. */
    private static final class Walk implements Goal<Mob> {
        private final Mob brain;
        private final GoalKey<Mob> key;
        private final Legs legs;
        private int ticks;

        Walk(Mob brain, NamespacedKey key, Legs legs) {
            this.brain = brain;
            this.key = GoalKey.of(Mob.class, key);
            this.legs = legs;
        }

        @Override
        public boolean shouldActivate() {
            return true;
        }

        @Override
        public void tick() {
            boolean repath = ticks++ % 3 == 0;
            if (legs.steering()) {
                brain.getPathfinder().stopPathfinding();
                return;
            }
            if (repath) brain.getPathfinder().moveTo(legs.goal(), legs.speed() > 0.15 ? 1.3 : 0.6);
        }

        @Override
        public @NotNull GoalKey<Mob> getKey() {
            return key;
        }

        @Override
        public @NotNull EnumSet<GoalType> getTypes() {
            return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
        }
    }
}
