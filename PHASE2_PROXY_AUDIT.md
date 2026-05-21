# Audit Phase 2 — *Proxy.java SecurityException coverage

**Pattern de référence**: `ILocationManagerProxy.java` lignes 49-92 — try englobant `super.invoke(...)`, catch `Throwable` + `isSecurityException(t)` cause-walk, `safeDefault(returnType)`.

> Note importante : ton prompt décrivait un `catch (SecurityException e)` direct, mais le code réel sur `ILocationManagerProxy` walks la cause chain via `isSecurityException()` (lignes 82-92). C'est nécessaire pour attraper les `SecurityException` wrappées dans `InvocationTargetException` (cas réflexion). Je propose d'appliquer le pattern code-réel partout.

**Anomalie collatérale détectée**: `ILocationManagerProxy.java` contient 1 NULL byte (probablement introduit par un éditeur lors du fix Phase 1). Git le voit comme binaire (`Bin 8137 -> 10220` dans le diff stat). Le compilateur l'accepte mais c'est de la dette : grep le skip par défaut. À nettoyer en marge de Phase 2 (1 commande `tr -d '\000'`).

**Hiérarchie des classes de base** :
- `ClassInvocationStub.invoke()` (base ultime) : appelle `method.invoke(mBase, args)` et **rethrow le cause sans filet**. Aucun catch global.
- `BinderInvocationStub` extends `ClassInvocationStub` : pareil, pas de filet ajouté.

Donc tout proxy qui ne override PAS `invoke()` ET dont les `@ProxyMethod` inner classes forward au système est implicitement à risque. Le fix recommandé pour ceux-là = **ajouter un override `invoke()` minimal** qui délègue à `super.invoke()` dans un try/catch.

---

## Tier 1 — À MODIFIER (override `invoke()` existant, pas de catch global) — 8 files

Ces proxies overridèrent déjà `invoke()` mais le `super.invoke(...)` ou `method.invoke(...)` est appelé hors try/catch. Fix = wrap le corps existant dans le try/catch de référence.

- [ ] `IAccountManagerProxy.java`
- [ ] `IAppWidgetManagerProxy.java`
- [ ] `ILauncherAppsProxy.java`
- [ ] `INotificationManagerProxy.java`
- [ ] `IPhoneSubInfoProxy.java`
- [ ] `IShortcutManagerProxy.java`
- [ ] `IStorageStatsManagerProxy.java`
- [ ] `IVibratorServiceProxy.java`

---

## Tier 2 — À MODIFIER (pas d'override `invoke()`, forwards à des system services permission-gated) — 28 files

Ces proxies n'override pas `invoke()`. Leur logique est dans des `@ProxyMethod` inner classes qui font `method.invoke(mBase, args)` → SecurityException brute si l'host (`com.phantom.app`) n'a pas la permission. Fix = **ajouter** un override `invoke()` qui délègue à `super.invoke(...)` dans le try/catch de référence. Strictement additif — les inner classes restent intactes.

Triés par criticité observée / probabilité d'incident (Telephony en haut, REL-003 confirmé) :

- [ ] `ITelephonyRegistryProxy.java` — `registerTelephonyCallback` crash observé (REL-003, Inneractive SDK)
- [ ] `ITelephonyManagerProxy.java` — IMEI/IMSI getters, READ_PHONE_STATE gate
- [ ] `ISensorPrivacyManagerProxy.java` — REL-002 cité dans audit
- [ ] `ISystemSensorManagerProxy.java` — body sensors permission
- [ ] `AudioPermissionProxy.java` — RECORD_AUDIO sur API 33+
- [ ] `AudioRecordProxy.java` — RECORD_AUDIO native path
- [ ] `MediaRecorderProxy.java` — RECORD_AUDIO + CAMERA
- [ ] `MediaRecorderClassProxy.java` — idem
- [ ] `IAudioServiceProxy.java` — audio routing / MODIFY_AUDIO_SETTINGS
- [ ] `IMediaSessionManagerProxy.java` — MEDIA_CONTENT_CONTROL (Notification listener)
- [ ] `IMediaRouterServiceProxy.java` — BLUETOOTH_SCAN sur API 31+
- [ ] `IWifiManagerProxy.java` — CHANGE_WIFI_STATE / ACCESS_FINE_LOCATION (scan results)
- [ ] `IConnectivityManagerProxy.java` — getActiveNetworkInfo perms variées
- [ ] `INetworkManagementServiceProxy.java` — internal net mgmt
- [ ] `IDnsResolverProxy.java` — DNS query perms sur API 29+
- [ ] `IAccessibilityManagerProxy.java` — BIND_ACCESSIBILITY_SERVICE
- [ ] `IInputMethodManagerProxy.java` — INPUT_METHOD_MANAGER perms
- [ ] `IAutofillManagerProxy.java` — autofill compatibility perms
- [ ] `IJobServiceProxy.java` — RECEIVE_BOOT_COMPLETED / JobScheduler perms
- [ ] `IPermissionManagerProxy.java` — GRANT_RUNTIME_PERMISSIONS
- [ ] `IDevicePolicyManagerProxy.java` — admin perms
- [ ] `IAttributionSourceProxy.java` — API 31+ attribution chain enforcement
- [ ] `IDisplayManagerProxy.java` — internal display, perms colorimétrie
- [ ] `IWindowManagerProxy.java` — SYSTEM_ALERT_WINDOW
- [ ] `IWindowSessionProxy.java` — idem
- [ ] `IUserManagerProxy.java` — MANAGE_USERS / QUERY_USERS
- [ ] `IStorageManagerProxy.java` — MANAGE_EXTERNAL_STORAGE
- [ ] `ISettingsProviderProxy.java` — WRITE_SETTINGS / WRITE_SECURE_SETTINGS

