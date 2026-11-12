package com.suvo.agent.core.office;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your Friend — Original project by Suvo.
 *
 * The team as a small 2D office, game style (landscape): the boss at its desk on the left, every
 * member at a desk of its own, and on the right the three things only one member can use at a
 * time — the phone, the browser, the camera. A member who uses one walks over and stands at it;
 * one who has to wait queues beside it. Bubbles say what each one is doing right now; a finished
 * job flies to the boss as a letter.
 *
 * Pure Java: it draws through {@link Pen}, so the same scene renders on Android and in a preview.
 */
public final class OfficeScene {

    /** The few drawing calls the scene needs. Colours are ARGB. */
    public interface Pen {
        /** Filled rounded rectangle (radius 0 = square corners). */
        void rect(float x, float y, float w, float h, float radius, int color);

        void oval(float cx, float cy, float rx, float ry, int color);

        void line(float x1, float y1, float x2, float y2, float width, int color);

        void tri(float x1, float y1, float x2, float y2, float x3, float y3, int color);

        /** @param align 0 = x is the left edge, 1 = x is the centre; y is the baseline */
        void text(String s, float x, float y, float size, int color, int align, boolean bold);

        float textWidth(String s, float size, boolean bold);
    }

    public static final int IDLE = 0, WORKING = 1, WAITING_THING = 2, WAITING_OK = 3, DONE = 4, FAILED = 5;

    /** One member as the scene sees it (filled in fresh for every frame's update). */
    public static final class Member {
        public long id;
        public String name = "", role = "";
        public int state = IDLE;
        /** What it does right now (already readable: "web search"). */
        public String doing = "";
        /** "screen" / "browser" / "camera" it holds, or "". */
        public String holds = "";
        /** The thing it waits for, and who has it. */
        public String waitsFor = "", waitsOn = "";
        public int jobId = -1, steps;
        public long finishedAt;
    }

    public static final class Colors {
        public boolean dark;
        public int wall, wallTrim, floorA, floorB, desk, deskFront, text, textDim, accent, onAccent, panel, panelText;
        public int good = 0xFF3FB56B, warn = 0xFFE8A93A, bad = 0xFFE05A4E;
    }

    public static final class Labels {
        public String boss = "Boss", phone = "Phone", browser = "Browser", camera = "Camera", thinking = "thinking",
                waiting = "waiting for the %1$s", needsOk = "needs your OK", done = "done", failed = "failed",
                working = "Working", free = "Free", board = "Today", steps = "%1$d steps", talking = "talking with you",
                reading = "reading a report";
    }

    public Colors c = new Colors();
    public Labels l = new Labels();

    private static final class Actor {
        float x, y;
        boolean placed;
        int lastState = IDLE, lastJob = -1;
        float walk; // phase
    }

    private static final class Letter {
        float x0, y0;
        long start;
        boolean ok;
    }

    private final Map<Long, Actor> actors = new HashMap<>();
    private final List<Letter> letters = new ArrayList<>();
    private List<Member> members = new ArrayList<>();
    private boolean bossBusy;
    private String bossSays = "";
    private long bossAlertUntil;
    private long lastFrame;
    private float w, h, u;

    // Where things are (set by layout()).
    private float bossX, bossY;
    private final float[] stX = new float[3], stY = new float[3];
    private float[] seatX = new float[0], seatY = new float[0];
    private float deskScale = 1f;
    private static final String[] THINGS = {"screen", "browser", "camera"};

    public void update(List<Member> now, boolean bossBusy, String bossSays) {
        this.members = now;
        this.bossBusy = bossBusy;
        this.bossSays = bossSays == null ? "" : bossSays;
    }

    private void layout(float w, float h) {
        this.w = w;
        this.h = h;
        u = h / 12f;
        bossX = Math.max(2.9f * u, w * 0.125f);
        bossY = 7.3f * u;
        float sx = w - 2.0f * u;
        for (int i = 0; i < 3; i++) {
            stX[i] = sx;
            stY[i] = (4.55f + i * 2.72f) * u;
        }
        int n = members.size();
        seatX = new float[n];
        seatY = new float[n];
        if (n == 0) return;
        float x0 = bossX + 3.4f * u, x1 = w - 5.6f * u;
        int rows = n <= 3 ? 1 : n <= 8 ? 2 : 3;
        int cols = (n + rows - 1) / rows;
        float cell = (x1 - x0) / Math.max(1, cols);
        deskScale = Math.max(0.55f, Math.min(1f, Math.min(cell / (3.3f * u), rows == 3 ? 0.66f : 1f)));
        float[] ys = rows == 1 ? new float[]{7.2f} : rows == 2 ? new float[]{5.4f, 9.6f} : new float[]{4.9f, 7.6f, 10.3f};
        for (int i = 0; i < n; i++) {
            int r = i / cols, col = i % cols;
            int inRow = r == rows - 1 ? n - cols * (rows - 1) : cols;
            float rowW = cell * inRow;
            float start = x0 + ((x1 - x0) - rowW) / 2f;
            seatX[i] = start + cell * (col + 0.5f);
            seatY[i] = ys[r] * u;
        }
    }

