package top.niunaijun.blackbox.fake.frameworks;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import top.niunaijun.blackbox.BlackBoxCore;

public class FingerprintManager {

    private static final String TAG = "FingerprintManager";
    private static final String PREF_NAME = "phantom_fingerprint";
    private static volatile FingerprintManager sInstance;
    private final Context mContext;
    private volatile String mHostAnchor;

    // ─── Clés de stockage ────────────────────────────────────────────────────
    private static final String KEY_HOST_ANCHOR    = "host_anchor";
    private static final String KEY_IMEI           = "imei_";
    private static final String KEY_MEID           = "meid_";
    private static final String KEY_IMSI           = "imsi_";
    private static final String KEY_ICC            = "icc_";
    private static final String KEY_ANDROID_ID     = "android_id_";
    private static final String KEY_GAID           = "gaid_";
    private static final String KEY_WIFI_MAC       = "wifi_mac_";
    private static final String KEY_BT_MAC         = "bt_mac_";
    private static final String KEY_SERIAL         = "serial_";
    private static final String KEY_BUILD_FP       = "build_fp_";
    private static final String KEY_PROFILE        = "profile_";
    private static final String KEY_BRAND          = "brand_";
    private static final String KEY_MANUFACTURER   = "manufacturer_";
    private static final String KEY_MODEL          = "model_";
    private static final String KEY_DEVICE         = "device_";
    private static final String KEY_PRODUCT        = "product_";
    private static final String KEY_BOARD          = "board_";
    private static final String KEY_HARDWARE       = "hardware_";
    private static final String KEY_BOOTLOADER     = "bootloader_";
    private static final String KEY_SECURITY_PATCH = "security_patch_";
    private static final String KEY_INCREMENTAL    = "incremental_";

    // Index dans DEVICE_PROFILES — point unique de vérité pour la mise en page.
    private static final int P_BRAND = 0, P_MANU = 1, P_MODEL = 2, P_DEVICE = 3,
            P_PRODUCT = 4, P_VERSION = 5, P_BOARD = 6, P_HW = 7,
            P_BOOTLOADER = 8, P_SECPATCH = 9;

    /**
     * Profils d'appareils cohérents : valeurs réelles sourcées de fiches techniques
     * publiques (build.prop dumps GitHub, GSMArena). Tous les profils sont arm64-v8a
     * compatibles (aucun x86 / ARMv7-only) — vérifié pour le device cible A16.
     *
     * Schéma : {brand, manufacturer, model, device, product, version,
     *           board, hardware, bootloader, securityPatch}
     */
    private static final String[][] DEVICE_PROFILES = {
        {"samsung", "samsung", "SM-G991B",   "o1s",       "o1sxxx",       "13", "exynos2100", "exynos2100", "G991BXXSGHWA5",   "2024-09-01"},
        {"samsung", "samsung", "SM-A546B",   "a54x",      "a54xeea",      "14", "s5e8835",    "s5e8835",    "A546BXXU8DXJ1",   "2024-12-01"},
        {"samsung", "samsung", "SM-S918B",   "dm3q",      "dm3qxxx",      "14", "kalama",     "qcom",       "S918BXXS3CXC5",   "2024-12-01"},
        {"google",  "Google",  "Pixel 7",    "panther",   "panther",      "14", "panther",    "panther",    "cloudripper-1.0-9602082", "2024-12-05"},
        {"google",  "Google",  "Pixel 8",    "shiba",     "shiba",        "14", "shiba",      "shiba",      "husky-1.4-12068000",      "2024-12-05"},
        {"xiaomi",  "Xiaomi",  "22071212AG", "taro",      "taro",         "13", "taro",       "qcom",       "unknown",         "2024-08-01"},
        {"xiaomi",  "Xiaomi",  "23049RAD8G", "fuxi",      "fuxi",         "14", "fuxi",       "qcom",       "unknown",         "2024-10-01"},
        {"OnePlus", "OnePlus", "CPH2423",    "OP555AL1",  "OP555AL1",     "13", "OP555AL1",   "qcom",       "unknown",         "2024-09-01"},
        {"motorola","motorola","moto g73 5G","devon",     "devon_retail", "13", "devon",      "mt6855",     "MOTO_BL",         "2024-08-01"},
    };

