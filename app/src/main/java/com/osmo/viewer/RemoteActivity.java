package com.osmo.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储位置列表：本机目录与 NAS 服务器放在同一个列表里。
 *
 * 两者点击后都进同一个浏览页（RemoteGalleryActivity），只是本地走 SAF、
 * 远程走 RemoteStore —— 用户不需要记两套操作。
 */
public class RemoteActivity extends Activity {

    private static final int REQ_TREE = 200;

    /** 列表里的一行：本机目录或一台服务器。 */
    private static final class Slot {
        final OsvLibrary.Tree local;
        final RemoteConfig remote;

        Slot(OsvLibrary.Tree local) { this.local = local; this.remote = null; }
        Slot(RemoteConfig remote) { this.local = null; this.remote = remote; }
    }

    private final List<Slot> slots = new ArrayList<>();
    private final List<RemoteConfig> configs = new ArrayList<>();
    private ListView list;
    private View empty;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);

        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_add).setOnClickListener(this::showAddMenu);

        list.setOnItemClickListener((parent, view, position, id) -> {
            Slot slot = slots.get(position);
            if (slot.local != null) openLocal(slot.local);
            else openBrowser(slot.remote);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Slot slot = slots.get(position);
            if (slot.local != null) showLocalActions(slot.local);
            else showActions(slot.remote);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        RemoteConfigs.migrate(this);
        configs.clear();
        configs.addAll(RemoteConfigs.load(this));
        slots.clear();
        for (OsvLibrary.Tree tree : OsvLibrary.trees(this)) slots.add(new Slot(tree));
        for (RemoteConfig cfg : configs) slots.add(new Slot(cfg));
        adapter.notifyDataSetChanged();
        boolean none = slots.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    /** 「添加」弹两项：本机目录 / 远程服务器。 */
    private void showAddMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("添加本机目录").setOnMenuItemClickListener(i -> {
            pickLocalFolder();
            return true;
        });
        menu.getMenu().add("添加服务器（NAS）").setOnMenuItemClickListener(i -> {
            showEditor(null);
            return true;
        });
        menu.show();
    }

    private void pickLocalFolder() {
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null) return;
        android.net.Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        if (OsvLibrary.hasTree(this, tree)) {
            toast("这个目录已经添加过了");
            return;
        }
        OsvLibrary.addTree(this, tree, OsvLibrary.treeLabel(this, tree));
        reload();
    }

    private void openLocal(OsvLibrary.Tree tree) {
        Intent i = new Intent(this, RemoteGalleryActivity.class);
        i.putExtra(RemoteGalleryActivity.EXTRA_TREE_URI, tree.uri);
        startActivity(i);
    }

    private void showLocalActions(final OsvLibrary.Tree tree) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(tree.caption())
                .setItems(new String[]{"浏览", "移除目录"}, (d, which) -> {
                    if (which == 0) {
                        openLocal(tree);
                        return;
                    }
                    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("移除目录")
                            .setMessage(tree.caption() + "\n\n只从这个列表移除，不会删除文件。")
                            .setPositiveButton("移除", (dd, ww) -> {
                                OsvLibrary.removeTree(this, tree.uri);
                                try {
                                    getContentResolver().releasePersistableUriPermission(
                                            tree.asUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } catch (Exception ignored) {
                                }
                                reload();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .show();
    }

    private void openBrowser(RemoteConfig cfg) {
        Intent i = new Intent(this, RemoteGalleryActivity.class);
        i.putExtra(RemoteGalleryActivity.EXTRA_CONFIG_ID, cfg.id);
        startActivity(i);
    }

    private void showActions(RemoteConfig cfg) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(cfg.name)
                .setItems(new String[]{"测试连接", "编辑", "删除"}, (d, which) -> {
                    if (which == 0) testConnection(cfg, message ->
                            new AlertDialog.Builder(this,
                                    android.R.style.Theme_Material_Dialog_Alert)
                                    .setTitle("测试连接")
                                    .setMessage(message)
                                    .setPositiveButton("知道了", null)
                                    .show());
                    else if (which == 1) showEditor(cfg);
                    else {
                        RemoteConfigs.remove(this, cfg.id);
                        reload();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ 编辑

    private void showEditor(RemoteConfig existing) {
        final RemoteConfig cfg = existing != null ? existing : new RemoteConfig();
        final boolean creating = existing == null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        final EditText name = field(root, "名称", cfg.name);
        final TextView proto = new TextView(this);
        proto.setText("协议：" + protoLabel(cfg.protocol) + "（点一下切换）");
        proto.setTextColor(0xFFCCCCD4);
        proto.setTextSize(13);
        proto.setPadding(0, pad / 2, 0, pad / 2);
        proto.setOnClickListener(v -> {
            String next = cfg.protocol.equals(RemoteConfig.PROTO_SMB) ? RemoteConfig.PROTO_WEBDAV
                    : cfg.protocol.equals(RemoteConfig.PROTO_WEBDAV) ? RemoteConfig.PROTO_FTP
                    : RemoteConfig.PROTO_SMB;
            cfg.protocol = next;
            proto.setText("协议：" + protoLabel(next) + "（点一下切换）");
        });
        root.addView(proto);

        final EditText host = field(root, "地址（IP 或域名）", cfg.host);
        final EditText port = field(root, "端口（留空用默认）",
                cfg.port > 0 ? String.valueOf(cfg.port) : "");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText user = field(root, "用户名（可空）", cfg.user);
        final EditText pass = field(root, "密码（可空）", cfg.password);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText path = field(root, "路径", cfg.path);

        final android.widget.CheckBox show = new android.widget.CheckBox(this);
        show.setText("在相册首页展示（与本机素材合并）");
        show.setTextColor(0xFFCCCCD4);
        show.setTextSize(13);
        show.setChecked(cfg.showInGallery);
        show.setPadding(0, pad / 2, 0, 0);
        root.addView(show);
        TextView hint = new TextView(this);
        hint.setText("SMB 写「共享名/子目录」，如 videos/2026-09；\nWebDAV/FTP 写起始目录，如 dav/osmo。");
        hint.setTextColor(0xFF7E7E8A);
        hint.setTextSize(11);
        hint.setPadding(0, pad / 4, 0, 0);
        root.addView(hint);

        // 测试结果就地显示，不另开窗口、也不关闭编辑器
        final TextView result = new TextView(this);
        result.setTextColor(0xFF9FD0A8);
        result.setTextSize(11.5f);
        result.setPadding(0, pad / 2, 0, pad / 2);
        result.setTextIsSelectable(true);
        root.addView(result);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(creating ? "添加服务器" : "编辑服务器")
                .setView(scroll)
                .setNeutralButton("测试", null)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        // 关键：AlertDialog 的按钮默认点一下就关闭，这里接管点击以保持对话框打开
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            RemoteConfig probe = readEditor(cfg, name, host, port, user, pass, path,
                    show.isChecked());
            if (probe.host.isEmpty()) {
                showResult(result, "先填地址再测试。", false);
                return;
            }
            showResult(result, "正在连接 " + probe.host + " …", true);
            testConnection(probe, text -> {
                if (isFinishing() || !dialog.isShowing()) return;
                showResult(result, text, text.startsWith("连接成功"));
            });
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            RemoteConfig edited = readEditor(cfg, name, host, port, user, pass, path,
                    show.isChecked());
            if (edited.host.isEmpty()) {
                showResult(result, "地址不能为空。", false);
                return;
            }
            edited.id = cfg.id;
            RemoteConfigs.upsert(this, edited);
            reload();
            dialog.dismiss();
        });
    }

    /** 把界面上的字段读成配置对象（不改动原对象）。 */
    private RemoteConfig readEditor(RemoteConfig base, EditText name, EditText host,
                                     EditText port, EditText user, EditText pass,
                                     EditText path) {
        return readEditor(base, name, host, port, user, pass, path, base.showInGallery);
    }

    private RemoteConfig readEditor(RemoteConfig base, EditText name, EditText host,
                                     EditText port, EditText user, EditText pass,
                                     EditText path, boolean showInGallery) {
        RemoteConfig cfg = new RemoteConfig();
        cfg.id = base.id;
        cfg.protocol = base.protocol;
        cfg.https = base.https;
        cfg.showInGallery = showInGallery;
        cfg.name = text(name, "未命名");
        cfg.host = text(host, "");
        cfg.user = text(user, "");
        cfg.password = text(pass, "");
        cfg.path = text(path, "");
        String p = text(port, "");
        cfg.port = p.isEmpty() ? 0 : safeInt(p);
        return cfg;
    }

    private void showResult(TextView view, String message, boolean ok) {
        view.setTextColor(ok ? 0xFF9FD0A8 : 0xFFE8A0A0);
        view.setText(message);
    }

    /** 连一次、列一次根目录，把结果交回调用方显示（不弹窗、不关对话框）。 */
    private void testConnection(final RemoteConfig cfg,
                                final java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String result;
            long started = System.currentTimeMillis();
            try {
                RemoteStore store = RemoteStore.open(cfg);
                try {
                    List<RemoteStore.Entry> entries = store.list("");
                    int dirs = 0, osv = 0;
                    for (RemoteStore.Entry e : entries) {
                        if (e.dir) dirs++;
                        else if (RemoteStore.isVideo(e.name)) osv++;
                    }
                    long cost = System.currentTimeMillis() - started;
                    result = "连接成功（" + cost + "ms）\n根目录： " + dirs + " 个文件夹， "
                            + osv + " 个 .OSV，共 " + entries.size() + " 项";
                } finally {
                    try { store.close(); } catch (Exception ignored) {}
                }
            } catch (Throwable t) {
                result = explain(cfg, t);
            }
            final String text = result;
            runOnUiThread(() -> done.accept(text));
        }, "remote-test").start();
    }

    /** 把异常翻译成「下一步该改什么」。 */
    private String explain(RemoteConfig cfg, Throwable t) {
        String msg = t.getMessage() == null ? t.toString() : t.getMessage();
        StringBuilder sb = new StringBuilder();
        sb.append("失败： ").append(msg).append("\n\n");

        String lower = msg.toLowerCase(java.util.Locale.US);
        if (t instanceof java.net.SocketTimeoutException) {
            sb.append("提示：地址/端口连不上。请确认手机和 NAS 在同一网段——\n")
                    .append("NAS 如果有多个 IP，要用本网段的那个（如 192.168.31.x 而不是 192.168.1.x）。");
            if (cfg.protocol.equals(RemoteConfig.PROTO_SMB)) {
                sb.append("\nSMB 还要确认共享已开启、端口 445 未被墙。");
            } else if (cfg.protocol.equals(RemoteConfig.PROTO_FTP)) {
                sb.append("\nFTP 默认端口 21，注意很多 NAS 默认只开 SFTP。");
            }
        } else if (t instanceof java.net.UnknownHostException) {
            sb.append("提示：域名解析不了，请直接填 IP。");
        } else if (lower.contains("认证") || lower.contains("denied") || lower.contains("logon")) {
            sb.append("提示：能连上但认证没过，检查用户名/密码和该用户的访问权限。");
        } else if (lower.contains("404") || lower.contains("不存在")) {
            sb.append("提示：路径不对。SMB 的第一段必须是共享名（如 videos/2026-09）；")
                    .append("WebDAV 要填服务器上的目录（如 dav/osmo）。");
        } else if (lower.contains("code 405") || lower.contains("not allowed")) {
            sb.append("提示：服务器拒绝了 PROPFIND，可能没有开启 WebDAV。");
        }
        return sb.toString();
    }

    private EditText field(LinearLayout parent, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFF9A9AA6);
        t.setTextSize(12);
        t.setPadding(0, Math.round(10 * getResources().getDisplayMetrics().density), 0, 2);
        parent.addView(t);

        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextSize(14);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private static String text(EditText e, String fallback) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String protoLabel(String protocol) {
        switch (protocol) {
            case RemoteConfig.PROTO_WEBDAV:
                return "WebDAV（http/https）";
            case RemoteConfig.PROTO_FTP:
                return "FTP（明文，仅兼容）";
            default:
                return "SMB2/3（推荐）";
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return slots.size();
        }

        @Override
        public Object getItem(int position) {
            return slots.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(RemoteActivity.this);
            Slot slot = slots.get(position);
            if (slot.local != null) {
                tv.setText(slot.local.caption() + "\n本地目录 · 点击浏览");
            } else {
                RemoteConfig c = slot.remote;
                tv.setText(c.label() + "\n相册首页：" + (c.showInGallery ? "显示 ✓" : "不显示"));
            }
            tv.setTextColor(0xFFEDEDF2);
            tv.setTextSize(14);
            tv.setPadding(Math.round(14 * getResources().getDisplayMetrics().density),
                    Math.round(14 * getResources().getDisplayMetrics().density),
                    Math.round(14 * getResources().getDisplayMetrics().density),
                    Math.round(14 * getResources().getDisplayMetrics().density));
            return tv;
        }
    }
}
