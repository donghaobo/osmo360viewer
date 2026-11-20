package com.osmo.viewer;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** 播放页：双鱼眼实时缝合 + 触摸/体感视角 + 播放控制。 */
public class PlayerActivity extends Activity
        implements StitchRenderer.Listener, DualVideoSource.Listener {

    public static final String EXTRA_NAME = "name";

    private static final int MAX_SEEK = 1000;
    /**
     * 开播前只等 8MB（约 1 秒内容），起播快；播放中抗网络抖动靠 RemoteFile
     * 那 50MB 的领先预读，不靠这个数。
     */
    private static final long INITIAL_BUFFER_BYTES = 8L * 1024 * 1024;
    private static final long INITIAL_BUFFER_TIMEOUT_MS = 20000;
    private static final String TAG = "OsvPlay";

    private final ViewState state = new ViewState();
    private final Calib calib = new Calib();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private GLSurfaceView glView;
    private StitchRenderer renderer;
    private DualVideoSource player;
    private AudioSource audio;
    private SensorOrientation sensor;

    private SurfaceTexture[] surfaces;
    private Uri uri;
    /** 远程播放时非空：整个播放期共用一份连接与块缓存。 */
    private RemoteFile remoteFile;
    private long durationUs = 1;
    private long savedPosUs;
    private long lastHudMs;

    private boolean sensorMode;
    private boolean sensorRefPending;
    private final float[] qOffset = {1f, 0f, 0f, 0f};     // 体感模式下手指的微调量
    private final float[] qSensorLast = {1f, 0f, 0f, 0f}; // 最近一次传感器姿态
    private final float[] qTmp = new float[4];
    private final float[] qTmp2 = new float[4];
    private final float[] qViewTmp = new float[4];

    /** 逐帧相机姿态（来自 djmd 轨），用于抵消拍摄时的转动（只给标签用，计算在 Stabilizer）。 */
    private AttitudeTrack attitudeTrack;
    /** 稳像的可调参数（延时/强度/平滑/时刻源），真机现场调。 */
    private StabSettings stab;
    /** 稳像本体：渲染线程每帧按显示帧的时刻调用，不走 UI 定时器。 */
    private Stabilizer stabilizer;

    private boolean userSeeking;
    private boolean resumeAfterSeek;
    private boolean looping = true;   // 播完自动从头再来，避免「停在最后一帧像卡住」

    private TextView msg;

    // 初始垂直方向（来自 OSV 内嵌的相机姿态）
    private float initialRoll = Float.NaN;
    private boolean autoLevel = true;
    private boolean attitudeLoaded;
    private boolean viewTouched;   // 用户已经动过视角的话，别再用姿态数据把画面转回去

    // 触摸
    private float lastX, lastY, pinchStart, pinchFov;
    private float lastPinchAngle = Float.NaN;   // 双指连线的角度（弧度）
    private float lastMoveX, lastMoveY, lastMoveTime;
    private float angularVelYaw, angularVelPitch;   // rad/s
    private long downTime;
    private float downX, downY;
    private boolean controlsHidden;
    private boolean dragging;

    private long lastRebuildMs;
    private int lastDrawCount;

    /** 每秒检查一次：解码在出帧、画面却不动，就把 SurfaceTexture 换一套重来。 */
    private final Runnable renderWatchdog = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (renderer != null && player != null && !player.isPaused()) {
                long staleMs = android.os.SystemClock.elapsedRealtime()
                        - renderer.lastConsumeMs();
                int draws = renderer.drawCount();
                String remote = remoteFile == null ? ""
                        : " · " + remoteFile.stats() + " · " + HttpMini.stats();
                if (attitudeTrack != null && attitudeTrack.usable()) {
                    remote += " · 稳像" + (autoLevel ? "开" : "关")
                            + "(" + attitudeTrack.size() + "帧) · " + stab.summary();
                }
                Diag.i(TAG, "画面检查 位置=" + player.positionUs() / 1000 + "ms 绘制累计="
                        + draws + " 距上次出图=" + staleMs + "ms" + remote);
                boolean glDead = (draws == lastDrawCount) || renderer.isFatal();
                if (glDead && android.os.SystemClock.elapsedRealtime() - lastRebuildMs > 4000) {
                    lastRebuildMs = android.os.SystemClock.elapsedRealtime();
                    restartGlPipeline(renderer.isFatal() ? "渲染线程已挂"
                            : "1 秒内没有绘制调用");
                } else if (staleMs > 2000
                        && android.os.SystemClock.elapsedRealtime() - lastRebuildMs > 4000) {
                    lastRebuildMs = android.os.SystemClock.elapsedRealtime();
                    Diag.e(TAG, "解码在推进但画面停更 " + staleMs + "ms，重建纹理", null);
                    renderer.requestTextureRebuild();
                }
                lastDrawCount = draws;
            }
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable inertia = new Runnable() {
        @Override
        public void run() {
            if (Math.abs(angularVelYaw) + Math.abs(angularVelPitch) < 0.05f) return;
            applyLook(angularVelYaw * 0.016f, angularVelPitch * 0.016f);
            angularVelYaw *= 0.90f;
            angularVelPitch *= 0.90f;
            ui.postDelayed(this, 16);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_player);

        msg = findViewById(R.id.msg);
        uri = getIntent().getData();
        if (uri == null) {
            msg.setText("没有收到文件");
            return;
        }

        String name = getIntent().getStringExtra(EXTRA_NAME);
        ((TextView) findViewById(R.id.title)).setText(name == null ? "OSV" : name);
        Diag.i(TAG, "打开 " + name + " : " + uri);
        if (RemoteConfigs.isRemote(uri.toString()) && name == null) {
            ((TextView) findViewById(R.id.title))
                    .setText(RemoteStore.baseName(RemoteConfigs.pathOf(uri.toString())));
        }

        createGlView();

        findViewById(R.id.back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_sensor).setOnClickListener(v -> toggleSensor());
        findViewById(R.id.btn_mode).setOnClickListener(v -> {
            state.equirect = !state.equirect;
            ((TextView) findViewById(R.id.btn_mode)).setText(state.equirect ? "透视" : "全景");
        });
        findViewById(R.id.btn_panel).setOnClickListener(v -> {
            View p = findViewById(R.id.panel);
            p.setVisibility(p.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        findViewById(R.id.btn_play).setOnClickListener(v -> togglePlay());
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            applyInitialOrientation();
            sensorRefPending = true;
        });
        findViewById(R.id.btn_level).setOnLongClickListener(v -> {
            toggleStabPanel();
            return true;
        });
        findViewById(R.id.btn_level).setOnClickListener(v -> {
            autoLevel = !autoLevel;
            stabilizer.setEnabled(autoLevel);
            if (!autoLevel) state.clearStabilizer();
            applyInitialOrientation();
            updateLevelLabel();
        });
        findViewById(R.id.btn_log).setOnClickListener(v ->
                Diag.exportAndShow(this, "播放页手动导出，文件=" + uri));
        findViewById(R.id.btn_loop).setOnClickListener(v -> {
            looping = !looping;
            updateLoopLabel();
        });

        sensor = new SensorOrientation(this, this::onDirection);
        if (!sensor.available()) {
            findViewById(R.id.btn_sensor).setAlpha(0.35f);
        }

        stab = StabSettings.load(this);
        stabilizer = new Stabilizer(state);
        stabilizer.setSettings(stab);
        stabilizer.setClock(() -> player == null ? 0 : player.positionUs());
        bindPanel();
        bindStabPanel();
        bindSeekBar();
        applySafeInsets();
        updateLevelLabel();
        updateLoopLabel();
        readInitialOrientation();
    }

    // ------------------------------------------------------------------ 初始垂直方向

    /**
     * 读 djmd：首帧姿态用来定初始垂直方向，整段序列用来做稳像。
     *
     * 两件事在同一条后台线程做——都要解封装同一个文件，分开读会互相抢 IO。
     */
    private void readInitialOrientation() {
        final Uri u = uri;
        if (u == null) return;
        new Thread(() -> {
            final float roll = OsvMeta.readInitialRoll(PlayerActivity.this, u);
            // 远程素材走 osvremote://，MediaExtractor 不认识，暂不做稳像
            final AttitudeTrack track = RemoteConfigs.isRemote(u.toString())
                    ? null : AttitudeTrack.load(PlayerActivity.this, u);
            ui.post(() -> {
                attitudeLoaded = true;
                initialRoll = roll;
                attitudeTrack = track;
                stabilizer.setTrack(track);
                updateLevelLabel();
                if (!viewTouched) state.setRoll(baseRoll());
            });
        }, "osv-attitude").start();
    }

    // ------------------------------------------------------------------ 稳像

    // 稳像的逐帧计算全在 Stabilizer 里，由渲染线程在算视图矩阵之前调用：
    // 时刻用的是「本帧刚取到的那张图」的 PTS，天然对齐。UI 线程不再参与。

    /**
     * 重置视角：回到光轴方向。
     *
     * 180° 是固定基线，不是「自动水平」的产物——摄像机把画面存反了，不转就默认颠倒。
     * 自动水平只在此基础上叠加素材自身的残余倾角。
     */
    private void applyInitialOrientation() {
        state.reset();
        state.setRoll(baseRoll());
        viewTouched = false;
        stabilizer.reset();
    }

    private float baseRoll() {
        if (autoLevel && !Float.isNaN(initialRoll)) return initialRoll;
        return ViewState.BASE_ROLL;
    }

    private void updateLoopLabel() {
        TextView loop = findViewById(R.id.btn_loop);
        if (loop != null) loop.setText(looping ? "循环播放 ✓" : "循环播放");
    }

    /** 相对水平面的倾角（roll 含 180° 基线，显示时扣掉）。 */
    private float relativeTiltDeg() {
        double deg = Math.toDegrees(initialRoll) - 180.0;
        while (deg > 180) deg -= 360;
        while (deg < -180) deg += 360;
        return (float) deg;
    }

    private void updateLevelLabel() {
        TextView level = findViewById(R.id.btn_level);
        if (level == null) return;
        if (!attitudeLoaded) {
            level.setAlpha(0.6f);
            level.setText("稳像（读取中…）");
        } else if (attitudeTrack == null && Float.isNaN(initialRoll)) {
            level.setAlpha(0.5f);
            level.setText("稳像（无姿态数据）");
        } else if (!autoLevel) {
            level.setAlpha(1f);
            level.setText("稳像");
        } else if (attitudeTrack != null && attitudeTrack.usable()) {
            level.setAlpha(1f);
            level.setText(Float.isNaN(initialRoll) ? "稳像 ✓"
                    : String.format(Locale.US, "稳像 ✓  倾斜 %+.1f°", relativeTiltDeg()));
        } else {
            // 只有首帧倾角、没有整段姿态时，退化成静态水平校正
            level.setAlpha(1f);
            level.setText(String.format(Locale.US, "自动水平 ✓  倾斜 %+.1f°", relativeTiltDeg()));
        }
    }

    /** 建/重建 GLSurfaceView + 渲染器。GL 线程一旦死掉，只能整条链换新。 */
    private void createGlView() {
        final FrameLayout stage = findViewById(R.id.stage);
        if (glView != null) {
            stage.removeView(glView);
            glView.onPause();
        }
        glView = new GLSurfaceView(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return handleTouch(event);
            }
        };
        glView.setEGLContextClientVersion(3);
        glView.setPreserveEGLContextOnPause(false);
        renderer = new StitchRenderer(state, calib, this);
        renderer.setStabilizer(stabilizer);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        stage.addView(glView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        glView.onResume();
    }

    /** 彻底换掉 GL 链：停解码 -> 换 GLSurfaceView -> 等新的 SurfaceTexture 再开解码。 */
    private void restartGlPipeline(String why) {
        Diag.e(TAG, "重建 GL 链（" + why + "）", null);
        if (player != null) {
            savedPosUs = player.positionUs();
            player.release();
            player = null;
        }
        if (audio != null) {
            audio.release();
            audio = null;
        }
        surfaces = null;
        createGlView();
        ui.postDelayed(this::startPlayback, 400);
    }

    /** 全面屏：控制条和参数面板要避开状态栏 / 手势条。 */
    private void applySafeInsets() {
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();

            View topBar = findViewById(R.id.top);
            topBar.setPadding(topBar.getPaddingLeft(), dp(6) + top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());

            View bottomBar = findViewById(R.id.bottom);
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), dp(10) + bottom);

            View hud = findViewById(R.id.hud);
            FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) hud.getLayoutParams();
            hp.topMargin = dp(62) + top;
            hud.setLayoutParams(hp);

            View panel = findViewById(R.id.panel);
            FrameLayout.LayoutParams pp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            pp.topMargin = dp(62) + top;
            panel.setLayoutParams(pp);
            return insets;
        });
        getWindow().getDecorView().requestApplyInsets();
    }

    // ------------------------------------------------------------------ 面板

    private void bindPanel() {
        SeekBar feather = findViewById(R.id.sk_feather);
        TextView lbFeather = findViewById(R.id.lb_feather);
        feather.setOnSeekBarChangeListener(simpleListener(p -> {
            state.featherDeg = p / 10f;
            lbFeather.setText(String.format(Locale.US, "%.1f°", state.featherDeg));
        }));

        SeekBar seam = findViewById(R.id.sk_seam);
        TextView lbSeam = findViewById(R.id.lb_seam);
        seam.setOnSeekBarChangeListener(simpleListener(p -> {
            state.seamDeg = (p - 200) / 10f;
            lbSeam.setText(String.format(Locale.US, "%+.1f°", state.seamDeg));
        }));

        SeekBar pole = findViewById(R.id.sk_pole);
        TextView lbPole = findViewById(R.id.lb_pole);
        pole.setOnSeekBarChangeListener(simpleListener(p -> {
            if (p == 0) {
                state.poleCut = 0f;
                lbPole.setText("关");
            } else {
                state.poleCut = (float) Math.sin(p / 100.0 * Math.PI / 2);
                lbPole.setText(Math.round(Math.toDegrees(
                        Math.asin(state.poleCut))) + "° 以上");
            }
        }));
    }

    // ------------------------------------------------------------------ 稳像调参

    /** 长按「稳像」开/关调参面板：播放不中断，改完立刻生效。 */
    private void toggleStabPanel() {
        View panel = findViewById(R.id.stab_panel);
        if (panel == null) return;
        boolean show = panel.getVisibility() != View.VISIBLE;
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) syncStabControls();
    }

    /** 参数 -> 界面（打开面板、恢复默认、切页面回来时）。 */
    private void syncStabControls() {
        SeekBar delay = findViewById(R.id.sk_stab_delay);
        if (delay != null) delay.setProgress(stab.delayMs + 200);
        SeekBar gain = findViewById(R.id.sk_stab_gain);
        if (gain != null) gain.setProgress(stab.gainPct);
        SeekBar alpha = findViewById(R.id.sk_stab_alpha);
        if (alpha != null) alpha.setProgress(stab.alphaPct);
        TextView lbDelay = findViewById(R.id.lb_stab_delay);
        if (lbDelay != null) lbDelay.setText(delayText());
        TextView lbGain = findViewById(R.id.lb_stab_gain);
        if (lbGain != null) lbGain.setText(stab.gainPct + "%");
        TextView lbAlpha = findViewById(R.id.lb_stab_alpha);
        if (lbAlpha != null) lbAlpha.setText(stab.alphaPct + "%");
        TextView src = findViewById(R.id.btn_stab_source);
        if (src != null) src.setText(stab.useDisplayPts ? "时刻源：显示帧" : "时刻源：播放器时钟");
    }

    private String delayText() {
        return String.format(Locale.US, "%+d ms", stab.delayMs);
    }

    private void bindStabPanel() {
        SeekBar delay = findViewById(R.id.sk_stab_delay);
        TextView lbDelay = findViewById(R.id.lb_stab_delay);
        if (delay != null) {
            delay.setOnSeekBarChangeListener(simpleListener(p -> {
                stab.delayMs = p - 200;   // -200..+200ms，正值 = 姿态比画面慢
                if (lbDelay != null) lbDelay.setText(delayText());
                saveStab();
            }));
        }

        SeekBar gain = findViewById(R.id.sk_stab_gain);
        TextView lbGain = findViewById(R.id.lb_stab_gain);
        if (gain != null) {
            gain.setOnSeekBarChangeListener(simpleListener(p -> {
                stab.gainPct = p;
                if (lbGain != null) lbGain.setText(p + "%");
                saveStab();
            }));
        }

        SeekBar alpha = findViewById(R.id.sk_stab_alpha);
        TextView lbAlpha = findViewById(R.id.lb_stab_alpha);
        if (alpha != null) {
            alpha.setOnSeekBarChangeListener(simpleListener(p -> {
                // 0 等于完全不跟手，没意义；最低 10%
                stab.alphaPct = Math.max(10, p);
                if (lbAlpha != null) lbAlpha.setText(stab.alphaPct + "%");
                saveStab();
            }));
        }

        TextView src = findViewById(R.id.btn_stab_source);
        if (src != null) {
            src.setOnClickListener(v -> {
                stab.useDisplayPts = !stab.useDisplayPts;
                src.setText(stab.useDisplayPts ? "时刻源：显示帧" : "时刻源：播放器时钟");
                saveStab();
            });
        }

        TextView reset = findViewById(R.id.btn_stab_reset);
        if (reset != null) {
            reset.setOnClickListener(v -> {
                stab.reset();
                syncStabControls();
                saveStab();
            });
        }

        syncStabControls();
    }

    /** 参数一变就落盘并重新对齐，免得平滑把旧修正慢慢带过去。 */
    private void saveStab() {
        stab.save(this);
        stabilizer.reset();
        Diag.i(TAG, "稳像参数 " + stab.summary());
    }

    private interface ValueListener {
        void onValue(int progress);
    }

    private SeekBar.OnSeekBarChangeListener simpleListener(ValueListener l) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) l.onValue(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        };
    }

    private void bindSeekBar() {
        SeekBar seek = findViewById(R.id.seek);
        TextView time = findViewById(R.id.time);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                long t = (long) (durationUs * (progress / (double) MAX_SEEK));
                time.setText(fmt(t) + " / " + fmt(durationUs));
                if (player != null) {
                    if (!player.isPaused()) {
                        resumeAfterSeek = true;
                        player.setPaused(true);
                        if (audio != null) audio.setPaused(true);
                    }
                    player.seekTo(t);
                }
                if (audio != null) audio.seekTo(t);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (resumeAfterSeek && player != null) {
                    player.setPaused(false);
                    if (audio != null) audio.setPaused(false);
                }
                resumeAfterSeek = false;
                updatePlayUI();
            }
        });
    }

    // ------------------------------------------------------------------ 播放控制

    private void togglePlay() {
        if (player == null) return;
        msg.setVisibility(View.GONE);
        boolean playing = !player.isPaused();
        if (!playing && player.positionUs() >= durationUs - 100_000) {
            player.seekTo(0);
            if (audio != null) audio.seekTo(0);
        }
        player.setPaused(playing);
        if (audio != null) audio.setPaused(playing);
        updatePlayUI();
    }

    private void resetProgressUi() {
        SeekBar seek = findViewById(R.id.seek);
        seek.setProgress(0);
        ((TextView) findViewById(R.id.time)).setText(fmt(0) + " / " + fmt(durationUs));
    }

    private void updatePlayUI() {
        TextView btn = findViewById(R.id.btn_play);
        boolean paused = player == null || player.isPaused();
        btn.setText(paused ? "▶" : "❚❚");
    }

    private void startPlayback() {
        if (isFinishing() || isDestroyed()) return;
        if (surfaces == null || surfaces[0] == null || uri == null) return;
        if (player != null) return;

        msg.setVisibility(View.VISIBLE);
        msg.setText("正在打开素材…");

        Diag.i(TAG, "启动解码，起点 " + savedPosUs / 1000 + "ms");

        if (remoteFile == null && RemoteConfigs.isRemote(uri.toString())) {
            msg.setVisibility(View.VISIBLE);
            msg.setText("正在连接远程存储…");
            final String uriString = uri.toString();
            new Thread(() -> {
                RemoteFile opened = null;
                String error = null;
                try {
                    RemoteConfig cfg = RemoteConfigs.find(this, RemoteConfigs.configIdOf(uriString));
                    if (cfg == null) throw new java.io.IOException("服务器配置已删除");
                    RemoteStore store = RemoteStore.open(cfg);
                    opened = new RemoteFile(store, RemoteConfigs.pathOf(uriString));
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final RemoteFile f = opened;
                final String err = error;
                if (f == null) {
                    ui.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        onError("无法读取远程文件：" + err);
                    });
                    return;
                }
                // 只等 8MB 就开播（起播快），抗抖动交给 RemoteFile 的 50MB 领先预读。
                // 进度不显示数字，屏幕上保持固定的「正在缓冲…」。
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    msg.setVisibility(View.VISIBLE);
                    msg.setText("正在缓冲…");
                });
                boolean ready = f.awaitBuffered(0, INITIAL_BUFFER_BYTES,
                        INITIAL_BUFFER_TIMEOUT_MS, null);
                Diag.i(TAG, "远程缓冲" + (ready ? "就绪 " : "超时（先开播）") + f.stats());
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        f.close();
                        return;
                    }
                    remoteFile = f;
                    startPlayback();
                });
            }, "remote-open").start();
            return;
        }

        DualVideoSource p = new DualVideoSource(this, uri, remoteFile, surfaces, this);
        player = p;
        p.start();
    }

    @Override
    public void onPrepared(DualVideoSource.Info info) {
        Diag.i(TAG, "准备完成 " + info.width + "x" + info.height
                + " 时长 " + info.durationUs / 1000 + "ms 音轨 " + info.audioTracks);
        ui.post(() -> {
            durationUs = Math.max(1, info.durationUs);
            state.vidW = info.width;
            state.vidH = info.height;
            msg.setVisibility(View.GONE);

            if (player != null) {
                if (savedPosUs > 0) player.seekTo(savedPosUs);
                player.setPaused(false);
            }
            if (info.audioTracks > 0 && audio == null) {
                audio = new AudioSource(this, uri, remoteFile);
                audio.start();
                if (savedPosUs > 0) audio.seekTo(savedPosUs);
                audio.setPaused(false);
            }
            updatePlayUI();
        });
    }

    @Override
    public void onProgress(long positionUs, long durationUs) {
        final long pos = positionUs;
        final long dur = durationUs;
        ui.post(() -> {
            if (userSeeking) return;
            SeekBar seek = findViewById(R.id.seek);
            seek.setProgress((int) Math.min(MAX_SEEK,
                    Math.round(MAX_SEEK * (pos / (double) Math.max(1, dur)))));
            ((TextView) findViewById(R.id.time)).setText(fmt(pos) + " / " + fmt(dur));
        });
    }

    @Override
    public void onEnded() {
        Diag.i(TAG, "播放结束" + (looping ? "，循环重播" : ""));
        ui.post(() -> {
            if (looping && player != null && !isFinishing() && !isDestroyed()) {
                player.seekTo(0);
                player.setPaused(false);
                if (audio != null) {
                    audio.seekTo(0);
                    audio.setPaused(false);
                }
                msg.setVisibility(View.GONE);
                // 立刻把进度条拉回 0，否则会停在末尾等下一次进度回调
                resetProgressUi();
            } else {
                msg.setVisibility(View.VISIBLE);
                msg.setText("播放结束 · 点击 ▶ 重播");
            }
            updatePlayUI();
        });
    }

    @Override
    public void onError(String message) {
        Diag.e(TAG, "播放失败: " + message, null);
        ui.post(() -> {
            msg.setVisibility(View.VISIBLE);
            msg.setText(message);
            if (!isFinishing() && !isDestroyed()) {
                new android.app.AlertDialog.Builder(this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("播放出现问题")
                        .setMessage(message + "\n\n可以导出日志发回来定位。")
                        .setPositiveButton("导出日志",
                                (d, w) -> Diag.exportAndShow(this, "播放失败: " + message))
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
    }

    // ------------------------------------------------------------------ GL 回调

    @Override
    public void onGlReady(SurfaceTexture[] s) {
        Diag.i(TAG, "GL 就绪" + (surfaces != s ? "（新 SurfaceTexture）" : ""));
        final boolean surfaceChanged = surfaces != s;
        surfaces = s;
        ui.post(() -> {
            // GL context 被重建时旧 SurfaceTexture 已失效，必须换新的重开解码器
            if (surfaceChanged && player != null) {
                savedPosUs = player.positionUs();
                player.release();
                player = null;
                if (audio != null) {
                    audio.release();
                    audio = null;
                }
            }
            startPlayback();
        });
    }

    @Override
    public void onGlError(String message) {
        Diag.e(TAG, message, null);
        if (renderer != null && renderer.isFatal()) {
            ui.post(() -> restartGlPipeline("渲染异常: " + message));
        }
        ui.post(() -> {
            msg.setVisibility(View.VISIBLE);
            msg.setText(message);
        });
    }

    @Override
    public void onFrame(long ptsUs, float fps) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastHudMs < 400) return;
        lastHudMs = now;
        final String text = String.format(Locale.US,
                "%.0f fps · %d×%d\n视角 %.0f° · 接缝 %+.1f°\n%s",
                fps, state.vidW, state.vidH, state.fov, state.seamDeg,
                sensorMode ? "体感 + 触摸" : "触摸");
        ui.post(() -> ((TextView) findViewById(R.id.hud)).setText(text));
    }

    // ------------------------------------------------------------------ 触摸

    private boolean handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                ui.removeCallbacks(inertia);
                angularVelYaw = angularVelPitch = 0;
                lastX = lastMoveX = downX = e.getX();
                lastY = lastMoveY = downY = e.getY();
                lastMoveTime = downTime = android.os.SystemClock.elapsedRealtime();
                dragging = false;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (e.getPointerCount() == 2) {
                    pinchStart = spacing(e);
                    pinchFov = state.fov;
                    lastPinchAngle = pinchAngle(e);
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // 双指松开一根后，用剩下的手指继续拖动，避免视角跳一下
                int rest = e.getActionIndex() == 0 ? 1 : 0;
                lastX = lastMoveX = e.getX(rest);
                lastY = lastMoveY = e.getY(rest);
                pinchStart = 0;
                lastPinchAngle = Float.NaN;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (e.getPointerCount() >= 2) {
                    // 双指同时做缩放和旋转：距离变视场角，连线转角滚转画面
                    float d = spacing(e);
                    if (pinchStart > 1f && d > 1f) {
                        state.fov = Math.max(ViewState.FOV_MIN,
                                Math.min(ViewState.FOV_MAX, pinchFov * pinchStart / d));
                    }
                    float angle = pinchAngle(e);
                    if (!Float.isNaN(lastPinchAngle)) {
                        float delta = wrapPi(angle - lastPinchAngle);
                        // 手指在屏幕上顺时针转，画面也顺时针转（Δ 已按 y 轴向下定义）
                        if (Math.abs(delta) > 0.0005f) rotateView(0f, 0f, 1f, -delta);
                    }
                    lastPinchAngle = angle;
                    dragging = true;
                    viewTouched = true;
                    break;
                }
                float dx = e.getX() - lastX;
                float dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                if (Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY) > dp(4)) {
                    dragging = true;
                    viewTouched = true;
                }

                float k = 1.6f * (state.fov / 90f) / Math.max(glView.getHeight(), 1);
                applyLook(dx * k, dy * k);

                long now = android.os.SystemClock.elapsedRealtime();
                float dt = Math.max(8, now - lastMoveTime) / 1000f;
                angularVelYaw = (e.getX() - lastMoveX) * k / dt;      // rad/s
                angularVelPitch = (e.getY() - lastMoveY) * k / dt;
                lastMoveX = e.getX();
                lastMoveY = e.getY();
                lastMoveTime = now;
                break;
            }
            case MotionEvent.ACTION_UP: {
                long dur = android.os.SystemClock.elapsedRealtime() - downTime;
                if (!dragging && dur < 260) {
                    toggleControls();
                } else {
                    ui.post(inertia);
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                angularVelYaw = angularVelPitch = 0;
                break;
            default:
                break;
        }
        return true;
    }

    /** 两指连线的角度，屏幕坐标（y 向下）下顺时针为正。 */
    private float pinchAngle(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        return (float) Math.atan2(e.getY(0) - e.getY(1), e.getX(0) - e.getX(1));
    }

    private static float wrapPi(float a) {
        float twoPi = (float) (Math.PI * 2);
        float r = a % twoPi;
        if (r > Math.PI) r -= twoPi;
        if (r < -Math.PI) r += twoPi;
        return r;
    }

    private float spacing(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 按屏幕轴做局部旋转：水平拖动绕「屏幕竖直轴」，垂直拖动绕「屏幕水平轴」。
     * 这样任意朝向下角速度都与手指位移成正比（天顶不再加速），也没有俯仰上限，
     * 可以翻过天顶继续转。
     *
     * @param dYawScreen   屏幕竖直轴的角，正 = 手指向右
     * @param dPitchScreen 屏幕水平轴的角，正 = 手指向下
     */
    private void applyLook(float dYawScreen, float dPitchScreen) {
        if (dYawScreen != 0f) rotateView(0f, -1f, 0f, dYawScreen);
        if (dPitchScreen != 0f) rotateView(1f, 0f, 0f, dPitchScreen);
    }

    private void rotateView(float ax, float ay, float az, float angle) {
        if (sensorMode) {
            // 体感模式：先把局部旋转作用在当前视图上，再反算回「相对传感器姿态的偏移」
            state.getQuat(qViewTmp);
            Quat.fromAxisAngle(qTmp, ax, ay, az, angle);
            Quat.mul(qTmp2, qViewTmp, qTmp);
            state.setQuat(qTmp2);
            Quat.conj(qTmp, qSensorLast);
            Quat.mul(qOffset, qTmp2, qTmp);
        } else {
            state.rotateLocal(ax, ay, az, angle);
        }
    }

    private void toggleControls() {
        controlsHidden = !controlsHidden;
        int vis = controlsHidden ? View.GONE : View.VISIBLE;
        findViewById(R.id.top).setVisibility(vis);
        findViewById(R.id.bottom).setVisibility(vis);
        findViewById(R.id.hud).setVisibility(vis);
        if (controlsHidden) findViewById(R.id.panel).setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ 体感

    private void toggleSensor() {
        if (!sensor.available()) {
            Toast.makeText(this, "这台设备没有姿态传感器", Toast.LENGTH_SHORT).show();
            return;
        }
        sensorMode = !sensorMode;
        TextView btn = findViewById(R.id.btn_sensor);
        btn.setText(sensorMode ? "体感 ✓" : "体感");
        btn.setBackgroundResource(sensorMode ? R.drawable.pill_on : R.drawable.pill);
        if (sensorMode) {
            Quat.identity(qOffset);
            Quat.identity(qSensorLast);
            sensorRefPending = true;
            sensor.start();
        } else {
            sensor.stop();
        }
    }

    private void onDirection(float yawRad, float pitchRad) {
        if (!sensorMode) return;
        if (sensorRefPending) {
            // 以开启体感那一刻的视角为基准：画面不跳，之后设备转动直接带着视角走
            sensorRefPending = false;
            float[] sensorRef = new float[4];
            Quat.fromYawPitch(sensorRef, yawRad, pitchRad);

            // 基准必须取「完整视角」：180° 基线和素材倾角都是绕视线轴的滚转，
            // 若用 yaw/pitch 重新组装就会把它们丢掉，画面立刻颠倒。
            state.getQuat(qViewTmp);
            Quat.conj(qTmp2, sensorRef);
            Quat.mul(qOffset, qViewTmp, qTmp2);
            Quat.copy(qSensorLast, sensorRef);
            return;
        }
        Quat.fromYawPitch(qSensorLast, yawRad, pitchRad);
        Quat.mul(qTmp, qOffset, qSensorLast);
        state.setQuat(qTmp);
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        Diag.i(TAG, "onResume");
        if (glView == null) return;   // uri 缺失时界面没建起来
        glView.onResume();
        if (sensorMode && sensor != null) sensor.start();
        ui.postDelayed(this::startPlayback, 300);
        ui.removeCallbacks(renderWatchdog);
        ui.postDelayed(renderWatchdog, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Diag.i(TAG, "onPause，位置 " + savedPosUs / 1000 + "ms");
        // 先停解码（GL 还在跑，避免 releaseOutputBuffer 卡在满队列上），再停 GL
        if (player != null) {
            savedPosUs = player.positionUs();
            player.release();
            player = null;
        }
        if (audio != null) {
            audio.release();
            audio = null;
        }
        ui.removeCallbacks(inertia);
        ui.removeCallbacks(renderWatchdog);
        if (remoteFile != null) {
            remoteFile.close();
            remoteFile = null;
        }
        if (sensor != null) sensor.stop();
        if (glView != null) glView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        if (audio != null) {
            audio.release();
            audio = null;
        }
        if (remoteFile != null) {
            remoteFile.close();
            remoteFile = null;
        }
    }

    private static String fmt(long us) {
        long s = Math.max(0, us) / 1_000_000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
