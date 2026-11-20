package com.osmo.viewer;

import android.media.MediaDataSource;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 远程文件 + 块缓存 + 后台预读。
 *
 * 播放一个 OSV 时，两条解码线程会分别在文件的两个区段上反复 seek 读，
 * 网络一旦跟不上就立刻卡帧。这里的做法：
 *   1. 按 256KB 分块，LRU 缓存（默认 48MB），不落地整个文件；
 *   2. 一个后台预读线程，跟着「最近在读的位置」多下几 MB，让解码线程
 *      拿到的绝大多数是内存命中；
 *   3. 开播前可用 awaitBuffered() 先攒够一段，避免开头就卡。
 */
public final class RemoteFile implements Closeable {

    /**
     * 单块大小。v0.5.3 从 256KB 提到 1MB：网络请求数直接少 4 倍，
     * 每块的协议开销（即便是 keep-alive 也有一次请求/响应往返）摊薄了。
     */
    private static final int BLOCK = 1024 * 1024;
    /**
     * 每个活跃读位置往前预读多少字节 = 50MB，约 5 秒双路码流（10MB/s）。
     * 开播只等 8MB，这 50MB 是在播放过程中持续保持的领先量：网络抖一下、
     * 短时掉速，解码器读到的仍是内存里的块，不会卡。
     */
    private static final long PREFETCH_AHEAD = 50L * 1024 * 1024;
    /** 期望的块缓存上限：128MB。实际值按 Java 堆大小往下调，见 maxCache。 */
    private static final long CACHE_WANTED = 128L * 1024 * 1024;
    /** 同时跟踪的读位置数量（双路解码 = 2），也是预读线程数。 */
    private static final int MAX_STREAMS = 2;

    public interface BufferListener {
        void onBuffer(long cachedBytes, long wantedBytes);
    }

    private final RemoteStore store;
    private final String path;
    private final long size;

    /** accessOrder = true：读到的块移到队尾，淘汰最久没用的。 */
    private final LinkedHashMap<Long, byte[]> blocks = new LinkedHashMap<>(64, 0.75f, true);
    private long cacheBytes;
    private volatile boolean closed;

    /** 活跃读位置（块序号 -> 最近访问时刻），预读线程据此往前拉。 */
    private final LinkedHashMap<Long, Long> demand = new LinkedHashMap<>(8, 0.75f, true);

    /**
     * 块缓存上限。128MB 要能同时装下双路各 50MB 的领先窗口（共 100MB），
     * 低于这个量缓存就会抖动：预读刚拉下来的块被淘汰，转头又得重下，
     * 带宽全浪费在重复下载上。堆小的机型按 maxMemory/3 自动降级。
     */
    private final long maxCache;

    /** 当前预读窗口（字节）；默认 50MB，买不起这么大缓存时跟着缩。 */
    private volatile long aheadBytes;

    private final List<Thread> prefetchers = new ArrayList<>();
    /** 正在下载的块号：解码线程和预读线程都靠它去重，避免同一块被拉两遍。 */
    private final Set<Long> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong bytesDown = new AtomicLong();
    private final long startedMs = System.currentTimeMillis();

    public RemoteFile(RemoteStore store, String path) throws IOException {
        this.store = store;
        this.path = path;
        long s = store.size(path);
        if (s <= 0) {
            throw new IOException("无法获取远程文件大小（文件可能不存在）");
        }
        this.size = s;
        // 缓存吃满会 OOM 崩溃，不如按实际堆大小缩窗口：宁可少领先几秒，
        // 也不能把 app 打死。maxMemory/3 给解码器、GL 纹理留出余量。
        this.maxCache = Math.min(CACHE_WANTED, Runtime.getRuntime().maxMemory() / 3);
        this.aheadBytes = Math.min(PREFETCH_AHEAD, maxCache / MAX_STREAMS);
        demand.put(0L, System.currentTimeMillis());   // 从头开始预读
        startPrefetch();
    }

    public long length() {
        return size;
    }

    public String path() {
        return path;
    }

    // ------------------------------------------------------------------ 读

