# Release Notes - NewBlackbox

## Version: Latest Build (2026-01-31)

---

### New Features

#### VPN Network Mode Toggle
Added a new setting to choose between VPN and normal network mode for sandboxed apps.

- **Location:** Settings → Others → Use VPN Network
- **Default:** OFF (normal network mode)
- When enabled, traffic is routed through BlackBox's VPN service
- Requires app restart to take effect

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/BlackBoxLoader.kt`
- `app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt`
- `app/src/main/res/xml/setting.xml`
- `app/src/main/res/values/strings.xml`
- `Bcore/src/main/java/top/niunaijun/blackbox/app/configuration/ClientConfiguration.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java`

#### Device Information Logging
Added comprehensive device info header in logcat for easier debugging:
- Android version, SDK level, security patch
- Device manufacturer, brand, model, hardware
- Supported CPU/ABIs (32-bit and 64-bit)
- Memory info (heap usage)
- App version and package info
- Build fingerprint and timestamps

---

### Bug Fixes

#### Slot Isolation on Android 14+

- Amélioration de l'isolation des slots sur Android 14+

---

#### Stabilité des slots face aux SDK qui demandent des permissions absentes

- Les apps qui demandent des permissions non accordées (téléphonie, micro, localisation, capteurs, etc.) ne crashent plus la slot

---

#### VPN Permission Fix
**Problem:** VPN service failed to establish interface (`builder.establish()` returned null).

**Root Cause:** Android requires `VpnService.prepare()` to be called from an Activity before VPN can be established.

**Solution:** Added VPN permission request to `MainActivity.kt` on app launch.

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt`

---

#### Android 10 Black Screen Fix
**Problem:** Apps would show a black screen and timeout on Android 10 (API 29).

**Root Cause:** 
- `BRAttributionSource.getRealClass()` returns `null` on Android < 31
- `SystemProviderStub.invoke()` crashed calling `.getName()` on null class
- `ClassInvocationStub.injectHook()` crashed when `getWho()` returned null

**Solution:**
- Added null checks in `SystemProviderStub.java` for API version checks
- Added null check in `ClassInvocationStub.java` to skip hooks when services don't exist

**Files Changed:**
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/ClassInvocationStub.java`

---

### Removed Features

#### Xposed Framework Support
- Removed `BXposedManagerService` and related AIDL interfaces
- Removed "Install Xposed Module" UI and Settings entries
- Cleaned up Xposed-related flags and package checks

---

### Stability Improvements

#### Deterministic Slot Identity Derivation
**Problem:** Slot identifiers (IMEI, Android ID, WiFi MAC, Bluetooth MAC) were
generated from `new Random()` and only existed as long as their SharedPreferences
entry survived. A clear-data of PhantomApp wiped every slot's identity, breaking
already-logged-in accounts inside slots on reinstall. The generated WiFi/Bluetooth
MACs also used the locally-administered bit (`0x02` prefix), an obvious
virtualization signal for anti-fraud SDKs.

**Solution:** Refactored `FingerprintManager` to derive each value
deterministically from a persistent host anchor:
- A **host anchor** is captured once from the real device's `Settings.Secure.ANDROID_ID`
  (with a `UUID` fallback when the host ID is missing or low-entropy), and persisted.
- Each `(userId, category)` pair produces a stable 64-bit seed via
  `SHA-256(hostAnchor + "@" + userId + "@" + category)`, used to seed `java.util.Random`.
- Same inputs always produce the same identifiers — a slot whose
  SharedPreferences are wiped is regenerated identically as long as the host
  Android ID is unchanged. Friends receiving the signed APK on their own
  devices get their own independent stable identities.
- WiFi and Bluetooth MACs now draw their OUI from a per-brand table of
  real IEEE-registered vendor prefixes (Samsung, Google, Xiaomi, OnePlus,
  Motorola), aligned with the slot's `DEVICE_PROFILES` brand. The
  locally-administered bit is no longer set.
- IMEI TACs are likewise drawn from a per-brand table so a slot reporting
  brand "samsung" produces an IMEI whose TAC is registered to Samsung.

**Migration:** Existing slots are left untouched (lazy non-destructive
migration). Only new slots and missing-field regeneration use the
deterministic path, so previously-stored values for already-logged-in
accounts are preserved.

**Null-safety hardening:** The first deployment surfaced `NullPointerException`s
inside slot processes (e.g. Vinted) when reading SharedPreferences —
`Context.getApplicationContext()` can return `null` in the very early stage
of a BlackBox-spawned slot process, leaving `FingerprintManager.mContext`
unusable. Defensive guards were added across the manager:
- The constructor now falls back to the raw context when
  `getApplicationContext()` returns null, so `mContext` is never the wrong
  kind of null.
- `prefs()` re-resolves the context dynamically via `BlackBoxCore.getContext()`
  when `mContext` is null, wraps `getSharedPreferences()` in try/catch, and
  returns `null` as a last resort instead of letting the NPE propagate.
- `getOrCreate()` handles a `null` SharedPreferences by returning an
  ephemeral generated value (logged at WARN) instead of crashing — the UI
  still gets a value, and persistence happens on the next session with a
  valid context.
- `getHostAnchor()`, `captureHostAnchor()`, `isHostProcess()`,
  `getProfileIndex()`, and `resetSlot()` were similarly guarded so none of
  the derivation chain assumes `mContext != null` or `prefs() != null`.
- `MainActivity.onCreate()` now force-initialises `FingerprintManager` with
  `applicationContext` before the slot RecyclerView's first bind, fixing
  the UI-side symptom where slot cards displayed "···" because
  `FingerprintManager.get()` had returned `null` mid-init.

**Files Changed:**
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/frameworks/FingerprintManager.java`
- `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt`

