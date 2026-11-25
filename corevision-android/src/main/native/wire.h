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
    CV_DETECT_FACES = 3,
    CV_FACE_EMBEDDING = 4,
    CV_DETECT_OBJECTS = 5,
    CV_DETECT_SKELETONS = 6,
    CV_SUPER_RESOLUTION = 7,
    CV_RECOGNIZE_TEXT = 8,
    CV_IMAGE_FEATURES = 9,
    CV_TEXT_FEATURES = 10,
    CV_SR_SCALE = 4,
    CV_TEXT_DIRECTION = 1,
    CV_FOREGROUND = 1,
    CV_COARSE = 2,
    CV_SUBJECT_DETAILS = 4,
    CV_SUBJECT_COUNT_SHIFT = 8,
    CV_SUBJECT_COUNT_MASK = 31 << CV_SUBJECT_COUNT_SHIFT,
    CV_DEFAULT_SUBJECTS = 6,
    CV_MAX_SUBJECTS = 20,
    CV_MAX_DIMENSION = 8192,
    CV_MAX_PIXELS = 12582912,
    CV_MAX_META = 32768,
    CV_MAX_OCR_BYTES = 1048576,
    CV_MAX_QUERY_UNITS = 100,
    CV_MAX_QUERY_BYTES = 400,
    CV_SEARCH_FEATURES = 256,
    CV_MAX_FACES = 256,
    CV_FACE_FEATURES = 256,
    CV_MAX_DETECTIONS = 256,
    CV_SKELETON_POINTS = 17,
    CV_INVALID_INPUT = 1,
    CV_INCOMPATIBLE = 4,
    CV_TIMEOUT = 6,
    CV_CPU_CONFIGURATION = 12,
    CV_SCENE_301 = 13,
    CV_SCENE_302 = 14,
    CV_FACE_DETECTION = 15,
    CV_FACE_COMPARISON = 16,
    CV_OBJECT_DETECTION = 17,
    CV_SKELETON_DETECTION = 18,
    CV_IMAGE_SUPER_RESOLUTION = 19,
    CV_TEXT_RECOGNITION = 20,
    CV_IMAGE_SEARCH = 21,
};

typedef struct {
    uint32_t magic, version, id, operation, flags, width, height, stride, bytes;
} CvRequest;

typedef struct {
    uint32_t magic, version, id, status, width, height, payload, foreground, coarse, metadata;
} CvResponse;

typedef struct {
    float left, top, right, bottom, probability;
    int32_t landmarks[5][2];
    float yaw, pitch, roll;
    int32_t occluded;
} CvFace;

typedef struct {
    float left, top, right, bottom;
} CvBounds;

typedef struct {
    CvBounds bounds;
    float score;
    int32_t label;
} CvObject;

typedef struct {
    int32_t horizontal, vertical;
    float score;
} CvSkeletonPoint;

typedef struct {
    CvBounds bounds;
    float score;
    CvSkeletonPoint points[CV_SKELETON_POINTS];
} CvSkeleton;

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
    if (request->magic != CV_MAGIC || request->version != CV_VERSION || request->id == 0 || request->operation != operation) {
        return 0;
    }
    if (operation == CV_TEXT_FEATURES) {
        return request->width == 0 && request->height == 0 && request->stride == 0 && request->flags == request->bytes &&
            request->bytes > 0 && request->bytes <= CV_MAX_QUERY_BYTES;
    }
    uint64_t pixels = (uint64_t)request->width * request->height;
    return request->width > 0 && request->height > 0 &&
        request->width <= CV_MAX_DIMENSION && request->height <= CV_MAX_DIMENSION &&
        pixels <= CV_MAX_PIXELS && request->stride == request->width * 4 &&
        request->bytes == pixels * 4 &&
        (request->flags & ~(operation == CV_SEGMENT ? CV_FOREGROUND | CV_COARSE | CV_SUBJECT_DETAILS | CV_SUBJECT_COUNT_MASK :
                            operation == CV_RECOGNIZE_TEXT ? CV_TEXT_DIRECTION : 0)) == 0 &&
        (operation != CV_SEGMENT || (request->flags >> CV_SUBJECT_COUNT_SHIFT) <= CV_MAX_SUBJECTS) &&
        (operation != CV_SUPER_RESOLUTION || (request->width <= CV_MAX_DIMENSION / CV_SR_SCALE &&
            request->height <= CV_MAX_DIMENSION / CV_SR_SCALE && pixels <= CV_MAX_PIXELS / (CV_SR_SCALE * CV_SR_SCALE))) &&
        (operation != CV_PROBE || (request->width == 2 && request->height == 2));
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
