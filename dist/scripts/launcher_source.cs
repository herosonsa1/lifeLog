using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace LifeLogDistLauncher
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [STAThread]
        private static void Main()
        {
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new LauncherForm());
        }
    }

    public sealed class LauncherForm : Form
    {
        private readonly string _distDir;
        private readonly string _scriptPath;
        private readonly Label _apkValueLabel;
        private readonly Label _aabValueLabel;
        private readonly Label _deviceValueLabel;
        private readonly TextBox _logBox;
        private Button[] _actionButtons;

        public LauncherForm()
        {
            _distDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            _scriptPath = Path.Combine(_distDir, "scripts", "build_and_deploy.ps1");

            Text = "나의일상다이어리 통합 배포 및 설치 런처";
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(760, 620);
            MinimumSize = new Size(720, 580);
            BackColor = Color.FromArgb(248, 250, 252);
            Font = new Font("맑은 고딕", 9.5f, FontStyle.Regular);

            string iconPath = Path.Combine(_distDir, "에셋등록관련자료", "favicon.ico");
            if (File.Exists(iconPath))
            {
                try { Icon = new Icon(iconPath); } catch { }
            }

            Panel root = new Panel
            {
                Dock = DockStyle.Fill,
                Padding = new Padding(24, 20, 24, 20),
                BackColor = Color.FromArgb(248, 250, 252)
            };
            Controls.Add(root);

            // 상단 타이틀 영역
            Label titleLabel = new Label
            {
                Text = "나의일상다이어리 통합 배포 런처",
                Font = new Font("맑은 고딕", 16f, FontStyle.Bold),
                ForeColor = Color.FromArgb(17, 24, 39),
                AutoSize = true,
                Location = new Point(24, 18)
            };
            root.Controls.Add(titleLabel);

            Label subTitleLabel = new Label
            {
                Text = "산출물(APK · AAB · 사용방법 · 에셋등록자료) 통합 관리 및 스마트폰 원클릭 배포 도구",
                Font = new Font("맑은 고딕", 9.5f, FontStyle.Regular),
                ForeColor = Color.FromArgb(100, 116, 139),
                AutoSize = true,
                Location = new Point(26, 52)
            };
            root.Controls.Add(subTitleLabel);

            // 상태 요약 카드
            Panel summaryCard = new Panel
            {
                Location = new Point(24, 86),
                Size = new Size(712, 112),
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right,
                BackColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle
            };
            root.Controls.Add(summaryCard);

            _apkValueLabel = AddMetricRow(summaryCard, "최신 설치 APK", 14);
            _aabValueLabel = AddMetricRow(summaryCard, "최신 스토어 AAB", 44);
            _deviceValueLabel = AddMetricRow(summaryCard, "연결된 스마트폰", 74);

            Button refreshBtn = CreateCleanButton("상태 새로고침", false);
            refreshBtn.Size = new Size(116, 32);
            refreshBtn.Location = new Point(580, 14);
            refreshBtn.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            refreshBtn.Click += (s, e) => RefreshArtifactStatus();
            summaryCard.Controls.Add(refreshBtn);

            // 버튼 그리드 영역
            TableLayoutPanel grid = new TableLayoutPanel
            {
                Location = new Point(24, 214),
                Size = new Size(712, 136),
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right,
                ColumnCount = 2,
                RowCount = 3,
                BackColor = Color.Transparent
            };
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50f));
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 33.33f));
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 33.33f));
            grid.RowStyles.Add(new RowStyle(SizeType.Percent, 33.34f));
            root.Controls.Add(grid);

            Button btnInstall = CreateCleanButton("스마트폰에 최신 APK 설치 및 실행", true);
            btnInstall.Click += async (s, e) => await RunScriptActionAsync("InstallLatestApk", "최신 APK 설치 및 실행");

            Button btnPerm = CreateCleanButton("스마트폰 필수 권한 일괄 허용 (ADB)", false);
            btnPerm.Click += async (s, e) => await RunScriptActionAsync("GrantPermissions", "필수 권한 일괄 허용");

            Button btnBuild = CreateCleanButton("신규 APK 및 AAB 통합 빌드 실행", false);
            btnBuild.Click += async (s, e) => await RunScriptActionAsync("BuildApkAndAab", "신규 APK/AAB 통합 빌드");

            Button btnManual = CreateCleanButton("사용방법 매뉴얼 열기 (HTML)", false);
            btnManual.Click += (s, e) => OpenTarget(Path.Combine(_distDir, "사용방법_매뉴얼.html"));

            Button btnAssets = CreateCleanButton("에셋등록관련자료 폴더 열기", false);
            btnAssets.Click += (s, e) => OpenTarget(Path.Combine(_distDir, "에셋등록관련자료"));

            Button btnDist = CreateCleanButton("dist 산출물 폴더 열기", false);
            btnDist.Click += (s, e) => OpenTarget(_distDir);

            _actionButtons = new[] { btnInstall, btnPerm, btnBuild, btnManual, btnAssets, btnDist };

            grid.Controls.Add(btnInstall, 0, 0);
            grid.Controls.Add(btnPerm, 1, 0);
            grid.Controls.Add(btnBuild, 0, 1);
            grid.Controls.Add(btnManual, 1, 1);
            grid.Controls.Add(btnAssets, 0, 2);
            grid.Controls.Add(btnDist, 1, 2);

            foreach (Control ctrl in grid.Controls)
            {
                ctrl.Dock = DockStyle.Fill;
                ctrl.Margin = new Padding(4);
            }

            // 실행 로그 섹션
            Label logHeader = new Label
            {
                Text = "작업 진행 로그",
                Font = new Font("맑은 고딕", 9.5f, FontStyle.Bold),
                ForeColor = Color.FromArgb(51, 65, 85),
                AutoSize = true,
                Location = new Point(24, 364)
            };
            root.Controls.Add(logHeader);

            _logBox = new TextBox
            {
                Location = new Point(24, 390),
                Size = new Size(712, 206),
                Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right,
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = Color.White,
                ForeColor = Color.FromArgb(30, 41, 59),
                BorderStyle = BorderStyle.FixedSingle,
                Font = new Font("Consolas", 9.2f, FontStyle.Regular)
            };
            root.Controls.Add(_logBox);

            Shown += (s, e) =>
            {
                AppendLog("나의일상다이어리 통합 배포 런처가 준비되었습니다.");
                AppendLog("산출물 기준 폴더: " + _distDir);
                RefreshArtifactStatus();
            };
        }

        private static Label AddMetricRow(Panel parent, string labelText, int top)
        {
            Label keyLabel = new Label
            {
                Text = labelText,
                Font = new Font("맑은 고딕", 9.2f, FontStyle.Bold),
                ForeColor = Color.FromArgb(71, 85, 105),
                Location = new Point(16, top),
                Size = new Size(125, 22)
            };
            parent.Controls.Add(keyLabel);

            Label valLabel = new Label
            {
                Text = "확인 중...",
                Font = new Font("맑은 고딕", 9.2f, FontStyle.Regular),
                ForeColor = Color.FromArgb(15, 23, 42),
                Location = new Point(146, top),
                Size = new Size(425, 22),
                AutoEllipsis = true
            };
            parent.Controls.Add(valLabel);
            return valLabel;
        }

        private static Button CreateCleanButton(string text, bool isPrimary)
        {
            Button btn = new Button
            {
                Text = text,
                FlatStyle = FlatStyle.Flat,
                Cursor = Cursors.Hand,
                Font = new Font("맑은 고딕", 9.5f, isPrimary ? FontStyle.Bold : FontStyle.Regular),
                BackColor = isPrimary ? Color.FromArgb(37, 99, 235) : Color.White,
                ForeColor = isPrimary ? Color.White : Color.FromArgb(30, 41, 59)
            };
            btn.FlatAppearance.BorderColor = isPrimary
                ? Color.FromArgb(29, 78, 216)
                : Color.FromArgb(203, 213, 225);
            btn.FlatAppearance.BorderSize = 1;
            return btn;
        }

        private void RefreshArtifactStatus()
        {
            try
            {
                FileInfo latestApk = new DirectoryInfo(_distDir)
                    .GetFiles("나의일상다이어리_v*.apk")
                    .Where(f => !f.Name.EndsWith("_release.apk", StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(f => ParseVersionKey(f.Name))
                    .FirstOrDefault();

                FileInfo latestAab = new DirectoryInfo(_distDir)
                    .GetFiles("나의일상다이어리_v*.aab")
                    .OrderByDescending(f => ParseVersionKey(f.Name))
                    .FirstOrDefault();

                _apkValueLabel.Text = latestApk != null
                    ? string.Format("{0} ({1:F2} MB)", latestApk.Name, latestApk.Length / 1048576.0)
                    : "아직 생성된 APK 없음";

                _aabValueLabel.Text = latestAab != null
                    ? string.Format("{0} ({1:F2} MB)", latestAab.Name, latestAab.Length / 1048576.0)
                    : "빌드 대기 중 (빌드 완료 시 자동 표시)";

                Task.Run(() =>
                {
                    string adbStatus = QueryConnectedDeviceSummary();
                    BeginInvoke((Action)(() => _deviceValueLabel.Text = adbStatus));
                });
            }
            catch (Exception ex)
            {
                AppendLog("[상태 조회 오류] " + ex.Message);
            }
        }

        private static Version ParseVersionKey(string fileName)
        {
            Match m = Regex.Match(fileName, @"v(\d+)\.(\d+)\.(\d+)");
            if (m.Success)
            {
                Version v;
                if (Version.TryParse(m.Groups[1].Value + "." + m.Groups[2].Value + "." + m.Groups[3].Value, out v))
                {
                    return v;
                }
            }
            return new Version(0, 0, 0);
        }

        private static string QueryConnectedDeviceSummary()
        {
            string localSdkAdb = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Android", "Sdk", "platform-tools", "adb.exe");
            string adbExe = File.Exists(localSdkAdb) ? localSdkAdb : "adb.exe";

            try
            {
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = adbExe,
                    Arguments = "devices",
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    StandardOutputEncoding = Encoding.UTF8
                };
                using (Process proc = Process.Start(psi))
                {
                    string output = proc.StandardOutput.ReadToEnd();
                    proc.WaitForExit(3000);
                    var lines = output
                        .Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)
                        .Skip(1)
                        .Where(l => l.Contains("\tdevice"))
                        .Select(l => l.Split('\t')[0].Trim())
                        .ToArray();
                    return lines.Length > 0
                        ? string.Join(", ", lines) + " (연결됨)"
                        : "연결된 기기 없음 (USB 디버깅 연결 확인)";
                }
            }
            catch
            {
                return "ADB 미감지 (Android SDK 확인 필요)";
            }
        }

        private async Task RunScriptActionAsync(string actionName, string friendlyTitle)
        {
            if (!File.Exists(_scriptPath))
            {
                AppendLog("[오류] 실행 스크립트를 찾을 수 없습니다: " + _scriptPath);
                return;
            }

            SetButtonsEnabled(false);
            AppendLog("------------------------------------------------------------");
            AppendLog("[시작] " + friendlyTitle + " 작업을 실행합니다...");

            await Task.Run(() =>
            {
                ProcessStartInfo psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = string.Format("-NoProfile -ExecutionPolicy Bypass -File \"{0}\" -Action {1}", _scriptPath, actionName),
                    WorkingDirectory = _distDir,
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8
                };

                using (Process proc = new Process { StartInfo = psi })
                {
                    proc.OutputDataReceived += (s, e) =>
                    {
                        if (!string.IsNullOrEmpty(e.Data))
                            BeginInvoke((Action)(() => AppendLog(e.Data)));
                    };
                    proc.ErrorDataReceived += (s, e) =>
                    {
                        if (!string.IsNullOrEmpty(e.Data))
                            BeginInvoke((Action)(() => AppendLog("[알림] " + e.Data)));
                    };
                    proc.Start();
                    proc.BeginOutputReadLine();
                    proc.BeginErrorReadLine();
                    proc.WaitForExit();
                }
            });

            AppendLog("[종료] " + friendlyTitle + " 작업이 끝났습니다.");
            SetButtonsEnabled(true);
            RefreshArtifactStatus();
        }

        private void OpenTarget(string path)
        {
            if (!File.Exists(path) && !Directory.Exists(path))
            {
                AppendLog("[안내] 대상 경로가 아직 존재하지 않습니다: " + path);
                return;
            }
            Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
        }

        private void SetButtonsEnabled(bool enabled)
        {
            foreach (Button b in _actionButtons) b.Enabled = enabled;
        }

        private void AppendLog(string message)
        {
            string line = string.Format("[{0:HH:mm:ss}] {1}{2}", DateTime.Now, message, Environment.NewLine);
            _logBox.AppendText(line);
        }
    }
}
