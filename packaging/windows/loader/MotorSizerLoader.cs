using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace MotorSizer
{
    internal static class Program
    {
        private const string MutexName = "Global\\MotorSizer-SingleInstance-v1";
        private static string _logPath;

        // 简单日志：写到 APPDATA/MotorSizer/logs/loader.log
        internal static void Log(string msg)
        {
            try
            {
                if (_logPath == null)
                {
                    string logDir = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                        "MotorSizer", "logs");
                    Directory.CreateDirectory(logDir);
                    _logPath = Path.Combine(logDir, "loader.log");
                }
                string line = DateTime.Now.ToString("HH:mm:ss.fff") + "  " + msg;
                File.AppendAllText(_logPath, line + Environment.NewLine);
            }
            catch { }
        }

        [STAThread]
        private static int Main(string[] args)
        {
            Log("==== MotorSizerLoader 启动 ====");
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            // 捕获 UI 线程未处理异常
            Application.ThreadException += (s, e) =>
            {
                Log("ThreadException: " + e.Exception);
                MessageBox.Show("发生未处理异常：\n" + e.Exception, "MotorSizer",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            };
            // 捕获非 UI 线程未处理异常
            AppDomain.CurrentDomain.UnhandledException += (s, e) =>
            {
                Log("AppDomain.UnhandledException: " + (e.ExceptionObject ?? "(null)"));
            };

            bool createdNew;
            using (Mutex mutex = new Mutex(true, MutexName, out createdNew))
            {
                Log("Mutex createdNew=" + createdNew);
                if (!createdNew)
                {
                    Log("已有实例，退出");
                    return 0;
                }
                return Run();
            }
        }

        private static int Run()
        {
            string exeDir = Path.GetDirectoryName(Application.ExecutablePath);
            if (string.IsNullOrEmpty(exeDir))
                exeDir = Directory.GetCurrentDirectory();
            Log("exeDir=" + exeDir);

            string appDir = Path.Combine(exeDir, "app");
            string jarPath = Path.Combine(appDir, "motorsizer-app-1.0.0-SNAPSHOT.jar");
            string runtimeJava = Path.Combine(exeDir, "runtime", "bin", "java.exe");
            Log("jarPath=" + jarPath + " exists=" + File.Exists(jarPath));
            Log("runtimeJava=" + runtimeJava + " exists=" + File.Exists(runtimeJava));

            if (!File.Exists(jarPath))
            {
                MessageBox.Show("找不到后端程序包：\n" + jarPath, "MotorSizer 启动错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }
            if (!File.Exists(runtimeJava))
            {
                MessageBox.Show("找不到运行时：\n" + runtimeJava, "MotorSizer 启动错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }

            // 启动 Java 后端子进程
            Process backend = new Process();
            backend.StartInfo.FileName = runtimeJava;
            backend.StartInfo.Arguments =
                "-Dmotorsizer.openBrowser=false -jar \"" + jarPath + "\"";
            backend.StartInfo.UseShellExecute = false;
            backend.StartInfo.CreateNoWindow = true;
            backend.EnableRaisingEvents = true;
            try
            {
                Log("启动后端: " + backend.StartInfo.FileName + " " + backend.StartInfo.Arguments);
                backend.Start();
                Log("后端 pid=" + backend.Id);
            }
            catch (Exception ex)
            {
                Log("后端启动失败: " + ex);
                MessageBox.Show("后端启动失败：\n" + ex.Message, "MotorSizer 启动错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 3;
            }

            // 轮询端口文件 → 拿到端口后 HTTP 探活（直到后端真正可连接），避免首次打开 ERR_CONNECTION_REFUSED。
            // 注意：app.port 在 WebServerInitializedEvent 就写入，但 Tomcat 端口此刻未必能立刻接受连接
            // （且该文件可能是上一实例残留），因此必须对 /api/health 探活成功才算就绪。
            string appData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MotorSizer");
            string portFile = Path.Combine(appData, "app.port");
            int port = 0;
            Log("开始轮询端口文件: " + portFile);
            for (int i = 0; i < 120; i++)
            {
                if (backend.HasExited)
                {
                    Log("后端提前退出, exitCode=" + backend.ExitCode);
                    int existingPort = ReadPort(portFile);
                    if (existingPort > 0)
                    {
                        port = existingPort;
                        Log("检测到已有实例端口=" + port + "，弹窗口指向它");
                        break;
                    }
                    MessageBox.Show("后端进程启动后立即退出。\n日志：" + Path.Combine(appData, "logs", "motorsizer.log"),
                        "MotorSizer 启动错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 4;
                }
                int p = ReadPort(portFile);
                if (p > 0)
                {
                    port = p;
                    Log("端口文件就绪=" + port + " (等待 " + (i + 1) + " 次)，开始 HTTP 探活");
                    // 端口文件出现不代表可连接，继续对 /api/health 探活直到成功
                    if (WaitUntilHealthy(port, 120, backend))
                    {
                        Log("HTTP 探活成功，后端已就绪");
                        break;
                    }
                    // 探活超时但后端未退出：可能是端口文件残留，回到外层继续轮询
                    if (backend.HasExited)
                    {
                        Log("探活期间后端退出，继续外层轮询");
                        continue;
                    }
                    MessageBox.Show("后端已启动但长时间无响应。\n日志：" + Path.Combine(appData, "logs", "motorsizer.log"),
                        "MotorSizer 启动错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    try { if (!backend.HasExited) backend.Kill(); } catch { }
                    return 5;
                }
                Thread.Sleep(500);
            }

            if (port <= 0)
            {
                Log("超时未拿到端口");
                MessageBox.Show("后端启动超时。", "MotorSizer 启动错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                try { if (!backend.HasExited) backend.Kill(); } catch { }
                return 5;
            }

            // 创建 WebView2 窗口
            Log("创建 MainForm");
            try
            {
                using (var form = new MainForm(port, exeDir))
                {
                    Application.Run(form);
                }
                Log("Application.Run 返回（窗口已关闭）");
            }
            catch (Exception ex)
            {
                Log("Application.Run 异常: " + ex);
                throw;
            }
            finally
            {
                Log("finally: 结束后端");
                try { if (!backend.HasExited) { backend.Kill(); backend.WaitForExit(3000); Log("后端已 kill"); } else Log("后端已自行退出"); } catch (Exception ex) { Log("kill 后端异常: " + ex); }
            }
            return 0;
        }

        private static int ReadPort(string portFile)
        {
            try
            {
                if (!File.Exists(portFile)) return 0;
                string s = File.ReadAllText(portFile).Trim();
                int p;
                return int.TryParse(s, out p) ? p : 0;
            }
            catch { return 0; }
        }

        // 对后端 /api/health 做 HTTP 探活，直到返回 200 或超时/后端退出。
        private static bool WaitUntilHealthy(int port, int maxTries, Process backend)
        {
            string url = "http://127.0.0.1:" + port + "/api/health";
            for (int i = 0; i < maxTries; i++)
            {
                if (backend.HasExited)
                {
                    Log("探活时后端已退出, exitCode=" + backend.ExitCode);
                    return false;
                }
                try
                {
                    HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
                    req.Timeout = 1000;
                    req.Method = "GET";
                    using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                    {
                        if ((int)resp.StatusCode == 200)
                        {
                            return true;
                        }
                    }
                }
                catch (Exception ex)
                {
                    // 连接被拒/超时等，继续等待
                    if (i == 0 || i % 10 == 9)
                        Log("探活失败(" + (i + 1) + "): " + ex.GetType().Name);
                }
                Thread.Sleep(500);
            }
            return false;
        }
    }

    internal sealed class MainForm : Form
    {
        private readonly int _port;
        private readonly string _exeDir;
        private WebView2 _webView;

        public MainForm(int port, string exeDir)
        {
            _port = port;
            _exeDir = exeDir;
            Text = "MotorSizer  电机选型工具";

            // 窗口初始尺寸基于屏幕工作区动态计算，避免在较矮的屏幕上被系统钳制
            // 导致内容（如左列「用户输入」底部脚注）被裁切。
            // 目标尺寸 1320×900，但不超过工作区（留少量边距），保证一屏尽量完整。
            var wa = Screen.PrimaryScreen.WorkingArea;
            Width = Math.Max(960, Math.Min(1320, wa.Width - 24));
            Height = Math.Max(640, Math.Min(900, wa.Height - 24));
            MinimumSize = new Size(960, 640);
            StartPosition = FormStartPosition.CenterScreen;

            // 从 exe 自身提取嵌入图标（/win32icon 编译时嵌入），无需外部 .ico 文件
            try
            {
                Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);
                Program.Log("窗口图标已从 exe 提取");
            }
            catch (Exception ex) { Program.Log("图标加载失败: " + ex); }

            _webView = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(_webView);

            Load += MainForm_Load;
            FormClosed += MainForm_FormClosed;
            Program.Log("MainForm 构造完成");
        }

        private async void MainForm_Load(object sender, EventArgs e)
        {
            Program.Log("MainForm_Load 开始");
            try
            {
                string userDataDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "MotorSizer", "webview-data");
                Directory.CreateDirectory(userDataDir);
                Program.Log("userDataDir=" + userDataDir);

                Program.Log("调用 CoreWebView2Environment.CreateAsync");
                var env = await CoreWebView2Environment.CreateAsync(null, userDataDir);
                Program.Log("CreateAsync 完成");

                Program.Log("调用 EnsureCoreWebView2Async");
                await _webView.EnsureCoreWebView2Async(env);
                Program.Log("EnsureCoreWebView2Async 完成, CoreWebView2=" + (_webView.CoreWebView2 != null));

                _webView.CoreWebView2.NavigationStarting += (s, args) =>
                {
                    Program.Log("NavigationStarting: " + args.Uri);
                    if (!args.Uri.StartsWith("http://127.0.0.1:" + _port) &&
                        !args.Uri.StartsWith("http://localhost:" + _port))
                    {
                        args.Cancel = true;
                        try { Process.Start(new ProcessStartInfo(args.Uri) { UseShellExecute = true }); } catch { }
                    }
                };
                _webView.CoreWebView2.NavigationCompleted += (s, args) =>
                {
                    Program.Log("NavigationCompleted: success=" + args.IsSuccess);
                };

                string url = "http://127.0.0.1:" + _port;
                Program.Log("Navigate: " + url);
                _webView.CoreWebView2.Navigate(url);
                Program.Log("Navigate 已调用");
            }
            catch (Exception ex)
            {
                Program.Log("MainForm_Load 异常: " + ex);
                MessageBox.Show("WebView2 初始化失败：\n" + ex.Message + "\n\n日志：APPDATA\\MotorSizer\\logs\\loader.log",
                    "MotorSizer", MessageBoxButtons.OK, MessageBoxIcon.Error);
                Close();
            }
        }

        private void MainForm_FormClosed(object sender, FormClosedEventArgs e)
        {
            Program.Log("MainForm_FormClosed");
        }
    }
}
