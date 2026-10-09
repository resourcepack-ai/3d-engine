package ai.resourcepack.engine3d;

import ai.resourcepack.engine3d.bake.Baker;
import ai.resourcepack.engine3d.bake.Rig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Prints a model's bones as a tree, and which vertices follow each ({@code -Dsample=path}). */
class RigDump {

    @Test
    void dump() throws Exception {
        String sample = System.getProperty("sample", "");
        Assumptions.assumeTrue(!sample.isEmpty() && Files.exists(Path.of(sample)), "no -Dsample");
        Path f = Path.of(sample);
        Rig rig = Baker.load(f);
        for (int i = 0; i < rig.names.length; i++) if (rig.parent[i] < 0) print(rig, i, 0);
        // Which node each vertex mostly follows, and where those vertices are at rest.
        java.util.Map<Integer, double[]> by = new java.util.TreeMap<>();
        double[][] rest = rig.positions(rig.restLocals());
        for (int v = 0; v < rig.verts.size(); v++) {
            Rig.Vert x = rig.verts.get(v);
            int best = 0;
            for (int k = 1; k < x.weights.length; k++) if (x.weights[k] > x.weights[best]) best = k;
            int node = rig.slotNodeOf(x.joints[best]);
            double[] a = by.computeIfAbsent(node, n -> new double[]{0, 1e9, 1e9, 1e9, -1e9, -1e9, -1e9});
            a[0]++;
            for (int k = 0; k < 3; k++) {
                a[1 + k] = Math.min(a[1 + k], rest[v][k]);
                a[4 + k] = Math.max(a[4 + k], rest[v][k]);
            }
        }
        for (var e : by.entrySet()) {
            double[] a = e.getValue();
            double[] o = ai.resourcepack.engine3d.bake.Mat.pos(rig.restWorldOf(e.getKey()));
            System.out.printf("    %-22s %5d verts  x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f  origin %.2f %.2f %.2f%n", rig.names[e.getKey()], (int) a[0],
                    a[1], a[4], a[2], a[5], a[3], a[6], o[0], o[1], o[2]);
        }
    }

    private static void print(Rig rig, int node, int depth) {
        System.out.println("  ".repeat(depth) + rig.names[node]);
        for (int i = 0; i < rig.names.length; i++) if (rig.parent[i] == node) print(rig, i, depth + 1);
    }
}
