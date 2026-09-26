using System;
using System.IO;
using Microsoft.UI.Xaml;

namespace CalcGui;

public partial class App : Application
{
    private Window _window;

    public App()
    {
        InitializeComponent();
        // 全局异常日志: 未处理异常写入 error.log, 避免无声崩溃且便于排查
        this.UnhandledException += (s, e) =>
        {
            try
            {
                File.AppendAllText(
                    Path.Combine(AppContext.BaseDirectory, "error.log"),
                    DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " | " + e.Message + "\n" +
                    e.Exception + "\n\n");
            }
            catch { }
            e.Handled = true;
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        _window.Activate();
    }
}
