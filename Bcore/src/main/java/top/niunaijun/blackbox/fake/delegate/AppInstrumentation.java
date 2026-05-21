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

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.NativeCore;
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

    // HiddenApiBypass — applique l'exemption "L" une seule fois par process.
    // Cette exemption débloque Unsafe.staticFieldOffset / staticFieldBase /
    // putObject qui sont blocklistés sur API 33+ (et inaccessibles via
    // setHiddenApiExemptions natif sur API 36).
    private static boolean sHiddenApiBypassTried = false;
    private static boolean sHiddenApiBypassOk = false;

    private static synchronized boolean ensureHiddenApiBypass() {
        if (sHiddenApiBypassTried) return sHiddenApiBypassOk;
        sHiddenApiBypassTried = true;
        try {
            sHiddenApiBypassOk = HiddenApiBypass.addHiddenApiExemptions("L");
            if (sHiddenApiBypassOk) {
                Log.i(TAG, "HiddenApiBypass.addHiddenApiExemptions(L) ✅");
            } else {
                Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions(L) returned false");
            }
        } catch (Throwable t) {
            sHiddenApiBypassOk = false;
            Log.w(TAG, "HiddenApiBypass.addHiddenApiExemptions failed: " + t);
        }
        return sHiddenApiBypassOk;
    }

    private static String formatFieldValue(Object value) {
        if (value instanceof Object[]) return java.util.Arrays.toString((Object[]) value);
        return String.valueOf(value);
    }

    // Probe une seule fois si sun.misc.Unsafe.staticFieldOffset est accessible.
    // Sur API 33+, la méthode est blocklistée/absente — la cascade Unsafe est
    // alors du code mort (le spoofing Build.* passe par le hook natif
    // __system_property_get + l'injection initiale dans le slot process).
    private static Boolean sUnsafeOffsetAvailable = null;

    private static synchronized boolean isUnsafeOffsetAvailable() {
        if (sUnsafeOffsetAvailable != null) return sUnsafeOffsetAvailable;
        try {
            Class.forName("sun.misc.Unsafe")
                 .getMethod("staticFieldOffset", Field.class);
            sUnsafeOffsetAvailable = true;
            Log.d(TAG, "Unsafe.staticFieldOffset available — cascade enabled");
        } catch (Throwable t) {
            sUnsafeOffsetAvailable = false;
            Log.d(TAG, "Unsafe.staticFieldOffset unavailable (API 33+) — " +
                       "skipping Unsafe cascade, native SystemPropertiesHook handles spoofing");
        }
        return sUnsafeOffsetAvailable;
    }

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
     * Path principal API 33+/36 — n'utilise PAS Unsafe.staticFieldOffset
     * (absente sur API 36) : récupère l'offset du static field via
     * Field.getOffset() (hidden API, débloquée par HiddenApiBypass.
     * addHiddenApiExemptions("L")), puis écrit dans le payload du Class
     * object via Unsafe.putObjectVolatile (présente API 36).
     *
     * En ART, les static fields sont stockés dans le payload de l'objet
     * java.lang.Class, donc putObjectVolatile(ownerClass, fieldOffset, value)
     * est équivalent à l'ancien Unsafe.putObject(staticFieldBase, staticFieldOffset, value).
     *
     * putObjectVolatile (vs putObject) garantit une memory barrier : tous les
     * threads du slot (UI, IO, WebView, ads SDKs) verront la même valeur
     * spoofée — pas d'incohérence intra-process qui flag chez les anti-fraud.
     */
    private static boolean setFieldViaHiddenApiBypass(Class<?> ownerClass,
                                                      String fieldName,
                                                      Object value) {
        if (!ensureHiddenApiBypass()) return false;
        try {
            // 1. Get Field — getStaticFields bypasse la blocklist hidden API
            //    sur les champs internes de Build, getDeclaredField suffit
            //    quand l'exemption "L" a déjà été appliquée.
            Field target = null;
            try {
                for (Field f : HiddenApiBypass.getStaticFields(ownerClass)) {
                    if (f.getName().equals(fieldName)) { target = f; break; }
                }
            } catch (Throwable ignored) { /* fallback ci-dessous */ }
            if (target == null) {
                target = ownerClass.getDeclaredField(fieldName);
            }

            // 2. Offset du field via Field.getOffset() (hidden API)
            Method getOffset = Field.class.getDeclaredMethod("getOffset");
            getOffset.setAccessible(true);
            int offset = (int) getOffset.invoke(target);

            // 3. Get Unsafe.theUnsafe (toujours dispo API 36)
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            if (unsafe == null) return false;

            // 4. putObjectVolatile(Class, offset, value) — write avec memory
            //    barrier pour cohérence multi-thread du slot.
            Method putObjectVolatile = unsafeClass.getMethod(
                    "putObjectVolatile", Object.class, long.class, Object.class);
            putObjectVolatile.invoke(unsafe, ownerClass, (long) offset, value);

            Log.i(TAG, "Build field injected via HiddenApiBypass: "
                    + fieldName + " = " + formatFieldValue(value));
            return true;
        } catch (Throwable t) {
            Log.v(TAG, "setFieldViaHiddenApiBypass(" + fieldName + ") failed: " + t);
            return false;
        }
    }

    /**
     * Fallback 1 — Unsafe sans HiddenApiBypass (Android 8-12).
     * Demoted to Log.v on failure since this is part of the cascade.
     */
    private static boolean setBuildFieldUnsafe(String fieldName, Object value) {
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
            Log.v(TAG, "setBuildFieldUnsafe(" + fieldName + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fallback 2 — réflexion classique (Android 8-11).
     */
    private static boolean setBuildFieldReflection(String fieldName, Object value) {
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
            Log.v(TAG, "setBuildFieldReflection(" + fieldName + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Cascade : HiddenApiBypass (Field.getOffset + putObjectVolatile) →
     * Unsafe legacy (staticFieldOffset, Android <14) → réflexion classique.
     * Accepte String ou String[]. Émet un Log.w UNIQUEMENT si tous les paths
     * ont échoué.
     *
     * Le path principal ne dépend pas de Unsafe.staticFieldOffset donc
     * fonctionne sur API 33+. Les fallbacks legacy nécessitent l'ancienne API
     * Unsafe et sont gated sur isUnsafeOffsetAvailable().
     */
    private static void setBuildField(String fieldName, Object value) {
        if (setFieldViaHiddenApiBypass(Build.class, fieldName, value)) return;
        if (isUnsafeOffsetAvailable()) {
            if (setBuildFieldUnsafe(fieldName, value)) return;
            if (setBuildFieldReflection(fieldName, value)) return;
        }
        Log.w(TAG, "setBuildField(" + fieldName + "): all strategies failed");
    }

    /**
     * Même cascade pour Build.VERSION (classe interne). Le path principal
     * (Field.getOffset + putObjectVolatile) fonctionne API 33+ ; le fallback
     * Unsafe legacy est gated sur isUnsafeOffsetAvailable().
     */
    private static void setVersionField(String fieldName, String value) {
        if (setFieldViaHiddenApiBypass(Build.VERSION.class, fieldName, value)) return;
        if (!isUnsafeOffsetAvailable()) {
            Log.w(TAG, "setVersionField(" + fieldName + "): no path available");
            return;
        }
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
                return;
            }
            field.setAccessible(true);
            try {
                Field mod = Field.class.getDeclaredField("accessFlags");
                mod.setAccessible(true);
                mod.setInt(field, mod.getInt(field) & ~0x10);
            } catch (Exception ignored) {}
            field.set(null, value);
        } catch (Exception e) {
            Log.w(TAG, "setVersionField(" + fieldName + "): all strategies failed: " + e.getMessage());
        }
    }

    // Variantes de partition lues par certains SDK avant la propriété principale
    // (Android 12+ split system/vendor/odm/system_ext). Garde la symétrie avec
    // SystemPropertiesProxy.getSpoofedValue côté Java.
    private static final String[] PROP_VARIANTS = {
            "", ".odm", ".system", ".vendor", ".system_ext"
    };

    /**
     * Push une propriété + toutes ses variantes vendor/odm/system/system_ext
     * vers la table native (consommée par SystemPropertiesHook côté C).
     * baseKey doit être de la forme "ro.product.model" ; la variante est
     * insérée entre "ro.product" et ".model".
     */
    private static void pushPropAndVariants(String baseKey, String value) {
        if (value == null) return;
        try {
            NativeCore.setSpoofedProperty(baseKey, value);
            int dot = baseKey.indexOf('.', 3); // après "ro."
            int secondDot = (dot >= 0) ? baseKey.indexOf('.', dot + 1) : -1;
            if (secondDot < 0) return;
            String prefix = baseKey.substring(0, secondDot);   // "ro.product"
            String tail   = baseKey.substring(secondDot);      // ".model"
            for (String variant : PROP_VARIANTS) {
                if (variant.isEmpty()) continue;
                NativeCore.setSpoofedProperty(prefix + variant + tail, value);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pushPropAndVariants(" + baseKey + ") failed: " + t.getMessage());
        }
    }

    private static void pushPropSimple(String key, String value) {
        if (value == null) return;
        try {
            NativeCore.setSpoofedProperty(key, value);
        } catch (Throwable t) {
            Log.w(TAG, "pushPropSimple(" + key + ") failed: " + t.getMessage());
        }
    }

    /**
     * Injecte tous les champs Build.* du profil du slot et les push vers le
     * hook natif __system_property_get.
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
            String fakeBoard        = fp.getBoard(userId);
            String fakeHardware     = fp.getHardware(userId);
            String fakeBootloader   = fp.getBootloader(userId);
            String fakeSerial       = fp.getSerial(userId);
            String fakeFp           = fp.getBuildFingerprint(userId);
            String fakeDisplay      = fp.getDisplay(userId);
            String fakeHost         = fp.getHost(userId);
            String fakeUser         = fp.getUser(userId);
            String fakeTags         = fp.getTags(userId);
            String fakeType         = fp.getType(userId);
            String fakeRadio        = fp.getRadio(userId);
            String fakeIncremental  = fp.getIncremental(userId);
            String fakeSecPatch     = fp.getSecurityPatch(userId);
            String[] fakeAbis       = fp.getSupportedAbis(userId);

            // Extrait Build.ID et VERSION.RELEASE du fingerprint
            // Format: brand/product/device:version/buildId/incremental:user/tags
            String fakeBuildId  = "PHANTOM_" + userId;
            String fakeRelease  = "14";
            try {
                String[] parts = fakeFp.split("/");
                if (parts.length >= 4) fakeBuildId = parts[3];
                if (fakeFp.contains(":12/")) fakeRelease = "12";
                else if (fakeFp.contains(":13/")) fakeRelease = "13";
                else if (fakeFp.contains(":14/")) fakeRelease = "14";
                else if (fakeFp.contains(":15/")) fakeRelease = "15";
                else if (fakeFp.contains(":16/")) fakeRelease = "16";
            } catch (Exception ignored) {}

            // ─── Injection Java Build.* ────────────────────────────────────
            setBuildField("BRAND",         fakeBrand);
            setBuildField("MANUFACTURER",  fakeManufacturer);
            setBuildField("MODEL",         fakeModel);
            setBuildField("DEVICE",        fakeDevice);
            setBuildField("PRODUCT",       fakeProduct);
            setBuildField("BOARD",         fakeBoard);
            setBuildField("HARDWARE",      fakeHardware);
            setBuildField("BOOTLOADER",    fakeBootloader);
            setBuildField("SERIAL",        fakeSerial);
            setBuildField("FINGERPRINT",   fakeFp);
            setBuildField("ID",            fakeBuildId);
            setBuildField("DISPLAY",       fakeDisplay);
            setBuildField("HOST",          fakeHost);
            setBuildField("USER",          fakeUser);
            setBuildField("TAGS",          fakeTags);
            setBuildField("TYPE",          fakeType);
            setBuildField("RADIO",         fakeRadio);
            setBuildField("SUPPORTED_ABIS",        fakeAbis);
            setBuildField("SUPPORTED_32_BIT_ABIS", new String[]{"armeabi-v7a", "armeabi"});
            setBuildField("SUPPORTED_64_BIT_ABIS", new String[]{"arm64-v8a"});
            setBuildField("CPU_ABI",       "arm64-v8a");
            setBuildField("CPU_ABI2",      "");
            setVersionField("RELEASE",         fakeRelease);
            setVersionField("INCREMENTAL",     fakeIncremental);
            setVersionField("SECURITY_PATCH",  fakeSecPatch);

            // ─── Push vers la table native pour __system_property_get ─────
            // Couvre les variantes vendor/odm/system_ext pour les props critiques.
            pushPropAndVariants("ro.product.brand",        fakeBrand);
            pushPropAndVariants("ro.product.manufacturer", fakeManufacturer);
            pushPropAndVariants("ro.product.model",        fakeModel);
            pushPropAndVariants("ro.product.device",       fakeDevice);
            pushPropAndVariants("ro.product.name",         fakeProduct);
            pushPropAndVariants("ro.product.board",        fakeBoard);
            pushPropSimple("ro.product.cpu.abi",      "arm64-v8a");
            pushPropSimple("ro.product.cpu.abi2",     "");
            pushPropSimple("ro.product.cpu.abilist",   "arm64-v8a,armeabi-v7a,armeabi");
            pushPropSimple("ro.product.cpu.abilist32", "armeabi-v7a,armeabi");
            pushPropSimple("ro.product.cpu.abilist64", "arm64-v8a");
            pushPropSimple("ro.hardware",          fakeHardware);
            pushPropSimple("ro.board.platform",    fakeBoard);
            pushPropSimple("ro.bootloader",        fakeBootloader);
            pushPropSimple("ro.boot.bootloader",   fakeBootloader);
            pushPropSimple("ro.serialno",          fakeSerial);
            pushPropSimple("ro.serial",            fakeSerial);
            pushPropSimple("ro.boot.serialno",     fakeSerial);
            pushPropSimple("ro.build.fingerprint",         fakeFp);
            pushPropSimple("ro.vendor.build.fingerprint",  fakeFp);
            pushPropSimple("ro.system.build.fingerprint",  fakeFp);
            pushPropSimple("ro.odm.build.fingerprint",     fakeFp);
            pushPropSimple("ro.system_ext.build.fingerprint", fakeFp);
            pushPropSimple("ro.build.id",          fakeBuildId);
            pushPropSimple("ro.vendor.build.id",   fakeBuildId);
            pushPropSimple("ro.system.build.id",   fakeBuildId);
            pushPropSimple("ro.build.display.id",  fakeDisplay);
            pushPropSimple("ro.build.host",        fakeHost);
            pushPropSimple("ro.build.user",        fakeUser);
            pushPropSimple("ro.build.tags",        fakeTags);
            pushPropSimple("ro.build.type",        fakeType);
            pushPropSimple("ro.build.version.release",          fakeRelease);
            pushPropSimple("ro.system.build.version.release",   fakeRelease);
            pushPropSimple("ro.vendor.build.version.release",   fakeRelease);
            pushPropSimple("ro.build.version.incremental",      fakeIncremental);
            pushPropSimple("ro.build.version.security_patch",   fakeSecPatch);
            pushPropSimple("ro.vendor.build.security_patch",    fakeSecPatch);

            // ─── Anti-fraud props supplémentaires ──────────────────────
            // Properties lues par les SDK anti-fraude (Metadome, Castle,
            // Sift) pour détecter émulateurs, debug build, bootloader
            // unlocked, et incohérences hardware/GPU. Valeurs cohérentes
            // avec un device samsung/mediatek release stock.
            pushPropSimple("ro.kernel.qemu",             "0");
            pushPropSimple("ro.boot.qemu",               "0");
            pushPropSimple("ro.boot.hardware",            fakeHardware);
            pushPropSimple("ro.hardware.egl",            "mali");
            pushPropSimple("ro.debuggable",              "0");
            pushPropSimple("ro.secure",                  "1");
            pushPropSimple("ro.build.flavor",             fakeProduct + "-" + fakeType);
            pushPropSimple("ro.boot.flash.locked",       "1");
            pushPropSimple("ro.boot.verifiedbootstate", "green");
            pushPropSimple("ro.boot.veritymode",         "enforcing");

            // Vérification
            if (fakeModel.equals(Build.MODEL)) {
                Log.d(TAG, "✅ Build injected — slot=" + userId
                        + " MODEL=" + fakeModel + " BRAND=" + fakeBrand
                        + " BOARD=" + fakeBoard + " HW=" + fakeHardware
                        + " ABIS=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS)
                        + " PATCH=" + Build.VERSION.SECURITY_PATCH);
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
