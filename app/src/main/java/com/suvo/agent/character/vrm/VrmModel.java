package com.suvo.agent.character.vrm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * A VRM avatar (VRM 0.x or 1.0) read from its .vrm / .glb file: the glTF scene graph, meshes with
 * skinning and morph targets, materials and textures, plus the VRM humanoid bone map and facial
 * expressions (blink, mouth shapes a/i/u/e/o, happy, angry, sad, surprised...). Pure parsing — no
 * GL — so it can run on a background thread. Textures stay encoded until the renderer uploads them.
 */
public final class VrmModel {

    public static final class Node {
        public String name = "";
        public int parent = -1;
        public int[] children = new int[0];
        public float[] t = {0, 0, 0};
        public float[] r = {0, 0, 0, 1};
        public float[] s = {1, 1, 1};
        /** Non-null when the file gives a matrix instead of TRS (then it is decomposed into t/r/s). */
        public int mesh = -1;
        public int skin = -1;
    }

    public static final class Primitive {
        public float[] positions;
        public float[] normals;
        public float[] uvs;
        public float[] joints;   // 4 per vertex (as floats for the shader)
        public float[] weights;  // 4 per vertex
        public int[] indices;
        public int material = -1;
        /** Morph target position deltas, one array per target (3 per vertex). */
        public List<float[]> targets = new ArrayList<>();
        public float[] min;
        public float[] max;

        public int vertexCount() {
            return positions == null ? 0 : positions.length / 3;
        }
    }

    public static final class Mesh {
        public List<Primitive> primitives = new ArrayList<>();
        public float[] defaultWeights = new float[0];
    }

    public static final class Skin {
        public int[] joints;
        public float[] inverseBind; // 16 per joint, column-major
    }

    public static final class Material {
        public float[] color = {1, 1, 1, 1};
        public int image = -1;
        public int alphaMode; // 0 opaque, 1 mask, 2 blend
        public float cutoff = 0.5f;
        public boolean doubleSided;
    }

    public static final class Image {
        public byte[] encoded;
        public String mime = "";
    }

    /** One morph target change of an expression. */
    public static final class Bind {
        public final int mesh;
        public final int target;
        public final float weight; // 0..1

        Bind(int mesh, int target, float weight) {
            this.mesh = mesh;
            this.target = target;
            this.weight = weight;
        }
    }

    /** One simulated bone of a hair/skirt/accessory chain (VRM spring bones). */
    public static final class SpringJoint {
        public int node;
        /** Node whose position is this joint's tail, or -1 (a tip: tail extended along the bone). */
        public int tail = -1;
        public float stiffness = 1f;
        public float gravity = 0f;
        public float[] gravityDir = {0, -1, 0};
        public float drag = 0.4f;
        public float hitRadius = 0.02f;
        public int[] colliderGroups = new int[0];
    }

    /** Sphere (or capsule, when {@code tail} is set) that hair cannot pass through. */
    public static final class Collider {
        public int node;
        public float[] offset = {0, 0, 0};
        public float radius;
        public float[] tail;
    }

    public final List<Node> nodes = new ArrayList<>();
    /** Spring joints in parent-before-child order. */
    public final List<SpringJoint> springs = new ArrayList<>();
    public final List<Collider> colliders = new ArrayList<>();
    /** Collider group → collider indices. */
    public final List<int[]> colliderGroups = new ArrayList<>();
    public final List<Mesh> meshes = new ArrayList<>();
    public final List<Skin> skins = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    public final List<Image> images = new ArrayList<>();
    public int[] roots = new int[0];
    /** Humanoid bone name (VRM 1.0 naming, e.g. "head", "leftUpperArm") → node index. */
    public final Map<String, Integer> bones = new HashMap<>();
    /** Expression name (VRM 1.0 naming: blink, aa, ih, ou, ee, oh, happy, angry, sad, relaxed, surprised...) → binds. */
    public final Map<String, List<Bind>> expressions = new HashMap<>();
    /** VRM 0.x models face -Z; VRM 1.0 models face +Z. */
    public boolean vrm0;
    public String title = "";
    public String author = "";
    public long vertexTotal;

    // ------------------------------------------------------------------------------- loading

    private static SoftReference<VrmModel> cache;
    private static String cacheKey = "";

