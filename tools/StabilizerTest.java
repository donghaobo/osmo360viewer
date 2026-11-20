package com.osmo.viewer;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 稳像（逐帧姿态抵消）的数值回归。
 *
 * 真机上「画面不抖了没有」很容易看错，坐标轴搞反也只表现为画面慢慢转走，所以这里
 * 把不变量先钉死：
 *   1. 相机没动 → 修正必须退化成单位四元数（否则画面会莫名歪掉）；
 *   2. 相机转了 θ → 修正必须是反方向 θ（世界方向在屏幕上不动）；
 *   3. 首尾样本取到的必须就是原始姿态，中间插值单调且不翻面；
 *   4. 稳像不能吃掉用户视角（getQuat 仍是用户视角，getMatrix 才是乘上稳像的）；
 *   5. 真实 djmd 素材上，相邻修正的角步长要连续（翻面或没归一化会看到大跳）。
 *
 * 运行：tools/verify_stabilizer.sh [数据目录，默认 /tmp/djmdtest]
 */
public class StabilizerTest {

    /** 低通系数（PlayerActivity.STAB_ALPHA 默认 1 = 不过滤；这里验证阈值附近的行为）。 */
    private static final float ALPHA = 0.6f;

    private static int failures;

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "/tmp/djmdtest";

        staticInput();
        frameConversion();
        rotatingInput();
        hemisphereFlip();
        userViewPreserved();
        interpolation();
        realTrack(dir);

