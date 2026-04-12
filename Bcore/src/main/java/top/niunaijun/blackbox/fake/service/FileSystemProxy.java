package top.niunaijun.blackbox.fake.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

public class FileSystemProxy extends ClassInvocationStub {
    public static final String TAG = "FileSystemProxy";

    // Mots-clés à filtrer de /proc/self/maps — révèlent l'environnement BlackBox
    private static final String[] MAPS_BLACKLIST = {
        "blackbox", "BlackBox", "niunaijun", "phantom", "Phantom",
        "top.niunaijun", "com.phantom"
    };

    public FileSystemProxy() {
        super();
    }

    @Override
    protected Object getWho() { return null; }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    public boolean isBadEnv() { return false; }

    // ─── Filtrage /proc/self/maps ─────────────────────────────────────────────

    /**
     * Filtre le contenu de /proc/self/maps pour supprimer toute ligne
     * révélant l'environnement BlackBox/PhantomApp.
     */
    public static String filterMapsContent(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            boolean blocked = false;
            for (String keyword : MAPS_BLACKLIST) {
                if (line.contains(keyword)) { blocked = true; break; }
            }
            if (!blocked) { sb.append(line).append("\n"); }
        }
        return sb.toString();
    }

    public static boolean isSensitivePath(String path) {
        if (path == null) return false;
        return path.equals("/proc/self/maps") || path.equals("/proc/self/smaps")
                || path.contains("/proc/self/maps");
    }

    // ─── Hooks File ───────────────────────────────────────────────────────────

    @ProxyMethod("mkdirs")
    public static class Mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                File file = (File) who;
                String path = file.getAbsolutePath();
                if (path.contains("Helium Crashpad") || path.contains("HeliumCrashReporter")) {
                    return true;
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "mkdirs failed: " + e.getMessage());
                return true;
            }
        }
    }

    @ProxyMethod("mkdir")
    public static class Mkdir extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                File file = (File) who;
                String path = file.getAbsolutePath();
                if (path.contains("Helium Crashpad") || path.contains("HeliumCrashReporter")) {
                    return true;
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "mkdir failed: " + e.getMessage());
                return true;
            }
        }
    }

    @ProxyMethod("isDirectory")
    public static class IsDirectory extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                File file = (File) who;
                String path = file.getAbsolutePath();
                if (path.contains("Helium Crashpad") || path.contains("HeliumCrashReporter")) {
                    return true;
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "isDirectory failed: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Intercepte Runtime.exec() pour filtrer la sortie de commandes
     * qui lisent /proc/self/maps (ex: cat /proc/self/maps, grep ... /proc/self/maps).
     */
    @ProxyMethod("exec")
    public static class Exec extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                boolean isMapsRead = false;
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String && ((String) arg).contains("proc/self/maps")) {
                            isMapsRead = true; break;
                        }
                        if (arg instanceof String[] ) {
                            for (String s : (String[]) arg) {
                                if (s != null && s.contains("proc/self/maps")) {
                                    isMapsRead = true; break;
                                }
                            }
                        }
                    }
                }
                if (!isMapsRead) return method.invoke(who, args);

                // Exécute la commande et filtre la sortie
                Process proc = (Process) method.invoke(who, args);
                if (proc == null) return null;
                StringBuilder raw = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        raw.append(line).append("\n");
                    }
                }
                String filtered = filterMapsContent(raw.toString());
                Slog.d(TAG, "Filtered /proc/self/maps via exec()");
                return new FilteredProcess(filtered);
            } catch (Exception e) {
                Slog.w(TAG, "exec hook error: " + e.getMessage());
                return method.invoke(who, args);
            }
        }
    }

    // ─── Process wrapper avec sortie filtrée ──────────────────────────────────

    /**
     * Wrapper de Process qui retourne un InputStream filtré sur stdout.
     * Utilisé pour masquer les chemins BlackBox quand une app lit /proc/self/maps
     * via Runtime.exec().
     */
    private static class FilteredProcess extends Process {
        private final byte[] mFilteredOutput;
        FilteredProcess(String filteredContent) {
            mFilteredOutput = filteredContent.getBytes(StandardCharsets.UTF_8);
        }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(mFilteredOutput);
        }
        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
        @Override public java.io.OutputStream getOutputStream() {
            return new java.io.OutputStream() { @Override public void write(int b) {} };
        }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() {}
    }
}
