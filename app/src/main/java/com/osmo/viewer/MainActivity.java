package com.osmo.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相册页：按日期分组的网格，缩放即切换分组粒度（天 / 月 / 年）。
 *
 * 数据来自本机多个目录 + 勾选了「首页展示」的 NAS，按文件名去重（本机优先）。
 * 扫描分两段回调：本机先出，NAS 扫完再增量补进来。
 */
public class MainActivity extends Activity implements GalleryView.Listener {

    private static final int REQ_TREE = 100;
    private static final int REQ_FILES = 101;
    private static final String PREFS = "gallery";
    private static final String KEY_COLUMNS = "columns";

    private final List<OsvItem> items = new ArrayList<>();
    private GalleryView gallery;
    private TextView status;
    private View empty;
    private ThumbLoader thumbs;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService probePool = Executors.newFixedThreadPool(2);
    private final Set<String> thumbRequested = Collections.synchronizedSet(new HashSet<String>());
    private int scanSeq;
    private GallerySources.Cancelable scanHandle;
    private String sourceNote = "";
    private final Runnable rebuildSoon = this::applyItems;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashCatcher.install(this);   // Application 已装过，这里只是兜底
        Diag.init(this);
        Diag.i("OsvLib", "相册页启动");

        boolean inflated = true;
        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            // 布局加载失败也要让人看到原因，而不是直接闪退
            inflated = false;
            Diag.e("OsvLib", "布局加载失败", t);
            CrashCatcher.record(this, t, "布局加载");
            showFatal("界面加载失败：" + t, t);
        }
        if (!inflated) return;

        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        thumbs = new ThumbLoader(this);

        gallery = findViewById(R.id.gallery);
        gallery.setListener(this);
        gallery.setColumns(prefs().getInt(KEY_COLUMNS, GalleryView.DEFAULT_COLUMNS));

        findViewById(R.id.btn_menu).setOnClickListener(this::showMenu);

        Button tree = findViewById(R.id.btn_tree);
        tree.setOnClickListener(v -> pickFolder());

        Button file = findViewById(R.id.btn_file);
        file.setOnClickListener(v -> pickFiles());

        updateStatus();
        maybeShowLastCrash();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 选择来源

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("系统没有可用的文件选择器");
        }
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_FILES);
        } catch (Exception e) {
            toast("系统没有可用的文件选择器");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanSeq++;
        if (scanHandle != null) scanHandle.cancel();
        probePool.shutdownNow();
        thumbs.close();
        main.removeCallbacks(rebuildSoon);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_TREE) {
            Uri tree = data.getData();
            if (tree == null) return;
            takePermission(tree, true);
            if (OsvLibrary.hasTree(this, tree)) {
                toast("这个目录已经添加过了");
                return;
            }
            // 追加而不是替换：可以有多个本地目录，合并展示
            OsvLibrary.addTree(this, tree, OsvLibrary.treeLabel(this, tree));
            toast("已添加目录：" + OsvLibrary.treeLabel(this, tree));
            reload();
        } else if (requestCode == REQ_FILES) {
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    picked.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            for (Uri u : picked) {
                takePermission(u, false);
                OsvLibrary.addFile(this, u);
            }
            if (!picked.isEmpty()) reload();
        }
    }

    private void takePermission(Uri uri, boolean isTree) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // 有些 provider 不支持持久化授权；失败时下次启动会提示目录不可用
        }
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 重新扫描所有来源。同名的旧条目会带上已经探测到的元信息和缩略图，
     * 避免刷新一次整屏闪成「读取中…」。
     */
    private void reload() {
        if (scanHandle != null) scanHandle.cancel();
        final int seq = ++scanSeq;
        final Map<String, OsvItem> known = new HashMap<>();
        for (OsvItem it : items) known.put(it.uri.toString(), it);

        status.setText("扫描中…");
        scanHandle = GallerySources.load(this, (found, done, note) -> {
            if (seq != scanSeq) return;
            for (OsvItem it : found) {
                OsvItem prev = known.get(it.uri.toString());
                if (prev != null) copyProbeState(prev, it);
            }
            if (note != null) sourceNote = note;
            items.clear();
            items.addAll(found);
            sortByDate();
            applyItems();
            if (!done) status.setText("已显示 " + items.size() + " 个 · 正在扫描 NAS…");
            probeAll(seq);
        });
    }

    private static void copyProbeState(OsvItem prev, OsvItem target) {
        target.probed = prev.probed;
        target.probeFailed = prev.probeFailed;
        target.durationMs = prev.durationMs;
        target.width = prev.width;
        target.height = prev.height;
        target.videoTracks = prev.videoTracks;
        target.thumb = prev.thumb;
        // 上次已用元数据校正过日期的话，保留更可信的那个
        if (prev.dateSource == ItemDate.SOURCE_META) {
            target.dateMillis = prev.dateMillis;
            target.dateSource = prev.dateSource;
        }
    }

    /** 新的在前；同一天按文件名倒序（编号越大越新）。 */
    private void sortByDate() {
        Collections.sort(items, (a, b) -> {
            int cmp = Long.compare(b.dateMillis, a.dateMillis);
            return cmp != 0 ? cmp : b.name.compareToIgnoreCase(a.name);
        });
    }

    private void applyItems() {
        gallery.setItems(items);
        boolean isEmpty = items.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        gallery.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        updateStatus();
    }

    private void updateStatus() {
        if (items.isEmpty()) {
            status.setText("选择一个包含 .OSV 文件的文件夹");
            return;
        }
        int mode = gallery.getMode();
        String grouping = mode == ItemDate.MODE_YEAR ? "按年"
                : mode == ItemDate.MODE_MONTH ? "按月" : "按天";
        int dup = 0;
        for (OsvItem it : items) if (it.places.size() > 1) dup++;
        status.setText(items.size() + " 个视频 · 每行 " + gallery.getColumns()
                + " 个 · " + grouping + "分组"
                + (dup > 0 ? " · " + dup + " 个有多个副本" : ""));
    }

    /** 只探测本机文件：远端每探测一次都要开连接，几十条一起会把网卡堵住。 */
    private void probeAll(int seq) {
        for (OsvItem it : new ArrayList<>(items)) {
            if (it.probed || it.remote) continue;
            probePool.execute(() -> {
                MediaProbe.Result r = MediaProbe.probe(MainActivity.this, it.uri);
                main.post(() -> {
                    if (seq != scanSeq) return;
                    it.probed = true;
                    it.probeFailed = !r.ok;
                    it.durationMs = r.durationMs;
                    it.width = r.width;
                    it.height = r.height;
                    it.videoTracks = r.videoTracks;
                    // 文件名里没有日期时，用元数据里的拍摄时间（比文件系统时间准）
                    if (r.dateMillis > 0 && it.dateSource != ItemDate.SOURCE_NAME) {
                        it.dateMillis = r.dateMillis;
                        it.dateSource = ItemDate.SOURCE_META;
                        main.removeCallbacks(rebuildSoon);
                        main.postDelayed(rebuildSoon, 400);   // 合并多次校正，只重排一次
                    }
                    gallery.refresh();
                });
            });
        }
    }

    // ------------------------------------------------------------------ 菜单

    private void showMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        boolean logging = Diag.isEnabled();
        menu.getMenu().add(logging ? "关闭诊断日志 ✓" : "开启诊断日志")
                .setOnMenuItemClickListener(i -> {
                    Diag.setEnabled(this, !logging);
                    toast(Diag.isEnabled() ? "诊断日志已开启" : "诊断日志已关闭");
                    return true;
                });
        menu.getMenu().add("添加本地文件夹").setOnMenuItemClickListener(i -> {
            pickFolder();
            return true;
        });
        menu.getMenu().add("存储位置（本机 / NAS）").setOnMenuItemClickListener(i -> {
            startActivity(new android.content.Intent(this, RemoteActivity.class));
            return true;
        });
        menu.getMenu().add("重新扫描").setOnMenuItemClickListener(i -> {
            reload();
            return true;
        });
        menu.getMenu().add("同步策略").setOnMenuItemClickListener(i -> {
            startActivity(new android.content.Intent(this, SyncActivity.class));
            return true;
        });
        menu.getMenu().add("传输任务").setOnMenuItemClickListener(i -> {
            startActivity(new android.content.Intent(this, TransferActivity.class));
            return true;
        });
        menu.getMenu().add("导出日志").setOnMenuItemClickListener(i -> {
            Diag.exportAndShow(this, "相册页手动导出");
            return true;
        });
        menu.getMenu().add("每行 2 个").setOnMenuItemClickListener(i -> {
            gallery.setColumns(2);
            return true;
        });
        menu.getMenu().add("每行 4 个").setOnMenuItemClickListener(i -> {
            gallery.setColumns(4);
            return true;
        });
        menu.getMenu().add("每行 8 个（按月）").setOnMenuItemClickListener(i -> {
            gallery.setColumns(8);
            return true;
        });
        menu.getMenu().add("每行 16 个（按年）").setOnMenuItemClickListener(i -> {
            gallery.setColumns(16);
            return true;
        });
        menu.show();
    }

    // ------------------------------------------------------------------ GalleryView 回调

    @Override
    public void onOpen(OsvItem item) {
        Diag.i("OsvLib", "打开 " + item.name + " (远程=" + item.remote + ")"
                + " 副本数=" + item.places.size());
        if (item.probeFailed) {
            toast("无法解析这个文件，可能不是 Osmo 360 的 .OSV");
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(item.uri);
        i.putExtra(PlayerActivity.EXTRA_NAME, item.displayName());
        startActivity(i);
    }

    @Override
    public void onLongPress(final OsvItem item) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(item.displayName())
                .setItems(new String[]{"复制到…", "删除…", "素材信息"}, (d, which) -> {
                    if (which == 0) {
                        ItemActions.copy(this, item);
                    } else if (which == 1) {
                        ItemActions.delete(this, item, msg -> reload());
                    } else {
                        showItemInfo(item);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showItemInfo(OsvItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(item.name).append('\n');
        sb.append("日期：").append(item.dateCaption()).append('\n');
        if (item.size > 0) sb.append("大小：").append(OsvLibrary.humanSize(item.size)).append('\n');
        if (item.probed && !item.probeFailed) {
            sb.append("时长：").append(OsvItem.fmtDuration(item.durationMs)).append('\n');
            sb.append("分辨率：").append(item.width).append('×').append(item.height).append('\n');
            sb.append("视频轨：").append(item.videoTracks)
                    .append(item.videoTracks >= 2 ? "（双鱼眼）" : "").append('\n');
        }
        String places = item.placesCaption();
        if (!places.isEmpty()) sb.append(places).append('\n');

        boolean picked = !item.remote && OsvLibrary.pickedFiles(this).contains(item.uri);
        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("素材信息")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null);
        if (picked) {
            b.setNegativeButton("从列表移除", (d, w) -> {
                OsvLibrary.removeFile(MainActivity.this, item.uri);
                reload();
            });
        }
        b.show();
    }

    /** 上次闪退的记录：有就弹出来，可导出。 */
    private void maybeShowLastCrash() {
        String crash = CrashCatcher.read(this);
        if (crash == null) return;
        String head = crash.length() > 1200 ? crash.substring(0, 1200) + "…" : crash;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("上次运行时闪退")
                .setMessage(head)
                .setPositiveButton("导出", (d, w) -> Diag.exportAndShow(this, "闪退记录"))
                .setNeutralButton("删除", (d, w) -> CrashCatcher.clear(this))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 启动阶段就失败时的兜底界面。 */
    private void showFatal(String message, Throwable t) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0E);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("OSV 播放器启动失败");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText(message + "\n\n点下面的按钮导出日志。");
        detail.setTextColor(0xFFBBBBC4);
        detail.setTextSize(12);
        detail.setPadding(0, pad / 2, 0, pad / 2);
        detail.setTextIsSelectable(true);
        root.addView(detail);

        Button export = new Button(this);
        export.setText("导出日志");
        export.setOnClickListener(v -> Diag.exportAndShow(MainActivity.this, "启动失败"));
        root.addView(export);

        setContentView(root);
    }

    @Override
    public void onColumnsChanged(int columns) {
        prefs().edit().putInt(KEY_COLUMNS, columns).apply();
        updateStatus();
    }

    @Override
    public void onNeedThumb(final OsvItem item, int sizePx) {
        if (!thumbRequested.add(item.uri.toString())) return;
        thumbs.load(item, (it, bmp) -> {
            if (bmp == null) thumbRequested.remove(item.uri.toString());   // 失败允许重试
            main.post(gallery::refresh);
        });
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
