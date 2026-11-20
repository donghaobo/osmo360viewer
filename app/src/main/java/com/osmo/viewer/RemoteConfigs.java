package com.osmo.viewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 远程服务器的保存/读取。密码目前是明文（见 README 的已知限制）。 */
public final class RemoteConfigs {

    private static final String PREFS = "remote";
    private static final String KEY_LIST = "servers";

    private RemoteConfigs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<RemoteConfig> load(Context c) {
        List<RemoteConfig> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_LIST, null);
        if (raw == null) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                RemoteConfig cfg = RemoteConfig.fromJson(array.getJSONObject(i));
                cfg.password = SecretStore.decrypt(cfg.password);
                out.add(cfg);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static RemoteConfig find(Context c, String id) {
        if (id == null) return null;
        for (RemoteConfig cfg : load(c)) {
            if (id.equals(cfg.id)) return cfg;
        }
        return null;
    }

    public static void save(Context c, List<RemoteConfig> list) {
        JSONArray array = new JSONArray();
        try {
            for (RemoteConfig cfg : list) {
                // 密码只以 AES/GCM 密文落盘（密钥在 Android Keystore）。
                // 注意要写副本：直接改调用方的 password 会让调用方拿到密文去连服务器。
                JSONObject o = cfg.toJson();
                o.put("password", SecretStore.encrypt(cfg.password));
                array.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString(KEY_LIST, array.toString()).apply();
    }

    /** 把历史明文密码升级成密文（幂等）：load 得到明文，save 时重新加密。 */
    public static void migrate(Context c) {
        if (!prefs(c).contains(KEY_LIST)) return;
        List<RemoteConfig> list = load(c);
        if (list.isEmpty()) return;
        boolean plain = false;
        for (RemoteConfig cfg : list) {
            if (cfg.password != null && !cfg.password.isEmpty()
                    && !SecretStore.isEncrypted(cfg.password)) {
                plain = true;
                break;
            }
        }
        if (plain) save(c, list);
    }

    public static void upsert(Context c, RemoteConfig cfg) {
        List<RemoteConfig> list = load(c);
        if (cfg.id == null) cfg.id = "s" + System.currentTimeMillis();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (cfg.id.equals(list.get(i).id)) {
                list.set(i, cfg);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(cfg);
        save(c, list);
    }

    public static void remove(Context c, String id) {
        List<RemoteConfig> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        save(c, list);
    }

    /** 远程文件的 URI：osvremote://<configId>/<编码后的绝对路径>。 */
    public static String uriFor(RemoteConfig cfg, String path) {
        return "osvremote://" + cfg.id + "/" + encode(path);
    }

    public static String configIdOf(String uriString) {
        if (uriString == null || !uriString.startsWith("osvremote://")) return null;
        String rest = uriString.substring("osvremote://".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    public static String pathOf(String uriString) {
        if (uriString == null || !uriString.startsWith("osvremote://")) return null;
        String rest = uriString.substring("osvremote://".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? "" : decode(rest.substring(slash + 1));
    }

    public static boolean isRemote(String uriString) {
        return uriString != null && uriString.startsWith("osvremote://");
    }

    private static String encode(String path) {
        try {
            return java.net.URLEncoder.encode(path, "UTF-8");
        } catch (Exception e) {
            return path;
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
