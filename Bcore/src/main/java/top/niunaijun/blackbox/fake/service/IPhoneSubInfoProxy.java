package top.niunaijun.blackbox.fake.service;

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
        return super.invoke(proxy, method, args);
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
