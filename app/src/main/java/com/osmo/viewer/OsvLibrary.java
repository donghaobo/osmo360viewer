package com.osmo.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相册数据源：若干个用户选定的 SAF 目录树 + 若干单独添加的文件。
 * 不申请存储权限，完全靠 SAF 的持久化授权。
 *
 * 从 v0.6.0 起支持多个目录，合并展示时按文件名去重（大小写不敏感）。
 * 旧版本只存了一个 tree_uri，读到时会自动迁移成列表，不会丢用户已有的配置。
 */
public class OsvLibrary {

    private static final String PREFS = "osv_viewer";
    private static final String KEY_TREES = "tree_list";
    private static final String KEY_TREE_LEGACY = "tree_uri";
    private static final String KEY_FILES = "file_uris";
    private static final int MAX_DEPTH = 6;

    /** 一个已授权的本地目录。 */
    public static final class Tree {
        public final String uri;
        public final String label;

        public Tree(String uri, String label) {
            this.uri = uri;
            this.label = label;
        }

        public Uri asUri() {
            return Uri.parse(uri);
        }

        /** 「本机 · DCIM」这种展示名。 */
        public String caption() {
            return "本机 · " + (label == null || label.isEmpty() ? uri : label);
        }
    }

    private OsvLibrary() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 本地目录

    public static List<Tree> trees(Context c) {
        List<Tree> out = readTrees(c);
        if (out.isEmpty()) {
            String legacy = prefs(c).getString(KEY_TREE_LEGACY, null);
            if (legacy != null && !legacy.isEmpty()) {
                // 旧版单目录：迁移成列表，旧键保留不动（回退旧版本时还能用）
                out.add(new Tree(legacy, treeLabel(c, Uri.parse(legacy))));
                writeTrees(c, out);
            }
        }
        return out;
    }

    public static void addTree(Context c, Uri uri, String label) {
        if (uri == null) return;
        List<Tree> list = trees(c);
        for (Tree t : list) {
            if (t.uri.equals(uri.toString())) return;   // 已经添加过
        }
        list.add(new Tree(uri.toString(), label));
        writeTrees(c, list);
    }

