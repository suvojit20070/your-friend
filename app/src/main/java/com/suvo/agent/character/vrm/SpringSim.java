package com.suvo.agent.character.vrm;

import android.opengl.Matrix;

/**
 * Your Friend — Original project by Suvo.
 *
 * VRM spring-bone physics (the same Verlet scheme as UniVRM): hair, skirts, ribbons and other
 * "secondary" bones swing with inertia when the body moves, fall back under stiffness and gravity,
 * and are pushed out of the head/body collider spheres. External force ("wind") lets the floating
 * character's hair trail behind when you drag it across the screen.
 *
 * Works in model space on the renderer's world matrices; must run after the body pose.
 */
final class SpringSim {

    private final VrmModel m;
    private final int count;
    private final float[][] prevTail;
    private final float[][] currTail;
    private final float[][] boneAxis;   // tail direction in the joint's local space (unit)
    private final float[] length;
    private final float[][] tailLocal;  // tail position in the joint's local space
    private boolean initialised;
    private final float[] tmp = new float[16];
    private final float[] local = new float[16];
    private final float[] v4 = new float[4];
    private final float[] o4 = new float[4];
    /** Extra force in model space (m/s²-ish), e.g. from dragging the floating window. */
    final float[] wind = new float[3];
    /**
     * Joints hanging from the hips (skirt, coat tails) — not hair. While sitting they get pushed
     * forward over the thighs ({@link #lowerForce}) and kept outside the thighs
     * ({@link #legPush}), so the skirt drapes over the lap instead of hanging open between the legs.
     */
    private final boolean[] lower;
    final float[] lowerForce = new float[3];
    /** 0..1: how strongly the thighs push the skirt (the sitting amount). */
    float legPush;
    /** Thigh capsule radius in model units. */
    float legRadius = 0.07f;
    private final int legL0, legL1, legR0, legR1;
    /** Least gravity for clothes on the lower body. */
    private static final float LOWER_GRAVITY = 0.3f;

    SpringSim(VrmModel m) {
        this.m = m;
        count = m.springs.size();
        prevTail = new float[count][3];
        currTail = new float[count][3];
        boneAxis = new float[count][3];
        tailLocal = new float[count][3];
        length = new float[count];
        lower = new boolean[count];
        java.util.Set<Integer> humanoid = new java.util.HashSet<>(m.bones.values());
        java.util.Set<Integer> lowerRoots = new java.util.HashSet<>();
        for (String b : new String[]{"hips", "spine", "leftUpperLeg", "rightUpperLeg", "leftLowerLeg", "rightLowerLeg"}) {
            Integer idx = m.bones.get(b);
            if (idx != null) lowerRoots.add(idx);
        }
        legL0 = bone("leftUpperLeg");
        legL1 = bone("leftLowerLeg");
        legR0 = bone("rightUpperLeg");
        legR1 = bone("rightLowerLeg");
        for (int i = 0; i < count; i++) {
            // First humanoid bone above the joint: hips/spine/legs = clothes on the lower body.
            int p = m.nodes.get(m.springs.get(i).node).parent, guard = 0;
            while (p >= 0 && !humanoid.contains(p) && guard++ < 64) p = m.nodes.get(p).parent;
            lower[i] = p >= 0 && lowerRoots.contains(p);
        }
        for (int i = 0; i < count; i++) {
            VrmModel.SpringJoint j = m.springs.get(i);
            float[] t;
            if (j.tail >= 0 && j.tail < m.nodes.size() && m.nodes.get(j.tail).parent == j.node) {
                t = m.nodes.get(j.tail).t.clone();
            } else {
                // A tip: extend 7 cm along the direction the bone already points.
                float[] own = m.nodes.get(j.node).t;
                float len = VrmMath.len(own[0], own[1], own[2]);
                t = len < 1e-5f ? new float[]{0, -0.07f, 0} : new float[]{own[0] / len * 0.07f, own[1] / len * 0.07f, own[2] / len * 0.07f};
            }
            tailLocal[i] = t;
            boneAxis[i] = VrmMath.norm3(t);
        }
    }

    private int bone(String name) {
        Integer idx = m.bones.get(name);
        return idx == null || idx < 0 || idx >= m.nodes.size() ? -1 : idx;
    }

