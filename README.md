# ETS 资源查看器

通过 Shizuku API 提权读取 `/storage/emulated/0/Android/data/com.ets100.secondary/files/Download/ETS_SECONDARY/resource/` 下的资源文件夹，按时间分组展示内容。

## 本次修改（可读性优化）

1. **去掉 HTML 标签**：`content.json` 里 `value` 中的 `<p>`、`<br>`、`<span>`、`&nbsp;` 等标签与实体统一转成纯文本
   （块级标签转为真实换行，多余空行压缩为一个），展示时不再出现原始标签。
2. **优化可读性**：内容块改为浅底圆角卡片（内边距 12dp）、正文 15sp、行距 +4dp；
   Part B 按题目分块，`第 N 题` 小标题加粗，答案带 `1. 2. 3.` 序号。
3. **颜色区分**：Part A 蓝色 / Part B 紫色 / Part C 绿色 —— 标题 Chip 与内容块背景同色系，
   并额外提供 `values-night` 深色配色，保证夜间模式对比度。
4. **每题只保留前 3 个答案**：Part B（`collector.3q5a`）递归收集的所有 `std` 数组，
   每个数组视为一道题，只取前 `MAX_ANSWERS_PER_QUESTION = 3` 条（常量在 `ResourceRepository.kt` 顶部）。

## 本次修改（Shizuku 运行时修复 + 改名）

1. **修复 `SecurityException: Permission Denial: newProcess`**：新版 Shizuku 服务端（v13）在**运行时**禁用了
   `Shizuku.newProcess()`——即使权限已授予，调用也会被服务端拒绝（`newProcess ... requires permission`）。
   这正是日志里「available=true, granted=true 但命令全部失败」的根因。
   官方推荐的替代方案是 **UserService**：Shizuku 拉起一个以 shell(2000) 身份运行的独立进程，载入我们自己的类干活。
   - 新增 `app/src/main/aidl/.../IUserFsService.aidl`：定义列目录 / 读文件 / 删除 / 执行命令接口。
   - 新增 `UserFsService.kt`：运行在 shell 进程中的实现，用 Java File API（列目录、读文件、删除）+ `ProcessBuilder`（兜底命令）。
     注意此类**不能用 AppLog**（依赖 App 进程 Context），失败原因经 `lastError()` 传回 App 进程写日志。
   - `ShizukuHelper` 改为双通道自动择优：优先 UserService（`prepareUserService()` 在每次加载前绑定，8 秒超时），
     服务端不支持时回退 `newProcess`。所有文件操作（listFolders / readFile / deleteRecursive）都封装在这里，
     `ResourceRepository` 不再直接拼 shell 命令。
   - `build.gradle` 开启 `buildFeatures.aidl true`；`MainActivity.onDestroy` 里 `releaseUserService()` 解绑。
   - 依赖说明：`dev.rikka.shizuku:api` / `:provider` 仍固定 **13.0.0**（13.1.x 起 `newProcess` 在客户端被改为 private，
     编译不过；13.0.0 保留 public 作回退通道）。UserService 在 13.0.0 中已完整支持。
2. **包名改为 `com.fuckets.etsviewer`**：`applicationId`、`namespace`、源码目录、`IUserFsService.aidl` 的 package 全部同步。
   UserService 的 ComponentName 用 `context.packageName` 动态取，不硬编码。
3. **开发者署名**：`strings.xml` 的 `about_developer_name` 改为「HOSHINO、moxiao9414」，关于页直接读取。

## 本次修改（性能优化）

1. **批量扫描替代 N+1 次跨进程调用（最大收益）**：原来每读一个文件夹的 `content.json` 都是一次
   Binder IPC（UserService）或一个独立 shell 进程（回退通道），N 个文件夹 = N+1 次往返。
   现在 `IUserFsService.loadEntries(basePath, offset, limit)` 一次调用拿回全部文件夹的名称/时间/内容：
   - UserService 通道：分页拉取（50 条/页），防 Binder 事务 ~1MB 上限（TransactionTooLargeException）
   - 回退通道：一条 shell 命令输出全量（`\u0001` 分隔字段、`\u0002` 分隔记录；合法 JSON 不含原始控制字符，安全）
   - 已在 Linux sh 下实测：正常读取 / 缺 content.json / 空目录三种边界均正确
