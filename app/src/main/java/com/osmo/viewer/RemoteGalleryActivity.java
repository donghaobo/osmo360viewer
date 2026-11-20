package com.osmo.viewer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 远程素材浏览：从配置的根目录递归扫一遍，按日期分组展示（复用 GalleryView）。
 * 缩略图按需从远程取关键帧，并缓存在本地（不缓存视频本体）。
 */
public class RemoteGalleryActivity extends Activity implements GalleryView.Listener {

    public static final String EXTRA_CONFIG_ID = "config_id";
    /** 传了这个就按本地目录浏览（与 NAS 共用同一套网格/缩略图/排序）。 */
    public static final String EXTRA_TREE_URI = "tree_uri";

    private final List<OsvItem> items = new ArrayList<>();
    private final Set<String> thumbRequested = Collections.synchronizedSet(new HashSet<String>());
    private final Handler main = new Handler(Looper.getMainLooper());

    private GalleryView gallery;
    private TextView status;
    private View empty;
    private ThumbLoader thumbs;
    private RemoteConfig config;
    /** 非空表示这次浏览的是本地目录，走 SAF 而不是 RemoteStore。 */
    private Uri localTree;
    private Thread worker;
    private volatile boolean stopped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.init(this);
        setContentView(R.layout.activity_remote_gallery);

        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        gallery = findViewById(R.id.gallery);
        thumbs = new ThumbLoader(this);
        gallery.setListener(this);
        gallery.setColumns(getSharedPreferences("gallery", MODE_PRIVATE)
                .getInt("columns", GalleryView.DEFAULT_COLUMNS));

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_menu).setOnClickListener(this::showMenu);

        String tree = getIntent().getStringExtra(EXTRA_TREE_URI);
        if (tree != null && !tree.isEmpty()) {
            localTree = Uri.parse(tree);
            ((TextView) findViewById(R.id.title)).setText(OsvLibrary.treeLabel(this, localTree));
            scanLocal();
            return;
        }
        config = RemoteConfigs.find(this, getIntent().getStringExtra(EXTRA_CONFIG_ID));
        if (config == null) {
            status.setText("服务器配置不存在");
            return;
        }
        ((TextView) findViewById(R.id.title)).setText(config.name);
        scan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopped = true;
        if (worker != null) worker.interrupt();
        thumbs.close();
    }

    // ------------------------------------------------------------------ 扫描

    private void scan() {
        final RemoteConfig cfg = config;
        status.setText("正在连接 " + cfg.protocol.toUpperCase() + " " + cfg.host + " …");
        worker = new Thread(() -> {
            RemoteStore store = null;
            List<OsvItem> found = new ArrayList<>();
            String error = null;
            try {
                store = RemoteStore.open(cfg);
                // 与首页合用的是同一套收集逻辑，深度/上限/日期推断保持一致
                found = RemoteScanner.scan(cfg, store, () -> stopped);
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
                Diag.w("OsvRemote", "扫描失败", t);
            } finally {
                if (store != null) {
                    try { store.close(); } catch (Exception ignored) {}
                }
            }
            final String err = error;
            final List<OsvItem> result = found;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (err != null) {
                    status.setText("连接失败：" + err);
                    empty.setVisibility(View.VISIBLE);
                } else {
                    items.clear();
                    items.addAll(result);
                    Collections.sort(items, (a, b) -> {
                        int cmp = Long.compare(b.dateMillis, a.dateMillis);
                        return cmp != 0 ? cmp : b.name.compareToIgnoreCase(a.name);
                    });
                    gallery.setItems(items);
                    boolean none = items.isEmpty();
                    gallery.setVisibility(none ? View.GONE : View.VISIBLE);
                    empty.setVisibility(none ? View.VISIBLE : View.GONE);
                    updateStatus();
                }
            });
        }, "osv-remote-scan");
        worker.start();
    }

    /** 本地目录：SAF 直接扫，不经过任何网络协议。 */
    private void scanLocal() {
        final Uri tree = localTree;
        status.setText("正在扫描本地目录…");
        worker = new Thread(() -> {
            List<OsvItem> found = new ArrayList<>();
            try {
                found = OsvLibrary.scanTree(this, tree);
            } catch (Throwable t) {
                Diag.w("OsvRemote", "本地目录扫描失败", t);
            }
            final List<OsvItem> result = found;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                items.clear();
                items.addAll(result);
                Collections.sort(items, (a, b) -> {
                    int cmp = Long.compare(b.dateMillis, a.dateMillis);
                    return cmp != 0 ? cmp : b.name.compareToIgnoreCase(a.name);
                });
                gallery.setItems(items);
                boolean none = items.isEmpty();
                gallery.setVisibility(none ? View.GONE : View.VISIBLE);
                empty.setVisibility(none ? View.VISIBLE : View.GONE);
                if (none) status.setText("这个目录里没有 .OSV 文件");
                else updateStatus();
            });
        }, "osv-local-scan");
        worker.start();
    }

    private void updateStatus() {
        int mode = gallery.getMode();
        String grouping = mode == ItemDate.MODE_YEAR ? "按年"
                : mode == ItemDate.MODE_MONTH ? "按月" : "按天";
        if (localTree != null) {
            status.setText(items.size() + " 个本地视频 · 每行 " + gallery.getColumns()
                    + " 个 · " + grouping + "分组");
            return;
        }
        status.setText(items.size() + " 个远程视频 · 每行 " + gallery.getColumns()
                + " 个 · " + grouping + "分组 · 缩略图本地缓存");
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("重新扫描").setOnMenuItemClickListener(i -> {
            if (localTree != null) scanLocal();
            else scan();
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

    // ------------------------------------------------------------------ 回调

    @Override
    public void onOpen(OsvItem item) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.setData(item.uri);
        i.putExtra(PlayerActivity.EXTRA_NAME, item.displayName());
        startActivity(i);
    }

    @Override
    public void onLongPress(final OsvItem item) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(item.displayName())
                .setItems(new String[]{"复制到…", "删除…", "素材信息"}, (d, which) -> {
                    if (which == 0) {
                        ItemActions.copy(this, item);
                    } else if (which == 1) {
                        ItemActions.delete(this, item, msg -> {
                            if (localTree != null) scanLocal();
                            else scan();
                        });
                    } else {
                        showInfo(item);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showInfo(OsvItem item) {
        String where = localTree != null
                ? "\n位置：本机 · " + OsvLibrary.treeLabel(this, localTree)
                : "\n路径：" + RemoteConfigs.pathOf(item.uri.toString());
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("素材信息")
                .setMessage("文件：" + item.name
                        + "\n日期：" + item.dateCaption()
                        + "\n大小：" + OsvLibrary.humanSize(item.size)
                        + where
                        + (item.placesCaption().isEmpty() ? ""
                        : "\n" + item.placesCaption()))
                .setPositiveButton("关闭", null)
                .show();
    }

    @Override
    public void onColumnsChanged(int columns) {
        getSharedPreferences("gallery", MODE_PRIVATE).edit()
                .putInt("columns", columns).apply();
        updateStatus();
    }

    @Override
    public void onNeedThumb(final OsvItem item, int sizePx) {
        if (!thumbRequested.add(item.uri.toString())) return;
        thumbs.load(item, (it, bmp) -> {
            if (bmp == null) thumbRequested.remove(item.uri.toString());
            main.post(gallery::refresh);
        });
    }
}
