"""
Renders the generated models the way the game will light them (Minecraft's
two entity lights, back faces culled), from wherever you like, to check them
without a server.

    python tools/render_preview.py out.png [eye_x eye_y eye_z look_x look_y look_z]

Coordinates are the OBJ files' (blocks; y up; the table's middle on the floor
at the origin, its length along x).
"""
import math
import os
import sys

import numpy as np
from PIL import Image

MODELS = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "models")
R = 0.028575
BED = 0.775


def load(name):
    v, vt, faces = [], [], []
    for line in open(os.path.join(MODELS, name + ".obj")):
        p = line.split()
        if not p:
            continue
        if p[0] == "v":
            v.append([float(x) for x in p[1:4]])
        elif p[0] == "vt":
            vt.append([float(x) for x in p[1:3]])
        elif p[0] == "f":
            idx = [tuple(int(k) - 1 for k in q.split("/")) for q in p[1:]]
            for k in range(1, len(idx) - 1):
                faces.append((idx[0], idx[k], idx[k + 1]))
    tex = np.asarray(Image.open(os.path.join(MODELS, name + ".png")).convert("RGB"), float)
    return np.array(v), np.array(vt), faces, tex


L0 = np.array([0.2, 1.0, -0.7]); L0 /= np.linalg.norm(L0)
L1 = np.array([-0.2, 1.0, 0.7]); L1 /= np.linalg.norm(L1)


def render(objects, eye, look, w=1280, h=720, fov=70):
    eye, look = np.array(eye, float), np.array(look, float)
    f = look - eye; f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    focal = (h / 2) / math.tan(math.radians(fov) / 2)
    img = np.zeros((h, w, 3)) + np.array([118, 140, 170])
    zb = np.full((h, w), np.inf)
    for (v, vt, faces, tex), xf in objects:
        V = xf(v)
        rel = V - eye
        cx, cy, cz = rel @ r, rel @ u, rel @ f
        th, tw = tex.shape[:2]
        for tri in faces:
            ids = [t[0] for t in tri]
            tids = [t[1] for t in tri]
            if min(cz[ids]) < 0.02:
                continue
            P = V[ids]
            n = np.cross(P[1] - P[0], P[2] - P[0])
            nl = np.linalg.norm(n)
            if nl == 0:
                continue
            n /= nl
            if np.dot(n, eye - P[0]) <= 0:
                continue   # culled, as the game does
            light = min(1.0, 0.4 + 0.6 * (max(0, n @ L0) + max(0, n @ L1)))
            sx = w / 2 + cx[ids] / cz[ids] * focal
            sy = h / 2 - cy[ids] / cz[ids] * focal
            x0, x1 = int(max(0, math.floor(sx.min()))), int(min(w - 1, math.ceil(sx.max())))
            y0, y1 = int(max(0, math.floor(sy.min()))), int(min(h - 1, math.ceil(sy.max())))
            if x0 > x1 or y0 > y1:
                continue
            xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            d = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0])
            if abs(d) < 1e-12:
                continue
            w1 = ((xs - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (ys - sy[0])) / d
            w2 = ((sx[1] - sx[0]) * (ys - sy[0]) - (xs - sx[0]) * (sy[1] - sy[0])) / d
            w0 = 1 - w1 - w2
            inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not inside.any():
                continue
            iz = 1 / cz[ids]
            izp = w0 * iz[0] + w1 * iz[1] + w2 * iz[2]
            z = 1 / izp
            T = vt[tids]
            tu = (w0 * T[0, 0] * iz[0] + w1 * T[1, 0] * iz[1] + w2 * T[2, 0] * iz[2]) * z
            tv = (w0 * T[0, 1] * iz[0] + w1 * T[1, 1] * iz[1] + w2 * T[2, 1] * iz[2]) * z
            sub = zb[y0:y1 + 1, x0:x1 + 1]
            ok = inside & (z < sub)
            px = np.clip((tu * tw).astype(int), 0, tw - 1)
            py = np.clip(((1 - tv) * th).astype(int), 0, th - 1)
            col = tex[py, px] * light
            sub[ok] = z[ok]
            img[y0:y1 + 1, x0:x1 + 1][ok] = col[ok]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def place(dx=0.0, dy=0.0, dz=0.0, rot=None):
    def xf(v):
        out = v if rot is None else v @ rot.T
        return out + np.array([dx, dy, dz])
    return xf


def scene(break_view=True):
    objs = [(load("table"), place())]
    # A rack on the foot spot (x = +0.635), cue ball at the head.
    row = math.sqrt(3) * R + 0.0002
    order = [1, 9, 2, 10, 8, 3, 11, 4, 12, 5, 13, 6, 14, 7, 15]
    k = 0
    cache = {}
    for rr in range(5):
        for c in range(rr + 1):
            n = order[k]; k += 1
            x = 0.635 + rr * row
            z = -(c - rr / 2) * 2 * R
            cache.setdefault(n, load("ball_%d" % n))
            ang = (n * 1.7) % (2 * math.pi)
            rot = np.array([[math.cos(ang), 0, math.sin(ang)], [0, 1, 0], [-math.sin(ang), 0, math.cos(ang)]])
            objs.append((cache[n], place(x, BED + R, z, rot)))
            objs.append((load("gloss"), place(x, BED + R, z)))
    objs.append((load("ball_0"), place(-0.75, BED + R, 0.05)))
    objs.append((load("gloss"), place(-0.75, BED + R, 0.05)))
    # The cue behind the cue ball, raised 6 degrees, tip 4 cm back.
    t = math.radians(90 - 6)
    rot = np.array([[math.cos(t), -math.sin(t), 0], [math.sin(t), math.cos(t), 0], [0, 0, 1]])  # +y -> -x tilted
    rot = np.array([[0, -1, 0], [1, 0, 0], [0, 0, 1]]) @ np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1]])
    a = math.radians(6)
    axis = np.array([-math.cos(a), math.sin(a), 0])   # tip to butt
    # rotation taking +y to axis, about z
    ang = math.atan2(-axis[0], axis[1])
    rz = np.array([[math.cos(ang), -math.sin(ang), 0], [math.sin(ang), math.cos(ang), 0], [0, 0, 1]])
    objs.append((load("cue"), place(-0.75 - R - 0.04, BED + R, 0.05, rz)))
    return objs


if __name__ == "__main__":
    out = sys.argv[1]
    if len(sys.argv) >= 8:
        eye = [float(x) for x in sys.argv[2:5]]
        look = [float(x) for x in sys.argv[5:8]]
    else:
        eye, look = [-2.2, 1.62, 0.9], [0.2, 0.78, 0]
    render(scene(), eye, look).save(out)
