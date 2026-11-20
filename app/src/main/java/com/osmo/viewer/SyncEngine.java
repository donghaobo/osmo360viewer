package com.osmo.viewer;

import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步执行器：扫源目录 → 过滤 → 增量判定 → 逐个复制。
 *
 * 只比元信息（名字/大小/时间），从不读文件内容做校验。
 * 方向由策略决定，所以源可能是本机目录也可能是 NAS。
 *
 * 同步是同步阻塞的，调用方自己放后台线程。
 */
public final class SyncEngine {

    public interface Progress {
        /** 正在处理哪个文件（用于界面提示）。 */
        void onStep(String text);

        /** 单文件进度；返回 false 取消整个同步。 */
        boolean onBytes(long done, long total);
    }

    public static final class Result {
        public int copied;
        public int skipped;
        public int failed;
        public long bytes;
        public final List<String> errors = new ArrayList<>();
        public boolean cancelled;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("复制 ").append(copied)
                    .append(" · 跳过 ").append(skipped)
                    .append(" · 失败 ").append(failed);
            if (bytes > 0) sb.append(" · ").append(OsvLibrary.humanSize(bytes));
            if (cancelled) sb.append(" · 已取消");
            return sb.toString();
        }
    }

    private static final int MAX_DEPTH = 8;
    private static final int MAX_FILES = 20000;

    private SyncEngine() {}

    public static Result run(Context c, SyncRule rule, Progress p) throws IOException {
        if (rule.selfLoop()) throw new IOException("源和目标是同一个目录，不同步");
        Result result = new Result();

        List<FileRef> files = new ArrayList<>();
        collect(c, rule, p, files);
        if (result.cancelled) return result;

        RemoteConfig srcCfg = rule.source.isRemote() ? RemoteConfigs.find(c, rule.source.ownerId) : null;
        RemoteConfig dstCfg = rule.dest.isRemote() ? RemoteConfigs.find(c, rule.dest.ownerId) : null;
        if (rule.source.isRemote() && srcCfg == null) throw new IOException("源服务器配置已删除");
        if (rule.dest.isRemote() && dstCfg == null) throw new IOException("目标服务器配置已删除");

        RemoteStore dstStore = null;
        try {
            for (FileRef f : files) {
                if (p != null) p.onStep(f.relPath);
                try {
                    String dstSub = joinPath(rule.dest.path, parentOf(f.relPath));
                    Long existSize = existingSize(c, rule, dstSub, f.name);
                    if (rule.upToDate(f.size, f.mtime, existSize, 0)) {
                        result.skipped++;
                        continue;
                    }

                    Transfer.Src src = f.local
                            ? Transfer.localSource(c, Uri.parse(f.docPath), f.name, f.size)
                            : Transfer.remoteSource(srcCfg, f.relPath, f.name, f.size);
                    Transfer.Dst dst = rule.dest.isRemote()
                            ? Transfer.remoteDest(dstCfg, dstSub, Transfer.Conflict.RENAME)
                            : Transfer.localDest(c, Uri.parse(rule.dest.ownerId), dstSub,
                            Transfer.Conflict.RENAME);

                    Transfer.copy(src, dst, (done, total) -> {
                        if (p != null && !p.onBytes(done, total)) {
                            result.cancelled = true;
                            return false;
                        }
                        return true;
                    });
                    if (result.cancelled) break;
                    result.copied++;
                    result.bytes += f.size;
                } catch (Throwable t) {
                    result.failed++;
                    String msg = f.name + "：" + (t.getMessage() == null ? t.toString() : t.getMessage());
                    result.errors.add(msg);
                    Diag.w("Sync", "同步失败 " + f.relPath, t);
                }
            }
        } finally {
            if (dstStore != null) {
                try { dstStore.close(); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 收集

    private static final class FileRef {
        String relPath;
        String name;
        long size;
        long mtime;
        boolean local;
        String docPath;   // 本地：document uri
    }

    private static void collect(Context c, SyncRule rule, Progress p, List<FileRef> out)
            throws IOException {
        if (rule.source.isRemote()) {
            RemoteConfig cfg = RemoteConfigs.find(c, rule.source.ownerId);
            if (cfg == null) throw new IOException("源服务器配置已删除");
            RemoteStore store = RemoteStore.open(cfg);
            try {
                walkRemote(store, rule, "", 0, p, out);
            } finally {
                try { store.close(); } catch (Exception ignored) {}
            }
        } else {
            Uri tree = Uri.parse(rule.source.ownerId);
            Uri root = Transfer.SafOps.resolveOrCreateDir(c, tree, rule.source.path);
            if (root == null) throw new IOException("本机源目录不可用");
            walkLocal(c, tree, root, rule.source.path, 0, rule, p, out);
        }
    }

    private static void walkLocal(Context c, Uri tree, Uri dirDoc, String rel, int depth,
                                  SyncRule rule, Progress p, List<FileRef> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        for (Transfer.Child ch : Transfer.SafOps.listChildren(c, tree, dirDoc)) {
            if (out.size() >= MAX_FILES) return;
            String childRel = rel.isEmpty() ? ch.name : rel + "/" + ch.name;
            if (ch.dir) {
                if (ch.name.startsWith(".")) continue;
                walkLocal(c, tree, ch.doc, childRel, depth + 1, rule, p, out);
                continue;
            }
            if (!rule.accept(ch.name, childRel)) continue;
            FileRef f = new FileRef();
            f.relPath = childRel;
            f.name = ch.name;
            f.size = ch.size;
            f.mtime = ch.mtime;
            f.local = true;
            f.docPath = ch.doc.toString();
            out.add(f);
        }
    }

    private static void walkRemote(RemoteStore store, SyncRule rule, String rel, int depth,
                                   Progress p, List<FileRef> out) throws IOException {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        for (RemoteStore.Entry e : store.list(rel)) {
            if (out.size() >= MAX_FILES) return;
            String childRel = rel.isEmpty() ? e.name : rel + "/" + e.name;
            if (e.dir) {
                if (e.name.startsWith(".")) continue;
                walkRemote(store, rule, childRel, depth + 1, p, out);
                continue;
            }
            if (!rule.accept(e.name, childRel)) continue;
            FileRef f = new FileRef();
            f.relPath = childRel;
            f.name = e.name;
            f.size = e.size;
            f.mtime = e.modifiedMs;
            f.local = false;
            out.add(f);
        }
    }

    // ------------------------------------------------------------------ 目标已有文件

    /** 目标端同名文件的大小；不存在返回 null。 */
    private static Long existingSize(Context c, SyncRule rule, String dstSub, String name) {
        try {
            if (rule.dest.isRemote()) {
                RemoteConfig cfg = RemoteConfigs.find(c, rule.dest.ownerId);
                if (cfg == null) return null;
                RemoteStore store = RemoteStore.open(cfg);
                try {
                    String path = dstSub.isEmpty() ? name : dstSub + "/" + name;
                    if (!store.exists(path)) return null;
                    long size = store.size(path);
                    return size < 0 ? null : size;
                } finally {
                    try { store.close(); } catch (Exception ignored) {}
                }
            }
            Uri tree = Uri.parse(rule.dest.ownerId);
            Uri dir = Transfer.SafOps.resolveOrCreateDir(c, tree, dstSub);
            if (dir == null) return null;
            Uri child = Transfer.SafOps.findChild(c, tree, dir, name);
            if (child == null) return null;
            long size = Transfer.SafOps.sizeOf(c, child);
            return size < 0 ? null : size;
        } catch (Throwable t) {
            return null;   // 查不到就当作不存在，宁可多传一次
        }
    }

    private static String joinPath(String base, String sub) {
        if (base == null || base.isEmpty()) return sub == null ? "" : sub;
        if (sub == null || sub.isEmpty()) return base;
        return base + "/" + sub;
    }

    private static String parentOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash <= 0 ? "" : relPath.substring(0, slash);
    }
}
