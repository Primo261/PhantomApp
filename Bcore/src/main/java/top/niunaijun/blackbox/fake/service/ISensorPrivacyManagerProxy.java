package top.niunaijun.blackbox.fake.service;

import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;


public class ISensorPrivacyManagerProxy extends BinderInvocationStub {
    public static final String TAG = "SensorPrivacyProxy";

    public ISensorPrivacyManagerProxy() {
        super(BRServiceManager.get().getService("sensor_privacy"));
    }

    @Override
    protected Object getWho() {
        try {
            
            Object stub = null;
            
            
            try {
                stub = Reflector.on("android.hardware.ISensorPrivacyManager$Stub")
                        .call("asInterface", BRServiceManager.get().getService("sensor_privacy"));
            } catch (Exception e1) {
                Slog.d(TAG, "Failed Android 16+ path, trying alternative: " + e1.getMessage());
                
                
                try {
                    stub = Reflector.on("android.hardware.ISensorPrivacyManager")
                            .call("asInterface", BRServiceManager.get().getService("sensor_privacy"));
                } catch (Exception e2) {
                    Slog.d(TAG, "Failed alternative path: " + e2.getMessage());
                    
                    
                    try {
                        Class<?> stubClass = Class.forName("android.hardware.ISensorPrivacyManager$Stub");
                        Method asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder.class);
                        stub = asInterfaceMethod.invoke(null, BRServiceManager.get().getService("sensor_privacy"));
                    } catch (Exception e3) {
                        Slog.e(TAG, "All reflection paths failed for ISensorPrivacyManager", e3);
                        return null;
                    }
                }
            }
            
            if (stub != null) {
                Slog.d(TAG, "Successfully obtained ISensorPrivacyManager interface");
                return (IInterface) stub;
            } else {
                Slog.e(TAG, "Reflection succeeded but returned null interface");
                return null;
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get ISensorPrivacyManager interface", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("sensor_privacy");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            if (isSecurityException(t)) {
                Log.w(TAG, "ISensorPrivacyManager call '" + method.getName()
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


    @ProxyMethod("isSensorPrivacyEnabled")
    public static class IsSensorPrivacyEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: isSensorPrivacyEnabled returning false");
            return false;
        }
    }

    
    @ProxyMethod("isSensorPrivacyEnabledForUser")
    public static class IsSensorPrivacyEnabledForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: isSensorPrivacyEnabledForUser returning false");
            return false;
        }
    }

    
    @ProxyMethod("isSensorPrivacyEnabledForProfile")
    public static class IsSensorPrivacyEnabledForProfile extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: isSensorPrivacyEnabledForProfile returning false");
            return false;
        }
    }

    
    @ProxyMethod("setSensorPrivacy")
    public static class SetSensorPrivacy extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: setSensorPrivacy allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setSensorPrivacyForProfile")
    public static class SetSensorPrivacyForProfile extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: setSensorPrivacyForProfile allowing");
            return method.invoke(who, args);
        }
    }
}


