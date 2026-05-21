package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRIShortcutServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class IShortcutManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IShortcutManagerProxy";

    public IShortcutManagerProxy() {
        super(BRServiceManager.get().getService(Context.SHORTCUT_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIShortcutServiceStub.get().asInterface(BRServiceManager.get().getService(Context.SHORTCUT_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.SHORTCUT_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getShortcuts"));
        addMethodHook(new PkgMethodProxy("disableShortcuts"));
        addMethodHook(new PkgMethodProxy("enableShortcuts"));
        addMethodHook(new PkgMethodProxy("getRemainingCallCount"));
        addMethodHook(new PkgMethodProxy("getRateLimitResetTime"));
        addMethodHook(new PkgMethodProxy("getIconMaxDimensions"));
        addMethodHook(new PkgMethodProxy("getMaxShortcutCountPerActivity"));
        addMethodHook(new PkgMethodProxy("reportShortcutUsed"));
        addMethodHook(new PkgMethodProxy("onApplicationActive"));
        addMethodHook(new PkgMethodProxy("hasShortcutHostPermission"));
        addMethodHook(new PkgMethodProxy("removeAllDynamicShortcuts"));
        addMethodHook(new PkgMethodProxy("removeDynamicShortcuts"));
        addMethodHook(new PkgMethodProxy("removeLongLivedShortcuts"));
        addMethodHook(new PkgMethodProxy("getManifestShortcuts"){
            @Override
            protected Object hook(Object who, Method method, Object[] args) throws Throwable {
                return ParceledListSliceCompat.create(new ArrayList<ShortcutInfo>());
            }
        });
    }

    @ProxyMethod("requestPinShortcut")
    public static class RequestPinShortcut extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("setDynamicShortcuts")
    public static class SetDynamicShortcuts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("addDynamicShortcuts")
    public static class AddDynamicShortcuts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("createShortcutResultIntent")
    public static class CreateShortcutResultIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new Intent();
        }
    }

    @ProxyMethod("pushDynamicShortcut")
    public static class pushDynamicShortcut extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable t) {
            if (isSecurityException(t)) {
                Log.w(TAG, "IShortcutManager call '" + method.getName()
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
}
