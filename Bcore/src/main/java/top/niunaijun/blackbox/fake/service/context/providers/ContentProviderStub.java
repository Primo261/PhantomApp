package top.niunaijun.blackbox.fake.service.context.providers;

import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.AttributionSourceUtils;
import top.niunaijun.blackbox.utils.Slog;

public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mAppPkg;

    // Authorities GMS liées au Google Advertising ID
    private static final String GAID_AUTHORITY = "com.google.android.gms.ads.identifier.provider";

    public IInterface wrapper(final IInterface contentProviderProxy, final String appPkg) {
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    protected void onBindMethod() {}

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }

        String methodName = method.getName();

        if ("call".equals(methodName)) {
            if (args != null) {
                // Interception Android ID via call()
                String androidIdSpoofed = tryInterceptAndroidIdCall(args);
                if (androidIdSpoofed != null) {
                    Bundle result = new Bundle();
                    result.putString("value", androidIdSpoofed);
                    Slog.d(TAG, "Spoofed android_id via call(): " + androidIdSpoofed);
                    return result;
                }
                // Interception Google Advertising ID via call()
                Bundle gaidBundle = tryInterceptGaidCall(args);
                if (gaidBundle != null) {
                    Slog.d(TAG, "Spoofed GAID via call()");
                    return gaidBundle;
                }
                AttributionSourceUtils.fixAttributionSourceInArgs(args);
            }
        } else {
            if ("query".equals(methodName) && args != null) {
                // Interception Android ID via query()
                String androidIdSpoofed = tryInterceptAndroidIdQuery(args);
                if (androidIdSpoofed != null) {
                    MatrixCursor cursor = new MatrixCursor(new String[]{"name", "value"});
                    cursor.addRow(new Object[]{"android_id", androidIdSpoofed});
                    Slog.d(TAG, "Spoofed android_id via query(): " + androidIdSpoofed);
                    return cursor;
                }
                // Interception Google Advertising ID via query()
                MatrixCursor gaidCursor = tryInterceptGaidQuery(args);
                if (gaidCursor != null) {
                    Slog.d(TAG, "Spoofed GAID via query()");
                    return gaidCursor;
                }
            }
            if (args != null && args.length > 0) {
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg instanceof String) {
                        String strArg = (String) arg;
                        if (!isSystemProviderAuthority(strArg)) {
                            args[i] = mAppPkg;
                        }
                    }
                }
                AttributionSourceUtils.fixAttributionSourceInArgs(args);
            }
        }

        if (methodName.equals("query") || methodName.equals("insert") ||
                methodName.equals("update") || methodName.equals("delete") ||
                methodName.equals("bulkInsert") || methodName.equals("call")) {
            try {
                return method.invoke(mBase, args);
            } catch (Throwable e) {
                Throwable cause = e.getCause();
                if (isUidMismatchError(cause)) {
                    Slog.w(TAG, "UID mismatch in ContentProvider call: "
                            + (cause != null ? cause.getMessage() : ""));
                    return getSafeDefaultValue(methodName, method.getReturnType());
                } else if (cause instanceof RuntimeException) {
                    String message = cause.getMessage();
                    if (message != null && (message.contains("uid") || message.contains("permission"))) {
                        Slog.w(TAG, "Permission/UID error: " + message);
                        return getSafeDefaultValue(methodName, method.getReturnType());
                    }
                }
                if ("call".equals(methodName)) {
                    return getSafeDefaultValue(methodName, method.getReturnType());
                }
                if (cause != null) throw cause;
                throw e;
            }
        }

        try {
            return method.invoke(mBase, args);
        } catch (Throwable e) {
            Throwable cause = e.getCause();
            if (isUidMismatchError(cause)) {
                return getSafeDefaultValue(methodName, method.getReturnType());
            }
            if (cause != null) throw cause;
            throw e;
        }
    }

    // ─── Interception Android ID ──────────────────────────────────────────────

    private String tryInterceptAndroidIdCall(Object[] args) {
        try {
            for (Object arg : args) {
                if (arg instanceof String && "android_id".equals(arg)) {
                    return getSpoofedAndroidId();
                }
                if (arg instanceof String && ((String) arg).contains("android_id")) {
                    return getSpoofedAndroidId();
                }
                if (arg instanceof Bundle) {
                    Bundle b = (Bundle) arg;
                    if ("android_id".equals(b.getString("key"))) return getSpoofedAndroidId();
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "tryInterceptAndroidIdCall error: " + e.getMessage());
        }
        return null;
    }

    private String tryInterceptAndroidIdQuery(Object[] args) {
        try {
            for (Object arg : args) {
                if (arg instanceof String && ((String) arg).contains("android_id")) {
                    return getSpoofedAndroidId();
                }
                if (arg instanceof android.net.Uri
                        && arg.toString().contains("android_id")) {
                    return getSpoofedAndroidId();
                }
                if (arg instanceof String[]) {
                    for (String s : (String[]) arg) {
                        if (s != null && s.contains("android_id")) return getSpoofedAndroidId();
                    }
                }
                if (arg instanceof Bundle) {
                    Bundle b = (Bundle) arg;
                    String sel = b.getString("android:query-arg-sql-selection");
                    if (sel != null && sel.contains("android_id")) return getSpoofedAndroidId();
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "tryInterceptAndroidIdQuery error: " + e.getMessage());
        }
        return null;
    }

    private String getSpoofedAndroidId() {
        try {
            FingerprintManager fp = FingerprintManager.get();
            if (fp != null) return fp.getAndroidId(BActivityThread.getUserId());
        } catch (Exception e) {
            Slog.w(TAG, "getSpoofedAndroidId error: " + e.getMessage());
        }
        return "0000000000000000";
    }

    // ─── Interception Google Advertising ID ──────────────────────────────────

    /**
     * Intercepte call() vers le provider GAID de GMS.
     * GMS retourne un Bundle avec "advertisingId" et "limitAdTracking".
     */
    private Bundle tryInterceptGaidCall(Object[] args) {
        try {
            boolean isGaidCall = false;
            for (Object arg : args) {
                if (arg instanceof String) {
                    String s = (String) arg;
                    if (s.contains(GAID_AUTHORITY)
                            || s.contains("advertisingId")
                            || s.contains("advertising_id")
                            || s.equals("getAdvertisingId")) {
                        isGaidCall = true;
                        break;
                    }
                }
            }
            if (!isGaidCall) return null;
            FingerprintManager fp = FingerprintManager.get();
            if (fp == null) return null;
            String gaid = fp.getAdvertisingId(BActivityThread.getUserId());
            Bundle result = new Bundle();
            result.putString("advertisingId", gaid);
            result.putBoolean("limitAdTracking", false);
            Slog.d(TAG, "Spoofed GAID via call() bundle: " + gaid);
            return result;
        } catch (Exception e) {
            Slog.w(TAG, "tryInterceptGaidCall error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Intercepte query() vers le provider GAID de GMS.
     * Retourne un MatrixCursor avec les colonnes attendues par AdvertisingIdClient.
     */
    private MatrixCursor tryInterceptGaidQuery(Object[] args) {
        try {
            boolean isGaidQuery = false;
            for (Object arg : args) {
                if (arg instanceof String) {
                    String s = (String) arg;
                    if (s.contains(GAID_AUTHORITY)
                            || s.contains("advertisingId")
                            || s.contains("advertising_id")) {
                        isGaidQuery = true;
                        break;
                    }
                }
                if (arg instanceof android.net.Uri) {
                    String uriStr = arg.toString();
                    if (uriStr.contains("ads.identifier") || uriStr.contains("advertisingId")) {
                        isGaidQuery = true;
                        break;
                    }
                }
            }
            if (!isGaidQuery) return null;
            FingerprintManager fp = FingerprintManager.get();
            if (fp == null) return null;
            String gaid = fp.getAdvertisingId(BActivityThread.getUserId());
            MatrixCursor cursor = new MatrixCursor(new String[]{"advertisingId", "limitAdTracking"});
            cursor.addRow(new Object[]{gaid, 0});
            Slog.d(TAG, "Spoofed GAID via query() cursor: " + gaid);
            return cursor;
        } catch (Exception e) {
            Slog.w(TAG, "tryInterceptGaidQuery error: " + e.getMessage());
            return null;
        }
    }

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null) return false;
        return authority.equals("settings") ||
               authority.equals("settings_global") ||
               authority.equals("settings_system") ||
               authority.equals("settings_secure") ||
               authority.equals("media") ||
               authority.equals("telephony") ||
               authority.startsWith("android.provider.Settings") ||
               // GMS providers — on garde l'authority originale
               authority.contains("google") ||
               authority.contains("gms");
    }

    private boolean isUidMismatchError(Throwable error) {
        if (error == null) return false;
        String message = error.getMessage();
        if (message == null) return false;
        return (message.contains("Calling uid") && message.contains("doesn't match source uid")) ||
               (message.contains("uid") && message.contains("permission")) ||
               message.contains("SecurityException") ||
               message.contains("UID mismatch");
    }

    private Object getSafeDefaultValue(String methodName, Class<?> returnType) {
        if (returnType != null) {
            if (returnType == String.class)                      return "true";
            if (returnType == int.class || returnType == Integer.class)  return 1;
            if (returnType == long.class || returnType == Long.class)    return 1L;
            if (returnType == float.class || returnType == Float.class)  return 1.0f;
            if (returnType == boolean.class || returnType == Boolean.class) return true;
            if (returnType == Bundle.class)                      return new Bundle();
        }
        switch (methodName) {
            case "query":       return null;
            case "insert":      return null;
            case "update":
            case "delete":
            case "bulkInsert":  return 0;
            case "call":        return new Bundle();
            default:            return null;
        }
    }
}
