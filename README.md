# OSV 播放器（Android）

看 DJI Osmo 360 的 `.OSV` 素材：相册页浏览、点开即播，双鱼眼实时缝合，触摸拖动 / 双指缩放 / 体感转头看四周。

```
OsvViewerApp/
├── app/src/main/java/com/osmo/viewer/
│   ├── MainActivity.java     相册页：SAF 选目录、扫描 .OSV、卡片网格
│   ├── OsvLibrary.java       SAF 权限持久化 + 目录递归扫描
│   ├── OsvItem.java          相册条目
│   ├── ItemDate.java         日期解析（文件名 → 元数据 → 文件时间）与分组
│   ├── RemoteConfig(s).java  远程服务器的配置与持久化
│   ├── RemoteStore.java      远程存储抽象（列目录 + 按偏移读）
│   ├── SmbStore.java         SMB2/3（smbj）
│   ├── WebDavStore.java      WebDAV（PROPFIND + Range GET，零依赖）
│   ├── FtpStore.java         FTP（commons-net，仅兼容用）
│   ├── RemoteFile.java       远程文件 + 128KB 块缓存（LRU 24MB）
│   ├── RemoteActivity.java   服务器列表：添加/编辑/删除
│   ├── RemoteGalleryActivity.java 远程素材浏览（复用 GalleryView）
│   ├── GalleryView.java      自绘网格：日期分组标题 + 2px 缝 + 捏合缩放
│   ├── MediaProbe.java       轨道探测（时长/分辨率/视频轨数）+ 缩略图
│   ├── ThumbLoader.java      缩略图内存/磁盘缓存
│   ├── PlayerActivity.java   播放页：手势、体感、播放控制、参数面板
│   ├── App.java              进程入口，装好闪退捕获
│   ├── CrashCatcher.java     未捕获异常落盘，下次启动提示并可导出
│   ├── Diag.java             诊断日志（环形缓冲 + 落盘 + 导出/分享，默认关闭）
│   ├── LogFileProvider.java  把导出的日志通过 content:// 交给分享面板
│   ├── StitchRenderer.java   GLES3 缝合渲染（UCM 投影 + 接缝羽化）
│   ├── DualVideoSource.java  双路 MediaCodec 解码 → SurfaceTexture
│   ├── AudioSource.java      AAC 音轨 → AudioTrack
│   ├── SensorOrientation.java 设备朝向（GAME_ROTATION_VECTOR，非积分，不漂移）
│   ├── OsvMeta.java         解析 djmd 轨的相机姿态 → 画面初始垂直方向
│   ├── Quat.java            四元数（视角与姿态合成）
│   ├── ViewState.java / Calib.java  视角状态 / 双鱼眼标定
│   └── GlUtil.java
└── tools/
    ├── verify_geometry.py       着色器几何自检（覆盖/接缝）
    ├── verify_view_rotation.py  视角数学自检（天顶/过极点）
    ├── parse_djmd.py            djmd protobuf 解析（姿态/传感器数据）
    ├── attitude_report.py       批量统计素材的初始 roll
    └── render_view.py           用真实鱼眼帧渲染任意视角（目视核对方向）
```

## 构建

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

发版用 release 包。签名用的是本机的 `osv-release.keystore`（已 gitignore）。
换机器时重新生成即可（包名不变就能覆盖升级，但签名变了必须卸载重装）：

```bash
keytool -genkeypair -keystore osv-release.keystore -alias osv \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass osvviewer2026 -keypass osvviewer2026 \
  -dname "CN=OSV Viewer, O=OsvViewer, C=CN"
```

需要 JDK 17+、Android SDK Platform 35。minSdk 26 / targetSdk 35，无第三方依赖（不引 AndroidX）。

安装：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 远程存储（NAS）

相册页右上「⋮」→「远程存储（NAS）」。添加服务器后点进去，会自动递归扫描并列出
`.OSV`，按日期分组、可捏合缩放，点开即播。**不下载整个视频**：播放和解码都走
`MediaDataSource` 按需读，缩略图只取关键帧附近的少量数据并缓存在本地。

### 协议选择

