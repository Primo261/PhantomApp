#include "ProcMapsFilter.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

// memfd_create syscall numbers per ABI. NDK's <sys/mman.h> exposes the libc
// wrapper only from API 30+, but the kernel syscall itself is available on
// every Android version we care about (10+).
#ifndef __NR_memfd_create
#  if defined(__aarch64__)
#    define __NR_memfd_create 279
#  elif defined(__arm__)
#    define __NR_memfd_create 385
#  elif defined(__x86_64__)
#    define __NR_memfd_create 319
#  elif defined(__i386__)
#    define __NR_memfd_create 356
#  else
#    error "Unsupported architecture for memfd_create"
#  endif
#endif

namespace {

    // Tokens whose presence in a /proc/self/maps line betrays the
    // PhantomApp/BlackBox engine. Any line containing one of them is dropped
    // in its entirety. Kept in sync with FileSystemProxy.MAPS_BLACKLIST on
    // the Java side (duplication is intentional — JNI upcalls per line would
    // be far too expensive).
    const char *const kBlacklist[] = {
            "libblackbox.so",
            "dobby",
            "xdl",
            "niunaijun",
            "top.niunaijun",
            "com.phantom",
            "blackbox",
            "BlackBox",
            "phantom",
            "Phantom",
            "/data/data/com.phantom.app",
            nullptr,
    };

    bool lineContainsBlacklisted(const char *line, size_t len) {
        for (int i = 0; kBlacklist[i] != nullptr; ++i) {
            const char *needle = kBlacklist[i];
            size_t needleLen = strlen(needle);
            if (needleLen == 0 || needleLen > len) continue;
            size_t limit = len - needleLen;
            for (size_t j = 0; j <= limit; ++j) {
                if (memcmp(line + j, needle, needleLen) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // Direct openat() syscall — bypasses libc wrappers (and therefore our own
    // FileSystemHook), so the filter can safely read the real maps file
    // without triggering recursion through new_open/new_openat.
    int rawOpenReadOnly(const char *path) {
        return (int) syscall(SYS_openat, AT_FDCWD, path,
                             O_RDONLY | O_CLOEXEC, 0);
    }

    int rawMemfdCreate() {
        // "anon" rather than "maps" so readlink(/proc/self/fd/N) gives a less
        // distinctive name (/memfd:anon) if anyone inspects our FDs. Hooking
        // readlink to hide memfd entirely is left for V2.
        return (int) syscall(__NR_memfd_create, "anon", 0);
    }

}

bool ProcMapsFilter::isSensitivePath(const char *path) {
    if (path == nullptr) return false;
    if (strcmp(path, "/proc/self/maps") == 0) return true;
    if (strcmp(path, "/proc/self/smaps") == 0) return true;

    // Also catch the form /proc/<getpid()>/maps — a sophisticated reader can
    // call getpid() and bypass /proc/self/ entirely.
    pid_t pid = getpid();
    char own[64];
    snprintf(own, sizeof(own), "/proc/%d/maps", pid);
    if (strcmp(path, own) == 0) return true;
    snprintf(own, sizeof(own), "/proc/%d/smaps", pid);
    if (strcmp(path, own) == 0) return true;

    return false;
}

int ProcMapsFilter::openFiltered(const char *originalPath) {
    int realFd = rawOpenReadOnly(originalPath);
    if (realFd < 0) {
        return -1;
    }

    size_t capacity = 64 * 1024;
    size_t length = 0;
    char *buf = (char *) malloc(capacity);
    if (buf == nullptr) {
        close(realFd);
        errno = ENOMEM;
        return -1;
    }

    while (true) {
        if (length + 4096 > capacity) {
            size_t nextCap = capacity * 2;
            char *next = (char *) realloc(buf, nextCap);
            if (next == nullptr) {
                free(buf);
                close(realFd);
                errno = ENOMEM;
                return -1;
            }
            buf = next;
            capacity = nextCap;
        }
        ssize_t r = read(realFd, buf + length, 4096);
        if (r < 0) {
            if (errno == EINTR) continue;
            int saved = errno;
            free(buf);
            close(realFd);
            errno = saved;
            return -1;
        }
        if (r == 0) break;
        length += (size_t) r;
    }
    close(realFd);

    // Allocate the filtered buffer to the same capacity — filtering can only
    // shrink the content.
    char *filtered = (char *) malloc(length + 1);
    if (filtered == nullptr) {
        free(buf);
        errno = ENOMEM;
        return -1;
    }
    size_t filteredLen = 0;
    size_t i = 0;
    while (i < length) {
        size_t lineStart = i;
        while (i < length && buf[i] != '\n') i++;
        size_t lineLen = i - lineStart;
        if (i < length) i++;  // consume '\n'
        if (!lineContainsBlacklisted(buf + lineStart, lineLen)) {
            memcpy(filtered + filteredLen, buf + lineStart, lineLen);
            filteredLen += lineLen;
            filtered[filteredLen++] = '\n';
        }
    }
    free(buf);

    int memFd = rawMemfdCreate();
    if (memFd < 0) {
        int saved = errno;
        free(filtered);
        errno = saved;
        return -1;
    }

    size_t written = 0;
    while (written < filteredLen) {
        ssize_t w = write(memFd, filtered + written, filteredLen - written);
        if (w < 0) {
            if (errno == EINTR) continue;
            int saved = errno;
            free(filtered);
            close(memFd);
            errno = saved;
            return -1;
        }
        written += (size_t) w;
    }
    free(filtered);

    if (lseek(memFd, 0, SEEK_SET) < 0) {
        int saved = errno;
        close(memFd);
        errno = saved;
        return -1;
    }
    return memFd;
}

FILE *ProcMapsFilter::fopenFiltered(const char *originalPath) {
    int fd = openFiltered(originalPath);
    if (fd < 0) return nullptr;
    FILE *fp = fdopen(fd, "r");
    if (fp == nullptr) {
        int saved = errno;
        close(fd);
        errno = saved;
        return nullptr;
    }
    return fp;
}
