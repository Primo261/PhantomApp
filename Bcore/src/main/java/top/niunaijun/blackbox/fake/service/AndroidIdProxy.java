package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Intercepte les lectures d'Android ID et de Google Advertising ID
 * via Settings.Secure — couverture des apps qui passent par cette couche.
 * La couche ContentProvider est couverte séparément par ContentProviderStub.
 */
public class AndroidIdProxy extends ClassInvocationStub {
    public static final String TAG = "AndroidIdProxy";

    @Override
    protected Object getWho() { return null; }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    public boolean isBadEnv() { return false; }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String safeAndroidId() {
        try {
            FingerprintManager fp = FingerprintManager.get();
            if (fp != null) return fp.getAndroidId(BActivityThread.getUserId());
        } catch (Exception e) {
            Slog.w(TAG, "safeAndroidId error: " + e.getMessage());
        }
        return "0000000000000000";
    }

    private static String safeGaid() {
        try {
            FingerprintManager fp = FingerprintManager.get();
            if (fp != null) return fp.getAdvertisingId(BActivityThread.getUserId());
        } catch (Exception e) {
            Slog.w(TAG, "safeGaid error: " + e.getMessage());
        }
        return "00000000-0000-0000-0000-000000000000";
    }

    private static boolean isAndroidIdKey(String key) {
        return key != null && (
                key.equals("android_id") ||
                key.contains("android_id") ||
                key.contains("ANDROID_ID") ||
                key.contains("secure_id") ||
                key.contains("device_id"));
    }

    private static boolean isGaidKey(String key) {
        return key != null && (
                key.equals("advertising_id") ||
                key.contains("advertisingId") ||
                key.contains("advertising_id") ||
                key.contains("gaid"));
    }

    // ─── Hooks Settings.Secure ───────────────────────────────────────────────

    @ProxyMethod("getAndroidId")
    public static class GetAndroidId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return safeAndroidId();
        }
    }

    @ProxyMethod("getString")
    public static class GetString extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            String key = (String) arg;
                            if (isAndroidIdKey(key)) return safeAndroidId();
                            if (isGaidKey(key))      return safeGaid();
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return safeAndroidId();
            }
        }
    }

    @ProxyMethod("getStringForUser")
    public static class GetStringForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            String key = (String) arg;
                            if (isAndroidIdKey(key)) return safeAndroidId();
                            if (isGaidKey(key))      return safeGaid();
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("Calling uid")) return "true";
                throw e;
            }
        }
    }

    @ProxyMethod("getLong")
    public static class GetLong extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            String key = (String) arg;
                            if (isAndroidIdKey(key)) {
                                try {
                                    return Long.parseLong(safeAndroidId(), 16);
                                } catch (Exception ex) { return 0L; }
                            }
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) { return 0L; }
        }
    }

    @ProxyMethod("get")
    public static class Get extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null) {
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            String key = (String) arg;
                            if (isAndroidIdKey(key)) return safeAndroidId();
                            if (isGaidKey(key))      return safeGaid();
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return safeAndroidId();
            }
        }
    }
}
