#include "wire.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sys/resource.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

extern "C" __attribute__((visibility("default"))) int pthread_cond_clockwait(
    pthread_cond_t *condition, pthread_mutex_t *mutex, clockid_t clock, const timespec *deadline) {
    if (deadline->tv_nsec < 0 || deadline->tv_nsec >= 1000000000L) return EINVAL;
    if (clock == CLOCK_REALTIME) return pthread_cond_timedwait(condition, mutex, deadline);
    if (clock != CLOCK_MONOTONIC) return EINVAL;

    timespec monotonic{}, realtime{};
    if (clock_gettime(CLOCK_MONOTONIC, &monotonic) != 0 ||
        clock_gettime(CLOCK_REALTIME, &realtime) != 0) return errno;
    if (deadline->tv_sec < monotonic.tv_sec ||
        (deadline->tv_sec == monotonic.tv_sec && deadline->tv_nsec <= monotonic.tv_nsec)) {
        return ETIMEDOUT;
    }
    const auto seconds = static_cast<__int128>(deadline->tv_sec) - monotonic.tv_sec + realtime.tv_sec;
    long nanos = deadline->tv_nsec - monotonic.tv_nsec + realtime.tv_nsec;
    const auto normalized = seconds + (nanos >= 1000000000L) - (nanos < 0);
    if (normalized > INT64_MAX) return EINVAL;
    nanos = (nanos + 1000000000L) % 1000000000L;
    const timespec converted{static_cast<time_t>(normalized), nanos};
    return pthread_cond_timedwait(condition, mutex, &converted);
}

extern "C" __attribute__((visibility("default"))) int OH_LOG_Print(
    int, int, unsigned int, const char *, const char *, ...) {
    return 0;
}

namespace dllite {
class FeatureInterpreter;
class AlgoManager;
class ModelConfig11p1;

struct IOTensor {
    std::string name;
    int32_t dataType;
    int32_t deviceType;
    std::vector<uint64_t> dims;
    void *data;
    uint64_t size;
};
}

namespace CVAlgo {
class SceneBase;
class SceneFactory;
class HolderBase;

struct DataCell {
    void *holder = nullptr;
    void *control = nullptr;

    DataCell() = default;
    DataCell(const DataCell &other) : holder(other.holder), control(other.control) {}
    DataCell &operator=(const DataCell &) = default;
    ~DataCell() {}
};
}

static_assert(sizeof(dllite::IOTensor) == 72 && offsetof(dllite::IOTensor, data) == 56);
static_assert(sizeof(CVAlgo::DataCell) == 16 && sizeof(std::string) == 24);
static_assert(sizeof(CvRequest) == 36 && sizeof(CvResponse) == 40);

static std::atomic<unsigned int> cpuConfigured{0};
static std::atomic<bool> cpuFailed{false};

namespace dllite::InferWrapperFactory {
__attribute__((visibility("default"))) void *OpenMindsporeCWrapper(
    const ModelConfig11p1 &config, const AlgoManager &manager, void *handle) {
    using SetDevice = int (*)(ModelConfig11p1 &, std::string &);
    using OpenWrapper = void *(*)(const ModelConfig11p1 &, const AlgoManager &, void *);
    static void *library = dlopen("libdlliteclient.so", RTLD_NOW | RTLD_NOLOAD);
    static auto set = library == nullptr ? nullptr : reinterpret_cast<SetDevice>(dlsym(library,
        "_ZN6dllite19InferWrapperFactory15SetCommonDeviceERNS_15ModelConfig11p1ERNSt4__n1"
        "12basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEE"));
    static auto open = library == nullptr ? nullptr : reinterpret_cast<OpenWrapper>(dlsym(library,
        "_ZN6dllite19InferWrapperFactory21OpenMindsporeCWrapperERKNS_15ModelConfig11p1ERKNS_11AlgoManagerEPv"));
    std::string cpu("MS_CPU");
    if (set == nullptr || open == nullptr || set(const_cast<ModelConfig11p1 &>(config), cpu) != 0) {
        cpuFailed = true;
        return nullptr;
    }
    void *wrapper = open(config, manager, handle);
    if (wrapper == nullptr) {
        cpuFailed = true;
    } else {
        ++cpuConfigured;
    }
    return wrapper;
}
}

