#include "FileSystemHook.h"
#include "ProcMapsFilter.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"

#include <cstring>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>


// Number of hooks successfully installed. Logged at the end of init() so we
// can confirm in logcat ("FileSystemHook: installed N hooks") that the hooks
// are live on a fresh boot.
static int g_installed = 0;

// ─── Trampolines vers les implémentations libc originales ────────────────────

static int   (*orig_open)     (const char *pathname, int flags, ...) = nullptr;
static int   (*orig_open64)   (const char *pathname, int flags, ...) = nullptr;
static int   (*orig_openat)   (int dirfd, const char *pathname, int flags, ...) = nullptr;
static int   (*orig_openat64) (int dirfd, const char *pathname, int flags, ...) = nullptr;
static int   (*orig___open_2) (const char *pathname, int flags) = nullptr;
static FILE *(*orig_fopen)    (const char *pathname, const char *mode) = nullptr;
static FILE *(*orig_fopen64)  (const char *pathname, const char *mode) = nullptr;

// ─── Chemins bloqués (préservation du comportement antérieur) ────────────────

static bool isResourceCacheBlocked(const char *path) {
    if (path == nullptr) return false;
    return strstr(path, "resource-cache") ||
           strstr(path, "@idmap") ||
           strstr(path, ".frro") ||
           strstr(path, "systemui") ||
           strstr(path, "data@resource-cache@");
}

// /proc/self/maps doit être synthétisé en lecture seule. Toute tentative
// d'écriture est laissée au kernel (qui répondra EACCES).
static bool shouldSynthesise(const char *path, int flags) {
    if ((flags & (O_WRONLY | O_RDWR)) != 0) return false;
    return ProcMapsFilter::isSensitivePath(path);
}

static bool fopenIsReadOnly(const char *mode) {
    if (mode == nullptr) return false;
    if (mode[0] != 'r') return false;
    for (const char *p = mode; *p; ++p) {
        if (*p == '+' || *p == 'w' || *p == 'a') return false;
    }
    return true;
}

// ─── Hooks ───────────────────────────────────────────────────────────────────

static int new_open(const char *pathname, int flags, ...) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access: %s", pathname);
        errno = ENOENT;
        return -1;
    }
    if (shouldSynthesise(pathname, flags)) {
        ALOGD("FileSystemHook: Synthesising filtered maps for: %s", pathname);
        int fd = ProcMapsFilter::openFiltered(pathname);
        if (fd >= 0) return fd;
    }
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);
    return orig_open(pathname, flags, mode);
}

static int new_open64(const char *pathname, int flags, ...) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access (64): %s", pathname);
        errno = ENOENT;
        return -1;
    }
    if (shouldSynthesise(pathname, flags)) {
        ALOGD("FileSystemHook: Synthesising filtered maps (64) for: %s", pathname);
        int fd = ProcMapsFilter::openFiltered(pathname);
        if (fd >= 0) return fd;
    }
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);
    return orig_open64(pathname, flags, mode);
}

static int new_openat(int dirfd, const char *pathname, int flags, ...) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access (at): %s", pathname);
        errno = ENOENT;
        return -1;
    }
    // V1 ne traite que les paths absolus (dirfd == AT_FDCWD). Un lecteur
    // sophistiqué qui fait open("/proc/self") + openat(dirfd, "maps") passe à
    // travers — à traiter en V2 si observé en pratique.
    if (dirfd == AT_FDCWD && shouldSynthesise(pathname, flags)) {
        ALOGD("FileSystemHook: Synthesising filtered maps (at) for: %s", pathname);
        int fd = ProcMapsFilter::openFiltered(pathname);
        if (fd >= 0) return fd;
    }
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);
    return orig_openat(dirfd, pathname, flags, mode);
}

static int new_openat64(int dirfd, const char *pathname, int flags, ...) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access (at64): %s", pathname);
        errno = ENOENT;
        return -1;
    }
    if (dirfd == AT_FDCWD && shouldSynthesise(pathname, flags)) {
        ALOGD("FileSystemHook: Synthesising filtered maps (at64) for: %s", pathname);
        int fd = ProcMapsFilter::openFiltered(pathname);
        if (fd >= 0) return fd;
    }
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);
    return orig_openat64(dirfd, pathname, flags, mode);
}

static int new___open_2(const char *pathname, int flags) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic file access (_2): %s", pathname);
        errno = ENOENT;
        return -1;
    }
    if (shouldSynthesise(pathname, flags)) {
        ALOGD("FileSystemHook: Synthesising filtered maps (_2) for: %s", pathname);
        int fd = ProcMapsFilter::openFiltered(pathname);
        if (fd >= 0) return fd;
    }
    return orig___open_2(pathname, flags);
}

static FILE *new_fopen(const char *pathname, const char *mode) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic fopen: %s", pathname);
        errno = ENOENT;
        return nullptr;
    }
    if (fopenIsReadOnly(mode) && ProcMapsFilter::isSensitivePath(pathname)) {
        ALOGD("FileSystemHook: Synthesising filtered maps via fopen for: %s", pathname);
        FILE *fp = ProcMapsFilter::fopenFiltered(pathname);
        if (fp != nullptr) return fp;
    }
    return orig_fopen(pathname, mode);
}

static FILE *new_fopen64(const char *pathname, const char *mode) {
    if (isResourceCacheBlocked(pathname)) {
        ALOGD("FileSystemHook: Blocking problematic fopen64: %s", pathname);
        errno = ENOENT;
        return nullptr;
    }
    if (fopenIsReadOnly(mode) && ProcMapsFilter::isSensitivePath(pathname)) {
        ALOGD("FileSystemHook: Synthesising filtered maps via fopen64 for: %s", pathname);
        FILE *fp = ProcMapsFilter::fopenFiltered(pathname);
        if (fp != nullptr) return fp;
    }
    return orig_fopen64(pathname, mode);
}

// ─── Installation ────────────────────────────────────────────────────────────

static void installOne(void *handle, const char *symbol,
                       void *replacement, void **trampoline) {
    void *target = xdl_sym(handle, symbol, nullptr);
    if (target == nullptr) {
        target = xdl_dsym(handle, symbol, nullptr);
    }
    if (target == nullptr) {
        ALOGE("FileSystemHook: symbol %s not found", symbol);
        return;
    }
    int rc = DobbyHook(target, replacement, trampoline);
    if (rc == 0) {
        ALOGD("FileSystemHook: hooked %s at %p", symbol, target);
        g_installed++;
    } else {
        ALOGE("FileSystemHook: DobbyHook failed for %s (rc=%d)", symbol, rc);
    }
}

void FileSystemHook::init() {
    ALOGD("FileSystemHook: Initializing file system hooks");

    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (!handle) {
        ALOGE("FileSystemHook: Failed to open libc.so");
        return;
    }

    installOne(handle, "open",       (void *) new_open,       (void **) &orig_open);
    installOne(handle, "open64",     (void *) new_open64,     (void **) &orig_open64);
    installOne(handle, "openat",     (void *) new_openat,     (void **) &orig_openat);
    installOne(handle, "openat64",   (void *) new_openat64,   (void **) &orig_openat64);
    installOne(handle, "__open_2",   (void *) new___open_2,   (void **) &orig___open_2);
    installOne(handle, "fopen",      (void *) new_fopen,      (void **) &orig_fopen);
    installOne(handle, "fopen64",    (void *) new_fopen64,    (void **) &orig_fopen64);

    xdl_close(handle);
    ALOGD("FileSystemHook: installed %d hooks", g_installed);
}
