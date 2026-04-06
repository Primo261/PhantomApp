package top.niunaijun.blackbox.fake.frameworks;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Random;

import top.niunaijun.blackbox.BlackBoxCore;

public class FingerprintManager {

    private static final String TAG = "FingerprintManager";
    private static final String PREF_NAME = "phantom_fingerprint";
    private static volatile FingerprintManager sInstance;
    private final Context mContext;

    // Clés de stockage
    private static final String KEY_IMEI       = "imei_";
    private static final String KEY_MEID       = "meid_";
    private static final String KEY_IMSI       = "imsi_";
    private static final String KEY_ICC        = "icc_";
    private static final String KEY_ANDROID_ID = "android_id_";
    private static final String KEY_WIFI_MAC   = "wifi_mac_";
    private static final String KEY_BT_MAC     = "bt_mac_";
    private static final String KEY_SERIAL     = "serial_";
    private static final String KEY_BUILD_FP   = "build_fp_";

    private FingerprintManager(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Auto-initialise si nécessaire — fonctionne dans TOUS les processus
     * (host process ET slot process)
     */
    public static FingerprintManager get() {
        if (sInstance == null) {
            synchronized (FingerprintManager.class) {
                if (sInstance == null) {
                    try {
                        Context ctx = BlackBoxCore.getContext();
                        if (ctx != null) {
                            sInstance = new FingerprintManager(ctx);
                            Log.d(TAG, "Auto-initialized in process: "
                                + android.os.Process.myPid());
                        } else {
                            Log.w(TAG, "BlackBoxCore.getContext() returned null");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Auto-init failed: " + e.getMessage());
                    }
                }
            }
        }
        return sInstance;
    }

    /**
     * Init explicite depuis BlackBoxCore.doAttachBaseContext
     * Garde pour compatibilité — get() s'auto-initialise aussi
     */
    public static void init(Context context) {
        if (sInstance == null) {
            synchronized (FingerprintManager.class) {
                if (sInstance == null) {
                    sInstance = new FingerprintManager(context);
                    Log.d(TAG, "Initialized explicitly");
                }
            }
        }
    }

    // ─── Getters publics ─────────────────────────────────────────────────────

    public String getImei(int userId) {
        return getOrCreate(KEY_IMEI + userId, () -> generateImei());
    }

    public String getMeid(int userId) {
        return getOrCreate(KEY_MEID + userId, () -> generateMeid());
    }

    public String getImsi(int userId) {
        return getOrCreate(KEY_IMSI + userId, () -> generateImsi());
    }

    public String getIccSerial(int userId) {
        return getOrCreate(KEY_ICC + userId, () -> generateIcc());
    }

    public String getAndroidId(int userId) {
        return getOrCreate(KEY_ANDROID_ID + userId, () -> generateAndroidId());
    }

    public String getWifiMac(int userId) {
        return getOrCreate(KEY_WIFI_MAC + userId, () -> generateMac());
    }

    public String getBluetoothMac(int userId) {
        return getOrCreate(KEY_BT_MAC + userId, () -> generateMac());
    }

    public String getSerial(int userId) {
        return getOrCreate(KEY_SERIAL + userId, () -> generateSerial());
    }

    public String getBuildFingerprint(int userId) {
        return getOrCreate(KEY_BUILD_FP + userId, () -> generateBuildFingerprint());
    }

    // ─── Reset complet d'un slot ──────────────────────────────────────────────

    public void resetSlot(int userId) {
        SharedPreferences.Editor editor = prefs().edit();
        editor.remove(KEY_IMEI       + userId);
        editor.remove(KEY_MEID       + userId);
        editor.remove(KEY_IMSI       + userId);
        editor.remove(KEY_ICC        + userId);
        editor.remove(KEY_ANDROID_ID + userId);
        editor.remove(KEY_WIFI_MAC   + userId);
        editor.remove(KEY_BT_MAC     + userId);
        editor.remove(KEY_SERIAL     + userId);
        editor.remove(KEY_BUILD_FP   + userId);
        editor.apply();
        Log.d(TAG, "Reset slot " + userId);
    }

    // ─── Générateurs ─────────────────────────────────────────────────────────

    private String generateImei() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        int[] tac = {3,5,8,3,4,9,r.nextInt(10),r.nextInt(10)};
        for (int d : tac) sb.append(d);
        for (int i = 0; i < 6; i++) sb.append(r.nextInt(10));
        sb.append(luhn(sb.toString()));
        return sb.toString();
    }

    private String generateMeid() {
        Random r = new Random();
        String chars = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private String generateImsi() {
        Random r = new Random();
        String[] mncs = {"01","02","03","04","05","06","07","08","09","10"};
        StringBuilder sb = new StringBuilder("20");
        sb.append(r.nextInt(9) + 1);
        sb.append(mncs[r.nextInt(mncs.length)]);
        for (int i = 0; i < 9; i++) sb.append(r.nextInt(10));
        return sb.toString();
    }

    private String generateIcc() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder("89");
        for (int i = 0; i < 17; i++) sb.append(r.nextInt(10));
        sb.append(luhn(sb.toString()));
        return sb.toString();
    }

    private String generateAndroidId() {
        Random r = new Random();
        String hex = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(hex.charAt(r.nextInt(16)));
        return sb.toString();
    }

    private String generateMac() {
        Random r = new Random();
        byte[] mac = new byte[6];
        r.nextBytes(mac);
        // bit multicast à 0, bit local à 1 → MAC administrée localement
        mac[0] = (byte) ((mac[0] & 0xFE) | 0x02);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }

    private String generateSerial() {
        Random r = new Random();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private String generateBuildFingerprint() {
        Random r = new Random();
        String[] brands = {"samsung", "google", "xiaomi", "oppo"};
        String[] versions = {"13", "14"};
        String brand = brands[r.nextInt(brands.length)];
        String ver = versions[r.nextInt(versions.length)];
        return brand + "/generic/" + brand + ":" + ver + "/release-keys";
    }

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    private interface Generator { String generate(); }

    private String getOrCreate(String key, Generator gen) {
        try {
            SharedPreferences sp = prefs();
            String val = sp.getString(key, null);
            if (val == null || val.isEmpty()) {
                val = gen.generate();
                sp.edit().putString(key, val).apply();
                Log.d(TAG, "Generated for key=" + key + " val=" + val);
            }
            return val;
        } catch (Exception e) {
            Log.e(TAG, "getOrCreate error for key=" + key + ": " + e.getMessage());
            return gen.generate();
        }
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private int luhn(String partial) {
        int sum = 0;
        boolean alt = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int n = partial.charAt(i) - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
            alt = !alt;
        }
        return (10 - (sum % 10)) % 10;
    }
}
