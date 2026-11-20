package com.osmo.viewer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 同步策略的持久化（SharedPreferences 里的一个 JSON 数组）。 */
public final class SyncRules {

    private static final String PREFS = "osv_sync";
    private static final String KEY = "rules";

    private SyncRules() {}

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<SyncRule> load(Context c) {
        List<SyncRule> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, null);
        if (raw == null) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                out.add(SyncRule.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context c, List<SyncRule> rules) {
        JSONArray array = new JSONArray();
        try {
            for (SyncRule r : rules) array.put(r.toJson());
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString(KEY, array.toString()).apply();
    }

    public static void upsert(Context c, SyncRule rule) {
        List<SyncRule> list = load(c);
        if (rule.id == null || rule.id.isEmpty()) {
            rule.id = "r" + System.currentTimeMillis();
        }
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (rule.id.equals(list.get(i).id)) {
                list.set(i, rule);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(rule);
        save(c, list);
    }

    public static void remove(Context c, String id) {
        List<SyncRule> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        save(c, list);
    }
}
