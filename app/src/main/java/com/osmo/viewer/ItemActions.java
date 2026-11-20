package com.osmo.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 长按一个素材能做的事：复制到别处、删除某个位置上的文件。
 *
 * 复制只负责把任务丢给 {@link TransferService}（真正的传输在后台），
 * 这里只处理「复制到哪」和「删哪一份」的选择。
 */
public final class ItemActions {

    /** 删除/复制完成后的回调（一般是刷新相册）。 */
    public interface Done {
        void onFinished(String message);
    }

    private ItemActions() {}

    // ------------------------------------------------------------------ 复制

    public static void copy(final Activity a, final OsvItem item) {
        final List<OsvLibrary.Tree> trees = OsvLibrary.trees(a);
        final List<RemoteConfig> remotes = new ArrayList<>(RemoteConfigs.load(a));

        List<String> labels = new ArrayList<>();
        for (OsvLibrary.Tree t : trees) labels.add(t.caption() + "（本机）");
        for (RemoteConfig cfg : remotes) labels.add(RemoteScanner.labelOf(cfg));
        if (labels.isEmpty()) {
            Toast.makeText(a, "还没有添加本机目录或 NAS，先去「存储位置」里添加",
                    Toast.LENGTH_LONG).show();
            return;
        }
        labels.add("选择其它本机目录…");

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("把 " + item.displayName() + " 复制到")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == labels.size() - 1) {
                        Toast.makeText(a, "请先在「存储位置」里添加该目录",
                                Toast.LENGTH_LONG).show();
                    } else if (which < trees.size()) {
                        askSubDir(a, "本机目录 " + trees.get(which).caption(), sub -> {
                            OsvLibrary.Tree t = trees.get(which);
                            enqueue(a, item, sourceType(item), sourceOwner(item),
                                    sourcePath(item),
                                    TransferJob.LOCAL, t.uri, sub);
                        });
                    } else {
                        RemoteConfig cfg = remotes.get(which - trees.size());
                        askSubDir(a, RemoteScanner.labelOf(cfg), sub ->
                                enqueue(a, item, sourceType(item), sourceOwner(item),
                                        sourcePath(item),
                                        TransferJob.REMOTE, cfg.id, sub));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 远端素材复制时，源也要按远程配置传。 */
    private static int sourceType(OsvItem item) {
        return item.remote ? TransferJob.REMOTE : TransferJob.LOCAL;
    }

    private static String sourceOwner(OsvItem item) {
        OsvItem.Place p = item.places.isEmpty() ? null : item.places.get(0);
        if (p == null) return item.uri.toString();
        return item.remote ? p.ownerId : p.path;
    }

    private static String sourcePath(OsvItem item) {
        OsvItem.Place p = item.places.isEmpty() ? null : item.places.get(0);
        return p == null ? "" : p.path;
    }

    private static void askSubDir(Activity a, String targetLabel, OnText done) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText edit = new EditText(a);
        edit.setHint("子目录（留空 = 根目录）");
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(edit);

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("复制到 " + targetLabel)
                .setView(box)
                .setPositiveButton("开始复制", (d, w) -> {
                    String sub = edit.getText().toString().trim();
                    while (sub.startsWith("/")) sub = sub.substring(1);
                    while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
                    done.onText(sub);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface OnText {
        void onText(String text);
    }

    private static void enqueue(Activity a, OsvItem item,
                                int srcType, String srcOwner, String srcPath,
                                int dstType, String dstOwner, String dstPath) {
        TransferJob job = new TransferJob();
        job.name = item.name;
        job.size = item.size;
        job.srcType = srcType;
        job.srcOwner = srcOwner;
        job.srcPath = srcPath;
        job.dstType = dstType;
        job.dstOwner = dstOwner;
        job.dstPath = dstPath;
        job.conflict = 2;   // 默认改名，避免覆盖用户已有的文件
        TransferService.enqueue(a, job);
        Toast.makeText(a, "已加入传输队列，可在「传输任务」里看进度",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ 删除

    public static void delete(final Activity a, final OsvItem item, final Done done) {
        if (item.places.isEmpty()) {
            Toast.makeText(a, "没有可删除的位置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.places.size() == 1) {
            confirmDelete(a, item, item.places, done);
            return;
        }

        // 同名多份：默认全不选，用户自己勾
        final boolean[] checked = new boolean[item.places.size()];
        String[] labels = new String[item.places.size()];
        for (int i = 0; i < item.places.size(); i++) {
            OsvItem.Place p = item.places.get(i);
            labels[i] = p.caption();
        }
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("删除哪些位置的文件？")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("下一步", (d, w) -> {
                    List<OsvItem.Place> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) picked.add(item.places.get(i));
                    }
                    if (picked.isEmpty()) {
                        Toast.makeText(a, "没有勾选任何位置", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmDelete(a, item, picked, done);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void confirmDelete(final Activity a, final OsvItem item,
                                      final List<OsvItem.Place> places, final Done done) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(item.name).append("\n\n");
        for (OsvItem.Place p : places) sb.append("· ").append(p.caption()).append('\n');
        sb.append("\n删除后无法恢复，确定吗？");

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("确认删除")
                .setMessage(sb.toString())
                .setPositiveButton("删除", (d, w) -> runDelete(a, places, done))
                .setNegativeButton("取消", null)
                .show();
    }

    private static void runDelete(final Activity a, final List<OsvItem.Place> places,
                                  final Done done) {
        new Thread(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (OsvItem.Place p : places) {
                try {
                    if (p.isRemote()) {
                        RemoteConfig cfg = RemoteConfigs.find(a, p.ownerId);
                        if (cfg == null) throw new java.io.IOException("配置已删除");
                        RemoteStore store = RemoteStore.open(cfg);
                        try {
                            store.delete(p.path);
                        } finally {
                            try { store.close(); } catch (Exception ignored) {}
                        }
                    } else {
                        if (!Transfer.SafOps.delete(a, Uri.parse(p.path))) {
                            throw new java.io.IOException("系统拒绝删除");
                        }
                    }
                    ok++;
                } catch (Throwable t) {
                    failed.add(p.label + "：" + (t.getMessage() == null ? t.toString() : t.getMessage()));
                    Diag.w("Delete", "删除失败 " + p.path, t);
                }
            }
            final String msg = failed.isEmpty()
                    ? "已删除 " + ok + " 个文件"
                    : "删除 " + ok + " 个，失败 " + failed.size() + " 个\n" + String.join("\n", failed);
            a.runOnUiThread(() -> {
                Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                if (done != null) done.onFinished(msg);
            });
        }, "osv-delete").start();
    }
}
