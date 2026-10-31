package ai.resourcepack.pool3d.game;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/**
 * The action bar while a player lines up a shot: the cue ball with a red dot
 * where the tip will meet it, and what the keys do. The ball is a glyph of
 * the {@code pool:hud} font the pack ships.
 */
final class Hud {

    private static final int SPIN = 11;

    private Hud() {
    }

    static void actionBar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }

    /**
     * The cue ball with the tip's spot on it, what the spin is, and the keys
     * for whatever the mouse is doing. There is no power bar: as in
     * Shooterspool, the power is how fast the cue is pushed.
     */
    static void shot(Player p, double side, double height, String mode, boolean overview) {
        int col = (int) Math.round(side * 10) + SPIN / 2;
        int row = SPIN / 2 - (int) Math.round(height * 10);
        col = Math.max(0, Math.min(SPIN - 1, col));
        row = Math.max(0, Math.min(SPIN - 1, row));
        TextComponent ball = new TextComponent(String.valueOf((char) (0xE100 + row * SPIN + col)));
        ball.setFont("pool:hud");
        String keys = switch (mode) {
            case "STROKE" -> "§fmouse back, then forward through the ball §7· let go of S to stop";
            case "SPIN" -> "§fmouse moves the tip §7· let go of W to keep it";
            default -> overview ? "§7space: back to the shot"
                    : "§7hold §fS§7 to cue · hold §fW§7 for spin · §fA/D§7 fine aim · §fscroll§7 zoom · §fspace§7 table";
        };
        BaseComponent[] tail = TextComponent.fromLegacyText("  §7" + describe(side, height) + "   " + keys);
        BaseComponent[] all = new BaseComponent[1 + tail.length];
        all[0] = ball;
        System.arraycopy(tail, 0, all, 1, tail.length);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, all);
    }

    private static String describe(double side, double height) {
        if (side == 0 && height == 0) return "centre ball";
        StringBuilder s = new StringBuilder();
        if (height > 0) s.append("follow ").append(Math.round(height * 10));
        if (height < 0) s.append("draw ").append(Math.round(-height * 10));
        if (side != 0) {
            if (s.length() > 0) s.append(", ");
            s.append(side > 0 ? "right " : "left ").append(Math.round(Math.abs(side) * 10));
        }
        return s.toString();
    }
}
