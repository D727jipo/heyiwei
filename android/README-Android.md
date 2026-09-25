# Android 版: 大数计算器 (APK)

用 **Android 自带框架**(`android.app` / `android.widget` / 平台 XML 布局)实现的计算器，
**不依赖 Gradle、AndroidX、Material 等任何第三方组件**，只用 Android SDK 自带的
`aapt2 + javac + d8 + zipalign + apksigner` 手工打包。

产物：`D:\计算器\calc-android.apk` (约 37 KB)

---

## 1. 安装

* 手机打开“允许安装未知来源应用”，把 `calc-android.apk` 传到手机点击安装；或
* 用 adb：

```
adb install -r D:\计算器\calc-android.apk
```

签名信息：debug 自签名证书，**v1 + v2 + v3** 三种方案，Android 5.0(API 21) ~ Android 15+ 均可安装。

| 项目 | 值 |
|------|-----|
| 包名 | `com.dsh.calc` |
| 应用名 | 大数计算器 |
| minSdkVersion | 21 (Android 5.0) |
| targetSdkVersion | 34 (Android 14) |
| compileSdk | 35 |
| 权限 | 无（不联网、不读文件，只用到剪贴板写入） |
| 体积 | 一个 dex，无 so 库，任何 CPU 架构通用 |

---

## 2. 界面与功能

```
┌──────────────────────────────────────┐
│ [输入表达式: 2^100           ]        │
│ ☑ 边输入边算(实时)                    │
│ 1267650600228229401496703205376       │  ← 结果(可选中)
│ 共 31 个字符                          │
│ [████████░░░░░░] 次方: 第 19/20 步…   │  ← 长计算实时进度
│  C  ←  (   )   ÷                      │
│  7  8  9   ^   ×                      │
│  4  5  6   √   −                      │
│  1  2  3   .   +                      │
│  0  π  e  设置  =                     │
│ [ 复制完整结果 ]                       │
└──────────────────────────────────────┘
```

* **边输入边算**：默认开启，输入停顿 0.3 秒后自动在后台线程计算并显示结果（不等回车）。
* **长计算实时进度**：像 `2^1000000` 这种要算一会儿的，进度条 + 文字实时显示
  “第几步 / 平方规模 / 约完成百分之几”，与桌面版的实时进度是同一套埋点。
* **超长结果**：结果超过 4000 字符时只渲染头尾预览，避免卡顿；
  完整结果点“复制完整结果”一键进剪贴板。
* **设置**：近似有效位数(默认 50)、结果位数安全上限(默认 100 万)、解除上限开关，
  保存在 SharedPreferences 里。

---

## 3. 支持的运算（与桌面版 calc.exe 完全一致）

| 运算 | 写法 | 说明 |
|------|------|------|
| 加/减/乘/除 | `+ - × ÷`（也认 `* /`） | 除数为 0 会提示 `错误: 除数不能等于0` |
| 开平方 | `√x` 或 `sqrt(x)` | 完全平方给精确值；负数报“负数不能开平方” |
| 次方 | `x^y` 或 `pow(x,y)`、`x**y` | 整数指数用二进制快速幂，**精确且不限大小** |
| 括号 | `( )`（也认全角） | |
| 常量 | `pi`、`e` | 按当前有效位数取近似 |
| 小数/科学计数 | `1.5`、`.5`、`1.5e10` | |

语义细节与桌面版逐条对齐（已用脚本比对 82 个用例，结果/错误/退出码完全一致）：

```
1+2*3        = 7
(1+2)*3/4    = 2.25
1/0          → 错误: 除数不能等于0
2^100        = 1267650600228229401496703205376      (精确)
2^1000       = 302 位精确值
2^1000000    = 301030 位精确值，过程中显示实时进度
sqrt(2)      = 1.4142135623730950488016887242096980785696718753769  (近似值, 50 位)
1/8          = 0.125                                (能整除就是精确值)
2^0.5        = 1.4142135623731                      (非整数指数, 浮点近似, 15 位)
```

---

## 4. 源码结构

```
D:\计算器\android\
├── AndroidManifest.xml          清单(包名/权限/入口 Activity)
├── res\
│   ├── layout\activity_main.xml     主界面(平台控件, GridLayout 键盘)
│   ├── layout\dialog_settings.xml   设置对话框
│   ├── values\strings.xml           文案
│   ├── values\styles.xml            按键样式
│   └── drawable\ic_launcher.xml     矢量图标(VectorDrawable)
├── src\com\dsh\calc\
│   ├── BigDec.java              任意精度十进制数(整数用平台 BigInteger,
│   │                            十进制层: 精度/舍入/精确判定/次方/开方/格式化/进度回调)
│   ├── Calc.java                Limits、CalcException、词法分析、递归下降求值
│   └── MainActivity.java        原生 Activity: 键盘、实时求值、后台线程、进度、复制、设置
├── test\
│   ├── com\dsh\calc\JavaCalcMain.java   桌面 JVM 测试入口(不属于 APK)
│   └── compare_with_exe.py              与桌面版 calc.exe 逐条对比的脚本
├── build_apk.bat                一键打包(不需要 Gradle)
└── build\                       中间产物(res.zip / classes / dex / keystore)
```

### 编译期依赖

* Android SDK：`aapt2`、`d8`、`zipalign`、`apksigner`、`platforms\android-35\android.jar`
  （脚本默认找 `E:\SDK`，可用环境变量 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 覆盖）
* JDK（脚本自动在 `C:\Program Files\Java\jdk-*` 里找一个可用的）

构建：

```
cd D:\计算器\android
build_apk.bat
```

脚本流程：`aapt2 compile` → `aapt2 link` → `javac`(对 android.jar 编译) → `jar`+`d8` →
把 `classes.dex` 打进 APK → `zipalign` → `apksigner sign`(自动生成 debug keystore) →
`apksigner verify` + `aapt2 dump badging`。

---

## 5. 与桌面版的对应关系

| | 桌面版 calc.exe | Android 版 APK |
|---|---|---|
| 大数实现 | 自研 C++ BigInt(10^9 进制 + Karatsuba + Knuth 除法) | 平台 `java.math.BigInteger` + 自研十进制层 |
| 语义 | 基准 | 与基准逐条对比 82 用例，0 差异 |
| 实时输出 | stderr 上原地刷新“算到哪了” | 界面进度条 + 文字，同一套进度埋点 |
| 交互 | `calc> ` 逐行输入 | 触摸键盘 + 边输入边算 |
| 位数安全上限 | `--force` / `force on` | 设置里“解除位数上限” |