---

#### Anti-Detection Native Hook Stability
- Removed `LOGD` calls from critical native hooks to prevent infinite recursion
- Fixed syntax errors in hook implementations
- Hooks now silently return `ENOENT` for blocked paths

---

### Anti-Detection

#### Native `__system_property_*` spoofing + complete `Build.*` injection
**Problem:** Native SDKs (Adjust, AppsFlyer, Castle, Firebase, anti-fraud
libraries) call `__system_property_get("ro.product.model")` etc. directly
from C, bypassing the Java `SystemPropertiesProxy` hook entirely. They
received the real Samsung A16 / MediaTek values regardless of what we
injected into `Build.*`. Additionally, only a subset of `Build.*` fields
was being injected — `BOARD`, `BOOTLOADER`, `HARDWARE`, `DISPLAY`, `HOST`,
`TAGS`, `TYPE`, `USER`, `RADIO`, `SUPPORTED_ABIS`, `VERSION.INCREMENTAL`,
`VERSION.SECURITY_PATCH` were left untouched, so a slot reporting a
spoofed `MODEL` still leaked its true MediaTek board/hardware.

**Solution:** Two coordinated changes — a new native hook layer plus a
complete `Build.*` injection table.

- **Native `SystemPropertiesHook` (V1).** New translation unit hooks
  three libc entry points via Dobby inline hooking, using the same
  `xdl_sym` + `DobbyHook` pattern as `FileSystemHook`:
    - `__system_property_get(name, value)` — the hot path, called
      thousands of times by SDKs during init. Looks up `name` in a
      process-local `std::unordered_map<std::string, std::string>`; if
      found, writes the spoofed value (clamped to 91 chars +
      `\0` per `PROP_VALUE_MAX`) and returns its length. Otherwise
      trampolines to the real symbol.
    - `__system_property_find(name)` (API 26+) — if `name` is spoofed,
      allocates a `FakePropInfo{name, value}` heap object and returns its
      address cast as `const prop_info*`; the pointer is registered in a
      second map so the matching read-callback can recognise it.
    - `__system_property_read_callback(pi, cb, cookie)` (API 26+) — looks
      up `pi` in the fake-pi registry; if matched, invokes `cb` with our
      spoofed `(name, value, serial=1)`; otherwise trampolines.
