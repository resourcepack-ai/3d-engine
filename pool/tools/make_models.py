"""
Builds 3D Pool's models: the table, the sixteen balls, the gloss and shadow
that sit on each ball, the cue and the ghost-ball ring. Writes .obj, .mtl and
.png files (and each model's .yml) into src/main/resources/models/.

Everything is made here from numbers, so the table is the same table the
physics plays on: the pocket mouths, jaws, holes and sights come from the same
WPA sizes as physics/TableGeometry.java. Change one, change the other.

    python tools/make_models.py

Needs numpy, scipy, Pillow and shapely 2.1+.
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage
import shapely
from shapely.geometry import Polygon, Point, box
from shapely.ops import unary_union

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "models")

# ---- sizes (metres), as TableGeometry --------------------------------------------------

R = 0.028575
A_FIELD, B_FIELD = 1.27, 0.635
NOSE = 0.0363
CORNER_MOUTH, SIDE_MOUTH = 0.124, 0.137
CORNER_SHELF, SIDE_SHELF = 0.025, 0.010
CORNER_HOLE, SIDE_HOLE = 0.064, 0.068

BED = 0.775            # cloth above the floor
CUSHION = 0.05         # nose to the rail's inner edge
RAIL = 0.13            # wood
RAIL_TOP = 0.0425      # rail top above the cloth
A_OUT, B_OUT = A_FIELD + CUSHION + RAIL, B_FIELD + CUSHION + RAIL
CORNER_ROUND = 0.05    # outer corners, in plan
APRON = 0.19           # rail top down to the cabinet's underside
CUT_CORNER, CUT_SIDE = 0.074, 0.071   # the pocket cut in the rail, around each hole
CUP = 0.13             # pocket depth below the cloth

CLOTH = np.array([38, 118, 66], float)


def corner_pockets():
    mc = CORNER_MOUTH / math.sqrt(2)
    out = []
    for sx in (-1, 1):
        for sy in (-1, 1):
            lx, ly = sx * (A_FIELD - mc), sy * B_FIELD
            px, py = sx * A_FIELD, sy * (B_FIELD - mc)
            n = math.sqrt(0.5)
            mx, my = (lx + px) / 2, (ly + py) / 2
            d = CORNER_SHELF + CORNER_HOLE
            out.append(dict(hole=(mx + sx * n * d, my + sy * n * d), r=CORNER_HOLE, cut=CUT_CORNER,
                            points=((lx, ly), (px, py)),
                            jaws=(((lx, ly), (sx * math.cos(math.radians(38)), sy * math.sin(math.radians(38)))),
                                  ((px, py), (sx * math.cos(math.radians(52)), sy * math.sin(math.radians(52)))))))
    return out


def side_pockets():
    ms = SIDE_MOUTH / 2
    out = []
    j = math.radians(104)
    for sy in (-1, 1):
        out.append(dict(hole=(0, sy * (B_FIELD + SIDE_SHELF + SIDE_HOLE)), r=SIDE_HOLE, cut=CUT_SIDE,
                        points=((ms, sy * B_FIELD), (-ms, sy * B_FIELD)),
                        jaws=(((ms, sy * B_FIELD), (math.cos(j), sy * math.sin(j))),
                              ((-ms, sy * B_FIELD), (-math.cos(j), sy * math.sin(j))))))
    return out


POCKETS = corner_pockets() + side_pockets()


# ---- mesh ----------------------------------------------------------------------------------

class Mesh:
    """Triangles in the table's frame: x along, y across, z up. Written to OBJ as (x, z, -y)."""

    def __init__(self):
        self.v, self.vt, self.f = [], [], []

    def vert(self, p, uv):
        self.v.append(p)
        self.vt.append(uv)
        return len(self.v) - 1

    def tri(self, a, b, c, up=None):
        """Adds a triangle; with {@code up} (a normal it should face) it is wound to face that way."""
        if up is not None:
            pa, pb, pc = (np.array(self.v[i]) for i in (a, b, c))
            if np.dot(np.cross(pb - pa, pc - pa), up) < 0:
                b, c = c, b
        self.f.append((a, b, c))

    def quad(self, a, b, c, d):
        """a b c d counter-clockwise seen from the front."""
        self.f.append((a, b, c))
        self.f.append((a, c, d))

    def write(self, name, texture=None, materials=None):
        """materials: list of (name, face range end) for multi-material meshes (untextured, Kd colours)."""
        path = os.path.join(OUT, name + ".obj")
        with open(path, "w", newline="\n") as o:
            o.write("# 3D Pool: %s, made by tools/make_models.py\n" % name)
            o.write("mtllib %s.mtl\n" % name)
            for x, y, z in self.v:
                o.write("v %.6f %.6f %.6f\n" % (x, z, -y))
            for u, v in self.vt:
                o.write("vt %.6f %.6f\n" % (u, v))
            if materials is None:
                o.write("usemtl skin\n")
                for a, b, c in self.f:
                    o.write("f %d/%d %d/%d %d/%d\n" % (a + 1, a + 1, b + 1, b + 1, c + 1, c + 1))
            else:
                start = 0
                for mname, end in materials:
                    o.write("usemtl %s\n" % mname)
                    for a, b, c in self.f[start:end]:
                        o.write("f %d/%d %d/%d %d/%d\n" % (a + 1, a + 1, b + 1, b + 1, c + 1, c + 1))
                    start = end
        with open(os.path.join(OUT, name + ".mtl"), "w", newline="\n") as o:
            if materials is None:
                o.write("newmtl skin\nKd 1 1 1\nmap_Kd %s\n" % texture)
        return len(self.f)


# ---- noise ---------------------------------------------------------------------------------

rng = np.random.default_rng(1)


