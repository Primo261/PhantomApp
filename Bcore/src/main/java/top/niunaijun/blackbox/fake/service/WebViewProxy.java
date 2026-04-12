package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

public class WebViewProxy extends ClassInvocationStub {
    public static final String TAG = "WebViewProxy";

    public WebViewProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Génère un User Agent Android réaliste et cohérent avec le profil du slot.
     * Correspond au format exact qu'une vraie WebView retournerait sur l'appareil spoofé.
     * CRITIQUE : ne doit JAMAIS contenir "BlackBox", "Phantom", "virtual" ou toute
     * signature qui permettrait à DataDome / Cloudflare de détecter l'environnement.
     */
    private static String buildSpoofedUserAgent(int userId) {
        try {
            FingerprintManager fp = FingerprintManager.get();
            String model = fp != null ? fp.getModel(userId) : Build.MODEL;
            String androidVersion = "14"; // défaut raisonnable

            // Déduit la version Android du profil
            if (fp != null) {
                String fingerprint = fp.getBuildFingerprint(userId);
                if (fingerprint.contains(":13/")) androidVersion = "13";
                else if (fingerprint.contains(":14/")) androidVersion = "14";
                else if (fingerprint.contains(":12/")) androidVersion = "12";
            }

            // Format exact d'un vrai User Agent Android WebView
            // ex: Mozilla/5.0 (Linux; Android 14; SM-G991B) AppleWebKit/537.36
            //     (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36
            String chromeVersion = getChromeVersionForAndroid(androidVersion);
            return "Mozilla/5.0 (Linux; Android " + androidVersion + "; " + model + ")"
                    + " AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/" + chromeVersion + " Mobile Safari/537.36";
        } catch (Exception e) {
            Slog.w(TAG, "buildSpoofedUserAgent error, using fallback: " + e.getMessage());
            // Fallback réaliste — aucune signature détectable
            return "Mozilla/5.0 (Linux; Android 14; Pixel 8)"
                    + " AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.6367.82 Mobile Safari/537.36";
        }
    }

    /**
     * Retourne une version Chrome cohérente avec la version Android.
     * Utilise des versions réelles et récentes.
     */
    private static String getChromeVersionForAndroid(String androidVersion) {
        switch (androidVersion) {
            case "12": return "120.0.6099.144";
            case "13": return "122.0.6261.119";
            case "14": return "124.0.6367.82";
            default:   return "124.0.6367.82";
        }
    }

    private static String getUniqueWebViewDir(String suffix) {
        try {
            Context ctx = BlackBoxCore.getContext();
            if (ctx != null) {
                String userId = String.valueOf(BActivityThread.getUserId());
                String dir = ctx.getApplicationInfo().dataDir + "/webview_" + userId + "_" + suffix;
                File f = new File(dir);
                if (!f.exists()) f.mkdirs();
                return dir;
            }
        } catch (Exception e) {
            Slog.w(TAG, "getUniqueWebViewDir error: " + e.getMessage());
        }
        return null;
    }

    // ─── Hooks ───────────────────────────────────────────────────────────────

    @ProxyMethod("<init>")
    public static class Constructor extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Context context = null;
            try {
                if (args != null && args.length > 0 && args[0] instanceof Context) {
                    context = (Context) args[0];
                }
                if (context == null) context = BlackBoxCore.getContext();

                // Répertoire unique par slot pour isoler cookies/cache/storage
                if (context != null) {
                    int userId = BActivityThread.getUserId();
                    String uniqueDir = context.getApplicationInfo().dataDir
                            + "/webview_" + userId + "_" + android.os.Process.myPid();
                    File dir = new File(uniqueDir);
                    if (!dir.exists()) dir.mkdirs();
                    System.setProperty("webview.data.dir", uniqueDir);
                    System.setProperty("webview.cache.dir", uniqueDir + "/cache");
                    System.setProperty("webview.cookies.dir", uniqueDir + "/cookies");
                }

                Object result = method.invoke(who, args);

                if (result instanceof WebView) {
                    configureWebView((WebView) result);
                }
                return result;
            } catch (Exception e) {
                Slog.w(TAG, "WebView Constructor failed: " + e.getMessage());
                if (context != null) {
                    try {
                        WebView wv = new WebView(context);
                        WebSettings s = wv.getSettings();
                        if (s != null) s.setJavaScriptEnabled(true);
                        return wv;
                    } catch (Exception ignored) {}
                }
                return null;
            }
        }

        private void configureWebView(WebView webView) {
            try {
                WebSettings settings = webView.getSettings();
                if (settings == null) return;

                int userId = BActivityThread.getUserId();

                // ══════════════════════════════════════════════════════════════
                // CRITIQUE : User Agent spoofé et réaliste — ZÉRO signature
                // BlackBox/Phantom/virtual. C'est la détection #1 de DataDome.
                // ══════════════════════════════════════════════════════════════
                settings.setUserAgentString(buildSpoofedUserAgent(userId));

                // Fonctionnalités nécessaires pour les apps modernes
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setCacheMode(WebSettings.LOAD_DEFAULT);
                settings.setBlockNetworkLoads(false);
                settings.setBlockNetworkImage(false);
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    settings.setAllowFileAccessFromFileURLs(false);
                    settings.setAllowUniversalAccessFromFileURLs(false);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    settings.setSafeBrowsingEnabled(false);
                }

                // AppCache (deprecated mais certaines apps l'utilisent)
                try {
                    Method setAppCacheEnabled = settings.getClass()
                            .getMethod("setAppCacheEnabled", boolean.class);
                    setAppCacheEnabled.invoke(settings, true);
                    if (webView.getContext() != null) {
                        Method setAppCachePath = settings.getClass()
                                .getMethod("setAppCachePath", String.class);
                        setAppCachePath.invoke(settings,
                                webView.getContext().getCacheDir().getAbsolutePath());
                    }
                } catch (Throwable ignored) {}

                try { webView.setNetworkAvailable(true); } catch (Exception ignored) {}

                Slog.d(TAG, "WebView configured — UA: " + settings.getUserAgentString());

            } catch (Exception e) {
                Slog.w(TAG, "configureWebView failed: " + e.getMessage());
            }
        }
    }

    @ProxyMethod("setDataDirectorySuffix")
    public static class SetDataDirectorySuffix extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0) {
                    String suffix = (String) args[0];
                    String userId = String.valueOf(BActivityThread.getUserId());
                    // Suffixe unique par slot — sans exposer "BlackBox" ou "Phantom"
                    args[0] = suffix + "_u" + userId;
                    Slog.d(TAG, "WebView: setDataDirectorySuffix → " + args[0]);
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "setDataDirectorySuffix failed: " + e.getMessage());
                return null;
            }
        }
    }

    @ProxyMethod("getDataDirectory")
    public static class GetDataDirectory extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                String dir = getUniqueWebViewDir("data");
                if (dir != null) return dir;
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "getDataDirectory failed: " + e.getMessage());
                return "/data/data/" + BlackBoxCore.getHostPkg() + "/webview_data";
            }
        }
    }

    @ProxyMethod("getInstance")
    public static class GetWebViewDatabaseInstance extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                String dbPath = getUniqueWebViewDir("db");
                if (dbPath != null) System.setProperty("webview.database.path", dbPath);
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "getInstance failed: " + e.getMessage());
                return null;
            }
        }
    }

    @ProxyMethod("loadUrl")
    public static class LoadUrl extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                Slog.d(TAG, "WebView: loadUrl → " + args[0]);
            }
            return method.invoke(who, args);
        }
    }
}