- **Push Java → native at slot init.** `AppInstrumentation.injectBuildFields`
  already runs at the earliest reliable point in a slot process
  (`callApplicationOnCreate`, before any third-party SDK `Application.onCreate`).
  After writing `Build.*` via Unsafe, it now calls
  `NativeCore.setSpoofedProperty(key, value)` for every relevant `ro.*`
  property — one JNI call per entry, fully amortised because it happens
  exactly once per slot. The native hook map is then populated before any
  SDK reads a property. **No JNI roundtrip on hot path** — the hook just
  does an `unordered_map::find` protected by a single `std::mutex`. This
  avoids both perf cost (thousands of calls would otherwise attach the
  thread to the VM repeatedly) and a recursion class (a Java call from
  inside the hook could re-enter `__system_property_get` from anywhere in
  the Bionic stack and loop indefinitely).
- **Variant coverage.** For each device-identifying property (model,
  brand, manufacturer, device, name, board), the corresponding
  `ro.product.*`, `ro.product.odm.*`, `ro.product.system.*`,
  `ro.product.vendor.*`, and `ro.product.system_ext.*` variants are all
  pushed. Some SDKs (especially on Android 12+) probe the partition-split
  variants first; covering only the unqualified form would leak the real
  values.
- **Extended `DEVICE_PROFILES`.** Each profile now carries 10 columns
  instead of 6: `{brand, manufacturer, model, device, product, version,
  board, hardware, bootloader, securityPatch}`. Real per-device values
  sourced from public `build.prop` dumps and GSMArena fact sheets — a
  Pixel 7 profile has `board=panther`, `hardware=panther`,
  `bootloader=cloudripper-1.0-9602082`; a Galaxy S23 Ultra has
  `board=kalama`, `hardware=qcom`, etc. All profiles are arm64-v8a
  compatible (no x86 / ARMv7-only entries that would mismatch the host
  ABI on a Samsung A16).
- **Cohérence security_patch ↔ build_fingerprint.** The fingerprint's
  `<BuildId>` portion (e.g. `UP1A.241201.NNN`) now encodes the same date
  as `VERSION.SECURITY_PATCH` (`2024-12-01` → `241201`), drawn from the
  same per-profile entry. Mismatches between fingerprint date and
  declared security patch level are a trivial anti-fraud signal — they
  cannot happen with this change.
- **Build.* extension.** `AppInstrumentation.injectBuildFields` now writes
  `BOARD`, `HARDWARE`, `BOOTLOADER`, `DISPLAY`, `HOST`, `USER`, `TAGS`,
  `TYPE`, `RADIO`, `SUPPORTED_ABIS` (String[]), `SUPPORTED_32_BIT_ABIS`,
  `SUPPORTED_64_BIT_ABIS`, `CPU_ABI`, `CPU_ABI2`, plus
  `VERSION.INCREMENTAL` and `VERSION.SECURITY_PATCH` — in addition to the
  pre-existing `BRAND` / `MANUFACTURER` / `MODEL` / `DEVICE` / `PRODUCT` /
  `SERIAL` / `FINGERPRINT` / `ID`. The previous bug where `HARDWARE` was
  set to the device codename instead of the chipset platform is fixed.
- **Installation tracking.** `SystemPropertiesHook::init()` ends with
  `ALOGD("SystemPropertiesHook: installed %d hooks", N)`, mirroring the
  existing FileSystemHook telemetry. Each interception logs
  `ALOGD("SystemPropertiesHook: spoofed %s -> %s", name, value)`.

