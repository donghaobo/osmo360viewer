package com.osmo.viewer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Range;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * 双路解码源：每颗镜头一条独立解码线程，共享同一个播放时钟。
 *
 * 之所以每路一个线程：某一路解码器卡住时只会拖住它自己，
 * 另一路和进度条（控制线程）都不受影响。
 * 控制线程负责时钟、进度上报、每秒状态日志和两路时间戳对齐检查。
 */
public class DualVideoSource {

    private static final String TAG = "DualVideoSource";
    /** 输入最多提前多少微秒喂给解码器。 */
    private static final long LOOKAHEAD_US = 100_000;
    private static final long SEEK_TIMEOUT_MS = 3000;
    /** 某一路超过这个时间没有输出，就认为它卡住了。 */
    private static final long STALL_MS = 1500;
    /** 单路恢复的最小间隔，避免反复 flush。 */
    private static final long RECOVER_COOLDOWN_MS = 1200;
    /** 两路 PTS 相差超过这个值就报「不同步」。 */
    private static final long ALIGN_WARN_US = 200_000;
    /** 相差超过这个值就主动把落后的一路对齐回来。 */
    private static final long ALIGN_FIX_US = 500_000;

    public interface Listener {
        void onPrepared(Info info);
        void onProgress(long positionUs, long durationUs);
        void onEnded();
        void onError(String message);
    }

    public static class Info {
        public int width;
        public int height;
        public long durationUs;
        public int audioTracks;
        public int videoTracks;
    }

    private final class Stream implements Runnable {
        final int index;
        MediaExtractor ex;
        MediaCodec codec;
        Surface surface;
        int track;
        volatile boolean eos;
        volatile boolean failed;
        volatile long lastPtsUs;
        /** 下一条待解码样本的 PTS（-1 = 已到末尾）。 */
        long nextPtsUs = -1;
        /** 文件首帧 pts，用于把码流时间轴对齐到 0。 */
        long basePtsUs;
        volatile long lastOutputWallMs;
        long lastRecoverWallMs;
        int eosRecoveries;
        int appliedSeekSeq;
        boolean previewPending;
        volatile boolean resyncRequested;
        volatile long resyncTargetUs;
        volatile int framesSinceTick;
        volatile boolean threadDone;

        Stream(int index) { this.index = index; }

        @Override
        public void run() {
            try {
                streamLoop(this);
            } catch (Throwable t) {
                failed = true;
                Diag.e(TAG, "流 " + index + " 线程异常退出", t);
            } finally {
                // 谁用谁释放：别的线程（含重建路径）不许碰这个 codec
                releaseSelf();
                threadDone = true;
                Diag.i(TAG, "流 " + index + " 线程已退出");
            }
        }

        synchronized void releaseSelf() {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
                codec = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            if (ex != null) {
                ex.release();
                ex = null;
            }
        }

        synchronized boolean isAliveCodec() {
            return codec != null;
        }
    }

    private final Context ctx;
    private final Uri uri;
    /** 远程播放时为非空，所有解封装都走它（本地为 null）。 */
    private final RemoteFile remote;
    private final SurfaceTexture[] textures;
    private final Listener listener;
    private final Stream[] streams = {new Stream(0), new Stream(1)};

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private Thread[] decodeThreads = new Thread[2];

    /** 让 MediaExtractor 指向本地 URI 或远程 MediaDataSource。 */
    private void bind(MediaExtractor ex) throws java.io.IOException {
        if (remote != null) {
            MediaDataSource ds = remote.dataSource();
            ex.setDataSource(ds);
        } else {
            ex.setDataSource(ctx, uri, null);
        }
    }
    private Thread controlThread;
    private Thread statusThread;

    private volatile boolean running;
    private volatile boolean released;
    private volatile boolean paused = true;

    /** 播放时钟：时间位置 = clockUs()。 */
    private volatile long wallBaseUs;
    private volatile long positionUs;
    private volatile long lastReportUs;
    private volatile boolean endedReported;

