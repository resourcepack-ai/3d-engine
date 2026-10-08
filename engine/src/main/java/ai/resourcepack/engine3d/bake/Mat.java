package ai.resourcepack.engine3d.bake;

/**
 * 4x4 matrices (column-major, as glTF stores them), quaternions ([x, y, z, w])
 * and vectors, as plain double arrays. Everything returns a new array.
 */
public final class Mat {

    private Mat() {
    }

    public static double[] ident() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    public static double[] mul(double[] a, double[] b) {
        double[] o = new double[16];
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                o[c * 4 + r] = s;
            }
        return o;
    }

    public static double[] trs(double[] t, double[] q, double[] s) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        return new double[]{
                (1 - 2 * (yy + zz)) * s[0], 2 * (xy + wz) * s[0], 2 * (xz - wy) * s[0], 0,
                2 * (xy - wz) * s[1], (1 - 2 * (xx + zz)) * s[1], 2 * (yz + wx) * s[1], 0,
                2 * (xz + wy) * s[2], 2 * (yz - wx) * s[2], (1 - 2 * (xx + yy)) * s[2], 0,
                t[0], t[1], t[2], 1,
        };
    }

    public static double[] xform(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12],
                m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13],
                m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14],
        };
    }

    /** The direction part of {@link #xform}: no translation. */
    public static double[] rotate(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[4] * v[1] + m[8] * v[2],
                m[1] * v[0] + m[5] * v[1] + m[9] * v[2],
                m[2] * v[0] + m[6] * v[1] + m[10] * v[2],
        };
    }

    /** The 3x3 part of a matrix, translation dropped. */
    public static double[] linear(double[] m) {
        return new double[]{m[0], m[1], m[2], 0, m[4], m[5], m[6], 0, m[8], m[9], m[10], 0, 0, 0, 0, 1};
    }

    public static double[] pos(double[] m) {
        return new double[]{m[12], m[13], m[14]};
    }

    public static double[] invert(double[] m) {
        double a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3], a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
        double a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11], a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];
        double b00 = a00 * a11 - a01 * a10, b01 = a00 * a12 - a02 * a10, b02 = a00 * a13 - a03 * a10;
        double b03 = a01 * a12 - a02 * a11, b04 = a01 * a13 - a03 * a11, b05 = a02 * a13 - a03 * a12;
        double b06 = a20 * a31 - a21 * a30, b07 = a20 * a32 - a22 * a30, b08 = a20 * a33 - a23 * a30;
        double b09 = a21 * a32 - a22 * a31, b10 = a21 * a33 - a23 * a31, b11 = a22 * a33 - a23 * a32;
        double det = 1 / (b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06);
        return new double[]{
                (a11 * b11 - a12 * b10 + a13 * b09) * det, (a02 * b10 - a01 * b11 - a03 * b09) * det, (a31 * b05 - a32 * b04 + a33 * b03) * det, (a22 * b04 - a21 * b05 - a23 * b03) * det,
                (a12 * b08 - a10 * b11 - a13 * b07) * det, (a00 * b11 - a02 * b08 + a03 * b07) * det, (a32 * b02 - a30 * b05 - a33 * b01) * det, (a20 * b05 - a22 * b02 + a23 * b01) * det,
                (a10 * b10 - a11 * b08 + a13 * b06) * det, (a01 * b08 - a00 * b10 - a03 * b06) * det, (a30 * b04 - a31 * b02 + a33 * b00) * det, (a21 * b02 - a20 * b04 - a23 * b00) * det,
                (a11 * b07 - a10 * b09 - a12 * b06) * det, (a00 * b09 - a01 * b07 + a02 * b06) * det, (a31 * b01 - a30 * b03 - a32 * b00) * det, (a20 * b03 - a21 * b01 + a22 * b00) * det,
        };
    }

    // ---- quaternions --------------------------------------------------------------

    public static double[] qmul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
        };
    }

    public static double[] qinv(double[] q) {
        return new double[]{-q[0], -q[1], -q[2], q[3]};
    }

    public static double[] normalize(double[] v) {
        double l = 0;
        for (double x : v) l += x * x;
        l = Math.sqrt(l);
        if (l == 0) return v.clone();
        double[] o = new double[v.length];
        for (int i = 0; i < v.length; i++) o[i] = v[i] / l;
        return o;
    }

    public static double[] slerp(double[] a, double[] b, double t) {
        double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        double[] bb = d < 0 ? new double[]{-b[0], -b[1], -b[2], -b[3]} : b;
        d = Math.abs(d);
        if (d > 0.9995) {
            double[] o = new double[4];
            for (int i = 0; i < 4; i++) o[i] = a[i] + (bb[i] - a[i]) * t;
            return normalize(o);
        }
        double th = Math.acos(d), s = Math.sin(th);
        double wa = Math.sin((1 - t) * th) / s, wb = Math.sin(t * th) / s;
        double[] o = new double[4];
        for (int i = 0; i < 4; i++) o[i] = a[i] * wa + bb[i] * wb;
        return o;
    }

    public static double[] lerp(double[] a, double[] b, double t) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] + (b[i] - a[i]) * t;
        return o;
    }

    /** XYZ Euler angles in degrees, as a quaternion. */
    public static double[] euler(double[] deg) {
        double h = Math.PI / 360;
        double cx = Math.cos(deg[0] * h), sx = Math.sin(deg[0] * h);
        double cy = Math.cos(deg[1] * h), sy = Math.sin(deg[1] * h);
        double cz = Math.cos(deg[2] * h), sz = Math.sin(deg[2] * h);
        return new double[]{
                sx * cy * cz - cx * sy * sz,
                cx * sy * cz + sx * cy * sz,
                cx * cy * sz - sx * sy * cz,
                cx * cy * cz + sx * sy * sz,
        };
    }

    /** The rotation part of a (possibly scaled) matrix as a unit quaternion. */
    public static double[] matQuat(double[] m) {
        double[] x = normalize(new double[]{m[0], m[1], m[2]});
        double[] y = normalize(new double[]{m[4], m[5], m[6]});
        double[] z = normalize(new double[]{m[8], m[9], m[10]});
        double m00 = x[0], m11 = y[1], m22 = z[2], tr = m00 + m11 + m22;
        double[] q;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            q = new double[]{(y[2] - z[1]) / s, (z[0] - x[2]) / s, (x[1] - y[0]) / s, s / 4};
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            q = new double[]{s / 4, (y[0] + x[1]) / s, (z[0] + x[2]) / s, (y[2] - z[1]) / s};
        } else if (m11 > m22) {
            double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            q = new double[]{(y[0] + x[1]) / s, s / 4, (z[1] + y[2]) / s, (z[0] - x[2]) / s};
        } else {
            double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            q = new double[]{(z[0] + x[2]) / s, (z[1] + y[2]) / s, s / 4, (x[1] - y[0]) / s};
        }
        return normalize(q);
    }

    /** The shortest rotation taking direction a onto direction b. */
    public static double[] fromTo(double[] a, double[] b) {
        double[] u = normalize(a), v = normalize(b);
        double d = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
        if (d > 0.999999) return new double[]{0, 0, 0, 1};
        double[] c = cross(u, v);
        return normalize(new double[]{c[0], c[1], c[2], 1 + d});
    }

    // ---- vectors ------------------------------------------------------------------

    public static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    public static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    public static double[] scale(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    public static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static double dist2(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return x * x + y * y + z * z;
    }
}
