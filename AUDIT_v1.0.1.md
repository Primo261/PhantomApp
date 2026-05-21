# Audit PhantomApp v1.0.1 — 2026-05-21

> Audit forensique pré-release. Sortie uniquement informative — aucune
> modification de code, aucun commit. Le scope couvre `app/src/main/java/com/phantom/**`,
> les Activities & adapters de la nouvelle UI, les `*Proxy.java` de
> `Bcore/.../fake/service/`, `FingerprintManager`, le manifest, ProGuard,
> les build files et la config réseau.

## Résumé exécutif

- **22 findings CRITIQUES** (bloquent la release pour des users payants : exfiltration de licence, patch trivial du gate, crash en slot avéré, ANR à chaque boot, MITM possible).
- **34 findings IMPORTANTS** (à fixer avant v1.1 : edge-cases licence/réseau, fuites mémoire, perfs, dette anti-RE).
- **18 findings NICE-TO-HAVE** (qualité code, perfs marginales, durcissement post-launch).

**Top 3 critiques à fixer EN PREMIER :**

1. **[SEC-001]** `allowBackup="true"` + `EncryptedSharedPreferences` à fallback silencieux ⇒ exfiltration trivial de la licence Ed25519-signée via `adb backup` ⇒ copie inter-device gratuite.
2. **[SEC-003]** Règle ProGuard `-keep class com.phantom.app.license.** { *; }` ⇒ tous les noms de classes/méthodes du gate licence (`Ed25519Verifier.verify`, `LicenseGuard.isValid`, etc.) restent en clair dans le release APK ⇒ un attaquant remplace `Ed25519Verifier.verify` par `return true` en 5 minutes.
3. **[REL-001]** `runBlocking` dans `MainActivity.onCreate` avec un budget 6s en attente d'un `/api/verify` réseau ⇒ ANR garanti si latence > 5s sur 4G médiocre + chaque retour foreground (cold start fréquent) re-paye ce coût.

---

## CRITIQUE (bloque release)

### [SEC-001] License exfiltrable via `adb backup` — `allowBackup=true`

**Fichier** : `app/src/main/AndroidManifest.xml:20`
**Catégorie** : Sécurité / Storage
**Description** : Le manifest déclare `android:allowBackup="true"` (par défaut implicite confirmé explicitement). En parallèle, `LicenseStorage.prefs()` retombe **silencieusement** sur des `SharedPreferences` en clair (`phantom_license_plain`) si `EncryptedSharedPreferences.create()` échoue (cf. LicenseStorage.kt:48-55).
**Reproduction** : `adb backup -f phantom.ab -noapk com.phantom.app` → décoder → on lit `LICENSE_KEY`, `PAYLOAD_JSON`, `SIGNATURE_HEX` et `DEVICE_ID` en clair sur n'importe quel device où le keystore Android a planté (OneUI vieux, ColorOS, etc.).
**Risque** : Un user partage sa licence avec un copain : ils copient `phantom_license_plain.xml` + le keystore tutbox sur device B ; serveur dira "device_count=1, deviceId match" (puisque deviceId est copié aussi). Distribution gratuite de licences.
**Recommandation** : `android:allowBackup="false"` ou au minimum un `BackupAgent` qui exclut explicitement `phantom_license_secure*` et `phantom_license_plain*`. Faire ÉCHOUER au lieu de retomber en clair quand `EncryptedSharedPreferences` ne se construit pas (`throw IllegalStateException` plutôt que `Log.e + fallback`).

### [SEC-002] License key entièrement logguée en production

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseApi.kt:60-75`, `LicenseGuard.kt:92,170,267-269`, `LicenseStorage.kt:80`, `LicenseParser.kt:25,44`
**Catégorie** : Sécurité / Crypto
**Description** : Aucun gating sur `BuildConfig.DEBUG`. La license entière + signature_hex + payload JSON sont écrits via `Log.d` dans le release APK :
```
LicenseApi.kt:62  Log.d(TAG, "verifyOnline: REQUEST BODY = $body")
LicenseGuard.kt:267 Log.d(TAG, "verifyLocal: canonical_json=$canonicalStr")
LicenseGuard.kt:269 Log.d(TAG, "verifyLocal: rawLicenseKeyBytes=${rawLicenseBytes.toHex()}")
LicenseStorage.kt:65 Log.d(TAG, "getOrCreateDeviceId: existing=$existing")
```
**Risque** : Toute app avec `READ_LOGS` (parfois OEMs accordent ça, ou root) ou un crash reporter intégré chez l'user (Crashlytics, etc. — non présent ici mais possible) capture la licence. Sur Android < 16 KB, `adb logcat` direct fonctionne après autorisation USB. Un copain à qui on prête le téléphone 5 min peut copier-coller la licence.
**Recommandation** : Wrapper `Slog` privé qui `if (BuildConfig.DEBUG)` autour de chaque `Log.d`, ou utiliser `android.util.Log#isLoggable` avec un TAG verbosé via `setprop`. Strip tous les `Log.d` sur licence/payload/signature en release via ProGuard.

### [SEC-003] ProGuard ne protège PAS le gate licence

