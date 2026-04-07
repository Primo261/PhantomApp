package top.niunaijun.blackbox.fake.service;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Intercepte les requêtes ContentProvider vers Settings.Secure
 * pour injecter notre Android ID par slot.
 *
 * Android ID est lu via :
 *   content://settings/secure  → colonne "value" pour la clé "android_id"
 *
 * Cette classe est enregistrée dans HookManager AVANT ContentResolverProxy
 * existant pour prendre la priorité.
 */
public class PhantomContentProviderProxy extends ClassInvocationStub {
    public static final String TAG = "PhantomContentProvider";

    private static final String SETTINGS_SECURE_URI = "content://settings/secure";
    private static final String ANDROID_ID_KEY = "android_id";

    @Override
    protected Object getWho() { return null; }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    public boolean isBadEnv() { return false; }

    /**
     * Intercepte ContentResolver.query() pour Settings.Secure
     */
    @ProxyMethod("query")
    public static class Query extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Uri uri = findUri(args);
                if (uri != null && isSettingsSecure(uri)) {
                    String name = extractNameFromUri(uri, args);
                    if (ANDROID_ID_KEY.equals(name)) {
                        FingerprintManager fp = FingerprintManager.get();
                        if (fp != null) {
                            String fakeId = fp.getAndroidId(BActivityThread.getUserId());
                            Slog.d(TAG, "Intercepted android_id query → " + fakeId);
                            return buildAndroidIdCursor(fakeId);
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "query hook error: " + e.getMessage());
                return method.invoke(who, args);
            }
        }
    }

    /**
     * Intercepte ContentResolver.call() pour Settings.Secure
     */
    @ProxyMethod("call")
    public static class Call extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                // args[1] = method name, args[2] = key string
                if (args != null && args.length >= 3) {
                    String key = null;
                    for (Object arg : args) {
                        if (arg instanceof String && ANDROID_ID_KEY.equals(arg)) {
                            key = (String) arg;
                            break;
                        }
                    }
                    Uri uri = findUri(args);
                    if (key != null && uri != null && isSettingsSecure(uri)) {
                        FingerprintManager fp = FingerprintManager.get();
                        if (fp != null) {
                            String fakeId = fp.getAndroidId(BActivityThread.getUserId());
                            Bundle b = new Bundle();
                            b.putString("value", fakeId);
                            return b;
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return method.invoke(who, args);
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isSettingsSecure(Uri uri) {
        String uriStr = uri.toString();
        return uriStr.startsWith(SETTINGS_SECURE_URI)
            || uriStr.contains("settings/secure");
    }

    private static Uri findUri(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Uri) return (Uri) arg;
        }
        return null;
    }

    private static String extractNameFromUri(Uri uri, Object[] args) {
        // URI format: content://settings/secure/android_id
        String path = uri.getLastPathSegment();
        if (path != null) return path;

        // fallback: cherche dans la sélection args
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String && ANDROID_ID_KEY.equals(arg)) {
                    return ANDROID_ID_KEY;
                }
                if (arg instanceof String[]) {
                    for (String s : (String[]) arg) {
                        if (ANDROID_ID_KEY.equals(s)) return ANDROID_ID_KEY;
                    }
                }
            }
        }
        return null;
    }

    private static Cursor buildAndroidIdCursor(String value) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"name", "value"});
        cursor.addRow(new Object[]{ANDROID_ID_KEY, value});
        return cursor;
    }
}
