package com.dsh.calc12;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 计算历史记录(最近 100 条), 持久化到 SharedPreferences(JSON)。
 * result 超过 5000 字符时只存前 5000 位并标注总位数; 重新点开算式会重新计算完整结果。
 */
public final class CalcHistory {

    public static final class Entry {
        public final String expr;
        public final String result;

        public Entry(String e, String r) {
            expr = e;
            result = r;
        }
    }

    private static final int MAX_ENTRIES = 100;
    private static final int MAX_RESULT_CHARS = 5000;
    private static final String PREFS = "MainActivity";
    private static final String KEY = "history_v1";

    private CalcHistory() {}

    public static List<Entry> load(Context ctx) {
        List<Entry> out = new ArrayList<Entry>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); ++i) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optString("e"), o.optString("r")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(Context ctx, String expr, String result) {
        try {
            if (expr == null || expr.trim().length() == 0) return;
            if (result == null || result.length() == 0) return;
            if (result.length() > MAX_RESULT_CHARS)
                result = result.substring(0, MAX_RESULT_CHARS) + "…(共 " + result.length() + " 位)";
            List<Entry> items = load(ctx);
            if (!items.isEmpty()
                    && items.get(0).expr.equals(expr)
                    && items.get(0).result.equals(result)) return;   // 相邻去重
            items.add(0, new Entry(expr, result));
            while (items.size() > MAX_ENTRIES) items.remove(items.size() - 1);
            save(ctx, items);
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context ctx) {
        try {
            save(ctx, new ArrayList<Entry>());
        } catch (Exception ignored) {
        }
    }

    private static void save(Context ctx, List<Entry> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Entry en : items) {
            JSONObject o = new JSONObject();
            o.put("e", en.expr);
            o.put("r", en.result);
            arr.put(o);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
