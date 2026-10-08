package ai.resourcepack.engine3d.bake;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Cuts a mesh down to a triangle budget by collapsing edges, cheapest first by
 * quadric error (Garland and Heckbert).
 *
 * Every collapse moves a vertex onto one of its neighbours rather than to a new
 * point, so every surviving vertex keeps its own UV and skin weights and the
 * animation needs nothing recomputed. Vertices are welded by position first,
 * so the mesh is one surface across UV seams; a vertex on a seam may only
 * slide along the seam (each of its copies onto the matching copy of the
 * neighbour), which keeps the texture from tearing. Open borders are locked.
 */
public final class Simplifier {

    private Simplifier() {
    }

    /** The surviving triangles ({a, b, c, material}, original vertex indices), at most about {@code target}. */
    public static List<int[]> simplify(double[][] positions, List<int[]> tris, int target) {
        if (tris.size() <= target) return tris;
        return new Run(positions, tris).run(target);
    }

    private static final class Run {
        final int[] pid;
        final List<int[]> verts = new ArrayList<>();
        final double[][] pos;
        final double[][] quadric;
        final List<Set<Integer>> trisOf = new ArrayList<>();
        final int[][] tris;
        final boolean[] dead;
        final boolean[] gone;
        final boolean[] locked;
        final int[] version;
        int alive;

        Run(double[][] positions, List<int[]> input) {
            Map<String, Integer> weld = new HashMap<>();
            pid = new int[positions.length];
            List<double[]> ps = new ArrayList<>();
            for (int i = 0; i < positions.length; i++) {
                double[] p = positions[i];
                String key = p[0] + "," + p[1] + "," + p[2];
                Integer id = weld.get(key);
                if (id == null) {
                    id = ps.size();
                    weld.put(key, id);
                    ps.add(p);
                    verts.add(new int[0]);
                    trisOf.add(new LinkedHashSet<>());
                }
                pid[i] = id;
                int[] old = verts.get(id);
                int[] grown = java.util.Arrays.copyOf(old, old.length + 1);
                grown[old.length] = i;
                verts.set(id, grown);
            }
            int n = ps.size();
            pos = ps.toArray(new double[0][]);
            quadric = new double[n][10];
            gone = new boolean[n];
            locked = new boolean[n];
            version = new int[n];
            tris = new int[input.size()][];
            dead = new boolean[input.size()];
            Map<Long, Integer> edgeUse = new HashMap<>();
            for (int t = 0; t < tris.length; t++) {
                int[] tri = input.get(t).clone();
                tris[t] = tri;
                int a = pid[tri[0]], b = pid[tri[1]], c = pid[tri[2]];
                if (a == b || b == c || a == c) {
                    dead[t] = true;
                    continue;
                }
                alive++;
                for (int k = 0; k < 3; k++) {
                    int p = pid[tri[k]];
                    trisOf.get(p).add(t);
                    int q = pid[tri[(k + 1) % 3]];
                    edgeUse.merge(edgeKey(p, q), 1, Integer::sum);
                }
                double[] plane = plane(pos[a], pos[b], pos[c]);
                if (plane != null) for (int k = 0; k < 3; k++) addPlane(quadric[pid[tri[k]]], plane);
            }
            for (var e : edgeUse.entrySet()) {
                if (e.getValue() != 2) {
                    locked[(int) (e.getKey() >> 32)] = true;
                    locked[(int) (long) e.getKey()] = true;
                }
            }
        }

        static long edgeKey(int a, int b) {
            int lo = Math.min(a, b), hi = Math.max(a, b);
            return ((long) lo << 32) | hi;
        }

        record Candidate(double cost, int u, int v, int vu, int vv) {
        }

        List<int[]> run(int target) {
            PriorityQueue<Candidate> queue = new PriorityQueue<>((x, y) -> Double.compare(x.cost, y.cost));
            for (int u = 0; u < pos.length; u++) push(queue, u);
            while (alive > target && !queue.isEmpty()) {
                Candidate c = queue.poll();
                if (gone[c.u] || gone[c.v] || version[c.u] != c.vu || version[c.v] != c.vv) continue;
                int[] remap = remap(c.u, c.v);
                if (remap == null || !valid(c.u, c.v)) continue;
                collapse(c.u, c.v, remap);
                Set<Integer> touched = new HashSet<>(neighbours(c.v));
                touched.add(c.v);
                for (int w : touched) version[w]++;
                for (int w : touched) push(queue, w);
            }
            List<int[]> out = new ArrayList<>();
            for (int t = 0; t < tris.length; t++) if (!dead[t]) out.add(tris[t]);
            return out;
        }

        void push(PriorityQueue<Candidate> queue, int u) {
            if (gone[u] || locked[u]) return;
            for (int v : neighbours(u)) {
                double[] q = new double[10];
                for (int k = 0; k < 10; k++) q[k] = quadric[u][k] + quadric[v][k];
                queue.add(new Candidate(error(q, pos[v]), u, v, version[u], version[v]));
            }
        }