2. **正则预编译**：`cleanText` 与解析路径的正则全部提升为顶层常量
   （原来每次调用临时构造 Regex 即编译，一次加载要编译几百次）。
3. **Part 视图惰性构建**：折叠的组不再 inflate 子视图（大文本 + 可选中 TextView 布局很贵），
   首次展开时才构建——组数多时首屏与滚动明显变快。
4. **DEBUG 日志跟随构建类型**：`AppLog.verbose` 默认 = `FLAG_DEBUGGABLE`，
   release 包不再全量写 D 级日志（省字符串拼接与文件 I/O）；排障时可改回 true。
5. **后台暂停光斑动画**：三个页面 `onPause` 暂停、`onResume` 恢复背景动画，退后台不费 GPU。

## 折叠/展开

- 每组试题**默认折叠**，标题行显示「第 N 组 · M 项」，点击整行（含右侧箭头）展开/折叠，箭头带 150ms 旋转动画。
- 展开状态保存在 `GroupAdapter` 的 `ExpandState` 里，**滚动回收后不会丢失**；下拉刷新重新加载数据时恢复"全部折叠"。
- 菜单里另有 **展开全部 / 折叠全部**（`GroupAdapter.expandAll()`）。

## 液态玻璃 UI

- **背景**：`drawable/bg_base.xml` 竖向渐变打底 + `layout/bg_liquid.xml` 四个彩色光斑（径向渐变）。
  光斑由 `LiquidGlass.applyBackdrop()` 用 `TRANSLATION_X/Y` + `SCALE` 缓慢漂移（9~16s 一轮、反向循环），
  只走 GPU 变换，不触发重绘。
- **真实背景模糊**：Android 12（API 31）以上对光斑层施加 `RenderEffect.createBlurEffect()`，
  玻璃后面透出来的是真的糊的；低版本由径向渐变自身足够柔和，观感接近。
  低端机发涩可把 `LiquidGlass.BACKDROP_BLUR_PX` 改成 `0f` 关掉模糊。
- **玻璃面板**：`LiquidGlass.surface()` 返回 `LayerDrawable`——
  ① 半透明底色（浅色 55% 白 / 深色 10% 白，可混主题色）② 顶部镜面高光（白→透明渐变）③ 1dp 亮边描边。
- **应用位置**：组卡片（中性玻璃，圆角 28dp）、组序号"玻璃珠"（按组序在 蓝/紫/绿 间轮转）、
  Part 内容块（按 A/B/C 着色的带色玻璃）、Chip（半透明底 + 彩色描边）、顶栏与状态提示。
- 深色模式：底色与光斑颜色都有一套 `values-night` 版本，玻璃厚度与描边亮度自动调整。

## 长按删除

- **长按组标题行** → 弹确认框 → 删除该组全部资源文件夹（Part A/B/C 对应的文件夹）。
- **长按单个 Part 卡片**（标题 Chip / 文件夹名 / 卡片空白处）→ 只删这一个文件夹。
  > 长按**正文**会被系统的「文字选择」接管（这是 Android 默认行为），此时不会弹删除框，长按时避开正文即可。
- 确认框会列出即将删除的文件夹名，删除为**物理删除、不可恢复**。
- 删除走 `ResourceRepository.deleteFolders()`：`rm -rf` 后 `ls -d` 复核一遍，
  返回成功/失败明细；文件夹名会做安全校验（拒绝 `.`/`..`/含 `/` 或引号的名字）。
- 删完自动重新加载列表，并用 Toast 提示「已删除 N 个文件夹」或失败明细。

## 关于页

- 入口：主界面右上角菜单 → **关于**。
- 应用标志 `drawable/ic_app_logo.xml`：48dp 画布的**矢量图**，圆形玻璃底 + 试卷卡 + 三条彩色内容行
  （蓝 / 紫 / 绿 = Part A / B / C 主题色），任意尺寸放大不失真。