| 协议 | 适合 | 说明 |
| --- | --- | --- |
| **SMB2/3（推荐）** | 局域网 NAS | 加密、随机读效率最高，Synology/QNAP/TrueNAS/Windows/macOS 原生支持 |
| WebDAV | 跨网络 / 反代 / HTTPS | 本质是 HTTP + Range，能穿网关；比 SMB 多一层开销（自带 `HttpMini` 实现） |
| FTP | 只有 FTP 的旧设备 | 明文传输，随机读靠 REST，效率和安全性都最差 |

结论：**优先 SMB2/3**，出门在外走 WebDAV，FTP 只当兜底。

字段填法：SMB 的「路径」第一段是共享名（如 `videos/2026-09`）；WebDAV/FTP 填起始目录。

服务器列表长按 → 「测试连接」（编辑框里也有「测试」按钮）会真的连一次并列一次根目录，
失败时按原因给出下一步建议（网段不对 / 认证失败 / 路径不对 / 未开启 WebDAV）。
**编辑器里的「测试」不会关闭对话框**，结果直接显示在字段下方，改完可再测。
**多网段 NAS 要注意**：手机在哪个网段就用哪个 IP，填另一个网段的地址会一直超时。

密码用 Android Keystore 里的密钥做 AES/GCM 加密后存 SharedPreferences，
历史明文密码在打开服务器列表时自动升级成密文。

## 使用

1. 打开 App →「文件夹」选 Osmo 360 的素材目录（或「文件」多选几个 `.OSV`）。
   用的是 SAF 授权，不需要存储权限，选过一次会被记住。
   素材按日期分组（新的在前），每组一行标题，标题下面才是图。
   日期取值优先级：文件名里的日期 → 视频元数据的拍摄时间 → 文件系统时间
   （长按某个素材能看到这个日期来自哪里）。

   网格缩放：双指捏合，或右上「⋮」菜单里直接选。**一次捏合手势只换一档**
   （手指开合累计到 1.6 倍才触发，触发后本次手势锁住），连续换档要重新捏。
   `getScaleFactor()` 给的是「相对上次回调」的增量，不能直接和阈值比较——
   必须累计整个手势的乘积，否则阈值永远达不到（v0.4.1 捏合失效就是这个原因）。
   列数与分组粒度是联动的：

   | 每行 | 分组 |
   | --- | --- |
   | 2 / 4 个 | 按天，标题为「2026年9月1日」 |
   | 8 个 | 按月，标题为「2026年9月」 |
   | 16 个 | 按年，标题为「2026年」 |

   选过的列数会被记住；单元格之间只有 2px 缝，图片自身已有黑边，不再画边框。
2. 点卡片进入播放页。播放页控件：

   | 操作 | 效果 |
   | --- | --- |
   | 单指拖动 | 转视角，绕屏幕轴转（松手有惯性） |
   | 双指捏合 | 缩放视场角 30°~150° |
   | 双指旋转 | 画面绕视线轴滚转（可与捏合同时进行） |
   | 单击画面 | 显示/隐藏控件 |
   | ▶ / ❚❚ | 播放暂停 |
   | 进度条 | 拖动定位（暂停中也能即时出画） |
   | 「体感」 | 开关运动传感器视角 |
   | 「全景」 | 透视窗口 ↔ 整球等距矩形 |
   | ⚙ | 接缝羽化 / 接缝偏移 / 极点硬边 / 自动水平 / 循环播放 / 导出日志 |

3. 默认循环播放（⚙ 面板可关）；关掉后播完会停在最后一帧并提示「点击 ▶ 重播」。
4. 体感模式下仍可用手指微调，相当于在实体姿态上加偏移；「重置视角」把基准拉回正面，
   并按素材里的姿态数据重新摆平地平线。
5. 名字只显示主干：先去掉 `.OSV` / `.OSV.mp4` 后缀，太长时从**前面**省略，尾部
   （通常是编号）始终可见。

## 关键实现

- **解码**：`.OSV` 是标准 ISOBMFF，`MediaExtractor` 按内容嗅探即可，与扩展名无关。
  前两条 `video/` 轨依序为 cam0(master)/cam1(slave)，各自 `MediaCodec` 硬解到独立
  `SurfaceTexture`（10bit 码流由系统转换到 GL 可采样纹理）。
