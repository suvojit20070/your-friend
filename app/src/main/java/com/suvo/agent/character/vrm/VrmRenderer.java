package com.suvo.agent.character.vrm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.Matrix;

import com.suvo.agent.core.character.RigParams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * Draws a {@link VrmModel} with OpenGL ES 3.0: GPU skinning (joint matrices in a float texture),
 * CPU morph targets for the face, cel-style shading with the model's own textures, alpha mask and
 * blend materials. The pose is driven by the same {@link RigParams} as the 2D character, so the
 * 3D model blinks, lip-syncs, looks toward taps, breathes and shows expressions the same way.
 * Must be used on one GL thread only.
 */
final class VrmRenderer {

    private static final String VS = "#version 300 es\n"
            + "layout(location=0) in vec3 aPos;\n"
            + "layout(location=1) in vec3 aNrm;\n"
            + "layout(location=2) in vec2 aUv;\n"
            + "layout(location=3) in vec4 aJoint;\n"
            + "layout(location=4) in vec4 aWeight;\n"
            + "uniform mat4 uVP;\n"
            + "uniform mat4 uModel;\n"
            + "uniform int uSkinned;\n"
            + "uniform highp sampler2D uJoints;\n"
            + "out vec2 vUv;\n"
            + "out vec3 vN;\n"
            + "mat4 jm(float f){ int i = int(f + 0.5); return mat4(texelFetch(uJoints, ivec2(0,i), 0), texelFetch(uJoints, ivec2(1,i), 0),"
            + " texelFetch(uJoints, ivec2(2,i), 0), texelFetch(uJoints, ivec2(3,i), 0)); }\n"
            + "void main(){\n"
            + "  mat4 m = uModel;\n"
            + "  if (uSkinned == 1) {\n"
            + "    float ws = aWeight.x + aWeight.y + aWeight.z + aWeight.w;\n"
            + "    vec4 w = ws > 0.0001 ? aWeight / ws : vec4(1.0, 0.0, 0.0, 0.0);\n"
            + "    m = uModel * (w.x * jm(aJoint.x) + w.y * jm(aJoint.y) + w.z * jm(aJoint.z) + w.w * jm(aJoint.w));\n"
            + "  }\n"
            + "  vUv = aUv;\n"
            + "  vN = mat3(m) * aNrm;\n"
            + "  gl_Position = uVP * m * vec4(aPos, 1.0);\n"
            + "}\n";

    private static final String FS = "#version 300 es\n"
            + "precision mediump float;\n"
            + "in vec2 vUv;\n"
            + "in vec3 vN;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec4 uColor;\n"
            + "uniform int uAlphaMode;\n"
            + "uniform float uCutoff;\n"
            + "uniform vec3 uLight;\n"
            + "out vec4 outColor;\n"
            + "void main(){\n"
            + "  vec4 c = texture(uTex, vUv) * uColor;\n"
            + "  if (uAlphaMode == 1 && c.a < uCutoff) discard;\n"
            + "  vec3 n = normalize(gl_FrontFacing ? vN : -vN);\n"
            + "  float d = dot(n, uLight);\n"
            + "  float shade = mix(0.80, 1.0, smoothstep(-0.05, 0.30, d));\n"
            + "  float a = uAlphaMode == 2 ? c.a : 1.0;\n"
            + "  if (uAlphaMode == 2 && a < 0.01) discard;\n"
            + "  outColor = vec4(c.rgb * shade * a, a);\n"
            + "}\n";

    private static final class Gpu {
        int vao;
        int posVbo;
        int indexCount;
        VrmModel.Primitive prim;
        int mesh;
        float[] lastWeights;
        FloatBuffer scratch;
    }

    private static final class Item {
        int node;
        Gpu gpu;
        VrmModel.Material mat;
        boolean skinned;
        int skin;
    }

    private final VrmModel m;
    private final String framing;
    private int program;
    private int uVP, uModel, uSkinned, uJoints, uTex, uColor, uAlphaMode, uCutoff, uLight;
    private final List<Item> opaque = new ArrayList<>();
    private final List<Item> blended = new ArrayList<>();
    private final List<Integer> glBuffers = new ArrayList<>();
    private final List<Integer> glVaos = new ArrayList<>();
    private int[] imageTex;
    private int whiteTex;
    private int[] skinTex;
    private FloatBuffer jointData;

    // Rest pose
    private final int n;
    private final float[] restWorld;
    private final float[][] restWorldRot;
    private final float[] world;
    private final float[][] localRot;
    /** Per-frame local translations (only the hips move; everything else keeps its rest offset). */
    private final float[][] localPos;
    private final float[][] meshWeights;

