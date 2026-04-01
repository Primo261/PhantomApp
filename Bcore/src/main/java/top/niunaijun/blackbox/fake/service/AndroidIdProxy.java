package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

public class AndroidIdProxy extends ClassInvocationStub {
    public static final String TAG = "AndroidIdProxy";

    public AndroidIdProxy() {
        super();
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getAndroidId")
    public static class GetAndroidId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getString")
    public static class GetString extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                            key.contains("secure_id") || key.contains("device_id")) {
                        return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
            }
        }
    }

    @ProxyMethod("getLong")
    public static class GetLong extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                            key.contains("secure_id") || key.contains("device_id")) {
                        return Long.parseLong(
                                FingerprintManager.get().getAndroidId(BActivityThread.getUserId()), 16
                        );
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return 0L;
            }
        }
    }

    @ProxyMethod("get")
    public static class Get extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                            key.contains("secure_id") || key.contains("device_id")) {
                        return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
            }
        }
    }

    @ProxyMethod("read")
    public static class Read extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0 && args[0] instanceof String) {
                    String key = (String) args[0];
                    if (key.contains("android_id") || key.contains("ANDROID_ID") ||
                            key.contains("secure_id") || key.contains("device_id")) {
                        return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
                    }
                }
                return method.invoke(who, args);
            } catch (Exception e) {
                return FingerprintManager.get().getAndroidId(BActivityThread.getUserId());
            }
        }
    }
}