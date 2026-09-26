using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel.DataTransfer;

namespace CalcGui;

public sealed partial class MainWindow : Window
{
    // 与安卓版一致的常量
    private const int ShowMaxChars = 4000;
    private const int ClipboardWarn = 100000;

    // 设置(持久化到 LocalSettings)
    private int precision = 50;
    private int maxDigits = 1000000;
    private bool force;
    private string themeMode = "dark";   // dark / light / system

    // 计算状态
    private string fullResult = "";
    private string lastValue = null;     // 最近一次完整结果(供 M+/M− 使用)
    private string memory = null;        // 记忆存储
    private bool degMode;                // false=Rad, true=Deg
    private bool invMode;
    private bool sciMode;
    private int generation;
    private volatile bool computing;

    private static readonly Regex PercentRegex = new("约完成\\s*(\\d+)%", RegexOptions.Compiled);

    public MainWindow()
    {
        InitializeComponent();
        Title = "科学计算器plus(科学计算器+)";
        LoadSettings();
        ApplyTheme();

        // 窗口尺寸(仿安卓横屏布局, 桌面上略微加宽)
        try
        {
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            var windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hwnd);
            var appWindow = Microsoft.UI.Windowing.AppWindow.GetFromWindowId(windowId);
            appWindow.Resize(new Windows.Graphics.SizeInt32(1180, 700));
        }
        catch { /* 尺寸调整失败不影响使用 */ }

