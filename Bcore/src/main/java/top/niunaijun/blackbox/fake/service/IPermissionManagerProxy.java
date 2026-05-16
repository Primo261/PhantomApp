package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.RuntimePermissionWhitelist;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ─── Auto-grant runtime permissions for virtualised apps ─────────────────
    //
    // Android 11+ (R+, isPermissionMgr=true) routes Context.checkSelfPermission()
    // through the `permissionmgr` binder service rather than PackageManager.
    // Without this hook, the sandboxed app calls IPermissionManager.checkPermission
    // and sees PERMISSION_DENIED — even though our IPackageManagerProxy and
    // IActivityManagerProxy hooks return GRANTED for the same permissions. That
    // mismatch is what caused Vinted to re-prompt 5 s after the host had
    // already auto-accepted the request from ActivityManagerCommonProxy.
    //
    // We grant the same permission set as IPackageManagerProxy: storage / media
    // (incl. READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO), camera,
    // location (fine + coarse + background), and audio capture. Everything else
    // falls through to the real check.

    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || args.length < 1 || !(args[0] instanceof String)) {
                return method.invoke(who, args);
            }
            String permission = (String) args[0];
            if (RuntimePermissionWhitelist.isAutoGranted(permission)) {
                if (DEBUG_PERM) Slog.d(TAG_PERM, "IPermissionManager auto-grant: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            return method.invoke(who, args);
        }
    }

    private static final String TAG_PERM = "PHANTOM_PERM";
    private static final boolean DEBUG_PERM = false;
}
