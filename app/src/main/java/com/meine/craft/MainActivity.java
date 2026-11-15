package com.meine.craft;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity {
    static final int WX = 96, WZ = 96, WH = 40, NC = 6, NA = 14;
    static final byte[] blk = new byte[WX * WZ * WH];
    static final int[] ITEMS = {2, 9, 4, 8, 6, 5};
    static final String[] NAMES = {"Erde", "Stein", "Holz", "Bretter", "Sand", "Laub"};
    static final float[][] COL = new float[16][];
    static final float[] SH = {0.82f, 0.72f, 1.0f, 0.5f, 0.78f, 0.68f};
    static final int[] DX = {1, -1, 0, 0, 0, 0};
    static final int[] DY = {0, 0, 1, -1, 0, 0};
    static final int[] DZ = {0, 0, 0, 0, 1, -1};
    static final int[][][] CORN = {
            {{1, 0, 0}, {1, 0, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 1}, {0, 0, 0}, {0, 1, 0}, {0, 1, 1}},
            {{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{1, 0, 1}, {0, 0, 1}, {0, 1, 1}, {1, 1, 1}},
            {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}};
    static final int[] IDX = {0, 1, 2, 0, 2, 3};
    static final FloatBuffer[] cbuf = new FloatBuffer[NC * NC];
    static final int[] ccnt = new int[NC * NC];
    static int SEED = 1;
    static boolean ready = false;

    static {
        COL[1] = new float[]{.33f, .60f, .20f};
        COL[2] = new float[]{.46f, .32f, .20f};
        COL[3] = new float[]{.50f, .50f, .52f};
        COL[4] = new float[]{.38f, .27f, .15f};
        COL[5] = new float[]{.17f, .44f, .14f};
        COL[6] = new float[]{.85f, .78f, .52f};
        COL[8] = new float[]{.72f, .56f, .33f};
        COL[9] = new float[]{.42f, .42f, .44f};
    }

    // ---------- Gemeinsamer Zustand ----------
    static class S {
        static volatile float mx, my, px, py, pz, tod;
        static volatile boolean jump, breakH, placeH, craftOpen, pick;
        static volatile int sel = 0, craftReq = 0;
        static volatile String msg = "";
        static volatile long msgUntil = 0;
        static final int[] cnt = new int[16];
        static float lookX, lookY;

        static synchronized void addLook(float a, float b) {
            lookX += a;
            lookY += b;
        }

        static synchronized float[] takeLook() {
            float[] r = {lookX, lookY};
            lookX = 0;
            lookY = 0;
            return r;
        }

        static void say(String m) {
            msg = m;
            msgUntil = System.currentTimeMillis() + 2500;
        }
    }

    GLSurfaceView gl;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        gl.setRenderer(new Rend());
        FrameLayout f = new FrameLayout(this);
        f.addView(gl);
        f.addView(new Hud(this));
        setContentView(f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        gl.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gl.onResume();
    }

    // ---------- Welt ----------
    static int get(int x, int y, int z) {
        if (x < 0 || x >= WX || z < 0 || z >= WZ || y < 0 || y >= WH) return 0;
        return blk[(y * WZ + z) * WX + x];
    }

    static void put(int x, int y, int z, int t) {
        if (x < 0 || x >= WX || z < 0 || z >= WZ || y < 0 || y >= WH) return;
        blk[(y * WZ + z) * WX + x] = (byte) t;
    }

    static boolean solid(int x, int y, int z) {
        if (x < 0 || x >= WX || z < 0 || z >= WZ || y < 0) return true;
        if (y >= WH) return false;
        return blk[(y * WZ + z) * WX + x] != 0;
    }

    static int ground(int x, int z) {
        for (int y = WH - 1; y >= 0; y--) if (get(x, y, z) != 0) return y + 1;
        return 0;
    }

    static float hh(int x, int z) {
        int h = x * 374761393 + z * 668265263 + SEED * 7919;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xffff) / 65535f;
    }

    static float vn(float x, float z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        float fx = x - xi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = hh(xi, zi), b = hh(xi + 1, zi), c = hh(xi, zi + 1), d = hh(xi + 1, zi + 1);
        float t1 = a + (b - a) * fx, t2 = c + (d - c) * fx;
        return t1 + (t2 - t1) * fz;
    }

    static void gen() {
        SEED = (int) (System.nanoTime() & 0xffff);
        int[] hm = new int[WX * WZ];
        for (int x = 0; x < WX; x++) {
            for (int z = 0; z < WZ; z++) {
                float f = vn(x * .02f, z * .02f) * .55f + vn(x * .05f, z * .05f) * .30f
                        + vn(x * .12f, z * .12f) * .15f;
                int h = (int) (8 + (f - 0.2f) * 28);
                h = Math.max(4, Math.min(30, h));
                hm[x * WZ + z] = h;
                for (int y = 0; y <= h; y++) {
                    int t;
                    if (h <= 12) t = (y >= h - 2) ? 6 : 3;
                    else t = (y == h) ? 1 : (y >= h - 3 ? 2 : 3);
                    put(x, y, z, t);
                }
            }
        }
        for (int x = 4; x < WX - 4; x++) {
            for (int z = 4; z < WZ - 4; z++) {
                int h = hm[x * WZ + z];
                if (h <= 13) continue;
                float r = hh(x * 31 + 7, z * 17 + 3);
                if (r > 0.014f) continue;
                int tr = 4 + (r < 0.007f ? 1 : 0);
                int ty = h + tr;
                for (int ly = ty - 2; ly <= ty + 1; ly++) {
                    int rad = (ly >= ty) ? 1 : 2;
                    for (int dx = -rad; dx <= rad; dx++) {
                        for (int dz = -rad; dz <= rad; dz++) {
                            if (rad == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                            if (get(x + dx, ly, z + dz) == 0) put(x + dx, ly, z + dz, 5);
                        }
                    }
                }
                for (int y = h + 1; y <= ty; y++) put(x, y, z, 4);
            }
        }
    }

    // ---------- Mesh ----------
    static class FB {
        float[] a = new float[1 << 16];
        int n = 0;

        void v(float x, float y, float z, float r, float g, float b) {
            if (n + 6 > a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = x;
            a[n++] = y;
            a[n++] = z;
            a[n++] = r;
            a[n++] = g;
            a[n++] = b;
        }
    }

    static final FB mesh = new FB();

    static float hash(int x, int y, int z, int f) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ f * 2654435;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 255) / 255f;
    }

    static void addFace(FB f, int x, int y, int z, int face, int t) {
        float[] c = COL[t];
        float var = 1f + (hash(x, y, z, face) - 0.5f) * 0.16f;
        float[] top = c, bot = c;
        if (t == 1) {
            if (face == 3) {
                top = bot = COL[2];
            } else if (face != 2) {
                bot = COL[2];
                top = COL[1];
            }
        } else if (t == 4 && (face == 2 || face == 3)) {
            top = bot = new float[]{c[0] * 1.45f, c[1] * 1.45f, c[2] * 1.45f};
        }
        float s = SH[face] * var;
        for (int i = 0; i < 6; i++) {
            int k = IDX[i];
            int[] cr = CORN[face][k];
            float[] col = (face < 2 || face > 3) ? (k < 2 ? bot : top) : top;
            f.v(x + cr[0], y + cr[1], z + cr[2], col[0] * s, col[1] * s, col[2] * s);
        }
    }

    static void rebuild(int cx, int cz) {
        FB f = mesh;
        f.n = 0;
        for (int y = 0; y < WH; y++) {
            for (int z = cz * 16; z < cz * 16 + 16; z++) {
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    int t = blk[(y * WZ + z) * WX + x];
                    if (t == 0) continue;
                    for (int face = 0; face < 6; face++) {
                        int nx = x + DX[face], ny = y + DY[face], nz = z + DZ[face];
                        if (ny < 0) continue;
                        if (get(nx, ny, nz) != 0) continue;
                        addFace(f, x, y, z, face, t);
                    }
                }
            }
        }
        FloatBuffer buf = ByteBuffer.allocateDirect(Math.max(4, f.n * 4))
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buf.put(f.a, 0, f.n);
        buf.position(0);
        cbuf[cx * NC + cz] = buf;
        ccnt[cx * NC + cz] = f.n / 6;
    }

    static void setBlock(int x, int y, int z, int t) {
        if (x < 0 || x >= WX || z < 0 || z >= WZ || y < 0 || y >= WH) return;
        put(x, y, z, t);
        int cx = x >> 4, cz = z >> 4;
        rebuild(cx, cz);
        if ((x & 15) == 0 && cx > 0) rebuild(cx - 1, cz);
        if ((x & 15) == 15 && cx < NC - 1) rebuild(cx + 1, cz);
        if ((z & 15) == 0 && cz > 0) rebuild(cx, cz - 1);
        if ((z & 15) == 15 && cz < NC - 1) rebuild(cx, cz + 1);
    }

    static final float[] TQ = new float[12];

    static void box(FB f, float ax, float ay, float az, float cs, float sn,
                    float lx, float ly, float lz, float sx, float sy, float sz,
                    float r, float g, float b) {
        for (int face = 0; face < 6; face++) {
            for (int k = 0; k < 4; k++) {
                int[] c = CORN[face][k];
                float xl = lx + (c[0] - .5f) * sx, yl = ly + (c[1] - .5f) * sy, zl = lz + (c[2] - .5f) * sz;
                TQ[k * 3] = ax + xl * cs + zl * sn;
                TQ[k * 3 + 1] = ay + yl;
                TQ[k * 3 + 2] = az - xl * sn + zl * cs;
            }
            float s = SH[face];
            for (int i = 0; i < 6; i++) {
                int k = IDX[i];
                f.v(TQ[k * 3], TQ[k * 3 + 1], TQ[k * 3 + 2], r * s, g * s, b * s);
            }
        }
    }

    // ---------- Tiere ----------
    static class An {
        float x, y, z, yaw, t, ph;
        boolean walk;
        int kind;
    }

    // ---------- Renderer ----------
    static class Rend implements GLSurfaceView.Renderer {
        int prog, aPos, aCol, uMVP, uCam, uFog, uLight;
        final float[] proj = new float[16], view = new float[16], vp = new float[16];
        float px, py, pz, vy, yaw, pitch, cd, tod = 0.1f;
        boolean onGround, inited = false;
        long last = 0;
        final Random rnd = new Random();
        final An[] an = new An[NA];
        final FB af = new FB();
        final FloatBuffer abuf = ByteBuffer.allocateDirect(NA * 216 * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }

        @Override
        public void onSurfaceCreated(GL10 g, EGLConfig c) {
            String vs = "uniform mat4 uMVP;uniform vec3 uCam;attribute vec3 aPos;attribute vec3 aCol;"
                    + "varying vec3 vCol;varying float vD;"
                    + "void main(){gl_Position=uMVP*vec4(aPos,1.0);vCol=aCol;vD=length(aPos-uCam);}";
            String fs = "precision mediump float;uniform vec3 uFog;uniform float uLight;"
                    + "varying vec3 vCol;varying float vD;"
                    + "void main(){float f=clamp((vD-32.0)/36.0,0.0,1.0);"
                    + "gl_FragColor=vec4(mix(vCol*uLight,uFog,f),1.0);}";
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(prog);
            aPos = GLES20.glGetAttribLocation(prog, "aPos");
            aCol = GLES20.glGetAttribLocation(prog, "aCol");
            uMVP = GLES20.glGetUniformLocation(prog, "uMVP");
            uCam = GLES20.glGetUniformLocation(prog, "uCam");
            uFog = GLES20.glGetUniformLocation(prog, "uFog");
            uLight = GLES20.glGetUniformLocation(prog, "uLight");
            if (!ready) {
                gen();
                for (int cx = 0; cx < NC; cx++) for (int cz = 0; cz < NC; cz++) rebuild(cx, cz);
                ready = true;
            }
            if (!inited) {
                px = WX / 2f + 0.5f;
                pz = WZ / 2f + 0.5f;
                py = ground(WX / 2, WZ / 2) + 0.01f;
                for (int i = 0; i < NA; i++) {
                    an[i] = new An();
                    an[i].kind = i % 3;
                    spawn(an[i]);
                }
                inited = true;
                S.say("Sammle Holz an Bäumen und baue dir etwas!");
            }
        }

        @Override
        public void onSurfaceChanged(GL10 g, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            Matrix.perspectiveM(proj, 0, 70f, (float) w / Math.max(1, h), 0.1f, 150f);
        }

        void spawn(An a) {
            for (int i = 0; i < 60; i++) {
                float x = 6 + rnd.nextFloat() * (WX - 12), z = 6 + rnd.nextFloat() * (WZ - 12);
                int gy = ground((int) x, (int) z);
                if (gy > 13 && get((int) x, gy - 1, (int) z) == 1) {
                    a.x = x;
                    a.z = z;
                    a.y = gy;
                    a.t = 0;
                    return;
                }
            }
            a.x = WX / 2f + 5;
            a.z = WZ / 2f + 5;
            a.y = ground((int) a.x, (int) a.z);
        }

        boolean hit(float x, float y, float z) {
            int x0 = (int) Math.floor(x - 0.3f), x1 = (int) Math.floor(x + 0.3f);
            int y0 = (int) Math.floor(y), y1 = (int) Math.floor(y + 1.8f);
            int z0 = (int) Math.floor(z - 0.3f), z1 = (int) Math.floor(z + 0.3f);
            for (int a = x0; a <= x1; a++)
                for (int b = y0; b <= y1; b++)
                    for (int c = z0; c <= z1; c++)
                        if (solid(a, b, c)) return true;
            return false;
        }

        void move(float dx, float dy, float dz) {
            if (dx != 0) {
                float nx = px + dx;
                if (!hit(nx, py, pz)) px = nx;
            }
            if (dz != 0) {
                float nz = pz + dz;
                if (!hit(px, py, nz)) pz = nz;
            }
            if (dy != 0) {
                float ny = py + dy;
                if (!hit(px, ny, pz)) {
                    py = ny;
                    onGround = false;
                } else {
                    if (dy < 0) {
                        onGround = true;
                        py = (float) Math.floor(py);
                    }
                    vy = 0;
                }
            }
        }

        float[] dir() {
            float cp = (float) Math.cos(pitch);
            return new float[]{cp * (float) Math.sin(yaw), (float) Math.sin(pitch), -cp * (float) Math.cos(yaw)};
        }

        boolean ray(int[] o) {
            float ex = px, ey = py + 1.62f, ez = pz;
            float[] d = dir();
            int lx = (int) Math.floor(ex), ly = (int) Math.floor(ey), lz = (int) Math.floor(ez);
            for (float t = 0; t < 5f; t += 0.03f) {
                int bx = (int) Math.floor(ex + d[0] * t), by = (int) Math.floor(ey + d[1] * t),
                        bz = (int) Math.floor(ez + d[2] * t);
                if (bx != lx || by != ly || bz != lz) {
                    if (get(bx, by, bz) != 0) {
                        o[0] = bx;
                        o[1] = by;
                        o[2] = bz;
                        o[3] = lx;
                        o[4] = ly;
                        o[5] = lz;
                        return true;
                    }
                    lx = bx;
                    ly = by;
                    lz = bz;
                }
            }
            return false;
        }

        boolean attack() {
            float ex = px, ey = py + 1.62f, ez = pz;
            float[] d = dir();
            int best = -1;
            float bd = 99;
            for (int i = 0; i < NA; i++) {
                float vx = an[i].x - ex, vy2 = an[i].y + 0.5f - ey, vz = an[i].z - ez;
                float dist = (float) Math.sqrt(vx * vx + vy2 * vy2 + vz * vz);
                if (dist > 3.8f || dist < 0.01f) continue;
                float dot = (vx * d[0] + vy2 * d[1] + vz * d[2]) / dist;
                if (dot > 0.9f && dist < bd) {
                    bd = dist;
                    best = i;
                }
            }
            if (best < 0) return false;
            spawn(an[best]);
            S.cnt[10]++;
            S.say("Fleisch +1");
            return true;
        }

        void doBreak() {
            if (attack()) return;
            int[] o = new int[6];
            if (!ray(o)) return;
            int t = get(o[0], o[1], o[2]);
            if (o[1] == 0) return;
            if ((t == 3 || t == 9) && !S.pick) {
                S.say("Du brauchst eine Spitzhacke (Craft-Button)");
                return;
            }
            int drop = t == 1 ? 2 : (t == 3 ? 9 : t);
            S.cnt[drop]++;
            setBlock(o[0], o[1], o[2], 0);
        }

        void doPlace() {
            int item = ITEMS[S.sel];
            if (S.cnt[item] <= 0) return;
            int[] o = new int[6];
            if (!ray(o)) return;
            int bx = o[3], by = o[4], bz = o[5];
            if (by < 0 || by >= WH) return;
            if (bx + 1 > px - 0.3f && bx < px + 0.3f && by + 1 > py && by < py + 1.8f
                    && bz + 1 > pz - 0.3f && bz < pz + 0.3f) return;
            S.cnt[item]--;
            setBlock(bx, by, bz, item);
        }

        void update(float dt) {
            float[] lk = S.takeLook();
            yaw += lk[0];
            pitch += lk[1];
            if (pitch > 1.5f) pitch = 1.5f;
            if (pitch < -1.5f) pitch = -1.5f;
            float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw);
            float fw = S.my, rt = S.mx;
            float wx = (sy * fw + cy * rt) * 4.5f, wz = (-cy * fw + sy * rt) * 4.5f;
            vy -= 22f * dt;
            if (vy < -16f) vy = -16f;
            if (S.jump && onGround) {
                vy = 7.6f;
                onGround = false;
            }
            move(wx * dt, 0, 0);
            move(0, 0, wz * dt);
            move(0, vy * dt, 0);
            if (py < -5) {
                px = WX / 2f + 0.5f;
                pz = WZ / 2f + 0.5f;
                py = ground(WX / 2, WZ / 2) + 0.01f;
                vy = 0;
            }
            cd -= dt;
            if (cd <= 0) {
                if (S.breakH) {
                    doBreak();
                    cd = 0.25f;
                } else if (S.placeH) {
                    doPlace();
                    cd = 0.25f;
                }
            }
            int cr = S.craftReq;
            S.craftReq = 0;
            if (cr == 1) {
                if (S.cnt[4] >= 1) {
                    S.cnt[4]--;
                    S.cnt[8] += 4;
                    S.say("4 Bretter gecraftet");
                } else S.say("Du brauchst 1 Holz");
            } else if (cr == 2) {
                if (S.pick) S.say("Du hast schon eine Spitzhacke");
                else if (S.cnt[8] >= 3 && S.cnt[4] >= 2) {
                    S.cnt[8] -= 3;
                    S.cnt[4] -= 2;
                    S.pick = true;
                    S.say("Spitzhacke gecraftet!");
                } else S.say("Du brauchst 3 Bretter + 2 Holz");
            }
            tod += dt / 300f;
            if (tod > 1) tod -= 1;
            S.tod = tod;
            S.px = px;
            S.py = py;
            S.pz = pz;
            for (int i = 0; i < NA; i++) {
                An a = an[i];
                a.t -= dt;
                if (a.t <= 0) {
                    a.t = 2 + rnd.nextFloat() * 4;
                    a.walk = rnd.nextFloat() < 0.7f;
                    a.yaw = rnd.nextFloat() * 6.283f;
                }
                if (a.walk) {
                    float nx = a.x + (float) Math.sin(a.yaw) * 0.9f * dt;
                    float nz = a.z + (float) Math.cos(a.yaw) * 0.9f * dt;
                    if (nx > 2 && nx < WX - 2 && nz > 2 && nz < WZ - 2) {
                        int gy = ground((int) nx, (int) nz);
                        if (gy >= 1 && Math.abs(gy - a.y) <= 1.01f) {
                            a.x = nx;
                            a.z = nz;
                            a.y = gy;
                        } else a.t = 0;
                    } else a.t = 0;
                    a.ph += dt * 6f;
                }
            }
        }

        void drawBuf(FloatBuffer b, int count) {
            b.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, b);
            b.position(3);
            GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 24, b);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
            b.position(0);
        }

        void render() {
            float d = Math.max(0f, Math.min(1f, (float) Math.sin(tod * 6.2832f) * 1.5f + 0.4f));
            float light = 0.25f + 0.75f * d;
            float sr = 0.02f + 0.53f * d, sg = 0.03f + 0.73f * d, sb = 0.07f + 0.88f * d;
            GLES20.glClearColor(sr, sg, sb, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(prog);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aCol);
            float ex = px, ey = py + 1.62f, ez = pz;
            float[] dr = dir();
            Matrix.setLookAtM(view, 0, ex, ey, ez, ex + dr[0], ey + dr[1], ez + dr[2], 0, 1, 0);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
            GLES20.glUniformMatrix4fv(uMVP, 1, false, vp, 0);
            GLES20.glUniform3f(uCam, ex, ey, ez);
            GLES20.glUniform3f(uFog, sr, sg, sb);
            GLES20.glUniform1f(uLight, light);
            float hx = (float) Math.sin(yaw), hz = -(float) Math.cos(yaw);
            for (int cx = 0; cx < NC; cx++) {
                for (int cz = 0; cz < NC; cz++) {
                    float mx = cx * 16 + 8 - ex, mz = cz * 16 + 8 - ez;
                    if (mx * hx + mz * hz < -12f) continue;
                    if (mx * mx + mz * mz > 90f * 90f) continue;
                    int i = cx * NC + cz;
                    if (cbuf[i] != null && ccnt[i] > 0) drawBuf(cbuf[i], ccnt[i]);
                }
            }
            af.n = 0;
            for (int i = 0; i < NA; i++) {
                An a = an[i];
                float cs = (float) Math.cos(a.yaw), sn = (float) Math.sin(a.yaw);
                float[] bc, hc, lc;
                if (a.kind == 0) {
                    bc = new float[]{.93f, .65f, .63f};
                    hc = new float[]{.95f, .58f, .57f};
                    lc = new float[]{.85f, .55f, .54f};
                } else if (a.kind == 1) {
                    bc = new float[]{.30f, .22f, .17f};
                    hc = new float[]{.25f, .18f, .14f};
                    lc = new float[]{.20f, .15f, .12f};
                } else {
                    bc = new float[]{.92f, .92f, .90f};
                    hc = new float[]{.25f, .22f, .20f};
                    lc = new float[]{.30f, .27f, .25f};
                }
                box(af, a.x, a.y, a.z, cs, sn, 0, 0.65f, 0, 0.5f, 0.42f, 0.85f, bc[0], bc[1], bc[2]);
                box(af, a.x, a.y, a.z, cs, sn, 0, 0.8f, 0.55f, 0.34f, 0.34f, 0.34f, hc[0], hc[1], hc[2]);
                for (int l = 0; l < 4; l++) {
                    float lxp = (l % 2 == 0) ? 0.17f : -0.17f;
                    float lzp = (l < 2) ? 0.28f : -0.28f;
                    float ph = (l == 0 || l == 3) ? a.ph : a.ph + 3.1416f;
                    float sw = a.walk ? (float) Math.sin(ph) * 0.12f : 0f;
                    box(af, a.x, a.y, a.z, cs, sn, lxp, 0.22f, lzp + sw, 0.14f, 0.44f, 0.14f,
                            lc[0], lc[1], lc[2]);
                }
            }
            abuf.clear();
            abuf.put(af.a, 0, af.n);
            abuf.position(0);
            drawBuf(abuf, af.n / 6);
        }

        @Override
        public void onDrawFrame(GL10 g) {
            long n = System.nanoTime();
            float dt = (n - last) / 1e9f;
            last = n;
            if (dt > 0.033f) dt = 0.033f;
            if (dt <= 0f) dt = 0.001f;
            if (!ready || !inited) return;
            update(dt);
            render();
        }
    }

    // ---------- Bedienung & Anzeige ----------
    static class Hud extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;
        final RectF craftBtn = new RectF(), panel = new RectF();
        final RectF[] hot = new RectF[6];
        final RectF[] rows = new RectF[2];
        final float[][] circ = new float[3][3];
        int movePid = -1, lookPid = -1, jumpPid = -1, breakPid = -1, placePid = -1;
        float joyX, joyY, knobX, knobY, lx, ly;

        Hud(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            for (int i = 0; i < 6; i++) hot[i] = new RectF();
            for (int i = 0; i < 2; i++) rows[i] = new RectF();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            craftBtn.set(w - 100 * d, 8 * d, w - 8 * d, 48 * d);
            float sw = 44 * d, gap = 4 * d;
            float x0 = (w - (6 * sw + 5 * gap)) / 2f;
            for (int i = 0; i < 6; i++) hot[i].set(x0 + i * (sw + gap), 8 * d, x0 + i * (sw + gap) + sw, 8 * d + sw);
            circ[0] = new float[]{w - 65 * d, h - 65 * d, 36 * d};
            circ[1] = new float[]{w - 150 * d, h - 65 * d, 36 * d};
            circ[2] = new float[]{w - 65 * d, h - 150 * d, 36 * d};
            panel.set(w / 2f - 170 * d, 64 * d, w / 2f + 170 * d, 194 * d);
            rows[0].set(panel.left + 8 * d, 72 * d, panel.right - 8 * d, 126 * d);
            rows[1].set(panel.left + 8 * d, 132 * d, panel.right - 8 * d, 186 * d);
        }

        boolean in(float[] c, float x, float y) {
            float dx = x - c[0], dy = y - c[1];
            return dx * dx + dy * dy <= c[2] * c[2];
        }

        void down(int id, float x, float y) {
            if (craftBtn.contains(x, y)) {
                S.craftOpen = !S.craftOpen;
                return;
            }
            if (S.craftOpen) {
                for (int i = 0; i < 2; i++) {
                    if (rows[i].contains(x, y)) {
                        S.craftReq = i + 1;
                        return;
                    }
                }
                if (panel.contains(x, y)) return;
            }
            for (int k = 0; k < 6; k++) {
                if (hot[k].contains(x, y)) {
                    S.sel = k;
                    return;
                }
            }
            if (in(circ[0], x, y)) {
                jumpPid = id;
                S.jump = true;
                return;
            }
            if (in(circ[1], x, y)) {
                breakPid = id;
                S.breakH = true;
                return;
            }
            if (in(circ[2], x, y)) {
                placePid = id;
                S.placeH = true;
                return;
            }
            if (x < getWidth() * 0.45f && movePid < 0) {
                movePid = id;
                joyX = x;
                joyY = y;
                knobX = x;
                knobY = y;
                return;
            }
            if (lookPid < 0) {
                lookPid = id;
                lx = x;
                ly = y;
            }
        }

        void moved(int id, float x, float y) {
            if (id == movePid) {
                float R = 60 * d;
                float dx = x - joyX, dy = y - joyY;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > R) {
                    dx = dx / len * R;
                    dy = dy / len * R;
                }
                knobX = joyX + dx;
                knobY = joyY + dy;
                S.mx = dx / R;
                S.my = -dy / R;
            } else if (id == lookPid) {
                S.addLook((x - lx) * 0.0045f, -(y - ly) * 0.0045f);
                lx = x;
                ly = y;
            }
        }

        void up(int id) {
            if (id == movePid) {
                movePid = -1;
                S.mx = 0;
                S.my = 0;
            }
            if (id == lookPid) lookPid = -1;
            if (id == jumpPid) {
                jumpPid = -1;
                S.jump = false;
            }
            if (id == breakPid) {
                breakPid = -1;
                S.breakH = false;
            }
            if (id == placePid) {
                placePid = -1;
                S.placeH = false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            int idx = e.getActionIndex();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
            } else if (a == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) moved(e.getPointerId(i), e.getX(i), e.getY(i));
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
                up(e.getPointerId(idx));
            } else if (a == MotionEvent.ACTION_CANCEL) {
                for (int i = 0; i < e.getPointerCount(); i++) up(e.getPointerId(i));
            }
            return true;
        }

        void circle(float[] c, String label, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            canvasRef.drawCircle(c[0], c[1], c[2], p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xCCFFFFFF);
            canvasRef.drawCircle(c[0], c[1], c[2], p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(11 * d);
            p.setTextAlign(Paint.Align.CENTER);
            canvasRef.drawText(label, c[0], c[1] + 4 * d, p);
        }

        Canvas canvasRef;

        @Override
        protected void onDraw(Canvas c) {
            canvasRef = c;
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0xCCFFFFFF);
            c.drawLine(w / 2 - 9 * d, h / 2, w / 2 + 9 * d, h / 2, p);
            c.drawLine(w / 2, h / 2 - 9 * d, w / 2, h / 2 + 9 * d, p);

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(12 * d);
            p.setColor(0xFFFFFFFF);
            c.drawText(String.format("X %.0f  Y %.0f  Z %.0f", S.px, S.py, S.pz), 10 * d, 20 * d, p);
            float dd = Math.max(0f, Math.min(1f, (float) Math.sin(S.tod * 6.2832f) * 1.5f + 0.4f));
            c.drawText((dd > 0.5f ? "Tag" : "Nacht") + "   Fleisch: " + S.cnt[10]
                    + "   Spitzhacke: " + (S.pick ? "ja" : "nein"), 10 * d, 38 * d, p);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0xAA000000);
            c.drawRoundRect(craftBtn, 10 * d, 10 * d, p);
            p.setColor(0xFFFFFFFF);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(14 * d);
            c.drawText("Craft", craftBtn.centerX(), craftBtn.centerY() + 5 * d, p);

            for (int i = 0; i < 6; i++) {
                RectF r = hot[i];
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x99000000);
                c.drawRoundRect(r, 6 * d, 6 * d, p);
                float[] col = COL[ITEMS[i]];
                p.setColor(Color.rgb((int) (col[0] * 255), (int) (col[1] * 255), (int) (col[2] * 255)));
                c.drawRect(r.left + 9 * d, r.top + 9 * d, r.right - 9 * d, r.bottom - 9 * d, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth((i == S.sel ? 4 : 1.5f) * d);
                p.setColor(i == S.sel ? 0xFF00E5FF : 0x88FFFFFF);
                c.drawRoundRect(r, 6 * d, 6 * d, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                p.setTextSize(12 * d);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(String.valueOf(S.cnt[ITEMS[i]]), r.right - 3 * d, r.bottom - 3 * d, p);
            }
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(11 * d);
            p.setColor(0xFFFFFFFF);
            c.drawText(NAMES[S.sel], w / 2, 66 * d, p);

            if (movePid >= 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(0x88FFFFFF);
                c.drawCircle(joyX, joyY, 60 * d, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x88FFFFFF);
                c.drawCircle(knobX, knobY, 24 * d, p);
            }
            circle(circ[0], "Sprung", 0x66000000);
            circle(circ[1], "Abbauen", 0x66FF4D4D);
            circle(circ[2], "Setzen", 0x664DFF88);

            if (S.craftOpen) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xDD101020);
                c.drawRoundRect(panel, 12 * d, 12 * d, p);
                boolean ok1 = S.cnt[4] >= 1;
                boolean ok2 = !S.pick && S.cnt[8] >= 3 && S.cnt[4] >= 2;
                String[] t1 = {"Bretter", "Spitzhacke"};
                String[] t2 = {"1 Holz -> 4 Bretter", "3 Bretter + 2 Holz"};
                boolean[] ok = {ok1, ok2};
                for (int i = 0; i < 2; i++) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(ok[i] ? 0xFF1E6B3A : 0xFF3A3A48);
                    c.drawRoundRect(rows[i], 8 * d, 8 * d, p);
                    p.setColor(0xFFFFFFFF);
                    p.setTextAlign(Paint.Align.LEFT);
                    p.setTextSize(15 * d);
                    c.drawText(t1[i], rows[i].left + 12 * d, rows[i].top + 22 * d, p);
                    p.setTextSize(12 * d);
                    p.setColor(0xFFCCCCDD);
                    c.drawText(t2[i], rows[i].left + 12 * d, rows[i].top + 42 * d, p);
                }
            }
            if (System.currentTimeMillis() < S.msgUntil) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFE066);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(14 * d);
                c.drawText(S.msg, w / 2, h - 24 * d, p);
            }
            postInvalidateOnAnimation();
        }
    }
}
