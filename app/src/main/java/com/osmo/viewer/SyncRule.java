package com.osmo.viewer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 一条同步策略：源目录 → 目标目录 + 增量判定 + 文件名过滤。
 *
 * 方向不绑定：两端都是 {@link Endpoint}，本地和 NAS 可以任意组合。
 * 增量判定与过滤规则照 BackupMate 的做法（只比元信息，从不读文件内容）。
 */
public final class SyncRule {

    /** 目标端已存在同名文件即跳过（最快）。 */
    public static final int INCREMENTAL_NAME = 0;
    /** 同名且大小一致才跳过。 */
    public static final int INCREMENTAL_SIZE = 1;
    /** 同名、大小、修改时间都一致才跳过（最严格）。 */
    public static final int INCREMENTAL_SIZE_MTIME = 2;

    public String id = "";
    public String name = "未命名同步";
    public Endpoint source = new Endpoint();
    public Endpoint dest = new Endpoint();
    public int incremental = INCREMENTAL_SIZE;
    /** 逗号/空格分隔的通配符；空 = 全部。 */
    public String include = "";
    public String exclude = "";
    public boolean skipHidden = true;
    public long lastRunMs;
    public String lastResult = "";

    public String incrementalLabel() {
        switch (incremental) {
            case INCREMENTAL_NAME: return "仅文件名";
            case INCREMENTAL_SIZE_MTIME: return "文件名+大小+时间";
            default: return "文件名+大小";
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("source", source.toJson());
        o.put("dest", dest.toJson());
        o.put("incremental", incremental);
        o.put("include", include == null ? "" : include);
        o.put("exclude", exclude == null ? "" : exclude);
        o.put("skipHidden", skipHidden);
        o.put("lastRunMs", lastRunMs);
        o.put("lastResult", lastResult == null ? "" : lastResult);
        return o;
    }

    public static SyncRule fromJson(JSONObject o) {
        SyncRule r = new SyncRule();
        r.id = o.optString("id");
        r.name = o.optString("name", "未命名同步");
        r.source = Endpoint.fromJson(o.optJSONObject("source"));
        r.dest = Endpoint.fromJson(o.optJSONObject("dest"));
        r.incremental = o.optInt("incremental", INCREMENTAL_SIZE);
        r.include = o.optString("include");
        r.exclude = o.optString("exclude");
        r.skipHidden = o.optBoolean("skipHidden", true);
        r.lastRunMs = o.optLong("lastRunMs", 0);
        r.lastResult = o.optString("lastResult");
        return r;
    }

    /** 源和目标指向同一处时会自己复制自己，必须在开跑前拦掉。 */
    public boolean selfLoop() {
        return source.sameOf(dest);
    }

    public String summary(android.content.Context c) {
        return source.label(c) + "  →  " + dest.label(c)
                + "\n" + incrementalLabel()
                + (include == null || include.trim().isEmpty() ? "" : " · 只传 " + include)
                + (exclude == null || exclude.trim().isEmpty() ? "" : " · 排除 " + exclude)
                + (lastResult.isEmpty() ? "" : "\n上次：" + lastResult);
    }

    // ------------------------------------------------------------------ 过滤

    /**
     * 文件名过滤：`*` 任意字符、`?` 单字符，大小写不敏感，全串匹配。
     * include 为空表示不过滤；exclude 命中即排除。
     */
    public boolean accept(String fileName, String relPath) {
        String n = fileName == null ? "" : fileName;
        String r = relPath == null ? "" : relPath;
        if (skipHidden && (n.startsWith(".") || r.startsWith(".") || r.contains("/."))) return false;
        Patterns includeList = compile(include);
        if (!includeList.isEmpty && !matches(includeList, n, r)) return false;
        Patterns excludeList = compile(exclude);
        return excludeList.isEmpty || !matches(excludeList, n, r);
    }

    /** @return 是否应该跳过（目标端已有且符合增量条件）。 */
    public boolean upToDate(long srcSize, long srcMtime, Long dstSize, long dstMtime) {
        if (dstSize == null) return false;
        switch (incremental) {
            case INCREMENTAL_NAME:
                return true;
            case INCREMENTAL_SIZE_MTIME:
                if (dstSize != srcSize) return false;
                return dstMtime == 0 || srcMtime == 0 || dstMtime == srcMtime;
            default:
                return dstSize == srcSize;
        }
    }

    private static final class Patterns {
        final java.util.regex.Pattern[] regexes;
        final boolean isEmpty;

        Patterns(java.util.regex.Pattern[] regexes) {
            this.regexes = regexes;
            this.isEmpty = regexes.length == 0;
        }
    }

    private static Patterns compile(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return new Patterns(new java.util.regex.Pattern[0]);
        }
        String[] parts = spec.split("[,;\\s]+");
        java.util.List<java.util.regex.Pattern> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            StringBuilder sb = new StringBuilder("^");
            for (char ch : p.toCharArray()) {
                if (ch == '*') sb.append(".*");
                else if (ch == '?') sb.append('.');
                else sb.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
            }
            sb.append('$');
            out.add(java.util.regex.Pattern.compile(sb.toString(),
                    java.util.regex.Pattern.CASE_INSENSITIVE));
        }
        return new Patterns(out.toArray(new java.util.regex.Pattern[0]));
    }

    private static boolean matches(Patterns list, String fileName, String relPath) {
        for (java.util.regex.Pattern p : list.regexes) {
            if (p.matcher(fileName).matches() || p.matcher(relPath).matches()) return true;
        }
        return false;
    }

    /** 名称里的大写扩展名，展示用。 */
    public static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.US);
    }
}