    boolean isEmpty() {
        return count == 0;
    }

    void reset() {
        initialised = false;
    }

    private void tailWorld(float[] world, int node, float[] tl, float[] out) {
        v4[0] = tl[0];
        v4[1] = tl[1];
        v4[2] = tl[2];
        v4[3] = 1;
        Matrix.multiplyMV(o4, 0, world, node * 16, v4, 0);
        out[0] = o4[0];
        out[1] = o4[1];
        out[2] = o4[2];
    }

    /**
     * Steps the simulation and writes new local rotations for the spring joints into
     * {@code localRot}, updating {@code world} for each joint as it goes (parents first).
     */
    void step(float dt, float[] world, float[][] localPos, float[][] localRot) {
        if (count == 0) return;
        if (!initialised || dt <= 0 || dt > 0.25f) {
            for (int i = 0; i < count; i++) {
                VrmModel.SpringJoint j = m.springs.get(i);
                tailWorld(world, j.node, tailLocal[i], currTail[i]);
                System.arraycopy(currTail[i], 0, prevTail[i], 0, 3);
                float[] h = {world[j.node * 16 + 12], world[j.node * 16 + 13], world[j.node * 16 + 14]};
                length[i] = VrmMath.len(currTail[i][0] - h[0], currTail[i][1] - h[1], currTail[i][2] - h[2]);
            }
            initialised = true;
            return;
        }
        dt = Math.min(dt, 1f / 20f);
        float[] head = new float[3];
        float[] next = new float[3];
        for (int i = 0; i < count; i++) {
            VrmModel.SpringJoint j = m.springs.get(i);
            int n = j.node;
            int parent = m.nodes.get(n).parent;
            VrmModel.Node node = m.nodes.get(n);
            // World matrix of the joint from its (already updated) parent and its rest rotation.
            VrmMath.compose(localPos[n], node.r, node.s, local, 0);
            if (parent >= 0) Matrix.multiplyMM(tmp, 0, world, parent * 16, local, 0);
            else System.arraycopy(local, 0, tmp, 0, 16);
            head[0] = tmp[12];
            head[1] = tmp[13];
            head[2] = tmp[14];
            float[] rot = VrmMath.rotationOf(tmp, 0);
            float[] axisWorld = rotate(rot, boneAxis[i]);

            float drag = Math.max(0f, Math.min(1f, j.drag));
            float stiff = j.stiffness * dt;
            // Clothes exported without gravity (common for VRoid skirts) still hang and sway.
            float grav = (lower[i] ? Math.max(j.gravity, LOWER_GRAVITY) : j.gravity) * dt;
            for (int k = 0; k < 3; k++) {
                float inertia = (currTail[i][k] - prevTail[i][k]) * (1f - drag);
                next[k] = currTail[i][k] + inertia + axisWorld[k] * stiff + j.gravityDir[k] * grav
                        + (wind[k] + (lower[i] ? lowerForce[k] : 0f)) * dt * dt;
            }
            constrain(head, next, length[i]);
            collide(j, world, head, next, length[i]);
            if (lower[i] && legPush > 0.05f) {
                pushOut(world, legL0, legL1, head, next, length[i]);
                pushOut(world, legR0, legR1, head, next, length[i]);
            }
            System.arraycopy(currTail[i], 0, prevTail[i], 0, 3);
            System.arraycopy(next, 0, currTail[i], 0, 3);

            // Rotate the joint so its bone points from head to the new tail.
            float[] want = VrmMath.norm3(new float[]{next[0] - head[0], next[1] - head[1], next[2] - head[2]});
            float[] fix = VrmMath.between(VrmMath.norm3(axisWorld), want);
            float[] newWorld = VrmMath.mul(fix, rot);
            float[] parentRot = parent >= 0 ? VrmMath.rotationOf(world, parent * 16) : VrmMath.quat(0, 0, 0, 1);
            localRot[n] = VrmMath.normalize(VrmMath.mul(VrmMath.inverse(parentRot), newWorld));
            VrmMath.compose(localPos[n], localRot[n], node.s, local, 0);
            if (parent >= 0) Matrix.multiplyMM(world, n * 16, world, parent * 16, local, 0);
            else System.arraycopy(local, 0, world, n * 16, 16);
        }
    }