Total Tier 1 + Tier 2 = **36 fichiers à patcher** (proche de ton estimation "~30").

---

## Déjà OK — 3 files

- `IActivityManagerProxy.java` — override invoke + try/catch SecurityException (8 mentions)
- `IAppOpsManagerProxy.java` — override invoke + try/catch SecurityException (2 mentions)
- `ILocationManagerProxy.java` — référence Phase 1, full pattern (à dénull-byter en parallèle)

---

## Couverture partielle — à étudier (5 files, hors scope batch initial)

Ces proxies catchent SecurityException dans certaines `@ProxyMethod` inner classes mais pas globalement. Ajouter un override `invoke()` ne casse rien (le catch local restera prioritaire), mais l'ROI dépend du nombre de chemins non couverts.

- `IContentProviderProxy.java` — 7 forwards, 3 catches locaux
- `IPackageManagerProxy.java` — 21 forwards, 4 catches locaux (gros proxy)
- `ISettingsSystemProxy.java` — 3 forwards, 3 catches (probable couverture totale, à vérifier)
- `IXiaomiAttributionSourceProxy.java` — 2 forwards, 1 catch
- `IXiaomiMiuiServicesProxy.java` — 8 forwards, 1 catch

Recommendation : à inclure dans Tier 2 (override invoke additif), mais après les 36 prioritaires.

---

## Pas de forward système — pas d'action requise (14 files)

Ces proxies n'appellent jamais `method.invoke(mBase, args)` : ils retournent des valeurs fixes / spoofées depuis `FingerprintManager` ou state interne BlackBox. Aucun risque SecurityException.

- `HCallbackProxy.java`
- `IAlarmManagerProxy.java`
- `IBluetoothManagerProxy.java`
- `IContextHubServiceProxy.java`
- `IDeviceIdentifiersPolicyProxy.java`
- `IFingerprintManagerProxy.java`
- `IMiuiSecurityManagerProxy.java`
- `IPersistentDataBlockServiceProxy.java`
- `IPowerManagerProxy.java`
- `ISystemUpdateProxy.java`
- `IVpnManagerProxy.java`
- `IWifiScannerProxy.java`
- `ReLinkerProxy.java`
- `StubHelper.java` (helper, pas un proxy)

---

## Forward interne / non-système (low priority) — 25 files

Ces proxies forwardent mais à des cibles non-permission-gated (filesystem dans le slot, internal BlackBox state, internal Activity manager, etc.). Le risque SecurityException existe en théorie (jamais zéro) mais aucun incident observé. À traiter en Phase 3 si nécessaire.

- `ActivityManagerCommonProxy.java`
- `AndroidIdProxy.java`
- `ApkAssetsProxy.java`
- `AuthenticationProxy.java`
- `BrowserEngineProxy.java`
- `ClassLoaderProxy.java`
- `ContentResolverProxy.java`
- `DeviceIdProxy.java`
- `FeatureFlagUtilsProxy.java`
- `FileSystemProxy.java`
- `GmsProxy.java`
- `GoogleAccountManagerProxy.java`
- `IActivityClientProxy.java`
- `IActivityTaskManagerProxy.java`
- `IGraphicsStatsProxy.java`
- `ISensitiveContentProtectionManagerProxy.java`
- `IXiaomiSettingsProxy.java` (6 catches déjà, couvert)
- `IWebViewUpdateServiceProxy.java`
- `LevelDbProxy.java`
- `MediaRecorderProxy.java` (déjà en Tier 2, à ne pas dupliquer)
- `PhantomContentProviderProxy.java`
- `ResourcesManagerProxy.java`
- `SQLiteDatabaseProxy.java`
- `SystemLibraryProxy.java`
- `SystemPropertiesProxy.java`
- `VpnCommonProxy.java`
- `WebViewFactoryProxy.java`
- `WebViewProxy.java`
- `WorkManagerProxy.java`

---

## Plan d'application proposé

Batches de 5 fichiers, dans cet ordre (du plus risqué/cité au moins) :

- **Batch 1** : ITelephonyRegistryProxy, ITelephonyManagerProxy, ISensorPrivacyManagerProxy, ISystemSensorManagerProxy, IAccountManagerProxy
- **Batch 2** : AudioPermissionProxy, AudioRecordProxy, MediaRecorderProxy, MediaRecorderClassProxy, IAudioServiceProxy
- **Batch 3** : IWifiManagerProxy, IConnectivityManagerProxy, INetworkManagementServiceProxy, IDnsResolverProxy, IMediaSessionManagerProxy
- **Batch 4** : INotificationManagerProxy, IShortcutManagerProxy, ILauncherAppsProxy, IAppWidgetManagerProxy, IAccessibilityManagerProxy
- **Batch 5** : IInputMethodManagerProxy, IAutofillManagerProxy, IJobServiceProxy, IPermissionManagerProxy, IDevicePolicyManagerProxy
- **Batch 6** : IAttributionSourceProxy, IDisplayManagerProxy, IWindowManagerProxy, IWindowSessionProxy, IUserManagerProxy
- **Batch 7** : IStorageManagerProxy, ISettingsProviderProxy, IStorageStatsManagerProxy, IVibratorServiceProxy, IMediaRouterServiceProxy
- **Batch 8** : IPhoneSubInfoProxy

Après chaque batch : `:Bcore:assembleDebug` → `:app:assembleDebug` → install → smoke test (boot slot Vinted, DevCheck cohérent, 0 crash, spoofing CPH2423 actif).

8 batches × 5 fichiers = 36 patches.
