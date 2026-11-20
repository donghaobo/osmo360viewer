package com.osmo.viewer;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件复制：本地 SAF 与 NAS 之间的四种组合共用一个引擎。
 *
 * 调用方只描述「从哪读」「往哪写」，连接建立/关闭、进度回传、同名处理都在这里。
 * 传输是同步阻塞的，调用方自己放到后台线程（见 {@link TransferService}）。
 *
 * v1 直接写最终文件名，不做 `.part` + 改名：中断会在目标端留下半截文件，
 * 已知限制记录在 docs/osv/gallery-merge-plan.md。
 */
public final class Transfer {

    /** 同名文件的处理方式。 */
    public enum Conflict {
        SKIP,       // 目标已有就跳过
        OVERWRITE,  // 覆盖
        RENAME      // 追加 -1、-2 …
    }

    /** 进度回调：返回 false 表示取消。 */
    public interface Progress {
        boolean onBytes(long done, long total);
    }

    public interface Src {
        String name();

        long length();

        String label();

        InputStream open() throws IOException;
    }

    public interface Dst {
        String label();

        /** 写一次；返回 null 表示按冲突策略跳过。 */
        String write(String sourceName, long length, InputStream in, Progress progress)
                throws IOException;
    }

    private Transfer() {}

    // ------------------------------------------------------------------ 源

    /** 本机 SAF 文档。 */
    public static Src localSource(final Context c, final Uri docUri, final String name,
                                  final long size) {
        return new Src() {
            @Override public String name() { return name; }
            @Override public long length() { return size; }
            @Override public String label() { return "本机"; }

            @Override
            public InputStream open() throws IOException {
                InputStream in = c.getContentResolver().openInputStream(docUri);
                if (in == null) throw new IOException("打不开本地文件：" + name);
                return in;
            }
        };
    }