def noise(h, w, cells_y, cells_x, order=3):
    """Smooth value noise, h x w, with about cells_y x cells_x features, in [-1, 1]."""
    base = rng.standard_normal((max(2, cells_y) + 3, max(2, cells_x) + 3))
    z = ndimage.zoom(base, ((h + 3 * h / cells_y) / base.shape[0], (w + 3 * w / cells_x) / base.shape[1]), order=order)
    z = z[:h, :w]
    return z / (np.abs(z).max() + 1e-9)


def fbm(h, w, cells_y, cells_x, octaves=4):
    out = np.zeros((h, w))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        out += amp * noise(h, w, cells_y * 2 ** o, cells_x * 2 ** o)
        total += amp
        amp *= 0.5
    return out / total


def wood(h, w, length_m, across_m, seed_shift=0.0, dark=(66, 38, 21), mid=(96, 57, 32), light=(124, 78, 45)):
    """Walnut, grain running along the image's x: h px across, w px along."""
    along = np.linspace(0, length_m, w)[None, :]
    across = np.linspace(0, across_m, h)[:, None]
    # Growth rings, wandering: the long warp bends whole bands, the short one makes the figure.
    warp = fbm(h, w, 3, max(2, int(length_m * 1.5))) * 0.06 + fbm(h, w, 5, max(4, int(length_m * 5))) * 0.012
    rings = (across + warp + seed_shift) * 16.0
    g = 0.5 + 0.5 * np.sin(rings * 2 * math.pi + 2.0 * fbm(h, w, 4, max(3, int(length_m * 3))))
    g = g ** 1.6
    # Fine fibres along the grain, and open pores.
    fibre = noise(h, w, max(8, int(across_m * 600)), max(4, int(length_m * 10)), order=1) * 0.5 + 0.5
    pores = (rng.random((h, w)) < 0.006) * 1.0
    pores = ndimage.uniform_filter1d(pores, size=9, axis=1)
    figure = fbm(h, w, 3, max(3, int(length_m * 4))) * 0.5 + 0.5
    t = np.clip(0.42 * g + 0.22 * fibre + 0.36 * figure, 0, 1)
    dark, mid, light = (np.array(c, float) for c in (dark, mid, light))
    col = np.where(t[..., None] < 0.5, dark + (mid - dark) * (t[..., None] / 0.5), mid + (light - mid) * ((t[..., None] - 0.5) / 0.5))
    col *= (1 - 0.18 * pores[..., None])
    return col


def cloth(h, w):
    fine = rng.standard_normal((h, w))
    fine = ndimage.gaussian_filter(fine, 0.6)
    fine /= np.abs(fine).max()
    mottle = fbm(h, w, 6, 10)
    shade = 1 + 0.035 * fine + 0.018 * mottle
    return CLOTH[None, None, :] * shade[..., None]


def leather(h, w):
    base = np.array([34, 26, 22], float)
    n = fbm(h, w, 12, 12) * 0.12 + ndimage.gaussian_filter(rng.standard_normal((h, w)), 0.8) * 0.05
    return base[None, None, :] * (1 + n[..., None])


# ---- the table -----------------------------------------------------------------------------

TEX = 2048
PLAN_H = 1152                 # rows 0..1151: the table seen from above
APRON_Y, APRON_H = 1152, 256  # the cabinet's sides
TILE_Y = 1408                 # below that: legs, leather, cushion faces, metal


def plan_uv(x, y):
    u = (x + A_OUT) / (2 * A_OUT)
    v = (y + B_OUT) / (2 * B_OUT)
    return (u, 1 - v * PLAN_H / TEX)


def region_uv(px0, py0, pw, ph, s, t):
    """(s, t) in [0, 1] inside a pixel region of the texture, as an OBJ uv (v up)."""
    return ((px0 + s * pw) / TEX, 1 - (py0 + t * ph) / TEX)


LEG_RG = (0, TILE_Y, 384, 640)
LEATHER_RG = (384, TILE_Y, 512, 512)
CUSHION_RG = (896, TILE_Y, 1152, 256)
METAL_RG = (896, TILE_Y + 256, 128, 128)
DARK_RG = (1024, TILE_Y + 256, 128, 128)


