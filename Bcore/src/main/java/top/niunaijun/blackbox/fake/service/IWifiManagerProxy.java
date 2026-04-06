package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.wifi.WifiInfo;

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

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
                if (wifiInfo == null) return null;

                FingerprintManager fp = FingerprintManager.get();
                if (fp == null) return wifiInfo;

                int userId = BActivityThread.getUserId();
                String fakeMac = fp.getWifiMac(userId);

                try { BRWifiInfo.get(wifiInfo)._set_mBSSID(fakeMac); }
                catch (Exception e) { Slog.w(TAG, "setBSSID failed: " + e.getMessage()); }

                try { BRWifiInfo.get(wifiInfo)._set_mMacAddress(fakeMac); }
                catch (Exception e) { Slog.w(TAG, "setMacAddress failed: " + e.getMessage()); }

                try {
                    BRWifiInfo.get(wifiInfo)._set_mWifiSsid(
                        BRWifiSsid.get().createFromAsciiEncoded("PhantomWifi_" + userId)
                    );
                } catch (Exception e) { Slog.w(TAG, "setSSID failed: " + e.getMessage()); }

                Slog.d(TAG, "Spoofed WiFi MAC → " + fakeMac + " slot=" + userId);
                return wifiInfo;
            } catch (Exception e) {
                Slog.w(TAG, "getConnectionInfo hook error: " + e.getMessage());
                return method.invoke(who, args);
            }
        }
    }
}
