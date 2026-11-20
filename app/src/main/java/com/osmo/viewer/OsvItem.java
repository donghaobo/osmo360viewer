package com.osmo.viewer;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * 相册里的一条 OSV 记录。元信息在扫描后异步补齐。
 *
 * 同一个文件名可能同时存在于本机某个目录和几台 NAS 上。相册只显示一条，
 * 其余位置记在 {@link #places} 里——长按删除时要让用户选删哪一处。
 */
public class OsvItem {

    /** 一个具体位置：本机的某个 SAF 文档，或某台 NAS 上的相对路径。 */
    public static final class Place {
        public static final int LOCAL = 0;
        public static final int REMOTE = 1;

        public final int type;
        /** 本地：SAF tree uri；远程：RemoteConfig.id */
        public final String ownerId;
        /** 展示用：「本机 · DCIM」/「客厅 NAS」 */
        public final String label;
        /** 本地：文档 Uri 字符串；远程：相对配置根的路径 */
        public final String path;
        public final long size;
        public final long modifiedMs;

        public Place(int type, String ownerId, String label, String path,
                     long size, long modifiedMs) {
            this.type = type;
            this.ownerId = ownerId;
            this.label = label;
            this.path = path;
            this.size = size;
            this.modifiedMs = modifiedMs;
        }

        public boolean isRemote() {
            return type == REMOTE;
        }

        /** 位置的一行摘要，删除时列出来用。 */
        public String caption() {
            String sizeText = size > 0 ? " · " + OsvLibrary.humanSize(size) : "";
            return label + " · " + (isRemote() ? path : "文档") + sizeText;
        }
    }

    public final Uri uri;
    public final String name;
    public final long size;
    /** 主副本是否在远端（决定要不要走网络探测/缩略图）。 */
    public final boolean remote;
    /** 同名文件的全部位置，第一个就是当前展示的主副本。 */
    public final List<Place> places = new ArrayList<>();

    public long durationMs;
    public int width;
    public int height;
    public int videoTracks;
    public boolean probed;
    public boolean probeFailed;
    /** 分组用的拍摄时间（毫秒，未知为 0）。 */
    public long dateMillis;
    /** 这个日期来自哪里：文件名 / 元数据 / 文件系统。 */
    public int dateSource = ItemDate.SOURCE_NONE;
    /** 相册页复用的缩略图（可能为 null）。 */
    public android.graphics.Bitmap thumb;

    public OsvItem(Uri uri, String name, long size) {
        this(uri, name, size, RemoteConfigs.isRemote(uri.toString()));
    }

    public OsvItem(Uri uri, String name, long size, boolean remote) {
        this.uri = uri;
        this.name = name;
        this.size = size;
        this.remote = remote;
    }

    /** 文件名去重用的键：大小写不敏感（SMB/FTP 目标多跑在大小写不敏感的文件系统上）。 */
    public String dedupeKey() {
        return name.toLowerCase(java.util.Locale.US);
    }

    /** 位置来源的简短标签，给卡片角标用。 */
    public String sourceBadge() {
        if (!remote) return null;
        return places.isEmpty() ? "NAS" : places.get(0).label;
    }

    /** 显示用名字：先去掉 .OSV（含 .OSV.mp4）后缀，卡片再从前面省略。 */
    public String displayName() {
        String n = name;
        String lower = n.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".mp4")) n = n.substring(0, n.length() - 4);
        lower = n.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".osv")) n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? name : n;
    }

    /** 日期显示（含来源，长按信息里用）。 */
    public String dateCaption() {
        if (dateMillis <= 0) return "时间未知";
        String text = ItemDate.headerText(dateMillis, ItemDate.MODE_DAY);
        switch (dateSource) {
            case ItemDate.SOURCE_NAME: return text + "（文件名）";
            case ItemDate.SOURCE_META: return text + "（元数据）";
            case ItemDate.SOURCE_FS: return text + "（文件时间）";
            default: return text;
        }
    }

    public String caption() {
        if (!probed) return remote ? "远端素材" : "读取中…";
        if (probeFailed) return "无法读取元信息";
        return fmtDuration(durationMs) + " · " + width + "×" + height
                + (videoTracks >= 2 ? " · 双鱼眼" : "");
    }

    /** 长按信息里列出的全部位置。 */
    public String placesCaption() {
        if (places.size() <= 1) {
            return places.isEmpty() ? "" : "位置：" + places.get(0).caption();
        }
        StringBuilder sb = new StringBuilder("位置（同名 " + places.size() + " 处）：");
        for (Place p : places) sb.append("\n· ").append(p.caption());
        return sb.toString();
    }

    public static String fmtDuration(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