    /** Keeps a skirt tail outside the thigh (capsule from hip to knee). */
    private void pushOut(float[] world, int a, int b, float[] head, float[] tail, float len) {
        if (a < 0 || b < 0) return;
        float ax = world[a * 16 + 12], ay = world[a * 16 + 13], az = world[a * 16 + 14];
        float bx = world[b * 16 + 12], by = world[b * 16 + 13], bz = world[b * 16 + 14];
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float l2 = abx * abx + aby * aby + abz * abz;
        if (l2 < 1e-9f) return;
        float t = ((tail[0] - ax) * abx + (tail[1] - ay) * aby + (tail[2] - az) * abz) / l2;
        t = Math.max(0f, Math.min(1f, t));
        float cx = ax + abx * t, cy = ay + aby * t, cz = az + abz * t;
        float r = legRadius * legPush;
        float dx = tail[0] - cx, dy = tail[1] - cy, dz = tail[2] - cz;
        float d = VrmMath.len(dx, dy, dz);
        if (d >= r) return;
        if (d < 1e-5f) {
            // Exactly on the axis: push up, over the thigh.
            dx = 0;
            dy = 1;
            dz = 0;
            d = 1;
        }
        float s = r / d;
        tail[0] = cx + dx * s;
        tail[1] = cy + dy * s;
        tail[2] = cz + dz * s;
        constrain(head, tail, len);
    }

    private static float[] rotate(float[] q, float[] v) {
        float[] p = {v[0], v[1], v[2], 0};
        float[] r = VrmMath.mul(VrmMath.mul(q, p), VrmMath.inverse(q));
        return new float[]{r[0], r[1], r[2]};
    }

    private static void constrain(float[] head, float[] tail, float len) {
        float dx = tail[0] - head[0], dy = tail[1] - head[1], dz = tail[2] - head[2];
        float d = VrmMath.len(dx, dy, dz);
        if (d < 1e-6f) {
            tail[1] = head[1] - len;
            return;
        }
        float s = len / d;
        tail[0] = head[0] + dx * s;
        tail[1] = head[1] + dy * s;
        tail[2] = head[2] + dz * s;
    }

    private void collide(VrmModel.SpringJoint j, float[] world, float[] head, float[] tail, float len) {
        for (int g : j.colliderGroups) {
            if (g < 0 || g >= m.colliderGroups.size()) continue;
            for (int ci : m.colliderGroups.get(g)) {
                if (ci < 0 || ci >= m.colliders.size()) continue;
                VrmModel.Collider c = m.colliders.get(ci);
                if (c.node < 0 || c.node >= m.nodes.size()) continue;
                float[] center = new float[3];
                tailWorld(world, c.node, c.offset, center);
                if (c.tail != null && c.tail.length == 3) {
                    // Capsule: nearest point on its segment.
                    float[] b = new float[3];
                    tailWorld(world, c.node, c.tail, b);
                    float[] ab = {b[0] - center[0], b[1] - center[1], b[2] - center[2]};
                    float l2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
                    float t = l2 < 1e-9f ? 0 : ((tail[0] - center[0]) * ab[0] + (tail[1] - center[1]) * ab[1] + (tail[2] - center[2]) * ab[2]) / l2;
                    t = Math.max(0, Math.min(1, t));
                    center[0] += ab[0] * t;
                    center[1] += ab[1] * t;
                    center[2] += ab[2] * t;
                }
                // Collider radius is in the collider bone's space; account for its scale.
                float scale = VrmMath.len(world[c.node * 16], world[c.node * 16 + 1], world[c.node * 16 + 2]);
                float r = c.radius * scale + j.hitRadius;
                float dx = tail[0] - center[0], dy = tail[1] - center[1], dz = tail[2] - center[2];
                float d = VrmMath.len(dx, dy, dz);
                if (d < r && d > 1e-6f) {
                    float s = r / d;
                    tail[0] = center[0] + dx * s;
                    tail[1] = center[1] + dy * s;
                    tail[2] = center[2] + dz * s;
                    constrain(head, tail, len);
                }
            }
        }
    }
}