- 页面内容：
  - **版本**：用 `PackageManager` 读取 `versionName` + 构建号（不依赖 `BuildConfig`，AGP 8 起默认不生成）
  - **开发者**：取自 `res/values/strings.xml` 的 `about_developer_name`，改署名只改这一行
  - 包名、Shizuku 授权状态与服务版本、设备型号、Android 版本
  - 功能简介、技术栈清单
  - 「查看运行日志」按钮直达日志页
- 三张信息卡与日志页同样套用 `LiquidGlass.surface()` 玻璃背景。

## 日志功能（排查问题用）

- **双通道输出**：`AppLog.d/i/w/e()` 同时写 Logcat（过滤关键字 **`ETSViewer`**）和本地文件
  `filesDir/logs/ets-viewer.log`（超过 2MB 自动裁剪，保留最近 3000 行）。
- **崩溃不丢现场**：`EtsApp` 里注册了 `UncaughtExceptionHandler`，崩溃时把堆栈写进日志并 `flush()` 落盘；
  内存中还常驻最近 800 条，即使来不及写文件也能在日志页看到。
- **App 内查看**：主界面右上角菜单 → **运行日志**，可刷新 / 复制 / 分享（导出为文本）/ 清空。
- **关键埋点**：
  | 标签 | 记录内容 |
  | --- | --- |
  | `Shizuku` | 服务可用性、权限状态与申请结果、Shizuku 版本号 |
  | `Shell` | 每条执行的 shell 命令、耗时、exit code、stdout 长度、stderr 内容 |
  | `Repo` | 扫描到的文件夹数、缺 `content.json` 的文件夹、每个文件夹的 `structure_type`、解析出的题目/答案数量、JSON 解析异常 |
  | `Text` | HTML 剥离前后的字符数变化 |
  | `Adapter` / `Main` / `Crash` | 分组绑定情况、加载耗时、未捕获异常 |

  调 `AppLog.verbose = false` 可关闭 DEBUG 级日志；`AppLog.truncate()` 用于截断超长文本。

## 功能逻辑

1. 启动时检测 Shizuku 服务，未授权则弹窗申请权限
2. 以 shell 身份执行命令列出 resource 目录下所有文件夹（**排除 `common`**）
3. 按文件夹名中的时间戳（无则按修改时间）**按时间排序**
4. 剔除没有 `content.json` 的文件夹，然后**按时间最接近的 3 个一组**分组
5. 解析每组内文件夹的 `content.json`，按 `structure_type` 分类：
   - `collector.read` → **Part A**：显示 `info` 下的 `value`
   - `collector.3q5a` → **Part B**：显示所有 `"std": [...]` 数组中每个对象的 `value`，每个数组（即每题）只取前 3 条
   - `collector.picture` → **Part C**：
     - 新结构：`info.std[]`（每条含 `value` / `ai` / `audio`）→ 取 `value`（为空时回退 `ai`），**只取第 1 条**（`MAX_ANSWERS_PART_C = 1`）
     - 兼容老结构：`info.stu.value`（单条文本）
     - **Part C 只展示正文，`info.keypoint` 不再显示**
     - 只有一条答案时不加 `1.` 编号，直接输出整段正文
   - `info.keypoint` 若存在，Part A / Part B 会在正文后追加一段**要点**（`</br>` 转成换行，按行编号）；Part C 不追加
   - 组内按 Part A → B → C 顺序展示
6. 界面为 Material Design 3 风格卡片列表，支持下拉刷新

## 构建方法

1. 安装 Android Studio（含 Android SDK 34）
2. 用 Android Studio 打开本目录 `ets-resource-viewer`
3. 等待 Gradle 同步完成后，Run → 安装到手机；或执行 `./gradlew assembleDebug` 生成 APK

## 使用前提

- 手机已安装并启动 **Shizuku**（ADB 激活或 root 激活均可）
- 手机上存在目标应用 `com.ets100.secondary` 且资源目录已生成

## 主要文件

| 文件 | 说明 |
| --- | --- |
| `ShizukuHelper.kt` | Shizuku 权限申请与 shell 命令执行封装 |
| `ResourceRepository.kt` | 文件夹枚举、排序、分组、content.json 解析 |
| `MainActivity.kt` | 主界面，权限流程与数据加载 |
| `GroupAdapter.kt` | MD3 卡片列表适配器 |