        System.out.println(failures == 0 ? "\n全部通过" : "\n失败 " + failures + " 项");
        if (failures != 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 1. 静止退化

    private static void staticInput() throws Exception {
        float[] q = axis(0.3f, 0.5f, 0.8f, 1.234f);   // 一个随意的固定姿态
        long[] t = times(0, 33_333, 20);
        AttitudeTrack track = track(t, repeat(q, 20));
        float[] base = new float[4];
        track.firstQuat(base);

        float worst = 0f;
        float[] now = new float[4];
        float[] stab = new float[4];
        float[] tmp = new float[4];
        for (int i = 0; i < 120; i++) {   // 采样点比样本密，插值路径也要是单位
            track.quatAt(i * 6_000, now);
            Quat.conj(tmp, now);
            Quat.mul(stab, tmp, base);
            worst = Math.max(worst, angle(stab));
        }
        check("静止素材：修正退化为单位", worst < 1e-4, "最大偏差 " + fmtDeg(deg(worst)));
    }

    // ------------------------------------------------------------------ 坐标系换算

    /**
     * 姿态换系。djmd 的 q 是 IMU 系 → 世界；渲染里用的是光学系（x 右、y 下、z 前）。
     * 换系把三个轴的旋转分别搬到：x 不动、z→+y、y→-z。
     *
     * 这个映射不是推出来的，是拿真实素材比出来的：不做换算（v0.9.0）时偏航会被当成
     * 滚转、滚转被当成偏航，补偿推到错误方向，真机上「开了更抖」。
     * tools/verify_stabilizer_sign.py 在 7 个朝向上把残留运动从 ~9° 压到 ~2°。
     */
    private static void frameConversion() throws Exception {
        // 绕 IMU z（上）转 20°：换算后是绕光学 +y 转 20°
        float[] q = axis(0f, 0f, 1f, (float) Math.toRadians(20));
        float[] conv = q.clone();
        AttitudeTrack.toOptical(conv);
        float[] expect = axis(0f, 1f, 0f, (float) Math.toRadians(20));
        check("IMU z 轴 → 光学 +y", angleBetween(conv, expect) < 1e-3,
                fmtDeg(deg(angleBetween(conv, expect))));

        // 俯仰绕两者共有的 x 轴，换算前后必须不变
        float[] pitch = axis(1f, 0f, 0f, (float) Math.toRadians(15));
        float[] pc = pitch.clone();
        AttitudeTrack.toOptical(pc);
        check("IMU x 轴不变（俯仰）", angleBetween(pc, pitch) < 1e-3,
                fmtDeg(deg(angleBetween(pc, pitch))));

        // 绕 IMU y 的旋转 → 光学 -z
        float[] roll = axis(0f, 1f, 0f, (float) Math.toRadians(12));
        float[] rc = roll.clone();
        AttitudeTrack.toOptical(rc);
        check("IMU y 轴 → 光学 -z", angleBetween(rc, axis(0f, 0f, -1f,
                (float) Math.toRadians(12))) < 1e-3,
                fmtDeg(deg(angleBetween(rc, axis(0f, 0f, -1f, (float) Math.toRadians(12))))));

        // 相对旋转也要跟着换系：conj(q_b') q_a' = conj(p) · conj(q_b) q_a · p
        float[] qa = axis(0.3f, 0.6f, 0.2f, 0.7f);
        float[] qb = axis(0.1f, 0.9f, 0.4f, 1.1f);
        float[] qac = qa.clone();
        float[] qbc = qb.clone();
        AttitudeTrack.toOptical(qac);
        AttitudeTrack.toOptical(qbc);
        float[] relConv = new float[4];
        Quat.mul(relConv, qConj(qbc), qac);
        float[] rel = new float[4];
        Quat.mul(rel, qConj(qb), qa);
        // 手工用 p 共轭一次
        float[] p = {0.70710678f, 0.70710678f, 0f, 0f};
        float[] inv = qConj(p);
        float[] t = new float[4];
        float[] relExpect = new float[4];
        Quat.mul(t, inv, rel);
        Quat.mul(relExpect, t, p);
        check("换系后的相对旋转 = 原相对旋转共轭", angleBetween(relConv, relExpect) < 1e-4,
                fmtDeg(deg(angleBetween(relConv, relExpect))));

        // 幅度不变：换算只是换基，不改变旋转大小
        check("换系不改变旋转幅度", Math.abs(angleBetween(relConv, new float[]{1f, 0f, 0f, 0f})
                - angleBetween(rel, new float[]{1f, 0f, 0f, 0f})) < 1e-4, "");
    }

    private static float[] qConj(float[] q) {
        return new float[]{q[0], -q[1], -q[2], -q[3]};
    }

    // ------------------------------------------------------------------ 2. 反向抵消

    private static void rotatingInput() throws Exception {
        // 相机绕世界 z 轴匀速转 40°，取 21 帧
        long[] t = times(0, 33_333, 21);
        float[] quats = new float[21 * 4];
        for (int i = 0; i < 21; i++) {
            float[] q = axis(0f, 0f, 1f, (float) Math.toRadians(40.0 * i / 20));
            System.arraycopy(q, 0, quats, i * 4, 4);
        }
        AttitudeTrack track = track(t, quats);
        float[] base = new float[4];
        track.firstQuat(base);
        float[] now = new float[4];
        float[] stab = new float[4];
        float[] tmp = new float[4];

        track.quatAt(t[20], now);
        Quat.conj(tmp, now);
        Quat.mul(stab, tmp, base);
        check("转 40°：修正角度等于 40°", Math.abs(deg(angle(stab)) - 40.0) < 0.2,
                fmtDeg(deg(angle(stab))));
        // 修正后的世界方向：stab * q = base
        float[] total = new float[4];
        Quat.mul(total, stab, now);
        float residual = angleBetween(total, base);
        check("修正后世界方向不动", residual < 1e-3, "残差 " + fmtDeg(deg(residual)));
    }

    // ------------------------------------------------------------------ 3. 半球

    private static void hemisphereFlip() throws Exception {
        float[] a = axis(0.2f, 1f, 0.3f, 0.9f);
        float[] b = new float[4];
        for (int i = 0; i < 4; i++) b[i] = -a[i];     // 同一姿态的另一种写法
        AttitudeTrack track = track(times(0, 50_000, 2), concat(a, b));
        float[] out = new float[4];
        track.quatAt(25_000, out);
        check("相邻帧 q/-q：插值不翻面", angleBetween(out, a) < 1e-3,
                "偏差 " + fmtDeg(deg(angleBetween(out, a))));
    }

    // ------------------------------------------------------------------ 4. 视角分离

    private static void userViewPreserved() {
        ViewState state = new ViewState();
        float[] user = axis(0f, 1f, 0f, 0.6f);
        state.setQuat(user);
        float[] stab = axis(0f, 0f, 1f, 0.35f);
        state.setStabilizer(stab);

        float[] got = new float[4];
        state.getQuat(got);
        check("稳像不影响用户视角", angleBetween(got, user) < 1e-4,
                "偏差 " + fmtDeg(deg(angleBetween(got, user))));

        float[] total = new float[4];
        float[] expect = new float[4];
        state.getTotalQuat(total);
        Quat.mul(expect, stab, user);
        check("最终视角 = 稳像 × 用户视角", angleBetween(total, expect) < 1e-4,
                "偏差 " + fmtDeg(deg(angleBetween(total, expect))));

        float[] m9 = new float[9];
        state.getMatrix(m9);
        float[] m9b = new float[9];
        Quat.toMatrix(expect, m9b);
        float diff = 0f;
        for (int i = 0; i < 9; i++) diff = Math.max(diff, Math.abs(m9[i] - m9b[i]));
        check("矩阵与四元数一致", diff < 1e-4, "最大元素差 " + diff);

        state.clearStabilizer();
        state.getQuat(got);
        check("关闭稳像后回到用户视角", angleBetween(got, user) < 1e-4, "");
    }

    // ------------------------------------------------------------------ 5. 插值

    private static void interpolation() throws Exception {
        long[] t = {0, 33_333, 66_666};
        float[] a = axis(1f, 0f, 0f, 0.2f);
        float[] b = axis(1f, 0f, 0f, 0.9f);
        float[] c = axis(1f, 0f, 0f, 1.5f);
        AttitudeTrack track = track(t, concat(a, b, c));

        float[] out = new float[4];
        track.quatAt(t[0], out);
        check("左端点精确命中", angleBetween(out, a) < 1e-4, "");
        track.quatAt(t[2], out);
        check("右端点精确命中", angleBetween(out, c) < 1e-4, "");
        track.quatAt(-1_000_000, out);
        check("越界取首帧", angleBetween(out, a) < 1e-4, "");
        track.quatAt(99_000_000, out);
        check("越界取尾帧", angleBetween(out, c) < 1e-4, "");

        boolean monotonic = true;
        float prev = -1f;
        for (int i = 0; i <= 20; i++) {
            track.quatAt(t[0] + i * (t[1] - t[0]) / 20, out);
            float ang = angleBetween(out, a);
            if (ang < prev - 1e-3f) monotonic = false;
            prev = ang;
        }
        check("区间内插值单调", monotonic, "");

        // 中点应当落在两端的「折中」处：与两端的角度相等
        track.quatAt((t[0] + t[1]) / 2, out);
        float d1 = angleBetween(out, a);
        float d2 = angleBetween(out, b);
        check("中点等距两端", Math.abs(d1 - d2) < 2e-3, d1 + " / " + d2);

        // 平滑用的 nlerp：t=0/1 精确命中，t 越大越靠近目标
        float[] s = new float[4];
        Quat.nlerp(s, a, b, 0f);
        check("nlerp t=0 命中起点", angleBetween(s, a) < 1e-4, "");
        Quat.nlerp(s, a, b, 1f);
        check("nlerp t=1 命中终点", angleBetween(s, b) < 1e-4, "");
        Quat.nlerp(s, a, b, 0.18f);
        check("nlerp 走小步", angleBetween(s, a) < angleBetween(a, b) * 0.3f,
                fmtDeg(deg(angleBetween(s, a))) + " vs 全程 "
                        + fmtDeg(deg(angleBetween(a, b))));
    }

    // ------------------------------------------------------------------ 6. 真实素材

    private static void realTrack(String dir) throws Exception {
        byte[] blob = Files.readAllBytes(Paths.get(dir, "djmd.bin"));
        int[] sizes = readSizes(Paths.get(dir, "sizes.txt"));
        List<float[]> quats = new ArrayList<>();
        int offset = 0;
        for (int size : sizes) {
            if (offset + size > blob.length) break;
            byte[] pkt = new byte[size];
            System.arraycopy(blob, offset, pkt, 0, size);
            offset += size;
            float[] q = OsvMeta.frameAttitude(pkt);
            if (q != null) quats.add(q);
        }
        System.out.printf("真实素材：%d 包，解析出姿态 %d 帧%n", sizes.length, quats.size());
        check("真实素材解析出足够姿态", quats.size() > 60, quats.size() + " 帧");

        long[] t = times(0, 33_333, quats.size());
        float[] flat = new float[quats.size() * 4];
        for (int i = 0; i < quats.size(); i++) {
            System.arraycopy(quats.get(i), 0, flat, i * 4, 4);
        }
        AttitudeTrack track = track(t, flat);
        float[] base = new float[4];
        track.firstQuat(base);
        check("基准姿态是单位四元数附近", Math.abs(length(base) - 1f) < 1e-3,
                "模长 " + length(base));

        // 逐帧修正：相邻步长必须连续（翻面/未归一化会突然跳到 100° 以上）
        float[] prevStab = null;
        float maxStep = 0f, totalRange = 0f;
        float[] now = new float[4];
        float[] tmp = new float[4];
        float[] stab = new float[4];
        for (int i = 0; i < quats.size(); i++) {
            track.quatAt(t[i], now);
            Quat.conj(tmp, now);
            Quat.mul(stab, tmp, base);
            if (prevStab != null) {
                maxStep = Math.max(maxStep, angleBetween(prevStab, stab));
                totalRange = Math.max(totalRange, angleBetween(stab, base));
            }
            prevStab = stab.clone();
        }
        System.out.printf("真实素材：最大相邻步长 %.2f°，修正幅度上限 %.2f°%n",
                deg(maxStep), deg(totalRange));
        check("相邻修正连续（<15°/帧）", deg(maxStep) < 15.0, fmtDeg(deg(maxStep)));
        check("修正幅度在合理范围（<179°）", deg(totalRange) < 179.0,
                fmtDeg(deg(totalRange)));

        // 低通（只有把 STAB_ALPHA 调到 1 以下才走这条路）：起点是单位四元数，
        // 不该把真实运动吃掉太多，也不能一步到位否则等于没滤波
        track.quatAt(t[quats.size() - 1], now);
        Quat.conj(tmp, now);
        Quat.mul(stab, tmp, base);
        float target = angleBetween(stab, new float[]{1f, 0f, 0f, 0f});
        float[] smooth = {1f, 0f, 0f, 0f};
        Quat.nlerp(smooth, smooth, stab, ALPHA);
        float first = angleBetween(smooth, new float[]{1f, 0f, 0f, 0f});
        check("低通第一拍只走一小步", first < target * (ALPHA + 0.05f),
                fmtDeg(deg(first)) + " / 目标 " + fmtDeg(deg(target)));
        for (int k = 0; k < 20; k++) Quat.nlerp(smooth, smooth, stab, ALPHA);
        check("低通 20 拍后基本跟上", angleBetween(smooth, stab) < Math.max(0.5f, target * 0.05f),
                fmtDeg(deg(angleBetween(smooth, stab))));
    }

    // ------------------------------------------------------------------ 工具

    private static AttitudeTrack track(long[] t, float[] quats) throws Exception {
        Constructor<AttitudeTrack> c = AttitudeTrack.class
                .getDeclaredConstructor(long[].class, float[].class, int.class);
        c.setAccessible(true);
        return c.newInstance(t, quats, t.length);
    }

    private static long[] times(long start, long step, int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = start + step * i;
        return t;
    }

    private static float[] repeat(float[] q, int n) {
        float[] out = new float[n * 4];
        for (int i = 0; i < n; i++) System.arraycopy(q, 0, out, i * 4, 4);
        return out;
    }

    private static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static float[] axis(float x, float y, float z, float angle) {
        float[] q = new float[4];
        Quat.fromAxisAngle(q, x, y, z, angle);
        return q;
    }

    private static float length(float[] q) {
        return (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    }

    /** 两个姿态之间的夹角（弧度）；q 与 -q 视为同一姿态。 */
    private static float angleBetween(float[] a, float[] b) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float na = length(a), nb = length(b);
        if (na < 1e-8f || nb < 1e-8f) return 0f;
        float c = Math.min(1f, Math.abs(dot) / (na * nb));
        return (float) (2.0 * Math.acos(c));
    }

    /** 相对单位四元数的转角。 */
    private static float angle(float[] q) {
        return angleBetween(q, new float[]{1f, 0f, 0f, 0f});
    }

    private static double deg(float rad) {
        return Math.toDegrees(rad);
    }

    private static String fmtDeg(double rad) {
        return String.format("%.3f°", rad);
    }

    private static int[] readSizes(java.nio.file.Path p) throws Exception {
        String[] parts = new String(Files.readAllBytes(p)).trim().split("\\s+");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        return out;
    }

    private static void check(String name, boolean ok, String detail) {
        System.out.printf("%s %s%s%n", ok ? "  ok  " : "  FAIL", name,
                detail == null || detail.isEmpty() ? "" : "  [" + detail + "]");
        if (!ok) failures++;
    }
}