        WireKeys();
        Closed += (s, e) => SaveSettings();
    }

    // ======================= 按键绑定 =======================

    private void WireKeys()
    {
        // 普通键盘插入键
        Bind(KeyLP, "(");  Bind(KeyRP, ")");
        Bind(KeyDiv, "÷"); Bind(KeyPow, "^");  Bind(KeyMul, "×");
        Bind(KeySqrt, "sqrt("); Bind(KeyMinus, "−");
        Bind(Key0, "0"); Bind(Key1, "1"); Bind(Key2, "2"); Bind(Key3, "3"); Bind(Key4, "4");
        Bind(Key5, "5"); Bind(Key6, "6"); Bind(Key7, "7"); Bind(Key8, "8"); Bind(Key9, "9");
        Bind(KeyDot, "."); Bind(KeyPlus, "+");
        Bind(KeyPi, "π");  Bind(KeyE, "e");

        // 科学键盘插入键
        Bind(skLP, "(");   Bind(skRP, ")");
        Bind(skRecip, "1/("); Bind(skSq, "^2"); Bind(skCube, "^3");
        Bind(skPowY, "^"); Bind(skFact, "fact("); Bind(skSqrt, "sqrt(");
        Bind(skRootY, "^(1/"); Bind(skE, "e"); Bind(skPi, "π");
        Bind(sk0, "0"); Bind(sk1, "1"); Bind(sk2, "2"); Bind(sk3, "3"); Bind(sk4, "4");
        Bind(sk5, "5"); Bind(sk6, "6"); Bind(sk7, "7"); Bind(sk8, "8"); Bind(sk9, "9");
        Bind(skDiv, "÷");  Bind(skMul, "×");
        Bind(skMinus, "−"); Bind(skPlus, "+");
        Bind(skPercent, "/100"); Bind(skDot, ".");
    }

    private void Bind(Button b, string text)
    {
        b.Click += (s, e) => Insert(text);
    }

    private void Insert(string s)
    {
        int start = InputBox.SelectionStart < 0 ? 0 : InputBox.SelectionStart;
        int len = Math.Max(0, InputBox.SelectionLength);
        if (len > 0) InputBox.Text = InputBox.Text.Remove(start, len);
        InputBox.Text = InputBox.Text.Insert(Math.Min(start, InputBox.Text.Length), s);
        InputBox.SelectionStart = start + s.Length;
        InputBox.SelectionLength = 0;
        InputBox.Focus(FocusState.Programmatic);
    }

    private void Backspace()
    {
        int start = InputBox.SelectionStart < 0 ? 0 : InputBox.SelectionStart;
        int len = Math.Max(0, InputBox.SelectionLength);
        if (len > 0)
        {
            InputBox.Text = InputBox.Text.Remove(start, len);
            InputBox.SelectionStart = start;
            return;
        }
        if (start > 0)
        {
            InputBox.Text = InputBox.Text.Remove(start - 1, 1);
            InputBox.SelectionStart = start - 1;
        }
        InputBox.Focus(FocusState.Programmatic);
    }

    // ======================= Inv / Rad-Deg / 动态插入键 =======================

    private string TrigText(string f)
    {
        if (!degMode) return f + "(";
        return f.StartsWith("a") ? "deg(" + f + "(" : f + "(rad(";
    }

    private string DynText(string key)
    {
        return key switch
        {
            "sin" => TrigText(invMode ? "asin" : "sin"),
            "cos" => TrigText(invMode ? "acos" : "cos"),
            "tan" => TrigText(invMode ? "atan" : "tan"),
            "ln" => invMode ? "exp(" : "ln(",
            "log" => invMode ? "10^(" : "log10(",
            "exp" => "exp(",
            _ => "",
        };
    }

    private void DynamicKey_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button b) Insert(DynText(b.Tag as string));
    }

    private void InvButton_Click(object sender, RoutedEventArgs e)
    {
        invMode = !invMode;
        skSin.Content = invMode ? "asin" : "sin";
        skCos.Content = invMode ? "acos" : "cos";
        skTan.Content = invMode ? "atan" : "tan";
        skLn.Content = invMode ? "eˣ" : "ln";
        skLog.Content = invMode ? "10ˣ" : "log";
    }

    private void RadDegButton_Click(object sender, RoutedEventArgs e)
    {
        degMode = !degMode;
        skRadDeg.Content = degMode ? "Deg" : "Rad";
        NoteText.Text = degMode ? "角度制(Deg)" : "弧度制(Rad)";
        NoteText.Foreground = PrimaryBrush;
    }

    // 主题相关画刷(按当前实际主题取色)
    private Brush ErrorBrush => RootGrid.ActualTheme == ElementTheme.Dark
        ? FromRgb(0xF2, 0xB8, 0xB5) : FromRgb(0xB3, 0x26, 0x1E);
    private Brush PrimaryBrush => RootGrid.ActualTheme == ElementTheme.Dark
        ? FromRgb(0xD0, 0xBC, 0xFF) : FromRgb(0x67, 0x50, 0xA4);
    private Brush VariantBrush => RootGrid.ActualTheme == ElementTheme.Dark
        ? FromRgb(0xCA, 0xC4, 0xD0) : FromRgb(0x49, 0x45, 0x4F);

    private static SolidColorBrush FromRgb(byte r, byte g, byte b)
        => new SolidColorBrush(Windows.UI.Color.FromArgb(0xFF, r, g, b));

    // ======================= 记忆 =======================

    private async void MCButton_Click(object sender, RoutedEventArgs e)
    {
        memory = null;
        NoteText.Text = "记忆已清除";
    }

    private async void MPlusButton_Click(object sender, RoutedEventArgs e) => await MemoryAddAsync(false);
    private async void MMinusButton_Click(object sender, RoutedEventArgs e) => await MemoryAddAsync(true);

    private async Task MemoryAddAsync(bool subtract)
    {
        if (lastValue == null)
        {
            NoteText.Text = "还没有结果, 先按 = 计算一次";
            NoteText.Foreground = ErrorBrush;
            return;
        }
        // 括号包住负数, 引擎自己处理 (m)-(-5) 的语义
        string expr = $"({memory ?? "0"}){(subtract ? "-" : "+")}({lastValue})";
        await RunEngineAsync(expr, result =>
        {
            memory = result;
            NoteText.Text = $"{(subtract ? "M−" : "M+")} 已存入: {result}";
        });
    }

    private async void MRButton_Click(object sender, RoutedEventArgs e)
    {
        if (memory == null)
        {
            NoteText.Text = "记忆为空";
            return;
        }
        Insert(memory);
        await Task.CompletedTask;
    }

    // ======================= 模式/顶栏 =======================

    private void ModeButton_Click(object sender, RoutedEventArgs e)
    {
        sciMode = !sciMode;
        NormalPad.Visibility = sciMode ? Visibility.Collapsed : Visibility.Visible;
        SciPad.Visibility = sciMode ? Visibility.Visible : Visibility.Collapsed;
        ModeButton.Content = sciMode ? "普通模式" : "科学";
        HintText.Text = sciMode
            ? "Inv 切换反函数 · Rad/Deg 切换角度制 · % 即 /100 · 缺右括号自动补全"
            : "支持 + − × ÷ ^(次方) √(开平方) 括号; 函数 sqrt(x) pow(a,b); 常量 pi e · 大数精确计算";
    }

    private async void CopyButton_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(fullResult))
        {
            NoteText.Text = "还没有结果";
            return;
        }
        var pkg = new DataPackage();
        pkg.SetText(fullResult);
        Clipboard.SetContent(pkg);
        string msg = $"已复制全部 {fullResult.Length} 个字符";
        if (fullResult.Length > ClipboardWarn) msg += "\n(文本很长, 若粘贴出来不完整请用「设置→导出」)";
        NoteText.Text = msg;
        await Task.CompletedTask;
    }

    private void ClearButton_Click(object sender, RoutedEventArgs e)
    {
        InputBox.Text = "";
        fullResult = "";
        lastValue = null;
        ResultText.Text = "";
        NoteText.Text = "";
        ViewAllButton.Visibility = Visibility.Collapsed;
        HideProgress();
    }

    private void BackspaceButton_Click(object sender, RoutedEventArgs e) => Backspace();

    private void EqButton_Click(object sender, RoutedEventArgs e)
    {
        string expr = AutoCloseParens(InputBox.Text?.Trim() ?? "");
        if (expr.Length == 0)
        {
            ClearButton_Click(sender, e);
            return;
        }
        if (expr.StartsWith("-") && expr.Length > 1 && !char.IsDigit(expr[1]) && expr[1] != '.')
            expr = "0" + expr;   // calc.exe 命令行会把 "-(" 当选项, 前补 0 保持语义
        _ = RunEngineAsync(expr, null);
    }

    private string AutoCloseParens(string expr)
    {
        int open = 0;
        foreach (char c in expr)
        {
            if (c == '(') ++open;
            else if (c == ')' && open > 0) --open;
        }
        return open <= 0 ? expr : expr + new string(')', open);
    }

    // ======================= 引擎调用(后台线程 + 实时进度) =======================

    private string FindEngine()
    {
        string dir = AppContext.BaseDirectory;
        for (int i = 0; i < 6 && dir != null; ++i, dir = Path.GetDirectoryName(dir))
        {
            string p = Path.Combine(dir ?? "", "calc.exe");
            if (File.Exists(p)) return p;
        }
        string known = @"D:\计算器\计算器-科学版\calc.exe";
        if (File.Exists(known)) return known;
        return "calc.exe";
    }

    private async Task RunEngineAsync(string expr, Action<string> onDone)
    {
        int myGen = ++generation;
        computing = true;
        ShowProgress("计算中…", -1);

        string engine = FindEngine();
        string stdout = "";
        var errBuilder = new StringBuilder();
        int exitCode = -1;
        string startError = null;

        await Task.Run(() =>
        {
            try
            {
                var psi = new ProcessStartInfo(engine)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8,
                };
                psi.ArgumentList.Add("-p");
                psi.ArgumentList.Add(precision.ToString());
                if (maxDigits != 1000000)
                {
                    psi.ArgumentList.Add("-m");
                    psi.ArgumentList.Add(maxDigits.ToString());
                }
                if (force) psi.ArgumentList.Add("-f");
                psi.ArgumentList.Add(expr);

                using var p = new Process { StartInfo = psi };
                p.ErrorDataReceived += (s, args) =>
                {
                    if (args.Data == null) return;
                    lock (errBuilder) errBuilder.AppendLine(args.Data);
                    string line = args.Data;
                    DispatcherQueue.TryEnqueue(() =>
                    {
                        if (myGen != generation) return;
                        var m = PercentRegex.Match(line);
                        if (m.Success) ShowProgress(line, int.Parse(m.Groups[1].Value));
                        else ShowProgress(line, -2);   // -2 = 保持当前进度条状态
                    });
                };
                p.Start();
                p.BeginErrorReadLine();
                stdout = p.StandardOutput.ReadToEnd();
                p.WaitForExit();
                exitCode = p.ExitCode;
            }
            catch (Exception ex)
            {
                startError = ex.Message;
            }
        });

        if (myGen != generation) return;
        computing = false;
        HideProgress();

        if (startError != null)
        {
            NoteText.Text = "内部错误: " + startError + " (找不到 calc.exe?)";
            NoteText.Foreground = ErrorBrush;
            return;
        }
        string errText = errBuilder.ToString().Trim();
        if (exitCode != 0)
        {
            fullResult = "";
            lastValue = null;
            ResultText.Text = "";
            ViewAllButton.Visibility = Visibility.Collapsed;
            string hint = errText.Contains("超过安全上限")
                ? "\n(可在设置里勾选「解除位数上限」)" : "";
            NoteText.Text = errText.Length > 0 ? errText + hint : "计算失败";
            NoteText.Foreground = ErrorBrush;
            return;
        }

        string result = stdout.Trim();
        fullResult = result;
        lastValue = result;
        bool approx = errText.Contains("近似值");

        ResultText.Text = result;
        ResultText.IsTextSelectionEnabled = true;
        if (result.Length > ShowMaxChars)
        {
            ResultText.Text = result.Substring(0, 100)
                + $"\n… …(中间省略 {result.Length - 200} 个字符)… …\n… …"
                + result.Substring(result.Length - 100);
            ResultText.IsTextSelectionEnabled = false;
            ViewAllButton.Visibility = Visibility.Visible;
        }
        else
        {
            ViewAllButton.Visibility = Visibility.Collapsed;
        }
        NoteText.Text = $"共 {result.Length} 个字符" + (approx ? " · 近似值" : "")
            + (result.Length > ShowMaxChars ? " · 点「查看全部数字」浏览完整结果" : "");
        NoteText.Foreground = VariantBrush;
        if (onDone == null) AddHistory(expr, result);   // 记忆键等内部调用不记历史
        onDone?.Invoke(result);
    }

    private void ShowProgress(string msg, int percent)
    {
        ProgressPanel.Visibility = Visibility.Visible;
        ProgressText.Text = msg.Length > 90 ? msg.Substring(0, 90) + "…" : msg;
        if (percent >= 0)
        {
            CalcProgress.IsIndeterminate = false;
            CalcProgress.Value = percent;
        }
        else if (percent == -1)
        {
            CalcProgress.IsIndeterminate = true;
        }
        // percent == -2: 保持进度条当前状态, 只更新文字
    }

    private void HideProgress()
    {
        ProgressPanel.Visibility = Visibility.Collapsed;
        CalcProgress.IsIndeterminate = true;
        ProgressText.Text = "";
    }

    // ======================= 长结果查看 / 导出 =======================

    // ======================= 历史记录(最近 100 条, JSON 持久化) =======================

    private sealed class HistoryItem
    {
        public string Expr { get; set; }
        public string Result { get; set; }
    }

    private string HistoryPath
    {
        get { return Path.Combine(AppContext.BaseDirectory, "calcgui-history.json"); }
    }

    private List<HistoryItem> LoadHistory()
    {
        try
        {
            if (!File.Exists(HistoryPath)) return new List<HistoryItem>();
            return JsonSerializer.Deserialize<List<HistoryItem>>(File.ReadAllText(HistoryPath))
                   ?? new List<HistoryItem>();
        }
        catch
        {
            return new List<HistoryItem>();
        }
    }

    private void SaveHistory(List<HistoryItem> items)
    {
        try
        {
            File.WriteAllText(HistoryPath, JsonSerializer.Serialize(items));
        }
        catch { }
    }

    private void AddHistory(string expr, string result)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(expr) || string.IsNullOrEmpty(result)) return;
            if (result.Length > 5000)
                result = result.Substring(0, 5000) + $"…(共 {result.Length} 位)";
            var items = LoadHistory();
            if (items.Count > 0 && items[0].Expr == expr && items[0].Result == result) return;
            items.Insert(0, new HistoryItem { Expr = expr, Result = result });
            if (items.Count > 100) items.RemoveRange(100, items.Count - 100);
            SaveHistory(items);
        }
        catch { }
    }

    private Brush OnSurfaceBrush => RootGrid.ActualTheme == ElementTheme.Dark
        ? FromRgb(0xE6, 0xE1, 0xE5) : FromRgb(0x1C, 0x1B, 0x1F);

    private void HistoryButton_Click(object sender, RoutedEventArgs e)
    {
        RenderHistory();
        HistoryPanel.Visibility = Visibility.Visible;
    }

    private void HistoryClose_Click(object sender, RoutedEventArgs e)
        => HistoryPanel.Visibility = Visibility.Collapsed;

    private void ClearHistory_Click(object sender, RoutedEventArgs e)
    {
        SaveHistory(new List<HistoryItem>());
        RenderHistory();
        NoteText.Text = "历史记录已清空";
    }

    private void RenderHistory()
    {
        HistoryList.Children.Clear();
        var items = LoadHistory();
        if (items.Count == 0)
        {
            HistoryList.Children.Add(new TextBlock
            {
                Text = "暂无历史记录",
                FontSize = 13,
                Margin = new Thickness(4, 12, 4, 4),
                Foreground = VariantBrush,
            });
            return;
        }
        foreach (var it in items)
        {
            var exprT = new TextBlock
            {
                Text = it.Expr,
                FontSize = 12,
                FontFamily = new FontFamily("Consolas"),
                TextWrapping = TextWrapping.Wrap,
                Foreground = VariantBrush,
            };
            var resT = new TextBlock
            {
                Text = "= " + it.Result,
                FontSize = 14,
                FontFamily = new FontFamily("Consolas"),
                TextWrapping = TextWrapping.Wrap,
                MaxHeight = 60,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Foreground = OnSurfaceBrush,
            };
            var row = new StackPanel { Spacing = 2 };
            row.Children.Add(exprT);
            row.Children.Add(resT);
            var border = new Border
            {
                Child = row,
                Padding = new Thickness(8, 6, 8, 6),
                CornerRadius = new CornerRadius(8),
            };
            string exprCopy = it.Expr;
            border.Tapped += (s, args) =>
            {
                InputBox.Text = exprCopy;
                InputBox.SelectionStart = exprCopy.Length;
                HistoryPanel.Visibility = Visibility.Collapsed;
                InputBox.Focus(FocusState.Programmatic);
            };
            HistoryList.Children.Add(border);
            HistoryList.Children.Add(new Border
            {
                Height = 1,
                Background = VariantBrush,
                Opacity = 0.25,
                Margin = new Thickness(8, 0, 8, 0),
            });
        }
    }

    // ======================= 长结果分页查看器(窗口内覆盖层, 与安卓版一致) =======================
    // 不用 ContentDialog: 大文本下 ContentDialog 会触发 WinRT stowed exception(0xc000027b) 直接崩溃

    private const int PageChars = 50000;
    private int frPage;
    private int frPages;

    private void ViewAllButton_Click(object sender, RoutedEventArgs e)
    {
        OpenFullResultViewer();
    }

    private void OpenFullResultViewer()
    {
        if (string.IsNullOrEmpty(fullResult))
        {
            NoteText.Text = "还没有结果";
            return;
        }
        frPages = (fullResult.Length + PageChars - 1) / PageChars;
        frPage = 0;
        FullResultPanel.Visibility = Visibility.Visible;
        FrRender();
    }

    private void FrRender()
    {
        int from = frPage * PageChars;
        int to = Math.Min(fullResult.Length, from + PageChars);
        frText.Text = WrapDigits(fullResult.Substring(from, to - from));
        frInfo.Text = $"共 {fullResult.Length} 个字符 · 第 {frPage + 1} / {frPages} 页 (每页 {PageChars} 字符)";
        bool hasPrev = frPage > 0, hasNext = frPage < frPages - 1;
        frFirst.IsEnabled = frPrev.IsEnabled = hasPrev;
        frNext.IsEnabled = frLast.IsEnabled = hasNext;
        frFirst.Opacity = frPrev.Opacity = hasPrev ? 1.0 : 0.4;
        frNext.Opacity = frLast.Opacity = hasNext ? 1.0 : 0.4;
    }

    private void FrClose_Click(object sender, RoutedEventArgs e)
        => FullResultPanel.Visibility = Visibility.Collapsed;

    private void FrFirst_Click(object sender, RoutedEventArgs e)
    {
        frPage = 0;
        FrRender();
    }

    private void FrPrev_Click(object sender, RoutedEventArgs e)
    {
        if (frPage > 0) --frPage;
        FrRender();
    }

    private void FrNext_Click(object sender, RoutedEventArgs e)
    {
        if (frPage < frPages - 1) ++frPage;
        FrRender();
    }

    private void FrLast_Click(object sender, RoutedEventArgs e)
    {
        frPage = frPages - 1;
        FrRender();
    }

    private static void CopyToClipboard(string s)
    {
        var pkg = new DataPackage();
        pkg.SetText(s);
        Clipboard.SetContent(pkg);
    }

    private void FrCopyPage_Click(object sender, RoutedEventArgs e)
    {
        int from = frPage * PageChars;
        int to = Math.Min(fullResult.Length, from + PageChars);
        CopyToClipboard(fullResult.Substring(from, to - from));   // 复制连续数字, 不带换行
        NoteText.Text = $"已复制本页 {(to - from)} 个字符";
    }

    private void FrCopyAll_Click(object sender, RoutedEventArgs e)
    {
        CopyToClipboard(fullResult);
        NoteText.Text = $"已复制全部 {fullResult.Length} 个字符";
    }

    private void FrExport_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            string path = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.Desktop),
                $"计算结果-{fullResult.Length}位.txt");
            File.WriteAllText(path, fullResult, Encoding.UTF8);
            NoteText.Text = "已导出到 " + path;
        }
        catch (Exception ex)
        {
            NoteText.Text = "导出失败: " + ex.Message;
        }
    }

    private void ResultText_Tapped(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e)
    {
        if (!ResultText.IsTextSelectionEnabled && fullResult.Length > ShowMaxChars)
            ViewAllButton_Click(sender, null);
    }

    /** 显示用: 每行 100 位强制换行(纯数字串没有断行点, 不换行会变成一条横向长线) */
    private static string WrapDigits(string s)
    {
        var sb = new StringBuilder(s.Length + s.Length / 100 + 2);
        for (int i = 0; i < s.Length; i += 100)
        {
            if (i > 0) sb.Append('\n');
            int end = Math.Min(s.Length, i + 100);
            sb.Append(s, i, end - i);   // C# Append(string, start, count): 第三参是长度不是下标
        }
        return sb.ToString();
    }

    // ======================= 设置 =======================

    private async void SettingsButton_Click(object sender, RoutedEventArgs e)
    {
        var pPrec = new TextBox { Text = precision.ToString(), Header = "近似结果有效数字位数 (1~20000, 默认 50)" };
        var pMax = new TextBox { Text = maxDigits.ToString(), Header = "结果位数安全上限 (默认 1000000)", Margin = new Thickness(0, 8, 0, 0) };
        var pForce = new CheckBox { Content = "解除位数上限(可能很慢/很占内存)", IsChecked = force, Margin = new Thickness(0, 8, 0, 0) };
        var themeSel = new RadioButtons
        {
            Header = "背景颜色",
            Margin = new Thickness(0, 8, 0, 0),
            Items = { "深色", "浅色", "跟随系统" },
            SelectedIndex = themeMode == "dark" ? 0 : themeMode == "light" ? 1 : 2,
        };
        var panel = new StackPanel { Spacing = 4, MinWidth = 380 };
        panel.Children.Add(pPrec);
        panel.Children.Add(pMax);
        panel.Children.Add(pForce);
        panel.Children.Add(themeSel);

        var dialog = new ContentDialog
        {
            Title = "计算设置",
            Content = panel,
            PrimaryButtonText = "确定",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = Content.XamlRoot,
        };
        var r = await dialog.ShowAsync();
        if (r != ContentDialogResult.Primary) return;

        try { int p = int.Parse(pPrec.Text.Trim()); if (p >= 1 && p <= 20000) precision = p; } catch { }
        try { int m = int.Parse(pMax.Text.Trim()); if (m >= 100 && m <= 100000000) maxDigits = m; } catch { }
        force = pForce.IsChecked == true;
        themeMode = themeSel.SelectedIndex switch { 0 => "dark", 1 => "light", _ => "system" };
        SaveSettings();
        ApplyTheme();
        NoteText.Text = $"设置已保存: 精度 {precision} · 上限 {maxDigits}" + (force ? " · 已解除上限" : "");
    }

    // ======================= 设置持久化 =======================

    private string SettingsPath
    {
        get
        {
            string dir = AppContext.BaseDirectory;
            return Path.Combine(dir, "calcgui-settings.txt");
        }
    }

    private void LoadSettings()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return;
            foreach (string lineRaw in File.ReadAllLines(SettingsPath))
            {
                string line = lineRaw.Trim();
                int eq = line.IndexOf('=');
                if (eq <= 0) continue;
                string k = line.Substring(0, eq).Trim();
                string v = line.Substring(eq + 1).Trim();
                switch (k)
                {
                    case "precision": int.TryParse(v, out precision); break;
                    case "maxDigits": int.TryParse(v, out maxDigits); break;
                    case "force": force = v == "1"; break;
                    case "theme": themeMode = v; break;
                }
            }
            if (precision < 1 || precision > 20000) precision = 50;
            if (maxDigits < 100 || maxDigits > 100000000) maxDigits = 1000000;
        }
        catch { }
    }

    private void SaveSettings()
    {
        try
        {
            File.WriteAllLines(SettingsPath, new[]
            {
                $"precision={precision}",
                $"maxDigits={maxDigits}",
                $"force={(force ? 1 : 0)}",
                $"theme={themeMode}",
            });
        }
        catch { }
    }

    private void ApplyTheme()
    {
        RootGrid.RequestedTheme = themeMode switch
        {
            "dark" => ElementTheme.Dark,
            "light" => ElementTheme.Light,
            _ => ElementTheme.Default,
        };
    }
}
