package top.niunaijun.blackbox.fake.service;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

public class ISettingsProviderProxy extends ClassInvocationStub {
    public static final String TAG = "ISettingsProviderProxy";

    @Override
    protected Object getWho() {
        // On intercepte Settings.Secure directement via la classe
        try {
            return Settings.Secure.class;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        // Hook statique via ClassInvocationStub
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public void injectHook() {
        // On intercepte via ContentResolver pour Android ID
        try {
            super.injectHook();
        } catch (Exception e) {
            Slog.w(TAG, "injectHook error (non-fatal): " + e.getMessage());
        }
    }

    @ProxyMethod("getStringForUser")
    public static class GetStringForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0) {
                    String key = null;
                    for (Object arg : args) {
                        if (arg instanceof String) {
                            key = (String) arg;
                            break;
                        }
                    }
                    if (Settings.Secure.ANDROID_ID.equals(key)) {
                        FingerprintManager fp = FingerprintManager.get();
                        if (fp != null) {
                            String id = fp.getAndroidId(BActivityThread.getUserId());
                            Slog.d(TAG, "Spoofed android_id → " + id);
                            return id;
                        }
                    }
                    if (key != null && key.contains("feature_flag")) {
                        return "true";
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("Calling uid")
                        && errorMsg.contains("doesn't match source uid")) {
                    return "true";
                }
                throw e;
            }
        }
    }

    @ProxyMethod("getString")
    public static class GetString extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0) {
                    String key = null;
                    for (Object arg : args) {
                        if (arg instanceof String) { key = (String) arg; break; }
                    }
                    if (Settings.Secure.ANDROID_ID.equals(key)) {
                        FingerprintManager fp = FingerprintManager.get();
                        if (fp != null) {
                            return fp.getAndroidId(BActivityThread.getUserId());
                        }
                    }
                    if (key != null && key.contains("feature_flag")) return "true";
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("Calling uid")
                        && errorMsg.contains("doesn't match source uid")) {
                    return "true";
                }
                throw e;
            }
        }
    }

    @ProxyMethod("getIntForUser")
    public static class GetIntForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Calling uid")) return 1;
                throw e;
            }
        }
    }

    @ProxyMethod("getInt")
    public static class GetInt extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Calling uid")) return 1;
                throw e;
            }
        }
    }

    @ProxyMethod("getLongForUser")
    public static class GetLongForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Calling uid")) return 1L;
                throw e;
            }
        }
    }

    @ProxyMethod("getLong")
    public static class GetLong extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Calling uid")) return 1L;
                throw e;
            }
        }
    }

    @ProxyMethod("getFloatForUser")
    public static class GetFloatForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Calling uid")) return 1.0f;
                throw e;
            }
        }
    }

    @ProxyMethod("getFloat")
    public static class GetFloat extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try { return method.invoke(who, args); }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Calling uid")) return 1.0f;
                throw e;
            }
        }
    }
}