**Fichier** : `app/proguard-rules.pro:35`
**Catégorie** : Anti-reverse engineering
**Description** :
```
-keep class com.phantom.app.license.** { *; }
```
garde **tous les noms** de classes et méthodes : `Ed25519Verifier.verify`, `LicenseGuard.isValid`, `LicenseGuard.activate`, `LicenseStorage.clearLicense`, `LicenseParser.parse`, etc. Le commentaire du fichier dit "JSONObject/reflection-based field access" pour justifier mais en pratique seul `LicensePayload` a besoin de la rétention de champs (et encore — il fait un parsing manuel via `getString`).
**Reproduction** : `jadx -d out app-release.apk` → ouvrir `com/phantom/app/license/Ed25519Verifier.smali` → remplacer le corps de `verify()` par `const/4 v0, 0x1; return v0` → `apktool b` + resign avec n'importe quelle clé → license fake passe. Vu que `MainActivity` n'a aucune verif d'intégrité (signature APK), l'app fonctionne tel quel après resign.
**Risque** : Si un seul user motivé décompile (= la définition même d'une distrib hors-store), il publie un APK "Phantom-cracked-v1.0.1.apk" sur un forum et toutes les futures licences sont contournables.
**Recommandation** :
- Supprimer la règle `-keep class com.phantom.app.license.** { *; }`. Ne garder que ce qui est strictement nécessaire pour JSONObject (les `data class` propriétés qui matchent les clés JSON, mais pas les classes utilitaires). Tester l'activation après obfuscation.
- Ajouter un check d'intégrité APK au boot : `context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures[0].toCharsString().hashCode()` comparé à une constante (elle-même obfusquée par XOR ou native).
- Idéalement, déplacer `Ed25519Verifier.verify` côté native (JNI vers BoringSSL ou libsodium déjà packagé). Patcher du natif strippé est ~10× plus dur que patcher du Kotlin.

### [SEC-004] Aucun anti-replay sur `/api/verify` (pas de nonce/timestamp signé)

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseApi.kt:46-69`
**Catégorie** : Sécurité / Protocole réseau
**Description** : Le corps POST `{licenseKey, deviceId, deviceInfo}` est envoyé tel quel, sans nonce, sans timestamp client, sans HMAC. Si la TLS est compromise (MITM Charles + cert installé manuellement sur device user, OU bug Android dans la stack TLS d'une vieille ROM), une réponse capturée précédemment `{valid: true, payload, signature_hex}` peut être rejouée indéfiniment pour booter en mode offline-grace.
**Risque** : Combiné avec [SEC-005] (pas de cert pinning), un user un peu motivé installe Charles + cert custom (procédure standard pour debug d'apps), capture la réponse positive du jour de son activation, puis intercepte indéfiniment `/api/verify` côté device pour répondre la même chose même après revocation serveur.
**Recommandation** : Ajouter un timestamp client + signature HMAC dans la requête, et un timestamp/nonce serveur dans la réponse pour empêcher le rejeu. Le serveur peut alors enforcer une fenêtre de validité (5min) sur la réponse signée.

### [SEC-005] Pas de certificate pinning sur `admin.phantomapp.fr`

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseApi.kt:35-42`, `NewsApi.kt:28-35`, `UpdateChecker.kt:36-43`
**Catégorie** : Sécurité / TLS
**Description** : `OkHttpClient.Builder()` est utilisé tel quel, sans `CertificatePinner`. Une CA root corrompue ou installée par l'user (Charles, Burp, NetGuard…) peut MITM toutes les requêtes.
**Risque** : Combiné avec [SEC-004], permet la création d'un proxy local qui répond toujours `valid:true` ⇒ contournement du gate de revocation et du gate d'expiration côté serveur (le local Ed25519 vérifie une payload + signature serveur, mais si le proxy local génère sa propre paire de clés + remplace `LicenseConfig.PUBLIC_KEY_HEX` dans le smali, c'est fini — cf. [SEC-003]). Indépendamment, MITM = lecture passive de toutes les licences valides qui passent par le device.
**Recommandation** : Pinner SPKI-SHA256 dans `network_security_config.xml` :
```xml
<domain-config>
  <domain>admin.phantomapp.fr</domain>
  <pin-set>
    <pin digest="SHA-256">…</pin>
    <pin digest="SHA-256">backup_pin</pin>
  </pin-set>
</domain-config>
```
+ retirer `<base-config cleartextTrafficPermitted="true" />` (cf. SEC-006).

### [SEC-006] `cleartextTrafficPermitted="true"` global

**Fichier** : `app/src/main/res/xml/network_security_config.xml:2-3`
**Catégorie** : Sécurité / TLS
**Description** :
```xml
<network-security-config>
  <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```
Configuration par défaut autorise tout HTTP cleartext pour TOUT host. Les appels à `admin.phantomapp.fr` sont en `https://` donc bénéficient quand même de TLS, mais un attaquant qui peut downgrader (DNS-spoof vers un host malveillant) peut servir HTTP cleartext et OkHttp l'acceptera.
**Risque** : Combiné avec [SEC-005], un attaquant DNS-spoof `admin.phantomapp.fr` → son serveur répond en HTTP → OkHttp accepte → réponse `valid:true` triviale, sans même avoir besoin de cert.
**Recommandation** :
```xml
<network-security-config>
  <base-config cleartextTrafficPermitted="false" />
  <domain-config>
    <domain includeSubdomains="true">admin.phantomapp.fr</domain>
    <pin-set>…</pin-set>
  </domain-config>
</network-security-config>
```

### [SEC-007] Build.MODEL/MANUFACTURER/BRAND envoyés au serveur sont ceux de l'**hôte**, pas du slot

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:279-285`
**Catégorie** : Sécurité / Privacy / Cohérence
**Description** :
```kotlin
private fun deviceInfo(): Map<String, String> = mapOf(
    "manufacturer" to (Build.MANUFACTURER ?: ""),
    "model" to (Build.MODEL ?: ""),
    "brand" to (Build.BRAND ?: ""),
    …
)
```
`Build.MODEL` est lu **dans le process host com.phantom.app** où aucune hook BlackBox ne s'applique. Le serveur reçoit donc le vrai modèle device (ex. "SM-A165F" sur l'A16) et pas un Build spoofé. Bug confirmé dans CLAUDE.md (au sujet du log "moto g73 5G" parasité par un autre device).
**Risque** : Le serveur fingerprintera le vrai device de Mathis (et tout futur user). Si une révocation se fait par modèle ("ban all moto g73"), elle s'applique à de mauvais devices. Plus important : le `device_count` côté serveur croît artificiellement si l'user réinstalle Phantom sur le même device mais que la storage est wipée et que `deviceId` est régénéré (cf. SEC-008) → tous les nouveaux `deviceId` arrivent avec le même Build.MODEL, le serveur peut soit les fusionner soit pas mais l'info envoyée est trompeuse vs. l'intention "device-bound".
**Recommandation** : Trois options selon l'intention :
1. **Si l'intention est de fingerprint l'hôte** : OK comme c'est mais documenter clairement.
2. **Si l'intention est de fingerprint le device sans révéler le modèle exact** : envoyer un hash. `hash(Build.MODEL+Build.BOARD+ANDROID_ID)`.
3. **Si l'intention est zéro fingerprint** : ne PAS envoyer `deviceInfo` du tout, n'utiliser que `deviceId` (UUID local). Recommandé.

### [SEC-008] deviceId est un UUID régénéré au premier launch — pas d'ancre device-bound

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseStorage.kt:61-72`
**Catégorie** : Sécurité / Licence
**Description** : `getOrCreateDeviceId` crée un `UUID.randomUUID()` puis le stocke dans `EncryptedSharedPreferences`. Aucun ancrage dans le device matériel (pas de SSAID, pas de Build.SERIAL, pas de keystore-backed key).
**Risque** :
- Un user qui désinstalle/réinstalle obtient un nouveau `deviceId` ⇒ pour le serveur ça compte comme un nouveau device, `device_count` peut être épuisé après quelques réinstalls.
- Plus grave : un user qui veut partager sa licence copie `phantom_license_plain.xml` (cf. SEC-001) avec le même `deviceId` ⇒ le serveur ne peut pas distinguer le device d'origine d'une copie, parce que l'UUID n'est PAS lié au matériel.
**Recommandation** : Lier `deviceId` à `Settings.Secure.ANDROID_ID` (host process) + Keystore-backed AES key (`KeyGenParameterSpec` avec `setUserAuthenticationRequired(false)` mais `setIsStrongBoxBacked(true)` quand possible). Le keystore est device-bound et un copy/paste de prefs ne survivra pas (le KeyAlias ne peut pas être recréé sans le matériel).

### [SEC-009] Pas de protection clock-rollback

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:47-52,80-87`
**Catégorie** : Sécurité / Licence
**Description** : `isValid` compare `payload.expires_at` à `System.currentTimeMillis() / 1000`. Une license expirée hier devient valide aujourd'hui si l'user met l'horloge du téléphone à hier. Pareil pour le grace offline (`now - lastVerifiedAt`).
**Risque** : User détecte l'expiration approchante → désactive auto-sync NTP → recule l'horloge de 6 mois → continue d'utiliser l'app indéfiniment en mode offline-grace (1h "perçu" mais l'horloge ne bouge plus). Le watchdog tente un re-verify online mais en offline ça part toujours en `UNREACHABLE` ⇒ grace passe.
**Recommandation** :
- Stocker la dernière timestamp serveur connue dans `EncryptedSharedPreferences`. Si la prochaine `System.currentTimeMillis()` est < cette valeur, c'est un rollback → invalider la licence.
- Faire un check `SystemClock.elapsedRealtime()` (monotonique, ne recule jamais sauf reboot) corrélé à `currentTimeMillis()` pour détecter les sauts.
- Sur les Pixel/Samsung modernes, `Build.VERSION.SDK_INT >= 30` permet `SystemClock.currentNetworkTimeMillis()` qui est non-spoofable côté user.

### [SEC-010] `WHATSAPP_CONTACT_URL` divulgue le numéro perso du propriétaire en clair dans l'APK

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseConfig.kt:6`
**Catégorie** : Sécurité / Privacy / OSINT
**Description** : `WHATSAPP_CONTACT_URL = "https://wa.me/33759700413"` est en const String, donc en clair dans le DEX final (toute personne qui décompile lit le numéro).
**Risque** : Numéro de Mathis dans tous les APKs distribués → cible pour spam SIM-swap, harcèlement, OSINT.
**Recommandation** : Si le canal de support est WhatsApp, créer un numéro pro Twilio/Vonage et router. Sinon, fetch le contact depuis `/api/news` (déjà fetché). À minima : XOR le numéro avec une constante puis décoder runtime — n'arrête pas un attaquant mais évite les grep automatisés.

### [SEC-011] WelcomeActivity exported=true sans permission, accessible par toute app installée sur le device hôte

**Fichier** : `app/src/main/AndroidManifest.xml:35-44`
**Catégorie** : Sécurité / IPC
**Description** : `WelcomeActivity` est exposed avec `android.intent.action.MAIN` + `LAUNCHER`. Normal pour le launcher mais aucune protection contre un appel externe `startActivityForResult` qui ferait sauter le gate licence (puisque `MainActivity.onCreate` est appelée derrière). Idem `MainActivity` (exported=true) et `ShortcutActivity` (exported=true).
**Risque** : Une app concurrente pourrait scripted `startActivity(WelcomeActivity)` pour analyser le splash, ou pire envoyer un `Intent` à `ShortcutActivity` avec des extras forgés qui contournent des checks.
**Recommandation** :
- `MainActivity` n'a pas besoin d'être exported=true (il a un MAIN sans LAUNCHER, donc ce n'est pas l'entry point officiel). Le mettre à `exported="false"`.
- `ShortcutActivity` : si c'est pour des shortcuts dynamiques créés par Phantom lui-même, vérifier l'identité de l'appelant via `callingActivity?.packageName` + check signature.
- Renforcer chaque entry point exposed avec `android:permission="com.phantom.app.permission.INTERNAL"` (defined par phantom, signature-level).

### [SEC-012] `verifyOnline` accepte `400-499` comme succès et parse le JSON quand même

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseApi.kt:76-83`
**Catégorie** : Sécurité / Protocole
**Description** :
```kotlin
if (!resp.isSuccessful && code !in 400..499) {
    return@withContext Result.failure(...)
}
val parsed = parseResponse(text)
```
Sur HTTP 401/403/etc., le code tombe quand même dans `parseResponse` qui retourne `valid=false` par défaut (`opt("valid", false)`) — OK pour `valid` mais : si l'attaquant MITM répond `HTTP 400` avec body `{"valid":true, "payload":{…}, "signature_hex":"…"}` (forgé), le client accepte la réponse. Le check Ed25519 derrière protège SI la pubkey n'a pas été swappée, mais en combo avec [SEC-003] tout tombe.
**Risque** : Crée un chemin de code "succès en cas d'erreur HTTP" inutile et exploitable.
**Recommandation** : Traiter strictement `!resp.isSuccessful` comme failure réseau, jamais comme un parse. La logique métier "license invalide" doit retourner HTTP 200 + `{valid:false, reason:…}` côté serveur, sinon c'est mal designé.

### [SEC-013] Activation accepte un payload server-side déjà expiré

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:122-171`
**Catégorie** : Fiabilité / Sécurité / Licence
**Description** : Dans `activate()`, après que le serveur dit `valid=true`, le code vérifie la signature Ed25519 et sauvegarde — mais ne vérifie **pas** que `payload.expires_at > now`. Si le serveur retourne `valid=true` avec un payload où `expires_at` < `now` (bug serveur, mauvaise config, attaque), le code sauve la licence. Au prochain boot, `isValid` rejette à `expires_at <= nowSec` (ligne 49) ⇒ kick vers ActivationActivity. L'user a "activé avec succès" mais ne peut pas booter — UX catastrophique.
**Reproduction** : Faire entrer une licence dont le serveur dirait `valid=true, payload.expires_at=1` (cas serveur buggé ou attaque ciblée). User voit "Activation Success" puis crash boot 2s plus tard.
**Recommandation** : Dans `activate()` après ligne 167, ajouter :
```kotlin
val nowSec = System.currentTimeMillis() / 1000L
if (payload.expires_at <= nowSec) {
    return ActivationResult.Failure("expired",
        "License déjà expirée. Contacte l'admin.")
}
```

### [SEC-014] EncryptedSharedPreferences fallback silencieux écrase la sécurité

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseStorage.kt:48-58`
**Catégorie** : Sécurité / Storage
**Description** : Si `EncryptedSharedPreferences.create()` throw (corrupt keystore, ROM custom, etc.), fallback silencieux vers `getSharedPreferences(FALLBACK_FILE_NAME, MODE_PRIVATE)` — license stockée en clair, **sans en informer l'user**. Le commentaire à raison de dire que la signature Ed25519 protège contre la forge, mais n'aborde pas le risque d'exfiltration (cf. SEC-001).
**Risque** : Sur des devices Android customs où Crypto a planté, l'user pense être protégé et ne l'est pas. Combiné avec allowBackup=true : exfiltration silencieuse.
**Recommandation** : Soit on accepte le fallback et on le surfaceà l'user via un toast/dialog ("Erreur de sécurité système, contacte l'admin"). Soit on échoue franchement : `throw IllegalStateException` et on log un `Log.e` pour Crashlytics.

### [REL-001] `runBlocking` 6s + appel réseau dans `MainActivity.onCreate` → ANR garanti

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:64-66`
**Catégorie** : Fiabilité / ANR
**Description** :
```kotlin
val gateOk = runBlocking {
    withTimeoutOrNull(6_000L) { LicenseGuard.isValid(this@MainActivity) }
}
```
`onCreate` du Main thread bloque jusqu'à 6 secondes. L'ANR threshold est 5 secondes. Si :
- l'user est en 3G/EDGE lente
- ou le device est en doze partiel
- ou `admin.phantomapp.fr` répond en 5.5s (CDN cold start, le serveur est sur OVH si je devine bien)

⇒ Android affiche "Phantom is not responding".
**Reproduction** : Forcer un proxy avec latence simulée 5s+ (`adb shell tc qdisc add dev wlan0 root netem delay 5500ms`) → cold-start Phantom → ANR dialog.
**Risque** : First impression catastrophique. Pour un produit payant, c'est rédhibitoire.
**Recommandation** :
1. Synchronous gate ne doit JAMAIS dépendre d'un appel réseau dans `onCreate`. Pattern propre : lire la licence locale uniquement (instant), si VALIDE (= signature OK + non expirée) → laisser passer + lancer reverify en arrière-plan via `lifecycleScope`. Si la reverify dit invalide → kick à `ActivationActivity` (au lieu de bloquer `onCreate`).
2. Plus simple : faire le gate dans `WelcomeActivity` (déjà un splash de 2.5s) avec `lifecycleScope.launch + withTimeoutOrNull(2_500L)` en parallèle de l'animation. Au pire l'user voit une animation 2.5s + 500ms d'extra splash si network lent.

### [REL-002] ~30 *Proxy.java forwardent les calls système sans catcher `SecurityException`

**Fichier** : Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ — voir liste complète ci-dessous
**Catégorie** : Fiabilité / Crash slot
**Description** : `ILocationManagerProxy` a été patché récemment avec un `try/catch(Throwable)` qui détecte `SecurityException` via la chaine de causes ; mais cette protection n'est PAS étendue. Liste des proxies qui forwardent à `method.invoke(who, args)` sans aucun catch de `SecurityException` dans leur `invoke` ou leurs `MethodHook` :
- `IAccessibilityManagerProxy`
- `IAccountManagerProxy`
- `IActivityClientProxy`
- `IActivityTaskManagerProxy`
- `IAlarmManagerProxy`
- `IAppWidgetManagerProxy`
- `IAutofillManagerProxy`
- `IContextHubServiceProxy`
- `IDeviceIdentifiersPolicyProxy`
- `IDevicePolicyManagerProxy`
- `IDisplayManagerProxy`
- `IFingerprintManagerProxy`
- `IGraphicsStatsProxy`
- `IInputMethodManagerProxy`
- `ILauncherAppsProxy`
- `IMediaRouterServiceProxy`
- `IMediaSessionManagerProxy`
- `INetworkManagementServiceProxy`
- `INotificationManagerProxy`
- `IPermissionManagerProxy`
- `IPersistentDataBlockServiceProxy`
- `IPowerManagerProxy`
- `IShortcutManagerProxy`
- `IStorageStatsManagerProxy`
- `ISystemUpdateProxy`
- `ITelephonyRegistryProxy`
- `IUserManagerProxy`
- `IVibratorServiceProxy`
- `IVpnManagerProxy`
- `IWifiScannerProxy`
- `IWindowManagerProxy`
- `IWindowSessionProxy`
**Risque** : N'importe quel SDK pub/ analytics dans une slot app (Inneractive confirmé, AppsFlyer, Adjust, Branch, Firebase, etc.) déclenchera un `SecurityException` la prochaine fois qu'Android 14+ resserre les permissions ; ça crashera l'app virtualisée. Vu que Phantom virtualise Vinted (qui embarque Datadome + Adjust + Bugsnag), c'est juste une question de temps avant qu'une perm random pète tout.
**Recommandation** : Extraire la logique safe-default de `ILocationManagerProxy.invoke()` dans `BinderInvocationStub` (ou un mixin) et l'appliquer aux ~30 proxies ci-dessus. Logique : try {super.invoke()} catch(Throwable) { if (isSecurityException(t)) return safeDefault(method.returnType); throw t; }.

### [REL-003] `ITelephonyRegistryProxy` confirmé fragile (Inneractive)

**Fichier** : `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/ITelephonyRegistryProxy.java:33-51`
**Catégorie** : Fiabilité / Bug connu
**Description** : Les `MethodHook` pour `listen` et `listenForSubscriber` forwardent directement (`method.invoke(who, args)`). CLAUDE.md note que Inneractive SDK déclenche un `SecurityException` sur `registerTelephonyCallback` — déjà observé. La protection n'a pas été appliquée. La méthode `registerTelephonyCallback` n'est même pas hookée explicitement et tombe dans `super.invoke()` qui ne catch pas non plus.
**Risque** : Crash garanti sur slots qui chargent un SDK pub utilisant TelephonyCallback (Android 12+) sans la perm. Vinted via Datadome est suspect.
**Recommandation** : Cf. REL-002. Override `invoke()` dans `ITelephonyRegistryProxy` avec le même pattern try/catch + isSecurityException.

### [REL-004] `LicenseStorage.prefs()` peut bloquer le main thread à la création des EncryptedSharedPreferences

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseStorage.kt:32-58`
**Catégorie** : Fiabilité / ANR
**Description** : `EncryptedSharedPreferences.create()` fait du `AndroidKeyStore`/AES init au premier appel — ~200ms typique, jusqu'à 2s sur OEMs custom. Le synchronized block dans `prefs()` est appelé depuis `MainActivity.onCreate` (via `LicenseGuard.isValid` puis `getStoredLicense`) qui est lui-même dans `runBlocking` (cf. REL-001) ⇒ s'ajoute aux 6s du gate.
**Risque** : Cumul des latences. Sur premier cold start après une mise à jour, peut atteindre 6.2s + 0.3s = 6.5s sur main thread.
**Recommandation** : Pré-warmer `LicenseStorage.prefs(context)` depuis `App.attachBaseContext` côté background thread (`HandlerThread`). Le premier appel main thread sera alors instantané.

### [REL-005] Watchdog inopérant pendant l'usage des slots (process host paused)

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:107-110`, `LicenseWatchdog.kt:74-82`
**Catégorie** : Fiabilité / Licence
**Description** : `LicenseWatchdog.stop()` est appelée dans `onPause`. Quand l'user lance Vinted dans un slot, MainActivity passe en `onPause` → watchdog s'arrête → revocation côté serveur n'est PAS prise en compte avant que l'user revienne au foreground de Phantom.
**Risque** : Un user dont la licence est révoquée peut continuer à utiliser Vinted dans un slot pendant 24h+ jusqu'à ce qu'il rouvre Phantom. Pour un produit anti-abuse (licences révoquées = bad actors), c'est un gros trou.
**Recommandation** : Soit accepter cet état (et le documenter), soit déclencher la revocation côté `Bcore` (kill all slot processes) depuis un service foreground qui survit à `onPause`. Solution intermédiaire : enregistrer un `WorkManager` periodic 30min qui re-verify et tue les slots si invalide.

### [REL-006] Race condition dans `LicenseStorage.clearLicense`

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseStorage.kt:93-102`
**Catégorie** : Fiabilité / Données
**Description** :
```kotlin
fun clearLicense(context: Context) {
    val sp = prefs(context)
    val deviceId = sp.getString(KEY_DEVICE_ID, null)
    sp.edit().clear().apply()
    if (deviceId != null) {
        sp.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }
}
```
Deux `edit().apply()` consécutifs. Apply est async ⇒ fenêtre où `KEY_DEVICE_ID` n'est plus lisible. Si le watchdog re-verify pendant ce gap (60s timing mais possible si deactivation manuelle au mauvais moment), `getStoredLicense` lit `deviceId=null` ⇒ retourne null ⇒ NO_LICENSE ⇒ kick.
**Risque** : Faible mais reproductible. Race-y, crée des bugs intermittents difficiles à reproduire.
**Recommandation** :
```kotlin
sp.edit()
  .clear()
  .apply { deviceId?.let { putString(KEY_DEVICE_ID, it) } }
  .apply()
```
(un seul edit transactionnel).

### [REL-007] `addNewSlot` utilise `users.size` comme userId — race en cas de clic double FAB

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:237-247`
**Catégorie** : Fiabilité / Concurrence
**Description** : Deux clics rapides sur le FAB ⇒ deux coroutines lancent `BlackBoxCore.get().createUser(users.size)`. Les deux lisent `users.size` (disons 3) avant que l'une ait fini de créer le slot ⇒ tentative de créer deux slots avec userId=3 simultanément. Le `BlackBoxCore.createUser` peut lever ou créer un état corrompu.
**Risque** : Slot fantôme, données slot mélangées, RecyclerView crash.
**Recommandation** : Debounce le FAB (`isEnabled = false` pendant le launch + reset dans finally) ET utiliser un compteur atomique pour `userId` (`AtomicInteger`).

### [REL-008] `ForceUpdateActivity` ne kill PAS les slots en cours

**Fichier** : `app/src/main/java/com/phantom/app/ui/ForceUpdateActivity.kt:24-33`
**Catégorie** : Fiabilité / Édge case
**Description** : `ForceUpdateActivity.start` fait `finishAffinity()` sur l'Activity host appelante, ce qui ferme `MainActivity` ⇒ mais les process slots virtualisés (`com.phantom.app:p0`, `:p1`, etc.) restent vivants en background. L'user peut continuer à utiliser Vinted dans un slot malgré le force update.
**Risque** : "Force update" pas vraiment forcé.
**Recommandation** : Avant `finishAffinity()`, appeler `BlackBoxCore.get().killAllProcesses()` (ou équivalent — Docs.md devrait avoir une API pour stopper tous les slot processes). Si ce n'est pas exposed, ajouter un kill global de `*.p0`/`*.p1`/etc. via `ActivityManager.killBackgroundProcesses`.

### [REL-009] `forceUpdate.openUrl` peut laisser l'user piégé

**Fichier** : `app/src/main/java/com/phantom/app/ui/ForceUpdateActivity.kt:42-79`
**Catégorie** : Fiabilité / UX
**Description** : Si `updateUrl` est vide OU si l'`ACTION_VIEW` Intent ne trouve aucun handler (devic Android sans browser, ou pas de WhatsApp/store), un Toast s'affiche mais le back est bloqué (`onBackPressedDispatcher` toujours en `handleOnBackPressed`) ⇒ l'user est piégé sur le screen, doit force-kill l'app.
**Risque** : Support flood.
**Recommandation** :
- Sur empty url, afficher message clair "Mise à jour requise, contacte l'admin via WhatsApp" + bouton WhatsApp.
- Le bouton `btn_quit` doit toujours fonctionner (il le fait, `finishAffinity()`).

### [SEC-015] License watchdog ne détecte pas le tampering local du `EncryptedSharedPreferences`

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:35-88`
**Catégorie** : Sécurité / Licence
**Description** : Si l'user (root) édite le payload pour étendre `expires_at` à +10 ans, `verifyLocalSignature` rejette ✓. Mais si l'user remplace **tout** (license_key + payload + signature) par une paire forgée localement, en patchant aussi `LicenseConfig.PUBLIC_KEY_HEX` dans le smali → tout passe (cf. SEC-003). Tant que SEC-003 est ouvert, ce check est de la déco.
**Risque** : Cf. SEC-003.
**Recommandation** : Cf. SEC-003 (native + obfusquer + intégrité APK).

### [REL-010] Le `BuildConfig.DEBUG` n'est utilisé nulle part pour gater les logs en release

**Fichier** : Tous les fichiers `com/phantom/app/**` qui font `Log.d`
**Catégorie** : Fiabilité / Sécurité hybride
**Description** : Aucun `if (BuildConfig.DEBUG)` ni `Log.isLoggable`. ProGuard `-assumenosideeffects` n'est pas dans `proguard-rules.pro` non plus.
**Risque** : Les ~70+ Log.d/Log.w/Log.e laissés dans le release APK :
- exposent des infos sensibles (cf. SEC-002)
- ralentissent légèrement (string concatenation toujours évaluée)
**Recommandation** : Ajouter à proguard-rules.pro :
```
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
```
… **mais seulement après avoir vérifié qu'aucun Log.d n'a un side effect** (en théorie aucun n'en a, vérifier).

---

## IMPORTANT (à fixer avant v1.1)

### [IMP-001] `targetSdk = 28` (Android 9) sur compileSdk 35

**Fichier** : `build.gradle:10`
**Catégorie** : Build / Compat
**Description** : `targetSdkVersion = 28` ⇒ Android n'applique pas les protections par défaut de SDK 29+ : pas de scoped storage enforcement, pas de notif des permissions auto-revoked, pas de package visibility filtering implicite (Phantom utilise `QUERY_ALL_PACKAGES` mais d'autres APIs sont impactées), pas de filterTouchesWhenObscured stricter.
**Risque** : Sur Android 14+, certaines features système changent de comportement uniquement si l'app target >= une certaine API ; ici targetSdk=28 ⇒ Phantom utilise les chemins legacy (parfois moins fiables, parfois moins sécurisés). Google Play Protect peut warn l'user "app obsolète".
**Recommandation** : Bumper progressivement à targetSdk=33 puis 34. Tester slot par slot. Note : le moteur BlackBox peut casser sur certains targetSdk élevés — c'est probablement la raison de la fixation à 28. À documenter clairement (commentaire dans build.gradle).

### [IMP-002] `READ_EXTERNAL_STORAGE` non bornée par `maxSdkVersion`

**Fichier** : `app/src/main/AndroidManifest.xml:13-15`
**Catégorie** : Manifest / Permissions
**Description** : `WRITE_EXTERNAL_STORAGE` a `maxSdkVersion="29"` ✓ mais `READ_EXTERNAL_STORAGE` n'en a pas. Sur API 33+ (Android 13) cette permission est remplacée par `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`/`READ_MEDIA_AUDIO` granulaires.
**Risque** : Demande de permission affiche "Photos and media" non-granulaire ⇒ user friction. Sur API 34+, peut être refusée par défaut.
**Recommandation** : Ajouter `android:maxSdkVersion="32"` à `READ_EXTERNAL_STORAGE`. Si access aux médias devient nécessaire, ajouter les nouvelles perms granulaires.

### [IMP-003] `MANAGE_EXTERNAL_STORAGE` jamais déclaré mais dialog appelle le settings screen

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:338-384`
**Catégorie** : Manifest / Fonctionnalité
**Description** : Sur API 30+ (Android 11), `Environment.isExternalStorageManager()` est testé et un dialog dirige l'user vers `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`. **Mais** le manifest ne déclare PAS `android.permission.MANAGE_EXTERNAL_STORAGE` ⇒ même si l'user clique "Autoriser", Android refuse de donner le toggle ON.
**Reproduction** : Cold install sur Android 13 → dialog s'affiche → "Autoriser" → settings → l'app n'apparaît pas dans la liste OR le toggle ne reste pas ON.
**Risque** : Feature install-APK-via-storage cassée silencieusement.
**Recommandation** : Si la feature est utilisée, ajouter au manifest `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />` + déclarer un usage justifié au cas où Play Store. Sinon, retirer le dialog.

### [IMP-004] `forceDarkAllowed=false` mais theme parent `MaterialComponents.Light`

**Fichier** : `app/src/main/res/values/themes.xml:3,17`
**Catégorie** : UI / Cohérence
**Description** : Le theme `Theme.BlackBox` étend `Theme.MaterialComponents.Light.NoActionBar` (= light theme) mais désactive forceDark, ET l'UI réelle est dark (`#0D0B14`, `#110F1D`). Les composants Material par défaut (snackbar, dialog si fallback) peuvent s'afficher en blanc.
**Risque** : Snackbar `showOptionalUpdateSnackbar` (MainActivity.kt:172) affiche du texte blanc sur fond blanc ou backgrounds incohérents.
**Recommandation** : Étendre `Theme.MaterialComponents.NoActionBar` (le dark par défaut) ou définir tous les colors explicitement.

### [IMP-005] `SlotCardAdapter.notifyDataSetChanged()` au lieu de `DiffUtil`

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/SlotCardAdapter.kt:54-58,66-69`
**Catégorie** : Performance
**Description** : `setSlots()` fait `clear() + addAll() + notifyDataSetChanged()`. Sur 5+ slots, refresh = full rebind de toutes les cards (chacune coûteuse : lit FingerprintManager, charge la liste d'apps, etc.).
**Risque** : Latence visible quand un slot est créé/supprimé.
**Recommandation** : Remplacer par `ListAdapter` + `DiffUtil.ItemCallback<SlotData>` (compare userId).

### [IMP-006] `SlotCardAdapter.loadApps` synchrone sur main thread

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/SlotCardAdapter.kt:219-228`
**Catégorie** : Performance / Fiabilité
**Description** : `BlackBoxCore.get().getInstalledApplications(0, userId)` est appelée depuis `onBindViewHolder` (main thread). Pour 10+ apps installées, c'est une roundtrip Binder + désérialisation Parcelable → 50-300ms par slot.
**Risque** : Lag visible au scroll/rebind. Devient pire si l'user installe beaucoup d'apps.
**Recommandation** : `loadApps` doit retourner une `LiveData`/`Flow` populée en background ; `onBindViewHolder` bind un placeholder + observe l'update.

### [IMP-007] `setHasFixedSize(false)` sur le RecyclerView principal

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:210`
**Catégorie** : Performance
**Description** : `setHasFixedSize(false)` force RV à remesurer à chaque modification adapter. Pour une liste verticale de slots dont chaque card a une hauteur identique, on peut mettre `true`.
**Recommandation** : `setHasFixedSize(true)`.

### [IMP-008] `onResume` re-déclenche update + news check à chaque retour foreground

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:100-105`
**Catégorie** : Performance / Réseau
**Description** : Pas de cache TTL sur `UpdateChecker.check` ou `NewsApi.fetch`. Un user qui switch Phantom ↔ Vinted en boucle (UX typique) déclenche `/api/version` + `/api/news` à chaque retour.
**Risque** : Battery + 4G data wasted + serverside load inutile.
**Recommandation** : Cacher les deux résultats en mémoire avec un TTL (15min update, 5min news). `if (now - lastFetched < TTL) return cached`.

### [IMP-009] `LicenseGuard.isValid` re-fait un appel réseau à chaque cold start, même si le local Ed25519 est valide

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:59-78`
**Catégorie** : Performance / UX
**Description** : Tant qu'il y a internet, l'online verify est obligatoire à chaque boot. Pour un user qui boot 10× dans la journée (UX typique), c'est 10 appels réseau. La conception le veut (pour catcher revocation au plus vite) mais 1 appel par session devrait suffire vu qu'un watchdog tourne ensuite à 60s.
**Recommandation** : Si `now - lastVerifiedAt < 5min`, skip l'online verify au boot. Le watchdog backfillera dans la minute.

### [IMP-010] `NewsBanner.dismissedIds` non thread-safe

**Fichier** : `app/src/main/java/com/phantom/app/news/NewsBanner.kt:27-29`
**Catégorie** : Fiabilité
**Description** : `MutableSet<String>` mutable static, accédée depuis le click listener (main thread) et lue depuis `render()` (main thread). En pratique pas de race ici, mais code fragile si déplacé sur worker.
**Recommandation** : `ConcurrentHashMap.newKeySet()` ou `Collections.synchronizedSet(mutableSetOf())`.

### [IMP-011] `LicenseStorage.prefs` peut crash sur attempted-write avant init si EncryptedSP a planté en attente

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseStorage.kt:32-58`
**Catégorie** : Fiabilité
**Description** : Si `EncryptedSharedPreferences.create()` throw, le fallback se déclenche dans le synchronized — OK. Mais si Crypto plante de manière intermittente (ROM exotique), le pattern `prefs?.let { return it }` re-tente à chaque appel. Pas un bug en soi, mais le log error peut être bruyant.
**Recommandation** : Cache aussi le "fallback used" pour ne pas spam le log.

### [IMP-012] `LicenseGuard.hasInternet` exige `NET_CAPABILITY_VALIDATED`

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:229-236`
**Catégorie** : Fiabilité / Edge case
**Description** : `NET_CAPABILITY_VALIDATED` n'est `true` qu'après qu'Android ait pingué generate_204. Sur certains réseaux captifs ou pendant les 10s qui suivent la connexion wifi, ce flag est `false` ⇒ `hasInternet=false` ⇒ fallback grace, alors qu'en réalité on a un internet OK.
**Risque** : Cold start juste après reconnexion wifi : online verify skipé sans raison.
**Recommandation** : Tenter d'abord avec `NET_CAPABILITY_INTERNET` only, et si le call échoue retomber sur grace. C'est plus tolérant.

### [IMP-013] `Watchdog` polls toutes les 60s même en doze/idle

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseWatchdog.kt:46-71`
**Catégorie** : Performance / Battery
**Description** : 60s interval = 60 calls/heure si l'user laisse Phantom au premier plan. Pas grave en pratique mais inutile.
**Recommandation** : Backoff exponentiel ou interval plus large (5-10min). Le watchdog est de la sécurité défensive, pas un kill-switch instantané.

### [IMP-014] Coroutine launched dans `LicenseWatchdog` ne survit pas à un kill mid-call, mais ré-attribué via WeakReference seulement

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseWatchdog.kt:30-32,84-102`
**Catégorie** : Fiabilité
**Description** : `WeakReference<Activity>` ⇒ après finish/GC, `kickToActivation` log "activity ref gone". Mais la `Job` continue tant que `stop()` n'est pas appelée. Si l'activity meurt sans onPause (force-kill via task switcher), watchdog tourne dans le vide.
**Risque** : Mineur (process meurt avec Activity dans ce cas).
**Recommandation** : OK, mais documenter le pattern. Alternative : appli LifecycleObserver process-wide via `ProcessLifecycleOwner`.

### [IMP-015] Pas de check `Activity.isFinishing` dans `LicenseGuard.activate` après suspension

**Fichier** : `app/src/main/java/com/phantom/app/ui/ActivationActivity.kt:117-139`
**Catégorie** : Fiabilité
**Description** : Si l'user back-pré pendant les ~5s du `activate()`, l'Activity est finishing mais la coroutine continue. À la fin elle fait `startActivity(MainActivity)` ⇒ peut lever ou bypass des state checks.
**Risque** : Crash mineur ou navigation incorrecte.
**Recommandation** : `if (!isFinishing) startActivity(...)` après `setBusy(false)`.

### [IMP-016] `LicenseParser.FORMAT_REGEX` ne tolère pas les caractères Crockford-normalisés (I→1, O→0)

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseParser.kt:19-27`
**Catégorie** : Fiabilité / UX
**Description** : La regex impose `[0-9A-HJKMNPQRSTVWXYZ]{5}` — exclut donc I, L, O, U. Or `CrockfordBase32.decode` les tolère et les remappe. Si l'user tape une licence avec un "O" (le visual du zéro), la regex rejette avant qu'on essaie le decode.
**Risque** : User saisit lentement, prend un O pour un 0, voit "Format invalide" sans comprendre pourquoi.
**Recommandation** : Soit normaliser I→1, L→1, O→0, U→V dans `normalize()` avant le regex match (le code Crockford le fait déjà à la décode, autant le faire avant le check format). Soit accepter [0-9A-Z] au format check et laisser CrockfordBase32 trancher.

### [IMP-017] `CrockfordBase32.decode` accepte des longueurs aberrantes silencieusement

**Fichier** : `app/src/main/java/com/phantom/app/license/CrockfordBase32.kt:34-59`
**Catégorie** : Fiabilité / Robustesse
**Description** : `decode` ne valide pas la longueur — il décode N chars en `ceil(N*5/8)` bytes. Si quelqu'un passe une chaîne de 30 chars, il obtient 19 bytes au lieu des 13 attendus. Le caller (LicenseParser) check `stripped.length == 20` avant, donc OK en pratique, mais le contrat de decode est fragile pour usage externe.
**Recommandation** : Documenter ou ajouter un `require(s.length in 0..256)` minimum.

### [IMP-018] `CrockfordBase32.decode` overflow buffer Int sur entrées longues

**Fichier** : `app/src/main/java/com/phantom/app/license/CrockfordBase32.kt:38-52`
**Catégorie** : Fiabilité / Confiance : moyenne
**Description** : `buffer = (buffer shl 5) or value`. Buffer est Int (32 bits). En théorie après accumulation `bitsInBuffer` ne dépasse jamais 12 (`< 8 + 5`), donc le shift left n'overflow pas. Mais le code n'a aucun masquage et ne défensive-clamp. Si jamais (régression future) `bitsInBuffer` dérive, des bits "fantômes" du passé peuvent leak dans le byte courant.
**Risque** : Faible mais code fragile.
**Recommandation** : Masquer après l'écriture du byte : `buffer = buffer and ((1 shl bitsInBuffer) - 1)`. Idem à la sortie de la boucle.

### [IMP-019] Manifest n'a pas `tools:targetApi` sur `networkSecurityConfig`

**Fichier** : `app/src/main/AndroidManifest.xml:23`
**Catégorie** : Manifest / Compat
**Description** : `networkSecurityConfig` est dispo depuis API 24. minSdk=21 ⇒ sur API 21-23 cet attribut est ignoré silencieusement. Pas critique car on est en HTTPS, mais à noter.
**Recommandation** : Bumper `minSdk` à 24 (le moteur BlackBox supporte de toute façon mal API 21-22 vu les hooks utilisés), ou ajouter `tools:targetApi="24"`.

### [IMP-020] App.attachBaseContext fait un `mContext = base!!` non-null asserted

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/app/App.kt:40`
**Catégorie** : Fiabilité
**Description** : `base!!` ⇒ NPE si Android passe null (extrêmement rare mais possible sur instrumentation tests ou tearDown OS). Le `if (base != null) mContext = base` du catch suggère que l'auteur sait que c'est possible.
**Recommandation** : `base?.let { mContext = it } ?: return`. Le code après dépend de mContext, donc bailout proprement.

### [IMP-021] `AppManager.mBlackBoxLoader` lazy fallback "retry" est mort-né

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/app/AppManager.kt:14-21`
**Catégorie** : Code quality / Bug
**Description** :
```kotlin
val mBlackBoxLoader by lazy {
    try {
        BlackBoxLoader()
    } catch (e: Exception) {
        Log.e(TAG, "Error creating BlackBoxLoader: ${e.message}")
        BlackBoxLoader()  // <-- retry, sans aucune chance d'avoir un état différent
    }
}
```
Le "retry" en cas d'exception re-throw immédiatement si le constructeur est déterministe (généralement le cas). C'est du flair, pas de la robustesse.
**Recommandation** : Soit ne pas retry, soit retry avec une stratégie qui change quelque chose (ex. reset un singleton). Sinon supprimer.

### [IMP-022] `WelcomeActivity` n'attend pas que `BlackBoxCore` soit prêt

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/WelcomeActivity.kt:43-72`
**Catégorie** : Fiabilité / Race
**Description** : `previewInstalledAppList()` appelle `ListViewModel.previewInstalledList()` qui touche BlackBoxCore. Si BlackBox est encore en initialisation (App.onCreate pas terminé), peut throw silencieusement (catch broad).
**Risque** : Cache non chauffé ⇒ first scroll lent. Pas bloquant.
**Recommandation** : Attendre `BlackBoxCore.get().isInitialized` (s'il existe) ou retry après un delay.

### [IMP-023] Layouts XML : `tools:ignore="HardcodedText"` sur strings français

**Fichier** : `app/src/main/res/layout/activity_main.xml:41,57,88,91`, `activity_activation.xml`, `activity_force_update.xml`, etc.
**Catégorie** : Code quality / i18n
**Description** : Tous les textes UI sont hardcoded en français dans les layouts ("Ma licence", "Nouveau slot", "Mise à jour disponible"). Pas de support i18n.
**Risque** : Impossible de traduire si Mathis veut un user anglophone (un copain à l'étranger).
**Recommandation** : Extraire toutes les strings dans `strings.xml`. Si EN n'est pas prévu c'est OK mais au moins centralisé pour pouvoir changer rapidement.

### [IMP-024] `Activity_main.xml` charge `logo_phantom.png` à hauteur 112dp depuis un seul density bucket

**Fichier** : `app/src/main/res/layout/activity_main.xml:33-41`
**Catégorie** : Performance / UI
**Description** : `git status` montre que `drawable-hdpi/logo_phantom.png`, `drawable-mdpi/...`, `drawable-xhdpi/...`, `drawable-xxhdpi/...` ont été DELETED, seul `drawable/logo_phantom.png` reste. Sur des devices haute densité (xxxhdpi), Android upscale le bitmap → flou + RAM gaspillée.
**Risque** : Sur des Pixel 8 / S23, le logo est flou ou pèse 3MB en mémoire.
**Recommandation** : Restaurer les variants HDPI/XHDPI/XXHDPI/XXXHDPI, ou mieux : convertir en VectorDrawable si c'est un logo monochrome/simple.

### [IMP-025] `CodeRainView` re-démarre l'animator sur chaque `onVisibilityChanged(VISIBLE)`

**Fichier** : `app/src/main/java/com/phantom/app/ui/CodeRainView.kt:93-111`
**Catégorie** : Performance / Battery
**Description** : `onAttachedToWindow + onVisibilityChanged` ⇒ trois callbacks redondants qui peuvent toggler l'animator. En cas de bug Android (changement de visibility en cascade), peut spinner.
**Risque** : Faible. CPU spike marginal.
**Recommandation** : Centraliser via une méthode privée `setAnimating(Boolean)` idempotente.

### [IMP-026] `ProGuard` ne strip pas les logs en release

**Fichier** : `app/proguard-rules.pro`
**Catégorie** : Sécurité / Performance
**Description** : Cf. REL-010 + SEC-002. Aucune règle `-assumenosideeffects` sur `Log.d`/`Log.v`.
**Recommandation** :
```
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
-assumenosideeffects class top.niunaijun.blackbox.utils.Slog {
    public static *** d(...);
}
```
ATTENTION : tester en release après — quelques rares logs peuvent avoir des side effects (rare en pratique).

### [IMP-027] Aucune protection contre clic-spam sur "Activate"

**Fichier** : `app/src/main/java/com/phantom/app/ui/ActivationActivity.kt:104-140`
**Catégorie** : Fiabilité / UX
**Description** : `setBusy(true)` désactive le bouton, mais le `setBusy(false)` est dans le `lifecycleScope.launch`. Si l'user tap pendant que `setBusy(true)` n'a pas encore re-render (latence de frame), peut déclencher deux calls.
**Risque** : Très faible en pratique. Deux POST `/api/verify` au lieu d'un.
**Recommandation** : Ajouter un `@Volatile var activateInFlight = false` guard.

### [IMP-028] `LicenseInfoActivity.confirmDeactivate` ne kill pas non plus les slots

**Fichier** : `app/src/main/java/com/phantom/app/ui/LicenseInfoActivity.kt:70-88`
**Catégorie** : Fiabilité / Licence
**Description** : Cf. REL-005 + REL-008. `clearLicense` puis `startActivity(ActivationActivity)` + `finishAffinity`. Slots virtualisés continuent. L'user a "deactivé" mais Vinted tourne encore dans `:p0`.
**Recommandation** : Killer tous les slot processes avant `finishAffinity`.

### [IMP-029] `lifecycleScope.launch` dans `ActivationActivity` sans `Dispatchers.IO` pour OkHttp

**Fichier** : `app/src/main/java/com/phantom/app/ui/ActivationActivity.kt:117-119`
**Catégorie** : Performance / Threading
**Description** : `lifecycleScope.launch { LicenseGuard.activate(...) }`. `LicenseGuard.activate` appelle `LicenseApi.verifyOnline` qui fait `withContext(Dispatchers.IO)` ✓ donc le bloc réseau est sur IO. Mais `Ed25519Verifier.verify` + `LicenseStorage.saveActivation` (qui peut init EncryptedSP, ~200ms) restent sur le dispatcher par défaut = Main. Pas dramatique mais visible.
**Recommandation** : Lancer tout `activate` sur `Dispatchers.IO` et switcher Main uniquement pour le UI feedback.

### [IMP-030] Force-unwrap potentiel dans `LicensePayload.fromJson`

**Fichier** : `app/src/main/java/com/phantom/app/license/LicensePayload.kt:42-55`
**Catégorie** : Fiabilité
**Description** : `obj.getString("license_id")` throw `JSONException` si manquant. C'est catché par `fromJsonOrNull` ✓ mais Log.e affiche "fromJson failed: $message" — message peu informatif. L'utilisateur ne sait pas quel field manquait.
**Recommandation** : Log le JSON tronqué (premiers 200 chars) + le field qui a échoué.

### [IMP-031] `LicenseGuard.activate` traite l'absence de `signature_hex` sur valid=true comme "réponse incomplète" mais accepte un payload partiel

**Fichier** : `app/src/main/java/com/phantom/app/license/LicenseGuard.kt:137-145`
**Catégorie** : Fiabilité / Sécurité
**Description** : Check `payload == null || signatureHex == null` ⇒ failure. Mais ne check pas les champs payload individuels (license_id non vide, expires_at > issued_at, email valide). Un bug serveur (ou MITM) peut produire un payload avec `expires_at=0, email=""` qui passe le check de présence puis tente la verify Ed25519 — la signature serait peut-être bonne pour ce payload trivial.
**Risque** : Faible avec un serveur sain. Faible défense en profondeur.
**Recommandation** : Sanity check des champs payload avant save : `expires_at > issued_at && expires_at > now && email.contains("@") && license_id.isNotBlank()`.

### [IMP-032] `Snackbar.make` peut crash si `viewBinding.root` n'a pas le bon parent

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:171-187`
**Catégorie** : Fiabilité
**Description** : `viewBinding.root` = `CoordinatorLayout` ⇒ Snackbar fonctionne. Catché par try/catch mais en cas d'échec aucune fallback ⇒ silently swallow.
**Recommandation** : Fallback sur `Toast` si Snackbar fail.

### [IMP-033] `MainActivity.checkVpnPermission` demande la perm VPN à chaque cold start

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:389-397`
**Catégorie** : UX
**Description** : `VpnService.prepare(this)` retourne un `Intent` si l'user n'a pas encore approuvé. Appelé inconditionnellement dans `onCreate`. Si l'user a refusé, on lui re-demande à chaque boot.
**Risque** : Spam dialogue ⇒ user désinstalle.
**Recommandation** : Persister la décision : "user has chosen to not enable VPN" → ne plus redemander avant un setting toggle explicite.

### [IMP-034] `MainActivity` toujours import `runBlocking` mais code parent `LoadingActivity`

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt:26,38`
**Catégorie** : Code quality
**Description** : `MainActivity : LoadingActivity()` ⇒ `LoadingActivity` est probablement un wrapper avec un `showLoading/hideLoading`. L'usage de `runBlocking` est anti-pattern dans une Activity moderne — surtout que `LoadingActivity` offre probablement déjà un mécanisme async via fragment ou coroutine.
**Recommandation** : Cf. REL-001. Le gate licence doit migrer vers WelcomeActivity, mais une fois là-bas, utiliser `lifecycleScope.launch` au lieu de `runBlocking`.

---

## NICE-TO-HAVE (post-launch)

### [OPT-001] `ViewPagerAdapter.kt` semble être dead code

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/view/main/ViewPagerAdapter.kt`
**Catégorie** : Code quality / Dead code
**Description** : CLAUDE.md note que l'ancien ViewPager2 a été remplacé par le RecyclerView vertical. La classe `ViewPagerAdapter` est probablement orpheline.
**Recommandation** : Vérifier les références (`grep -rn "ViewPagerAdapter" app/`) et supprimer si non utilisée.

### [OPT-002] `MathUtil.java` et `Resolution.java` sont en Java au milieu d'un module Kotlin

**Fichier** : `app/src/main/java/top/niunaijun/blackboxa/util/MathUtil.java`, `Resolution.java`
**Catégorie** : Code quality
**Description** : Incohérence stylistique.
**Recommandation** : Migrer en Kotlin si triviales. Pas urgent.

### [OPT-003] `setting.xml` (preferences) probablement obsolète

**Fichier** : `app/src/main/res/xml/setting.xml`
**Catégorie** : Code quality
**Description** : `SettingFragment` n'a pas été cité dans la modernisation UI. Peut référer à des prefs supprimées/renommées.
**Recommandation** : Audit séparé du fragment de settings.

### [OPT-004] `phantom-release.jks` est dans `.gitignore` ✓ mais aucune doc sur comment recréer le keystore

**Fichier** : `.gitignore`
**Catégorie** : Build / DR
**Description** : Si Mathis perd son MacBook, comment recréer la signature de release ? Sans le keystore, impossible de publier des updates de l'app existante (Android refuse les APK signés différemment).
**Recommandation** : `keystore.properties.template` + doc `BUILD.md` qui explique comment générer + sauvegarder le keystore en lieu sûr (1Password, etc.).

### [OPT-005] `applicationId` figé à `com.phantom.app` mais namespace Kotlin/Java = `top.niunaijun.blackboxa`

**Fichier** : `build.gradle:14,18`
**Catégorie** : Build / Cohérence
**Description** : Cosmétique mais source de confusion. Le `namespace` (= `R` class location) est différent du `applicationId` (= identité Play Store). C'est la convention NewBlackbox qui transparaît.
**Recommandation** : Vérifier que les `BuildConfig.APPLICATION_ID` usages sont OK. Documenter la dualité.

### [OPT-006] `applicationVariants.configureEach` renomme l'APK avec `versionName` mais pas versionCode

**Fichier** : `build.gradle:67-71`
**Catégorie** : Build
**Description** : `BlackBox_1.0.1_universal.apk` ne contient pas le versionCode (401). Quand on a 3 builds 1.0.1 successifs (debug, release intermediate, release final), c'est ambigü.
**Recommandation** : `outputFileName = "BlackBox_${variant.versionName}-${variant.versionCode}_${output.baseName}.apk"`.

### [OPT-007] Universal APK + ABI splits = 3 fichiers à distribuer

**Fichier** : `build.gradle:26-34`
**Catégorie** : Build / Distribution
**Description** : `universalApk true` génère un universal + un par ABI. Si Mathis distribue manuellement, c'est facile de partager le mauvais.
**Recommandation** : Si la distribution est manuelle (WhatsApp/lien direct), désactiver les splits et ne garder que `universalApk`. Sinon, doc claire sur lequel envoyer.

### [OPT-008] `compileSdk = 35` vs `targetSdk = 28`

**Fichier** : `build.gradle:9-10`
**Catégorie** : Build
**Description** : Écart énorme (35 vs 28). Cf. IMP-001.

### [OPT-009] `dependencies` listées en mix dot-style et libs-version-catalog

**Fichier** : `app/build.gradle:74-121`
**Catégorie** : Build / Cohérence
**Description** : `libs.appcompat` (TOML catalog) cohabite avec `'androidx.preference:preference-ktx:1.1.1'` (hardcoded). Maintenance plus difficile.
**Recommandation** : Migrer tout vers `libs.versions.toml`.

### [OPT-010] `androidx.preference:1.1.1` est vieux (2020)

**Fichier** : `app/build.gradle:86`
**Catégorie** : Dependencies
**Description** : Version 1.1.1 date d'avril 2020. La dernière stable est 1.2.1.
**Risque** : Bug fixes manqués, parfois compat ColorOS/MIUI.
**Recommandation** : Bump à 1.2.1.

### [OPT-011] `androidx.lifecycle:2.3.1` vieux aussi

**Fichier** : `app/build.gradle:88-90`
**Catégorie** : Dependencies
**Description** : 2.3.1 = 2021. Actuel = 2.8.x.
**Recommandation** : Bump à 2.6.2 ou 2.7.0 (compat avec compileSdk=35).

### [OPT-012] Plusieurs deps de 3rd-party très spécifiques (osmdroid, dotsindicator, SimpleSearchView, CornerLabelView)

**Fichier** : `app/build.gradle:97-104`
**Catégorie** : Dependencies / Bloat
**Description** : Si ces libs sont utilisées uniquement par des features legacy (Xposed, fake location qui n'apparaissent plus dans l'UI principale), elles gonflent l'APK pour rien.
**Recommandation** : Audit d'usage (`grep -rn "osmdroid\|dotsindicator\|SimpleSearchView\|CornerLabelView" app/src/`). Retirer si dead code.

### [OPT-013] `kotlinx-coroutines-android:1.7.3` vieux (2023)

**Fichier** : `app/build.gradle:120`
**Catégorie** : Dependencies
**Description** : Actuel = 1.8.0+ (avec better main-dispatcher loading).
**Recommandation** : Bump à 1.8.0.

### [OPT-014] `okhttp:4.12.0` est OK mais 4.x est en mode maintenance

**Fichier** : `app/build.gradle:119`
**Catégorie** : Dependencies
**Description** : OkHttp 5.x stable depuis 2024. 4.12 est la dernière 4.x mais les nouvelles features (cert transparency, etc.) ne sont qu'en 5.
**Recommandation** : Évaluer migration 5.x.

### [OPT-015] `bouncycastle:1.78` ⇒ pèse ~6MB dans l'APK

**Fichier** : `app/build.gradle:117`
**Catégorie** : Dependencies / APK size
**Description** : BouncyCastle full pour faire un Ed25519 verify. Énorme overhead. `libsodium-jni` ou `tink` (Google) pèsent ~500KB pour la même fonction.
**Recommandation** : Switcher à `com.google.crypto.tink:tink-android:1.13.0` (~600KB) ou écrire un Ed25519 verify pure-Kotlin (~100 lignes). Préférable pour anti-RE de toute façon (cf. SEC-003 recommandation native).

### [OPT-016] `dimens.xml` et `colors.xml` recouvrent l'ancien scheme (BlackBox d'origine) + nouveau (Phantom)

**Fichier** : `app/src/main/res/values/colors.xml`, `dimens.xml`
**Catégorie** : Code quality
**Description** : Couleurs et dimens probablement avec des entrées orphelines (de l'ancien design Material BlackBox).
**Recommandation** : Audit et nettoyage.

### [OPT-017] `themes.xml` style `PhantomDialogStyle` marqué "Legacy" mais conservé

**Fichier** : `app/src/main/res/values/themes.xml:37-42`
**Catégorie** : Code quality
**Description** : Le commentaire dit "kept so any straggler references still work". Si plus rien ne le référence, le supprimer.
**Recommandation** : `grep -rn "PhantomDialogStyle" app/src/` puis supprimer si 0 résultat.

### [OPT-018] Aucun test (unit, instrumentation)

**Fichier** : `app/build.gradle:82-84`
**Catégorie** : Code quality
**Description** : `testImplementation libs.junit` + `androidTestImplementation libs.espresso.core` déclarés mais aucun test écrit pour LicenseParser, CrockfordBase32, Ed25519Verifier, LicensePayload.toCanonicalJson. Pourtant ce sont les fonctions critiques.
**Recommandation** : Ajouter au moins des unit tests pour :
- `CrockfordBase32.decode/encode` round-trip
- `LicenseParser.normalize/parse` cas limites
- `LicensePayload.toCanonicalJson` matching exactement le serveur (vector tests)
- `Ed25519Verifier.verify` avec une paire connue (RFC 8032 vectors)

---

## Annexe : couverture

### Fichiers audités

**License / Crypto (priorité A)** — tous lus intégralement :
- `LicenseConfig.kt`, `LicenseGuard.kt`, `LicenseStorage.kt`, `LicenseApi.kt`,
  `LicensePayload.kt`, `LicenseParser.kt`, `CrockfordBase32.kt`,
  `Ed25519Verifier.kt`, `LicenseWatchdog.kt`

**UI Phantom (priorité B)** — lus intégralement :
- `ActivationActivity.kt`, `LicenseInfoActivity.kt`, `ForceUpdateActivity.kt`,
  `CodeRainView.kt`

**News / Update (priorité C)** — lus intégralement :
- `NewsApi.kt`, `NewsBanner.kt`, `UpdateChecker.kt`

**UI principale (priorité D)** — lus intégralement :
- `MainActivity.kt`, `WelcomeActivity.kt`, `SlotCardAdapter.kt`,
  `SlotAppAdapter.kt`, `App.kt`, `AppManager.kt`

**Proxies Bcore (priorité E)** — scan exhaustif :
- Liste complète des 80+ proxies obtenue, grep exhaustif sur
  `catch (SecurityException`, lecture intégrale de `ILocationManagerProxy`,
  `ITelephonyRegistryProxy`, `ITelephonyManagerProxy`, `IWifiManagerProxy`,
  `AndroidIdProxy`, `IXiaomiAttributionSourceProxy`.
- Audit "ne catch pas SecurityException" : 32 fichiers identifiés (cf. REL-002).

**FingerprintManager (priorité F)** — lecture des 320 premières lignes
(initialisation + générateurs critiques). Reste des 651 lignes = générateurs
de strings cohérents (TAC, OUI, profil), pas un risque connu.

**Manifest, ProGuard, Build (priorité G/H/I/J)** — lus intégralement :
- `AndroidManifest.xml`, `proguard-rules.pro`, `build.gradle`,
  `app/build.gradle`, `themes.xml`, `network_security_config.xml`, `.gitignore`

**Greps de sécurité (priorité K)** :
- `grep -rn "Log\.(d|v|i|w|e)" app/src/main/java/com/phantom` : 70+ occurrences
- `grep -rn "TODO|FIXME|XXX|HACK" app/src/ Bcore/src/` : 0 occurrence
- `grep -rn "runBlocking|GlobalScope"` : 1 occurrence critique
- `grep -rn "http://" app/src/`: 0 cleartext URL utilisé (manifest perm OK)
- `grep -rn "addJavascriptInterface|setJavaScriptEnabled"` : 2 dans
  `WebViewProxy.java` (slot-side, héritage NewBlackbox — pas un risque host)
- `git ls-files | grep secret/.env/.jks` : aucun fichier sensible commit ✓

### Fichiers NON audités (justification)

**Bcore module non-UI** : `BlackBoxCore.java`, `BActivityThread.java`,
`fake/delegate/**`, `fake/hook/**`, `proxy/**`, `core/**`, `entity/**`,
`utils/**`, `xposed/**`. Hors scope (audit pré-release UI/license, pas
moteur). Ces fichiers sont déjà éprouvés en prod (NewBlackbox upstream +
forks).

**Bcore JNI / native** : `Bcore/src/main/cpp/**`. Hors scope explicite
(CLAUDE.md : "demander avant de toucher le natif"). Les findings autour des
hooks natifs (Build.MODEL host vs slot, WiFi MAC via /sys/, etc.) sont
déjà documentés dans CLAUDE.md > "Broken / incomplete".

**`app/src/main/java/top/niunaijun/blackboxa/view/fake/**`** (FakeLocation
UI), `view/gms/**` (GMS Manager), `view/list/**` (App list), `view/apps/**`,
`view/setting/**`, `view/base/LoadingActivity.kt`, `widget/**`. Scan superficiel
seulement : pas dans le flux licence/activation/release critique. Possibles
findings code-quality similaires à OPT-003.

**`black-reflection/`, `compiler/`** : libs auxiliaires non touchées par
v1.0.1. Hors scope.

**Resources XML non-layout** : `drawable/*.xml`, `font/`, `mipmap-*`,
`anim/`, `menu/`. Pas de risque sécurité identifié à ce niveau.

### Bugs déjà connus (confirmation)

1. **ILocationManagerProxy SecurityException fix** : Vérifié dans le code
   actuel. ✓ Fix appliqué à `registerLocationListener` ET au catch global
   dans `invoke()`. Couvre tous les sub-hooks via le try/catch dans `invoke`.
   PAS un finding mais lié à [REL-002] (étendre le pattern aux 30 autres
   proxies).

2. **ITelephonyRegistryProxy SecurityException** : Confirmé non-patché.
   Cf. [REL-003].

3. **Build.MODEL host vs slot** : Confirmé. Cf. [SEC-007].

4. **ERR_CACHE_MISS Vinted post-login (Datadome)** : Non auditable sans
   reproduction en live. Hors scope code-static.

5. **CodeRainView en background** : Vérifié. `onVisibilityChanged` +
   `onDetachedFromWindow` cancel l'animator ✓ mais redondance / fragilité,
   cf. [IMP-025].

6. **LicenseWatchdog stop pendant slot** : Confirmé — c'est intentionnel
   (`MainActivity.onPause`) mais ouvre [REL-005].

7. **License storage en SharedPreferences** : Confirmé que c'est
   `EncryptedSharedPreferences` ✓ MAIS avec fallback silencieux ✗
   ([SEC-014]) ET allowBackup=true ✗ ([SEC-001]).