    // Valeurs statiques (identiques pour tous les slots) — TAGS/TYPE/USER/HOST sur
    // un retail device sont des constantes ; HOST varie cosmétiquement mais aucun
    // anti-fraud connu ne le compare contre une vraie base. SUPPORTED_ABIS reste
    // constant car tous les profils ci-dessus sont des smartphones arm64 modernes.
    private static final String[] SUPPORTED_ABIS = {"arm64-v8a", "armeabi-v7a", "armeabi"};
    private static final String STATIC_TAGS  = "release-keys";
    private static final String STATIC_TYPE  = "user";
    private static final String STATIC_USER  = "android-build";
    private static final String STATIC_HOST  = "abfarm-build";
    private static final String STATIC_RADIO = "unknown";

    // Vrais OUI IEEE par marque. Permet aux MACs spoofées de ressembler à du
    // matériel constructeur, au lieu du préfixe 0x02 locally-administered que
    // les heuristiques anti-fraude utilisent comme signal de virtualisation.
    private static final Map<String, String[]> OUI_BY_BRAND = new HashMap<>();
    static {
        OUI_BY_BRAND.put("samsung",  new String[]{"00:12:FB", "34:23:BA", "78:1F:DB", "8C:77:12", "BC:14:85"});
        OUI_BY_BRAND.put("google",   new String[]{"00:1A:11", "3C:5A:B4", "54:60:09", "94:EB:2C", "F4:F5:E8"});
        OUI_BY_BRAND.put("xiaomi",   new String[]{"04:CF:8C", "28:E3:1F", "64:09:80", "74:23:44", "8C:BE:BE"});
        OUI_BY_BRAND.put("OnePlus",  new String[]{"64:A2:F9", "94:65:2D", "C0:EE:FB", "D8:55:A3"});
        OUI_BY_BRAND.put("motorola", new String[]{"00:08:0E", "00:13:5A", "B0:7B:25", "EC:88:92"});
    }

    // TAC (Type Allocation Code, 8 premiers chiffres d'un IMEI) alignés sur la
    // marque du slot : un slot Samsung doit produire un IMEI dont le TAC est
    // enregistré à Samsung, sinon un anti-fraud peut détecter l'incohérence.
    private static final Map<String, String[]> TAC_BY_BRAND = new HashMap<>();
    static {
        TAC_BY_BRAND.put("samsung",  new String[]{"35283012", "35347624", "35381711", "35411023"});
        TAC_BY_BRAND.put("google",   new String[]{"35167415", "35395814", "35283011"});
        TAC_BY_BRAND.put("xiaomi",   new String[]{"86891405", "86916304", "86977504"});
        TAC_BY_BRAND.put("OnePlus",  new String[]{"86777605", "86918607"});
        TAC_BY_BRAND.put("motorola", new String[]{"35472112", "35918910"});
    }

    private static final String[] DEFAULT_OUI = {"00:1A:11", "34:23:BA"};
    private static final String[] DEFAULT_TAC = {"35283012", "35347624"};

    private FingerprintManager(Context context) {
        // getApplicationContext() peut retourner null dans certains slot
        // processes BlackBox (le Application n'est pas encore attaché). On
        // fallback sur le context original pour ne jamais perdre la référence.
        Context appCtx = (context != null) ? context.getApplicationContext() : null;
        mContext = (appCtx != null) ? appCtx : context;
    }

