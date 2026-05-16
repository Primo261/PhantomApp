#ifndef PHANTOM_PROCMAPSFILTER_H
#define PHANTOM_PROCMAPSFILTER_H

#include <stdio.h>

// Filters /proc/self/maps (and /proc/<pid>/maps and the smaps variants) so a
// virtualisation-aware reader never sees libblackbox.so, the phantom data
// directory, or other tokens that betray the engine. The filter generates the
// scrubbed content in RAM (memfd_create) and hands back a normal FD/FILE*
// pointing at it, so callers can mmap/read/seek as if they had opened the
// real file.
namespace ProcMapsFilter {

    // Returns true if the path is one of the maps/smaps files we synthesise.
    // Recognises both "/proc/self/<file>" and "/proc/<getpid()>/<file>".
    bool isSensitivePath(const char *path);

    // Returns an FD on a memfd containing the filtered contents, or -1 with
    // errno set if anything failed. Caller owns the FD and must close it.
    int openFiltered(const char *originalPath);

    // Same as openFiltered() but wrapped in a FILE* for fopen() callers.
    FILE *fopenFiltered(const char *originalPath);

}

#endif
