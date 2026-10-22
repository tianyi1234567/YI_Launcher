package com.yilauncher.yi_launcher.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 找本机可用的 Java：启动器自带 runtime、JAVA_HOME、PATH、常见安装目录都会扫一遍。 */
public final class JavaRuntimes {

    public record JavaRuntime(Path exe, int major, String display) {
        @Override
        public String toString() {
            return display;
        }
    }

    private static final Pattern VERSION = Pattern.compile("version \"([^\"]+)\"");

    private static volatile List<JavaRuntime> cache;

    private JavaRuntimes() {
    }

    public static synchronized List<JavaRuntime> all(Path minecraftRoot) {
        if (cache != null) {
            return cache;
        }
        Set<Path> candidates = new LinkedHashSet<>();
        collectBundled(minecraftRoot, candidates);
        collectEnvironment(candidates);
        collectKnownFolders(candidates);

        List<JavaRuntime> found = new ArrayList<>();
        for (Path exe : candidates) {
            JavaRuntime runtime = probe(exe);
            if (runtime != null) {
                found.add(runtime);
            }
        }
        found.sort(Comparator.comparingInt(JavaRuntime::major).thenComparing(r -> r.exe().toString()));
        cache = List.copyOf(found);
        return cache;
    }

    /** 优先精确匹配；否则取不低于要求的最低版本；再不行就用最高的。 */
    public static JavaRuntime pick(Path minecraftRoot, int requiredMajor) {
        List<JavaRuntime> runtimes = all(minecraftRoot);
        if (runtimes.isEmpty()) {
            return null;
        }
        for (JavaRuntime runtime : runtimes) {
            if (runtime.major() == requiredMajor) {
                return runtime;
            }
        }
        for (JavaRuntime runtime : runtimes) {
            if (runtime.major() >= requiredMajor) {
                return runtime;
            }
        }
        return runtimes.get(runtimes.size() - 1);
    }

    public static JavaRuntime probe(Path exe) {
        if (exe == null || !Files.isRegularFile(exe)) {
            return null;
        }
        try {
            Process process = new ProcessBuilder(exe.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Matcher matcher = VERSION.matcher(output);
            if (!matcher.find()) {
                return null;
            }
            String version = matcher.group(1);
            int major = majorOf(version);
            if (major <= 0) {
                return null;
            }
            return new JavaRuntime(exe, major, "Java " + version);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static int majorOf(String version) {
        if (version.startsWith("1.")) {
            String[] parts = version.split("\\.");
            return parts.length > 1 ? parseInt(parts[1]) : 0;
        }
        int dot = version.indexOf('.');
        return parseInt(dot < 0 ? version : version.substring(0, dot));
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static void collectBundled(Path root, Set<Path> out) {
        Path runtimeDir = root.resolve("runtime");
        if (!Files.isDirectory(runtimeDir)) {
            return;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(runtimeDir)) {
            for (Path dir : dirs) {
                addExe(out, dir.resolve("bin"));
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private static void collectEnvironment(Set<Path> out) {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addExe(out, Path.of(javaHome, "bin"));
        }
        // PATH 里的 java
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry.toLowerCase(Locale.ROOT).contains("java") || entry.toLowerCase(Locale.ROOT).contains("jdk")) {
                    addExe(out, Path.of(entry));
                }
            }
        }
    }

    private static void collectKnownFolders(Set<Path> out) {
        List<Path> bases = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            bases.add(Path.of(home, ".jdks"));
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            bases.add(Path.of("C:\\Program Files\\Java"));
            bases.add(Path.of("C:\\Program Files\\Zulu"));
            bases.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
            bases.add(Path.of("C:\\Program Files\\Amazon Corretto"));
            bases.add(Path.of("C:\\Program Files\\Microsoft"));
            bases.add(Path.of("C:\\Program Files\\BellSoft"));
            bases.add(Path.of("C:\\Program Files\\Semeru"));
        } else if (os.contains("mac")) {
            bases.add(Path.of("/Library/Java/JavaVirtualMachines"));
        } else {
            bases.add(Path.of("/usr/lib/jvm"));
        }
        for (Path base : bases) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
                for (Path dir : dirs) {
                    addExe(out, dir.resolve("bin"));
                    addExe(out, dir.resolve("Contents").resolve("Home").resolve("bin"));
                }
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    private static void addExe(Set<Path> out, Path binDir) {
        if (binDir == null || !Files.isDirectory(binDir)) {
            return;
        }
        Path javaw = binDir.resolve("javaw.exe");
        if (Files.isRegularFile(javaw)) {
            out.add(javaw);
            return;
        }
        Path java = binDir.resolve("java.exe");
        if (Files.isRegularFile(java)) {
            out.add(java);
            return;
        }
        Path plain = binDir.resolve("java");
        if (Files.isRegularFile(plain)) {
            out.add(plain);
        }
    }
}
