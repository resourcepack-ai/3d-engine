package ai.resourcepack.pool3d.game;

import ai.resourcepack.pool3d.physics.Simulation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What happened during one shot, as far as the rules care. */
public final class ShotRecord {

    /** The first ball the cue ball touched, or -1. */
    public int firstContact = -1;
    /** Balls that went down, in order (the cue ball as 0). */
    public final List<Integer> pocketed = new ArrayList<>();
    /** Any ball reached a cushion after the cue ball first touched one. */
    public boolean railAfterContact;
    /** Distinct object balls that touched a cushion (for the break). */
    public final Set<Integer> objectBallsToRail = new LinkedHashSet<>();

    public void add(Simulation.Event e) {
        switch (e.kind()) {
            case BALL -> {
                if (firstContact < 0 && (e.a() == 0 || e.b() == 0)) firstContact = e.a() == 0 ? e.b() : e.a();
            }
            case CUSHION -> {
                if (firstContact >= 0) railAfterContact = true;
                if (e.a() != 0) objectBallsToRail.add(e.a());
            }
            case POCKET -> pocketed.add(e.a());
        }
    }

    public boolean scratched() {
        return pocketed.contains(0);
    }
}
