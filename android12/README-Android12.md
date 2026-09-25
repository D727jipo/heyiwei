# Android 12 版 (Material You) · 大数计算器 12

在原有版本基础上，改用 **Android 12 自带 UI 配置**（现代 Material You 观感）重新做的一版。
原有 APK 完全保留、未做任何改动，两个包名不同，**可以同时装在一台手机上**。

| | 旧版 | 新版 |
|---|---|---|
| 文件 | `calc-android.apk` (36.6 KB) | **`calc-android12.apk`** |
| 版本 | 1.0 | **2.1** (versionCode 21) |
| 包名 | `com.dsh.calc` | `com.dsh.calc12` |
| 应用名 | 大数计算器 | 大数计算器 12 |
| 界面 | `Theme.DeviceDefault.Light`，朴素按键 | Android 12 `Theme.DeviceDefault.DayNight` + 动态取色 |
| 依赖 | 仅系统框架 | 仅系统框架（依然零第三方库） |

---

## 版本历史与版本号规则

版本号写在 `AndroidManifest.xml` 里（**唯一来源**，`build_apk.bat` 不再用
`--version-code/--version-name` 覆盖它）：

```xml
android:versionCode="21"
android:versionName="2.1"
```

规则：`versionCode = 主版本 * 10 + 次版本`，每次改动次版本 +1，以此类推（2.2 → 22、2.3 → 23 …）。

| 版本 | versionCode | 内容 |
|---|---|---|
| **2.3** | 23 | **修复「结果太长被省略、复制不到完整数字」**：结果框只放头尾预览（几十万位全塞进 `TextView` 会把界面拖死），新增**完整结果查看器** —— 分页浏览全部数字（每页 5 万字符，首页/上一页/下一页/末页）、**复制本页**、**复制全部**、**导出到文件**（系统自带 `ACTION_CREATE_DOCUMENT`，上百万位也能完整落盘）。长结果时结果框不再允许选中复制（避免复制到省略版），改成**点击直接打开查看器**；菜单里也加了「查看全部数字」「导出到文件」。 |
| **2.2** | 22 | 新增设置项**「背景颜色」**（与精度/位数上限相互独立）：默认 **跟随系统**；选 **手动选择** 后可指定 **白色**（浅色）或 **深色**。实现用的是平台自带能力——`createConfigurationContext()` 只给本 Activity 套一份覆盖了 `uiMode` 的 `Configuration`，不引入 AppCompat；`values-night` / `values-v31` 等资源会按所选模式重新解析，所以背景、卡片、按键、文字颜色会整体切换。切回"跟随系统"即不再覆盖，恢复跟随系统深浅色。 |
| **2.1** | 21 | **修复 π 按钮无效**：键盘上的 π 键往输入框插入的是 `π` 字符(U+03C0)，而表达式解析器只认字母 `pi`，所以点完算不出来（报"未知的名称: π"）。原因是 `π` 属于 Unicode 字母，会被词法分析当成标识符处理，而不是像 `√ × ÷` 那样走符号分支。现在在标识符分支把 `π`/`Π` 归一化成 `pi`，两者完全等价。 |
| 2.0 | 20 | 首个 Android 12 (Material You) 版本：动态取色、DayNight、系统开屏画面、圆角/水波纹/卡片、edge-to-edge、原生 PopupMenu 菜单。 |

> 旧版 `calc-android.apk` 按你的要求**一个字节都没动**，所以它里面的 π 键仍有同样的问题。
> 需要的话执行 `D:\计算器\android\build_apk.bat` 会用同一份已修复的核心重新打包（包名/版本仍是 1.0）。

---

## 新版用到的 Android 12 自带 UI 能力

全部来自系统框架，**没有引入 Material Components / AndroidX / Gradle**：

1. **Material You 动态取色**：`@android:color/system_accent1_*` / `system_neutral1_*` / `system_neutral2_*`
   —— 界面配色跟随用户壁纸（`res/values-v31/colors.xml`、`res/values-night-v31/colors.xml`）。
2. **Android 12 系统开屏画面**：主题属性 `windowSplashScreenBackground`、
   `windowSplashScreenAnimatedIcon`、`windowSplashScreenIconBackgroundColor`。
3. **DayNight 深浅色**：`Theme.DeviceDefault.DayNight`，自动跟随系统深色模式；
   Android 9 及以下自动回退到 Material 浅色/深色主题（`values-night/`）。