**Properties covered (per-slot push):** `ro.product.brand`,
`ro.product.manufacturer`, `ro.product.model`, `ro.product.device`,
`ro.product.name`, `ro.product.board` (+ `.odm/.system/.vendor/.system_ext`
variants for each); `ro.product.cpu.abi`, `ro.product.cpu.abi2`,
`ro.product.cpu.abilist`, `ro.product.cpu.abilist32`,
`ro.product.cpu.abilist64`; `ro.hardware`, `ro.board.platform`,
`ro.bootloader`, `ro.boot.bootloader`; `ro.serialno`, `ro.serial`,
`ro.boot.serialno`; `ro.build.fingerprint` (+ vendor/system/odm/system_ext
variants); `ro.build.id`, `ro.vendor.build.id`, `ro.system.build.id`;
`ro.build.display.id`, `ro.build.host`, `ro.build.user`, `ro.build.tags`,
`ro.build.type`; `ro.build.version.release` (+ system/vendor variants),
`ro.build.version.incremental`, `ro.build.version.security_patch`,
`ro.vendor.build.security_patch`.

**Known V1 limitations:**
- If a third-party native SDK calls `__system_property_get` *before*
  `Application.onCreate` (would require `System.loadLibrary` in a static
  initializer of an early-touched class, rare), the table is still empty
  and the real value is returned. Identical failure mode to the existing
  Java `Build.*` injection path.
- `__system_property_find` returns a synthesised `FakePropInfo*` whose
  layout differs from Bionic's real `prop_info`. Code that dereferences
  the pointer directly instead of via `__system_property_read_callback`
  (or `__system_property_serial` / `__system_property_read`) would
  segfault. Standard libc usage goes through the read-callback API, so
  this is acceptable.

**Verification commands:**
```bash
# At slot launch — confirm hooks are installed
adb logcat -s NativeCore:D | grep "SystemPropertiesHook: installed"

# During SDK init inside a slot — see live interceptions
adb logcat | grep "SystemPropertiesHook: spoofed"

# Validate cross-layer coherence inside a slot via DevCheck:
# Build.BOARD, Build.HARDWARE, Build.SUPPORTED_ABIS, Build.VERSION.SECURITY_PATCH
# must all match the device profile shown in PhantomApp's slot card.
```

**Files Changed:**
- `Bcore/src/main/cpp/Hook/SystemPropertiesHook.h` (new — interface)
- `Bcore/src/main/cpp/Hook/SystemPropertiesHook.cpp` (new — Dobby hooks +
  thread-safe spoofed-props table + fake-pi registry)
- `Bcore/src/main/cpp/BoxCore.cpp` (wires `SystemPropertiesHook::init()`
  into `nativeHook()` after `FileSystemHook::init()`; registers JNI
  `setSpoofedProperty` in `gMethods`)
- `Bcore/src/main/cpp/Android.mk` (adds `Hook/SystemPropertiesHook.cpp`
  to `LOCAL_SRC_FILES`)
- `Bcore/src/main/java/top/niunaijun/blackbox/core/NativeCore.java`
  (declares `public static native void setSpoofedProperty(String, String)`)
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/frameworks/FingerprintManager.java`
  (extends `DEVICE_PROFILES` to 10 columns; adds `getBoard`,
  `getHardware`, `getBootloader`, `getSecurityPatch`, `getIncremental`,
  `getDisplay`, `getSupportedAbis`, `getTags`, `getType`, `getUser`,
  `getHost`, `getRadio`; replaces `generateBuildId` with
  `generateBuildIdAlignedTo(version, securityPatch, seed)` for
  fingerprint↔patch coherence; `resetSlot` clears the new keys)
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java`
  (`setBuildFieldUnsafe`/`setBuildFieldReflection`/`setBuildField` accept
  `Object` so they handle `String[]` for ABI arrays; `injectBuildFields`
  injects all new Build.* + version fields and pushes ~40 `ro.*`
  properties to the native table via `pushPropAndVariants` /
  `pushPropSimple`)

---

#### `/proc/self/maps` native filtering
**Problem:** Anti-fraud SDKs (commonly bundled by apps like Vinted) detect a
virtualised environment by reading `/proc/self/maps` and looking for tokens
such as `libblackbox.so`, `/data/data/com.phantom.app/`, `dobby`, or
`niunaijun`. Previously, only `Runtime.exec()` reads were filtered (via
`FileSystemProxy.Exec`); any direct `FileInputStream("/proc/self/maps")`,
`fopen()` from a native library, or `open()` syscall returned the unredacted
contents and immediately exposed the engine. Worse, `FileSystemHook::init()`
was retrieving libc symbol pointers but **never actually installing the
hooks via Dobby**, so even the `resource-cache` / `@idmap` blocking it
advertised was inert.

