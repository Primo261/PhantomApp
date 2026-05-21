package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.net.wifi.BRIWifiManagerStub;
import black.android.net.wifi.BRWifiInfo;
import black.android.net.wifi.BRWifiSsid;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

public class IWifiManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IWifiManagerProxy";

    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(
            BRServiceManager.get().getService(Context.WIFI_SERVICE)
        );
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isBadEnv() { return false; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            if (isSecurityException(t)) {
                Log.w(TAG, "IWifiManager call '" + method.getName()
                        + "' denied, returning safe default", t);
                return safeDefault(method.getReturnType());
            }
            throw t;
        }
    }

    private static boolean isSecurityException(Throwable t) {
        Throwable cause = t;
        int depth = 0;
        while (cause != null && depth++ < 16) {
            if (cause instanceof SecurityException) return true;
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        return false;
    }

    private static Object safeDefault(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.FALSE;
        if (returnType == int.class     || returnType == Integer.class) return 0;
        if (returnType == long.class    || returnType == Long.class)    return 0L;
        if (returnType == float.class   || returnType == Float.class)   return 0f;
        if (returnType == double.class  || returnType == Double.class)  return 0.0;
        if (returnType == short.class   || returnType == Short.class)   return (short) 0;
        if (returnType == byte.class    || returnType == Byte.class)    return (byte) 0;
        if (returnType == char.class    || returnType == Character.class) return '\0';
        if (returnType == void.class    || returnType == Void.class)    return null;
        return null;
    }

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
                if (wifiInfo == null) return null;
                return spoofWifiInfo(wifiInfo);
            } catch (Exception e) {
                Slog.w(TAG, "getConnectionInfo error: " + e.getMessage());
                try { return method.invoke(who, args); }
                catch (Exception e2) { return null; }
            }
        }
    }

    @ProxyMethod("getPrivilegedConnectedNetwork")
    public static class GetPrivilegedConnectedNetwork extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
                if (wifiInfo == null) return null;
                return spoofWifiInfo(wifiInfo);
            } catch (Exception e) {
                try { return method.invoke(who, args); }
                catch (Exception e2) { return null; }
            }
        }
    }

    // Spoofe toutes les valeurs MAC dans un WifiInfo
    private static WifiInfo spoofWifiInfo(WifiInfo wifiInfo) {
        FingerprintManager fp = FingerprintManager.get();
        if (fp == null) return wifiInfo;

        int userId = BActivityThread.getUserId();
        String fakeMac = fp.getWifiMac(userId);

        // Méthode 1 — via BRWifiInfo reflection wrappers
        try { BRWifiInfo.get(wifiInfo)._set_mBSSID(fakeMac); }
        catch (Throwable ignored) {}

        try { BRWifiInfo.get(wifiInfo)._set_mMacAddress(fakeMac); }
        catch (Throwable ignored) {}

        // Méthode 2 — via réflexion directe sur les champs WifiInfo
        setWifiField(wifiInfo, "mBSSID",      fakeMac);
        setWifiField(wifiInfo, "mMacAddress", fakeMac);

        // SSID par slot
        try {
            BRWifiInfo.get(wifiInfo)._set_mWifiSsid(
                BRWifiSsid.get().createFromAsciiEncoded("Phantom_" + userId)
            );
        } catch (Throwable ignored) {}

        Slog.d(TAG, "WiFi MAC spoofed → " + fakeMac + " slot=" + userId);
        return wifiInfo;
    }

    private static void setWifiField(Object obj, String fieldName, String value) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null) {
                try {
                    Field f = clazz.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {}
    }
}