    // Camera
    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] vp = new float[16];
    private final float[] root = new float[16];
    private final float[] tmp = new float[16];
    private final float[] center = new float[3];
    private float height = 1.6f;
    private float width = 0.6f;
    private float headY = 1.4f;
    private float topY = 1.6f;
    private float aspect = 1f;
    private int viewW = 1, viewH = 1;

    // Pose helpers
    private float[] leftArmRestDir, rightArmRestDir;
    private final float[] flipQ;

    VrmRenderer(VrmModel model, String framing) {
        m = model;
        this.framing = framing == null ? "full" : framing;
        n = m.nodes.size();
        restWorld = new float[16 * n];
        restWorldRot = new float[n][];
        world = new float[16 * n];
        localRot = new float[n][];
        meshWeights = new float[m.meshes.size()][];
        for (int i = 0; i < m.meshes.size(); i++) {
            VrmModel.Mesh mesh = m.meshes.get(i);
            int targets = 0;
            for (VrmModel.Primitive p : mesh.primitives) targets = Math.max(targets, p.targets.size());
            meshWeights[i] = new float[Math.max(targets, mesh.defaultWeights.length)];
        }
        // VRM 0.x faces -Z: turn it around so it faces the camera like VRM 1.0.
        flipQ = m.vrm0 ? VrmMath.axisAngle(0, 1, 0, (float) Math.PI) : VrmMath.quat(0, 0, 0, 1);
        Matrix.setIdentityM(root, 0);
        if (m.vrm0) Matrix.rotateM(root, 0, 180f, 0, 1, 0);
        localPos = new float[n][];
        for (int i = 0; i < n; i++) {
            localRot[i] = m.nodes.get(i).r.clone();
            localPos[i] = m.nodes.get(i).t.clone();
        }
        computeWorld(restWorld, null);
        for (int i = 0; i < n; i++) restWorldRot[i] = VrmMath.rotationOf(restWorld, i * 16);
        springs = new SpringSim(m);
        leftArmRestDir = boneDir("leftUpperArm", "leftLowerArm");
        rightArmRestDir = boneDir("rightUpperArm", "rightLowerArm");
        computeBounds();
    }

    // ------------------------------------------------------------------------------- GL setup

    void init() {
        program = link(VS, FS);
        uVP = GLES30.glGetUniformLocation(program, "uVP");
        uModel = GLES30.glGetUniformLocation(program, "uModel");
        uSkinned = GLES30.glGetUniformLocation(program, "uSkinned");
        uJoints = GLES30.glGetUniformLocation(program, "uJoints");
        uTex = GLES30.glGetUniformLocation(program, "uTex");
        uColor = GLES30.glGetUniformLocation(program, "uColor");
        uAlphaMode = GLES30.glGetUniformLocation(program, "uAlphaMode");
        uCutoff = GLES30.glGetUniformLocation(program, "uCutoff");
        uLight = GLES30.glGetUniformLocation(program, "uLight");

        whiteTex = texture(ByteBuffer.allocateDirect(4).put(new byte[]{-1, -1, -1, -1}), 1, 1, false);
        imageTex = new int[m.images.size()];
        Map<Integer, Boolean> used = new HashMap<>();
        for (VrmModel.Material mt : m.materials) if (mt.image >= 0) used.put(mt.image, true);
        for (int i = 0; i < imageTex.length; i++) imageTex[i] = used.containsKey(i) ? uploadImage(m.images.get(i)) : 0;

        skinTex = new int[m.skins.size()];
        int maxJoints = 1;
        for (int s = 0; s < m.skins.size(); s++) {
            int count = Math.max(1, m.skins.get(s).joints.length);
            maxJoints = Math.max(maxJoints, count);
            int[] t = new int[1];
            GLES30.glGenTextures(1, t, 0);
            skinTex[s] = t[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 4, count, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null);
        }
        jointData = ByteBuffer.allocateDirect(maxJoints * 64).order(ByteOrder.nativeOrder()).asFloatBuffer();

        Map<String, Gpu> gpuByPrim = new HashMap<>();
        for (int ni = 0; ni < n; ni++) {
            VrmModel.Node node = m.nodes.get(ni);
            if (node.mesh < 0 || node.mesh >= m.meshes.size()) continue;
            VrmModel.Mesh mesh = m.meshes.get(node.mesh);
            for (int pi = 0; pi < mesh.primitives.size(); pi++) {
                VrmModel.Primitive p = mesh.primitives.get(pi);
                String key = node.mesh + ":" + pi;
                Gpu gpu = gpuByPrim.get(key);
                if (gpu == null) {
                    gpu = upload(p, node.mesh);
                    gpuByPrim.put(key, gpu);
                }
                Item it = new Item();
                it.node = ni;
                it.gpu = gpu;
                it.mat = p.material >= 0 && p.material < m.materials.size() ? m.materials.get(p.material) : new VrmModel.Material();
                it.skinned = node.skin >= 0 && node.skin < m.skins.size() && p.joints != null;
                it.skin = node.skin;
                (it.mat.alphaMode == 2 ? blended : opaque).add(it);
            }
        }
    }

    private Gpu upload(VrmModel.Primitive p, int mesh) {
        Gpu g = new Gpu();
        g.prim = p;
        g.mesh = mesh;
        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        g.vao = vao[0];
        glVaos.add(g.vao);
        GLES30.glBindVertexArray(g.vao);
        boolean morph = !p.targets.isEmpty();
        g.posVbo = vbo(p.positions, 0, 3, morph);
        vbo(p.normals, 1, 3, false);
        vbo(p.uvs, 2, 2, false);
        if (p.joints != null) {
            vbo(p.joints, 3, 4, false);
            vbo(p.weights, 4, 4, false);
        } else {
            GLES30.glVertexAttrib4f(3, 0, 0, 0, 0);
            GLES30.glVertexAttrib4f(4, 1, 0, 0, 0);
        }
        int[] ib = new int[1];
        GLES30.glGenBuffers(1, ib, 0);
        glBuffers.add(ib[0]);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib[0]);
        IntBuffer idx = ByteBuffer.allocateDirect(p.indices.length * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        idx.put(p.indices).position(0);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.length * 4, idx, GLES30.GL_STATIC_DRAW);
        g.indexCount = p.indices.length;
        GLES30.glBindVertexArray(0);
        if (morph) {
            g.scratch = ByteBuffer.allocateDirect(p.positions.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            g.lastWeights = new float[p.targets.size()];
        }
        return g;
    }

    private int vbo(float[] data, int loc, int size, boolean dynamic) {
        int[] b = new int[1];
        GLES30.glGenBuffers(1, b, 0);
        glBuffers.add(b[0]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0]);
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, fb, dynamic ? GLES30.GL_DYNAMIC_DRAW : GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(loc);
        GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, 0, 0);
        return b[0];
    }

    private int uploadImage(VrmModel.Image im) {
        if (im.encoded == null) return 0;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(im.encoded, 0, im.encoded.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPremultiplied = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(im.encoded, 0, im.encoded.length, o);
        if (bmp == null) return 0;
        try {
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap c = bmp.copy(Bitmap.Config.ARGB_8888, false);
                bmp.recycle();
                bmp = c;
            }
            ByteBuffer px = ByteBuffer.allocateDirect(bmp.getWidth() * bmp.getHeight() * 4).order(ByteOrder.nativeOrder());
            bmp.copyPixelsToBuffer(px);
            px.position(0);
            return texture(px, bmp.getWidth(), bmp.getHeight(), true);
        } finally {
            bmp.recycle();
        }
    }

    private int texture(ByteBuffer rgba, int w, int h, boolean mip) {
        rgba.position(0);
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        if (mip) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        } else {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        }
        return t[0];
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new IllegalStateException("Shader error: " + log);
        }
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        if (ok[0] == 0) throw new IllegalStateException("Program error: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    void release() {
        if (program != 0) GLES30.glDeleteProgram(program);
        program = 0;
        int[] one = new int[1];
        for (int b : glBuffers) {
            one[0] = b;
            GLES30.glDeleteBuffers(1, one, 0);
        }
        for (int a : glVaos) {
            one[0] = a;
            GLES30.glDeleteVertexArrays(1, one, 0);
        }
        glBuffers.clear();
        glVaos.clear();
        List<Integer> tex = new ArrayList<>();
        if (imageTex != null) for (int t : imageTex) if (t != 0) tex.add(t);
        if (skinTex != null) for (int t : skinTex) if (t != 0) tex.add(t);
        if (whiteTex != 0) tex.add(whiteTex);
        for (int t : tex) {
            one[0] = t;
            GLES30.glDeleteTextures(1, one, 0);
        }
    }

    // ------------------------------------------------------------------------------- framing

    private float[] position(float[] mat, int node) {
        if (node < 0) return null;
        float[] p = {mat[node * 16 + 12], mat[node * 16 + 13], mat[node * 16 + 14]};
        if (m.vrm0) {
            p[0] = -p[0];
            p[2] = -p[2];
        }
        return p;
    }

    private float[] boneDir(String from, String to) {
        Integer a = m.bones.get(from), b = m.bones.get(to);
        if (a == null || b == null) return null;
        float[] pa = {restWorld[a * 16 + 12], restWorld[a * 16 + 13], restWorld[a * 16 + 14]};
        float[] pb = {restWorld[b * 16 + 12], restWorld[b * 16 + 13], restWorld[b * 16 + 14]};
        return VrmMath.norm3(new float[]{pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2]});
    }

    private void computeBounds() {
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float[] v = new float[4];
        float[] out = new float[4];
        for (int ni = 0; ni < n; ni++) {
            VrmModel.Node node = m.nodes.get(ni);
            if (node.mesh < 0 || node.mesh >= m.meshes.size()) continue;
            for (VrmModel.Primitive p : m.meshes.get(node.mesh).primitives) {
                if (p.min == null || p.max == null || p.min.length < 3) continue;
                boolean skinned = node.skin >= 0 && p.joints != null;
                for (int c = 0; c < 8; c++) {
                    v[0] = (c & 1) == 0 ? p.min[0] : p.max[0];
                    v[1] = (c & 2) == 0 ? p.min[1] : p.max[1];
                    v[2] = (c & 4) == 0 ? p.min[2] : p.max[2];
                    v[3] = 1;
                    if (skinned) System.arraycopy(v, 0, out, 0, 4);
                    else Matrix.multiplyMV(out, 0, restWorld, ni * 16, v, 0);
                    for (int k = 0; k < 3; k++) {
                        lo[k] = Math.min(lo[k], out[k]);
                        hi[k] = Math.max(hi[k], out[k]);
                    }
                }
            }
        }
        if (lo[0] > hi[0]) {
            lo = new float[]{-0.3f, 0, -0.2f};
            hi = new float[]{0.3f, 1.6f, 0.2f};
        }
        center[0] = (lo[0] + hi[0]) / 2;
        center[1] = (lo[1] + hi[1]) / 2;
        center[2] = (lo[2] + hi[2]) / 2;
        if (m.vrm0) {
            center[0] = -center[0];
            center[2] = -center[2];
        }
        height = Math.max(0.2f, hi[1] - lo[1]);
        width = Math.max(0.1f, hi[0] - lo[0]);
        topY = hi[1];
        float[] head = position(restWorld, m.bones.containsKey("head") ? m.bones.get("head") : -1);
        headY = head != null ? head[1] : lo[1] + height * 0.88f;
    }

    void resize(int w, int h) {
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
        aspect = viewW / (float) viewH;
        GLES30.glViewport(0, 0, viewW, viewH);
        float fov = 24f;
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, 50f);
        float tanHalf = (float) Math.tan(Math.toRadians(fov / 2));
        float targetY, fitH, fitW;
        if ("upper".equals(framing)) {
            fitH = Math.max(0.35f, (topY - headY) * 2.2f + height * 0.22f);
            targetY = topY - fitH / 2f;
            fitW = Math.min(width * 0.7f, height * 0.32f);
        } else {
            fitH = height * 1.04f;
            targetY = center[1];
            // The rest pose is a T/A-pose; the arms are lowered when drawn, so fit to the body, not the arm span.
            fitW = Math.min(width * 1.04f, height * 0.42f);
        }
        float dist = Math.max(fitH / 2f / tanHalf, fitW / 2f / (tanHalf * aspect));
        Matrix.setLookAtM(view, 0, center[0], targetY, center[2] + dist, center[0], targetY, center[2], 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        // Where the feet and the seat are on screen (fractions of the view height), so the floating
        // window can stand the character on the screen bottom or sit it on an edge.
        footFrac = screenFrac(center[0], center[1] - height / 2f, center[2]);
        float[] hips = position(restWorld, m.bones.containsKey("hips") ? m.bones.get("hips") : -1);
        seatFrac = hips == null ? -1f : screenFrac(hips[0], hips[1] - 0.07f, hips[2]);
    }

    volatile float footFrac = 1f;
    volatile float seatFrac = -1f;

    private float screenFrac(float x, float y, float z) {
        float[] p = {x, y, z, 1};
        float[] c = new float[4];
        Matrix.multiplyMV(c, 0, vp, 0, p, 0);
        if (c[3] <= 0) return 1f;
        return 1f - (c[1] / c[3] + 1f) / 2f;
    }

    /** Which region a tap at view coordinates hits (for the same reactions as the 2D character). */
    String regionAt(float x, float y) {
        float[] head = position(restWorld, m.bones.containsKey("head") ? m.bones.get("head") : -1);
        if (head == null) return y < viewH * 0.25f ? "HEAD" : "BODY";
        float[] p = {head[0], head[1], head[2], 1};
        float[] clip = new float[4];
        Matrix.multiplyMV(clip, 0, vp, 0, p, 0);
        if (clip[3] <= 0) return "BODY";
        float sy = (1 - (clip[1] / clip[3] + 1) / 2) * viewH;
        float headSize = viewH * 0.12f * (1.6f / Math.max(0.6f, height)) * ("upper".equals(framing) ? 2.6f : 1f);
        if (y < sy - headSize * 0.25f) return "HEAD";
        if (y < sy + headSize * 0.9f) return "FACE";
        return "BODY";
    }

    // ------------------------------------------------------------------------------- posing

    private void computeWorld(float[] out, float[][] rot) {
        float[] local = new float[16];
        for (int r : m.roots) walk(r, -1, out, rot, local);
    }

    private void walk(int i, int parent, float[] out, float[][] rot, float[] local) {
        if (i < 0 || i >= n) return;
        VrmModel.Node node = m.nodes.get(i);
        VrmMath.compose(rot == null ? node.t : localPos[i], rot == null ? node.r : rot[i], node.s, local, 0);
        if (parent < 0) System.arraycopy(local, 0, out, i * 16, 16);
        else Matrix.multiplyMM(out, i * 16, out, parent * 16, local, 0);
        for (int c : node.children) walk(c, i, out, rot, local);
    }

    /** Rotates a bone by {@code qDisplay} given in the camera-facing model space. */
    private void rotateBone(String bone, float[] qDisplay) {
        Integer idx = m.bones.get(bone);
        if (idx == null || idx < 0 || idx >= n) return;
        float[] q = VrmMath.mul(VrmMath.mul(VrmMath.inverse(flipQ), qDisplay), flipQ);
        int parent = m.nodes.get(idx).parent;
        float[] pr = parent >= 0 ? restWorldRot[parent] : VrmMath.quat(0, 0, 0, 1);
        float[] delta = VrmMath.mul(VrmMath.mul(VrmMath.inverse(pr), q), pr);
        localRot[idx] = VrmMath.normalize(VrmMath.mul(delta, localRot[idx]));
    }

    private static final float DEG = (float) (Math.PI / 180);

    // Smoothed activity levels (0..1) derived from the shared animation parameters.
    private float speak, think, listen, happy, lastTime = -1;
    // Sitting (floating character on top of something) and the idle "life" scheduler.
    private volatile boolean sitTarget;
    private float sit;
    private final java.util.Random rnd = new java.util.Random();
    private int idleAction = -1;
    private float idleStart, idleLen, nextIdleAt = 3f;
    private float weight, weightTarget, weightNextAt;
    private final SpringSim springs;
    private float lookYawExtra, lookPitchExtra;

    static final int IDLE_LOOK_AROUND = 0, IDLE_HAIR = 1, IDLE_HANDS = 2, IDLE_SWAY = 3, IDLE_GLANCE = 4, IDLE_STRETCH = 5;

    /** Sit on the edge of something (floating mode) instead of standing. */
    void setSitting(boolean s) {
        sitTarget = s;
    }

    /** External force for hair and clothes, in model-space units (e.g. from dragging the window). */
    void setWind(float x, float y, float z) {
        springs.wind[0] = m.vrm0 ? -x : x;
        springs.wind[1] = y;
        springs.wind[2] = m.vrm0 ? -z : z;
    }

    private static float approach(float cur, float target, float rate, float dt) {
        return cur + (target - cur) * (1f - (float) Math.exp(-rate * dt));
    }

    /** 0 → 1 → 0 envelope over an action's length, with soft ends. */
    private static float envelope(float t, float len) {
        if (t <= 0 || t >= len) return 0;
        float in = Math.min(1f, t / 0.7f), out = Math.min(1f, (len - t) / 0.8f);
        float e = Math.min(in, out);
        return e * e * (3 - 2 * e);
    }

    private static final String[] FINGERS = {"Index", "Middle", "Ring", "Little"};
    private static final String[] JOINTS = {"Proximal", "Intermediate", "Distal"};

    /**
     * Procedural full-body animation so the character never stands like a statue: a relaxed
     * contrapposto stance with the weight moving between the legs, breathing through chest and
     * shoulders, idle actions every few seconds (looking around, touching the hair, hands
     * together, swaying, glancing at you, stretching), talking gestures, a hand-to-chin pose while
     * thinking, leaning in while listening, a happy bounce, sitting on edges in floating mode, and
     * spring-bone physics for hair and clothes. Rotations are given in the camera-facing model
     * space and converted per bone, so VRM 0.x and 1.0 rigs behave the same.
     */
    private void pose(RigParams p, float time) {
        float dt = lastTime < 0 ? 1f / 30f : Math.max(0f, Math.min(0.1f, time - lastTime));
        lastTime = time;
        for (int i = 0; i < n; i++) {
            VrmModel.Node node = m.nodes.get(i);
            System.arraycopy(node.r, 0, localRot[i], 0, 4);
            System.arraycopy(node.t, 0, localPos[i], 0, 3);
        }
        speak = approach(speak, p.mouthOpen > 0.06f ? 1f : 0f, p.mouthOpen > 0.06f ? 4f : 1.2f, dt);
        think = approach(think, p.thinkDots > 0.15f ? 1f : 0f, 2.2f, dt);
        listen = approach(listen, p.listenLevel > 0.02f ? 1f : 0f, 2.5f, dt);
        happy = approach(happy, Math.max(p.eyeSmile, p.sparkle) > 0.3f ? 1f : 0f, 3f, dt);
        sit = approach(sit, sitTarget ? 1f : 0f, 5f, dt);
        float breath = p.breath - 0.5f;
        boolean busy = speak > 0.3f || think > 0.3f || listen > 0.3f;

        // ---- Idle scheduler: a new small action every few seconds while nothing else happens.
        if (idleAction >= 0 && time - idleStart > idleLen) idleAction = -1;
        if (idleAction < 0 && !busy && time > nextIdleAt) {
            idleAction = rnd.nextInt(sit > 0.5f ? 5 : 6);
            if (idleAction == IDLE_STRETCH && rnd.nextFloat() < 0.6f) idleAction = IDLE_LOOK_AROUND; // stretching is rare
            idleStart = time;
            idleLen = 3.2f + rnd.nextFloat() * 2.5f;
            nextIdleAt = time + idleLen + 2.5f + rnd.nextFloat() * 5f;
        }
        if (busy && idleAction >= 0) idleLen = Math.min(idleLen, time - idleStart + 0.8f); // wrap up quickly
        float idleT = time - idleStart;
        float env = idleAction >= 0 ? envelope(idleT, idleLen) : 0f;
        float aLook = idleAction == IDLE_LOOK_AROUND ? env : 0, aHair = idleAction == IDLE_HAIR ? env : 0;
        float aHands = idleAction == IDLE_HANDS ? env : 0, aSway = idleAction == IDLE_SWAY ? env : 0;
        float aGlance = idleAction == IDLE_GLANCE ? env : 0, aStretch = idleAction == IDLE_STRETCH ? env : 0;

        // ---- Weight shift: settles on one leg for a while, then moves to the other.
        if (time > weightNextAt) {
            weightTarget = weightTarget > 0 ? -(0.55f + rnd.nextFloat() * 0.45f) : (0.55f + rnd.nextFloat() * 0.45f);
            weightNextAt = time + 4f + rnd.nextFloat() * 5f;
        }
        weight = approach(weight, weightTarget * (1f - 0.5f * think) * (1f - sit), 0.9f, dt);
        float sway = aSway * (float) Math.sin(idleT * 2.2f) * 0.8f;
        float shift = weight + sway;
        float stand = 1f - sit;

        // ---- Hips
        float bounce = happy * stand * (float) Math.abs(Math.sin(time * 5.5)) * 0.014f;
        moveBone("hips", shift * 0.022f * stand, (breath * 0.004f + bounce - listen * 0.004f - Math.abs(shift) * 0.006f) * stand, listen * 0.01f);
        rotateBone("hips", VrmMath.mul(VrmMath.axisAngle(0, 0, 1, shift * 5.5f * stand * DEG),
                VrmMath.axisAngle(0, 1, 0, (shift * 6f + aLook * (float) Math.sin(idleT * 1.6f) * 6f) * DEG)));

        // ---- Legs: standing leg straight, the other relaxed at the knee (contrapposto); or sitting.
        float relaxL = Math.max(0f, -shift), relaxR = Math.max(0f, shift);
        float[] legTilt = VrmMath.axisAngle(0, 0, 1, -shift * 5.5f * stand * DEG);
        rotateBone("leftUpperLeg", VrmMath.mul(legTilt, VrmMath.axisAngle(1, 0, 0, (-relaxL * 9f * stand - sit * 86f) * DEG)));
        rotateBone("rightUpperLeg", VrmMath.mul(legTilt, VrmMath.axisAngle(1, 0, 0, (-relaxR * 9f * stand - sit * 82f) * DEG)));
        float kick = sit * (float) Math.sin(time * 1.3f) * 9f; // legs swing gently while sitting
        rotateBone("leftLowerLeg", VrmMath.axisAngle(1, 0, 0, (relaxL * 16f * stand + sit * 80f + kick) * DEG));
        rotateBone("rightLowerLeg", VrmMath.axisAngle(1, 0, 0, (relaxR * 16f * stand + sit * 84f - kick) * DEG));
        rotateBone("leftFoot", VrmMath.axisAngle(1, 0, 0, (relaxL * 6f * stand) * DEG));
        rotateBone("rightFoot", VrmMath.axisAngle(1, 0, 0, (relaxR * 6f * stand) * DEG));

        // ---- Spine, chest, shoulders
        float lean = listen * 6f + think * 2f + sit * 6f + aStretch * -8f;
        rotateBone("spine", VrmMath.mul(VrmMath.axisAngle(0, 0, 1, (-shift * 7f * stand - p.bodyX * 4f + sway * 3f) * DEG),
                VrmMath.axisAngle(1, 0, 0, (lean + breath * 2.2f) * DEG)));
        rotateBone("chest", VrmMath.mul(VrmMath.axisAngle(0, 1, 0, (speak * (float) Math.sin(time * 1.7) * 4f + p.angleX * 8f) * DEG),
                VrmMath.axisAngle(1, 0, 0, (-breath * 3.0f - aStretch * 6f) * DEG)));
        rotateBone("upperChest", VrmMath.axisAngle(1, 0, 0, -breath * 1.8f * DEG));
        float shrug = breath * 2.6f + aStretch * 10f;
        rotateBone("leftShoulder", VrmMath.axisAngle(0, 0, 1, shrug * DEG));
        rotateBone("rightShoulder", VrmMath.axisAngle(0, 0, 1, -shrug * DEG));

        // ---- Arms
        float swing = (float) Math.sin(time * 0.9) * 3f * stand;
        float gestL = speak * Math.max(0f, (float) Math.sin(time * 2.3 + 1.0)) * (1f - think);
        float gestR = speak * Math.max(0f, (float) Math.sin(time * 2.3 + 2.9)) * (1f - think);
        float lap = sit; // hands rest on the lap when sitting
        armDown("leftUpperArm", leftArmRestDir, 0);
        armDown("rightUpperArm", rightArmRestDir, 0);
        // Forward/back (around X), inward (around Y), outward/up (around Z)
        rotateBone("leftUpperArm", VrmMath.mul(VrmMath.axisAngle(0, 0, 1, (aStretch * 150f + aHands * -4f) * DEG),
                VrmMath.axisAngle(1, 0, 0, (-swing - gestL * 24f - aHands * 8f - lap * 14f) * DEG)));
        rotateBone("leftUpperArm", VrmMath.axisAngle(0, 1, 0, -(aHands * 26f + lap * 10f) * DEG));
        rotateBone("rightUpperArm", VrmMath.mul(VrmMath.axisAngle(0, 0, 1, (-aStretch * 150f - aHair * 100f + aHands * 4f) * DEG),
                VrmMath.axisAngle(1, 0, 0, (swing - gestR * 24f - think * 38f - aHands * 8f - aHair * 10f - lap * 14f) * DEG)));
        rotateBone("rightUpperArm", VrmMath.axisAngle(0, 1, 0, (think * 18f + aHands * 26f + lap * 10f) * DEG));
        // Elbows (in the rest frame: bending forward around the vertical axis)
        float elbowL = 18f + gestL * 55f + listen * 6f + aHands * 70f + lap * 40f + aStretch * -10f;
        float elbowR = 18f + gestR * 55f + think * 105f + listen * 6f + aHands * 70f + aHair * 25f + lap * 40f + aStretch * -10f;
        rotateBone("leftLowerArm", VrmMath.axisAngle(0, 1, 0, -elbowL * DEG));
        rotateBone("rightLowerArm", VrmMath.axisAngle(0, 1, 0, elbowR * DEG));
        // Touching the hair: the forearm folds up toward the head (around the forward axis).
        if (aHair > 0) rotateBone("rightLowerArm", VrmMath.axisAngle(0, 0, 1, -aHair * 125f * DEG));
        rotateBone("leftHand", VrmMath.axisAngle(1, 0, 0, (10f + gestL * 22f + aHands * 20f) * DEG));
        rotateBone("rightHand", VrmMath.axisAngle(1, 0, 0, (-10f - gestR * 22f - aHands * 20f - aHair * 25f) * DEG));
        // Fingers: naturally curled; open while gesturing; curled more when thinking.
        for (String f : FINGERS) {
            for (int k = 0; k < JOINTS.length; k++) {
                float base = (k == 0 ? 16f : 22f) + ("Little".equals(f) ? 7f : "Ring".equals(f) ? 4f : 0f);
                float curlL = base * (1f - 0.5f * gestL - 0.6f * aStretch);
                float curlR = base * (1f - 0.5f * gestR - 0.6f * aStretch) + think * (k == 0 ? 25f : 35f) - aHair * 8f;
                rotateBone("left" + f + JOINTS[k], VrmMath.axisAngle(0, 0, 1, -curlL * DEG));
                rotateBone("right" + f + JOINTS[k], VrmMath.axisAngle(0, 0, 1, curlR * DEG));
            }
        }
        rotateBone("leftThumbProximal", VrmMath.axisAngle(0, 1, 0, -12f * DEG));
        rotateBone("rightThumbProximal", VrmMath.axisAngle(0, 1, 0, 12f * DEG));

        // ---- Head: looks where the screen was tapped (strongly), nods while talking, tilts
        // while thinking or listening, looks around when idle, glances at you.
        float nod = speak * (float) Math.sin(time * 4.6) * 3f;
        float lookAround = aLook * (float) Math.sin(idleT * 1.6f) * 35f;
        lookYawExtra = approach(lookYawExtra, lookAround, 3f, dt);
        lookPitchExtra = approach(lookPitchExtra, aLook * -4f + aGlance * 2f + aStretch * -12f, 3f, dt);
        float yaw = (p.angleX * 55f - think * 10f + lookYawExtra) * DEG;
        float pitch = (p.angleY * 30f + nod - think * 8f - listen * 2f + lookPitchExtra + sit * 6f) * DEG;
        float roll = (-p.angleZ * 14f + think * 7f + listen * 6f + aGlance * 8f + aHair * -6f + shift * 3f * stand) * DEG;
        float[] look = VrmMath.mul(VrmMath.axisAngle(0, 1, 0, yaw), VrmMath.mul(VrmMath.axisAngle(1, 0, 0, pitch), VrmMath.axisAngle(0, 0, 1, roll)));
        rotateBone("neck", VrmMath.scale(look, 0.4f));
        rotateBone("head", VrmMath.scale(look, m.bones.containsKey("neck") ? 0.6f : 1f));

        // Eyes lead the head toward where it is looking.
        float[] eye = VrmMath.mul(VrmMath.axisAngle(0, 1, 0, (p.eyeBallX * 12 + p.angleX * 8 - think * 6 + lookYawExtra * 0.25f) * DEG),
                VrmMath.axisAngle(1, 0, 0, (p.eyeBallY * 9 - think * 6) * DEG));
        rotateBone("leftEye", eye);
        rotateBone("rightEye", eye);

        computeWorld(world, localRot);
        // Hair, skirt and accessories follow with physics.
        if (!springs.isEmpty()) {
            springs.step(dt, world, localPos, localRot);
            computeWorld(world, localRot);
        }
    }

    /** Glance (smile) and happy weights from the idle scheduler, used by the face. */
    private float idleGlance() {
        if (idleAction != IDLE_GLANCE) return 0;
        return envelope(lastTime - idleStart, idleLen);
    }

    /** Moves a bone by a display-space offset (metres). */
    private void moveBone(String bone, float dx, float dy, float dz) {
        Integer idx = m.bones.get(bone);
        if (idx == null || idx < 0 || idx >= n) return;
        float[] d = {m.vrm0 ? -dx : dx, dy, m.vrm0 ? -dz : dz};
        int parent = m.nodes.get(idx).parent;
        if (parent >= 0) {
            float[] inv = VrmMath.inverse(restWorldRot[parent]);
            float[] q = VrmMath.mul(VrmMath.mul(inv, new float[]{d[0], d[1], d[2], 0}), restWorldRot[parent]);
            d[0] = q[0];
            d[1] = q[1];
            d[2] = q[2];
            // Undo the parent's scale so the offset stays in metres.
            float sx = VrmMath.len(restWorld[parent * 16], restWorld[parent * 16 + 1], restWorld[parent * 16 + 2]);
            if (sx > 1e-6f) {
                d[0] /= sx;
                d[1] /= sx;
                d[2] /= sx;
            }
        }
        localPos[idx][0] += d[0];
        localPos[idx][1] += d[1];
        localPos[idx][2] += d[2];
    }

    private void armDown(String bone, float[] restDir, float sway) {
        if (restDir == null) return;
        // Model-space rest direction → display space.
        float[] d = restDir.clone();
        if (m.vrm0) {
            d[0] = -d[0];
            d[2] = -d[2];
        }
        float side = d[0] >= 0 ? 1 : -1;
        float[] target = VrmMath.norm3(new float[]{side * 0.26f, -1f, 0.05f});
        float[] q = VrmMath.between(d, target);
        if (sway != 0) q = VrmMath.mul(VrmMath.axisAngle(0, 0, 1, sway), q);
        rotateBone(bone, q);
    }

    // ------------------------------------------------------------------------------- expressions

    private float expr(String name, float w, Map<String, Float> out) {
        if (w <= 0.001f || !m.expressions.containsKey(name)) return 0;
        out.put(name, Math.min(1f, Math.max(out.containsKey(name) ? out.get(name) : 0f, w)));
        return w;
    }

    private void expressions(RigParams p) {
        Map<String, Float> e = new HashMap<>();
        float happy = clamp(p.eyeSmile * 0.8f + Math.max(0, p.mouthForm - 0.35f) * 0.6f, 0, 0.75f);
        float sad = clamp(-p.mouthForm * 0.7f + Math.max(0, p.browInner) * 0.3f, 0, 0.8f);
        float angry = clamp(-p.browForm - 0.25f, 0, 0.8f);
        float surprised = clamp((Math.max(p.eyeLOpen, p.eyeROpen) - 1.05f) / 0.25f, 0, 0.9f);
        expr("happy", happy, e);
        if (sad > 0.15f) expr("sad", sad, e);
        if (angry > 0.1f) expr("angry", angry, e);
        expr("surprised", surprised, e);
        // A happy face usually closes the eyes already; blink less so they do not fold twice.
        float eyeFactor = 1 - happy;
        float blinkL = clamp(1 - p.eyeLOpen, 0, 1) * eyeFactor;
        float blinkR = clamp(1 - p.eyeROpen, 0, 1) * eyeFactor;
        if (m.expressions.containsKey("blinkLeft") && m.expressions.containsKey("blinkRight") && Math.abs(blinkL - blinkR) > 0.05f) {
            expr("blinkLeft", blinkL, e);
            expr("blinkRight", blinkR, e);
        } else {
            expr("blink", (blinkL + blinkR) / 2, e);
        }
        float open = clamp(p.mouthOpen, 0, 1);
        float wide = p.mouthWide;
        // Shapes measured from the audio when speaking; otherwise loudness and the coarse form.
        float va = p.visActive;
        float jaw = (float) Math.pow(open, 0.8);
        float mA = (1 - va) * open * 0.9f + va * jaw * p.visA * 1.15f;
        float mI = (1 - va) * open * clamp((wide - 0.55f) * 2.2f, 0, 1) + va * jaw * p.visI;
        float mU = (1 - va) * open * clamp((0.45f - wide) * 2.4f, 0, 1) + va * jaw * p.visU;
        float mE = va * jaw * p.visE;
        float mO = va * jaw * p.visO;
        // Every shape opens the jaw a little, so quiet syllables still move the mouth.
        float minJaw = va * jaw * 0.25f;
        expr("aa", Math.max(mA, minJaw), e);
        expr("ih", mI, e);
        expr("ou", mU, e);
        expr("ee", mE, e);
        expr("oh", mO, e);
        // Glance at you with a little smile during the idle "glance" action.
        expr("happy", idleGlance() * 0.45f, e);

        for (float[] w : meshWeights) Arrays.fill(w, 0);
        for (int i = 0; i < m.meshes.size(); i++) {
            float[] d = m.meshes.get(i).defaultWeights;
            System.arraycopy(d, 0, meshWeights[i], 0, Math.min(d.length, meshWeights[i].length));
        }
        for (Map.Entry<String, Float> en : e.entrySet()) {
            for (VrmModel.Bind b : m.expressions.get(en.getKey())) {
                if (b.mesh < 0 || b.mesh >= meshWeights.length || b.target < 0 || b.target >= meshWeights[b.mesh].length) continue;
                meshWeights[b.mesh][b.target] = clamp(meshWeights[b.mesh][b.target] + en.getValue() * b.weight, 0, 1);
            }
        }
    }

    private void applyMorphs(Gpu g) {
        if (g.lastWeights == null) return;
        float[] w = meshWeights[g.mesh];
        boolean changed = false;
        for (int t = 0; t < g.lastWeights.length; t++) {
            float v = t < w.length ? w[t] : 0;
            if (Math.abs(v - g.lastWeights[t]) > 0.004f) changed = true;
        }
        if (!changed) return;
        float[] base = g.prim.positions;
        float[] out = new float[base.length];
        System.arraycopy(base, 0, out, 0, base.length);
        for (int t = 0; t < g.lastWeights.length; t++) {
            float v = t < w.length ? w[t] : 0;
            g.lastWeights[t] = v;
            float[] d = g.prim.targets.get(t);
            if (v <= 0.001f || d == null || d.length != out.length) continue;
            for (int k = 0; k < out.length; k++) out[k] += d[k] * v;
        }
        g.scratch.position(0);
        g.scratch.put(out).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.posVbo);
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, out.length * 4, g.scratch);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------------------- drawing

    void draw(RigParams p, float time) {
        pose(p, time);
        expressions(p);

        for (int s = 0; s < m.skins.size(); s++) {
            VrmModel.Skin skin = m.skins.get(s);
            jointData.position(0);
            for (int k = 0; k < skin.joints.length; k++) {
                int j = skin.joints[k];
                if (j < 0 || j >= n) Matrix.setIdentityM(tmp, 0);
                else Matrix.multiplyMM(tmp, 0, world, j * 16, skin.inverseBind, k * 16);
                jointData.put(tmp);
            }
            jointData.position(0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skinTex[s]);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 4, Math.max(1, skin.joints.length), GLES30.GL_RGBA, GLES30.GL_FLOAT, jointData);
        }

        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(uVP, 1, false, vp, 0);
        float[] light = VrmMath.norm3(new float[]{0.35f, 0.55f, 0.75f});
        GLES30.glUniform3f(uLight, light[0], light[1], light[2]);
        GLES30.glUniform1i(uTex, 0);
        GLES30.glUniform1i(uJoints, 1);

        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glDepthMask(true);
        for (Item it : opaque) drawItem(it);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        for (Item it : blended) drawItem(it);
        GLES30.glDepthMask(true);
        GLES30.glBindVertexArray(0);
    }

    private void drawItem(Item it) {
        applyMorphs(it.gpu);
        if (it.mat.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE);
        else {
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);
        }
        if (it.skinned) {
            GLES30.glUniformMatrix4fv(uModel, 1, false, root, 0);
            GLES30.glUniform1i(uSkinned, 1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, skinTex[it.skin]);
        } else {
            Matrix.multiplyMM(tmp, 0, root, 0, world, it.node * 16);
            GLES30.glUniformMatrix4fv(uModel, 1, false, tmp, 0);
            GLES30.glUniform1i(uSkinned, 0);
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int tex = it.mat.image >= 0 && it.mat.image < imageTex.length && imageTex[it.mat.image] != 0 ? imageTex[it.mat.image] : whiteTex;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        float[] c = it.mat.color;
        GLES30.glUniform4f(uColor, c[0], c[1], c[2], c[3]);
        GLES30.glUniform1i(uAlphaMode, it.mat.alphaMode);
        GLES30.glUniform1f(uCutoff, it.mat.cutoff);
        GLES30.glBindVertexArray(it.gpu.vao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, it.gpu.indexCount, GLES30.GL_UNSIGNED_INT, 0);
    }
}