def paint_table():
    img = np.zeros((TEX, TEX, 3))
    # Plan: cloth in the middle, walnut rails mitred at the corners, sights.
    xs = np.linspace(-A_OUT, A_OUT, TEX)[None, :] * np.ones((PLAN_H, 1))
    ys = (np.linspace(-B_OUT, B_OUT, PLAN_H)[:, None]) * np.ones((1, TEX))
    plan = cloth(PLAN_H, TEX)
    along_x = wood(PLAN_H, TEX, 2 * A_OUT, 2 * B_OUT)
    along_y = wood(TEX, PLAN_H, 2 * B_OUT, 2 * A_OUT, seed_shift=0.37).transpose(1, 0, 2)
    rail = (np.abs(xs) > A_FIELD + CUSHION - 0.002) | (np.abs(ys) > B_FIELD + CUSHION - 0.002)
    # Long rails run the length; the end rails run across; they meet in mitres.
    dx = A_OUT - np.abs(xs)
    dy = B_OUT - np.abs(ys)
    ends = dx < dy
    woodplan = np.where(ends[..., None], along_y, along_x)
    plan = np.where(rail[..., None], woodplan, plan)
    # A hairline at each mitre and where wood meets the cushion.
    mitre = np.abs(dx - dy) < 0.0012
    plan[rail & mitre] *= 0.55
    edge = rail & ((np.abs(np.abs(xs) - (A_FIELD + CUSHION)) < 0.0012) & (np.abs(ys) < B_FIELD + CUSHION) |
                   (np.abs(np.abs(ys) - (B_FIELD + CUSHION)) < 0.0012) & (np.abs(xs) < A_FIELD + CUSHION))
    plan[edge] *= 0.5
    # A gentle sheen across the rail tops: lacquer catching the room.
    sheen = 1 + 0.06 * np.clip(fbm(PLAN_H, TEX, 2, 4), -1, 1)
    plan[rail] *= sheen[rail][:, None]
    img[:PLAN_H] = plan
    # Sights: mother-of-pearl diamonds 3 11/16 in back from the nose.
    sight_back = 0.0937
    sights = []
    for i in (1, 2, 3, 5, 6, 7):
        x = -A_FIELD + i * (2 * A_FIELD) / 8
        for sy in (-1, 1):
            sights.append((x, sy * (B_FIELD + sight_back), 0))
    for j in (1, 2, 3):
        y = -B_FIELD + j * (2 * B_FIELD) / 4
        for sx in (-1, 1):
            sights.append((sx * (A_FIELD + sight_back), y, 1))
    pearl = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(pearl)
    ppm_x = TEX / (2 * A_OUT)
    ppm_y = PLAN_H / (2 * B_OUT)
    for x, y, rot in sights:
        cx, cy = (x + A_OUT) * ppm_x, (y + B_OUT) * ppm_y
        L, Wd = 0.0135, 0.0068   # half-length along the rail, half-width
        if rot == 0:
            pts = [(cx - L * ppm_x, cy), (cx, cy - Wd * ppm_y), (cx + L * ppm_x, cy), (cx, cy + Wd * ppm_y)]
        else:
            pts = [(cx, cy - L * ppm_y), (cx + Wd * ppm_x, cy), (cx, cy + L * ppm_y), (cx - Wd * ppm_x, cy)]
        grow = [(cx + (px - cx) * 1.28, cy + (py - cy) * 1.28) for px, py in pts]
        d.polygon(grow, fill=(176, 140, 72))     # a brass surround
        d.polygon(pts, fill=(236, 232, 222))
        inner = [(cx + (px - cx) * 0.55, cy + (py - cy) * 0.55) for px, py in pts]
        d.polygon(inner, fill=(226, 228, 236))
    # The foot spot: a small white sticker.
    fx, fy = (A_FIELD / 2 + A_OUT) * ppm_x, (B_OUT) * ppm_y
    d.ellipse([fx - 0.006 * ppm_x, fy - 0.006 * ppm_y, fx + 0.006 * ppm_x, fy + 0.006 * ppm_y], fill=(228, 230, 222))
    img[:PLAN_H] = np.asarray(pearl, float)[:PLAN_H]
    # Apron: walnut along its length, a little darker; a groove line near the top.
    ap = wood(APRON_H, TEX, 3.0, 0.25, seed_shift=0.11) * 0.92
    ap[int(APRON_H * 0.18):int(APRON_H * 0.18) + 2] *= 0.55
    img[APRON_Y:APRON_Y + APRON_H] = ap
    x0, y0, w, h = LEG_RG
    img[y0:y0 + h, x0:x0 + w] = wood(w, h, 0.7, 0.4, seed_shift=0.6).transpose(1, 0, 2) * 0.95
    x0, y0, w, h = LEATHER_RG
    img[y0:y0 + h, x0:x0 + w] = leather(h, w)
    x0, y0, w, h = CUSHION_RG
    img[y0:y0 + h, x0:x0 + w] = cloth(h, w) * 0.97
    x0, y0, w, h = METAL_RG
    img[y0:y0 + h, x0:x0 + w] = np.array([24, 24, 26], float)
    x0, y0, w, h = DARK_RG
    img[y0:y0 + h, x0:x0 + w] = np.array([30, 20, 14], float)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(OUT, "table.png"))


def poly_tris(m, poly, z, uvf, up=(0, 0, 1)):
    """Triangulates a shapely polygon (holes allowed) at height z."""
    tris = shapely.constrained_delaunay_triangles(poly)
    for t in tris.geoms:
        if not poly.buffer(1e-7).contains(t.centroid):
            continue
        cs = list(t.exterior.coords)[:3]
        idx = [m.vert((x, y, z), uvf(x, y)) for x, y in cs]
        m.tri(*idx, up=np.array(up, float))


def densify(poly, step=0.01):
    return shapely.segmentize(poly, step)


def rounded_rect(a, b, r, n=10):
    pts = []
    for cx, cy, a0 in ((a - r, b - r, 0), (-a + r, b - r, 90), (-a + r, -b + r, 180), (a - r, -b + r, 270)):
        for i in range(n + 1):
            t = math.radians(a0 + 90 * i / n)
            pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    return pts


