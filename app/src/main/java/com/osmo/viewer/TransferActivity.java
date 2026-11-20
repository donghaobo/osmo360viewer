package com.osmo.viewer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 传输任务列表：进行中与已完成都列出来，长按可取消正在跑的那个。 */
public class TransferActivity extends Activity {

    private final List<TransferJob> jobs = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ListView list;
    private TextView status;
    private View empty;
    private Adapter adapter;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reload();
            ui.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);

        adapter = new Adapter();
        list.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            TransferService.clearFinished();
            reload();
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            TransferJob job = jobs.get(position);
            if (job.finished()) return true;
            new android.app.AlertDialog.Builder(this,
                    android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("取消复制")
                    .setMessage(job.name + "\n\n已传的部分会留在目标端。")
                    .setPositiveButton("取消任务", (d, w) -> {
                        TransferService.cancel(job.id);
                        reload();
                    })
                    .setNegativeButton("继续", null)
                    .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private void reload() {
        jobs.clear();
        jobs.addAll(TransferService.jobs());
        adapter.notifyDataSetChanged();
        boolean none = jobs.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        int running = 0;
        for (TransferJob j : jobs) if (!j.finished()) running++;
        status.setText(running > 0
                ? running + " 个任务进行中 · 共 " + jobs.size() + " 个"
                : "共 " + jobs.size() + " 个任务");
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return jobs.size(); }

        @Override public Object getItem(int position) { return jobs.get(position); }

        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(TransferActivity.this);
            TransferJob job = jobs.get(position);
            String detail = job.stateText();
            if (job.message != null && !job.message.isEmpty()
                    && job.state != TransferJob.RUNNING) {
                detail += "\n" + job.message;
            }
            tv.setText(job.name + "\n" + detail);
            tv.setTextColor(job.state == TransferJob.FAILED ? 0xFFE8A0A0 : 0xFFEDEDF2);
            tv.setTextSize(13.5f);
            int pad = Math.round(14 * getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad);
            return tv;
        }
    }
}