- **两路独立线程**：每颗镜头一条解码线程 + 一条控制线程。某一路卡在解码器里
  只会拖住它自己，另一路和进度条都不受影响（之前共用一条线程，一路卡住会连着
  进度一起冻住）。控制线程负责时钟、进度、结束判定和两路对齐。
- **时间戳对齐**：两路都按同一个播放时钟喂数据；控制线程每秒比对两路的 PTS，
  差 >200ms 打「两路不同步」标记，>500ms 直接把落后的一路 seek 回领先者位置。
- **单路自愈**：某一路超过 1.5s 没出帧、下一帧落到播放位置 3s 之后、或还没播完
  就 EOS，就把这一路 flush 后重新 seek 回当前播放位置。两路都失败才算播放失败。
- **谁用谁释放**：每路 codec/surface/extractor 只由它自己的解码线程在 `finally`
  里释放。重建纹理时若在别的线程 release，会抛 `Invalid to call at Released state`
  并把恢复路径一起带崩（v0.3.3 日志里出现过）。线程卡死宁可泄漏也不跨线程释放。
- **同步**：两路共用同一个播放时钟（`elapsedRealtime` 映射到 PTS），输入按 PTS 提前
  100ms 喂入，所以两条轨永远同帧；`PTS - 首帧PTS` 用于对齐非 0 起始的时间轴。
- **缝合**：移植自仓库里的 WebGL 播放器，几何全部解析计算。射线先投影到 cam0，
  再乘相对旋转投到 cam1，各自走 Unified Camera Model（`xi≈0.717`，能外推到 190°，
  内嵌的 OpenCV 鱼眼多项式在 92.5° 就到顶回落）；接缝取两光轴等距的那圈大圆，
  按角度差 smoothstep 羽化，极点帽区可切硬边。
- **体感**：`TYPE_GAME_ROTATION_VECTOR` 取设备背面（后置镜头方向）的绝对方位角/俯仰角，
  相对开启时刻的基准做增量映射。不用陀螺积分，没有漂移；不用磁力计，不怕磁干扰。
  基准用**完整视角四元数**（`offset = view × sensor⁻¹`），不能用 yaw/pitch 重新组装——
  180° 基线和素材倾角都是绕视线轴的滚转，重建成 yaw/pitch 会把它们丢掉，一开体感画面就颠倒。
- **触摸**：视角用四元数，拖动按「屏幕轴」做局部旋转——水平绕屏幕竖直轴、垂直绕屏幕水平轴。
  任意朝向下屏幕上的角速度都与手指位移成正比，也没有俯仰夹断，可以翻过天顶继续转；
  旧写法（yaw/pitch）在天顶附近会把同样的位移放大近千倍，而且过不去极点。
- **取帧不依赖回调**：每帧无条件 `updateTexImage()`，用 `getTimestamp()` 是否变化
  判断有没有新帧。实测某些机型/系统版本上 `OnFrameAvailableListener` 会静默停发
  （解码一直 30fps 但纹理不再更新，画面冻在旧帧），所以这条路不能作为唯一依据。
- **进度上报的时基**：进度按 200ms 节流，基准 `lastReportUs` 必须随 seek 一起清零。
  否则循环重播时播放时钟回到 0，`now - lastReportUs` 恒为负，进度再也不会回调
  （表现就是进度条归零后不再增长）。seek 时同时清掉两路的 eos，避免控制线程
  立刻又判定「播放结束」。
- **画面看门狗**：每秒比对「解码是否在出帧」和「纹理是否在更新」。GL 线程死掉
  （异常抛出 `onDrawFrame` 会永久终止渲染线程）时整条 GL 链重建；只是纹理停更时
  单独换一套 SurfaceTexture 并让解码端重开。
- **闪退可查**：`App` 创建时就装上 `CrashCatcher`，任何线程的未捕获异常都会写到
  `crash/last-crash.txt`，下次启动弹窗提示并可导出（也可以 `adb shell cat
  /sdcard/Android/data/com.osmo.viewer/files/crash/last-crash.txt`）。相册页的
  `setContentView` 也包了 try/catch：布局加载失败会进一个兜底界面显示错误并给导出按钮，
  而不是直接退出。