    /** The 3D character bundled with the app (app/src/main/assets). */
    public static final String DEFAULT_ASSET = "Pink_Haired_Girl_Teen.vrm";
    public static final String ASSET_PREFIX = "asset:";

    /** Bundled model name if the APK contains one (the default first, else any .vrm in assets), or null. */
    public static String bundledAsset(android.content.Context ctx) {
        try {
            String[] names = ctx.getAssets().list("");
            if (names == null) return null;
            String any = null;
            for (String n : names) {
                if (n.equals(DEFAULT_ASSET)) return n;
                if (any == null && n.toLowerCase(Locale.ROOT).endsWith(".vrm")) any = n;
            }
            return any;
        } catch (IOException e) {
            return null;
        }
    }

    /** Loads a model from "asset:name" or a file path (cached). */
    public static VrmModel load(android.content.Context ctx, String source) throws IOException {
        if (source.startsWith(ASSET_PREFIX)) {
            String name = source.substring(ASSET_PREFIX.length());
            synchronized (VrmModel.class) {
                String key = source;
                VrmModel m = cache == null ? null : cache.get();
                if (m != null && key.equals(cacheKey)) return m;
                byte[] data;
                try (InputStream in = ctx.getAssets().open(name)) {
                    data = readAll(in, 8 * 1024 * 1024);
                }
                m = parse(data);
                cache = new SoftReference<>(m);
                cacheKey = key;
                return m;
            }
        }
        return load(new File(source));
    }

