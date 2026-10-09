package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.bake.Manifest;
import ai.resourcepack.engine3d.bake.Spec;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A baked model as the runtime holds it. */
final class LoadedModel implements Model {

    final Manifest manifest;
    /** What the runtime reads of the spec: seats, looking, hitboxes, driving. */
    final Spec spec;
    private final Map<String, Object> settings;
    private final Set<String> points = new LinkedHashSet<>();

    LoadedModel(Manifest manifest, Spec spec) {
        this.manifest = manifest;
        this.spec = spec;
        this.settings = spec.raw;
        for (Manifest.Model m : manifest.models.values())
            for (String p : m.points.keySet()) if (!p.startsWith("_")) points.add(p);
    }

    /** The game model to show for {@code id}'s head, or null when the head isn't drawn apart. */
    String head(String id) {
        return manifest.lookBone != null ? id + Manifest.LOOK : null;
    }

    /** The drive settings, or null when it can't be driven. */
    Spec.Drive drive() {
        return spec.drive != null && !spec.seats.isEmpty() ? spec.drive : null;
    }

    /** The seat whose rider drives. */
    String driverSeat() {
        Spec.Drive d = drive();
        if (d == null) return null;
        return d.seat != null && spec.seats.containsKey(d.seat) ? d.seat : spec.seats.keySet().iterator().next();
    }

    @Override
    public java.util.List<String> seats() {
        return java.util.List.copyOf(spec.seats.keySet());
    }

    @Override
    public boolean looks() {
        return spec.look != null;
    }

    @Override
    public boolean drivable() {
        return drive() != null;
    }

    @Override
    public String name() {
        return manifest.name;
    }

    @Override
    public Set<String> states() {
        return Collections.unmodifiableSet(manifest.states.keySet());
    }

    @Override
    public String defaultState() {
        return manifest.defaultState;
    }

    @Override
    public boolean reachable(String from, String to) {
        return from.equals(to) || manifest.nextHop(from, to) != null;
    }

    @Override
    public Set<String> points() {
        return Collections.unmodifiableSet(points);
    }

    @Override
    public double height() {
        return manifest.height;
    }

    @Override
    public double radius() {
        return manifest.radius;
    }

    @Override
    public Map<String, Object> locomotion() {
        return Collections.unmodifiableMap(manifest.locomotion);
    }

    @Override
    public Map<String, Object> settings() {
        return Collections.unmodifiableMap(settings);
    }
}