    private int thingIndex(String t) {
        for (int i = 0; i < 3; i++) if (THINGS[i].equals(t)) return i;
        return -1;
    }

    /** The member (id), the boss (-1), or Long.MIN_VALUE for nothing at that point. */
    public long hit(float x, float y) {
        if (u <= 0) return Long.MIN_VALUE;
        if (Math.abs(x - bossX) < 2.0f * u && Math.abs(y - (bossY - 0.4f * u)) < 1.9f * u) return -1;
        long best = Long.MIN_VALUE;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < members.size(); i++) {
            Actor a = actors.get(members.get(i).id);
            if (a == null) continue;
            float d = Math.min(dist(x, y, a.x, a.y - 0.9f * u), dist(x, y, seatX[i], seatY[i]));
            if (d < 1.9f * u * deskScale + 0.4f * u && d < bestD) {
                bestD = d;
                best = members.get(i).id;
            }
        }
        return best;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    // ------------------------------------------------------------------------------------ drawing

    public void draw(Pen p, float width, float height, long now) {
        layout(width, height);
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        room(p, now);

        // Where everyone is going, and one step toward it.
        int[] queue = new int[3];
        float[] tx = new float[members.size()], ty = new float[members.size()];
        boolean[] seated = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            Actor a = actors.get(m.id);
            if (a == null) {
                a = new Actor();
                actors.put(m.id, a);
            }
            int hold = m.state == WORKING || m.state == WAITING_OK ? thingIndex(m.holds) : -1;
            int wait = m.state == WAITING_THING ? thingIndex(m.waitsFor) : -1;
            if (hold >= 0) {
                tx[i] = stX[hold] - 1.55f * u;
                ty[i] = stY[hold] + 0.55f * u;
            } else if (wait >= 0) {
                tx[i] = stX[wait] - 2.75f * u - queue[wait] * 0.95f * u;
                ty[i] = stY[wait] + 0.75f * u;
                queue[wait]++;
            } else {
                tx[i] = seatX[i];
                ty[i] = seatY[i];
            }
            if (!a.placed) {
                a.x = tx[i];
                a.y = ty[i];
                a.placed = true;
            }
            float d = dist(a.x, a.y, tx[i], ty[i]);
            float step = 7.5f * u * dt;
            if (d <= step || d > w) {
                a.x = tx[i];
                a.y = ty[i];
            } else {
                a.x += (tx[i] - a.x) / d * step;
                a.y += (ty[i] - a.y) / d * step;
                a.walk += dt * 11f;
            }
            seated[i] = hold < 0 && wait < 0 && dist(a.x, a.y, seatX[i], seatY[i]) < 0.05f * u;
            // A job just ended: its report flies to the boss.
            if ((m.state == DONE || m.state == FAILED) && a.lastState != m.state && a.lastJob == m.jobId && a.lastJob >= 0) {
                Letter le = new Letter();
                le.x0 = a.x;
                le.y0 = a.y - 1.6f * u * deskScale;
                le.start = now;
                le.ok = m.state == DONE;
                letters.add(le);
            }
            a.lastState = m.state;
            a.lastJob = m.jobId;
        }

        // Painter's order: what is lower on the floor is drawn later.
        List<float[]> order = new ArrayList<>(); // {y, kind, index}: 0 desk(+seated member), 1 walking member, 2 station, 3 boss
        for (int i = 0; i < members.size(); i++) {
            order.add(new float[]{seatY[i] + 0.6f * u, 0, i});
            if (!seated[i]) order.add(new float[]{actors.get(members.get(i).id).y, 1, i});
        }
        for (int i = 0; i < 3; i++) order.add(new float[]{stY[i] + 0.5f * u, 2, i});
        order.add(new float[]{bossY + 0.7f * u, 3, 0});
        Collections.sort(order, (a, b) -> Float.compare(a[0], b[0]));
        for (float[] o : order) {
            int i = (int) o[2];
            switch ((int) o[1]) {
                case 0: {
                    Member m = members.get(i);
                    if (seated[i]) person(p, seatX[i], seatY[i], deskScale, m.name, m.state, true, 0, now, false);
                    desk(p, seatX[i], seatY[i], deskScale, m, seated[i], now);
                    break;
                }
                case 1: {
                    Member m = members.get(i);
                    Actor a = actors.get(m.id);
                    boolean moving = dist(a.x, a.y, tx[i], ty[i]) > 0.05f * u;
                    person(p, a.x, a.y, deskScale, m.name, m.state, false, moving ? a.walk : 0, now, false);
                    break;
                }
                case 2:
                    station(p, i, now);
                    break;
                default:
                    boss(p, now);
            }
        }