- **自定义 View 必须提供 (Context, AttributeSet) 构造**：`LayoutInflater` 只认这个签名，
  少它会在 inflate 时抛 `InflateException` 直接闪退（v0.4.0 就是因为这个闪退）。
- **日志默认关闭**：右上「⋮」里可以开启诊断日志，开启后才写盘并逐秒记录；
  关闭状态下导出只会得到一行说明。出问题时先开启、重现，再导出。
- **日志与导出**：解封装、解码器能力、seek、每路恢复、EOS、GL 与音频失败都写进
  `Diag`（logcat + 内存环形缓冲 + `files/logs/osv.log`）。另外**每秒一条状态**：
  两路各自的 PTS、这一秒出了多少帧、eos/failed、距上次出帧多久、两路时间戳差，
  用来区分「素材播完了」和「解码真的卡住」。渲染侧同样每秒一行 `渲染每秒`
  （绘制次数 / 两路纹理各更新了几次 / 距上次出图多久 / 视口 / program id），
  加上 `画面检查`。判读：
  - `绘制=0`：GL 线程死了（会看到 `重建 GL 链`）；
  - `绘制=60 更新0=0 更新1=0`：解码出来的帧没进纹理（回调没触发）；
  - `绘制=60 更新0=30 更新1=30 距上次出图≈16ms`：GL 正常，黑屏要去查着色器/采样。相册页右上「日志」和
  播放页 ⚙ 面板里的「导出日志」会生成 `osv-viewer-log-时间.txt`，可分享或存到
  下载目录；播放失败时会直接弹窗提供导出入口。
- **初始垂直方向**：`.OSV` 的 `djmd` 主轨每帧带一条 `FrameMetaOfCamera.camera_attitude`
  四元数（IMU 系 → 世界 Z-up）。换算到光学系后取重力的水平分量得到相机相对重力的
  滚转，再叠加 180° 基线——摄像机把 cam0 存成 y 朝上的方向，与着色器的相机系差半个
  圈，不带这 180° 时所有素材都是倒的（已用真实帧重投影逐张核对）。相机倒装时
  元数据接近 180°、叠加后正好回到 0，同样正确。取前 45 帧中位数，规避起手抖动。
  这 180° 是**固定基线**，关掉「自动水平」也照样转；自动水平只叠加素材残余倾角。
  它是 `ViewState` 的默认姿态（构造时就是 Rz(180°)），不是运行时某一刻补上去的，
  所以第一帧就是正的，也不会被体感/重置等操作弄丢。

## 已知限制

- **解码能力**：两路 3000×3000 HEVC Main10 30fps 同时硬解是这台机器的上限瓶颈。
  开启前会先查 `VideoCapabilities.isSizeSupported`，不支持时直接给出「支持范围」提示，
  而不是崩在 configure。低于该能力的设备无法播放原始素材。
- **拼接质量**：标定来自 DJI Studio 的通用参数（custom_0.json），本机若不在 DJI
  per-device 覆盖表里会有 1~2° 个体差异；可用「接缝偏移」微调。
- **近景视差**：外参只有旋转没有基线，镜头间距约 2~3 cm，30cm 内的物体在接缝上必然错位。
- **极点**：±90° 俯仰附近两路都是掠射成像，宽羽化会拖鬼影，用「极点硬边」收一下。
- **远程播放**：随机读走 SMB/HTTP Range，按 **256KB** 分块、LRU 上限 **48MB**，
  不落地整个文件。另有一个**后台预读线程**跟着最近两个读位置（双路解码）各向前
  多取 8MB，实测顺序播放命中率 ~92%、请求数减半。开播前先 `awaitBuffered` 攒够
  8MB（超时 20s 也照常开播），播放页显示「正在缓冲 x/y MB」。
  缩略图按 uri 哈希缓存在本地 `cache/thumbs`。
- **远程密码**：AES/GCM 加密存储，密钥在 Android Keystore（不可导出）。清除应用数据
  或换机后旧密文解不开，需要重新填密码。Keystore 不可用时（极少见）会退化为明文，
  且不影响功能。
