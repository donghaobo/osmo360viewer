package com.osmo.viewer;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 双鱼眼实时缝合。每帧两个 GPU 采样 + 一次加权平均：
 *   屏幕像素 -> 视线方向 -> 各镜头相机坐标 -> UCM 投影 -> 像素。
 * 几何全部解析计算，不需要预烘 LUT。
 */
public class StitchRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "StitchRenderer";

    public interface Listener {
        /** GL 上下文就绪，SurfaceTexture 已创建：可以开始解码了。 */
        void onGlReady(SurfaceTexture[] surfaces);
        /** 每次画面提交后的统计回调（可能在 GL 线程）。 */
        void onFrame(long ptsUs, float fps);

        /** GL 资源初始化失败（着色器编译/链接）时的原因。 */
        void onGlError(String message);
    }

    private static final String VERT = """
            #version 300 es
            in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
            """;

    private static final String FRAG = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;

            uniform samplerExternalOES uTex0;
            uniform samplerExternalOES uTex1;
            uniform mat4 uTexMat0;
            uniform mat4 uTexMat1;

            uniform vec2  uOut;       // 画布像素尺寸
            uniform vec2  uVid;       // 视频像素尺寸
            uniform float uTanH, uTanV;
            uniform mat3  uViewRot;   // 视角：世界方向 = uViewRot * 相机方向
            uniform float uYaw;       // 仅等距矩形模式用于横向滚动
            uniform mat3  uRot1;      // cam0 -> cam1
            uniform vec4  uL0, uL1;   // fx, fy, cx, cy
            uniform vec4  uD0, uD1;   // k1, k2, p1, p2
            uniform vec2  uXi;
            uniform vec2  uRmax;
            uniform vec3  uGain1;
            uniform vec2  uFeatherSeam; // (羽化弧度, 接缝偏移弧度)
            uniform float uPoleCut;
            uniform float uMode;        // 0 = 透视, 1 = 等距矩形

            out vec4 outColor;

            // UCM 正向投影：射线 -> 该镜头的像素坐标；false 表示这根射线落在镜头看不到的地方。
            bool project(vec3 d, vec4 L, vec4 K, float xi, float rmax, out vec2 uv) {
              float den = d.z + xi;
              if (den <= 1e-4) return false;
              float a = d.x / den, b = d.y / den;
              float r2 = a * a + b * b;
              float dr = 1.0 + K.x * r2 + K.y * r2 * r2;
              float xd = a * dr + 2.0 * K.z * a * b + K.w * (r2 + 2.0 * a * a);
              float yd = b * dr + K.z * (r2 + 2.0 * b * b) + 2.0 * K.w * a * b;
              vec2 p = vec2(L.x * xd + L.z, L.y * yd + L.w);
              uv = p / uVid;
              return length(p - L.zw) <= rmax;
            }

            vec2 orient(mat4 m, vec2 uv) {
              return (m * vec4(uv, 0.0, 1.0)).xy;
            }

            void main() {
              vec3 d0;
              if (uMode > 0.5) {
                // 等距矩形：整球一次画完，同样支持拖动横向浏览
                float lon = (gl_FragCoord.x / uOut.x - 0.5) * 6.28318530718 + uYaw;
                float lat = (gl_FragCoord.y / uOut.y - 0.5) * 3.14159265359;
                d0 = vec3(sin(lon) * cos(lat), -sin(lat), cos(lon) * cos(lat));
              } else {
                vec2 ndc = gl_FragCoord.xy / uOut * 2.0 - 1.0;
                vec3 v = normalize(vec3(ndc.x * uTanH, -ndc.y * uTanV, 1.0));
                d0 = uViewRot * v;
              }
              vec3 d1 = uRot1 * d0;

              vec2 uv0, uv1;
              bool ok0 = project(d0, uL0, uD0, uXi.x, uRmax.x, uv0);
              bool ok1 = project(d1, uL1, uD1, uXi.y, uRmax.y, uv1);
              if (!ok0 && !ok1) { outColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

              // 接缝 = 两镜头到各自光轴夹角相等的那圈大圆，按角度差羽化。
              float th0 = acos(clamp(d0.z, -1.0, 1.0));
              float th1 = acos(clamp(d1.z, -1.0, 1.0)) + uFeatherSeam.y;

              // 极点帽区两路都是掠射，宽羽化会拖出鬼影：帽内收窄到近乎硬边。
              float feather = uFeatherSeam.x;
              if (uPoleCut > 0.0) {
                float k = smoothstep(uPoleCut - 0.05, uPoleCut + 0.05, abs(d0.y));
                feather = mix(uFeatherSeam.x, 0.012, k);
              }
              float w0 = clamp(0.5 + (th1 - th0) / max(feather, 1e-4), 0.0, 1.0);
              w0 = w0 * w0 * (3.0 - 2.0 * w0);

              if (!ok0) w0 = 0.0;
              float w1 = ok1 ? 1.0 - w0 : 0.0;

              vec3 c0 = ok0 ? texture(uTex0, orient(uTexMat0, uv0)).rgb : vec3(0.0);
              vec3 c1 = ok1 ? texture(uTex1, orient(uTexMat1, uv1)).rgb * uGain1 : vec3(0.0);
              outColor = vec4((c0 * w0 + c1 * w1) / max(w0 + w1, 1e-5), 1.0);
            }
            """;

    private final ViewState state;
    private final Calib calib;
    private final Listener listener;

    private int program;
    private int aPos;
    private int vao;
    private int vbo;
    private int tex0;
    private int tex1;
    private SurfaceTexture st0;
    private SurfaceTexture st1;

    private long lastTs0 = Long.MIN_VALUE;
    private long lastTs1 = Long.MIN_VALUE;
    private final float[] mat0 = new float[16];
    private final float[] mat1 = new float[16];
    private final int[] viewport = new int[4];
    private final float[] viewRot = new float[9];
    private final float[] rot1Gl = new float[9];
    private volatile long lastPts;   // 最近一次交给显示的那一帧的 PTS（微秒）
    /** 稳像：本帧取到图之后、算视图矩阵之前调用，时刻天然对齐，不经过 UI 定时器。 */
    private Stabilizer stabilizer;
    private int frameCount;
    private long fpsStart;

    // 诊断：这些数是判断「没画面」到底卡在哪一层的唯一依据
    private volatile long lastConsumeMs;      // 最近一次 updateTexImage 的时刻
    private volatile int consumed0, consumed1;
    private volatile int drawCount;
    private volatile boolean rebuildRequested;
    private volatile boolean fatal;
    private int diagDraws;
    private int diagConsumed0, diagConsumed1;
    private long diagStartMs;
    private int lastGlError;
    private long lastPullErrorMs;

    // uniform 位置
    private int uOut, uVid, uTanH, uTanV, uYaw, uViewRot, uRot1;
    private int uL0, uL1, uD0, uD1, uXi, uRmax, uGain1, uFeatherSeam, uPoleCut, uMode;
    private int uTexMat0, uTexMat1;

    public StitchRenderer(ViewState state, Calib calib, Listener listener) {
        this.state = state;
        this.calib = calib;
        this.listener = listener;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            program = GlUtil.program(VERT, FRAG);
        } catch (Throwable t) {
            Log.e(TAG, "shader init failed", t);
            listener.onGlError("图形初始化失败：" + t.getMessage());
            return;
        }
        GLES30.glUseProgram(program);
        aPos = GLES30.glGetAttribLocation(program, "aPos");

        int[] arr = new int[1];
        GLES30.glGenVertexArrays(1, arr, 0);
        vao = arr[0];
        GLES30.glBindVertexArray(vao);
        int[] buf = new int[1];
        GLES30.glGenBuffers(1, buf, 0);
        vbo = buf[0];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
        FloatBuffer verts = GlUtil.fullscreenTriangle();
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, verts, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(aPos);
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glBindVertexArray(0);

        createTextures();
        float[] rot = calib.rot;
        // GLSL mat3 列主序：行主序 rot 需要转置
        System.arraycopy(new float[]{rot[0], rot[3], rot[6], rot[1], rot[4], rot[7],
                rot[2], rot[5], rot[8]}, 0, rot1Gl, 0, 9);
        lastConsumeMs = SystemClock.elapsedRealtime();
        diagStartMs = lastConsumeMs;

        uOut = GLES30.glGetUniformLocation(program, "uOut");
        uVid = GLES30.glGetUniformLocation(program, "uVid");
        uTanH = GLES30.glGetUniformLocation(program, "uTanH");
        uTanV = GLES30.glGetUniformLocation(program, "uTanV");
        uYaw = GLES30.glGetUniformLocation(program, "uYaw");
        uViewRot = GLES30.glGetUniformLocation(program, "uViewRot");
        uRot1 = GLES30.glGetUniformLocation(program, "uRot1");
        uL0 = GLES30.glGetUniformLocation(program, "uL0");
        uL1 = GLES30.glGetUniformLocation(program, "uL1");
        uD0 = GLES30.glGetUniformLocation(program, "uD0");
        uD1 = GLES30.glGetUniformLocation(program, "uD1");
        uXi = GLES30.glGetUniformLocation(program, "uXi");
        uRmax = GLES30.glGetUniformLocation(program, "uRmax");
        uGain1 = GLES30.glGetUniformLocation(program, "uGain1");
        uFeatherSeam = GLES30.glGetUniformLocation(program, "uFeatherSeam");
        uPoleCut = GLES30.glGetUniformLocation(program, "uPoleCut");
        uMode = GLES30.glGetUniformLocation(program, "uMode");
        uTexMat0 = GLES30.glGetUniformLocation(program, "uTexMat0");
        uTexMat1 = GLES30.glGetUniformLocation(program, "uTexMat1");

        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex0"), 0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex1"), 1);

        GlUtil.check("onSurfaceCreated");
        listener.onGlReady(new SurfaceTexture[]{st0, st1});
    }

    /**
     * 无条件 updateTexImage 一次；只有时间戳变了才算拿到新帧。
     * 不依赖 OnFrameAvailableListener —— 它在部分机型/系统版本上会停发。
     */
    private boolean pullFrame(int idx) {
        SurfaceTexture st = idx == 0 ? st0 : st1;
        if (st == null) return false;
        try {
            st.updateTexImage();
            st.getTransformMatrix(idx == 0 ? mat0 : mat1);
            long ts = st.getTimestamp();
            long prev = idx == 0 ? lastTs0 : lastTs1;
            if (ts == prev) return false;
            if (idx == 0) {
                lastTs0 = ts;
                lastPts = ts;
                consumed0++;
            } else {
                lastTs1 = ts;
                consumed1++;
            }
            return true;
        } catch (Throwable t) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPullErrorMs > 1000) {
                lastPullErrorMs = now;
                Diag.w(TAG, "流 " + idx + " 取图失败", t);
            }
            return false;
        }
    }

    /** 建外部纹理 + SurfaceTexture。 */
    private void createTextures() {
        tex0 = GlUtil.externalTexture();
        tex1 = GlUtil.externalTexture();
        st0 = new SurfaceTexture(tex0);
        st1 = new SurfaceTexture(tex1);
        st0.getTransformMatrix(mat0);
        st1.getTransformMatrix(mat1);
        lastTs0 = Long.MIN_VALUE;
        lastTs1 = Long.MIN_VALUE;
        Diag.i(TAG, "SurfaceTexture 已创建 tex0=" + tex0 + " tex1=" + tex1);
    }

    private void destroyTextures() {
        if (st0 != null) {
            st0.release();
            st0 = null;
        }
        if (st1 != null) {
            st1.release();
            st1 = null;
        }
        int[] t = {tex0, tex1};
        GLES30.glDeleteTextures(2, t, 0);
        tex0 = 0;
        tex1 = 0;
    }

    /** 由 UI 线程请求把 SurfaceTexture 全换一套（解码端会跟着重开）。 */
    public void requestTextureRebuild() {
        rebuildRequested = true;
    }

    /**
     * 屏幕上正在显示的那一帧的媒体时间（微秒）；还没出帧时返回 -1。
     *
     * 稳像要按「看得见的那一帧」去查姿态：播放器的 positionUs() 是墙钟插值，
     * 与解码管线里已经吃掉、但还没显示出去的帧差着几十到一百多毫秒，用错就会
     * 把补偿推成「反相」，画面越看越抖。
     */
    public void setStabilizer(Stabilizer s) {
        this.stabilizer = s;
    }

    public long displayedPtsUs() {
        long pts = lastPts;
        return pts <= 0 ? -1 : pts / 1000;
    }

    public long lastConsumeMs() {
        return lastConsumeMs;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (program == 0) return;
        try {
            drawFrame();
        } catch (Throwable t) {
            // 抛出 onDrawFrame 会直接杀死 GLSurfaceView 的渲染线程，之后永远不再出图
            Diag.e(TAG, "渲染线程异常（之后不会再出图）", t);
            fatal = true;
            listener.onGlError("渲染出错：" + t);
        }
    }

    /** 渲染线程是否已经挂了（GLSurfaceView 不会自己恢复）。 */
    public boolean isFatal() {
        return fatal;
    }

    private void drawFrame() {

        if (rebuildRequested) {
            rebuildRequested = false;
            Diag.w(TAG, "重建 SurfaceTexture（画面停更）");
            destroyTextures();
            createTextures();
            lastConsumeMs = SystemClock.elapsedRealtime();
            listener.onGlReady(new SurfaceTexture[]{st0, st1});
        }

        // 无条件取图：这台设备的 onFrameAvailable 会静默停发（曾导致画面卡死在旧帧）
        boolean got0 = pullFrame(0);
        boolean got1 = pullFrame(1);
        if (got0 || got1) lastConsumeMs = SystemClock.elapsedRealtime();

        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);
        GLES30.glBindVertexArray(vao);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex1);

        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0);
        int w = Math.max(1, viewport[2]);
        int h = Math.max(1, viewport[3]);

        float fov = state.fov;
        float aspect = (float) w / h;
        float halfWide = (float) Math.tan(Math.toRadians(fov) / 2);
        float tanH = aspect >= 1 ? halfWide : halfWide * aspect;
        float tanV = aspect >= 1 ? halfWide / aspect : halfWide;

        GLES30.glUniform2f(uOut, w, h);
        GLES30.glUniform2f(uVid, state.vidW, state.vidH);
        GLES30.glUniform1f(uTanH, tanH);
        GLES30.glUniform1f(uTanV, tanV);
        if (stabilizer != null) stabilizer.update(lastPts / 1000);
        state.getMatrix(viewRot);
        GLES30.glUniformMatrix3fv(uViewRot, 1, false, viewRot, 0);
        GLES30.glUniform1f(uYaw, state.yaw());

        float s = Calib.scaleFor(state.vidW);
        GLES30.glUniform4f(uL0, calib.cam0[0] * s, calib.cam0[1] * s, calib.cam0[2] * s, calib.cam0[3] * s);
        GLES30.glUniform4f(uL1, calib.cam1[0] * s, calib.cam1[1] * s, calib.cam1[2] * s, calib.cam1[3] * s);
        GLES30.glUniform4f(uD0, calib.cam0[4], calib.cam0[5], calib.cam0[6], calib.cam0[7]);
        GLES30.glUniform4f(uD1, calib.cam1[4], calib.cam1[5], calib.cam1[6], calib.cam1[7]);
        GLES30.glUniform2f(uXi, calib.cam0[8], calib.cam1[8]);
        GLES30.glUniform2f(uRmax, calib.cam0[9] * s, calib.cam1[9] * s);
        GLES30.glUniform3f(uGain1, calib.gain[0], calib.gain[1], calib.gain[2]);

        GLES30.glUniformMatrix3fv(uRot1, 1, false, rot1Gl, 0);

        GLES30.glUniform2f(uFeatherSeam,
                (float) Math.toRadians(Math.max(0.3f, state.featherDeg)),
                (float) Math.toRadians(state.seamDeg));
        GLES30.glUniform1f(uPoleCut, state.poleCut);
        GLES30.glUniform1f(uMode, state.equirect ? 1f : 0f);
        GLES30.glUniformMatrix4fv(uTexMat0, 1, false, mat0, 0);
        GLES30.glUniformMatrix4fv(uTexMat1, 1, false, mat1, 0);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindVertexArray(0);
        drawCount++;

        int err = GLES30.glGetError();
        if (err != 0 && err != lastGlError) {
            lastGlError = err;
            Diag.w(TAG, "glError 0x" + Integer.toHexString(err));
        }

        frameCount++;
        long now = System.nanoTime();
        if (fpsStart == 0) fpsStart = now;
        if (now - fpsStart >= 500_000_000L) {
            float fps = frameCount * 1e9f / (now - fpsStart);
            frameCount = 0;
            fpsStart = now;
            listener.onFrame(lastPts, fps);
        }

        // 每秒一条渲染状态：绘制次数 / 两路纹理各更新了多少 / 距上次出图多久
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - diagStartMs >= 1000) {
            Diag.i(TAG, "渲染每秒 绘制=" + (drawCount - diagDraws)
                    + " 纹理更新0=" + (consumed0 - diagConsumed0)
                    + " 更新1=" + (consumed1 - diagConsumed1)
                    + " 距上次出图=" + (nowMs - lastConsumeMs) + "ms"
                    + " 视口=" + viewport[2] + "x" + viewport[3]
                    + " program=" + program);
            diagStartMs = nowMs;
            diagDraws = drawCount;
            diagConsumed0 = consumed0;
            diagConsumed1 = consumed1;
        }
    }

    public int drawCount() {
        return drawCount;
    }

    public SurfaceTexture[] surfaces() {
        return new SurfaceTexture[]{st0, st1};
    }
}
