// 衣启动器 · 原生启动壳（C# / .NET Framework，编译成单文件 exe）
// 职责：1) 把内置的 app.jar + JavaFX/JNA 运行库解压到用户目录 2) 找本机 Java 21+
//      3) 找不到就提示并打开 Java 下载页 4) 找到就用 javaw 拉起 JavaFX 主程序
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class Program
{
    /// <summary>JavaFX 主类。</summary>
    private const string AppMainClass = "com.yilauncher.yi_launcher.LauncherApp";
    /// <summary>本启动器要求的最低 Java 大版本（jar 用 21 编译）。</summary>
    private const int MinJavaVersion = 21;
    /// <summary>内置的运行库压缩包（构建时以 /resource 嵌入）。</summary>
    private const string PayloadResource = "payload.zip";
    private const string AdoptiumUrl = "https://adoptium.net/temurin/releases/?version=21";
    private const string OracleUrl = "https://www.oracle.com/java/technologies/downloads/#java21";

    private static string[] cliArgs = new string[0];

    [STAThread]
    private static int Main(string[] args)
    {
        cliArgs = args ?? new string[0];
        bool check = HasFlag("--check");
        try
        {
            string baseDir = EnsurePayload();
            string javaExe;
            string versionText;
            int major;
            FindBestJava(out javaExe, out versionText, out major);

            if (check)
            {
                WriteCheckReport(baseDir, javaExe, versionText, major);
                return 0;
            }
            if (javaExe == null || major < MinJavaVersion)
            {
                ShowJavaMissing(versionText, major);
                return 1;
            }
            Launch(javaExe, baseDir);
            return 0;
        }
        catch (Exception error)
        {
            if (check)
            {
                try { File.WriteAllText(CheckPath(), "error: " + error, Encoding.UTF8); } catch { }
                return 2;
            }
            MessageBox.Show("衣启动器启动失败：\n\n" + error.Message, "衣启动器",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }
    }

    /* ============================ 1. 解压内置运行库 ============================ */

    /// <summary>把内置 zip 解到 %LOCALAPPDATA%\YI_Launcher\runtime\&lt;内容指纹&gt;，已存在就直接复用。</summary>
    private static string EnsurePayload()
    {
        Assembly asm = Assembly.GetExecutingAssembly();
        using (Stream probe = asm.GetManifestResourceStream(PayloadResource))
        {
            if (probe == null)
            {
                throw new InvalidOperationException("安装包损坏：找不到内置运行库。");
            }
            string stamp = probe.Length.ToString("x");
            string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "YI_Launcher", "runtime");
            string dir = Path.Combine(root, stamp);
            string marker = Path.Combine(dir, "app.jar");
            if (File.Exists(marker))
            {
                CleanOldRuntimes(root, dir);
                return dir;
            }

            Directory.CreateDirectory(root);
            string temp = Path.Combine(root, stamp + ".tmp");
            if (Directory.Exists(temp))
            {
                Directory.Delete(temp, true);
            }
            Directory.CreateDirectory(temp);

            string zipPath = Path.Combine(Path.GetTempPath(), "yi_launcher_" + Guid.NewGuid().ToString("N") + ".zip");
            using (FileStream file = File.Create(zipPath))
            {
                probe.Position = 0;
                probe.CopyTo(file);
            }
            try
            {
                ZipFile.ExtractToDirectory(zipPath, temp);
            }
            finally
            {
                try { File.Delete(zipPath); } catch { }
            }
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, true);
            }
            Directory.Move(temp, dir);
            CleanOldRuntimes(root, dir);
            return dir;
        }
    }

    /// <summary>清掉旧版本解压出来的运行库，别让它们一直堆在硬盘上。</summary>
    private static void CleanOldRuntimes(string root, string keep)
    {
        try
        {
            foreach (string dir in Directory.GetDirectories(root))
            {
                if (string.Equals(dir, keep, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                try { Directory.Delete(dir, true); } catch { }
            }
        }
        catch { }
    }

    /* ============================ 2. 找 Java ============================ */

    /// <summary>在所有可能的位置里挑版本最高的 Java；版本低于要求也返回（用来提示用户当前装的是几）。</summary>
    private static void FindBestJava(out string javaExe, out string versionText, out int major)
    {
        javaExe = null;
        versionText = null;
        major = 0;

        if (Environment.GetEnvironmentVariable("YI_LAUNCHER_NO_JAVA") == "1")
        {
            return;
        }

        Dictionary<string, string> seen = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        int probed = 0;
        foreach (string exe in Candidates())
        {
            string full;
            try { full = Path.GetFullPath(exe); } catch { continue; }
            if (!File.Exists(full) || seen.ContainsKey(full))
            {
                continue;
            }
            seen[full] = full;
            if (probed++ >= 14)
            {
                break;
            }
            int version;
            string text = ReadJavaVersion(full, out version);
            if (text == null)
            {
                continue;
            }
            if (javaExe == null || version > major)
            {
                javaExe = PreferWindowless(full);
                versionText = text;
                major = version;
            }
            if (major >= MinJavaVersion && probed >= 6)
            {
                break;
            }
        }
    }

    /// <summary>同目录有 javaw.exe 就用它，避免弹黑框。</summary>
    private static string PreferWindowless(string javaExe)
    {
        try
        {
            string javaw = Path.Combine(Path.GetDirectoryName(javaExe), "javaw.exe");
            if (File.Exists(javaw))
            {
                return javaw;
            }
        }
        catch { }
        return javaExe;
    }

    /// <summary>跑一次 java -version，解析大版本号（8 / 17 / 21 …）。</summary>
    private static string ReadJavaVersion(string javaExe, out int major)
    {
        major = 0;
        try
        {
            ProcessStartInfo info = new ProcessStartInfo(javaExe)
            {
                Arguments = "-version",
                UseShellExecute = false,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                CreateNoWindow = true
            };
            using (Process process = Process.Start(info))
            {
                string output = process.StandardError.ReadToEnd();
                if (!process.WaitForExit(5000))
                {
                    try { process.Kill(); } catch { }
                    return null;
                }
                output += process.StandardOutput.ReadToEnd();
                Match match = Regex.Match(output, "version \"([^\"]+)\"");
                if (!match.Success)
                {
                    return null;
                }
                string text = match.Groups[1].Value;
                string[] parts = text.Split('.', '_', '-');
                int first = int.Parse(parts[0]);
                if (first == 1 && parts.Length > 1)
                {
                    major = int.Parse(parts[1]);
                }
                else
                {
                    major = first;
                }
                return text;
            }
        }
        catch
        {
            return null;
        }
    }

    /// <summary>候选 java.exe：JAVA_HOME、注册表、PATH、常见安装目录、Minecraft 自带运行时。</summary>
    private static IEnumerable<string> Candidates()
    {
        List<string> list = new List<string>();

        string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome))
        {
            list.Add(Path.Combine(javaHome, "bin", "java.exe"));
        }

        foreach (string value in RegistryHomes())
        {
            if (!string.IsNullOrEmpty(value))
            {
                list.Add(Path.Combine(value, "bin", "java.exe"));
            }
        }

        string path = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrEmpty(path))
        {
            foreach (string part in path.Split(';'))
            {
                if (part.Trim().Length > 0)
                {
                    list.Add(Path.Combine(part.Trim().Trim('"'), "java.exe"));
                }
            }
        }

        List<string> roots = new List<string>();
        AddRoot(roots, Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles));
        AddRoot(roots, Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86));
        AddRoot(roots, Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) + "\\Programs");
        AddRoot(roots, Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + "\\.jdks");
        foreach (string root in roots)
        {
            list.AddRange(ScanForJava(root, 2));
        }

        // Minecraft 自带运行时里常常正好有 Java 21
        foreach (string mc in MinecraftRuntimeRoots())
        {
            list.AddRange(ScanForJava(mc, 5));
        }
        return list;
    }

    private static void AddRoot(List<string> roots, string root)
    {
        try
        {
            if (!string.IsNullOrEmpty(root) && Directory.Exists(root) && !roots.Contains(root))
            {
                roots.Add(root);
            }
        }
        catch { }
    }

    /// <summary>在安装根目录里找 bin\java.exe（限定深度，别把整块硬盘翻一遍）。</summary>
    private static IEnumerable<string> ScanForJava(string root, int depth)
    {
        List<string> found = new List<string>();
        try
        {
            ScanInto(root, depth, found, new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "Windows", "$Recycle.Bin", "System32", "Temp", "node_modules", "cache", "Cache"
            });
        }
        catch { }
        return found;
    }

    private static void ScanInto(string dir, int depth, List<string> found, HashSet<string> skip)
    {
        if (depth < 0 || found.Count > 200)
        {
            return;
        }
        string direct = Path.Combine(dir, "bin", "java.exe");
        try
        {
            if (File.Exists(direct))
            {
                found.Add(direct);
                return;
            }
        }
        catch { }
        string[] children;
        try { children = Directory.GetDirectories(dir); } catch { return; }
        foreach (string child in children)
        {
            string name = Path.GetFileName(child);
            if (skip.Contains(name) || name.StartsWith("."))
            {
                continue;
            }
            ScanInto(child, depth - 1, found, skip);
        }
    }

    /// <summary>注册表里登记的 JDK / JRE 安装位置。</summary>
    private static IEnumerable<string> RegistryHomes()
    {
        List<string> list = new List<string>();
        string[] keys =
        {
            @"SOFTWARE\JavaSoft\JDK",
            @"SOFTWARE\JavaSoft\Java Development Kit",
            @"SOFTWARE\JavaSoft\Java Runtime Environment",
            @"SOFTWARE\JavaSoft\JRE",
            @"SOFTWARE\WOW6432Node\JavaSoft\JDK",
            @"SOFTWARE\WOW6432Node\JavaSoft\Java Runtime Environment",
            @"SOFTWARE\Eclipse Adoptium\JDK",
            @"SOFTWARE\Eclipse Adoptium\JRE",
            @"SOFTWARE\Eclipse Foundation\JDK",
            @"SOFTWARE\Microsoft\JDK",
            @"SOFTWARE\Azul Systems\Zulu",
            @"SOFTWARE\Amazon Corretto",
            @"SOFTWARE\BellSoft\LibericaJDK",
            @"SOFTWARE\Semeru\JDK",
            @"SOFTWARE\Red Hat\java"
        };
        string[] valueNames = { "JavaHome", "Path", "InstallationPath", "InstallLocation", "Home" };
        foreach (string key in keys)
        {
            foreach (RegistryKey root in new[] { Registry.LocalMachine, Registry.CurrentUser })
            {
                try
                {
                    using (RegistryKey baseKey = root.OpenSubKey(key))
                    {
                        if (baseKey == null)
                        {
                            continue;
                        }
                        foreach (string name in valueNames)
                        {
                            string text = baseKey.GetValue(name) as string;
                            if (text != null && text.Length > 0)
                            {
                                list.Add(text);
                            }
                        }
                        foreach (string sub in baseKey.GetSubKeyNames())
                        {
                            try
                            {
                                using (RegistryKey versionKey = baseKey.OpenSubKey(sub))
                                {
                                    if (versionKey == null)
                                    {
                                        continue;
                                    }
                                    foreach (string name in valueNames)
                                    {
                                        string text = versionKey.GetValue(name) as string;
                                        if (text != null && text.Length > 0)
                                        {
                                            list.Add(text);
                                        }
                                    }
                                }
                            }
                            catch { }
                        }
                    }
                }
                catch { }
            }
        }
        return list;
    }

    /// <summary>Minecraft 的运行时目录（官方启动器与各整合包常见位置）。</summary>
    private static IEnumerable<string> MinecraftRuntimeRoots()
    {
        List<string> list = new List<string>();
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string[] candidates =
        {
            Path.Combine(appData, ".minecraft", "runtime"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".minecraft", "runtime"),
            Path.Combine(appData, "com.modrinth.theseus", "meta", "runtime")
        };
        foreach (string dir in candidates)
        {
            try
            {
                if (Directory.Exists(dir))
                {
                    list.Add(dir);
                }
            }
            catch { }
        }
        return list;
    }

    /* ============================ 3. 拉起主程序 ============================ */

    private static void Launch(string javaExe, string baseDir)
    {
        string libDir = Path.Combine(baseDir, "lib");
        string extDir = Path.Combine(baseDir, "ext");
        string appJar = Path.Combine(baseDir, "app.jar");

        StringBuilder args = new StringBuilder();
        args.Append("--module-path \"").Append(libDir).Append("\" ");
        args.Append("--add-modules javafx.controls ");
        args.Append("-classpath \"").Append(appJar).Append(';').Append(extDir).Append("\\*\"").Append(' ');
        args.Append(AppMainClass);

        ProcessStartInfo info = new ProcessStartInfo(javaExe)
        {
            Arguments = args.ToString(),
            WorkingDirectory = baseDir,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        Process.Start(info);
    }

    /* ============================ 4. 缺 Java 的提示 ============================ */

    private static void ShowJavaMissing(string versionText, int major)
    {
        string found = versionText == null
                ? "这台电脑上还没有检测到 Java。"
                : "检测到 Java " + versionText + "，但版本太旧了。";

        string text = "衣启动器需要 Java " + MinJavaVersion + " 或更新版本才能运行。\n\n"
                + found + "\n\n"
                + "请先安装 Java " + MinJavaVersion + "（Eclipse Temurin 21 / Oracle JDK 21 / Zulu 21 都可以），"
                + "装完再打开衣启动器。\n\n"
                + "是：打开 Java 21 下载页（Temurin）\n"
                + "否：打开 Oracle 官方下载页\n"
                + "取消：关闭";

        DialogResult result = MessageBox.Show(text, "需要安装 Java " + MinJavaVersion,
                MessageBoxButtons.YesNoCancel, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button1);
        if (result == DialogResult.Yes)
        {
            OpenUrl(AdoptiumUrl);
        }
        else if (result == DialogResult.No)
        {
            OpenUrl(OracleUrl);
        }
    }

    private static void OpenUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception error)
        {
            MessageBox.Show("打不开浏览器，请手动访问：\n" + url + "\n\n(" + error.Message + ")",
                    "衣启动器", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    /* ============================ 5. 自检（--check） ============================ */

    private static bool HasFlag(string flag)
    {
        foreach (string arg in cliArgs)
        {
            if (string.Equals(arg, flag, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }
        return false;
    }

    private static string CheckPath()
    {
        return Path.Combine(Path.GetTempPath(), "yi_launcher_check.txt");
    }

    private static void WriteCheckReport(string baseDir, string javaExe, string versionText, int major)
    {
        StringBuilder report = new StringBuilder();
        report.AppendLine("payload=" + baseDir);
        report.AppendLine("app.jar=" + File.Exists(Path.Combine(baseDir, "app.jar")));
        int libs = Directory.Exists(Path.Combine(baseDir, "lib"))
                ? Directory.GetFiles(Path.Combine(baseDir, "lib"), "*.jar").Length : 0;
        int exts = Directory.Exists(Path.Combine(baseDir, "ext"))
                ? Directory.GetFiles(Path.Combine(baseDir, "ext"), "*.jar").Length : 0;
        report.AppendLine("lib=" + libs + " ext=" + exts);
        report.AppendLine("java=" + (javaExe ?? "(none)"));
        report.AppendLine("version=" + (versionText ?? "(none)"));
        report.AppendLine("major=" + major);
        report.AppendLine("min=" + MinJavaVersion);
        report.AppendLine("wouldPrompt=" + (javaExe == null || major < MinJavaVersion));
        File.WriteAllText(CheckPath(), report.ToString(), Encoding.UTF8);
    }
}