        Set<Integer> neighbours(int u) {
            Set<Integer> out = new LinkedHashSet<>();
            for (int t : trisOf.get(u)) for (int k = 0; k < 3; k++) {
                int p = pid[tris[t][k]];
                if (p != u) out.add(p);
            }
            return out;
        }

        /**
         * For each copy of u, the copy of v it lands on: the one it shares a
         * triangle with. Null if some copy of u has none (u is on a seam and
         * v is not across from it on the same seam).
         */
        int[] remap(int u, int v) {
            int[] copies = verts.get(u);
            int[] out = new int[copies.length];
            for (int i = 0; i < copies.length; i++) {
                int a = copies[i], found = -1;
                boolean used = false;
                for (int t : trisOf.get(u)) {
                    int[] tri = tris[t];
                    boolean hasA = false;
                    int b = -1;
                    for (int k = 0; k < 3; k++) {
                        if (tri[k] == a) hasA = true;
                        if (pid[tri[k]] == v) b = tri[k];
                    }
                    if (!hasA) continue;
                    used = true;
                    if (b >= 0) {
                        found = b;
                        break;
                    }
                }
                if (!used) found = verts.get(v)[0]; // a copy no live triangle uses
                if (found < 0) return null;
                out[i] = found;
            }
            return out;
        }

        boolean valid(int u, int v) {
            // Link condition: the only vertices both are joined to are the
            // tips of the triangles on the edge, or the collapse pinches the
            // surface into a non-manifold one.
            Set<Integer> common = new HashSet<>(neighbours(u));
            common.retainAll(neighbours(v));
            int tips = 0;
            for (int t : trisOf.get(u)) {
                boolean hasV = false;
                for (int k = 0; k < 3; k++) if (pid[tris[t][k]] == v) hasV = true;
                if (hasV) tips++;
            }
            if (common.size() != tips) return false;
            // No triangle may flip or collapse to a sliver.
            for (int t : trisOf.get(u)) {
                int[] tri = tris[t];
                double[][] before = new double[3][], after = new double[3][];
                boolean hasV = false;
                for (int k = 0; k < 3; k++) {
                    int p = pid[tri[k]];
                    if (p == v) hasV = true;
                    before[k] = pos[p];
                    after[k] = p == u ? pos[v] : pos[p];
                }
                if (hasV) continue;
                double[] n0 = normal(before), n1 = normal(after);
                double l0 = Math.sqrt(Mat.dot(n0, n0)), l1 = Math.sqrt(Mat.dot(n1, n1));
                if (l1 < 1e-12) return false;
                if (l0 > 1e-12 && Mat.dot(n0, n1) / (l0 * l1) < 0.3) return false;
            }
            return true;
        }

        void collapse(int u, int v, int[] remap) {
            int[] copies = verts.get(u);
            for (int t : new ArrayList<>(trisOf.get(u))) {
                int[] tri = tris[t];
                boolean hasV = false;
                for (int k = 0; k < 3; k++) if (pid[tri[k]] == v) hasV = true;
                if (hasV) {
                    dead[t] = true;
                    alive--;
                    for (int k = 0; k < 3; k++) trisOf.get(pid[tri[k]]).remove(t);
                    continue;
                }
                for (int k = 0; k < 3; k++)
                    for (int i = 0; i < copies.length; i++) if (tri[k] == copies[i]) tri[k] = remap[i];
                trisOf.get(v).add(t);
            }
            trisOf.get(u).clear();
            for (int k = 0; k < 10; k++) quadric[v][k] += quadric[u][k];
            gone[u] = true;
        }

        static double[] normal(double[][] p) {
            return Mat.cross(Mat.sub(p[1], p[0]), Mat.sub(p[2], p[0]));
        }

        static double[] plane(double[] a, double[] b, double[] c) {
            double[] n = Mat.cross(Mat.sub(b, a), Mat.sub(c, a));
            double len = Math.sqrt(Mat.dot(n, n));
            if (len < 1e-15) return null;
            double area = len / 2;
            double nx = n[0] / len, ny = n[1] / len, nz = n[2] / len;
            double d = -(nx * a[0] + ny * a[1] + nz * a[2]);
            return new double[]{nx, ny, nz, d, area};
        }

        /** Adds an area-weighted plane to a symmetric 4x4 quadric stored as 10 values. */
        static void addPlane(double[] q, double[] p) {
            double a = p[0], b = p[1], c = p[2], d = p[3], w = p[4];
            q[0] += w * a * a; q[1] += w * a * b; q[2] += w * a * c; q[3] += w * a * d;
            q[4] += w * b * b; q[5] += w * b * c; q[6] += w * b * d;
            q[7] += w * c * c; q[8] += w * c * d;
            q[9] += w * d * d;
        }

        static double error(double[] q, double[] v) {
            double x = v[0], y = v[1], z = v[2];
            return q[0] * x * x + 2 * q[1] * x * y + 2 * q[2] * x * z + 2 * q[3] * x
                    + q[4] * y * y + 2 * q[5] * y * z + 2 * q[6] * y
                    + q[7] * z * z + 2 * q[8] * z + q[9];
        }
    }
}
