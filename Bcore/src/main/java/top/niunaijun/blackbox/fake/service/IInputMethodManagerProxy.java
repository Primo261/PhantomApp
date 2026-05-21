package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;


@ScanClass(IInputMethodManagerProxy.class)
public class IInputMethodManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IInputMethodManagerProxy";

    public IInputMethodManagerProxy() {
        super(BRServiceManager.get().getService(Context.INPUT_METHOD_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRServiceManager.get().getService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.INPUT_METHOD_SERVICE);
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
                Log.w(TAG, "IInputMethodManager call '" + method.getName()
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

    @ProxyMethod("startInputOrWindowGainedFocus")
    public static class StartInputOrWindowGainedFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }
}
