package com.osmo.viewer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** 一台远程服务器（NAS）的连接配置。 */
public class RemoteConfig {

    public static final String PROTO_SMB = "smb";
    public static final String PROTO_WEBDAV = "webdav";
    public static final String PROTO_FTP = "ftp";

    public String id;
    public String name;
    public String protocol = PROTO_SMB;
    public String host = "";
    public int port;
    public String user = "";
    public String password = "";
    /** SMB: 共享名/子路径；WebDAV/FTP: 起始目录。 */
    public String path = "";
    /** WebDAV 是否 https。 */
    public boolean https;
    /** 是否把这台服务器并进相册首页（与本机素材合并去重）。 */
    public boolean showInGallery;

    public int defaultPort() {
        switch (protocol) {
            case PROTO_WEBDAV:
                return https ? 443 : 80;
            case PROTO_FTP:
                return 21;
            default:
                return 445;
        }
    }

    public String label() {
        String p = port > 0 ? String.valueOf(port) : String.valueOf(defaultPort());
        return name + "\n" + protocol.toUpperCase(Locale.US) + " · " + host + ":" + p
                + (path.isEmpty() ? "" : " · " + path);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("protocol", protocol);
        o.put("host", host);
        o.put("port", port);
        o.put("user", user);
        o.put("password", password);
        o.put("path", path);
        o.put("https", https);
        o.put("show_in_gallery", showInGallery);
        return o;
    }

    public static RemoteConfig fromJson(JSONObject o) {
        RemoteConfig c = new RemoteConfig();
        c.id = o.optString("id");
        c.name = o.optString("name");
        c.protocol = o.optString("protocol", PROTO_SMB);
        c.host = o.optString("host");
        c.port = o.optInt("port", 0);
        c.user = o.optString("user");
        c.password = o.optString("password");
        c.path = o.optString("path");
        c.https = o.optBoolean("https", false);
        c.showInGallery = o.optBoolean("show_in_gallery", false);
        return c;
    }
}