    /**
     * Auto-initialise si nécessaire — fonctionne dans TOUS les processus
     * (host process ET slot process).
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
     * Init explicite depuis BlackBoxCore.doAttachBaseContext ou MainActivity.onCreate.
     * Gardé pour compatibilité — get() s'auto-initialise aussi.
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

    // ─── Host anchor (graine globale persistante) ────────────────────────────

    // L'ancre est l'entrée stable partagée par toutes les dérivations
    // d'identité de slots. Capturée une fois depuis l'Android ID réel du
    // device hôte (ou un UUID en fallback) puis persistée à vie. Survit à un
    // clear-data de PhantomApp tant que l'Android ID hôte est inchangé — donc
    // les slots récupèrent les mêmes identités après réinstall.
    private String getHostAnchor() {
        String cached = mHostAnchor;
        if (cached != null) return cached;
        synchronized (this) {
            if (mHostAnchor != null) return mHostAnchor;
            SharedPreferences sp = prefs();
            if (sp != null) {
                String stored = sp.getString(KEY_HOST_ANCHOR, null);
                if (stored != null && !stored.isEmpty()) {
                    mHostAnchor = stored;
                    return stored;
                }
            }
            String captured;
            if (sp != null && isHostProcess()) {
                captured = captureHostAnchor();
                sp.edit().putString(KEY_HOST_ANCHOR, captured).apply();
                Log.d(TAG, "Host anchor captured and persisted");
            } else {
                // Slot process avant que l'host ait capturé l'ancre, ou
                // prefs() indisponible. Fallback éphémère : caché en mémoire
                // pour rester consistant dans la session, jamais persisté.
                captured = UUID.randomUUID().toString();
                Log.w(TAG, "Host anchor accessed before persistence available; "
                        + "using ephemeral fallback");
            }
            mHostAnchor = captured;
            return captured;
        }
    }

    private String captureHostAnchor() {
        try {
            Context ctx = mContext;
            if (ctx == null) ctx = BlackBoxCore.getContext();
            if (ctx != null) {
                String androidId = Settings.Secure.getString(
                        ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (isStrongIdentifier(androidId)) {
                    return androidId;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "captureHostAnchor failed reading ANDROID_ID: " + e.getMessage());
        }
        return UUID.randomUUID().toString();
    }

    // Rejette les Android IDs AOSP buggués (9774d56d682e549c et autres
    // valeurs faiblement entropiques) en exigeant au moins 4 caractères
    // distincts.
    private boolean isStrongIdentifier(String s) {
        if (s == null || s.length() < 8) return false;
        HashSet<Character> distinct = new HashSet<>();
        for (int i = 0; i < s.length(); i++) distinct.add(s.charAt(i));
        return distinct.size() >= 4;
    }

    private boolean isHostProcess() {
        Context ctx = mContext;
        if (ctx == null) ctx = BlackBoxCore.getContext();
        if (ctx == null) return false;
        String procName = readProcName();
        if (procName == null) return false;
        return ctx.getPackageName().equals(procName);
    }

    private static String readProcName() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String line = br.readLine();
            if (line == null) return null;
            return line.trim().replace("\0", "");
        } catch (Exception ignored) {
            return null;
        }
    }

    // ─── Dérivation déterministe (seed 64 bits) ──────────────────────────────

    // Pour un même (slot, catégorie), produit un seed 64 bits stable en
    // hashant une chaîne composite. Les mêmes entrées donnent toujours la
    // même sortie : un slot dont les SharedPreferences sont effacées peut
    // être régénéré à l'identique tant que l'ancre hôte est intacte.
    private long seedFor(int userId, String category) {
        String composite = getHostAnchor() + "@" + userId + "@" + category;
        return sha256First8AsLong(composite);
    }

    private static long sha256First8AsLong(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (digest[i] & 0xFFL);
            }
            return result;
        } catch (Exception e) {
            return input.hashCode();
        }
    }

    // ─── Index de profil (cohérence entre tous les champs Build.*) ───────────

    private int getProfileIndex(int userId) {
        String key = KEY_PROFILE + userId;
        SharedPreferences sp = prefs();
        if (sp != null) {
            String stored = sp.getString(key, null);
            if (stored != null && !stored.isEmpty()) {
                try { return Integer.parseInt(stored); } catch (Exception ignored) {}
            }
        }
        long seed = seedFor(userId, "profile");
        int idx = (int) Math.floorMod(seed, (long) DEVICE_PROFILES.length);
        if (sp != null) {
            sp.edit().putString(key, String.valueOf(idx)).apply();
        }
        Log.d(TAG, "Profile assigned for slot=" + userId + " idx=" + idx
                + " brand=" + DEVICE_PROFILES[idx][0]);
        return idx;
    }

    // ─── Getters publics ─────────────────────────────────────────────────────

    public String getImei(int userId) {
        return getOrCreate(KEY_IMEI + userId, () -> {
            String brand = DEVICE_PROFILES[getProfileIndex(userId)][0];
            return generateImei(seedFor(userId, "imei"), brand);
        });
    }

    public String getMeid(int userId) {
        return getOrCreate(KEY_MEID + userId, this::generateMeid);
    }

    public String getImsi(int userId) {
        return getOrCreate(KEY_IMSI + userId, this::generateImsi);
    }

    public String getIccSerial(int userId) {
        return getOrCreate(KEY_ICC + userId, this::generateIcc);
    }

    public String getAndroidId(int userId) {
        return getOrCreate(KEY_ANDROID_ID + userId,
                () -> generateAndroidId(seedFor(userId, "android_id")));
    }

    /**
     * Google Advertising ID (GAID) — format UUID v4.
     * Unique par slot, persistant, réinitialisé par resetSlot().
     */
    public String getAdvertisingId(int userId) {
        return getOrCreate(KEY_GAID + userId, this::generateUUID);
    }

