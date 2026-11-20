package com.osmo.viewer;

/**
 * 一次复制任务的描述 + 运行状态。
 *
 * 放进 Service 时只靠这些可序列化的字段重建「从哪读 / 往哪写」，
 * 不传 Uri 对象之外的活引用（进程被重建也能接着跑）。
 */
public final class TransferJob implements java.io.Serializable {

    public static final int LOCAL = 0;
    public static final int REMOTE = 1;

    public static final int WAITING = 0;
    public static final int RUNNING = 1;
    public static final int OK = 2;
    public static final int FAILED = 3;
    public static final int CANCELLED = 4;

    public String id = "j" + System.currentTimeMillis()
            + "-" + (int) (Math.random() * 1000);
    public String name = "";

    /** 源：本地是 document uri，远程是 configId + 配置根下的相对路径。 */
    public int srcType;
    public String srcOwner = "";
    public String srcPath = "";
    public long size;

    /** 目标：本地是 tree uri + 相对目录，远程是 configId + 相对目录。 */
    public int dstType;
    public String dstOwner = "";
    public String dstPath = "";

    /** {@link Transfer.Conflict} 的序号。 */
    public int conflict = 2;

    public volatile int state = WAITING;
    public volatile long done;
    public volatile boolean cancelRequested;
    public volatile String message = "";
    public long startedMs;
    public long endedMs;

    public boolean finished() {
        return state == OK || state == FAILED || state == CANCELLED;
    }

    public int percent() {
        if (size <= 0) return state == OK ? 100 : 0;
        return (int) Math.min(100, done * 100 / size);
    }

    public String stateText() {
        switch (state) {
            case RUNNING: return "复制中 " + percent() + "%";
            case OK: return "已完成";
            case FAILED: return "失败：" + message;
            case CANCELLED: return "已取消";
            default: return "排队中";
        }
    }
}
