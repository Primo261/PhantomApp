#include "SystemPropertiesHook.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"

#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>

// ─── Types libc (déclarés ici pour ne pas dépendre de <sys/system_properties.h>
//     qui peut varier selon la NDK et n'expose pas toujours read_callback). ──

struct prop_info;
typedef void (*prop_read_callback_fn)(void *cookie, const char *name,
                                       const char *value, uint32_t serial);

// ─── État global ─────────────────────────────────────────────────────────────

// Map des propriétés spoofées (key → value). Populée depuis Java au boot du
// slot via NativeCore.setSpoofedProperty. Lecture sur hot path (un hook
// par appel __system_property_get), écriture rare (au boot). Mutex suffit.
static std::mutex                              g_props_mutex;
static std::unordered_map<std::string, std::string> g_spoofed_props;

// prop_info synthétique : on contrôle la struct nous-mêmes pour pouvoir
// répondre à __system_property_read_callback sans toucher au format interne
// de Bionic.
struct FakePropInfo {
    std::string name;
    std::string value;
};

// Registre des FakePropInfo alloués par notre hook __system_property_find,
// indexé par pointeur. Lookup O(1) dans __system_property_read_callback pour
// distinguer "c'est l'un des nôtres" vs "c'est un prop_info Bionic réel".
static std::mutex                                              g_pis_mutex;
static std::unordered_map<const prop_info *, FakePropInfo *>   g_fake_pis;

static int g_installed = 0;

// ─── Trampolines ─────────────────────────────────────────────────────────────

static int (*orig_system_property_get)(const char *name, char *value) = nullptr;
static const prop_info *(*orig_system_property_find)(const char *name) = nullptr;
static void (*orig_system_property_read_callback)(const prop_info *pi,
                                                   prop_read_callback_fn cb,
                                                   void *cookie) = nullptr;

// ─── Helpers ─────────────────────────────────────────────────────────────────

// Cherche une valeur spoofée. Retourne true si trouvée et copie dans `out`.
static bool lookupSpoofed(const char *name, std::string &out) {
    if (name == nullptr) return false;
    std::lock_guard<std::mutex> lk(g_props_mutex);
    auto it = g_spoofed_props.find(name);
    if (it == g_spoofed_props.end()) return false;
    out = it->second;
    return true;
}

// ─── Hooks ───────────────────────────────────────────────────────────────────

// Signature ABI : int __system_property_get(const char *name, char *value)
// `value` est un buffer de PROP_VALUE_MAX (92 octets, incluant le \0).
// Retourne la longueur écrite (hors \0) ou 0 si la propriété n'existe pas.
static int new_system_property_get(const char *name, char *value) {
    std::string spoofed;
    if (lookupSpoofed(name, spoofed)) {
        // PROP_VALUE_MAX dans Bionic = 92. On clamp pour ne jamais déborder
        // même si le caller a passé un buffer de taille standard.
        const size_t kPropMax = 91;
        size_t len = spoofed.size();
        if (len > kPropMax) len = kPropMax;
        if (value != nullptr) {
            memcpy(value, spoofed.data(), len);
            value[len] = '\0';
        }
        ALOGD("SystemPropertiesHook: spoofed %s -> %s", name, spoofed.c_str());
        return static_cast<int>(len);
    }
    if (orig_system_property_get == nullptr) return 0;
    return orig_system_property_get(name, value);
}

// Signature : const prop_info* __system_property_find(const char *name)
// Si name est spoofé, on alloue un FakePropInfo et on retourne son adresse
// castée en const prop_info*. Sinon trampoline.
static const prop_info *new_system_property_find(const char *name) {
    std::string spoofed;
    if (lookupSpoofed(name, spoofed)) {
        auto *fake = new FakePropInfo{std::string(name), spoofed};
        const prop_info *handle = reinterpret_cast<const prop_info *>(fake);
        {
            std::lock_guard<std::mutex> lk(g_pis_mutex);
            g_fake_pis[handle] = fake;
        }
        ALOGD("SystemPropertiesHook: find spoofed %s -> fake_pi=%p", name, handle);
        return handle;
    }
    if (orig_system_property_find == nullptr) return nullptr;
    return orig_system_property_find(name);
}

// Signature : void __system_property_read_callback(const prop_info* pi,
//                                                   void (*cb)(void*, const char*, const char*, uint32_t),
//                                                   void* cookie)
// Si pi est dans notre registre, on appelle cb avec nos données spoofées.
// Sinon trampoline.
static void new_system_property_read_callback(const prop_info *pi,
                                                prop_read_callback_fn cb,
                                                void *cookie) {
    FakePropInfo *fake = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_pis_mutex);
        auto it = g_fake_pis.find(pi);
        if (it != g_fake_pis.end()) fake = it->second;
    }
    if (fake != nullptr) {
        if (cb != nullptr) {
            // serial=1 : champ utilisé par Bionic pour détecter les
            // changements live ; valeur arbitraire stable côté nous.
            cb(cookie, fake->name.c_str(), fake->value.c_str(), 1u);
        }
        ALOGD("SystemPropertiesHook: read_callback spoofed %s -> %s",
              fake->name.c_str(), fake->value.c_str());
        return;
    }
    if (orig_system_property_read_callback == nullptr) return;
    orig_system_property_read_callback(pi, cb, cookie);
}

// ─── Installation ────────────────────────────────────────────────────────────

static void installOne(void *handle, const char *symbol,
                       void *replacement, void **trampoline) {
    void *target = xdl_sym(handle, symbol, nullptr);
    if (target == nullptr) {
        target = xdl_dsym(handle, symbol, nullptr);
    }
    if (target == nullptr) {
        ALOGE("SystemPropertiesHook: symbol %s not found", symbol);
        return;
    }
    int rc = DobbyHook(target, replacement, trampoline);
    if (rc == 0) {
        ALOGD("SystemPropertiesHook: hooked %s at %p", symbol, target);
        g_installed++;
    } else {
        ALOGE("SystemPropertiesHook: DobbyHook failed for %s (rc=%d)", symbol, rc);
    }
}

void SystemPropertiesHook::init() {
    ALOGD("SystemPropertiesHook: Initializing system property hooks");

    void *libc = xdl_open("libc.so", XDL_DEFAULT);
    if (libc == nullptr) {
        ALOGE("SystemPropertiesHook: Failed to open libc.so");
        return;
    }

    installOne(libc, "__system_property_get",
               (void *) new_system_property_get,
               (void **) &orig_system_property_get);

    // __system_property_find et read_callback sont API 26+ (Android 8.0).
    // Sur API < 26, xdl_sym retournera nullptr et installOne loguera juste
    // "symbol not found" — comportement gracieux.
    installOne(libc, "__system_property_find",
               (void *) new_system_property_find,
               (void **) &orig_system_property_find);

    installOne(libc, "__system_property_read_callback",
               (void *) new_system_property_read_callback,
               (void **) &orig_system_property_read_callback);

    xdl_close(libc);
    ALOGD("SystemPropertiesHook: installed %d hooks", g_installed);
}

void SystemPropertiesHook::setSpoofedProperty(const char *key, const char *value) {
    if (key == nullptr || value == nullptr) return;
    std::lock_guard<std::mutex> lk(g_props_mutex);
    g_spoofed_props[std::string(key)] = std::string(value);
}
