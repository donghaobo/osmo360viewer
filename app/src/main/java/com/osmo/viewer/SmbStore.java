package com.osmo.viewer;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SMB2/3：NAS 上最通用也最合适的协议（加密、随机读效率高，
 * Windows / macOS / Synology / QNAP / TrueNAS 全都原生支持）。
 *
 * path 的第一段是共享名，其余是共享内路径，例如 "videos/2026-09"。
 */
public class SmbStore implements RemoteStore {

    private final SMBClient client = new SMBClient();
    private Connection connection;
    private Session session;
    private DiskShare share;
    private final String shareName;
    private final String basePath;

    public SmbStore(RemoteConfig cfg) throws IOException {
        String p = cfg.path == null ? "" : cfg.path.trim();
        while (p.startsWith("/") || p.startsWith("\\")) p = p.substring(1);
        int slash = p.indexOf('/');
        if (slash < 0) {
            shareName = p;
            basePath = "";
        } else {
            shareName = p.substring(0, slash);
            basePath = p.substring(slash + 1);
        }
        if (shareName.isEmpty()) {
            throw new IOException("SMB 需要填共享名，例如 videos 或 videos/2026-09");
        }
        int port = cfg.port > 0 ? cfg.port : 445;
        try {
            connection = client.connect(cfg.host, port);
            AuthenticationContext auth = (cfg.user == null || cfg.user.isEmpty())
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(cfg.user,
                    cfg.password == null ? new char[0] : cfg.password.toCharArray(), null);
            session = connection.authenticate(auth);
            share = (DiskShare) session.connectShare(shareName);
        } catch (IOException e) {
            close();
            throw new IOException("SMB 连接失败：" + e.getMessage(), e);
        }
    }

    /** 把相对路径补成共享内的完整路径（SMB 用反斜杠，根为空串）。 */
    private String full(String path) {
        String rel = path == null ? "" : path;
        while (rel.startsWith("/") || rel.startsWith("\\")) rel = rel.substring(1);
        String joined = rel.isEmpty() ? basePath
                : (basePath.isEmpty() ? rel : basePath.replace('\\', '/') + "/" + rel);
        return joined.replace('/', '\\');
    }

    @Override
    public List<Entry> list(String dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        String rel = full(dir);
        if (!share.folderExists(rel)) return out;
        for (FileIdBothDirectoryInformation info : share.list(rel)) {
            String name = info.getFileName();
            if (".".equals(name) || "..".equals(name)) continue;
            boolean isDir = (info.getFileAttributes()
                    & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
            long modified = 0;
            try {
                com.hierynomus.msdtyp.FileTime t = info.getLastWriteTime();
                if (t != null) modified = t.toEpochMillis();
            } catch (Throwable ignored) {
            }
            out.add(new Entry(RemoteStore.join(dir, name), name, isDir,
                    isDir ? 0 : info.getEndOfFile(), modified));
        }
        return out;
    }

    @Override
    public int readAt(String path, long pos, byte[] buf, int off, int len) throws IOException {
        String rel = full(path);
        try (File file = share.openFile(rel,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null)) {
            long size = file.getFileInformation().getStandardInformation().getEndOfFile();
            if (pos >= size) return -1;
            int want = (int) Math.min(len, size - pos);
            int got = 0;
            // SMB2 单次读有上限（通常 64KB~1MB），循环补齐
            while (got < want) {
                int n = file.read(buf, pos + got, off + got, want - got);
                if (n <= 0) break;
                got += n;
            }
            return got <= 0 ? -1 : got;
        }
    }

    @Override
    public long size(String path) throws IOException {
        return share.getFileInformation(full(path)).getStandardInformation().getEndOfFile();
    }

    @Override
    public void upload(String path, java.io.InputStream in, long length, Progress cb)
            throws IOException {
        String rel = full(path);
        try (File file = share.openFile(rel,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null)) {
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                file.write(buf, done, 0, n);
                done += n;
                if (cb != null && !cb.onBytes(done, length)) {
                    throw new IOException("传输已取消");
                }
            }
        }
    }

    @Override
    public void delete(String path) throws IOException {
        String rel = full(path);
        if (share.fileExists(rel)) {
            share.rm(rel);
        }
        // 目录不在这里删：调用方只该传文件路径，传目录说明有 bug，静默跳过更安全
    }

    @Override
    public boolean exists(String path) throws IOException {
        return share.fileExists(full(path));
    }

    @Override
    public void mkdirs(String dir) throws IOException {
        String rel = full(dir);
        if (rel.isEmpty()) return;
        String acc = "";
        for (String seg : rel.split("\\\\")) {
            if (seg.isEmpty()) continue;
            acc = acc.isEmpty() ? seg : acc + "\\" + seg;
            if (!share.folderExists(acc)) share.mkdir(acc);
        }
    }

    @Override
    public void close() {
        try {
            if (share != null) share.close();
        } catch (Exception ignored) {
        }
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (connection != null) connection.close();
        } catch (Exception ignored) {
        }
        try {
            client.close();
        } catch (Exception ignored) {
        }
        share = null;
        session = null;
        connection = null;
    }
}