    public String getWifiMac(int userId) {
        return getOrCreate(KEY_WIFI_MAC + userId, () -> {
            String brand = DEVICE_PROFILES[getProfileIndex(userId)][0];
            return generateMac(seedFor(userId, "wifi_mac"), brand);
        });
    }

    public String getBluetoothMac(int userId) {
        return getOrCreate(KEY_BT_MAC + userId, () -> {
            String brand = DEVICE_PROFILES[getProfileIndex(userId)][0];
            return generateMac(seedFor(userId, "bt_mac"), brand);
        });
    }

    public String getSerial(int userId) {
        return getOrCreate(KEY_SERIAL + userId, this::generateSerial);
    }

    // Champs Build.* cohérents avec le profil du slot

    public String getBrand(int userId) {
        return getOrCreate(KEY_BRAND + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_BRAND]);
    }

    public String getManufacturer(int userId) {
        return getOrCreate(KEY_MANUFACTURER + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_MANU]);
    }

    public String getModel(int userId) {
        return getOrCreate(KEY_MODEL + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_MODEL]);
    }

    public String getDevice(int userId) {
        return getOrCreate(KEY_DEVICE + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_DEVICE]);
    }

    public String getProduct(int userId) {
        return getOrCreate(KEY_PRODUCT + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_PRODUCT]);
    }

    public String getBoard(int userId) {
        return getOrCreate(KEY_BOARD + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_BOARD]);
    }

    public String getHardware(int userId) {
        return getOrCreate(KEY_HARDWARE + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_HW]);
    }

    public String getBootloader(int userId) {
        return getOrCreate(KEY_BOOTLOADER + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_BOOTLOADER]);
    }

    /**
     * Security patch level du profil — date fixe par profil, garantit la
     * cohérence avec la date encodée dans le BUILD_ID du fingerprint
     * (sinon : signal trivial pour anti-fraud).
     */
    public String getSecurityPatch(int userId) {
        return getOrCreate(KEY_SECURITY_PATCH + userId,
                () -> DEVICE_PROFILES[getProfileIndex(userId)][P_SECPATCH]);
    }

    /**
     * Compteur incremental — entier 8 chiffres, déterministe par slot.
     * Aligné sur le format réel de Build.VERSION.INCREMENTAL.
     */
    public String getIncremental(int userId) {
        return getOrCreate(KEY_INCREMENTAL + userId, () -> {
            Random r = new Random(seedFor(userId, "incremental"));
            return String.valueOf(10000000 + r.nextInt(89999999));
        });
    }

    // Champs statiques — pas de stockage, retour direct des constantes.
    public String[] getSupportedAbis(int userId) { return SUPPORTED_ABIS.clone(); }
    public String   getTags(int userId)          { return STATIC_TAGS; }
    public String   getType(int userId)          { return STATIC_TYPE; }
    public String   getUser(int userId)          { return STATIC_USER; }
    public String   getHost(int userId)          { return STATIC_HOST; }
    public String   getRadio(int userId)         { return STATIC_RADIO; }

    /**
     * Build.DISPLAY ressemble typiquement à "<BuildId>.<suffix>" — on dérive
     * du fingerprint pour rester cohérent.
     */
    public String getDisplay(int userId) {
        String fp = getBuildFingerprint(userId);
        String buildId = extractBuildIdFromFingerprint(fp);
        if (buildId == null) return fp;
        Random r = new Random(seedFor(userId, "display"));
        char suffix = (char) ('A' + r.nextInt(26));
        return buildId + "." + suffix + (r.nextInt(9) + 1);
    }

    public String getBuildFingerprint(int userId) {
        return getOrCreate(KEY_BUILD_FP + userId, () -> {
            int idx = getProfileIndex(userId);
            String brand       = DEVICE_PROFILES[idx][P_BRAND];
            String device      = DEVICE_PROFILES[idx][P_DEVICE];
            String product     = DEVICE_PROFILES[idx][P_PRODUCT];
            String version     = DEVICE_PROFILES[idx][P_VERSION];
            String securityPatch = DEVICE_PROFILES[idx][P_SECPATCH];
            String buildId     = generateBuildIdAlignedTo(version, securityPatch, seedFor(userId, "buildid"));
            String incremental = String.valueOf(10000000 +
                    (int) (Math.floorMod(seedFor(userId, "incremental"), 89999999L)));
            return brand + "/" + product + "/" + device + ":" + version
                    + "/" + buildId + "/" + incremental
                    + ":" + STATIC_TYPE + "/" + STATIC_TAGS;
        });
    }

    private static String extractBuildIdFromFingerprint(String fp) {
        try {
            String[] parts = fp.split("/");
            return parts.length >= 4 ? parts[3] : null;
        } catch (Exception e) { return null; }
    }

    // ─── Reset complet d'un slot ──────────────────────────────────────────────

    // L'ancre hôte n'est volontairement pas touchée : régénérer un slot
    // identique doit produire les mêmes valeurs qu'auparavant.
    public void resetSlot(int userId) {
        SharedPreferences sp = prefs();
        if (sp == null) {
            Log.w(TAG, "resetSlot " + userId + " skipped — prefs() unavailable");
            return;
        }
        SharedPreferences.Editor editor = sp.edit();
        editor.remove(KEY_IMEI         + userId);
        editor.remove(KEY_MEID         + userId);
        editor.remove(KEY_IMSI         + userId);
        editor.remove(KEY_ICC          + userId);
        editor.remove(KEY_ANDROID_ID   + userId);
        editor.remove(KEY_GAID         + userId);
        editor.remove(KEY_WIFI_MAC     + userId);
        editor.remove(KEY_BT_MAC       + userId);
        editor.remove(KEY_SERIAL       + userId);
        editor.remove(KEY_BUILD_FP     + userId);
        editor.remove(KEY_PROFILE      + userId);
        editor.remove(KEY_BRAND          + userId);
        editor.remove(KEY_MANUFACTURER   + userId);
        editor.remove(KEY_MODEL          + userId);
        editor.remove(KEY_DEVICE         + userId);
        editor.remove(KEY_PRODUCT        + userId);
        editor.remove(KEY_BOARD          + userId);
        editor.remove(KEY_HARDWARE       + userId);
        editor.remove(KEY_BOOTLOADER     + userId);
        editor.remove(KEY_SECURITY_PATCH + userId);
        editor.remove(KEY_INCREMENTAL    + userId);
        editor.apply();
        Log.d(TAG, "Reset slot " + userId);
    }

    // ─── Générateurs ─────────────────────────────────────────────────────────

    private String generateImei(long seed, String brand) {
        Random r = new Random(seed);
        String[] tacPool = TAC_BY_BRAND.containsKey(brand)
                ? TAC_BY_BRAND.get(brand) : DEFAULT_TAC;
        String tac = tacPool[r.nextInt(tacPool.length)];
        StringBuilder sb = new StringBuilder(tac);
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
        String[] mncs = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10"};
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

    private String generateAndroidId(long seed) {
        Random r = new Random(seed);
        String hex = "0123456789abcdef";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(hex.charAt(r.nextInt(16)));
        return sb.toString();
    }

    /**
     * Génère un UUID v4 aléatoire — format standard du Google Advertising ID.
     */
    private String generateUUID() {
        return UUID.randomUUID().toString();
    }

    // OUI IEEE réel aligné sur la marque + 3 octets dérivés du seed. Le bit
    // locally-administered reste à 0, donc l'adresse ressemble à du matériel
    // constructeur authentique.
    private String generateMac(long seed, String brand) {
        Random r = new Random(seed);
        String[] ouiPool = OUI_BY_BRAND.containsKey(brand)
                ? OUI_BY_BRAND.get(brand) : DEFAULT_OUI;
        String oui = ouiPool[r.nextInt(ouiPool.length)];
        StringBuilder sb = new StringBuilder(oui);
        for (int i = 0; i < 3; i++) {
            sb.append(":");
            sb.append(String.format("%02X", r.nextInt(256)));
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

    /**
     * Build ID aligné sur la version Android + date du security patch du profil.
     * Format AOSP : <Letter><Branch><Number>.<YYMMDD>.<NNN>
     *   - Letter   = T (Android 13), U (Android 14), A (Android 15), B (Android 16)
     *   - YYMMDD   = date du security patch (ou un mois avant — les builds sortent
     *                avant le patch level qu'ils incluent)
     *   - NNN      = compteur de build (3 chiffres) dérivé du seed
     *
     * Exemple Android 14, patch 2024-12-05 → "UP1A.241201.NNN"
     */
    private String generateBuildIdAlignedTo(String version, String securityPatch, long seed) {
        Random r = new Random(seed);
        char letter;
        switch (version) {
            case "12": letter = 'S'; break;
            case "13": letter = 'T'; break;
            case "14": letter = 'U'; break;
            case "15": letter = 'A'; break;
            case "16": letter = 'B'; break;
            default:   letter = 'U';
        }
        String yymmdd = "240101";
        try {
            // securityPatch = "YYYY-MM-DD" — on garde YYMMDD avec DD=01 (les
            // builds officiels Android sortent toujours au 1er du mois).
            String[] p = securityPatch.split("-");
            if (p.length == 3) yymmdd = p[0].substring(2) + p[1] + "01";
        } catch (Exception ignored) {}
        int suffix = r.nextInt(900) + 100;
        return letter + "P1A." + yymmdd + "." + String.format("%03d", suffix);
    }

    // ─── Utilitaires ──────────────────────────────────────────────────────────

    private interface Generator { String generate(); }

    private String getOrCreate(String key, Generator gen) {
        try {
            SharedPreferences sp = prefs();
            if (sp == null) {
                // mContext indisponible et BlackBoxCore.getContext() aussi.
                // Renvoie une valeur éphémère pour que l'UI affiche quelque
                // chose ; la prochaine session avec un context valide
                // persistera correctement.
                Log.w(TAG, "prefs() unavailable for key=" + key
                        + "; returning ephemeral value");
                return gen.generate();
            }
            String val = sp.getString(key, null);
            if (val == null || val.isEmpty()) {
                val = gen.generate();
                sp.edit().putString(key, val).apply();
                Log.d(TAG, "Generated for key=" + key);
            }
            return val;
        } catch (Exception e) {
            Log.e(TAG, "getOrCreate error for key=" + key + ": " + e.getMessage());
            return gen.generate();
        }
    }

    private SharedPreferences prefs() {
        Context ctx = mContext;
        if (ctx == null) ctx = BlackBoxCore.getContext();
        if (ctx == null) return null;
        try {
            return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            Log.w(TAG, "getSharedPreferences failed: " + e.getMessage());
            return null;
        }
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