    /** seek 请求：任一线程都能提，但由各自的解码线程执行。 */
    private final Object seekLock = new Object();
    private long seekTargetUs = -1;
    private int seekSeq;

    private volatile long durationUs;
    private volatile int width;
    private volatile int height;

    public DualVideoSource(Context ctx, Uri uri, RemoteFile remote,
                           SurfaceTexture[] textures, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        Diag.init(this.ctx);
        this.uri = uri;
        this.remote = remote;
        this.textures = textures;
        this.listener = listener;
    }

    public void start() {
        controlThread = new Thread(this::controlLoop, "osv-ctrl");
        controlThread.start();
    }

    public void setPaused(boolean p) {
        if (p == paused) return;
        if (p) {
            positionUs = clockUs();
            paused = true;
        } else {
            wallBaseUs = SystemClock.elapsedRealtimeNanos() / 1000 - positionUs;
            paused = false;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void seekTo(long us) {
        long target = Math.max(0, us);
        synchronized (seekLock) {
            seekTargetUs = target;
            seekSeq++;
        }
        endedReported = false;
        // 播放时钟回到前面了，进度节流的基准必须一起清掉；
        // 否则 now - lastReportUs 恒为负，onProgress 再也不会上报（进度条停在 0）。
        lastReportUs = 0;
        // 两路的 eos 由各自线程稍后清，这里先清掉，避免控制线程立刻又判定「播放结束」
        for (Stream s : streams) {
            s.eos = false;
            s.failed = false;
        }
        positionUs = target;
        wallBaseUs = SystemClock.elapsedRealtimeNanos() / 1000 - target;
        Diag.i(TAG, "seek -> " + target / 1000 + "ms");
    }

    public long positionUs() {
        return paused ? positionUs : clockUs();
    }

    public long durationUs() {
        return durationUs;
    }

    public void release() {
        released = true;
        running = false;
        paused = true;
        for (Thread t : decodeThreads) interruptQuiet(t);
        interruptQuiet(statusThread);
        joinQuiet(controlThread, 4000);
        joinQuiet(statusThread, 800);
        for (Thread t : decodeThreads) joinQuiet(t, 800);
        controlThread = null;
        statusThread = null;
        decodeThreads = new Thread[2];
    }

    private long clockUs() {
        return SystemClock.elapsedRealtimeNanos() / 1000 - wallBaseUs;
    }

    // ------------------------------------------------------------------ 控制线程

    private void controlLoop() {
        try {
            if (!open() || released) return;
            Info info = new Info();
            info.width = width;
            info.height = height;
            info.durationUs = durationUs;
            info.audioTracks = countAudioTracks();
            info.videoTracks = 2;
            listener.onPrepared(info);

            if (released) return;
            running = true;
            wallBaseUs = SystemClock.elapsedRealtimeNanos() / 1000;
            positionUs = 0;

            for (int i = 0; i < 2; i++) {
                decodeThreads[i] = new Thread(streams[i], "osv-dec" + i);
                decodeThreads[i].start();
            }
            statusThread = new Thread(this::statusLoop, "osv-status");
            statusThread.start();

            while (running) {
                if (!paused) {
                    long now = clockUs();
                    if (now < lastReportUs || now - lastReportUs > 200_000) {
                        lastReportUs = now;
                        listener.onProgress(now, durationUs);
                    }
                    if (!endedReported && allDone()) {
                        endedReported = true;
                        positionUs = durationUs;
                        paused = true;
                        Diag.i(TAG, "两路都结束（eos0=" + streams[0].eos + " failed0="
                                + streams[0].failed + " eos1=" + streams[1].eos
                                + " failed1=" + streams[1].failed + "）");
                        listener.onProgress(durationUs, durationUs);
                        listener.onEnded();
                    }
                    if (streams[0].failed && streams[1].failed) {
                        Diag.e(TAG, "两路解码都中断", null);
                        listener.onError("两路解码都中断了");
                        return;
                    }
                }
                sleepQuiet(20);
            }
        } catch (Throwable t) {
            Diag.e(TAG, "控制线程异常", t);
            listener.onError(describe(t));
        } finally {
            running = false;
            paused = true;
            close();
        }
    }

    private boolean allDone() {
        return (streams[0].eos || streams[0].failed)
                && (streams[1].eos || streams[1].failed);
    }

    /** 每秒一条：两路各出多少帧、播到哪、时间戳是否对齐。 */
    private void statusLoop() {
        while (running) {
            sleepQuiet(1000);
            if (!running) break;
            Stream a = streams[0], b = streams[1];
            int fa = a.framesSinceTick, fb = b.framesSinceTick;
            a.framesSinceTick = 0;
            b.framesSinceTick = 0;
            long now = clockUs();
            long driftUs = a.lastPtsUs - b.lastPtsUs;
            long wall = SystemClock.elapsedRealtime();

            Diag.i(TAG, String.format(Locale.US,
                    "每秒 位置=%dms | 流0 pts=%dms 帧=%d eos=%b failed=%b 停%dms"
                            + " | 流1 pts=%dms 帧=%d eos=%b failed=%b 停%dms"
                            + " | 时间戳差=%+dms%s",
                    now / 1000, a.lastPtsUs / 1000, fa, a.eos, a.failed,
                    wall - a.lastOutputWallMs,
                    b.lastPtsUs / 1000, fb, b.eos, b.failed,
                    wall - b.lastOutputWallMs,
                    driftUs / 1000,
                    Math.abs(driftUs) > ALIGN_WARN_US ? "  <== 两路不同步" : ""));

            // 明显错位时，把落后的一路重新对齐到另一路的 PTS
            if (!paused && Math.abs(driftUs) > ALIGN_FIX_US
                    && !a.eos && !b.eos) {
                Stream lag = driftUs > 0 ? b : a;
                Stream lead = driftUs > 0 ? a : b;
                lag.resyncTargetUs = Math.max(0, lead.lastPtsUs - lag.basePtsUs);
                lag.resyncRequested = true;
                Diag.w(TAG, "流 " + lag.index + " 落后 " + Math.abs(driftUs) / 1000
                        + "ms，重新对齐到 " + lag.resyncTargetUs / 1000 + "ms");
            }
        }
    }

    // ------------------------------------------------------------------ 解码线程

    private void streamLoop(Stream s) {
        while (running) {
            applySeekIfNeeded(s);
            if (s.resyncRequested) {
                long target = s.resyncTargetUs;
                s.resyncRequested = false;
                doStreamSeek(s, target, "对齐");
            }

            if (paused) {
                if (s.previewPending) {
                    s.previewPending = false;
                    renderPreviewFrame(s);
                }
                sleepQuiet(8);
                continue;
            }
            s.previewPending = false;

            long now = clockUs();
            if (s.codec == null) {   // 正在被关闭
                sleepQuiet(8);
                continue;
            }
            try {
                feed(s, now + LOOKAHEAD_US);
                drain(s);
            } catch (Throwable t) {
                Diag.w(TAG, "流 " + s.index + " 解码异常，尝试恢复", t);
                recover(s, now);
                continue;
            }
            healthCheck(s, now);
            sleepQuiet(2);
        }
    }

    private void applySeekIfNeeded(Stream s) {
        if (s.codec == null) return;
        long target = -1;
        synchronized (seekLock) {
            if (seekSeq != s.appliedSeekSeq) {
                s.appliedSeekSeq = seekSeq;
                target = seekTargetUs;
            }
        }
        if (target < 0) return;
        doStreamSeek(s, target, "seek");
        if (paused) s.previewPending = true;
    }

    private void doStreamSeek(Stream s, long targetUs, String why) {
        if (s.codec == null) return;
        try {
            s.codec.flush();
        } catch (Exception ignored) {
        }
        s.eos = false;
        s.failed = false;
        s.eosRecoveries = 0;
        s.lastOutputWallMs = SystemClock.elapsedRealtime();
        s.lastRecoverWallMs = 0;
        try {
            s.ex.seekTo(Math.max(0, targetUs) + s.basePtsUs,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            s.nextPtsUs = s.ex.getSampleTime();
        } catch (Throwable t) {
            s.failed = true;
            Diag.e(TAG, "流 " + s.index + " " + why + " 失败", t);
            return;
        }
        Diag.i(TAG, "流 " + s.index + " " + why + " 到 " + targetUs / 1000 + "ms");
    }

    // ------------------------------------------------------------------ 喂/取

    private void feed(Stream s, long untilUs) {
        if (s.eos) return;
        long pts = s.ex.getSampleTime();
        s.nextPtsUs = pts;
        if (pts < 0) {
            queueEos(s);
            return;
        }
        if (pts - s.basePtsUs > untilUs) return;

        int in = s.codec.dequeueInputBuffer(0);
        if (in < 0) return;
        ByteBuffer buf = s.codec.getInputBuffer(in);
        if (buf == null) return;
        int size = s.ex.readSampleData(buf, 0);
        if (size < 0) {
            queueEos(s);
            return;
        }
        s.codec.queueInputBuffer(in, 0, size, pts, 0);
        s.lastPtsUs = pts;
        s.ex.advance();
    }

    private void queueEos(Stream s) {
        if (s.eos) return;
        int in = s.codec.dequeueInputBuffer(0);
        if (in < 0) return;
        s.codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        s.eos = true;
    }

    private void drain(Stream s) {
        for (int guard = 0; guard < 8; guard++) {
            int out = s.codec.dequeueOutputBuffer(bufferInfo, 0);
            if (out >= 0) {
                s.lastPtsUs = bufferInfo.presentationTimeUs;
                s.lastOutputWallMs = SystemClock.elapsedRealtime();
                s.framesSinceTick++;
                s.codec.releaseOutputBuffer(out, true);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) s.eos = true;
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 尺寸/格式变化：SurfaceTexture 会自行跟随
            } else {
                break;
            }
        }
    }

    // ------------------------------------------------------------------ 自愈

    private void recover(Stream s, long nowUs) {
        if (s.codec == null) return;
        long wall = SystemClock.elapsedRealtime();
        if (wall - s.lastRecoverWallMs < RECOVER_COOLDOWN_MS) return;
        s.lastRecoverWallMs = wall;
        s.lastOutputWallMs = wall;
        try {
            s.codec.flush();
            s.ex.seekTo(Math.max(0, nowUs) + s.basePtsUs,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            s.eos = false;
            s.nextPtsUs = s.ex.getSampleTime();
            Diag.w(TAG, "流 " + s.index + " 已恢复到 " + nowUs / 1000 + "ms");
        } catch (Throwable t) {
            s.failed = true;
            Diag.e(TAG, "流 " + s.index + " 无法恢复", t);
        }
    }

    /** 单路看门狗：卡死、跑到未来、或提前 EOS 时把它拉回当前播放位置。 */
    private void healthCheck(Stream s, long nowUs) {
        long wall = SystemClock.elapsedRealtime();
        if (s.eos) {
            if (s.eosRecoveries < 2 && nowUs < durationUs - 250_000) {
                s.eosRecoveries++;
                Diag.w(TAG, "流 " + s.index + " 提前 EOS，尝试续播");
                recover(s, nowUs);
            }
            return;
        }
        boolean stalled = wall - s.lastOutputWallMs > STALL_MS;
        boolean farAhead = s.nextPtsUs >= 0 && s.nextPtsUs - s.basePtsUs > nowUs + 3_000_000L;
        if (stalled || farAhead) {
            Diag.w(TAG, "流 " + s.index + " 疑似卡住(stalled=" + stalled
                    + ", ahead=" + farAhead + ")，尝试恢复");
            recover(s, nowUs);
        }
    }

    /** 暂停中 seek：解出至少一帧交给 GL，保证拖动进度条时画面跟着走。 */
    private void renderPreviewFrame(Stream s) {
        long deadline = SystemClock.elapsedRealtime() + SEEK_TIMEOUT_MS;
        boolean got = false;
        for (int i = 0; i < 400 && !got && SystemClock.elapsedRealtime() < deadline; i++) {
            feedBypass(s);
            got = pollOneFrame(s);
            if (!got) sleepQuiet(3);
        }
        if (!got) Diag.w(TAG, "流 " + s.index + " seek 预览取不到帧");
    }

    private void feedBypass(Stream s) {
        if (s.eos) return;
        int in = s.codec.dequeueInputBuffer(0);
        if (in < 0) return;
        ByteBuffer buf = s.codec.getInputBuffer(in);
        if (buf == null) return;
        long pts = s.ex.getSampleTime();
        if (pts < 0) {
            queueEos(s);
            return;
        }
        int size = s.ex.readSampleData(buf, 0);
        if (size < 0) {
            queueEos(s);
            return;
        }
        s.codec.queueInputBuffer(in, 0, size, pts, 0);
        s.lastPtsUs = pts;
        s.ex.advance();
    }

    private boolean pollOneFrame(Stream s) {
        int out = s.codec.dequeueOutputBuffer(bufferInfo, 0);
        if (out >= 0) {
            s.lastPtsUs = bufferInfo.presentationTimeUs;
            s.lastOutputWallMs = SystemClock.elapsedRealtime();
            s.framesSinceTick++;
            s.codec.releaseOutputBuffer(out, true);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 打开/关闭

    private boolean open() {
        try {
            MediaExtractor probe = new MediaExtractor();
            bind(probe);
            Diag.i(TAG, "解封装 " + (remote != null ? "远程 " + remote.path() : uri.toString())
                    + "，轨数=" + probe.getTrackCount());

            int[] tracks = new int[2];
            int found = 0;
            for (int i = 0; i < probe.getTrackCount(); i++) {
                MediaFormat f = probe.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && !mime.contains("mjpeg")) {
                    if (found < 2) tracks[found] = i;
                    found++;
                    if (found == 1) {
                        width = f.getInteger(MediaFormat.KEY_WIDTH);
                        height = f.getInteger(MediaFormat.KEY_HEIGHT);
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            durationUs = f.getLong(MediaFormat.KEY_DURATION);
                        }
                    }
                }
            }
            probe.release();

            if (found == 0) {
                Diag.e(TAG, "没有可解码的视频轨", null);
                listener.onError("这个文件里没有可解码的视频轨");
                return false;
            }
            if (found < 2) {
                Diag.e(TAG, "只找到 " + found + " 条视频轨", null);
                listener.onError("只找到 1 条视频轨，不是双鱼眼素材");
                return false;
            }
            durationUs = Math.max(durationUs, 1);
            width = Math.max(1, width);
            height = Math.max(1, height);

            for (int i = 0; i < 2; i++) {
                Stream s = streams[i];
                s.track = tracks[i];
                s.ex = new MediaExtractor();
                bind(s.ex);
                MediaFormat fmt = s.ex.getTrackFormat(s.track);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                textures[i].setDefaultBufferSize(width, height);
                s.surface = new Surface(textures[i]);
                s.codec = MediaCodec.createDecoderByType(mime);
                String limit = capabilityProblem(s.codec, mime, width, height);
                Diag.i(TAG, "流" + i + " 轨" + tracks[i] + " " + mime + " "
                        + width + "x" + height + " 解码器=" + s.codec.getName()
                        + " 对齐=" + alignmentText(s.codec, mime)
                        + "（" + (limit == null ? "能力检查通过" : "不支持") + "）");
                if (limit != null) {
                    Diag.e(TAG, limit, null);
                    listener.onError(limit);
                    closeStreams();   // 已经建好的解码器要还回去，否则一路泄漏
                    return false;
                }
                try {
                    s.codec.configure(fmt, s.surface, null, 0);
                    s.codec.start();
                } catch (Throwable t) {
                    // 预检已经放行了，这里失败就是解码器真的吃不下：把名字和尺寸写进日志
                    String why = "解码器 " + s.codec.getName() + " 无法配置 "
                            + width + "×" + height + "（" + mime + "，对齐 "
                            + alignmentText(s.codec, mime) + "）：" + describe(t);
                    Diag.e(TAG, why, t);
                    listener.onError(why);
                    closeStreams();
                    return false;
                }
                s.ex.selectTrack(s.track);
                long firstPts = s.ex.getSampleTime();
                s.basePtsUs = firstPts < 0 ? 0 : firstPts;
                s.nextPtsUs = firstPts;
                s.lastOutputWallMs = SystemClock.elapsedRealtime();
                s.lastRecoverWallMs = 0;
                Diag.i(TAG, "流" + i + " 首帧 pts=" + s.basePtsUs / 1000 + "ms");
            }
            return true;
        } catch (Throwable t) {
            Diag.e(TAG, "打开/配置解码器失败", t);
            listener.onError(describe(t));
            closeStreams();
            return false;
        }
    }

    /** 失败路径用：把已经打开的解码器/Surface/解封装器都还回去。 */
    private void closeStreams() {
        for (Stream s : streams) {
            if (s != null) s.releaseSelf();
        }
    }

    /**
     * 只拦「明显超出范围」的尺寸。
     *
     * 不用 VideoCapabilities.isSizeSupported()：它除了查范围，还要求宽高满足
     * alignment。不少 QTI 解码器上报的对齐比它实际能吃下的更严，OSV 固定的
     * 3000×3000 就会被误判——实测骁龙 7+ Gen 3（c2.qti.hevc.decoder）报的
     * 范围是 96~4096 × 96~4096，3000 正好在中间却返回 false。
     *
     * 真正的判据交给 configure：解不了会抛异常，那时再报错也不迟。
     */
    private String capabilityProblem(MediaCodec codec, String mime, int w, int h) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo()
                    .getCapabilitiesForType(mime);
            MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
            if (video == null) return null;
            Range<Integer> widths = video.getSupportedWidths();
            Range<Integer> heights = video.getSupportedHeights();
            if (widths.contains(w) && heights.contains(h)) return null;
            return "这台设备的 HEVC 解码器不支持 " + w + "×" + h
                    + "（支持范围 " + widths.getLower() + "~" + widths.getUpper()
                    + " × " + heights.getLower() + "~" + heights.getUpper()
                    + "），无法播放这个素材";
        } catch (Throwable ignored) {
            return null;  // 查不出来就交给 configure 报错
        }
    }

    /** 诊断用：解码器要求的宽高对齐。真解不了时靠它判断是不是对齐不符。 */
    private String alignmentText(MediaCodec codec, String mime) {
        try {
            MediaCodecInfo.VideoCapabilities video = codec.getCodecInfo()
                    .getCapabilitiesForType(mime).getVideoCapabilities();
            if (video == null) return "?";
            return video.getWidthAlignment() + "×" + video.getHeightAlignment();
        } catch (Throwable t) {
            return "?";
        }
    }

    private String describe(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (t instanceof MediaCodec.CodecException) return "解码器错误：" + msg;
        if (msg.contains("configure") || msg.contains("codec")) {
            return "设备无法解码这个码流（可能是 3000×3000 HEVC 10bit 超出解码器能力）：" + msg;
        }
        return "播放失败：" + msg;
    }

    private int countAudioTracks() {
        MediaExtractor ex = new MediaExtractor();
        try {
            bind(ex);
            int n = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * 关闭：只等解码线程退出，资源由线程自己的 finally 释放。
     * 线程被卡死在 native 时宁可泄漏，也不要在别的线程里 release（会抛
     * "Invalid to call at Released state" 并把后续恢复路径一起带崩）。
     */
    private void close() {
        running = false;
        for (int i = 0; i < streams.length; i++) {
            Thread t = decodeThreads.length > i ? decodeThreads[i] : null;
            if (t != null && t.isAlive()) {
                t.interrupt();
                joinQuiet(t, 1500);
            }
            if (t != null && t.isAlive()) {
                Diag.w(TAG, "流 " + i + " 线程未退出，放弃释放其资源");
            } else {
                streams[i].releaseSelf();
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void interruptQuiet(Thread t) {
        if (t != null) t.interrupt();
    }

    private static void joinQuiet(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