    public static void removeTree(Context c, String uri) {
        List<Tree> list = trees(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).uri.equals(uri)) list.remove(i);
        }
        writeTrees(c, list);
    }

    /** 该目录是否已经添加过（用于"添加"时给出提示）。 */
    public static boolean hasTree(Context c, Uri uri) {
        if (uri == null) return false;
        for (Tree t : trees(c)) {
            if (t.uri.equals(uri.toString())) return true;
        }
        return false;
    }

    private static List<Tree> readTrees(Context c) {
        List<Tree> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_TREES, null);
        if (raw == null) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String uri = o.optString("uri");
                if (uri.isEmpty()) continue;
                out.add(new Tree(uri, o.optString("label")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void writeTrees(Context c, List<Tree> list) {
        JSONArray array = new JSONArray();
        try {
            for (Tree t : list) {
                JSONObject o = new JSONObject();
                o.put("uri", t.uri);
                o.put("label", t.label == null ? "" : t.label);
                array.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString(KEY_TREES, array.toString()).apply();
    }

    /** 目录的展示名：优先问系统要根目录的 display_name。 */
    public static String treeLabel(Context c, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cur = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
            }
        } catch (Exception ignored) {
        }
        String last = tree.getLastPathSegment();
        if (last == null) return tree.toString();
        int colon = last.indexOf(':');
        return colon >= 0 ? last.substring(colon + 1) : last;
    }

    // ------------------------------------------------------------------ 零散文件

    public static void addFile(Context c, Uri uri) {
        Set<String> set = new HashSet<>(prefs(c).getStringSet(KEY_FILES, new HashSet<>()));
        set.add(uri.toString());
        prefs(c).edit().putStringSet(KEY_FILES, set).apply();
    }

    public static void removeFile(Context c, Uri uri) {
        Set<String> set = new HashSet<>(prefs(c).getStringSet(KEY_FILES, new HashSet<>()));
        set.remove(uri.toString());
        prefs(c).edit().putStringSet(KEY_FILES, set).apply();
    }

    public static List<Uri> pickedFiles(Context c) {
        List<Uri> out = new ArrayList<>();
        for (String s : prefs(c).getStringSet(KEY_FILES, new HashSet<>())) {
            out.add(Uri.parse(s));
        }
        return out;
    }

    /**
     * 单独扫一个目录树（本地目录浏览页用）。
     * 和 {@link #scan} 的区别只是不合并其它来源、不跨目录去重。
     */
    public static List<OsvItem> scanTree(Context c, Uri treeUri) {
        List<OsvItem> out = new ArrayList<>();
        if (treeUri == null) return out;
        Tree owner = new Tree(treeUri.toString(), treeLabel(c, treeUri));
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId);
            collect(c, treeUri, children, owner, 0, out);
        } catch (Exception ignored) {
            // 授权被回收 / 目录已删除：返回空列表，界面会提示
        }
        out.sort((a, b) -> b.name.compareToIgnoreCase(a.name));
        return out;
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 扫描全部本地来源（多个目录 + 零散文件），按文件名去重。
     *
     * 同名的多个本地副本都记进 places，第一个（目录里先遇到的）当主副本。
     */
    public static List<OsvItem> scan(Context c) {
        Map<String, OsvItem> byName = new HashMap<>();
        for (Tree tree : trees(c)) {
            List<OsvItem> found = new ArrayList<>();
            try {
                Uri uri = tree.asUri();
                String rootId = DocumentsContract.getTreeDocumentId(uri);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, rootId);
                collect(c, uri, children, tree, 0, found);
            } catch (Exception ignored) {
                // 授权被回收 / 目录已删除：跳过这个目录
            }
            for (OsvItem item : found) {
                merge(byName, item);
            }
        }
        for (Uri u : pickedFiles(c)) {
            String name = displayName(c, u);
            if (name == null) continue;
            OsvItem item = new OsvItem(u, name, fileSize(c, u), false);
            applyDate(item, lastModified(c, u));
            item.places.add(new OsvItem.Place(OsvItem.Place.LOCAL, "", "本机 · 单独添加",
                    u.toString(), item.size, item.dateMillis));
            merge(byName, item);
        }
        List<OsvItem> out = new ArrayList<>(byName.values());
        out.sort((a, b) -> b.name.compareToIgnoreCase(a.name));
        return out;
    }

    /**
     * 同名只留一个主副本，其余位置合并进 places。
     *
     * 注意：item 自己的位置在创建时已经放进 places 了，这里**不能再补一次**，
     * 否则单目录下也会显示成「同名 2 处」。
     */
    private static void merge(Map<String, OsvItem> byName, OsvItem item) {
        String key = item.dedupeKey();
        OsvItem exist = byName.get(key);
        if (exist == null) {
            byName.put(key, item);
            return;
        }
        for (OsvItem.Place p : item.places) {
            if (!hasPlace(exist, p)) exist.places.add(p);
        }
    }

    private static boolean hasPlace(OsvItem item, OsvItem.Place place) {
        for (OsvItem.Place p : item.places) {
            if (p.type == place.type && p.path.equals(place.path)) return true;
        }
        return false;
    }

    private static void collect(Context c, Uri tree, Uri childrenUri, Tree owner,
                                int depth, List<OsvItem> out) {
        if (depth > MAX_DEPTH) return;
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        List<Uri> subDirs = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(childrenUri, cols, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                long size = cur.isNull(3) ? 0 : cur.getLong(3);
                long modified = cur.isNull(4) ? 0 : cur.getLong(4);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subDirs.add(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id));
                } else if (name != null && isOsv(name)) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    OsvItem item = new OsvItem(doc, name, size, false);
                    applyDate(item, modified);
                    item.places.add(new OsvItem.Place(OsvItem.Place.LOCAL, owner.uri,
                            owner.caption(), doc.toString(), size, modified));
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        for (Uri sub : subDirs) collect(c, tree, sub, owner, depth + 1, out);
    }

    /**
     * 先把文件名里的日期填上，没有就用文件系统时间兜底。
     * 元数据里的拍摄时间要解码才能拿到，由探测线程稍后覆盖（见 MainActivity）。
     */
    private static void applyDate(OsvItem item, long fsMillis) {
        long fromName = ItemDate.parseName(item.name);
        if (fromName > 0) {
            item.dateMillis = fromName;
            item.dateSource = ItemDate.SOURCE_NAME;
        } else if (fsMillis > 0) {
            item.dateMillis = fsMillis;
            item.dateSource = ItemDate.SOURCE_FS;
        }
    }

    private static long lastModified(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static boolean isOsv(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".osv") || n.endsWith(".osv.mp4");
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    private static long fileSize(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        String[] u = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(java.util.Locale.US, i == 0 ? "%.0f%s" : "%.1f%s", v, u[i]);
    }
}