- **音画**：音频独立按 AudioTrack 节奏播放，未用音频时钟反向校正视频，长片可能有几十毫秒级偏差。
- **体感方向**：按「设备背面 = 视线」推导；若个别机型上左右相反，请反馈。
- **自动水平**只做「绕光轴的滚转」，不改俯仰：素材是仰拍/俯拍时，初始画面仍朝光轴方向。
  光轴接近天顶/地面（重力水平分量过小）时不给校正，避免乱转。

## 已验证 / 待验证

- `assembleDebug` 通过，`lint` 无 error；APK 见 `app/build/outputs/apk/debug/`。
- `python3 tools/verify_geometry.py` 复现着色器数学：任意 yaw 下「两路都看不到」的像素为 0%，
  yaw=0 全来自 cam0、yaw=180° 全来自 cam1，整球等距矩形覆盖 100%，接缝落在 90°/270°。
- `python3 tools/verify_view_rotation.py` 复现视角数学：水平拖动不改变俯仰；连续下拖 200°
  的 pitch 轨迹为 1°→46°→90°→89°→44°→−20°（顺利越过天顶）；任意朝向（含正对天顶）
  手指转 5° 视线就转 5.00°，而旧欧拉实现在天顶会放大 ~1000×。
- `python3 tools/attitude_report.py <file.OSV>` 实测四段素材的 roll 中位数：
  −9.4° / +11.1° / −31.0° / +16.3°（都在小角度内，没有 180° 倒装样本）。
- `python3 tools/verify_view_rotation.py` 还覆盖双指旋转：手指顺时针 12° → 画面顺时针 12.00°。
- `python3 tools/render_view.py` 把真实鱼眼帧按不同 roll 重投影后逐张目视核对：
  0015 t=20s（元数据 −27.6° → 152.4°）与 t=45s（+6.8° → 186.8°）、0017（−32°→148°）、
  0047（+16.3°→196.3°）都用「180° + 元数据」得到正立画面，用不加 180° 的角度则是颠倒的。
- 版本 v0.5.2（versionCode 15）。修复编辑器的「测试」一点就关窗（AlertDialog 默认行为），
  结果改为内联显示；新增远程预读缓冲，开播前攒一段再放。
- 预读效果用假存储量化验过：600ms 内自动填满 8MB 窗口；顺序读 12MB 命中 92%、
  只需 23 次网络请求（无预读时 48 次）；300 次跳读后缓存仍 ≤48MB；关闭后预读线程停止。
- 版本 v0.5.1（versionCode 14）。修复 v0.5.0 的两个远程缺陷：
  1) WebDAV 完全连不上 —— Android 的 `HttpURLConnection` 禁止 `PROPFIND`
     这种自定义方法，改为自带 `HttpMini` 直接写请求行；
  2) 能列目录但打不开文件 —— URL 拼装给文件路径也加了结尾 `/`，服务器当目录返回 404。
  两处都用本地真实 HTTP 服务器 + 逐字节比对验过（含中文/空格路径、忽略 Range 的服务器、
  认证失败），见 `tools/dav_regression.md` 的复现步骤。
- 远程相关的纯逻辑用离线用例验过：块缓存跨块读/末尾截断/越界/命中，WebDAV
  href 相对化（含绝对 URL、百分号编码、跨目录过滤）全部通过。
- 未验证：真机连 NAS 的实测（SMB 认证方式、FTP 的 REST 支持、远程 3000×3000
  解码的带宽余量）。修复相册捏合缩放不生效
  （增量因子误当总倍数用）；一次手势只换一档。v0.4.0 启动闪退的原因已定位：
  `GalleryView` 缺少 `(Context, AttributeSet)` 构造，`LayoutInflater` 找不到就抛异常。
  已用 dexdump 核对新旧包的构造器签名确认。
- 日期解析用真实文件名验过：`CAM_20260901080026_0015_D.OSV`、`DJI_...`、
  `2026-09-01 08.00.26.mp4`、`20260901.osv` 都解析为 2026-09-01；非法日期
  `20261332` 被拒绝并回落到下一级来源。
- 真机行为（硬解是否支持 3000×3000、实际帧率、体感手感、接缝观感）需要装机验证。