4. **原生现代控件观感**：`RippleDrawable` 水波纹按键、`ShapeDrawable` 大圆角（输入框 28dp、卡片 24dp、按键 20dp）、
   `elevation` 卡片投影、`PopupMenu` 原生菜单（标题栏 ⋮）。
5. **edge-to-edge 沉浸式**：`setDecorFitsSystemWindows(false)` + `WindowInsets` 适配状态栏/手势条/刘海，
   状态栏与导航栏透明。

界面结构：标题栏（大标题 + ⋮ 菜单）→ 圆角输入框 → 实时开关 → 圆角结果卡片（结果 / 字符数 / 进度条）
→ 5×5 圆角按键（数字、`( ) ÷ ^ × √ − . +`、`0 π e 复制 =`）→ 底部说明。

---

## 安装

```
adb install -r D:\计算器\calc-android12.apk
```

或直接传到手机点击安装。签名 v1+v2+v3，minSdk 21（Android 5.0）～ targetSdk 34，
动态取色/开屏画面在 Android 12+ 生效，旧系统自动使用回退主题。

---

## 功能（与旧版/桌面版完全一致）

计算核心与桌面版 `calc.exe` 是**同一份代码**（`android/src/com/dsh/calc/BigDec.java`、`Calc.java`），
新版只换了 UI 与资源，因此语义完全一致：

* `+ − × ÷`，除数为 0 → `错误: 除数不能等于0`
* `√` / `sqrt()` 开平方，完全平方精确，负数报错
* `^` / `pow()` / `**` 次方，整数指数用二进制快速幂，**精确且不限大小**（`2^1000000` 也能算）
* 括号、`pi`、`e`、小数与科学计数法；键盘上的 **`π` 与 `pi` 等价**（`π*2`、`π^2` 都能算）
* 边输入边算（实时）；耗时计算在后台线程跑，进度条实时显示"第几步 / 中间结果多少位 / 约完成百分之几"
* 超长结果：结果框只放头尾预览（避免几十万位把界面拖死），点 **「查看全部数字」**（或直接点结果框）
  打开**完整结果查看器** —— 分页浏览全部数字（每页 5 万字符）、**复制本页** / **复制全部** / **导出到文件**
* 菜单（⋮）：计算设置 / 复制完整结果 / **查看全部数字** / **导出到文件** / 关于
* 菜单 → 计算设置：有效位数、结果位数上限、解除上限
* 菜单 → 计算设置 → **背景颜色**：默认**跟随系统**；选「手动选择」后可指定**白色**或**深色**，
  切换立即生效并记住（与精度设置分开保存）

---

## 源码结构

```
D:\计算器\
├── calc-android.apk        旧版(未改动)
├── calc-android12.apk      新版(Android 12 UI)
├── android\                旧版工程 + 共用计算核心
│   ├── src\com\dsh\calc\
│   │   ├── BigDec.java          任意精度十进制数(共用)
│   │   ├── Calc.java            词法/语法/求值(共用)
│   │   ├── CalcException.java   错误类型(共用)
│   │   ├── Limits.java          精度与上限(共用)
│   │   └── MainActivity.java    旧版界面
│   └── build_apk.bat
└── android12\              新版工程
    ├── AndroidManifest.xml       package=com.dsh.calc12, theme=@style/AppTheme
    ├── res\
    │   ├── values\ themes / colors / styles / strings
    │   ├── values-night\         深色回退配色与主题
    │   ├── values-v31\           Android 12 动态取色 + DayNight + SplashScreen
    │   ├── values-night-v31\     深色 + Android 12
    │   ├── drawable\             圆角/水波纹/图标(矢量)
    │   ├── mipmap*\              自适应图标
    │   └── layout\
    ├── src\com\dsh\calc12\MainActivity.java   新版界面
    └── build_apk.bat            一键打包(输出 ..\calc-android12.apk)
```

重新构建新版：

```
cd D:\计算器\android12
build_apk.bat
```

脚本同样只用 SDK 自带工具：`aapt2 → javac → jar+d8 → zipalign → apksigner`，
并直接复用 `..\android\src\com\dsh\calc` 下的计算核心（编译期通过 `-classpath` 引用，
不是复制代码）。

> 注意：批处理脚本必须保持纯 ASCII 内容——`cmd.exe` 用 GBK 解析文件，
> 含中文注释会导致脚本被截断（这次踩过坑）。
