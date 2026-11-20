package com.osmo.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步策略列表：单条执行、一键全部、添加/编辑/删除。
 *
 * 执行放在普通后台线程 + 进度对话框里（v1 不接前台 Service，
 * 切出应用期间不保证继续，见 docs 里的已知限制）。
 */
public class SyncActivity extends Activity {

    private final List<SyncRule> rules = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListView list;
    private TextView status;
    private View empty;
    private Adapter adapter;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);

        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_add).setOnClickListener(v -> showEditor(null));
        findViewById(R.id.btn_run_all).setOnClickListener(v -> runAll());

        list.setOnItemClickListener((parent, view, position, id) -> runRule(rules.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showRuleActions(rules.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        rules.clear();
        rules.addAll(SyncRules.load(this));
        adapter.notifyDataSetChanged();
        boolean none = rules.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        status.setText(none ? "还没有策略" : "共 " + rules.size() + " 条策略 · 点一条立即执行");
    }

    // ------------------------------------------------------------------ 执行

    private void runRule(SyncRule rule) {
        runRules(java.util.Collections.singletonList(rule));
    }

    private void runAll() {
        List<SyncRule> all = SyncRules.load(this);
        if (all.isEmpty()) {
            Toast.makeText(this, "还没有策略", Toast.LENGTH_SHORT).show();
            return;
        }
        runRules(all);
    }

    /** 顺序执行若干策略；进度对话框里可取消。 */
    private void runRules(final List<SyncRule> todo) {
        if (running) {
            Toast.makeText(this, "已经在同步中", Toast.LENGTH_SHORT).show();
            return;
        }
        running = true;

        final TextView message = new TextView(this);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        message.setPadding(pad, pad, pad, pad);
        message.setTextColor(0xFFDDDDE4);
        message.setTextSize(13);
        message.setText("准备同步…");

        final boolean[] cancel = new boolean[1];
        final AlertDialog dialog = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("同步中")
                .setView(message)
                .setNegativeButton("取消", (d, w) -> cancel[0] = true)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            int totalCopied = 0, totalSkipped = 0, totalFailed = 0;
            StringBuilder failedRules = new StringBuilder();
            boolean cancelled = false;
            for (int i = 0; i < todo.size(); i++) {
                final SyncRule rule = todo.get(i);
                final int index = i + 1;
                final int count = todo.size();
                if (cancel[0]) {
                    cancelled = true;
                    break;
                }
                try {
                    SyncEngine.Result r = SyncEngine.run(this, rule, new SyncEngine.Progress() {
                        @Override
                        public void onStep(String text) {
                            ui.post(() -> message.setText("(" + index + "/" + count + ") "
                                    + rule.name + "\n" + text));
                        }

                        @Override
                        public boolean onBytes(long done, long total) {
                            if (done % (4 * 1024 * 1024) < 256 * 1024) {
                                ui.post(() -> message.setText("(" + index + "/" + count + ") "
                                        + rule.name + "\n已传 "
                                        + OsvLibrary.humanSize(done) + " / "
                                        + OsvLibrary.humanSize(total)));
                            }
                            return !cancel[0];
                        }
                    });
                    totalCopied += r.copied;
                    totalSkipped += r.skipped;
                    totalFailed += r.failed;
                    cancelled |= r.cancelled;
                    rule.lastRunMs = System.currentTimeMillis();
                    rule.lastResult = r.summary();
                    SyncRules.upsert(this, rule);
                    if (!r.errors.isEmpty()) {
                        failedRules.append(rule.name).append("：")
                                .append(r.errors.get(0)).append('\n');
                    }
                } catch (Throwable t) {
                    totalFailed++;
                    failedRules.append(rule.name).append("：")
                            .append(t.getMessage() == null ? t.toString() : t.getMessage())
                            .append('\n');
                    Diag.w("Sync", "策略执行失败 " + rule.name, t);
                }
            }

            final int copied = totalCopied, skipped = totalSkipped, failed = totalFailed;
            final boolean wasCancelled = cancelled;
            final String extra = failedRules.toString();
            ui.post(() -> {
                running = false;
                if (!dialog.isShowing()) return;
                dialog.dismiss();
                String text = "复制 " + copied + " · 跳过 " + skipped + " · 失败 " + failed
                        + (wasCancelled ? " · 已取消" : "")
                        + (extra.isEmpty() ? "" : "\n\n" + extra);
                new AlertDialog.Builder(SyncActivity.this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("同步完成")
                        .setMessage(text)
                        .setPositiveButton("知道了", null)
                        .show();
                reload();
            });
        }, "osv-sync").start();
    }

    private void showRuleActions(final SyncRule rule) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(rule.name)
                .setItems(new String[]{"立即执行", "编辑", "删除"}, (d, which) -> {
                    if (which == 0) runRule(rule);
                    else if (which == 1) showEditor(rule);
                    else new AlertDialog.Builder(SyncActivity.this,
                            android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("删除策略")
                            .setMessage(rule.name)
                            .setPositiveButton("删除", (dd, ww) -> {
                                SyncRules.remove(this, rule.id);
                                reload();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .show();
    }

    // ------------------------------------------------------------------ 编辑

    private void showEditor(final SyncRule existing) {
        final SyncRule rule = existing != null ? existing : newRule();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(this);
        name.setHint("策略名称");
        name.setSingleLine(true);
        name.setText(rule.name);
        root.addView(name);

        final TextView source = new TextView(this);
        source.setText("源：" + rule.source.label(this) + "（点一下改）");
        source.setTextColor(0xFFB8C6E8);
        source.setTextSize(13);
        source.setPadding(0, pad / 2, 0, pad / 2);
        source.setOnClickListener(v -> pickEndpoint("选择源目录", rule.source, ep -> {
            rule.source = ep;
            source.setText("源：" + ep.label(this) + "（点一下改）");
        }));
        root.addView(source);

        final TextView dest = new TextView(this);
        dest.setText("目的：" + rule.dest.label(this) + "（点一下改）");
        dest.setTextColor(0xFFB8C6E8);
        dest.setTextSize(13);
        dest.setPadding(0, pad / 2, 0, pad / 2);
        dest.setOnClickListener(v -> pickEndpoint("选择目标目录", rule.dest, ep -> {
            rule.dest = ep;
            dest.setText("目的：" + ep.label(this) + "（点一下改）");
        }));
        root.addView(dest);

        final TextView inc = new TextView(this);
        inc.setText("增量判断：" + rule.incrementalLabel() + "（点一下切换）");
        inc.setTextColor(0xFFB8C6E8);
        inc.setTextSize(13);
        inc.setPadding(0, pad / 2, 0, pad / 2);
        inc.setOnClickListener(v -> {
            rule.incremental = (rule.incremental + 1) % 3;
            inc.setText("增量判断：" + rule.incrementalLabel() + "（点一下切换）");
        });
        root.addView(inc);

        final EditText include = new EditText(this);
        include.setHint("只传这些（通配符，空 = 全部）");
        include.setSingleLine(true);
        include.setInputType(InputType.TYPE_CLASS_TEXT);
        include.setText(rule.include);
        root.addView(include);

        final EditText exclude = new EditText(this);
        exclude.setHint("排除这些（通配符，可空）");
        exclude.setSingleLine(true);
        exclude.setInputType(InputType.TYPE_CLASS_TEXT);
        exclude.setText(rule.exclude);
        root.addView(exclude);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(existing == null ? "添加同步策略" : "编辑同步策略")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    rule.name = n.isEmpty() ? "未命名同步" : n;
                    rule.include = include.getText().toString().trim();
                    rule.exclude = exclude.getText().toString().trim();
                    if (rule.source.ownerId.isEmpty() || rule.dest.ownerId.isEmpty()) {
                        Toast.makeText(this, "源和目的都要选", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (rule.selfLoop()) {
                        Toast.makeText(this, "源和目的不能是同一个目录", Toast.LENGTH_LONG).show();
                        return;
                    }
                    SyncRules.upsert(this, rule);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private SyncRule newRule() {
        SyncRule r = new SyncRule();
        r.name = "同步";
        List<OsvLibrary.Tree> trees = OsvLibrary.trees(this);
        if (!trees.isEmpty()) r.source = new Endpoint(Endpoint.LOCAL, trees.get(0).uri, "");
        List<RemoteConfig> remotes = RemoteConfigs.load(this);
        if (!remotes.isEmpty()) r.dest = new Endpoint(Endpoint.REMOTE, remotes.get(0).id, "");
        return r;
    }

    private interface OnEndpoint {
        void onEndpoint(Endpoint e);
    }

    private void pickEndpoint(String title, Endpoint current, OnEndpoint done) {
        List<String> labels = new ArrayList<>();
        final List<Endpoint> options = new ArrayList<>();
        for (OsvLibrary.Tree t : OsvLibrary.trees(this)) {
            options.add(new Endpoint(Endpoint.LOCAL, t.uri, ""));
            labels.add(t.caption() + "（本机）");
        }
        for (RemoteConfig cfg : RemoteConfigs.load(this)) {
            options.add(new Endpoint(Endpoint.REMOTE, cfg.id, ""));
            labels.add(RemoteScanner.labelOf(cfg));
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, "先去「存储位置」添加本机目录或 NAS", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    Endpoint picked = options.get(which);
                    askSubDir(picked, done);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askSubDir(final Endpoint picked, final OnEndpoint done) {
        final EditText edit = new EditText(this);
        edit.setHint("子目录（留空 = 根目录）");
        edit.setSingleLine(true);
        edit.setText(picked.path);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        edit.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("子目录")
                .setView(edit)
                .setPositiveButton("确定", (d, w) -> {
                    Endpoint ep = picked.copy();
                    ep.path = edit.getText().toString().trim();
                    done.onEndpoint(ep);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return rules.size(); }

        @Override public Object getItem(int position) { return rules.get(position); }

        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(SyncActivity.this);
            SyncRule rule = rules.get(position);
            tv.setText(rule.name + "\n" + rule.summary(SyncActivity.this));
            tv.setTextColor(0xFFEDEDF2);
            tv.setTextSize(13f);
            int pad = Math.round(14 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            return tv;
        }
    }
}