    /**
     * 读一段数据，返回字节数，越界返回 -1。
     *
     * 不整个方法加锁：两条解码线程 + 预读线程会同时读，锁里做网络 I/O
     * 会让它们互相排队，把并发带宽压成单流。现在只在改缓存时短暂加锁。
     */
    public int read(long pos, byte[] buf, int off, int len) throws IOException {
        if (closed) throw new IOException("已关闭");
        if (pos >= size) return -1;
        int want = (int) Math.min(len, size - pos);
        int done = 0;

        // 记录读位置供预读跟随；只保留最近两个（双路解码）
        synchronized (demand) {
            demand.put(pos / BLOCK, System.currentTimeMillis());
            while (demand.size() > MAX_STREAMS) {
                Iterator<Long> it = demand.keySet().iterator();
                it.next();
                it.remove();
            }
        }

        while (done < want) {
            long p = pos + done;
            long index = p / BLOCK;
            int inner = (int) (p % BLOCK);
            byte[] block = loadBlock(index);
            if (block == null) break;
            int n = Math.min(want - done, block.length - inner);
            if (n <= 0) break;
            System.arraycopy(block, inner, buf, off + done, n);
            done += n;
        }
        return done <= 0 ? -1 : done;
    }

    /**
     * 命中就直接返回；没命中就走网络。
     *
     * 下载本身也要抢 inFlight，不能只在预读线程里登记：否则解码线程和预读
     * 线程可能同时看它没在下载，各下一份，白白多耗一倍带宽。
     */
    private byte[] loadBlock(long index) throws IOException {
        byte[] cached = getBlock(index);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        // 最多等 6 秒（600 × 10ms）：别人在拉就等它，拉不下来也不能把解码线程挂死
        for (int attempt = 0; attempt < 600 && !closed; attempt++) {
            if (inFlight.contains(index)) {
                sleepQuiet(10);
                cached = getBlock(index);
                if (cached != null) {
                    hits.incrementAndGet();
                    return cached;
                }
                continue;
            }
            byte[] data = fetchExclusive(index);
            if (data != null) {
                misses.incrementAndGet();
                return data;
            }
            // 刚好被别人抢走，下一轮接着看
        }
        return null;
    }

    /** 抢到 inFlight 才下载，抢不到返回 null。下载成功顺手入缓存。 */
    private byte[] fetchExclusive(long index) throws IOException {
        if (!inFlight.add(index)) return null;
        try {
            byte[] data = fetchBlock(index);
            if (data != null) putBlock(index, data);
            return data;
        } finally {
            inFlight.remove(index);
        }
    }

    private synchronized byte[] getBlock(long index) {
        return blocks.get(index);
    }

    private byte[] fetchBlock(long index) throws IOException {
        long start = index * BLOCK;
        if (start >= size) return null;
        int want = (int) Math.min(BLOCK, size - start);
        byte[] block = new byte[want];
        int got = 0;
        while (got < want) {
            int n = store.readAt(path, start + got, block, got, want - got);
            if (n <= 0) break;
            got += n;
        }
        if (got <= 0) return null;
        bytesDown.addAndGet(got);
        return got == want ? block : java.util.Arrays.copyOf(block, got);
    }

