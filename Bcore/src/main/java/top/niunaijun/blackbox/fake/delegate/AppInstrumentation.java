package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static AppInstrumentation sAppInstrumentation;

    // Cache Unsafe
    private static Object sUnsafe = null;
    private static boolean sUnsafeInitDone = false;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {}

    // ─── Unsafe — 3 stratégies d'accès ───────────────────────────────────────

    /**
     * Stratégie 1 : accès via theUnsafe field (Android 8-14, parfois 15-16)
     */
    private static Object tryGetUnsafeViaField() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object u = f.get(null);
            if (u != null) {
                Log.d(TAG, "Unsafe via theUnsafe field ✅");
                return u;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unsafe via theUnsafe failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Stratégie 2 : allocateInstance (bypass total — fonctionne même quand
     * theUnsafe est sur la blocklist car allocateInstance est une méthode
     * native publique de Unsafe accessible via réflexion normale)
     */
    private static Object tryGetUnsafeViaAllocateInstance() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            // allocateInstance crée une instance sans constructeur
            // ce qui bypass les hidden API checks sur l'instance
            Method allocate = Class.class.getDeclaredMethod("newInstance");
            // Alternative : via Constructor.newInstance sans vérifications
            java.lang.reflect.Constructor<?> ctor = unsafeClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object u = ctor.newInstance();
            Log.d(TAG, "Unsafe via allocateInstance ✅");
            return u;
        } catch (Exception e) {
            Log.w(TAG, "Unsafe via allocateInstance failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Stratégie 3 : réflexion sur getUnsafe() avec bypass via
     * setTargetSdkVersion(0) qui désactive les hidden API checks
     * pour le thread courant sur certaines versions Android.
     */
    private static Object tryGetUnsafeViaSdkBypass() {
        try {
            // Tente de désactiver temporairement les restrictions hidden API
            Class<?> vmClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);

            // setTargetSdkVersion(0) désactive les hidden API restrictions
            Method setVersion = vmClass.getDeclaredMethod("setTargetSdkVersion", int.class);
            setVersion.setAccessible(true);
            setVersion.invoke(runtime, 0); // 0 = désactive les checks

            // Maintenant on peut accéder à theUnsafe
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object u = f.get(null);

            // Restaure la version réelle
            setVersion.invoke(runtime, Build.VERSION.SDK_INT);

            if (u != null) {
                Log.d(TAG, "Unsafe via SDK bypass ✅");
                return u;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unsafe via SDK bypass failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Obtient sun.misc.Unsafe via les 3 stratégies en cascade.
     * Au moins une fonctionne sur Android 8-16.
     */
    private static synchronized Object getUnsafe() {
        if (sUnsafeInitDone) return sUnsafe;
        sUnsafeInitDone = true;

        sUnsafe = tryGetUnsafeViaField();
        if (sUnsafe != null) return sUnsafe;

        sUnsafe = tryGetUnsafeViaSdkBypass();
        if (sUnsafe != null) return sUnsafe;

        sUnsafe = tryGetUnsafeViaAllocateInstance();
        return sUnsafe;
    }

    // ─── Injection Build.* ────────────────────────────────────────────────────

    /**
     * Écrit un champ static final de Build via Unsafe.putObject().
     * Bypass total ART — fonctionne Android 12-16.
     */
    private static boolean setBuildFieldUnsafe(String fieldName, String value) {
        try {
            Object unsafe = getUnsafe();
            if (unsafe == null) return false;

            Class<?> unsafeClass = unsafe.getClass();
            Field buildField = Build.class.getDeclaredField(fieldName);

            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method staticFieldBase   = unsafeClass.getMethod("staticFieldBase",   Field.class);
            Method putObject         = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);

            long offset = (long) staticFieldOffset.invoke(unsafe, buildField);
            Object base = staticFieldBase.invoke(unsafe, buildField);
            putObject.invoke(unsafe, base, offset, value);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setBuildFieldUnsafe(" + fieldName + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fallback réflexion classique (Android 8-11).
     */
    private static boolean setBuildFieldReflection(String fieldName, String value) {
        try {
            Field field = Build.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            try {
                Field modifiers = Field.class.getDeclaredField("accessFlags");
                modifiers.setAccessible(true);
                modifiers.setInt(field, modifiers.getInt(field) & ~0x10);
            } catch (Exception ignored) {}
            field.set(null, value);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setBuildFieldReflection(" + fieldName + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Unsafe en priorité, réflexion en fallback.
     */
    private static void setBuildField(String fieldName, String value) {
        if (!setBuildFieldUnsafe(fieldName, value)) {
            setBuildFieldReflection(fieldName, value);
        }
    }

    /**
     * Même méthode pour Build.VERSION (classe interne).
     */
    private static void setVersionField(String fieldName, String value) {
        try {
            Object unsafe = getUnsafe();
            Field field = Build.VERSION.class.getDeclaredField(fieldName);
            if (unsafe != null) {
                Class<?> unsafeClass = unsafe.getClass();
                Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
                Method staticFieldBase   = unsafeClass.getMethod("staticFieldBase",   Field.class);
                Method putObject         = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
                long offset = (long) staticFieldOffset.invoke(unsafe, field);
                Object base = staticFieldBase.invoke(unsafe, field);
                putObject.invoke(unsafe, base, offset, value);
            } else {
                field.setAccessible(true);
                try {
                    Field mod = Field.class.getDeclaredField("accessFlags");
                    mod.setAccessible(true);
                    mod.setInt(field, mod.getInt(field) & ~0x10);
                } catch (Exception ignored) {}
                field.set(null, value);
            }
        } catch (Exception e) {
            Log.w(TAG, "setVersionField(" + fieldName + ") failed: " + e.getMessage());
        }
    }

    /**
     * Injecte tous les champs Build.* du profil du slot.
     */
    private void injectBuildFields() {
        try {
            FingerprintManager fp = FingerprintManager.get();
            if (fp == null) { Log.w(TAG, "injectBuildFields: fp null"); return; }

            int userId = BActivityThread.getUserId();

            String fakeBrand        = fp.getBrand(userId);
            String fakeManufacturer = fp.getManufacturer(userId);
            String fakeModel        = fp.getModel(userId);
            String fakeDevice       = fp.getDevice(userId);
            String fakeProduct      = fp.getProduct(userId);
            String fakeSerial       = fp.getSerial(userId);
            String fakeFp           = fp.getBuildFingerprint(userId);

            // Extrait Build.ID et VERSION.RELEASE du fingerprint
            // Format: brand/product/device:version/buildId/release-keys
            String fakeBuildId      = "PHANTOM_" + userId;
            String fakeRelease      = "14";
            try {
                String[] parts = fakeFp.split("/");
                if (parts.length >= 4) fakeBuildId = parts[3];
                if (fakeFp.contains(":13/")) fakeRelease = "13";
                else if (fakeFp.contains(":14/")) fakeRelease = "14";
                else if (fakeFp.contains(":12/")) fakeRelease = "12";
            } catch (Exception ignored) {}

            // Injection
            setBuildField("BRAND",        fakeBrand);
            setBuildField("MANUFACTURER", fakeManufacturer);
            setBuildField("MODEL",        fakeModel);
            setBuildField("DEVICE",       fakeDevice);
            setBuildField("PRODUCT",      fakeProduct);
            setBuildField("HARDWARE",     fakeDevice);
            setBuildField("SERIAL",       fakeSerial);
            setBuildField("FINGERPRINT",  fakeFp);
            setBuildField("ID",           fakeBuildId);
            setVersionField("RELEASE",    fakeRelease);

            // Vérification
            if (fakeModel.equals(Build.MODEL)) {
                Log.d(TAG, "✅ Build injected — slot=" + userId
                        + " MODEL=" + fakeModel + " BRAND=" + fakeBrand);
            } else {
                Log.w(TAG, "⚠️ Build.MODEL still=" + Build.MODEL + " (expected " + fakeModel + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "injectBuildFields failed: " + e.getMessage());
        }
    }

    // ─── Hooks Instrumentation ────────────────────────────────────────────────

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation)) return;
            mBaseInstrumentation = mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) return true;
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) return false;
        do {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if (obj instanceof AppInstrumentation) return true;
                    } catch (Exception e) { return false; }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) activity.getTheme().applyStyle(info.theme, true);
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);
        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle ps) {
        checkActivity(activity);
        injectBuildFields();
        super.callActivityOnCreate(activity, icicle, ps);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        injectBuildFields();
        super.callActivityOnCreate(activity, icicle);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        injectBuildFields(); // Premier point — le plus important
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
