# PhantomApp — Claude Code Guide

> Read this file first. It contains everything you need to navigate, build,
> and meaningfully contribute to PhantomApp without making the same mistakes
> we've already made.

## What this project is

PhantomApp is an Android app that runs multiple isolated instances ("slots")
of third-party apps, where each slot presents a **completely distinct device
identity** to the app installed inside it: distinct IMEI, Android ID, MAC
address, build fingerprint, etc.

It is built on top of a fork of the **NewBlackbox** virtual engine (which
itself derives from VirtualApp). No root is required; everything runs in
userspace via Java/Kotlin hooks plus a small amount of native code.

**Use case**: personal use, signed APK distribution to friends and to the
owner's other Android devices. **Not** a Play-Store product. Reliability
matters more than polish; security/anti-detection robustness matters more
than feature breadth.

**Owner**: Mathis. Primary test device: Samsung Galaxy A16, Android 16
(API 36), MediaTek Helio G85.

## Repository layout

```
PhantomApp/
├── app/                    # PhantomApp UI (Kotlin)
│   └── src/main/java/top/niunaijun/blackboxa/
│       ├── view/main/      # MainActivity, slot list, BlackBoxLoader
│       ├── view/setting/   # SettingFragment
│       └── app/            # App, AppManager
├── Bcore/                  # NewBlackbox engine (Java, mostly)
│   └── src/main/java/top/niunaijun/blackbox/
│       ├── BlackBoxCore.java
│       ├── app/configuration/ClientConfiguration.java
│       ├── fake/
│       │   ├── delegate/   # Instrumentation + Activity delegates
│       │   ├── hook/       # ClassInvocationStub and other hook plumbing
│       │   └── service/    # All the *Proxy classes (the spoofing layer)
│       │       ├── context/providers/SystemProviderStub.java
│       │       └── ...     # ITelephonyManagerProxy, IWifiManagerProxy, etc.
│       ├── proxy/          # ProxyService (process spawning)
│       └── utils/          # FingerprintManager and helpers
├── black-reflection/       # Reflection helper library
├── compiler/               # Annotation processor
├── assets/
├── Docs.md                 # API reference for BlackBoxCore
├── RELEASE_NOTES.md        # Per-version change log — keep updated
└── README.md
```

**Two package namespaces** — don't mix them up:
- `top.niunaijun.blackbox` — the engine (Bcore module)
- `top.niunaijun.blackboxa` — the PhantomApp UI (app module)

