package com.osmo.viewer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从一台远程服务器递归收集 .OSV，生成可直接塞进相册的 {@link OsvItem}。
 *
 * 独立成类是因为首页合并展示和单独的 NAS 浏览页都要用同一套逻辑，
 * 否则两处的深度限制、日期推断、路径拼接迟早会走偏。
 */
public final class RemoteScanner {

    /** 递归深度：NAS 上的目录层级通常不深，限住是为了避免扫描失控。 */
    public static final int MAX_DEPTH = 4;
    /** 单台服务器最多收集多少条。 */
    public static final int MAX_ITEMS = 3000;

    public interface Cancel {
        boolean cancelled();
    }

    private RemoteScanner() {}

    public static List<OsvItem> scan(RemoteConfig cfg, RemoteStore store, Cancel cancel)
            throws IOException {
        List<OsvItem> out = new ArrayList<>();
        collect(cfg, store, "", 0, cancel, out);
        return out;
    }

    private static void collect(RemoteConfig cfg, RemoteStore store, String dir, int depth,
                                Cancel cancel, List<OsvItem> out) throws IOException {
        if (depth > MAX_DEPTH || out.size() >= MAX_ITEMS) return;
        if (cancel != null && cancel.cancelled()) return;
        List<RemoteStore.Entry> entries = store.list(dir);
        for (RemoteStore.Entry e : entries) {
            if (cancel != null && cancel.cancelled()) return;
            if (out.size() >= MAX_ITEMS) return;
            if (e.dir) {
                collect(cfg, store, e.path, depth + 1, cancel, out);
                continue;
            }
            if (!RemoteStore.isVideo(e.name)) continue;
            OsvItem item = toItem(cfg, e);
            out.add(item);
        }
    }

    /** 单个远程条目 → OsvItem（日期先按文件名，再退回服务器的修改时间）。 */
    public static OsvItem toItem(RemoteConfig cfg, RemoteStore.Entry e) {
        String uri = RemoteConfigs.uriFor(cfg, e.path);
        OsvItem item = new OsvItem(android.net.Uri.parse(uri), e.name, e.size, true);
        long fromName = ItemDate.parseName(e.name);
        if (fromName > 0) {
            item.dateMillis = fromName;
            item.dateSource = ItemDate.SOURCE_NAME;
        } else if (e.modifiedMs > 0) {
            item.dateMillis = e.modifiedMs;
            item.dateSource = ItemDate.SOURCE_FS;
        }
        item.places.add(new OsvItem.Place(OsvItem.Place.REMOTE, cfg.id,
                labelOf(cfg), e.path, e.size, e.modifiedMs));
        return item;
    }

    /** NAS 在界面上的短名字。 */
    public static String labelOf(RemoteConfig cfg) {
        String name = cfg.name == null || cfg.name.trim().isEmpty() ? cfg.host : cfg.name.trim();
        return "NAS · " + name;
    }
}