        // Bubbles over everything.
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            Actor a = actors.get(m.id);
            String say = "";
            int dot = 0;
            if (m.state == WORKING) {
                say = (m.doing.isEmpty() ? l.thinking : m.doing) + dots(now);
                dot = c.accent;
            } else if (m.state == WAITING_THING) {
                say = String.format(l.waiting, thingLabel(m.waitsFor)) + (m.waitsOn.isEmpty() ? "" : " · " + m.waitsOn);
                dot = c.warn;
            } else if (m.state == WAITING_OK) {
                say = l.needsOk;
                dot = c.warn;
            } else if (m.state == DONE && now - m.finishedAt < 8000) {
                say = l.done;
                dot = c.good;
            } else if (m.state == FAILED && now - m.finishedAt < 12000) {
                say = l.failed;
                dot = c.bad;
            }
            // The one at a station speaks higher than the one queuing beside it, so both can be read.
            float lift = seated[i] ? 2.0f : m.state == WAITING_THING ? 2.3f : 3.2f;
            if (!say.isEmpty()) bubble(p, a.x, a.y - lift * u * deskScale, say, dot);
        }
        if (bossBusy || now < bossAlertUntil) {
            bubble(p, bossX, bossHidden ? bossDeskTop() - (bossHiddenHeight + 0.15f) * u : bossY - 3.0f * u, now < bossAlertUntil && !bossBusy ? l.reading + dots(now)
                    : (bossSays.isEmpty() ? l.talking : bossSays) + dots(now), c.accent);
        }

        // Letters on their way to the boss.
        for (int i = letters.size() - 1; i >= 0; i--) {
            Letter le = letters.get(i);
            float t = (now - le.start) / 1100f;
            if (t >= 1f) {
                letters.remove(i);
                bossAlertUntil = now + 2500;
                continue;
            }
            float e = t * t * (3 - 2 * t);
            float x = le.x0 + (bossX - le.x0) * e;
            float y = le.y0 + (bossY - 1.9f * u - le.y0) * e - (float) Math.sin(e * Math.PI) * 1.6f * u;
            float s = 0.42f * u;
            p.rect(x - s, y - s * 0.68f, s * 2, s * 1.36f, s * 0.2f, 0xFFFFFFFF);
            p.line(x - s, y - s * 0.6f, x, y + s * 0.1f, u * 0.05f, 0xFF9AA0A6);
            p.line(x + s, y - s * 0.6f, x, y + s * 0.1f, u * 0.05f, 0xFF9AA0A6);
            p.oval(x + s * 0.75f, y - s * 0.6f, s * 0.3f, s * 0.3f, le.ok ? c.good : c.bad);
        }
    }

    private static String dots(long now) {
        int n = (int) ((now / 400) % 4);
        return n == 0 ? "" : n == 1 ? "." : n == 2 ? ".." : "...";
    }

    private String thingLabel(String t) {
        return "screen".equals(t) ? l.phone.toLowerCase() : "browser".equals(t) ? l.browser.toLowerCase() : l.camera.toLowerCase();
    }

    private void room(Pen p, long now) {
        float wallH = 3.2f * u;
        p.rect(0, 0, w, wallH, 0, c.wall);
        p.rect(0, wallH, w, h - wallH, 0, c.floorA);
        // Floor tiles.
        float t = 1.5f * u;
        for (int r = 0; r * t < h - wallH; r++) {
            for (int col = (r % 2); col * t < w; col += 2) p.rect(col * t, wallH + r * t, t, t, 0, c.floorB);
        }
        p.rect(0, wallH - 0.16f * u, w, 0.32f * u, 0, c.wallTrim);
        // Windows: day sky in the light theme, a night sky in the dark one.
        float[] wx = {w * 0.40f, w * 0.64f};
        for (float x : wx) {
            float ww = 3.3f * u, wh = 1.75f * u, y = 0.62f * u;
            p.rect(x - ww / 2 - 0.1f * u, y - 0.1f * u, ww + 0.2f * u, wh + 0.2f * u, 0.16f * u, c.wallTrim);
            p.rect(x - ww / 2, y, ww, wh, 0.1f * u, c.dark ? 0xFF16233F : 0xFFBFE3F7);
            if (c.dark) {
                p.oval(x + ww * 0.28f, y + wh * 0.32f, 0.26f * u, 0.26f * u, 0xFFF4EBC8);
                for (int i = 0; i < 6; i++) {
                    float sx = x - ww * 0.42f + (i * 0.37f % 1f) * ww * 0.6f, sy = y + (0.15f + (i * 0.53f % 1f) * 0.6f) * wh;
                    boolean on = ((now / 600 + i) % 5) != 0;
                    p.oval(sx, sy, 0.035f * u, 0.035f * u, on ? 0xFFFFFFFF : 0x66FFFFFF);
                }
            } else {
                p.oval(x - ww * 0.22f, y + wh * 0.42f, 0.5f * u, 0.22f * u, 0xFFFFFFFF);
                p.oval(x - ww * 0.08f, y + wh * 0.34f, 0.36f * u, 0.2f * u, 0xFFFFFFFF);
                p.oval(x + ww * 0.3f, y + wh * 0.6f, 0.42f * u, 0.18f * u, 0xFFFFFFFF);
            }
            p.rect(x - 0.05f * u, y, 0.1f * u, wh, 0, c.wallTrim);
            p.rect(x - ww / 2, y + wh / 2 - 0.05f * u, ww, 0.1f * u, 0, c.wallTrim);
        }
        // Wall clock with the real time.
        float cx = w * 0.52f, cy = 1.5f * u, cr = 0.62f * u;
        p.oval(cx, cy, cr, cr, c.wallTrim);
        p.oval(cx, cy, cr * 0.85f, cr * 0.85f, c.panel);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        double hr = (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60.0) / 12.0 * Math.PI * 2;
        double mn = cal.get(Calendar.MINUTE) / 60.0 * Math.PI * 2;
        p.line(cx, cy, cx + (float) Math.sin(hr) * cr * 0.42f, cy - (float) Math.cos(hr) * cr * 0.42f, 0.09f * u, c.panelText);
        p.line(cx, cy, cx + (float) Math.sin(mn) * cr * 0.66f, cy - (float) Math.cos(mn) * cr * 0.66f, 0.06f * u, c.panelText);
        p.oval(cx, cy, 0.07f * u, 0.07f * u, c.accent);
        // Whiteboard beside the boss: today's numbers.
        int working = 0, done = 0, failed = 0;
        for (Member m : members) {
            if (m.state == WORKING || m.state == WAITING_THING || m.state == WAITING_OK) working++;
            else if (m.state == DONE) done++;
            else if (m.state == FAILED) failed++;
        }
        float bx = bossX - 2.1f * u, bw = 4.2f * u, by = 0.5f * u, bh = 2.25f * u;
        p.rect(bx - 0.08f * u, by - 0.08f * u, bw + 0.16f * u, bh + 0.16f * u, 0.18f * u, c.wallTrim);
        p.rect(bx, by, bw, bh, 0.12f * u, c.panel);
        p.text(l.board, bx + 0.3f * u, by + 0.6f * u, 0.4f * u, c.panelText, 0, true);
        String[] rows = {l.working, l.done, l.failed};
        int[] nums = {working, done, failed};
        int[] cols = {c.accent, c.good, c.bad};
        for (int i = 0; i < 3; i++) {
            float ry = by + (1.08f + i * 0.44f) * u;
            p.oval(bx + 0.42f * u, ry - 0.11f * u, 0.11f * u, 0.11f * u, cols[i]);
            p.text(rows[i], bx + 0.7f * u, ry, 0.33f * u, c.panelText, 0, false);
            p.text(String.valueOf(nums[i]), bx + bw - 0.55f * u, ry, 0.36f * u, c.panelText, 1, true);
        }
        // Plants in the corners.
        plant(p, 0.9f * u, h - 0.6f * u, 1f);
        // The boss's rug.
        p.oval(bossX, bossY + 0.5f * u, 2.9f * u, 1.25f * u, alpha(c.accent, c.dark ? 0.16f : 0.2f));
        p.oval(bossX, bossY + 0.5f * u, 2.45f * u, 1.0f * u, alpha(c.accent, c.dark ? 0.12f : 0.16f));
    }

    private void plant(Pen p, float x, float y, float s) {
        float k = u * s;
        p.oval(x, y + 0.12f * k, 0.5f * k, 0.14f * k, 0x22000000);
        p.oval(x - 0.28f * k, y - 0.95f * k, 0.3f * k, 0.5f * k, 0xFF3E9B5F);
        p.oval(x + 0.28f * k, y - 0.9f * k, 0.3f * k, 0.46f * k, 0xFF4CAF6E);
        p.oval(x, y - 1.2f * k, 0.3f * k, 0.56f * k, 0xFF57BD7B);
        p.rect(x - 0.36f * k, y - 0.62f * k, 0.72f * k, 0.68f * k, 0.16f * k, 0xFFC97B4A);
        p.rect(x - 0.42f * k, y - 0.66f * k, 0.84f * k, 0.18f * k, 0.08f * k, 0xFFDB8E5C);
    }

    private static final int[] SHIRTS = {0xFF5B8DEF, 0xFFEF6C8B, 0xFF3FB58E, 0xFFF2A541, 0xFF9B7BEA, 0xFF4CC2D8, 0xFFE8795B, 0xFF7DBB4B};
    private static final int[] HAIRS = {0xFF2B2320, 0xFF5A3A22, 0xFF8A5A2B, 0xFF1F2A44, 0xFFB5651D, 0xFF3A2A4A};
    private static final int[] SKINS = {0xFFF6D3B5, 0xFFE9B994, 0xFFC98F66, 0xFFF9DDC6, 0xFFB67B52};

    private static int hash(String s) {
        int x = 7;
        for (int i = 0; i < s.length(); i++) x = x * 31 + s.charAt(i);
        return x & 0x7FFFFFFF;
    }

    /**
     * One little person. {@code y} is where the feet are (standing) or the seat (sitting: the
     * legs are behind the desk).
     */
    private void person(Pen p, float x, float y, float s, String name, int state, boolean sitting, float walk, long now, boolean isBoss) {
        float k = u * s * (isBoss ? 1.12f : 1f);
        int hsh = hash(name);
        int shirt = isBoss ? c.accent : SHIRTS[hsh % SHIRTS.length];
        int hair = HAIRS[(hsh / 7) % HAIRS.length];
        int skin = SKINS[(hsh / 13) % SKINS.length];
        float bob = walk != 0 ? (float) Math.abs(Math.sin(walk)) * 0.09f * k : 0;
        boolean typing = sitting && state == WORKING;
        boolean asleep = sitting && state == IDLE && !isBoss;
        float breathe = (float) Math.sin(now / 700.0 + hsh) * 0.015f * k;
        float base = y - bob;
        if (!sitting) {
            p.oval(x, y + 0.02f * k, 0.5f * k, 0.14f * k, 0x26000000);
            float sw = walk != 0 ? (float) Math.sin(walk) * 0.14f * k : 0;
            p.rect(x - 0.27f * k, base - 0.55f * k - Math.max(0, sw), 0.22f * k, 0.55f * k, 0.08f * k, 0xFF39404D);
            p.rect(x + 0.05f * k, base - 0.55f * k - Math.max(0, -sw), 0.22f * k, 0.55f * k, 0.08f * k, 0xFF39404D);
        } else {
            // The chair's back.
            p.rect(x - 0.56f * k, base - 1.15f * k, 1.12f * k, 1.0f * k, 0.3f * k, c.dark ? 0xFF2C313B : 0xFF59606E);
        }
        float bodyTop = base - (sitting ? 1.02f : 1.42f) * k + breathe;
        // Arms.
        float a1 = typing ? (float) Math.sin(now / 90.0 + hsh) * 0.05f * k : 0;
        float a2 = typing ? (float) Math.sin(now / 90.0 + hsh + 2) * 0.05f * k : 0;
        p.rect(x - 0.62f * k, bodyTop + 0.14f * k + a1, 0.24f * k, 0.62f * k, 0.12f * k, shade(shirt, 0.86f));
        p.rect(x + 0.38f * k, bodyTop + 0.14f * k + a2, 0.24f * k, 0.62f * k, 0.12f * k, shade(shirt, 0.86f));
        // Body.
        p.rect(x - 0.46f * k, bodyTop, 0.92f * k, 0.95f * k, 0.3f * k, shirt);
        if (isBoss) {
            p.tri(x - 0.12f * k, bodyTop + 0.06f * k, x + 0.12f * k, bodyTop + 0.06f * k, x, bodyTop + 0.52f * k, c.onAccent);
        }
        // Head: hair behind, face in front.
        float hy = bodyTop - 0.3f * k;
        int style = (hsh / 3) % 3;
        if (style == 1) p.rect(x - 0.47f * k, hy - 0.1f * k, 0.94f * k, 0.62f * k, 0.2f * k, hair); // long hair
        if (style == 2) p.oval(x, hy - 0.5f * k, 0.2f * k, 0.2f * k, hair); // bun
        p.oval(x, hy - 0.06f * k, 0.46f * k, 0.46f * k, hair);
        p.oval(x, hy + 0.05f * k, 0.38f * k, 0.37f * k, skin);
        // Face.
        boolean blink = asleep || ((now + hsh * 37L) % 3600) < 130;
        float ey = hy + 0.03f * k;
        if (blink) {
            p.line(x - 0.2f * k, ey, x - 0.08f * k, ey, 0.045f * k, 0xFF2A2A2A);
            p.line(x + 0.08f * k, ey, x + 0.2f * k, ey, 0.045f * k, 0xFF2A2A2A);
        } else {
            p.oval(x - 0.14f * k, ey, 0.05f * k, 0.065f * k, 0xFF2A2A2A);
            p.oval(x + 0.14f * k, ey, 0.05f * k, 0.065f * k, 0xFF2A2A2A);
        }
        if (state == DONE) {
            p.line(x - 0.09f * k, hy + 0.2f * k, x, hy + 0.25f * k, 0.04f * k, 0xFF8A4B3A);
            p.line(x, hy + 0.25f * k, x + 0.09f * k, hy + 0.2f * k, 0.04f * k, 0xFF8A4B3A);
        } else {
            p.line(x - 0.06f * k, hy + 0.22f * k, x + 0.06f * k, hy + 0.22f * k, 0.04f * k, 0xFF8A4B3A);
        }
        p.oval(x - 0.26f * k, hy + 0.14f * k, 0.07f * k, 0.045f * k, 0x33FF6B6B);
        p.oval(x + 0.26f * k, hy + 0.14f * k, 0.07f * k, 0.045f * k, 0x33FF6B6B);
        if (asleep) {
            for (int i = 0; i < 3; i++) {
                float ph = ((now / 1400f) + i * 0.33f) % 1f;
                p.text("z", x + (0.5f + ph * 0.5f) * k, hy - (0.3f + ph * 0.9f) * k, (0.24f + ph * 0.16f) * k,
                        alpha(c.textDim, 1f - ph), 1, true);
            }
        }
    }

    private void desk(Pen p, float x, float y, float s, Member m, boolean seated, long now) {
        float k = u * s;
        float top = y - 0.42f * k, dw = 2.7f * k;
        p.oval(x, y + 1.05f * k, dw * 0.52f, 0.2f * k, 0x1F000000);
        p.rect(x - dw / 2, top, dw, 0.8f * k, 0.14f * k, c.desk);
        p.rect(x - dw / 2, top + 0.6f * k, dw, 0.85f * k, 0.12f * k, c.deskFront);
        p.rect(x - dw / 2, top + 0.58f * k, dw, 0.1f * k, 0, shade(c.deskFront, 0.9f));
        // Laptop (its back faces us); the light on it blinks while its owner works at the desk.
        float lw = 0.95f * k, lh = 0.62f * k;
        p.rect(x - lw / 2, top - lh + 0.2f * k, lw, lh, 0.08f * k, c.dark ? 0xFF4A5160 : 0xFF3C4350);
        boolean on = seated && m.state == WORKING;
        p.oval(x, top - lh / 2 + 0.2f * k, 0.09f * k, 0.09f * k, on ? (((now / 350) % 2 == 0) ? c.accent : alpha(c.accent, 0.55f)) : 0xFF7B8494);
        // A mug and some paper.
        p.rect(x + dw * 0.3f, top + 0.12f * k, 0.26f * k, 0.3f * k, 0.07f * k, 0xFFFFFFFF);
        p.rect(x + dw * 0.3f + 0.03f * k, top + 0.14f * k, 0.2f * k, 0.08f * k, 0.04f * k, 0xFF8A5A3C);
        p.rect(x - dw * 0.42f, top + 0.14f * k, 0.5f * k, 0.34f * k, 0.03f * k, 0xFFF4F4F4);
        p.line(x - dw * 0.39f, top + 0.24f * k, x - dw * 0.27f, top + 0.24f * k, 0.03f * k, 0xFFB0B5BD);
        p.line(x - dw * 0.39f, top + 0.34f * k, x - dw * 0.30f, top + 0.34f * k, 0.03f * k, 0xFFB0B5BD);
        // The last job's flag.
        if (m.state == DONE || m.state == FAILED) {
            float fx = x + dw / 2 - 0.28f * k, fy = top + 1.02f * k;
            p.oval(fx, fy, 0.2f * k, 0.2f * k, m.state == DONE ? c.good : c.bad);
            if (m.state == DONE) {
                p.line(fx - 0.09f * k, fy, fx - 0.02f * k, fy + 0.07f * k, 0.045f * k, 0xFFFFFFFF);
                p.line(fx - 0.02f * k, fy + 0.07f * k, fx + 0.1f * k, fy - 0.07f * k, 0.045f * k, 0xFFFFFFFF);
            } else {
                p.line(fx - 0.07f * k, fy - 0.07f * k, fx + 0.07f * k, fy + 0.07f * k, 0.045f * k, 0xFFFFFFFF);
                p.line(fx - 0.07f * k, fy + 0.07f * k, fx + 0.07f * k, fy - 0.07f * k, 0.045f * k, 0xFFFFFFFF);
            }
        }
        // Name plate on the front, role under the desk.
        p.text(clip(p, m.name, 0.34f * k, true, dw * 0.72f), x, top + 1.02f * k, 0.34f * k, c.text, 1, true);
        String sub = m.state != IDLE && m.steps > 0 ? String.format(l.steps, m.steps) : m.role;
        if (!sub.isEmpty()) p.text(clip(p, sub, 0.24f * k, false, dw * 0.72f), x, top + 1.34f * k, 0.24f * k, alpha(c.text, 0.7f), 1, false);
    }

    private void station(Pen p, int i, long now) {
        final float full = this.u;
        final float x = stX[i], y = stY[i];
        final float u = full * 0.82f; // the art is a little smaller than the people
        String holder = "";
        boolean used = false;
        for (Member m : members) {
            if ((m.state == WORKING || m.state == WAITING_OK) && THINGS[i].equals(m.holds)) {
                used = true;
                holder = m.name;
            }
        }
        float pulse = used ? 0.5f + 0.5f * (float) Math.sin(now / 260.0) : 0;
        p.oval(x, y + 0.6f * u, 1.25f * u, 0.36f * u, used ? alpha(c.accent, 0.2f + 0.16f * pulse) : alpha(c.textDim, 0.12f));
        // A small stand.
        p.rect(x - 0.75f * u, y + 0.1f * u, 1.5f * u, 0.7f * u, 0.14f * u, c.deskFront);
        p.rect(x - 0.85f * u, y - 0.05f * u, 1.7f * u, 0.3f * u, 0.1f * u, c.desk);
        int body = c.dark ? 0xFF4A5160 : 0xFF3C4350;
        int glow = used ? c.accent : (c.dark ? 0xFF7B8494 : 0xFFAEB6C4);
        if (i == 0) { // phone
            p.rect(x - 0.34f * u, y - 1.3f * u, 0.68f * u, 1.3f * u, 0.14f * u, body);
            p.rect(x - 0.27f * u, y - 1.2f * u, 0.54f * u, 1.02f * u, 0.07f * u, glow);
            p.oval(x, y - 0.09f * u, 0.05f * u, 0.05f * u, 0xFFDDDDDD);
        } else if (i == 1) { // browser: a screen with a globe
            p.rect(x - 0.72f * u, y - 1.25f * u, 1.44f * u, 1.0f * u, 0.12f * u, body);
            p.rect(x - 0.64f * u, y - 1.17f * u, 1.28f * u, 0.84f * u, 0.07f * u, glow);
            p.rect(x - 0.1f * u, y - 0.27f * u, 0.2f * u, 0.26f * u, 0, body);
            float gx = x, gy = y - 0.75f * u, gr = 0.3f * u;
            p.oval(gx, gy, gr, gr, 0xFFFFFFFF);
            p.line(gx - gr, gy, gx + gr, gy, 0.04f * u, body);
            p.line(gx, gy - gr, gx, gy + gr, 0.04f * u, body);
            p.oval(gx, gy, gr * 0.45f, gr * 0.92f, alpha(body, 0.25f));
        } else { // camera on a tripod
            p.line(x, y - 0.5f * u, x - 0.35f * u, y - 0.02f * u, 0.07f * u, body);
            p.line(x, y - 0.5f * u, x + 0.35f * u, y - 0.02f * u, 0.07f * u, body);
            p.line(x, y - 0.5f * u, x, y - 0.02f * u, 0.07f * u, body);
            p.rect(x - 0.5f * u, y - 1.15f * u, 1.0f * u, 0.66f * u, 0.12f * u, body);
            p.rect(x - 0.3f * u, y - 1.28f * u, 0.3f * u, 0.16f * u, 0.05f * u, body);
            p.oval(x, y - 0.82f * u, 0.24f * u, 0.24f * u, glow);
            p.oval(x, y - 0.82f * u, 0.11f * u, 0.11f * u, body);
        }
        String name = i == 0 ? l.phone : i == 1 ? l.browser : l.camera;
        p.text(name, x, y + 1.12f * full, 0.3f * full, c.text, 1, true);
        p.text(used ? clip(p, holder, 0.25f * full, false, 2.6f * full) : l.free, x, y + 1.46f * full, 0.25f * full, used ? c.accent : c.textDim, 1, false);
    }

    private void boss(Pen p, long now) {
        float x = bossX, y = bossY;
        if (!bossHidden) person(p, x, y, 1f, bossName, bossBusy ? WORKING : IDLE, true, 0, now, true);
        float k = u * 1.12f, top = y - 0.42f * k, dw = 3.5f * k;
        p.oval(x, y + 1.1f * k, dw * 0.52f, 0.22f * k, 0x1F000000);
        p.rect(x - dw / 2, top, dw, 0.86f * k, 0.16f * k, shade(c.desk, 0.94f));
        p.rect(x - dw / 2, top + 0.64f * k, dw, 0.9f * k, 0.14f * k, shade(c.deskFront, 0.92f));
        p.rect(x - dw / 2, top + 0.62f * k, dw, 0.1f * k, 0, shade(c.deskFront, 0.82f));
        if (!bossHidden) {
            float lw = 1.05f * k, lh = 0.66f * k;
            p.rect(x - lw / 2, top - lh + 0.2f * k, lw, lh, 0.08f * k, c.dark ? 0xFF4A5160 : 0xFF3C4350);
            p.oval(x, top - lh / 2 + 0.2f * k, 0.1f * k, 0.1f * k, bossBusy ? c.accent : 0xFF7B8494);
        }
        // A desk phone and the in-tray for reports.
        p.rect(x - dw * 0.42f, top + 0.12f * k, 0.6f * k, 0.36f * k, 0.08f * k, 0xFF2F3541);
        p.rect(x - dw * 0.40f, top + 0.06f * k, 0.56f * k, 0.14f * k, 0.07f * k, 0xFF4A5160);
        p.rect(x + dw * 0.24f, top + 0.14f * k, 0.66f * k, 0.36f * k, 0.05f * k, 0xFFB98B5E);
        if (now < bossAlertUntil) p.rect(x + dw * 0.26f, top + 0.08f * k, 0.56f * k, 0.3f * k, 0.03f * k, 0xFFFFFFFF);
        // The star on the name plate.
        float py = top + 1.2f * k;
        p.rect(x - 1.25f * k, py - 0.4f * k, 2.5f * k, 0.56f * k, 0.28f * k, c.accent);
        p.text(clip(p, l.boss + " · " + bossName, 0.33f * k, true, 2.3f * k), x, py, 0.33f * k, c.onAccent, 1, true);
    }

    /** The boss's (the character's) name. */
    public String bossName = "";
    /** The boss is a 3D model drawn by another view over this one: only its desk is drawn here. */
    public boolean bossHidden;
    /** How tall (in units) the 3D boss stands above its desk, so its bubble goes above its head. */
    public float bossHiddenHeight = 3.4f;

    public float unit() {
        return u;
    }

    public float bossX() {
        return bossX;
    }

    /** The top edge of the boss's desk (where a 3D boss is cut off). */
    public float bossDeskTop() {
        return bossY - 0.42f * u * 1.12f;
    }

    private void bubble(Pen p, float x, float y, String say, int dot) {
        float size = 0.34f * u;
        String t = clip(p, say, size, false, 5.4f * u);
        float tw = p.textWidth(t, size, false);
        float bw = tw + 0.95f * u, bh = 0.72f * u;
        float bx = Math.max(0.15f * u, Math.min(w - bw - 0.15f * u, x - bw / 2));
        float by = Math.max(0.15f * u, y - bh);
        p.rect(bx + 0.04f * u, by + 0.07f * u, bw, bh, bh / 2, 0x2A000000);
        p.rect(bx, by, bw, bh, bh / 2, c.panel);
        float tx = Math.max(bx + 0.3f * u, Math.min(bx + bw - 0.3f * u, x));
        p.tri(tx - 0.16f * u, by + bh - 0.02f * u, tx + 0.16f * u, by + bh - 0.02f * u, tx, by + bh + 0.2f * u, c.panel);
        p.oval(bx + 0.36f * u, by + bh / 2, 0.11f * u, 0.11f * u, dot);
        p.text(t, bx + 0.6f * u, by + bh / 2 + size * 0.35f, size, c.panelText, 0, false);
    }

    private static String clip(Pen p, String s, float size, boolean bold, float max) {
        if (s == null) return "";
        if (p.textWidth(s, size, bold) <= max) return s;
        String t = s;
        while (t.length() > 1 && p.textWidth(t + "…", size, bold) > max) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24);
    }

    private static int shade(int color, float f) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((color & 0xFF) * f));
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
