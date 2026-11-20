package com.osmo.viewer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 同步的一端：本地目录或 NAS 目录。
 *
 * 源和目的用同一个结构，不绑定方向——「本地→NAS」「NAS→本地」「NAS→NAS」
 * 「本地→本地」都是同一条流水线。
 */
public final class Endpoint {

    public static final int LOCAL = 0;
    public static final int REMOTE = 1;

    /** 本地：SAF tree uri；远程：RemoteConfig.id */
    public String ownerId = "";
    public int type = LOCAL;
    /** 配置根下的相对子目录，"" 表示根 */
    public String path = "";

    public Endpoint() {}

    public Endpoint(int type, String ownerId, String path) {
        this.type = type;
        this.ownerId = ownerId;
        this.path = normalize(path);
    }

    public boolean isRemote() {
        return type == REMOTE;
    }

    /** 展示名：本地带目录名，远程带服务器名。 */
    public String label(android.content.Context c) {
        String base;
        if (isRemote()) {
            RemoteConfig cfg = RemoteConfigs.find(c, ownerId);
            base = cfg == null ? "已删除的服务器" : RemoteScanner.labelOf(cfg);
        } else {
            base = "本机 · " + OsvLibrary.treeLabel(c, android.net.Uri.parse(ownerId));
        }
        return path.isEmpty() ? base : base + " / " + path;
    }

    public Endpoint copy() {
        return new Endpoint(type, ownerId, path);
    }

    public boolean sameOf(Endpoint o) {
        return o != null && type == o.type && ownerId.equals(o.ownerId)
                && normalize(path).equals(normalize(o.path));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("owner", ownerId);
        o.put("path", path == null ? "" : path);
        return o;
    }

    public static Endpoint fromJson(JSONObject o) {
        Endpoint e = new Endpoint();
        e.type = o.optInt("type", LOCAL);
        e.ownerId = o.optString("owner");
        e.path = normalize(o.optString("path"));
        return e;
    }

    private static String normalize(String p) {
        if (p == null) return "";
        String s = p.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    @Override
    public String toString() {
        return (isRemote() ? "remote:" + ownerId : "local:" + ownerId)
                + (path.isEmpty() ? "" : "/" + path);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Endpoint && toString().toLowerCase(Locale.US)
                .equals(o.toString().toLowerCase(Locale.US));
    }

    @Override
    public int hashCode() {
        return toString().toLowerCase(Locale.US).hashCode();
    }
}
