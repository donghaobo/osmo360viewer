package com.osmo.viewer;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 素材日期的解析与分组。
 *
 * 取值优先级（和手机相册一致）：
 *   1. 文件名里的日期（CAM_20260901080026_0015_D.OSV 这种）
 *   2. 视频元数据里的拍摄时间（mvhd / METADATA_KEY_DATE）
 *   3. 文件系统的修改时间（SAF 的 last-modified）
 */
public final class ItemDate {

    public static final int MODE_DAY = 0;
    public static final int MODE_MONTH = 1;
    public static final int MODE_YEAR = 2;

    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_NAME = 1;
    public static final int SOURCE_META = 2;
    public static final int SOURCE_FS = 3;

    // 20260901080026 / 20260901_080026 / 2026-09-01 08:00:26 / 2026-09-01
    private static final Pattern P_COMPACT =
            Pattern.compile("(20\\d{2})(\\d{2})(\\d{2})[_-]?(\\d{2})?(\\d{2})?(\\d{2})?");
    private static final Pattern P_DASHED =
            Pattern.compile("(20\\d{2})[-_.](\\d{2})[-_.](\\d{2})(?:[ T_-](\\d{2})[:.](\\d{2})[:.](\\d{2}))?");

    private static final ThreadLocal<SimpleDateFormat> FMT_DAY = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy年M月d日", Locale.CHINA));
    private static final ThreadLocal<SimpleDateFormat> FMT_MONTH = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy年M月", Locale.CHINA));
    private static final ThreadLocal<SimpleDateFormat> FMT_YEAR = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy年", Locale.CHINA));
    private static final ThreadLocal<SimpleDateFormat> KEY_DAY = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyyMMdd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> KEY_MONTH = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyyMM", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> KEY_YEAR = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy", Locale.US));

    private static final String[] META_PATTERNS = {
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy:MM:dd HH:mm:ss",
    };

    private ItemDate() {}

    /** 列数 -> 分组粒度。2/4 列按天，8 列按月，16 列按年。 */
    public static int modeForColumns(int columns) {
        if (columns >= 16) return MODE_YEAR;
        if (columns >= 8) return MODE_MONTH;
        return MODE_DAY;
    }

    /** 从文件名推测拍摄时间；找不到返回 -1。 */
    public static long parseName(String name) {
        if (name == null) return -1;
        long v = match(P_COMPACT, name, true);
        if (v > 0) return v;
        return match(P_DASHED, name, false);
    }

    private static long match(Pattern p, String name, boolean optionalTime) {
        Matcher m = p.matcher(name);
        while (m.find()) {
            int year = num(m.group(1));
            int month = num(m.group(2));
            int day = num(m.group(3));
            int hour = m.groupCount() >= 4 ? num(m.group(4)) : -1;
            int minute = m.groupCount() >= 5 ? num(m.group(5)) : -1;
            int second = m.groupCount() >= 6 ? num(m.group(6)) : -1;
            long ms = toMillis(year, month, day, hour, minute, second);
            if (ms > 0) return ms;
        }
        return -1;
    }

    private static int num(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long toMillis(int year, int month, int day, int hour, int minute, int second) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return -1;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day,
                hour < 0 ? 12 : hour, Math.max(0, minute), Math.max(0, second));
        if (c.get(Calendar.YEAR) != year || c.get(Calendar.MONTH) != month - 1
                || c.get(Calendar.DAY_OF_MONTH) != day) {
            return -1;   // 20261332 这类非法日期
        }
        return c.getTimeInMillis();
    }

    /** 解析元数据里的日期字符串（DJI 一般给 2026-09-01T08:00:26.000Z）。 */
    public static long parseMeta(String text) {
        if (text == null) return -1;
        String s = text.trim();
        for (String pattern : META_PATTERNS) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            if (pattern.endsWith("'Z'")) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            try {
                f.setLenient(false);
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        // 有些设备直接给 20260901
        return parseName(s);
    }

    public static String headerText(long millis, int mode) {
        if (millis <= 0) return "未知日期";
        switch (mode) {
            case MODE_YEAR:
                return FMT_YEAR.get().format(new Date(millis));
            case MODE_MONTH:
                return FMT_MONTH.get().format(new Date(millis));
            default:
                return FMT_DAY.get().format(new Date(millis));
        }
    }

    public static String groupKey(long millis, int mode) {
        if (millis <= 0) return "unknown";
        switch (mode) {
            case MODE_YEAR:
                return KEY_YEAR.get().format(new Date(millis));
            case MODE_MONTH:
                return KEY_MONTH.get().format(new Date(millis));
            default:
                return KEY_DAY.get().format(new Date(millis));
        }
    }
}