def build_table():
    m = Mesh()
    up = np.array([0, 0, 1.0])
    z_bed = BED
    holes = [Point(p["hole"]).buffer(p["r"], 48) for p in POCKETS]
    cuts = [Point(p["hole"]).buffer(p["cut"], 48) for p in POCKETS]
    inner = box(-A_FIELD - CUSHION, -B_FIELD - CUSHION, A_FIELD + CUSHION, B_FIELD + CUSHION)

    # 1. The bed: cloth out to the rail line, holes cut through it.
    bed = inner.difference(unary_union(holes))
    poly_tris(m, densify(bed, 0.12), z_bed, plan_uv)

    # 2. A leather ledge at cloth height between the hole and the rail's cut.
    ledge = unary_union(cuts).difference(inner).difference(unary_union(holes))
    leather_uv = lambda x, y: region_uv(*LEATHER_RG, (x * 7) % 1, (y * 7) % 1)
    # (leather is plain enough that a stretched, unwrapped mapping reads fine; keep it inside the cell)
    leather_uv = lambda x, y: region_uv(*LEATHER_RG, 0.1 + 0.8 * ((x + A_OUT) / (2 * A_OUT)), 0.1 + 0.8 * ((y + B_OUT) / (2 * B_OUT)))
    if not ledge.is_empty:
        for g in getattr(ledge, "geoms", [ledge]):
            poly_tris(m, g, z_bed - 0.0005, leather_uv)

    # 3. Rail tops: walnut, outer corners rounded, cut round each pocket.
    inset = 0.015
    outer_top = Polygon(rounded_rect(A_OUT - inset, B_OUT - inset, CORNER_ROUND - inset))
    rails = outer_top.difference(inner).difference(unary_union(cuts))
    for g in getattr(rails, "geoms", [rails]):
        poly_tris(m, g, z_bed + RAIL_TOP, plan_uv)

    # 4. The bullnose and apron: rings of the rounded rectangle stepping out and down.
    profile = [(inset, 0.0), (0.009, -0.0015), (0.004, -0.005), (0.001, -0.010), (0.0, -0.017),
               (0.0, -0.150), (-0.004, -0.154), (-0.004, -0.168), (0.0, -0.172), (0.02, -APRON)]
    rings = []
    for off, dz in profile:
        pts = rounded_rect(A_OUT - off, B_OUT - off, CORNER_ROUND - off)
        rings.append([(x, y, z_bed + RAIL_TOP + dz) for x, y in pts])
    # Where the cut for a pocket reaches the bullnose, the ring passes through
    # air; the cut is well inside it for these sizes, so the rings stay whole.
    n = len(rings[0])
    perim = [0.0]
    for i in range(1, n + 1):
        a, b = rings[0][(i - 1) % n], rings[0][i % n]
        perim.append(perim[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    vlen = [0.0]
    for k in range(1, len(profile)):
        vlen.append(vlen[-1] + math.hypot(profile[k][0] - profile[k - 1][0], profile[k][1] - profile[k - 1][1]))
    total_v = vlen[-1]
    for k in range(len(rings) - 1):
        for i in range(n):
            j = (i + 1) % n
            # u along the edge, wrapping every 3 m of the apron texture, never across a quad.
            u0 = (perim[i] % 3.0) / 3.0
            u1 = u0 + (perim[i + 1] - perim[i]) / 3.0
            if u1 > 1.0:
                u0, u1 = 0.0, (perim[i + 1] - perim[i]) / 3.0
            v0, v1 = vlen[k] / total_v, vlen[k + 1] / total_v
            a = m.vert(rings[k][i], region_uv(0, APRON_Y, TEX, APRON_H, u0, v0))
            b = m.vert(rings[k][j], region_uv(0, APRON_Y, TEX, APRON_H, u1, v0))
            c = m.vert(rings[k + 1][j], region_uv(0, APRON_Y, TEX, APRON_H, u1, v1))
            d = m.vert(rings[k + 1][i], region_uv(0, APRON_Y, TEX, APRON_H, u0, v1))
            # Outward faces: the rings run anticlockwise seen from above.
            m.quad(a, d, c, b)
    # The cabinet's underside.
    under = Polygon([(x, y) for x, y, z in rings[-1]])
    poly_tris(m, under, z_bed + RAIL_TOP - APRON, lambda x, y: region_uv(*DARK_RG, 0.5, 0.5), up=(0, 0, -1))

    # 5. Cushions: a profile swept along each nose, ends cut along the jaws.
    # (set back from the nose, height above the cloth)
    prof = [(0.011, 0.0), (0.006, 0.014), (0.002, 0.028), (0.0, NOSE), (0.0025, NOSE + 0.0035),
            (0.008, NOSE + 0.0055), (0.02, RAIL_TOP - 0.0005), (CUSHION, RAIL_TOP)]
    plen = [0.0]
    for k in range(1, len(prof)):
        plen.append(plen[-1] + math.hypot(prof[k][0] - prof[k - 1][0], prof[k][1] - prof[k - 1][1]))
    pockets_by_point = {}
    for p in POCKETS:
        for (pt, dirv) in p["jaws"]:
            pockets_by_point[(round(pt[0], 6), round(pt[1], 6))] = dirv
    mc = CORNER_MOUTH / math.sqrt(2)
    ms = SIDE_MOUTH / 2
    noses = []
    for sy in (-1, 1):
        noses.append(((-A_FIELD + mc, sy * B_FIELD), (-ms, sy * B_FIELD), (0, sy)))
        noses.append(((ms, sy * B_FIELD), (A_FIELD - mc, sy * B_FIELD), (0, sy)))
    for sx in (-1, 1):
        noses.append(((sx * A_FIELD, -B_FIELD + mc), (sx * A_FIELD, B_FIELD - mc), (sx, 0)))
    for (P, Q, out) in noses:
        jp = np.array(pockets_by_point[(round(P[0], 6), round(P[1], 6))])
        jq = np.array(pockets_by_point[(round(Q[0], 6), round(Q[1], 6))])
        P, Q, o = np.array(P), np.array(Q), np.array(out, float)
        length = np.linalg.norm(Q - P)

        def at(end, jaw, s):
            # The point on the jaw line from `end` that is `s` back from the nose.
            return end + jaw * (s / max(1e-9, np.dot(jaw, o)))

        layers = []
        for s, h in prof:
            layers.append((at(P, jp, s), at(Q, jq, s), h))
        for k in range(len(layers) - 1):
            (p0, q0, h0), (p1, q1, h1) = layers[k], layers[k + 1]
            top = k >= 4
            if top:
                uvf = lambda pt: plan_uv(pt[0], pt[1])
                a = m.vert((*p0, z_bed + h0), uvf(p0)); b = m.vert((*q0, z_bed + h0), uvf(q0))
                c = m.vert((*q1, z_bed + h1), uvf(q1)); d = m.vert((*p1, z_bed + h1), uvf(p1))
            else:
                v0, v1 = plen[k] / plen[4] * 0.9, plen[k + 1] / plen[4] * 0.9
                a = m.vert((*p0, z_bed + h0), region_uv(*CUSHION_RG, 0.02, v0))
                b = m.vert((*q0, z_bed + h0), region_uv(*CUSHION_RG, 0.02 + 0.96 * min(1, length / 1.3), v0))
                c = m.vert((*q1, z_bed + h1), region_uv(*CUSHION_RG, 0.02 + 0.96 * min(1, length / 1.3), v1))
                d = m.vert((*p1, z_bed + h1), region_uv(*CUSHION_RG, 0.02, v1))
            # Facing into the table and up: wind so the normal points against `o`.
            pa, pb, pc = np.array(m.v[a]), np.array(m.v[b]), np.array(m.v[c])
            nrm = np.cross(pb - pa, pc - pa)
            want = np.array([-o[0], -o[1], 0.6])
            if np.dot(nrm, want) < 0:
                m.quad(a, d, c, b)
            else:
                m.quad(a, b, c, d)
        # The back, against the rail: only seen where a pocket's cut takes the rail away.
        pb_, qb_ = at(P, jp, CUSHION), at(Q, jq, CUSHION)
        q4 = [m.vert((*pb_, z_bed), region_uv(*CUSHION_RG, 0.02, 0.95)), m.vert((*qb_, z_bed), region_uv(*CUSHION_RG, 0.9, 0.95)),
              m.vert((*qb_, z_bed + RAIL_TOP), region_uv(*CUSHION_RG, 0.9, 0.6)), m.vert((*pb_, z_bed + RAIL_TOP), region_uv(*CUSHION_RG, 0.02, 0.6))]
        m.tri(q4[0], q4[1], q4[2], up=np.array([o[0], o[1], 0]))
        m.tri(q4[0], q4[2], q4[3], up=np.array([o[0], o[1], 0]))
        # The jaw faces: each end of the cushion, a flat polygon down to the cloth.
        for end, jaw, sign in ((P, jp, -1), (Q, jq, 1)):
            chain = [(at(end, jaw, s), h) for s, h in prof]
            back = at(end, jaw, CUSHION)
            pts3 = [(*pt, z_bed + h) for pt, h in chain] + [(*back, z_bed)]
            # Facing out of the cushion, towards the pocket: along the nose, away from the other end.
            along = (Q - P) / length * sign
            ref = pts3[0]
            idx = []
            for pt in pts3:
                dist = np.linalg.norm(np.array(pt[:2]) - np.array(ref[:2]))
                idx.append(m.vert(pt, region_uv(*CUSHION_RG, 0.02 + dist * 6, 0.05 + (pt[2] - z_bed) / RAIL_TOP * 0.8)))
            for k in range(1, len(idx) - 1):
                m.tri(idx[0], idx[k], idx[k + 1], up=np.array([along[0], along[1], 0.0]))

    # 6. Pockets: the cut wall in the rail, the hole through the slate, the cup.
    for p in POCKETS:
        hx, hy = p["hole"]
        steps = 40
        # The wall from the rail top down to the cloth, only where it bounds wood.
        for i in range(steps):
            t0, t1 = 2 * math.pi * i / steps, 2 * math.pi * (i + 1) / steps
            a0 = (hx + p["cut"] * math.cos(t0), hy + p["cut"] * math.sin(t0))
            a1 = (hx + p["cut"] * math.cos(t1), hy + p["cut"] * math.sin(t1))
            mid = Point((a0[0] + a1[0]) / 2, (a0[1] + a1[1]) / 2)
            if inner.buffer(-1e-4).contains(mid) or not outer_top.buffer(0.002).contains(mid):
                continue
            u0, u1 = 0.1 + 0.8 * i / steps, 0.1 + 0.8 * (i + 1) / steps
            q = [m.vert((*a0, z_bed + RAIL_TOP), region_uv(*LEATHER_RG, u0, 0.1)),
                 m.vert((*a1, z_bed + RAIL_TOP), region_uv(*LEATHER_RG, u1, 0.1)),
                 m.vert((*a1, z_bed), region_uv(*LEATHER_RG, u1, 0.3)),
                 m.vert((*a0, z_bed), region_uv(*LEATHER_RG, u0, 0.3))]
            # Facing the hole's middle.
            m.tri(q[0], q[1], q[2], up=np.array([hx - mid.x, hy - mid.y, 0]))
            m.tri(q[0], q[2], q[3], up=np.array([hx - mid.x, hy - mid.y, 0]))
        # The hole and cup: a cylinder facing inwards and a floor.
        ring_top, ring_bot = [], []
        for i in range(steps + 1):
            t = 2 * math.pi * i / steps
            x, y = hx + p["r"] * math.cos(t), hy + p["r"] * math.sin(t)
            u = 0.1 + 0.8 * i / steps
            ring_top.append(m.vert((x, y, z_bed), region_uv(*LEATHER_RG, u, 0.35)))
            ring_bot.append(m.vert((x, y, z_bed - CUP), region_uv(*LEATHER_RG, u, 0.85)))
        for i in range(steps):
            t = 2 * math.pi * (i + 0.5) / steps
            inward = np.array([-math.cos(t), -math.sin(t), 0])
            m.tri(ring_top[i], ring_top[i + 1], ring_bot[i + 1], up=inward)
            m.tri(ring_top[i], ring_bot[i + 1], ring_bot[i], up=inward)
        floor = Point(p["hole"]).buffer(p["r"], 20)
        poly_tris(m, floor, z_bed - CUP, lambda x, y: region_uv(*LEATHER_RG, 0.5 + (x - hx) * 4, 0.5 + (y - hy) * 4))

    # 7. Legs: tapered square posts with a plinth, inset from the corners.
    leg_top = z_bed + RAIL_TOP - APRON
    for sx in (-1, 1):
        for sy in (-1, 1):
            cx, cy = sx * (A_OUT - 0.24), sy * (B_OUT - 0.19)
            sections = [(0.0, 0.085, METAL_RG), (0.012, 0.085, METAL_RG), (0.012, 0.082, LEG_RG),
                        (0.07, 0.082, LEG_RG), (0.078, 0.066, LEG_RG), (leg_top - 0.05, 0.075, LEG_RG),
                        (leg_top - 0.042, 0.082, LEG_RG), (leg_top, 0.082, LEG_RG)]
            for k in range(len(sections) - 1):
                z0, h0, rg = sections[k]
                z1, h1, _ = sections[k + 1]
                if z1 - z0 < 1e-9 and h0 == h1:
                    continue
                for side in range(4):
                    ang0 = side * math.pi / 2 + math.pi / 4
                    ang1 = ang0 + math.pi / 2
                    c0 = (math.cos(ang0) * math.sqrt(2), math.sin(ang0) * math.sqrt(2))
                    c1 = (math.cos(ang1) * math.sqrt(2), math.sin(ang1) * math.sqrt(2))
                    pa = (cx + c0[0] * h0, cy + c0[1] * h0, z0)
                    pb = (cx + c1[0] * h0, cy + c1[1] * h0, z0)
                    pc = (cx + c1[0] * h1, cy + c1[1] * h1, z1)
                    pd = (cx + c0[0] * h1, cy + c0[1] * h1, z1)
                    vv0, vv1 = z0 / leg_top, z1 / leg_top
                    ia = m.vert(pa, region_uv(*rg, 0.05 + side * 0.22, 1 - vv0 * 0.95))
                    ib = m.vert(pb, region_uv(*rg, 0.27 + side * 0.22, 1 - vv0 * 0.95))
                    ic = m.vert(pc, region_uv(*rg, 0.27 + side * 0.22, 1 - vv1 * 0.95))
                    idd = m.vert(pd, region_uv(*rg, 0.05 + side * 0.22, 1 - vv1 * 0.95))
                    outward = np.array([math.cos(ang0 + math.pi / 4), math.sin(ang0 + math.pi / 4), 0])
                    m.tri(ia, ib, ic, up=outward + np.array([0, 0, (h0 - h1) * 4]))
                    m.tri(ia, ic, idd, up=outward + np.array([0, 0, (h0 - h1) * 4]))
            # The floor pad under the foot.
            pad = box(cx - 0.085, cy - 0.085, cx + 0.085, cy + 0.085)
            poly_tris(m, pad, 0.0005, lambda x, y: region_uv(*METAL_RG, 0.5, 0.5), up=(0, 0, -1))

    paint_table()
    n = m.write("table", "table.png")
    print("table:", n, "triangles")


# ---- balls ---------------------------------------------------------------------------------

BALL_COLOURS = {
    1: (246, 192, 0), 2: (22, 74, 170), 3: (210, 36, 30), 4: (82, 40, 140),
    5: (242, 106, 24), 6: (12, 120, 64), 7: (128, 30, 34), 8: (16, 16, 18),
}
IVORY = np.array([246, 243, 233], float)
BW, BH = 1024, 512


def font(size):
    for f in ("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/segoeuib.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    return ImageFont.load_default()


def numeral(n, size=256):
    """The number as printed on a ball: black, bold, centred, underlined for 6 and 9."""
    img = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(img)
    f = font(int(size * (0.62 if n < 10 else 0.5)))
    text = str(n)
    bb = d.textbbox((0, 0), text, font=f)
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((size - w) / 2 - bb[0], (size - h) / 2 - bb[1] - size * 0.02), text, font=f, fill=255)
    if n in (6, 9):
        y = (size + h) / 2 + size * 0.03
        d.rectangle([size / 2 - w * 0.45, y, size / 2 + w * 0.45, y + size * 0.045], fill=255)
    return np.asarray(img, float) / 255.0


def ball_texture(n):
    lon = (np.arange(BW) + 0.5) / BW * 2 * math.pi
    lat = math.pi / 2 - (np.arange(BH) + 0.5) / BH * math.pi
    LON, LAT = np.meshgrid(lon, lat)
    d = np.stack([np.cos(LAT) * np.cos(LON), np.sin(LAT), np.cos(LAT) * np.sin(LON)], -1)  # y up
    img = np.zeros((BH, BW, 3)) + IVORY
    if n == 0:
        # A measle cue ball: red dots all over, so the spin shows.
        dots = [(0, 1, 0), (0, -1, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)]
        s = 1 / math.sqrt(3)
        dots += [(sx * s, sy * s, sz * s) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
        for c in dots:
            c = np.array(c) / np.linalg.norm(c)
            ang = np.arccos(np.clip(d @ c, -1, 1))
            edge = np.clip((0.115 - ang) / 0.01, 0, 1)
            img = img * (1 - edge[..., None]) + np.array([200, 26, 32]) * edge[..., None]
        return img
    colour = np.array(BALL_COLOURS[n if n <= 8 else n - 8], float)
    if n <= 8:
        img[:] = colour
    else:
        band = np.clip((0.62 - np.abs(LAT)) / 0.012, 0, 1)   # the stripe, a bit over a third of the height
        img = img * (1 - band[..., None]) + colour * band[..., None]
    # Two number disks, on the equator, opposite each other.
    num = numeral(n)
    for centre in ((1, 0, 0), (-1, 0, 0)):
        c = np.array(centre, float)
        e1 = np.array([0, 0, -1.0]) * (1 if centre[0] > 0 else -1)  # right, seen from outside
        e2 = np.array([0, 1.0, 0])                                    # up
        dot = d @ c
        ang = np.arccos(np.clip(dot, -1, 1))
        disk = np.clip((0.335 - ang) / 0.01, 0, 1)
        img = img * (1 - disk[..., None]) + IVORY * disk[..., None]
        with np.errstate(divide="ignore", invalid="ignore"):
            u = (d @ e1) / dot
            v = (d @ e2) / dot
        size = 0.52
        px = ((u / size) * 0.5 + 0.5) * (num.shape[1] - 1)
        py = ((-v / size) * 0.5 + 0.5) * (num.shape[0] - 1)
        ok = (dot > 0) & (px >= 0) & (px <= num.shape[1] - 1) & (py >= 0) & (py <= num.shape[0] - 1)
        ink = np.zeros_like(dot)
        ink[ok] = ndimage.map_coordinates(num, [py[ok], px[ok]], order=1)
        ink *= disk
        img = img * (1 - ink[..., None]) + np.array([14, 14, 16]) * ink[..., None]
    return img


def build_ball(n):
    m = Mesh()
    rows, cols = 24, 48
    for i in range(rows + 1):
        lat = math.pi / 2 - math.pi * i / rows
        for j in range(cols + 1):
            lon = 2 * math.pi * j / cols
            # Texture lon/lat to the mesh: texture x = cos lat cos lon, texture z = cos lat sin lon, y up.
            tx, ty, tz = math.cos(lat) * math.cos(lon), math.sin(lat), math.cos(lat) * math.sin(lon)
            # The mesh is in the table frame (z up): x = tx, y = -tz, z = ty.
            m.vert((R * tx, -R * tz, R * ty), (j / cols, 1 - i / rows))
    for i in range(rows):
        for j in range(cols):
            a = i * (cols + 1) + j
            b, c, d = a + 1, a + cols + 2, a + cols + 1
            centre = np.mean([m.v[k] for k in (a, b, c, d)], axis=0)
            if i == 0:
                m.tri(a, c, d, up=centre)
            elif i == rows - 1:
                m.tri(a, b, c, up=centre)
            else:
                m.tri(a, b, c, up=centre)
                m.tri(a, c, d, up=centre)
    img = ball_texture(n)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(OUT, "ball_%d.png" % n))
    m.write("ball_%d" % n, "ball_%d.png" % n)
    with open(os.path.join(OUT, "ball_%d.yml" % n), "w", newline="\n") as o:
        o.write("# Pool ball %d: 2 1/4 in, centred on the display so it turns about its middle.\n" % n)
        o.write("offset: [0, %.6f, 0]\ntexture-width: 512\ntriangles: 4000\nhitbox: false\n" % (-R))


def build_gloss():
    """What does not turn with a ball: the lamp's highlight on top and its contact shadow."""
    m = Mesh()
    # The highlight: a small cap just proud of the top, a little long, like a lamp's reflection.
    rows, cols = 4, 24
    cap = []
    for i in range(rows + 1):
        polar = math.radians(13) * i / rows
        ring = []
        for j in range(cols + 1):
            az = 2 * math.pi * j / cols
            # Stretched a little along x, then put back on a shell just proud of the ball.
            d = np.array([math.sin(polar) * math.cos(az) * 1.3, math.sin(polar) * math.sin(az) * 0.85, math.cos(polar)])
            d = d / np.linalg.norm(d) * R * 1.006
            ring.append(m.vert(tuple(d), region(0)))
        cap.append(ring)
    for i in range(rows):
        for j in range(cols):
            a, b, c, d = cap[i][j], cap[i][j + 1], cap[i + 1][j + 1], cap[i + 1][j]
            m.tri(a, d, c, up=np.array([0, 0, 1.0]))
            m.tri(a, c, b, up=np.array([0, 0, 1.0]))
    # The shadow: a disc on the cloth under the ball.
    centre = m.vert((0, 0, -R + 0.0006), region(1))
    rim = [m.vert((R * 0.78 * math.cos(2 * math.pi * j / 32), R * 0.78 * math.sin(2 * math.pi * j / 32), -R + 0.0006), region(1)) for j in range(33)]
    for j in range(32):
        m.tri(centre, rim[j], rim[j + 1], up=np.array([0, 0, 1.0]))
    img = np.zeros((16, 32, 3))
    img[:, :16] = (255, 255, 252)
    img[:, 16:] = CLOTH * 0.42
    Image.fromarray(img.astype(np.uint8)).save(os.path.join(OUT, "gloss.png"))
    m.write("gloss", "gloss.png")
    with open(os.path.join(OUT, "gloss.yml"), "w", newline="\n") as o:
        o.write("# A ball's highlight and contact shadow, which stay put while the ball turns.\n")
        o.write("offset: [0, %.6f, 0]\ntexture-width: 32\nhitbox: false\n" % (-(R - 0.0006)))


def region(k):
    """Swatch k of the gloss texture (two side by side)."""
    return (0.25 + 0.5 * k, 0.5)


# ---- the cue -------------------------------------------------------------------------------

CUE_LEN = 1.47
CW_, CH_ = 256, 2048


def cue_profile():
    """(distance from the tip, radius) down the cue."""
    return [(0.0, 0.0), (0.0, 0.0058), (0.004, 0.0063), (0.012, 0.0065), (0.012, 0.0066), (0.034, 0.0066),
            (0.034, 0.0066), (0.30, 0.0068), (0.60, 0.0085), (0.73, 0.0104), (0.73, 0.0112), (0.748, 0.0112),
            (0.748, 0.0106), (0.76, 0.0106), (1.05, 0.0128), (1.05, 0.0131), (1.30, 0.0141), (1.30, 0.0146),
            (1.44, 0.0150), (1.44, 0.0148), (1.468, 0.0146), (1.47, 0.0120), (1.47, 0.0)]


def paint_cue():
    img = np.zeros((CH_, CW_, 3))
    t = (np.arange(CH_) + 0.5) / CH_ * CUE_LEN          # down the cue
    a = (np.arange(CW_) + 0.5) / CW_                     # round it
    T, Aa = np.meshgrid(t, a, indexing="ij")
    maple = np.array([232, 206, 160], float)
    shaft_grain = wood(CW_, CH_, CUE_LEN, 0.05, dark=(206, 176, 128), mid=(228, 200, 154), light=(240, 220, 182)).transpose(1, 0, 2)
    ebony = wood(CW_, CH_, CUE_LEN, 0.05, seed_shift=0.3, dark=(16, 10, 8), mid=(30, 18, 12), light=(52, 30, 20)).transpose(1, 0, 2)
    img[:] = shaft_grain
    img[T < 0.012] = (52, 92, 170)                       # the tip, chalked blue
    img[(T >= 0.012) & (T < 0.034)] = (238, 236, 228)     # ferrule
    img[(T >= 0.73) & (T < 0.76)] = (24, 24, 26)          # joint collar
    joint_ring = (T >= 0.748) & (T < 0.752)
    img[joint_ring] = (196, 196, 204)
    fore = (T >= 0.76) & (T < 1.05)
    img[fore] = ebony[fore]
    # Four maple points running up the forearm into the ebony.
    for k in range(4):
        centre = (k + 0.5) / 4
        da = np.abs(((Aa - centre + 0.5) % 1.0) - 0.5)
        width = np.clip((1.05 - T) / 0.29, 0, 1) * 0.085
        pt = fore & (da < width) & (T > 0.80)
        veneer = fore & (da < width + 0.008) & (da >= width) & (T > 0.80)
        img[veneer] = (150, 22, 30)
        img[pt] = shaft_grain[pt] * 0.98
    for z0 in (1.045, 1.30, 1.435):
        img[(T >= z0) & (T < z0 + 0.005)] = (210, 210, 214)
    wrap = (T >= 1.05) & (T < 1.30)
    linen = 18 + 10 * (np.sin(Aa * 2 * math.pi * 40 + T * 900) > 0.3)
    img[wrap] = np.stack([linen, linen, linen + 2], -1)[wrap]
    sleeve = (T >= 1.30) & (T < 1.44)
    img[sleeve] = ebony[sleeve] * 1.2 + np.array([10, 0, 0])
    img[T >= 1.44] = (14, 14, 14)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(OUT, "cue.png"))


def build_cue():
    m = Mesh()
    segs = 20
    prof = cue_profile()
    rings = []
    for k, (d, r) in enumerate(prof):
        ring = []
        for j in range(segs + 1):
            ang = 2 * math.pi * j / segs
            # Along the cue is z up in the table frame, so it is +Y in the file; tip at 0.
            ring.append(m.vert((r * math.cos(ang), r * math.sin(ang), d), (j / segs, 1 - d / CUE_LEN)))
        rings.append(ring)
    for k in range(len(rings) - 1):
        if prof[k][1] == 0 and prof[k + 1][1] == 0:
            continue
        for j in range(segs):
            a, b, c, dd = rings[k][j], rings[k][j + 1], rings[k + 1][j + 1], rings[k + 1][j]
            ang = 2 * math.pi * (j + 0.5) / segs
            mid_r = (prof[k][1] + prof[k + 1][1]) / 2
            dz = prof[k + 1][0] - prof[k][0]
            dr = prof[k + 1][1] - prof[k][1]
            outward = np.array([math.cos(ang) * dz, math.sin(ang) * dz, -dr])
            if np.linalg.norm(outward) < 1e-12:
                continue
            if prof[k][1] > 0:
                m.tri(a, b, c, up=outward)
            if prof[k + 1][1] > 0:
                m.tri(a, c, dd, up=outward)
    paint_cue()
    m.write("cue", "cue.png")
    with open(os.path.join(OUT, "cue.yml"), "w", newline="\n") as o:
        o.write("# A 58 in cue, standing on its tip: the display turns it about the tip.\n")
        o.write("texture-width: 1024\ntriangles: 4000\nhitbox: false\n")


def build_ghost():
    """The ghost ball: a thin white ring where the cue ball will be when it meets the object ball."""
    m = Mesh()
    segs, tube_segs = 64, 6
    rr, tube = R, 0.0011
    grid = []
    for i in range(segs + 1):
        a = 2 * math.pi * i / segs
        row = []
        for j in range(tube_segs + 1):
            b = 2 * math.pi * j / tube_segs
            x = (rr + tube * math.cos(b)) * math.cos(a)
            y = (rr + tube * math.cos(b)) * math.sin(a)
            z = tube * math.sin(b)
            row.append(m.vert((x, y, z), (0.5, 0.5)))
        grid.append(row)
    for i in range(segs):
        for j in range(tube_segs):
            a, b, c, d = grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]
            ang = 2 * math.pi * (i + 0.5) / segs
            bb = 2 * math.pi * (j + 0.5) / tube_segs
            out = np.array([math.cos(bb) * math.cos(ang), math.cos(bb) * math.sin(ang), math.sin(bb)])
            m.tri(a, b, c, up=out)
            m.tri(a, c, d, up=out)
    Image.fromarray(np.full((4, 4, 3), 250, np.uint8)).save(os.path.join(OUT, "ghost.png"))
    m.write("ghost", "ghost.png")
    with open(os.path.join(OUT, "ghost.yml"), "w", newline="\n") as o:
        o.write("# The ghost ball's outline, centred on the display.\n")
        o.write("offset: [0, %.6f, 0]\ntexture-width: 16\nhitbox: false\n" % (-0.0011))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build_table()
    with open(os.path.join(OUT, "table.yml"), "w", newline="\n") as o:
        o.write("# A 9 ft pool table, made by tools/make_models.py. Kept whole: no simplifying.\n")
        o.write("texture-width: 2048\ntriangles: 60000\nhitbox: false\n")
    for n in range(16):
        build_ball(n)
    build_gloss()
    build_cue()
    build_ghost()
