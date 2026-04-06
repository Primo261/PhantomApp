package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

public class IBluetoothManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IBluetoothManagerProxy";

    public IBluetoothManagerProxy() {
        super(BRServiceManager.get().getService(Context.BLUETOOTH_SERVICE));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder binder = BRServiceManager.get().getService(Context.BLUETOOTH_SERVICE);
            return binder;
        } catch (Exception e) { return null; }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        try { replaceSystemService(Context.BLUETOOTH_SERVICE); }
        catch (Exception e) { /* non-fatal */ }
    }

    @Override
    public boolean isBadEnv() { return false; }

    @ProxyMethod("getAddress")
    public static class GetAddress extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FingerprintManager fp = FingerprintManager.get();
            return fp != null ? fp.getBluetoothMac(BActivityThread.getUserId()) : "02:00:00:00:00:00";
        }
    }

    @ProxyMethod("getName")
    public static class GetName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return "PhantomDevice_" + BActivityThread.getUserId();
        }
    }
}