    /** NAS 上的文件：打开时建立连接，流关闭时把连接关掉。 */
    public static Src remoteSource(final RemoteConfig cfg, final String path,
                                   final String name, final long size) {
        return new Src() {
            @Override public String name() { return name; }
            @Override public long length() { return size; }
            @Override public String label() { return RemoteScanner.labelOf(cfg); }

            @Override
            public InputStream open() throws IOException {
                final RemoteStore store = RemoteStore.open(cfg);
                InputStream in = store.open(path, size);
                return new java.io.FilterInputStream(in) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            try { store.close(); } catch (Exception ignored) {}
                        }
                    }
                };
            }
        };
    }

    // ------------------------------------------------------------------ 目标

    /**
     * 本地目录目标。
     *
     * @param treeUri 用户授权的目录树
     * @param relDir  目录里的子路径，"" 表示根
     */
    public static Dst localDest(final Context c, final Uri treeUri, final String relDir,
                                final Conflict conflict) {
        return new Dst() {
            @Override public String label() { return "本机 · " + relDir; }

            @Override
            public String write(String sourceName, long length, InputStream in, Progress progress)
                    throws IOException {
                Uri dir = SafOps.resolveOrCreateDir(c, treeUri, relDir);
                if (dir == null) throw new IOException("本地目标目录不可写：" + relDir);
                Uri existing = SafOps.findChild(c, treeUri, dir, sourceName);
                String finalName = pickName(c, treeUri, dir, sourceName, conflict, existing);
                if (finalName == null) return null;   // SKIP

                if (existing != null && conflict == Conflict.OVERWRITE) {
                    SafOps.delete(c, existing);
                }
                Uri doc = DocumentsContract.createDocument(
                        c.getContentResolver(), dir, "video/mp4", finalName);
                if (doc == null) throw new IOException("无法在本地创建文件：" + finalName);
                try (OutputStream out = c.getContentResolver().openOutputStream(doc)) {
                    if (out == null) throw new IOException("无法写入本地文件：" + finalName);
                    copyStream(in, out, length, progress);
                }
                return finalName;
            }
        };
    }

    /** NAS 目录目标（dir 为配置根下的相对目录）。 */
    public static Dst remoteDest(final RemoteConfig cfg, final String dir,
                                 final Conflict conflict) {
        return new Dst() {
            @Override public String label() { return RemoteScanner.labelOf(cfg); }

            @Override
            public String write(String sourceName, long length, InputStream in, Progress progress)
                    throws IOException {
                RemoteStore store = RemoteStore.open(cfg);
                try {
                    if (dir != null && !dir.isEmpty()) store.mkdirs(dir);
                    String finalName = pickRemoteName(store, dir, sourceName, conflict);
                    if (finalName == null) return null;

                    String path = dir == null || dir.isEmpty() ? finalName : dir + "/" + finalName;
                    if (conflict == Conflict.OVERWRITE && store.exists(path)) {
                        store.delete(path);
                    }
                    store.upload(path, in, length, progress == null ? null : progress::onBytes);
                    return finalName;
                } finally {
                    try { store.close(); } catch (Exception ignored) {}
                }
            }
        };
    }

    // ------------------------------------------------------------------ 执行

    /** 同步复制一个文件；返回目标文件名，跳过时返回 null，失败抛异常。 */
    public static String copy(Src src, Dst dst, Progress progress) throws IOException {
        try (InputStream in = src.open()) {
            return dst.write(src.name(), src.length(), in, progress);
        }
    }

    /** 把字节从 in 搬到 out，顺带回传进度。 */
    static void copyStream(InputStream in, OutputStream out, long total, Progress progress)
            throws IOException {
        byte[] buf = new byte[512 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (progress != null && !progress.onBytes(done, total)) {
                throw new IOException("传输已取消");
            }
        }
        out.flush();
    }

    // ------------------------------------------------------------------ 命名

    private static String pickName(Context c, Uri tree, Uri dir, String name, Conflict conflict,
                                   Uri existing) {
        if (existing == null) return name;
        switch (conflict) {
            case SKIP:
                return null;
            case OVERWRITE:
                return name;
            default:
                return uniqueName(name, candidate -> SafOps.findChild(c, tree, dir, candidate) != null);
        }
    }

    private static String pickRemoteName(RemoteStore store, String dir, String name,
                                         Conflict conflict) throws IOException {
        String path = dir == null || dir.isEmpty() ? name : dir + "/" + name;
        if (!store.exists(path)) return name;
        switch (conflict) {
            case SKIP:
                return null;
            case OVERWRITE:
                return name;
            default:
                return uniqueName(name, candidate -> {
                    String p = dir == null || dir.isEmpty() ? candidate : dir + "/" + candidate;
                    try {
                        return store.exists(p);
                    } catch (IOException e) {
                        return true;   // 查不了就当它存在，继续往后找
                    }
                });
        }
    }

    private interface Exists {
        boolean test(String name);
    }

    /** `名字.OSV` → `名字-1.OSV`、`名字-2.OSV` … */
    private static String uniqueName(String name, Exists exists) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "-" + i + ext;
            if (!exists.test(candidate)) return candidate;
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    /** 目录里的一个子项。 */
    public static final class Child {
        public final String name;
        public final boolean dir;
        public final long size;
        public final long mtime;
        public final Uri doc;

        Child(String name, boolean dir, long size, long mtime, Uri doc) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.doc = doc;
        }
    }

    /** 按平台把 ContentResolver 关心的事收在一处，便于以后换实现。 */
    public static final class SafOps {

        private SafOps() {}

        /** 逐级解析目录；不存在就创建。 */
        static Uri resolveOrCreateDir(Context c, Uri tree, String relDir) throws IOException {
            Uri current = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            if (relDir == null || relDir.trim().isEmpty()) return current;
            for (String seg : relDir.split("/")) {
                if (seg.isEmpty() || ".".equals(seg)) continue;
                Uri child = findChild(c, tree, current, seg);
                if (child == null) {
                    child = DocumentsContract.createDocument(c.getContentResolver(),
                            current, DocumentsContract.Document.MIME_TYPE_DIR, seg);
                    if (child == null) return null;
                }
                current = child;
            }
            return current;
        }

        /** 在某个文档（目录）下按显示名找子项。 */
        static Uri findChild(Context c, Uri tree, Uri dirDoc, String name) {
            if (name == null || name.isEmpty()) return null;
            String docId = DocumentsContract.getDocumentId(dirDoc);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            String[] cols = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            };
            try (android.database.Cursor cur = c.getContentResolver()
                    .query(children, cols, null, null, null)) {
                if (cur == null) return null;
                while (cur.moveToNext()) {
                    if (name.equals(cur.getString(1))) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        /** 目录里的直接子项（同步用的遍历基础）。 */
        public static java.util.List<Child> listChildren(Context c, Uri tree, Uri dirDoc) {
            java.util.List<Child> out = new java.util.ArrayList<>();
            String docId = DocumentsContract.getDocumentId(dirDoc);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            String[] cols = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            };
            try (android.database.Cursor cur = c.getContentResolver()
                    .query(children, cols, null, null, null)) {
                if (cur == null) return out;
                while (cur.moveToNext()) {
                    String id = cur.getString(0);
                    String name = cur.getString(1);
                    String mime = cur.getString(2);
                    long size = cur.isNull(3) ? 0 : cur.getLong(3);
                    long mtime = cur.isNull(4) ? 0 : cur.getLong(4);
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    out.add(new Child(name, isDir, size, mtime,
                            DocumentsContract.buildDocumentUriUsingTree(tree, id)));
                }
            } catch (Exception ignored) {
            }
            return out;
        }

        static boolean delete(Context c, Uri doc) {
            try {
                return DocumentsContract.deleteDocument(c.getContentResolver(), doc);
            } catch (Exception e) {
                return false;
            }
        }

        /** 本地文件大小（拿不到返回 -1）。 */
        static long sizeOf(Context c, Uri doc) {
            try (android.database.Cursor cur = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
            } catch (Exception ignored) {
            }
            return -1;
        }

        static ContentResolver resolver(Context c) {
            return c.getContentResolver();
        }
    }
}