**Solution:** `FileSystemHook` is now wired through `DobbyHook` (the
mechanism already used by `Utils/VirtualSpoof.cpp`) on a broader set of libc
entry points, and a new module `ProcMapsFilter` synthesises a scrubbed
version of the maps file on demand.

- Hooked syscalls (libc entry points): `open`, `open64`, `openat`,
  `openat64`, `__open_2`, `fopen`, `fopen64`. Each one is replaced via
  Dobby inline hooking; the original is preserved as a trampoline so the
  fall-through path still works for unrelated files.
- Recognised paths: `/proc/self/maps`, `/proc/self/smaps`,
  `/proc/<getpid()>/maps`, `/proc/<getpid()>/smaps`. The PID form is
  resolved at call time so a reader that opens its own PID directly
  (rather than via `self`) is still caught.
- Synthesis: the real file is read using a direct `SYS_openat` syscall
  (bypassing our own hooks to avoid recursion), filtered line-by-line
  against a blacklist, written to a `memfd_create` FD named `"anon"`
  (rather than `"maps"` to reduce signature when readlink-ed via
  `/proc/self/fd/<n>`), and returned. No content is ever written to
  disk.
- Blacklist (kept symmetric with `FileSystemProxy.MAPS_BLACKLIST` on the
  Java side): `libblackbox.so`, `dobby`, `xdl`, `niunaijun`,
  `top.niunaijun`, `com.phantom`, `blackbox`, `BlackBox`, `phantom`,
  `Phantom`, `/data/data/com.phantom.app`.
- Write opens (`O_WRONLY` / `O_RDWR`, or `fopen` modes containing `w`,
  `a`, `+`) are left to fall through to the real syscall so the kernel
  can reject them with `EACCES` as usual.
- Installation tracking: `init()` ends with
  `ALOGD("FileSystemHook: installed %d hooks", N)` so a fresh logcat
  capture at boot can confirm the hooks are live.

**Known V1 limitation:** `openat()` with `dirfd != AT_FDCWD` is not
synthesised — an attacker who opens `/proc/self` as a directory FD and
then `openat(dirfd, "maps", ...)` bypasses the filter. Left for V2 if
observed in practice (would require resolving the dirfd via
`readlink(/proc/self/fd/<dirfd>)`).

**Files Changed:**
- `Bcore/src/main/cpp/Hook/FileSystemHook.cpp` (full rewrite — real
  `DobbyHook` installation + extended syscall coverage)
- `Bcore/src/main/cpp/Hook/ProcMapsFilter.h` (new)
- `Bcore/src/main/cpp/Hook/ProcMapsFilter.cpp` (new — memfd-backed
  filtered maps synthesis)
- `Bcore/src/main/cpp/Android.mk` (registered the new translation unit)
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/FileSystemProxy.java`
  (extended `MAPS_BLACKLIST` for symmetry with the native side)

---

### Known Issues

#### Oppo/ColorOS Thermal Stats Error
On Oppo/ColorOS devices, you may see errors like:
```
OppoThermalStats: PackageManager$NameNotFoundException: top.niunaijun.blackboxa:p0
```
**This is harmless** - it's an Oppo system bug where their thermal management incorrectly uses process names (with `:p0` suffix) instead of package names. The app works normally.

---

### Compatibility

| Android Version | Status |
|-----------------|--------|
| Android 10 (Q)  | ✅ Fixed |
| Android 11 (R)  | ✅ Supported |
| Android 12 (S)  | ✅ Supported |
| Android 13 (T)  | ✅ Supported |
| Android 14 (U)  | ✅ Supported |
| Android 15+     | ✅ Supported |
