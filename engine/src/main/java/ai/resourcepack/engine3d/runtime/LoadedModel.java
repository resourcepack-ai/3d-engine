package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.bake.Manifest;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** A baked model as the runtime holds it. */
final class LoadedModel implements Model {

    final Manifest manifest;
    private final Map<String, Object> settings;
    private final Set<String> points = new LinkedHashSet<>();

    LoadedModel(Manifest manifest, Map<String, Object> settings) {
        this.manifest = manifest;
        this.settings = settings;
        for (Manifest.Model m : manifest.models.values()) points.addAll(m.points.keySet());
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
