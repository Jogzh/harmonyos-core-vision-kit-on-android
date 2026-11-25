#ifndef COREVISION_WIRE_H
#define COREVISION_WIRE_H

#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

#define CV_PARENT_ENV "COREVISION_WORKER_PARENT"

enum {
    CV_MAGIC = 0x31564341,
    CV_VERSION = 1,
    CV_PROBE = 1,
    CV_SEGMENT = 2,
    CV_FOREGROUND = 1,
    CV_COARSE = 2,
    CV_MAX_DIMENSION = 8192,
    CV_MAX_PIXELS = 12582912,
    CV_MAX_META = 32768,
    CV_INCOMPATIBLE = 4,
    CV_TIMEOUT = 6,
    CV_CPU_CONFIGURATION = 12,
    CV_SCENE_301 = 13,
    CV_SCENE_302 = 14,
};

typedef struct {
    uint32_t magic, version, id, operation, flags, width, height, stride, bytes;
} CvRequest;

typedef struct {
    uint32_t magic, version, id, status, width, height, alpha, foreground, coarse, metadata;
} CvResponse;

static inline int cv_read(int fd, void *buffer, size_t size) {
    unsigned char *destination = (unsigned char *)buffer;
    while (size != 0) {
        ssize_t count = read(fd, destination, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        destination += count;
        size -= (size_t)count;
    }
    return 1;
}

static inline int cv_write(int fd, const void *buffer, size_t size) {
    const unsigned char *source = (const unsigned char *)buffer;
    while (size != 0) {
        ssize_t count = write(fd, source, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        source += count;
        size -= (size_t)count;
    }
    return 1;
}

static inline int cv_valid(const CvRequest *request, uint32_t operation) {
    uint64_t pixels = (uint64_t)request->width * request->height;
    return request->magic == CV_MAGIC && request->version == CV_VERSION && request->id != 0 &&
        request->operation == operation && request->width > 0 && request->height > 0 &&
        request->width <= CV_MAX_DIMENSION && request->height <= CV_MAX_DIMENSION &&
        pixels <= CV_MAX_PIXELS && request->stride == request->width * 4 &&
        request->bytes == pixels * 4 &&
        (operation == CV_PROBE ? request->width == 2 && request->height == 2 && request->flags == 0
                              : (request->flags & ~(CV_FOREGROUND | CV_COARSE)) == 0);
}

static inline int cv_watch_parent(const char *parent) {
    if (parent == NULL) return 0;
    char *end = NULL;
    long expected = strtol(parent, &end, 10);
    return expected > 1 && *end == '\0' && prctl(PR_SET_PDEATHSIG, SIGKILL) == 0 && getppid() == expected;
}

static inline void cv_context(char *buffer, size_t capacity) {
    FILE *file = fopen("/proc/self/attr/current", "r");
    if (file != NULL) {
        if (fgets(buffer, (int)capacity, file) != NULL) {
            for (size_t index = 0; buffer[index] != '\0'; ++index) {
                if (buffer[index] == '\n') buffer[index] = '\0';
            }
        }
        fclose(file);
    }
}

#endif
