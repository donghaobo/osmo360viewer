package com.osmo.viewer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;

/** OSV 的音轨（AAC-LC 48k 立体声）：独立解码 + AudioTrack 播放。 */
public class AudioSource {

    private static final String TAG = "AudioSource";

    private final Context ctx;
    private final Uri uri;
    private final RemoteFile remote;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused = true;
    private volatile long seekRequestUs = -1;

    private MediaExtractor ex;
    private MediaCodec codec;
    private volatile AudioTrack track;

    public AudioSource(Context ctx, Uri uri, RemoteFile remote) {
        this.ctx = ctx.getApplicationContext();
        this.uri = uri;
        this.remote = remote;
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "osv-audio");
        thread.start();
    }

    public void setPaused(boolean p) {
        paused = p;
        if (track != null) {
            try {
                if (p) track.pause();
                else track.play();
            } catch (Exception ignored) {
            }
        }
    }

    public void seekTo(long us) {
        seekRequestUs = Math.max(0, us);
    }

    public void release() {
        running = false;
        if (thread != null) {
            try { thread.join(1200); } catch (InterruptedException ignored) {}
            thread = null;
        }
        close();
    }

    private void run() {
        try {
            ex = new MediaExtractor();
            if (remote != null) ex.setDataSource(remote.dataSource());
            else ex.setDataSource(ctx, uri, null);
            int audioTrack = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }
            if (audioTrack < 0) return;

            MediaFormat fmt = ex.getTrackFormat(audioTrack);
            ex.selectTrack(audioTrack);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int channelMask = channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;

            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf * 4, 65536))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                close();
                return;
            }
            if (!paused) track.play();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcm = new byte[65536];
            boolean inputDone = false;

            while (running) {
                if (seekRequestUs >= 0) {
                    long target = seekRequestUs;
                    seekRequestUs = -1;
                    codec.flush();
                    ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    inputDone = false;
                    try {
                        track.pause();
                        track.flush();
                        if (!paused) track.play();
                    } catch (Exception ignored) {}
                }
                if (paused) {
                    sleepQuiet(10);
                    continue;
                }

                // 输入
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(2000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }

                // 输出
                for (int guard = 0; guard < 4; guard++) {
                    int out = codec.dequeueOutputBuffer(info, 0);
                    if (out < 0) break;
                    if (info.size > 0) {
                        if (pcm.length < info.size) pcm = new byte[info.size];
                        ByteBuffer b = codec.getOutputBuffer(out);
                        if (b != null) {
                            b.position(info.offset);
                            b.limit(info.offset + info.size);
                            b.get(pcm, 0, info.size);
                            track.write(pcm, 0, info.size);
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        inputDone = true;
                    }
                }
            }
        } catch (Throwable t) {
            Diag.w(TAG, "音频解码/播放失败（不影响画面）", t);
        } finally {
            close();
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void close() {
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            track = null;
        }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) {}
            try { codec.release(); } catch (Exception ignored) {}
            codec = null;
        }
        if (ex != null) {
            ex.release();
            ex = null;
        }
    }
}