    /** Loads (or returns the cached copy of) the model at {@code path}. */
    public static synchronized VrmModel load(File file) throws IOException {
        String key = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        VrmModel m = cache == null ? null : cache.get();
        if (m != null && key.equals(cacheKey)) return m;
        if (file.length() > 200L * 1024 * 1024) throw new IOException("The model file is larger than 200 MB");
        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            data = readAll(in, (int) file.length());
        }
        m = parse(data);
        cache = new SoftReference<>(m);
        cacheKey = key;
        return m;
    }

    private static byte[] readAll(InputStream in, int size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, size));
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Checks the header and returns a short description, or throws when it is not a GLB/VRM file. */
    public static String probe(File file) throws IOException {
        VrmModel m = load(file);
        return (m.title.isEmpty() ? file.getName() : m.title) + (m.author.isEmpty() ? "" : " · " + m.author)
                + " · " + (m.vrm0 ? "VRM 0.x" : "VRM 1.0") + " · " + m.vertexTotal + " vertices";
    }

    static VrmModel parse(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 20 || bb.getInt(0) != 0x46546C67) throw new IOException("Not a VRM/GLB file (no glTF header)");
        int version = bb.getInt(4);
        if (version != 2) throw new IOException("Unsupported glTF version " + version);
        int offset = 12;
        String json = null;
        ByteBuffer bin = null;
        while (offset + 8 <= data.length) {
            int len = bb.getInt(offset);
            int type = bb.getInt(offset + 4);
            if (len < 0 || offset + 8 + len > data.length) throw new IOException("Damaged GLB chunk");
            if (type == 0x4E4F534A) json = new String(data, offset + 8, len, StandardCharsets.UTF_8);
            else if (type == 0x004E4942) {
                bin = ByteBuffer.wrap(data, offset + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            offset += 8 + len;
        }
        if (json == null) throw new IOException("GLB has no JSON chunk");
        try {
            return new Builder(new JSONObject(json), bin).build();
        } catch (JSONException e) {
            throw new IOException("Invalid model JSON: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------- builder

    private static final class Builder {
        final JSONObject j;
        final ByteBuffer bin;
        final VrmModel m = new VrmModel();

        Builder(JSONObject j, ByteBuffer bin) {
            this.j = j;
            this.bin = bin;
        }

        VrmModel build() throws IOException, JSONException {
            readNodes();
            readMeshes();
            readSkins();
            readImagesAndMaterials();
            readVrm();
            return m;
        }

        void readNodes() throws JSONException {
            JSONArray arr = j.optJSONArray("nodes");
            int n = arr == null ? 0 : arr.length();
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.getJSONObject(i);
                Node nd = new Node();
                nd.name = o.optString("name", "");
                nd.mesh = o.optInt("mesh", -1);
                nd.skin = o.optInt("skin", -1);
                JSONArray mat = o.optJSONArray("matrix");
                if (mat != null && mat.length() == 16) {
                    float[] mm = new float[16];
                    for (int k = 0; k < 16; k++) mm[k] = (float) mat.optDouble(k);
                    VrmMath.decompose(mm, nd.t, nd.r, nd.s);
                } else {
                    copy(o.optJSONArray("translation"), nd.t);
                    copy(o.optJSONArray("rotation"), nd.r);
                    copy(o.optJSONArray("scale"), nd.s);
                }
                JSONArray ch = o.optJSONArray("children");
                if (ch != null) {
                    nd.children = new int[ch.length()];
                    for (int k = 0; k < ch.length(); k++) nd.children[k] = ch.getInt(k);
                }
                m.nodes.add(nd);
            }
            for (int i = 0; i < m.nodes.size(); i++) {
                for (int c : m.nodes.get(i).children) if (c >= 0 && c < m.nodes.size()) m.nodes.get(c).parent = i;
            }
            JSONArray scenes = j.optJSONArray("scenes");
            JSONObject scene = scenes == null ? null : scenes.optJSONObject(j.optInt("scene", 0));
            JSONArray rootArr = scene == null ? null : scene.optJSONArray("nodes");
            if (rootArr != null) {
                m.roots = new int[rootArr.length()];
                for (int k = 0; k < rootArr.length(); k++) m.roots[k] = rootArr.getInt(k);
            } else {
                List<Integer> r = new ArrayList<>();
                for (int i = 0; i < m.nodes.size(); i++) if (m.nodes.get(i).parent < 0) r.add(i);
                m.roots = new int[r.size()];
                for (int k = 0; k < r.size(); k++) m.roots[k] = r.get(k);
            }
        }

        static void copy(JSONArray a, float[] out) {
            if (a == null || a.length() != out.length) return;
            for (int k = 0; k < out.length; k++) out[k] = (float) a.optDouble(k, out[k]);
        }

        void readMeshes() throws IOException, JSONException {
            JSONArray arr = j.optJSONArray("meshes");
            int n = arr == null ? 0 : arr.length();
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.getJSONObject(i);
                Mesh mesh = new Mesh();
                JSONArray prims = o.optJSONArray("primitives");
                for (int p = 0; prims != null && p < prims.length(); p++) {
                    JSONObject po = prims.getJSONObject(p);
                    if (po.optInt("mode", 4) != 4) continue; // triangles only
                    JSONObject at = po.getJSONObject("attributes");
                    if (!at.has("POSITION")) continue;
                    Primitive pr = new Primitive();
                    int posAcc = at.getInt("POSITION");
                    pr.positions = floats(posAcc);
                    JSONObject pa = accessor(posAcc);
                    pr.min = vec(pa.optJSONArray("min"));
                    pr.max = vec(pa.optJSONArray("max"));
                    int vc = pr.vertexCount();
                    pr.normals = at.has("NORMAL") ? floats(at.getInt("NORMAL")) : new float[vc * 3];
                    pr.uvs = at.has("TEXCOORD_0") ? floats(at.getInt("TEXCOORD_0")) : new float[vc * 2];
                    if (at.has("JOINTS_0") && at.has("WEIGHTS_0")) {
                        pr.joints = floats(at.getInt("JOINTS_0"));
                        pr.weights = floats(at.getInt("WEIGHTS_0"));
                    }
                    if (po.has("indices")) {
                        float[] idx = floats(po.getInt("indices"));
                        pr.indices = new int[idx.length];
                        for (int k = 0; k < idx.length; k++) pr.indices[k] = (int) idx[k];
                    } else {
                        pr.indices = new int[vc];
                        for (int k = 0; k < vc; k++) pr.indices[k] = k;
                    }
                    pr.material = po.optInt("material", -1);
                    JSONArray targets = po.optJSONArray("targets");
                    for (int t = 0; targets != null && t < targets.length(); t++) {
                        JSONObject to = targets.getJSONObject(t);
                        pr.targets.add(to.has("POSITION") ? floats(to.getInt("POSITION")) : null);
                    }
                    m.vertexTotal += vc;
                    mesh.primitives.add(pr);
                }
                JSONArray w = o.optJSONArray("weights");
                if (w != null) {
                    mesh.defaultWeights = new float[w.length()];
                    for (int k = 0; k < w.length(); k++) mesh.defaultWeights[k] = (float) w.optDouble(k, 0);
                }
                m.meshes.add(mesh);
            }
        }

        static float[] vec(JSONArray a) {
            if (a == null) return null;
            float[] v = new float[a.length()];
            for (int k = 0; k < v.length; k++) v[k] = (float) a.optDouble(k, 0);
            return v;
        }

        void readSkins() throws IOException, JSONException {
            JSONArray arr = j.optJSONArray("skins");
            int n = arr == null ? 0 : arr.length();
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.getJSONObject(i);
                Skin s = new Skin();
                JSONArray js = o.getJSONArray("joints");
                s.joints = new int[js.length()];
                for (int k = 0; k < js.length(); k++) s.joints[k] = js.getInt(k);
                if (o.has("inverseBindMatrices")) {
                    s.inverseBind = floats(o.getInt("inverseBindMatrices"));
                } else {
                    s.inverseBind = new float[16 * s.joints.length];
                    for (int k = 0; k < s.joints.length; k++) android.opengl.Matrix.setIdentityM(s.inverseBind, k * 16);
                }
                m.skins.add(s);
            }
        }

        void readImagesAndMaterials() throws JSONException {
            JSONArray imgs = j.optJSONArray("images");
            for (int i = 0; imgs != null && i < imgs.length(); i++) {
                JSONObject o = imgs.getJSONObject(i);
                Image im = new Image();
                im.mime = o.optString("mimeType", "");
                if (o.has("bufferView") && bin != null) {
                    JSONObject bv = j.getJSONArray("bufferViews").getJSONObject(o.getInt("bufferView"));
                    int off = bv.optInt("byteOffset", 0);
                    int len = bv.getInt("byteLength");
                    if (off >= 0 && len > 0 && off + len <= bin.capacity()) {
                        im.encoded = new byte[len];
                        ByteBuffer d = bin.duplicate();
                        d.position(off);
                        d.get(im.encoded);
                    }
                }
                m.images.add(im);
            }
            JSONArray textures = j.optJSONArray("textures");
            JSONArray mats = j.optJSONArray("materials");
            for (int i = 0; mats != null && i < mats.length(); i++) {
                JSONObject o = mats.getJSONObject(i);
                Material mt = new Material();
                JSONObject pbr = o.optJSONObject("pbrMetallicRoughness");
                if (pbr != null) {
                    JSONArray c = pbr.optJSONArray("baseColorFactor");
                    if (c != null && c.length() == 4) for (int k = 0; k < 4; k++) mt.color[k] = (float) c.optDouble(k, 1);
                    JSONObject tex = pbr.optJSONObject("baseColorTexture");
                    if (tex != null && textures != null) {
                        JSONObject to = textures.optJSONObject(tex.optInt("index", -1));
                        if (to != null) mt.image = to.optInt("source", -1);
                    }
                }
                String mode = o.optString("alphaMode", "OPAQUE");
                mt.alphaMode = "MASK".equals(mode) ? 1 : "BLEND".equals(mode) ? 2 : 0;
                mt.cutoff = (float) o.optDouble("alphaCutoff", 0.5);
                mt.doubleSided = o.optBoolean("doubleSided", false);
                m.materials.add(mt);
            }
        }

        void readVrm() throws JSONException {
            JSONObject ext = j.optJSONObject("extensions");
            if (ext == null) return;
            JSONObject v1 = ext.optJSONObject("VRMC_vrm");
            JSONObject v0 = ext.optJSONObject("VRM");
            if (v1 != null) {
                m.vrm0 = false;
                JSONObject meta = v1.optJSONObject("meta");
                if (meta != null) {
                    m.title = meta.optString("name", "");
                    JSONArray authors = meta.optJSONArray("authors");
                    if (authors != null && authors.length() > 0) m.author = authors.optString(0);
                }
                JSONObject hb = v1.optJSONObject("humanoid") == null ? null : v1.getJSONObject("humanoid").optJSONObject("humanBones");
                if (hb != null) {
                    JSONArray names = hb.names();
                    for (int i = 0; names != null && i < names.length(); i++) {
                        JSONObject b = hb.optJSONObject(names.getString(i));
                        if (b != null && b.has("node")) m.bones.put(names.getString(i), b.getInt("node"));
                    }
                }
                JSONObject ex = v1.optJSONObject("expressions");
                if (ex != null) {
                    addV1Expressions(ex.optJSONObject("preset"));
                    addV1Expressions(ex.optJSONObject("custom"));
                }
                readSpringsV1(ext.optJSONObject("VRMC_springBone"));
            } else if (v0 != null) {
                m.vrm0 = true;
                readSpringsV0(v0.optJSONObject("secondaryAnimation"));
                JSONObject meta = v0.optJSONObject("meta");
                if (meta != null) {
                    m.title = meta.optString("title", "");
                    m.author = meta.optString("author", "");
                }
                JSONObject hum = v0.optJSONObject("humanoid");
                JSONArray hb = hum == null ? null : hum.optJSONArray("humanBones");
                for (int i = 0; hb != null && i < hb.length(); i++) {
                    JSONObject b = hb.getJSONObject(i);
                    if (b.has("bone") && b.has("node")) m.bones.put(b.getString("bone"), b.getInt("node"));
                }
                JSONObject bsm = v0.optJSONObject("blendShapeMaster");
                JSONArray groups = bsm == null ? null : bsm.optJSONArray("blendShapeGroups");
                for (int i = 0; groups != null && i < groups.length(); i++) {
                    JSONObject gr = groups.getJSONObject(i);
                    String preset = gr.optString("presetName", "unknown");
                    String name = "unknown".equals(preset) ? gr.optString("name", "") : preset;
                    String key = v0Name(name);
                    if (key.isEmpty()) continue;
                    List<Bind> list = new ArrayList<>();
                    JSONArray binds = gr.optJSONArray("binds");
                    for (int k = 0; binds != null && k < binds.length(); k++) {
                        JSONObject b = binds.getJSONObject(k);
                        list.add(new Bind(b.optInt("mesh", -1), b.optInt("index", -1), (float) b.optDouble("weight", 100) / 100f));
                    }
                    if (!list.isEmpty() && !m.expressions.containsKey(key)) m.expressions.put(key, list);
                }
            }
        }

        // VRM 0.x stores these in Unity's left-handed axes: x is mirrored relative to glTF.
        static float[] v0vec(JSONObject o, float[] def) {
            if (o == null) return def;
            return new float[]{-(float) o.optDouble("x", 0), (float) o.optDouble("y", 0), (float) o.optDouble("z", 0)};
        }

        void readSpringsV0(JSONObject sa) throws JSONException {
            if (sa == null) return;
            JSONArray groups = sa.optJSONArray("colliderGroups");
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONObject g = groups.getJSONObject(i);
                JSONArray cs = g.optJSONArray("colliders");
                int[] idx = new int[cs == null ? 0 : cs.length()];
                for (int k = 0; k < idx.length; k++) {
                    JSONObject c = cs.getJSONObject(k);
                    Collider col = new Collider();
                    col.node = g.optInt("node", -1);
                    col.offset = v0vec(c.optJSONObject("offset"), new float[]{0, 0, 0});
                    col.radius = (float) c.optDouble("radius", 0);
                    idx[k] = m.colliders.size();
                    m.colliders.add(col);
                }
                m.colliderGroups.add(idx);
            }
            JSONArray bgs = sa.optJSONArray("boneGroups");
            for (int i = 0; bgs != null && i < bgs.length(); i++) {
                JSONObject bg = bgs.getJSONObject(i);
                JSONArray cg = bg.optJSONArray("colliderGroups");
                int[] groupsIdx = new int[cg == null ? 0 : cg.length()];
                for (int k = 0; k < groupsIdx.length; k++) groupsIdx[k] = cg.getInt(k);
                JSONArray bones = bg.optJSONArray("bones");
                for (int b = 0; bones != null && b < bones.length(); b++) {
                    addV0Chain(bones.getInt(b), bg, groupsIdx);
                }
            }
        }

        /** Every bone under a VRM 0.x spring root is a joint whose tail is its first child. */
        void addV0Chain(int root, JSONObject bg, int[] groups) {
            if (root < 0 || root >= m.nodes.size()) return;
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                int n = stack.pop();
                Node node = m.nodes.get(n);
                SpringJoint j = new SpringJoint();
                j.node = n;
                j.tail = node.children.length > 0 ? node.children[0] : -1;
                j.stiffness = (float) bg.optDouble("stiffiness", bg.optDouble("stiffness", 1));
                j.gravity = (float) bg.optDouble("gravityPower", 0);
                j.gravityDir = v0vec(bg.optJSONObject("gravityDir"), new float[]{0, -1, 0});
                j.drag = (float) bg.optDouble("dragForce", 0.4);
                j.hitRadius = (float) bg.optDouble("hitRadius", 0.02);
                j.colliderGroups = groups;
                m.springs.add(j);
                for (int c = node.children.length - 1; c >= 0; c--) {
                    int ch = node.children[c];
                    if (ch >= 0 && ch < m.nodes.size()) stack.push(ch);
                }
            }
        }

        void readSpringsV1(JSONObject sb) throws JSONException {
            if (sb == null) return;
            JSONArray cols = sb.optJSONArray("colliders");
            for (int i = 0; cols != null && i < cols.length(); i++) {
                JSONObject c = cols.getJSONObject(i);
                Collider col = new Collider();
                col.node = c.optInt("node", -1);
                JSONObject shape = c.optJSONObject("shape");
                JSONObject sphere = shape == null ? null : shape.optJSONObject("sphere");
                JSONObject capsule = shape == null ? null : shape.optJSONObject("capsule");
                JSONObject sh = sphere != null ? sphere : capsule;
                if (sh != null) {
                    float[] off = vec(sh.optJSONArray("offset"));
                    if (off != null && off.length == 3) col.offset = off;
                    col.radius = (float) sh.optDouble("radius", 0);
                    if (capsule != null) col.tail = vec(capsule.optJSONArray("tail"));
                }
                m.colliders.add(col);
            }
            JSONArray groups = sb.optJSONArray("colliderGroups");
            for (int i = 0; groups != null && i < groups.length(); i++) {
                JSONArray cs = groups.getJSONObject(i).optJSONArray("colliders");
                int[] idx = new int[cs == null ? 0 : cs.length()];
                for (int k = 0; k < idx.length; k++) idx[k] = cs.getInt(k);
                m.colliderGroups.add(idx);
            }
            JSONArray springs = sb.optJSONArray("springs");
            for (int i = 0; springs != null && i < springs.length(); i++) {
                JSONObject sp = springs.getJSONObject(i);
                JSONArray cg = sp.optJSONArray("colliderGroups");
                int[] groupsIdx = new int[cg == null ? 0 : cg.length()];
                for (int k = 0; k < groupsIdx.length; k++) groupsIdx[k] = cg.getInt(k);
                JSONArray joints = sp.optJSONArray("joints");
                // The last joint is only the tail of the one before it.
                for (int k = 0; joints != null && k + 1 < joints.length(); k++) {
                    JSONObject jo = joints.getJSONObject(k);
                    SpringJoint j = new SpringJoint();
                    j.node = jo.optInt("node", -1);
                    j.tail = joints.getJSONObject(k + 1).optInt("node", -1);
                    j.stiffness = (float) jo.optDouble("stiffness", 1);
                    j.gravity = (float) jo.optDouble("gravityPower", 0);
                    float[] gd = vec(jo.optJSONArray("gravityDir"));
                    if (gd != null && gd.length == 3) j.gravityDir = gd;
                    j.drag = (float) jo.optDouble("dragForce", 0.5);
                    j.hitRadius = (float) jo.optDouble("hitRadius", 0);
                    j.colliderGroups = groupsIdx;
                    if (j.node >= 0 && j.node < m.nodes.size()) m.springs.add(j);
                }
            }
        }

        void addV1Expressions(JSONObject set) throws JSONException {
            if (set == null) return;
            JSONArray names = set.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String name = names.getString(i);
                JSONObject e = set.getJSONObject(name);
                JSONArray binds = e.optJSONArray("morphTargetBinds");
                List<Bind> list = new ArrayList<>();
                for (int k = 0; binds != null && k < binds.length(); k++) {
                    JSONObject b = binds.getJSONObject(k);
                    int node = b.optInt("node", -1);
                    int mesh = node >= 0 && node < m.nodes.size() ? m.nodes.get(node).mesh : -1;
                    list.add(new Bind(mesh, b.optInt("index", -1), (float) b.optDouble("weight", 1)));
                }
                if (!list.isEmpty()) m.expressions.put(name, list);
            }
        }

        static String v0Name(String n) {
            switch (n.toLowerCase(Locale.ROOT)) {
                case "a":
                    return "aa";
                case "i":
                    return "ih";
                case "u":
                    return "ou";
                case "e":
                    return "ee";
                case "o":
                    return "oh";
                case "blink":
                    return "blink";
                case "blink_l":
                    return "blinkLeft";
                case "blink_r":
                    return "blinkRight";
                case "joy":
                    return "happy";
                case "angry":
                    return "angry";
                case "sorrow":
                    return "sad";
                case "fun":
                    return "relaxed";
                case "surprised":
                    return "surprised";
                default:
                    return n;
            }
        }

        // --------------------------------------------------------------------------- accessors

        JSONObject accessor(int index) throws JSONException {
            return j.getJSONArray("accessors").getJSONObject(index);
        }

        static int components(String type) {
            switch (type) {
                case "SCALAR":
                    return 1;
                case "VEC2":
                    return 2;
                case "VEC3":
                    return 3;
                case "VEC4":
                    return 4;
                case "MAT2":
                    return 4;
                case "MAT3":
                    return 9;
                case "MAT4":
                    return 16;
                default:
                    return 1;
            }
        }

        static int size(int componentType) {
            switch (componentType) {
                case 5120:
                case 5121:
                    return 1;
                case 5122:
                case 5123:
                    return 2;
                default:
                    return 4;
            }
        }

        static float read(ByteBuffer b, int pos, int ct, boolean normalized) {
            switch (ct) {
                case 5120: {
                    byte v = b.get(pos);
                    return normalized ? Math.max(v / 127f, -1f) : v;
                }
                case 5121: {
                    int v = b.get(pos) & 0xff;
                    return normalized ? v / 255f : v;
                }
                case 5122: {
                    short v = b.getShort(pos);
                    return normalized ? Math.max(v / 32767f, -1f) : v;
                }
                case 5123: {
                    int v = b.getShort(pos) & 0xffff;
                    return normalized ? v / 65535f : v;
                }
                case 5125:
                    return (float) (b.getInt(pos) & 0xffffffffL);
                default:
                    return b.getFloat(pos);
            }
        }

        /** Reads an accessor as floats (integers become exact floats), including sparse values. */
        float[] floats(int index) throws IOException, JSONException {
            JSONObject a = accessor(index);
            int count = a.getInt("count");
            int comps = components(a.getString("type"));
            int ct = a.getInt("componentType");
            boolean norm = a.optBoolean("normalized", false);
            if ((long) count * comps > 40_000_000L) throw new IOException("Accessor too large");
            float[] out = new float[count * comps];
            if (a.has("bufferView")) {
                if (bin == null) throw new IOException("The model refers to external buffers; use a single .vrm/.glb file");
                JSONObject bv = j.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"));
                if (bv.optInt("buffer", 0) != 0) throw new IOException("External buffers are not supported");
                int base = bv.optInt("byteOffset", 0) + a.optInt("byteOffset", 0);
                int elem = size(ct) * comps;
                int stride = bv.optInt("byteStride", 0);
                if (stride == 0) stride = elem;
                int last = base + (count - 1) * stride + elem;
                if (count > 0 && (base < 0 || last > bin.capacity())) throw new IOException("Accessor outside the buffer");
                for (int i = 0; i < count; i++) {
                    int p = base + i * stride;
                    for (int c = 0; c < comps; c++) out[i * comps + c] = read(bin, p + c * size(ct), ct, norm);
                }
            }
            JSONObject sparse = a.optJSONObject("sparse");
            if (sparse != null && bin != null) {
                int n = sparse.getInt("count");
                JSONObject si = sparse.getJSONObject("indices");
                JSONObject sv = sparse.getJSONObject("values");
                JSONObject ibv = j.getJSONArray("bufferViews").getJSONObject(si.getInt("bufferView"));
                JSONObject vbv = j.getJSONArray("bufferViews").getJSONObject(sv.getInt("bufferView"));
                int ib = ibv.optInt("byteOffset", 0) + si.optInt("byteOffset", 0);
                int vb = vbv.optInt("byteOffset", 0) + sv.optInt("byteOffset", 0);
                int ict = si.getInt("componentType");
                for (int k = 0; k < n; k++) {
                    int target = (int) read(bin, ib + k * size(ict), ict, false);
                    if (target < 0 || target >= count) continue;
                    for (int c = 0; c < comps; c++) {
                        out[target * comps + c] = read(bin, vb + (k * comps + c) * size(ct), ct, norm);
                    }
                }
            }
            return out;
        }
    }
}