    /** 必须在持有 this 锁时调用。 */
    private synchronized void putBlock(long index, byte[] data) {
        byte[] old = blocks.put(index, data);
        if (old != null) cacheBytes -= old.length;
        cacheBytes += data.length;
        Iterator<Map.Entry<Long, byte[]>> it = blocks.entrySet().iterator();
        while (cacheBytes > maxCache && it.hasNext()) {
            Map.Entry<Long, byte[]> eldest = it.next();
            cacheBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    private synchronized boolean isCached(long index) {
        return blocks.containsKey(index);
    }

    private synchronized long cachedBytes() {
        return cacheBytes;
    }

    // ------------------------------------------------------------------ 预读

    /**
     * 两条预读线程，对应双路解码的两个读位置。
     *
     * 单线程串行拉的时候，网络 I/O 一次只能有一个在飞；改成两个之后，
     * 两路各占一条 keep-alive 连接，NVMe NAS + WiFi 上能叠加出更高吞吐。
     */
    private void startPrefetch() {
        for (int i = 0; i < MAX_STREAMS; i++) {
            Thread t = new Thread(this::prefetchLoop, "osv-prefetch-" + i);
            t.setDaemon(true);
            prefetchers.add(t);
            t.start();
        }
    }

    /**
     * 从「最近的读位置」往前找第一个该拉、又没人正在拉的块，拉下来。
     *
     * 用 inFlight 去重：两个线程同时扫同一段预读窗口时，后到的会自动跳过，
     * 于是自然地分工成两路并行下载，而不是重复下同一块。
     */
    private void prefetchLoop() {
        while (!closed) {
            long index = nextWantedBlock();
            if (index < 0) {
                sleepQuiet(120);
                continue;
            }
            try {
                if (fetchExclusive(index) == null) sleepQuiet(5);   // 被解码线程抢先了
            } catch (Throwable t) {
                if (!closed) Diag.w("RemoteFile", "预读失败 idx=" + index, t);
                return;
            }
        }
    }

    /** 返回一个待下载的块号；没有就给 -1。 */
    private long nextWantedBlock() {
        List<Long> positions;
        synchronized (demand) {
            positions = new ArrayList<>(demand.keySet());
        }
        if (cachedBytes() > maxCache - BLOCK * 2) return -1;

        long lastBlock = (size - 1) / BLOCK;
        for (long startBlock : positions) {
            if (closed) return -1;
            long endBlock = Math.min((startBlock * BLOCK + aheadBytes) / BLOCK, lastBlock);
            for (long index = startBlock; index <= endBlock; index++) {
                if (!inFlight.contains(index) && !isCached(index)) return index;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 缓冲等待

    /** 区间 [pos, pos+len) 内已缓存多少字节。 */
    public synchronized long cachedIn(long pos, long len) {
        long end = Math.min(size, pos + len);
        long total = 0;
        for (long p = pos; p < end; p += BLOCK) {
            if (blocks.get(p / BLOCK) != null) total += BLOCK;
        }
        return Math.min(total, Math.max(0, end - pos));
    }

    /** 等 [pos, pos+want) 攒够再返回；超时返回实际是否够。 */
    public boolean awaitBuffered(long pos, long want, long timeoutMs, BufferListener listener) {
        long target = Math.min(want, size - pos);
        if (target <= 0) return true;
        // 调用方要的攒量超过默认窗口时，把窗口撑到能覆盖它
        if (target > aheadBytes) aheadBytes = Math.min(target, maxCache / MAX_STREAMS);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !closed) {
            long cached = cachedIn(pos, target);
            if (listener != null) listener.onBuffer(cached, target);
            if (cached >= target) return true;
            sleepQuiet(100);
        }
        boolean ok = cachedIn(pos, target) >= target;
        if (!ok) {
            Diag.w("RemoteFile", "缓冲未达目标 " + (cachedIn(pos, target) / 1024)
                    + "KB/" + (target / 1024) + "KB，先开播");
        }
        return ok;
    }

    /** 诊断用一行统计。 */
    public String stats() {
        long ms = Math.max(1, System.currentTimeMillis() - startedMs);
        long down = bytesDown.get();
        long rateKb = down * 1000 / ms / 1024;
        long hitCount = hits.get();
        long total = hitCount + misses.get();
        long hitRate = total == 0 ? 0 : hitCount * 100 / total;
        return "缓存 " + (cachedBytes() / 1024 / 1024) + "MB/" + (maxCache / 1024 / 1024)
                + "MB · 命中 " + hitRate + "% · 下行 " + (down / 1024 / 1024)
                + "MB @ " + rateKb + "KB/s";
    }

    /** 给 MediaExtractor / MediaMetadataRetriever 用；close 不真正关闭底层文件。 */
    public MediaDataSource dataSource() {
        return new MediaDataSource() {
            @Override
            public int readAt(long position, byte[] buffer, int offset, int size) {
                try {
                    return RemoteFile.this.read(position, buffer, offset, size);
                } catch (Throwable t) {
                    Diag.w("RemoteFile", "读取失败 pos=" + position, t);
                    return -1;
                }
            }

            @Override
            public long getSize() {
                return size;
            }

            @Override
            public void close() {
                // 由持有者负责关闭（两条解码共用一个 RemoteFile）
            }
        };
    }

    @Override
    public void close() {
        closed = true;
        for (Thread t : prefetchers) t.interrupt();
        prefetchers.clear();
        synchronized (this) {
            blocks.clear();
            cacheBytes = 0;
        }
        inFlight.clear();
        try {
            store.close();
        } catch (Exception ignored) {
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
