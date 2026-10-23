package com.suvo.agent.character.vrm;

/**
 * Your Friend — Original project by Suvo.
 *
 * Small quaternion / matrix helpers for posing VRM bones. Quaternions are {x, y, z, w}; matrices
 * are column-major float[16] like android.opengl.Matrix and glTF.
 */
final class VrmMath {

    private VrmMath() {
    }

    static float[] quat(float x, float y, float z, float w) {
        return new float[]{x, y, z, w};
    }

    static float[] axisAngle(float ax, float ay, float az, float radians) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-6f) return quat(0, 0, 0, 1);
        float s = (float) Math.sin(radians / 2) / len;
        return quat(ax * s, ay * s, az * s, (float) Math.cos(radians / 2));
    }

    /** a * b (apply b first, then a). */
    static float[] mul(float[] a, float[] b) {
        return quat(
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]);
    }

    static float[] inverse(float[] q) {
        float n = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
        if (n < 1e-12f) return quat(0, 0, 0, 1);
        return quat(-q[0] / n, -q[1] / n, -q[2] / n, q[3] / n);
    }

    static float[] normalize(float[] q) {
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1e-9f) return quat(0, 0, 0, 1);
        return quat(q[0] / n, q[1] / n, q[2] / n, q[3] / n);
    }

    /** Spherical blend from identity toward q by t (0..1). */
    static float[] scale(float[] q, float t) {
        float[] id = {0, 0, 0, 1};
        return slerp(id, q, t);
    }

    static float[] slerp(float[] a, float[] b, float t) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float[] bb = b;
        if (dot < 0) {
            dot = -dot;
            bb = quat(-b[0], -b[1], -b[2], -b[3]);
        }
        if (dot > 0.9995f) {
            return normalize(quat(a[0] + t * (bb[0] - a[0]), a[1] + t * (bb[1] - a[1]), a[2] + t * (bb[2] - a[2]), a[3] + t * (bb[3] - a[3])));
        }
        double th = Math.acos(dot);
        double s = Math.sin(th);
        float wa = (float) (Math.sin((1 - t) * th) / s);
        float wb = (float) (Math.sin(t * th) / s);
        return quat(wa * a[0] + wb * bb[0], wa * a[1] + wb * bb[1], wa * a[2] + wb * bb[2], wa * a[3] + wb * bb[3]);
    }

    /** Shortest rotation taking unit vector u to unit vector v. */
    static float[] between(float[] u, float[] v) {
        float d = u[0] * v[0] + u[1] * v[1] + u[2] * v[2];
        if (d > 0.99999f) return quat(0, 0, 0, 1);
        if (d < -0.99999f) {
            float[] axis = Math.abs(u[0]) < 0.9f ? cross(u, new float[]{1, 0, 0}) : cross(u, new float[]{0, 1, 0});
            return axisAngle(axis[0], axis[1], axis[2], (float) Math.PI);
        }
        float[] c = cross(u, v);
        return normalize(quat(c[0], c[1], c[2], 1 + d));
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float[] norm3(float[] v) {
        float n = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (n < 1e-9f) return new float[]{0, -1, 0};
        return new float[]{v[0] / n, v[1] / n, v[2] / n};
    }

    /** Column-major TRS matrix into out[off..off+15]. */
    static void compose(float[] t, float[] q, float[] s, float[] out, int off) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        out[off] = (1 - 2 * (yy + zz)) * s[0];
        out[off + 1] = (2 * (xy + wz)) * s[0];
        out[off + 2] = (2 * (xz - wy)) * s[0];
        out[off + 3] = 0;
        out[off + 4] = (2 * (xy - wz)) * s[1];
        out[off + 5] = (1 - 2 * (xx + zz)) * s[1];
        out[off + 6] = (2 * (yz + wx)) * s[1];
        out[off + 7] = 0;
        out[off + 8] = (2 * (xz + wy)) * s[2];
        out[off + 9] = (2 * (yz - wx)) * s[2];
        out[off + 10] = (1 - 2 * (xx + yy)) * s[2];
        out[off + 11] = 0;
        out[off + 12] = t[0];
        out[off + 13] = t[1];
        out[off + 14] = t[2];
        out[off + 15] = 1;
    }

    /** Rotation part of a column-major matrix (scale removed) as a quaternion. */
    static float[] rotationOf(float[] m, int off) {
        float sx = len(m[off], m[off + 1], m[off + 2]);
        float sy = len(m[off + 4], m[off + 5], m[off + 6]);
        float sz = len(m[off + 8], m[off + 9], m[off + 10]);
        if (sx < 1e-9f) sx = 1;
        if (sy < 1e-9f) sy = 1;
        if (sz < 1e-9f) sz = 1;
        float m00 = m[off] / sx, m10 = m[off + 1] / sx, m20 = m[off + 2] / sx;
        float m01 = m[off + 4] / sy, m11 = m[off + 5] / sy, m21 = m[off + 6] / sy;
        float m02 = m[off + 8] / sz, m12 = m[off + 9] / sz, m22 = m[off + 10] / sz;
        float tr = m00 + m11 + m22;
        float x, y, z, w;
        if (tr > 0) {
            float s = (float) Math.sqrt(tr + 1.0) * 2;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        return normalize(quat(x, y, z, w));
    }

    static void decompose(float[] m, float[] t, float[] r, float[] s) {
        t[0] = m[12];
        t[1] = m[13];
        t[2] = m[14];
        s[0] = len(m[0], m[1], m[2]);
        s[1] = len(m[4], m[5], m[6]);
        s[2] = len(m[8], m[9], m[10]);
        float[] q = rotationOf(m, 0);
        System.arraycopy(q, 0, r, 0, 4);
    }

    static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
