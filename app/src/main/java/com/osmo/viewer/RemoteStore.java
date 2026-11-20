package com.osmo.viewer;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 远程存储的统一接口：读（列目录 / 按偏移读）与写（上传 / 删除 / 建目录）。
 *
 * 之所以强调随机读：播放 1GB 的 OSV 要靠两条 MediaExtractor 反复 seek，
 * 顺序流式读不够用；因此每个协议都必须支持按偏移定位读。
 *
 * 写能力是 v0.7 加的，用默认方法实现，老协议不实现也不会编译失败。
 */
public interface RemoteStore extends Closeable {

    /** 上传进度回调；返回 false 表示用户取消。 */
    interface Progress {
        boolean onBytes(long done, long total);
    }

    final class Entry {
        public String path;
        public String name;
        public boolean dir;
        public long size;
        public long modifiedMs;

        Entry(String path, String name, boolean dir, long size, long modifiedMs) {
            this.path = path;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }
    }

    /** 列目录（只需要一层，交给调用方递归）。 */
    List<Entry> list(String dir) throws IOException;

    /** 从 pos 开始读最多 len 字节；返回实际字节数，pos 已到末尾返回 -1。 */
    int readAt(String path, long pos, byte[] buf, int off, int len) throws IOException;

    /** 文件大小；未知返回 -1。 */
    long size(String path) throws IOException;

    // ------------------------------------------------------------------ 写

    /** 上传到 path。length < 0 表示总长未知。 */
    default void upload(String path, java.io.InputStream in, long length, Progress cb)
            throws IOException {
        throw new IOException("这个协议还不支持上传");
    }

    default void delete(String path) throws IOException {
        throw new IOException("这个协议还不支持删除");
    }

    default boolean exists(String path) throws IOException {
        return false;
    }

    /** 递归创建目录；dir 为空表示根目录。 */
    default void mkdirs(String dir) throws IOException {
        throw new IOException("这个协议还不支持建目录");
    }

    /** 把 readAt 包装成流，方便"本地读 → 远端写"这种传输复用同一套拷贝逻辑。 */
    default java.io.InputStream open(String path, long length) {
        return new java.io.InputStream() {
            private long pos;
            private final byte[] buf = new byte[256 * 1024];
            private int off;
            private int len;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n <= 0 ? -1 : (one[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int o, int l) throws IOException {
                if (len > 0) {
                    int n = Math.min(l, len);
                    System.arraycopy(buf, off, b, o, n);
                    off += n;
                    len -= n;
                    return n;
                }
                if (length >= 0 && pos >= length) return -1;
                int want = (int) Math.min(buf.length,
                        length >= 0 ? length - pos : buf.length);
                int n = readAt(path, pos, buf, 0, want);
                if (n <= 0) return -1;
                pos += n;
                off = 0;
                len = n;
                return read(b, o, l);
            }
        };
    }

    static RemoteStore open(RemoteConfig cfg) throws IOException {
        switch (cfg.protocol) {
            case RemoteConfig.PROTO_WEBDAV:
                return new WebDavStore(cfg);
            case RemoteConfig.PROTO_FTP:
                return new FtpStore(cfg);
            case RemoteConfig.PROTO_SMB:
            default:
                return new SmbStore(cfg);
        }
    }

    /** 统一路径：去掉首尾多余分隔符，用 / 连接。 */
    static String join(String dir, String name) {
        String d = dir == null ? "" : dir;
        while (d.startsWith("/")) d = d.substring(1);
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        return d.isEmpty() ? name : d + "/" + name;
    }

    static String parent(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i <= 0 ? "" : path.substring(0, i);
    }

    static String baseName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static boolean isVideo(String name) {
        if (name == null) return false;
        return OsvLibrary.isOsv(name.toLowerCase(Locale.US));
    }
}
