package ai.resourcepack.pool3d.game;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 8-ball, close to the WPA's rules but with no called shots: a ball that goes
 * in counts, however it got there.
 *
 * <ul>
 *   <li>The break must sink a ball or drive four object balls to a cushion.</li>
 *   <li>The table is open after the break. The first player to sink only
 *   solids or only stripes on a legal shot has that group.</li>
 *   <li>Every shot must hit one of your own balls first (any but the 8 on an
 *   open table; the 8 once your group is gone), and then either sink
 *   something or send a ball to a cushion.</li>
 *   <li>A foul gives the other player the cue ball in hand, anywhere.</li>
 *   <li>Sinking the 8 wins once your group is cleared and the shot is clean;
 *   any other time it loses. On the break it is spotted again.</li>
 * </ul>
 *
 * With one player it is practice: no fouls, the cue ball comes back in hand
 * after a scratch, and clearing the table is the end.
 */
public final class EightBall {

    public enum Group {
        NONE("—"), SOLIDS("Solids"), STRIPES("Stripes");

        public final String label;

        Group(String label) {
            this.label = label;
        }

        public boolean owns(int ball) {
            return this == SOLIDS ? ball >= 1 && ball <= 7 : this == STRIPES && ball >= 9 && ball <= 15;
        }

        Group other() {
            return this == SOLIDS ? STRIPES : this == STRIPES ? SOLIDS : NONE;
        }
    }

    public enum InHand {NO, ANYWHERE}

    /**
     * The outcome of a shot: a headline for the screen, whether the shooter
     * goes again, and what the next shooter starts with.
     */
    public record Verdict(String headline, String detail, boolean foul, boolean keepTurn, int winner,
                          boolean spotEight, InHand inHand) {
    }

    public final int players;
    public int turn;
    public boolean breakShot = true;
    public final Group[] group = {Group.NONE, Group.NONE};
    public final Set<Integer> down = new LinkedHashSet<>();
    public boolean over;

    public EightBall(int players, int breaker) {
        this.players = players;
        this.turn = breaker;
    }

    public boolean practice() {
        return players == 1;
    }

    public Group groupOf(int player) {
        return group[player];
    }

    public boolean open() {
        return group[0] == Group.NONE;
    }

    /** How many of a player's group are still on the table (7 while the table is open). */
    public int left(int player) {
        Group g = group[player];
        if (g == Group.NONE) return 7;
        int n = 0;
        for (int b = 1; b <= 15; b++) if (b != 8 && g.owns(b) && !down.contains(b)) n++;
        return n;
    }

    /** Whether the player is on the 8. */
    public boolean onEight(int player) {
        return group[player] != Group.NONE && left(player) == 0;
    }

    /** The balls the shooter may legally hit first. */
    public List<Integer> targets() {
        List<Integer> out = new ArrayList<>();
        for (int b = 1; b <= 15; b++) {
            if (down.contains(b)) continue;
            if (practice() || (open() && b != 8) || (!open() && (onEight(turn) ? b == 8 : group[turn].owns(b)))) out.add(b);
        }
        return out;
    }

    public Verdict judge(ShotRecord shot) {
        Verdict v = practice() ? practise(shot) : rule(shot);
        for (int b : shot.pocketed) if (b != 0 && !(b == 8 && v.spotEight())) down.add(b);
        breakShot = false;
        if (v.winner() >= 0) over = true;
        else if (!v.keepTurn()) turn = (turn + 1) % players;
        return v;
    }

    private Verdict practise(ShotRecord shot) {
        Set<Integer> after = new LinkedHashSet<>(down);
        for (int b : shot.pocketed) if (b != 0) after.add(b);
        if (after.size() == 15) return new Verdict("Table cleared!", "Every ball down.", false, true, 0, false, InHand.NO);
        if (shot.scratched()) return new Verdict("Scratch", "Cue ball in hand.", false, true, -1, false, InHand.ANYWHERE);
        return new Verdict(null, null, false, true, -1, false, InHand.NO);
    }

    private Verdict rule(ShotRecord shot) {
        int me = turn, them = (turn + 1) % players;
        boolean eightDown = shot.pocketed.contains(8);
        boolean scratch = shot.scratched();

        if (breakShot) {
            boolean legal = shot.pocketed.stream().anyMatch(b -> b != 0) || shot.objectBallsToRail.size() >= 4;
            if (scratch || !legal || eightDown) {
                String why = scratch ? "Scratch on the break" : !legal ? "Illegal break" : null;
                if (eightDown && !scratch && legal)
                    return new Verdict("8 on the break", "The 8 is spotted. Keep shooting.", false, true, -1, true, InHand.NO);
                return new Verdict("Foul", why + (eightDown ? "; the 8 is spotted" : "") + ".", true, false, -1, eightDown, InHand.ANYWHERE);
            }
            boolean any = shot.pocketed.stream().anyMatch(b -> b != 0);
            return new Verdict(any ? "Good break" : null, any ? "Table is open. Keep shooting." : null, false, any, -1, false, InHand.NO);
        }

        String foul = null;
        if (shot.firstContact < 0) foul = "No ball hit";
        else if (!firstContactLegal(me, shot.firstContact)) foul = "Wrong ball first: the " + shot.firstContact;
        else if (scratch) foul = "Scratch";
        else if (shot.pocketed.isEmpty() && !shot.railAfterContact) foul = "No rail after contact";

        if (eightDown) {
            boolean wasOnEight = onEight(me);
            if (wasOnEight && foul == null) return new Verdict("Winner!", "8-ball in.", false, false, me, false, InHand.NO);
            String why = !wasOnEight ? "8-ball down early" : "8-ball on a foul";
            return new Verdict("8-ball lost", why + ".", true, false, them, false, InHand.NO);
        }

        // Groups: the first clean shot that sinks only one kind decides them.
        if (open() && foul == null) {
            boolean solids = false, stripes = false;
            for (int b : shot.pocketed) {
                if (b >= 1 && b <= 7) solids = true;
                if (b >= 9) stripes = true;
            }
            if (solids != stripes) {
                group[me] = solids ? Group.SOLIDS : Group.STRIPES;
                group[them] = group[me].other();
                return new Verdict(group[me].label, "You have the " + group[me].label.toLowerCase() + ".", false, true, -1, false, InHand.NO);
            }
            if (solids) return new Verdict(null, "Both groups went down; the table stays open.", false, true, -1, false, InHand.NO);
        }

        if (foul != null) return new Verdict("Foul", foul + ".", true, false, -1, false, InHand.ANYWHERE);
        boolean mine = false;
        for (int b : shot.pocketed) if (b != 0 && (open() || group[me].owns(b))) mine = true;
        return new Verdict(null, null, false, mine, -1, false, InHand.NO);
    }

    private boolean firstContactLegal(int player, int ball) {
        if (open()) return ball != 8;
        if (onEight(player)) return ball == 8;
        return group[player].owns(ball);
    }
}
