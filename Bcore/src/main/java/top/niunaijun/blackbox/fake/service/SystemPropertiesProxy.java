package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.FingerprintManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Intercepte android.os.SystemProperties.get() pour spoofer les propriétés
 * système à la source — avant même que Build.* soit initialisé.
 *
 * Note : getWho() retourne null (comme AndroidIdProxy) car ClassInvocationStub
 * hook les méthodes statiques via le proxy dynamique, pas via un objet instance.
 *
 * Un seul @ProxyMethod("get") gère les deux surcharges :
 *   - get(String key)
 *   - get(String key, String def)
 * en inspectant args.length.
 */
public class SystemPropertiesProxy extends ClassInvocationStub {
    public static final String TAG = "SystemPropertiesProxy";

    @Override
    protected Object getWho() {
        return null; // Comme AndroidIdProxy — hook statique
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {}

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ─── Mapping propriétés système → valeurs spoofées ───────────────────────

    /**
     * Retourne la valeur spoofée pour une propriété système donnée.
     * Null si non concernée → l'appel passe au système réel.
     *
     * Couvre toutes les variantes (vendor, system, odm) car Android 12+
     * lit souvent les variantes avant la propriété principale.
     */
    private static String getSpoofedValue(String key) {
        if (key == null) return null;
        try {
            FingerprintManager fp = FingerprintManager.get();
            if (fp == null) return null;
            int userId = BActivityThread.getUserId();

            switch (key) {
                // MODEL
                case "ro.product.model":
                case "ro.product.odm.model":
                case "ro.product.system.model":
                case "ro.product.vendor.model":
                case "ro.product.system_ext.model":
                    return fp.getModel(userId);

                // BRAND
                case "ro.product.brand":
                case "ro.product.odm.brand":
                case "ro.product.system.brand":
                case "ro.product.vendor.brand":
                case "ro.product.system_ext.brand":
                    return fp.getBrand(userId);

                // MANUFACTURER
                case "ro.product.manufacturer":
                case "ro.product.odm.manufacturer":
                case "ro.product.system.manufacturer":
                case "ro.product.vendor.manufacturer":
                case "ro.product.system_ext.manufacturer":
                    return fp.getManufacturer(userId);

                // DEVICE
                case "ro.product.device":
                case "ro.product.odm.device":
                case "ro.product.system.device":
                case "ro.product.vendor.device":
                case "ro.product.system_ext.device":
                    return fp.getDevice(userId);

                // PRODUCT (name)
                case "ro.product.name":
                case "ro.product.odm.name":
                case "ro.product.system.name":
                case "ro.product.vendor.name":
                case "ro.product.system_ext.name":
                    return fp.getProduct(userId);

                // SERIAL
                case "ro.serialno":
                case "ro.serial":
                case "ro.boot.serialno":
                    return fp.getSerial(userId);

                // FINGERPRINT
                case "ro.build.fingerprint":
                case "ro.vendor.build.fingerprint":
                case "ro.system.build.fingerprint":
                case "ro.odm.build.fingerprint":
                case "ro.system_ext.build.fingerprint":
                    return fp.getBuildFingerprint(userId);

                // BUILD ID
                case "ro.build.id":
                case "ro.vendor.build.id":
                case "ro.system.build.id":
                    try {
                        String[] p = fp.getBuildFingerprint(userId).split("/");
                        return p.length >= 4 ? p[3] : null;
                    } catch (Exception e) { return null; }

                // VERSION RELEASE
                case "ro.build.version.release":
                case "ro.system.build.version.release":
                case "ro.vendor.build.version.release":
                    try {
                        String fp2 = fp.getBuildFingerprint(userId);
                        if (fp2.contains(":13/")) return "13";
                        if (fp2.contains(":14/")) return "14";
                        if (fp2.contains(":12/")) return "12";
                    } catch (Exception ignored) {}
                    return null;

                default:
                    return null;
            }
        } catch (Exception e) {
            Slog.w(TAG, "getSpoofedValue error key=" + key + ": " + e.getMessage());
            return null;
        }
    }

    // ─── Hook unique — gère get(String) ET get(String, String) ───────────────

    /**
     * Un seul hook pour les deux surcharges de SystemProperties.get().
     * args[0] = key (toujours présent)
     * args[1] = defaultValue (optionnel)
     */
    @ProxyMethod("get")
    public static class Get extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args == null || args.length < 1 || !(args[0] instanceof String)) {
                    return method.invoke(who, args);
                }

                String key = (String) args[0];
                String spoofed = getSpoofedValue(key);

                if (spoofed != null) {
                    Slog.d(TAG, "Spoofed SystemProps.get(" + key + ") → " + spoofed);
                    return spoofed;
                }

                return method.invoke(who, args);
            } catch (Exception e) {
                // Fallback sur la valeur par défaut si fournie
                if (args != null && args.length >= 2) return args[1];
                return "";
            }
        }
    }

    // getInt et getLong pour les props numériques si besoin futur
    @ProxyMethod("getInt")
    public static class GetInt extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Pas de spoofing numérique pour l'instant — passe au système
            try { return method.invoke(who, args); }
            catch (Exception e) { return args != null && args.length >= 2 ? args[1] : 0; }
        }
    }
}