## Build, install, test

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device (assumes the A16 is plugged in via USB
# and ADB debugging is enabled)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Build signed release APK
./gradlew assembleRelease

# Clean build (use when hooks misbehave after refactors)
./gradlew clean assembleDebug
```

Required toolchain:
- JDK 17
- Android SDK 34+
- NDK 29.0.13846066 (pinned — native hooks are sensitive to NDK version)
- Android Studio Arctic Fox or newer

## The core mental model

There are **three layers** of identity in PhantomApp, and confusing them is
the single biggest source of wasted effort:

1. **Slot UI in PhantomApp** — what the user sees in the slot card. This is
   just a display of values pulled from `FingerprintManager`. Values
   appearing correctly here proves nothing about runtime spoofing.

2. **App-facing Java APIs inside the slot** — e.g. `TelephonyManager.
   getDeviceId()`, `Settings.Secure.getString(..., ANDROID_ID)`,
   `WifiInfo.getMacAddress()`. These are intercepted by the `*Proxy` classes
   in `Bcore/.../fake/service/`. This is what most third-party SDKs read.

3. **Lower-level system calls and files** — e.g. reading
   `/sys/class/net/wlan0/address` directly, calling `/proc/net/`, or hitting
   a `ContentProvider` via raw `IContentProvider` binder. Some SDKs and most
   anti-fraud libraries do this. Java-API hooks **do not** catch these; you
   need either native hooks (Dobby/xDL) or `ContentProvider`-level
   interception.

**A slot has a working spoofed identity only when all three layers agree.**

## Current state (as of last work session)

### Working ✅
- Slot creation and isolation via `BlackBoxCore.get().createUser()`
- App installation in a slot (file path or package name)
- App launch in a slot via `BlackBoxCore.get().launchApk()`
- UI rebuild with dark purple theme (`#0D0B14` / `#110F1D` / `#6C3FC7`)
- Vertical `RecyclerView` of slot cards (replaces old ViewPager2 — see
  Gotchas)
- `FingerprintManager` auto-initialises in `get()` via
  `BlackBoxCore.getContext()` — no explicit `init()` needed
- All proxy hooks (`ITelephonyManagerProxy`, `IWifiManagerProxy`,
  `ISettingsProviderProxy`, `AndroidIdProxy`, `IPhoneSubInfoProxy`,
  `IDeviceIdentifiersPolicyProxy`, `DeviceIdProxy`, `IBluetoothManagerProxy`)
  null-safe-wrapped around every `FingerprintManager.get()` call
- IMEI, MAC and Android ID display **distinct spoofed values per slot in
  PhantomApp's own UI**
- VPN network mode toggle (Settings → Others → Use VPN Network)
- Android 10 black-screen fix (null checks in `SystemProviderStub` and
  `ClassInvocationStub`)

### Broken / incomplete ❌
- **Android ID** still returns the real device value in third-party test
  apps inside slots. Requires a `ContentProvider`-level hook (Settings is
  read via `IContentProvider` binder on modern Android, not via
  `Settings.Secure.getString` directly).
- **WiFi MAC** still returns the real device value in third-party apps on
  Android 10+. On these versions, apps without `LOCAL_MAC_ADDRESS` get
  `02:00:00:00:00:00` from the Java API, but many SDKs read
  `/sys/class/net/wlan0/address` or `/proc/net/arp` directly. Needs a
  native-level hook on `open`/`openat`/`readlink` or equivalent.

## What to work on next

In priority order:

1. **Android ID via ContentProvider hook.** Find where `IContentProvider`
   calls go through in `Bcore/.../fake/service/context/providers/`, intercept
   `call()` / `query()` for the `settings` authority, and return the
   spoofed value from `FingerprintManager` when the key is `android_id`.
   Verify with DevCheck installed inside a slot.

2. **WiFi MAC via native hook.** Reuse the existing native hook
   infrastructure (Dobby/xDL — see `Bcore/src/main/cpp/`). Hook
   `open`/`openat` on `/sys/class/net/wlan0/address` and friends to return
   the slot's spoofed MAC. Note: the current "Anti-Detection Native Hook
   Stability" work removed `LOGD` calls from critical native hooks to
   prevent infinite recursion — keep that pattern.

3. **Build fingerprint and other Build.* fields.** Lower priority but
   commonly checked by anti-fraud libs.

## Test workflow (do not skip)

```bash
# 1. Build & install PhantomApp
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Open PhantomApp on device, create at least 2 slots
# 3. In each slot, install DevCheck (or any device-info app)
# 4. Open DevCheck inside slot A, screenshot the identifiers
# 5. Open DevCheck inside slot B, compare

# Useful logcat filters during testing:
adb logcat | grep -E "BlackBox|FingerprintManager|Proxy"

# Logs specific to the spoofing layer (suggested while debugging hooks):
adb logcat | grep -E "AndroidIdProxy|IWifiManagerProxy|SystemProviderStub"
```

**A fix is not a fix until DevCheck (or equivalent) inside a slot shows the
spoofed value.** PhantomApp's own UI is not a valid test surface for hook
correctness — it only proves `FingerprintManager` stores the right value.

## Conventions and patterns

- A **slot** is a BlackBox user, identified by an `int userId`. User IDs
  start at 0 and increment. Slot 0 is created on first launch.
- Every spoofed value is keyed by `userId` inside `FingerprintManager`.
- **Always** read from `FingerprintManager.get()` in proxy hooks, never
  generate identifiers ad hoc inside a hook.
- Proxy classes follow the pattern `I<ServiceName>Proxy`, sitting in
  `Bcore/.../fake/service/<service>/`. They typically extend a base
  `BinderInvocationStub` and register method-level interceptors.
- Native hooks live under `Bcore/src/main/cpp/`. They use Dobby (ARM) and
  xDL (PLT). Be very careful about logging — see Gotchas.
- Kotlin is used for the app UI; Java is used for the engine. Don't
  rewrite Bcore in Kotlin.

## Gotchas (hard-won)

These cost real time on previous sessions. Do not re-discover them.

1. **`FingerprintManager.get()` returns null in slot processes if you
   `init()` only in the host process.** BlackBox spawns isolated slot
   processes via `ProxyService`, and statics do not propagate. The
   singleton **must** auto-init in `get()` using
   `BlackBoxCore.getContext()`. Do not remove this.

2. **ViewPager2 pagination breaks `getUserId()`.** The old UI used a
   ViewPager2 of slot fragments, which caused `BActivityThread.getUserId()`
   to always return 0 — so every slot read and wrote the same fingerprint
   entries. The vertical `RecyclerView` of cards is the correct structure;
   do not regress to a pager.

3. **PhantomApp UI showing the right value ≠ working hook.** See "core
   mental model" above. Always verify with a third-party app inside a slot.

4. **`LOGD` in native hooks causes infinite recursion.** Anti-detection
   native hooks intercept file ops; logging from inside them re-enters the
   hook. Hooks must silently return their fallback (typically `ENOENT` for
   blocked paths).

5. **Android < 31 quirks.** `BRAttributionSource.getRealClass()` returns
   `null`, and some service stubs don't exist. Always null-check before
   calling `.getName()` on resolved classes, and skip the hook entirely if
   the system service doesn't exist on that API level.

6. **Oppo/ColorOS thermal noise.** `OppoThermalStats:
   PackageManager$NameNotFoundException: top.niunaijun.blackboxa:p0` is
   harmless — it's an Oppo bug that uses process names instead of package
   names. Ignore.

## Working style expectations

- **Deliver complete files, not partial diffs.** If a change requires
  touching three methods in a 600-line file, output the whole file.
- **Read related files before making changes.** When modifying a proxy
  hook, read at least one other similar proxy in the same directory to
  understand the conventions.
- **Verify empirically before claiming a fix.** Build, install, launch
  DevCheck inside a slot, check the value. Don't conclude from compilation
  success.
- **Push back on bad ideas.** If a request would regress a known-good
  pattern (e.g. "let's use ViewPager2 for the slots"), say so.
- **Update `RELEASE_NOTES.md`** when you ship a meaningful change. Match
  the existing structure (New Features / Bug Fixes / Stability / Known
  Issues).

## Useful references

- `Docs.md` — BlackBoxCore API reference (install, uninstall, list apps,
  start/stop process, UID spoofing helpers).
- `RELEASE_NOTES.md` — what changed and why, version by version.
- Upstream NewBlackbox: <https://github.com/FBlackBox/BlackBox> (commits
  on `master` are useful for context but **do not** blind-merge; this fork
  has diverged in several files).
- Waxmoon Multi App — competing commercial product, decompiled earlier as
  reference for how a polished version of this concept ships. Not in the
  repo; ask the owner if you need the decompile.

## When in doubt

Ask the owner before:
- Rearchitecting slot management or `FingerprintManager`
- Touching the native hook layer (`Bcore/src/main/cpp/`)
- Adding new third-party dependencies
- Changing the package namespaces

For everything else, default to reading the relevant files, making the
change, building, and testing on the A16 before reporting back.
