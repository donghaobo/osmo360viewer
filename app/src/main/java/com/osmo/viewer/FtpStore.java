package com.osmo.viewer;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP：老协议、明文传输、随机读要靠 REST，效率不如 SMB。
 * 只在 NAS 只开了 FTP 时才用；能用 SMB2/3 就用 SMB。
 */
public class FtpStore implements RemoteStore {

    private static final int TIMEOUT_MS = 15000;

    private final FTPClient client = new FTPClient();
    private final String basePath;

    public FtpStore(RemoteConfig cfg) throws IOException {
        String base = cfg.path == null ? "" : cfg.path.trim();
        while (base.startsWith("/")) base = base.substring(1);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        basePath = base;

        client.setConnectTimeout(TIMEOUT_MS);
        client.setDefaultTimeout(TIMEOUT_MS);
        client.setDataTimeout(java.time.Duration.ofMillis(TIMEOUT_MS));
        int port = cfg.port > 0 ? cfg.port : 21;
        client.connect(cfg.host, port);
        if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
            client.disconnect();
            throw new IOException("FTP 连接被拒绝：" + client.getReplyString());
        }
        boolean ok = client.login(cfg.user == null ? "anonymous" : cfg.user,
                cfg.password == null ? "" : cfg.password);
        if (!ok) {
            client.disconnect();
            throw new IOException("FTP 登录失败：" + client.getReplyString());
        }
        client.enterLocalPassiveMode();
        client.setFileType(FTPClient.BINARY_FILE_TYPE);
        client.setAutodetectUTF8(true);
    }

    private String full(String path) {
        String rel = path == null ? "" : path.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        return "/" + (basePath.isEmpty() ? rel : (rel.isEmpty() ? basePath : basePath + "/" + rel));
    }

    @Override
    public synchronized List<Entry> list(String dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        FTPFile[] files = client.listFiles(full(dir));
        if (files == null) return out;
        for (FTPFile f : files) {
            String name = f.getName();
            if (".".equals(name) || "..".equals(name)) continue;
            long modified = f.getTimestamp() == null ? 0 : f.getTimestamp().getTimeInMillis();
            out.add(new Entry(RemoteStore.join(dir, name), name, f.isDirectory(),
                    f.isDirectory() ? 0 : f.getSize(), modified));
        }
        return out;
    }

    // 下面三个方法 synchronized：FTPClient 一条控制连接扛不住并发命令，
    // 而 RemoteFile 现在是两条预读线程加解码线程同时在读。FTP 反正不是性能路径。
    @Override
    public synchronized int readAt(String path, long pos, byte[] buf, int off, int len) throws IOException {
        if (pos > 0) client.setRestartOffset(pos);   // 不支持 REST 的服务器会在读到时返回错误
        try (InputStream in = client.retrieveFileStream(full(path))) {
            if (in == null) {
                throw new IOException("FTP 读取失败：" + client.getReplyString());
            }
            int total = 0;
            int want = Math.min(len, 1024 * 1024);
            while (total < want) {
                int n = in.read(buf, off + total, want - total);
                if (n < 0) break;
                total += n;
            }
            return total <= 0 ? -1 : total;
        } finally {
            try {
                client.completePendingCommand();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public synchronized long size(String path) throws IOException {
        // SIZE 命令对二进制模式最准，不支持时退回目录列表
        try {
            int size = client.size(full(path));
            if (size > 0) return size;
        } catch (Exception ignored) {
        }
        FTPFile[] files = client.listFiles(full(path));
        if (files != null && files.length == 1) return files[0].getSize();
        return -1;
    }

    @Override
    public synchronized void upload(String path, java.io.InputStream in, long length, Progress cb)
            throws IOException {
        String remote = full(path);
        try (java.io.OutputStream out = client.storeFileStream(remote)) {
            if (out == null) {
                throw new IOException("FTP 上传失败：" + client.getReplyString());
            }
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (cb != null && !cb.onBytes(done, length)) {
                    throw new IOException("传输已取消");
                }
            }
        } finally {
            // storeFileStream 之后必须收尾，否则控制连接会卡在下一条命令上
            try {
                client.completePendingCommand();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public synchronized void delete(String path) throws IOException {
        String remote = full(path);
        if (!client.deleteFile(remote)) {
            String reply = client.getReplyString();
            // 550 = 文件不存在 / 无权限。不存在按成功处理，避免"删两次"报错。
            if (reply != null && reply.startsWith("550")) return;
            throw new IOException("FTP 删除失败：" + reply);
        }
    }

    @Override
    public synchronized boolean exists(String path) throws IOException {
        FTPFile[] files = client.listFiles(full(path));
        return files != null && files.length > 0;
    }

    @Override
    public synchronized void mkdirs(String dir) throws IOException {
        String rel = dir == null ? "" : dir.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) return;
        String acc = "";
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) continue;
            acc = acc.isEmpty() ? seg : acc + "/" + seg;
            String remote = full(acc);
            FTPFile[] exist = client.listFiles(remote);
            if (exist != null && exist.length > 0) continue;
            client.makeDirectory(remote);
        }
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.logout();
                client.disconnect();
            }
        } catch (Exception ignored) {
        }
    }
}
