package ai.resourcepack.engine3d.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A Wavefront .obj (with its .mtl and textures beside it) as a static {@link Rig}. */
public final class ObjLoader {

    private ObjLoader() {
    }

    public static Rig load(Path file) throws IOException {
        List<double[]> v = new ArrayList<>(), vt = new ArrayList<>();
        List<double[]> positions = new ArrayList<>(), uvs = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();
        Map<String, Integer> corner = new HashMap<>();
        Map<String, Integer> materialIndex = new HashMap<>();
        List<Rig.Material> materials = new ArrayList<>();
        Map<String, Rig.Material> library = new HashMap<>();
        int material = 0;
        materials.add(new Rig.Material(null, new double[]{0.8, 0.8, 0.8, 1}));
        materialIndex.put("", 0);

        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "v" -> v.add(new double[]{d(p, 1), d(p, 2), d(p, 3)});
                case "vt" -> vt.add(new double[]{d(p, 1), p.length > 2 ? d(p, 2) : 0});
                case "mtllib" -> library.putAll(mtl(file.getParent(), line.substring(7).trim()));
                case "usemtl" -> {
                    String name = line.substring(6).trim();
                    material = materialIndex.computeIfAbsent(name, n -> {
                        materials.add(library.getOrDefault(n, new Rig.Material(null, new double[]{0.8, 0.8, 0.8, 1})));
                        return materials.size() - 1;
                    });
                }
                case "f" -> {
                    int[] face = new int[p.length - 1];
                    for (int i = 1; i < p.length; i++) {
                        String key = p[i];
                        Integer idx = corner.get(key);
                        if (idx == null) {
                            String[] refs = key.split("/");
                            int vi = index(refs[0], v.size());
                            int ti = refs.length > 1 && !refs[1].isEmpty() ? index(refs[1], vt.size()) : -1;
                            idx = positions.size();
                            positions.add(v.get(vi));
                            // OBJ's v is up; glTF's (and the atlas's) is down.
                            uvs.add(ti >= 0 ? new double[]{vt.get(ti)[0], 1 - vt.get(ti)[1]} : new double[]{0.5, 0.5});
                            corner.put(key, idx);
                        }
                        face[i - 1] = idx;
                    }
                    for (int k = 1; k + 1 < face.length; k++) tris.add(new int[]{face[0], face[k], face[k + 1], material});
                }
                default -> { }
            }
        }
        if (tris.isEmpty()) throw new IOException("No faces in this file");
        return Rig.fromMesh(positions, uvs, tris, materials);
    }

    private static Map<String, Rig.Material> mtl(Path dir, String name) throws IOException {
        Map<String, Rig.Material> out = new HashMap<>();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.exists(file)) return out;
        String current = null;
        byte[] image = null;
        double[] colour = {1, 1, 1, 1};
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            String[] p = line.split("\\s+");
            if (p[0].equals("newmtl")) {
                if (current != null) out.put(current, new Rig.Material(image, colour));
                current = line.substring(6).trim();
                image = null;
                colour = new double[]{1, 1, 1, 1};
            } else if (p[0].equals("Kd") && p.length >= 4) {
                colour = new double[]{d(p, 1), d(p, 2), d(p, 3), 1};
            } else if (p[0].equals("map_Kd")) {
                Path img = dir.resolve(p[p.length - 1]).normalize();
                if (img.startsWith(dir) && Files.exists(img)) image = Files.readAllBytes(img);
            }
        }
        if (current != null) out.put(current, new Rig.Material(image, colour));
        // A textured material's Kd is usually a stand-in, not a tint.
        out.replaceAll((k, m) -> m.image() != null ? new Rig.Material(m.image(), new double[]{1, 1, 1, 1}) : m);
        return out;
    }

    private static double d(String[] p, int i) {
        return i < p.length ? Double.parseDouble(p[i]) : 0;
    }

    private static int index(String ref, int count) {
        int i = Integer.parseInt(ref);
        return i < 0 ? count + i : i - 1;
    }
}
