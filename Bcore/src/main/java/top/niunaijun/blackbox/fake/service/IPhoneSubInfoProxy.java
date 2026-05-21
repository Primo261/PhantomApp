package top.niunaijun.blackbox.fake.service;

import android.util.Log;

import java.lang.reflect.Method;

import black.android.telephony.BRTelephonyManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;

public class IPhoneSubInfoProxy extends ClassInvocationStub {
    public static final String TAG = "IPhoneSubInfoProxy";

    public IPhoneSubInfoProxy() {
        try {
            if (BRTelephonyManager.get()._check_sServiceHandleCacheEnabled() != null) {
                BRTelephonyManager.get()._set_sServiceHandleCacheEnabled(true);
            }
            if (BRTelephonyManager.get()._check_getSubscriberInfoService() != null) {
                BRTelephonyManager.get().getSubscriberInfoService();
            }
        } catch (Exception e) { /* non-fatal */ }
    }

    @Override
    protected Object getWho() {
        try { return BRTelephonyManager.get().sIPhoneSubInfo(); }
        catch (Exception e) { return null; }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        try { BRTelephonyManager.get()._set_sIPhoneSubInfo(proxyInvocation); }
        catch (Exception e) { /* non-fatal */ }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try { MethodParameterUtils.replaceFirstAppPkg(args); }
        catch (Exception e) { /* non-fatal */ }
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            if (isSecurityException(t)) {
                Log.w(TAG, "IPhoneSubInfo call '" + method.getName()
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

    @Override
    public boolean isBadEnv() { return false; }

    @ProxyMethod("getLine1NumberForSubscriber")
    public static class GetLine1NumberForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getImeiForSubscriber")
    public static class GetImeiForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FingerprintManager fp = FingerprintManager.get();
            return fp != null ? fp.getImei(BActivityThread.getUserId()) : "000000000000000";
        }
    }

    @ProxyMethod("getMeidForSubscriber")
    public static class GetMeidForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FingerprintManager fp = FingerprintManager.get();
            return fp != null ? fp.getMeid(BActivityThread.getUserId()) : "00000000000000";
        }
    }

    @ProxyMethod("getSubscriberIdForSubscriber")
    public static class GetSubscriberIdForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FingerprintManager fp = FingerprintManager.get();
            return fp != null ? fp.getImsi(BActivityThread.getUserId()) : "000000000000000";
        }
    }

    @ProxyMethod("getIccSerialNumberForSubscriber")
    public static class GetIccSerialNumberForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FingerprintManager fp = FingerprintManager.get();
            return fp != null ? fp.getIccSerial(BActivityThread.getUserId()) : "0000000000000000000";
        }
    }

    @ProxyMethod("getDeviceIdForPhone")
    public static class GetDeviceIdForPhone extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FingerprintManager fp = FingerprintManager.get();
            return fp != null ? fp.getImei(BActivityThread.getUserId()) : "000000000000000";
        }
    }
}
