package ai.resourcepack.engine3d.bake.mesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs triangles across a shared edge into quads, halving the faces obj³ has
 * to encode and decode. Minecraft splits a quad (0,1,2,3) into (0,1,2) and
 * (2,3,0), so a quad (p,q,r,s) built from triangles (p,q,r) and (r,s,p)
 * reproduces both exactly, whatever any frame does to them.
 */
public final class Quads {

    private Quads() {
    }

    /** Faces of 4 (or 3, where nothing pairs) vertex indices. */
    public static int[][] build(List<int[]> tris, double[][] rest) {
        Map<Long, Integer> edge = new HashMap<>();
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            for (int k = 0; k < 3; k++) edge.put(key(t[k], t[(k + 1) % 3]), i);
        }
        double[][] normals = new double[tris.size()][];
        for (int i = 0; i < tris.size(); i++) {
            int[] t = tris.get(i);
            normals[i] = Mat.normalize(Mat.cross(Mat.sub(rest[t[1]], rest[t[0]]), Mat.sub(rest[t[2]], rest[t[0]])));
        }
        boolean[] used = new boolean[tris.size()];
        List<int[]> faces = new ArrayList<>();
        for (int i = 0; i < tris.size(); i++) {
            if (used[i]) continue;
            int[] t = tris.get(i);
            int bestJ = -1;
            int[] bestQuad = null;
            double bestDot = -2;
            for (int k = 0; k < 3; k++) {
                // Triangle i as (p,q,r) with the shared edge r->p; its partner holds p->r.
                int p = t[(k + 2) % 3], q = t[k], r = t[(k + 1) % 3];
                Integer j = edge.get(key(p, r));
                if (j == null || used[j] || j == i || tris.get(j)[3] != t[3]) continue;
                int[] o = tris.get(j);
                int s = -1;
                for (int m = 0; m < 3; m++) if (o[m] != p && o[m] != r) s = o[m];
                if (s < 0) continue;
                double dot = Mat.dot(normals[i], normals[j]);
                if (dot > bestDot) {
                    bestDot = dot;
                    bestJ = j;
                    bestQuad = new int[]{p, q, r, s};
                }
            }
            used[i] = true;
            if (bestQuad != null && bestDot > 0.5) {
                used[bestJ] = true;
                faces.add(bestQuad);
            } else {
                faces.add(new int[]{t[0], t[1], t[2]});
            }
        }
        return faces.toArray(new int[0][]);
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }
}