namespace {
using Scene = std::shared_ptr<CVAlgo::SceneBase>;
using Interpreter = std::shared_ptr<dllite::FeatureInterpreter>;
using Cell = CVAlgo::DataCell;
using Cache = std::unordered_map<std::string, Cell>;
using CacheArg = std::pair<std::string, Cache *>;

constexpr size_t INTERPRETER_OFFSET = 0x3143f8;
constexpr size_t IMAGE_RESULT_CONSTRUCTOR = 0x2bde30;
constexpr size_t OUTPUT_SINK_GET = 0x2bb2ec;
constexpr size_t OUTPUT_SINK_OFFSET = 40;
constexpr size_t OUTPUT_READY_OFFSET = 360;
constexpr size_t IMAGE_RESULT_SIZE = 0x540;
constexpr size_t SEGMENT_RESULT_OFFSET = 256;

template<typename Type>
Type field(const void *object, size_t offset) {
    Type value;
    std::memcpy(&value, static_cast<const unsigned char *>(object) + offset, sizeof(value));
    return value;
}

struct Api {
    int (*initFeature)();
    CVAlgo::SceneFactory *(*getFactory)();
    Scene (*createScene)(CVAlgo::SceneFactory *, unsigned int);
    int (*initScene)(CVAlgo::SceneBase *, Interpreter, const std::string &, const std::string &,
                     const std::string &);
    int (*runScene)(CVAlgo::SceneBase *, const Cell &, const CacheArg &);
    void (*tensorHolder)(void *, dllite::IOTensor *, const std::string &, bool);
    void (*segmentHolder)(void *, void *, const std::string &, bool);
    Cell (*createCell)(CVAlgo::HolderBase *);
    void (*constructResult)(void *);
    int (*getResult)(const void *, void *);
};

template<typename Function>
void resolve(Function &function, void *library, const char *name) {
    function = reinterpret_cast<Function>(dlsym(library, name));
}

uintptr_t bind(Api &api, void *engine) {
    resolve(api.initFeature, engine, "_Z22InitFeatureInterpreterv");
    resolve(api.getFactory, engine, "_ZN6CVAlgo12SceneFactory11GetInstanceEv");
    resolve(api.createScene, engine, "_ZN6CVAlgo12SceneFactory17createWithSceneIdEj");
    resolve(api.initScene, engine, "_ZN6CVAlgo9SceneBase4InitENSt4__n110shared_ptrIN6dllite18FeatureInterpreterEEERKNS1_12basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEESD_SD_");
    resolve(api.runScene, engine, "_ZN6CVAlgo9SceneBase3RunERKNS_8DataCellERKNSt4__n14pairINS4_12basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEEPNS4_13unordered_mapISB_S1_NS4_4hashISB_EENS4_8equal_toISB_EENS9_INS5_IKSB_S1_EEEEEEEE");
    resolve(api.tensorHolder, engine, "_ZN6CVAlgo6HolderIN6dllite8IOTensorEEC2EPS2_RKNSt4__n112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb");
    resolve(api.segmentHolder, engine, "_ZN6CVAlgo6HolderINS_6SegResEEC2EPS1_RKNSt4__n112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEEb");
    resolve(api.createCell, engine, "_ZN6CVAlgo6CreateEPNS_10HolderBaseE");
    Dl_info info{};
    if (!api.initFeature || !api.getFactory || !api.createScene || !api.initScene || !api.runScene ||
        !api.tensorHolder || !api.segmentHolder || !api.createCell ||
        dladdr(reinterpret_cast<void *>(api.initFeature), &info) == 0 || !info.dli_fbase) return 0;

    const auto base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    api.constructResult = reinterpret_cast<decltype(api.constructResult)>(base + IMAGE_RESULT_CONSTRUCTOR);
    api.getResult = reinterpret_cast<decltype(api.getResult)>(base + OUTPUT_SINK_GET);
    return base;
}

[[noreturn]] void fail(int fd, const CvRequest &request, uint32_t status, const char *detail) {
    if (cpuFailed) {
        status = CV_CPU_CONFIGURATION;
        detail = "DLLite could not configure or open MS_CPU";
    }
    const auto length = static_cast<uint32_t>(strnlen(detail, CV_MAX_META));
    const CvResponse response{CV_MAGIC, CV_VERSION, request.id, status, 0, 0, 0, 0, 0, length};
    const bool sent = cv_write(fd, &response, sizeof(response)) && cv_write(fd, detail, length);
    _Exit(sent ? 0 : 4);
}

Cell imageCell(const Api &api, const CvRequest &request, uint8_t *rgba, const std::string &key) {
    auto *tensor = new dllite::IOTensor{"input pixelMap", 0, -1,
        {1, request.height, request.width, 4}, rgba, request.bytes};
    auto *holder = new unsigned char[48]{};
    api.tensorHolder(holder, tensor, key, true);
    return api.createCell(reinterpret_cast<CVAlgo::HolderBase *>(holder));
}

struct Mask {
    uint32_t width, height;
    float score;
    const uint8_t *data;
    size_t stride;
};

Mask waitForMask(int fd, const CvRequest &request, const Api &api, const Scene &scene,
                 void *result, uint32_t failure) {
    auto *sink = field<const uint8_t *>(scene.get(), OUTPUT_SINK_OFFSET);
    if (!sink) fail(fd, request, failure, "Missing output sink");
    api.constructResult(result);
    const auto *ready = reinterpret_cast<const volatile uint8_t *>(sink + OUTPUT_READY_OFFSET);
    for (unsigned int attempt = 0; *ready == 0 && attempt < 1000; ++attempt) usleep(20000);
    if (*ready == 0) fail(fd, request, CV_TIMEOUT, "Timed out waiting for a scene result");
    if (api.getResult(sink, result) != 1) fail(fd, request, failure, "Output sink rejected result");

    Mask mask{field<uint32_t>(result, 276), field<uint32_t>(result, 272), field<float>(result, 360),
              field<const uint8_t *>(result, 280), 0};
    const auto *stride = field<const size_t *>(result, 336);
    if (!mask.data || !stride || !mask.width || !mask.height ||
        mask.width > 4096 || mask.height > 4096 || !std::isfinite(mask.score)) {
        fail(fd, request, failure, "Unexpected mask layout");
    }
    mask.stride = *stride;
    if (mask.stride < mask.width * sizeof(float) || mask.stride > 65536) {
        fail(fd, request, failure, "Unexpected mask row stride");
    }
    return mask;
}

struct AxisSample {
    uint32_t left, right;
    double fraction;
};

AxisSample sampleAt(uint32_t index, uint32_t input, uint32_t output) {
    const double position = std::clamp((index + 0.5) * input / output - 0.5, 0.0, input - 1.0);
    const auto left = static_cast<uint32_t>(position);
    return {left, std::min(left + 1, input - 1), position - left};
}

uint8_t quantize(float value) {
    return static_cast<uint8_t>(std::clamp(value, 0.0f, 255.0f) + 0.5f);
}

void render(const Mask &mask, const CvRequest &request, const uint8_t *rgba,
            std::vector<uint8_t> &alpha, std::vector<uint8_t> *foreground) {
    std::vector<AxisSample> columns;
    columns.reserve(request.width);
    for (uint32_t col = 0; col < request.width; ++col) {
        columns.push_back(sampleAt(col, mask.width, request.width));
    }
    const auto interpolate = [&columns](const uint8_t *row, uint32_t col) {
        const auto &sample = columns[col];
        const float left = field<float>(row, sample.left * sizeof(float));
        const float right = field<float>(row, sample.right * sizeof(float));
        return static_cast<float>(left * (1.0 - sample.fraction) + right * sample.fraction);
    };

    for (uint32_t row = 0; row < request.height; ++row) {
        const auto vertical = sampleAt(row, mask.height, request.height);
        const auto *top = mask.data + vertical.left * mask.stride;
        const auto *bottom = mask.data + vertical.right * mask.stride;
        for (uint32_t col = 0; col < request.width; ++col) {
            const size_t pixel = static_cast<size_t>(row) * request.width + col;
            const float value = static_cast<float>(interpolate(top, col) * (1.0 - vertical.fraction) +
                                                   interpolate(bottom, col) * vertical.fraction);
            const float coverage = std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
            alpha[pixel] = quantize(coverage * 255.0f);
            if (foreground != nullptr) {
                const size_t offset = pixel * 4;
                for (size_t channel = 0; channel < 3; ++channel) {
                    (*foreground)[offset + channel] = quantize(rgba[offset + channel] * coverage);
                }
                (*foreground)[offset + 3] = alpha[pixel];
            }
        }
    }
}

struct LoadedObjects {
    std::string nativeDir;
    std::string metadata;
};

int loadedObject(dl_phdr_info *info, size_t, void *opaque) {
    const char *path = info->dlpi_name;
    if (path == nullptr || *path == '\0' || std::strcmp(path, "linux-vdso.so.1") == 0) return 0;
    auto &objects = *static_cast<LoadedObjects *>(opaque);
    if (std::strncmp(path, objects.nativeDir.c_str(), objects.nativeDir.size()) != 0) return 1;
    const char *name = path + objects.nativeDir.size();
    if (std::strchr(name, '/') != nullptr || objects.metadata.size() + std::strlen(name) + 8 > CV_MAX_META) return 1;
    objects.metadata.append("loaded=").append(name).append("\n");
    return 0;
}

int64_t milliseconds() {
    timespec current{};
    clock_gettime(CLOCK_MONOTONIC, &current);
    return static_cast<int64_t>(current.tv_sec) * 1000 + current.tv_nsec / 1000000;
}

[[noreturn]] void run(int fd, const CvRequest &request, const char *enginePath,
                      const std::string &models, const std::string &cachePath) {
    const int64_t start = milliseconds();
    std::vector<uint8_t> rgba(request.bytes);
    if (!cv_read(STDIN_FILENO, rgba.data(), rgba.size())) _Exit(2);
    void *engine = dlopen(enginePath, RTLD_NOW | RTLD_LOCAL);
    if (!engine) fail(fd, request, CV_INCOMPATIBLE, dlerror());

    Api api{};
    const auto base = bind(api, engine);
    if (!base || api.initFeature() != 0) fail(fd, request, CV_INCOMPATIBLE, "Engine ABI initialization failed");
    const auto &interpreter = *reinterpret_cast<const Interpreter *>(base + INTERPRETER_OFFSET);
    auto *factory = api.getFactory();
    if (!interpreter || !factory) fail(fd, request, CV_INCOMPATIBLE, "Missing interpreter or factory");

    Cache cache;
    const CacheArg segmentArg("backbone_640.ms", &cache);
    const CacheArg mattingArg("salient_matting", &cache);
    const Scene segment = api.createScene(factory, 301);
    if (!segment || api.initScene(segment.get(), interpreter, models, cachePath, cachePath) != 0) {
        fail(fd, request, CV_SCENE_301, "Scene 301 initialization failed");
    }
    const Cell input = imageCell(api, request, rgba.data(), "input pixelMap");
    if (api.runScene(segment.get(), input, segmentArg) != 0) {
        fail(fd, request, CV_SCENE_301, "Scene 301 submission failed");
    }
    alignas(16) uint8_t segmentResult[IMAGE_RESULT_SIZE];
    const Mask segmentMask = waitForMask(fd, request, api, segment, segmentResult, CV_SCENE_301);
    if (cpuFailed || cpuConfigured < 2) fail(fd, request, CV_CPU_CONFIGURATION, "Missing 301 CPU models");
    const uint32_t pixels = request.width * request.height;
    const uint32_t coarseBytes = (request.flags & CV_COARSE) ? pixels : 0;
    std::vector<uint8_t> coarse(coarseBytes);
    if (coarseBytes) render(segmentMask, request, rgba.data(), coarse, nullptr);

    const Scene matte = api.createScene(factory, 302);
    if (!matte || api.initScene(matte.get(), interpreter, models, cachePath, cachePath) != 0) {
        fail(fd, request, CV_SCENE_302, "Scene 302 initialization failed");
    }
    auto *segmentHolder = new unsigned char[64]{};
    api.segmentHolder(segmentHolder, segmentResult + SEGMENT_RESULT_OFFSET, "SegRes", true);
    cache["raw_image"] = imageCell(api, request, rgba.data(), "raw_image");
    cache["SegRes"] = api.createCell(reinterpret_cast<CVAlgo::HolderBase *>(segmentHolder));
    const Cell empty;
    if (api.runScene(matte.get(), empty, mattingArg) != 0) {
        fail(fd, request, CV_SCENE_302, "Scene 302 submission failed");
    }
    alignas(16) uint8_t mattingResult[IMAGE_RESULT_SIZE];
    const Mask mattingMask = waitForMask(fd, request, api, matte, mattingResult, CV_SCENE_302);
    if (cpuFailed || cpuConfigured < 3) fail(fd, request, CV_CPU_CONFIGURATION, "Missing 302 CPU model");

    const uint32_t foregroundBytes = (request.flags & CV_FOREGROUND) ? request.bytes : 0;
    std::vector<uint8_t> alpha(pixels), foreground(foregroundBytes);
    render(mattingMask, request, rgba.data(), alpha, foregroundBytes ? &foreground : nullptr);

    char context[128] = "unavailable";
    cv_context(context, sizeof(context));
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    char details[512];
    const int length = snprintf(details, sizeof(details),
        "pid=%d\nuid=%d\npageSize=%ld\nselinux=%s\ncpu=MS_CPU\ncpuConfigurations=%u\n"
        "scene301Score=%.9g\nscene302Score=%.9g\nworkerMillis=%lld\nworkerPeakRssKiB=%ld\n",
        getpid(), (int)getuid(), sysconf(_SC_PAGESIZE), context, cpuConfigured.load(),
        segmentMask.score, mattingMask.score, static_cast<long long>(milliseconds() - start), usage.ru_maxrss);
    if (length < 0 || static_cast<size_t>(length) >= sizeof(details)) _Exit(3);
    const char *slash = std::strrchr(enginePath, '/');
    if (slash == nullptr) fail(fd, request, CV_INCOMPATIBLE, "Missing installed library directory");
    LoadedObjects loaded{std::string(enginePath, static_cast<size_t>(slash - enginePath + 1)),
                         std::string(details, static_cast<size_t>(length))};
    if (dl_iterate_phdr(loadedObject, &loaded) != 0) {
        fail(fd, request, CV_INCOMPATIBLE, "Native mapping outside the installed library directory");
    }
    const CvResponse response{CV_MAGIC, CV_VERSION, request.id, 0, request.width, request.height,
                              pixels, foregroundBytes, coarseBytes, static_cast<uint32_t>(loaded.metadata.size())};
    const bool sent = cv_write(fd, &response, sizeof(response)) &&
        cv_write(fd, loaded.metadata.data(), loaded.metadata.size()) && cv_write(fd, alpha.data(), alpha.size()) &&
        cv_write(fd, foreground.data(), foreground.size()) && cv_write(fd, coarse.data(), coarse.size());
    _Exit(sent ? 0 : 4);
}
}

int main(int argc, char **argv) {
    if (argc != 4 || !cv_watch_parent(getenv(CV_PARENT_ENV))) return 1;
    const int protocol = dup(STDOUT_FILENO);
    const int discard = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (protocol < 0 || discard < 0 || dup2(discard, STDOUT_FILENO) < 0 ||
        dup2(discard, STDERR_FILENO) < 0) return 1;
    close(discard);
    CvRequest request{};
    if (!cv_read(STDIN_FILENO, &request, sizeof(request)) || !cv_valid(&request, CV_SEGMENT)) return 2;
    if (sysconf(_SC_PAGESIZE) != 4096) fail(protocol, request, CV_INCOMPATIBLE, "Requires 4 KiB pages");
    try {
        run(protocol, request, argv[1], argv[2], argv[3]);
    } catch (...) {
        fail(protocol, request, CV_INCOMPATIBLE, "Worker C++ ABI or allocation failure");
    }
}
