#include "face_feature.h"
#include "ocr_bridge.h"
#include "text_feature.h"

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
static_assert(sizeof(CvRequest) == 36 && sizeof(CvResponse) == 40 && sizeof(CvFace) == 76);
static_assert(sizeof(CvObject) == 24 && sizeof(CvSkeleton) == 224);

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
constexpr unsigned int FACE_SCENE = 0;
constexpr unsigned int IMAGE_RETRIEVAL_SCENE = 1;
constexpr unsigned int OBJECT_SCENE = 5;
constexpr unsigned int SKELETON_SCENE = 1003;
constexpr size_t FACE_RESULT_OFFSET = 400;
constexpr size_t IMAGE_FEATURE_OFFSET = 208;
constexpr size_t OBJECT_VECTOR_OFFSET = 568;
constexpr size_t SKELETON_VECTOR_OFFSET = 600;
constexpr size_t OBJECT_RECORD_BYTES = 28;
constexpr size_t LANDMARK_RESULT_OFFSET = 624;
constexpr size_t POSE_RESULT_OFFSET = 656;
constexpr size_t OCCLUSION_RESULT_OFFSET = 856;
constexpr size_t FACE_PROBABILITY_RESULT_OFFSET = 1136;
constexpr size_t FACE_RECT_BYTES = 28;
constexpr size_t VECTOR_HEADER_BYTES = 24;

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

void waitForResult(int fd, const CvRequest &request, const Api &api, const Scene &scene,
                   void *result, uint32_t failure) {
    auto *sink = field<const uint8_t *>(scene.get(), OUTPUT_SINK_OFFSET);
    if (!sink) fail(fd, request, failure, "Missing output sink");
    api.constructResult(result);
    const auto *ready = reinterpret_cast<const volatile uint8_t *>(sink + OUTPUT_READY_OFFSET);
    for (unsigned int attempt = 0; *ready == 0 && attempt < 1000; ++attempt) usleep(20000);
    if (*ready == 0) fail(fd, request, CV_TIMEOUT, "Timed out waiting for a scene result");
    if (api.getResult(sink, result) != 1) fail(fd, request, failure, "Output sink rejected result");
}

Mask waitForMask(int fd, const CvRequest &request, const Api &api, const Scene &scene,
                 void *result, uint32_t failure) {
    waitForResult(fd, request, api, scene, result, failure);
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

struct SubjectMap {
    unsigned count = 0;
    std::vector<uint8_t> labels;
};

template<typename Visit>
void visitNeighbors(uint32_t pixel, uint32_t width, uint32_t height, Visit visit) {
    const uint32_t column = pixel % width, row = pixel / width;
    const uint32_t minColumn = column == 0 ? 0 : column - 1;
    const uint32_t minRow = row == 0 ? 0 : row - 1;
    for (uint32_t y = minRow; y <= std::min(row + 1, height - 1); ++y) {
        for (uint32_t x = minColumn; x <= std::min(column + 1, width - 1); ++x) visit(y * width + x);
    }
}

SubjectMap labelSubjects(const Mask &mask, unsigned maxCount, bool details) {
    const auto pixels = mask.width * mask.height;
    std::vector<uint8_t> coverage(pixels);
    for (uint32_t row = 0; row < mask.height; ++row) {
        const auto *data = mask.data + row * mask.stride;
        for (uint32_t column = 0; column < mask.width; ++column) {
            const float value = field<float>(data, column * sizeof(float));
            if (std::isfinite(value)) coverage[row * mask.width + column] = quantize(std::clamp(value, 0.0f, 1.0f) * 255);
        }
    }

    const size_t minimumArea = std::max<uint32_t>(4, (pixels + 999) / 1000);
    std::vector<uint32_t> components(pixels), pending;
    std::vector<std::pair<size_t, uint32_t>> areas;
    uint32_t component = 0;
    for (uint32_t pixel = 0; pixel < pixels; ++pixel) {
        if (coverage[pixel] <= 128 || components[pixel]) continue;
        ++component;
        components[pixel] = component;
        pending.clear();
        pending.push_back(pixel);
        for (size_t index = 0; index < pending.size(); ++index) {
            visitNeighbors(pending[index], mask.width, mask.height, [&](uint32_t neighbor) {
                if (coverage[neighbor] > 128 && !components[neighbor]) {
                    components[neighbor] = component;
                    pending.push_back(neighbor);
                }
            });
        }
        if (pending.size() >= minimumArea) areas.emplace_back(pending.size(), component);
    }

    const size_t count = std::min<size_t>(areas.size(), maxCount);
    SubjectMap result{static_cast<unsigned>(count), {}};
    if (!details || !count) return result;
    std::partial_sort(areas.begin(), areas.begin() + count, areas.end(), [](const auto &first, const auto &second) {
        return first.first != second.first ? first.first > second.first : first.second < second.second;
    });
    std::vector<uint8_t> ranks(component + 1);
    for (size_t index = 0; index < count; ++index) ranks[areas[index].second] = index + 1;
    result.labels.resize(pixels);
    pending.clear();
    for (uint32_t pixel = 0; pixel < pixels; ++pixel) {
        const auto rank = ranks[components[pixel]];
        result.labels[pixel] = rank;
        if (rank) pending.push_back(pixel);
    }
    for (size_t index = 0; index < pending.size(); ++index) {
        const uint32_t pixel = pending[index];
        const uint8_t rank = result.labels[pixel];
        visitNeighbors(pixel, mask.width, mask.height, [&](uint32_t neighbor) {
            if (coverage[neighbor] && !components[neighbor] && !result.labels[neighbor]) {
                result.labels[neighbor] = rank;
                pending.push_back(neighbor);
            }
        });
    }
    return result;
}

bool hasColorVariation(const std::vector<uint8_t> &rgba) {
    constexpr uint32_t RGB_MASK = 0x00ffffff;
    const auto first = field<uint32_t>(rgba.data(), 0) & RGB_MASK;
    for (size_t offset = 4; offset < rgba.size(); offset += 4) {
        if ((field<uint32_t>(rgba.data(), offset) & RGB_MASK) != first) return true;
    }
    return false;
}

uint8_t sampleSubject(const std::vector<uint8_t> &labels, uint32_t width,
                      const AxisSample &horizontal, const AxisSample &vertical) {
    const auto *top = labels.data() + vertical.left * width;
    const auto *bottom = labels.data() + vertical.right * width;
    const uint8_t candidates[] = {top[horizontal.left], top[horizontal.right], bottom[horizontal.left], bottom[horizontal.right]};
    const double left = 1 - horizontal.fraction, upper = 1 - vertical.fraction;
    const double weights[] = {left * upper, horizontal.fraction * upper,
                              left * vertical.fraction, horizontal.fraction * vertical.fraction};
    uint8_t best = 0;
    double largest = 0;
    for (size_t index = 0; index < 4; ++index) {
        if (candidates[index] && weights[index] > largest) {
            best = candidates[index];
            largest = weights[index];
        }
    }
    return best;
}

void render(const Mask &mask, const CvRequest &request, const uint8_t *rgba,
            std::vector<uint8_t> &alpha, std::vector<uint8_t> *foreground, const SubjectMap *subjects = nullptr) {
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

    const auto pixels = request.width * request.height;
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
            if (subjects && !subjects->labels.empty() && alpha[pixel]) {
                alpha[pixels + pixel] = sampleSubject(subjects->labels, mask.width, columns[col], vertical);
            }
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

std::string runtimeMetadata(int fd, const CvRequest &request, const char *enginePath, int64_t start,
                            const char *sceneDetails) {
    char context[128] = "unavailable";
    cv_context(context, sizeof(context));
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    char details[512];
    const int length = snprintf(details, sizeof(details),
        "pid=%d\nuid=%d\npageSize=%ld\nselinux=%s\ncpu=MS_CPU\ncpuConfigurations=%u\n"
        "%sworkerMillis=%lld\nworkerPeakRssKiB=%ld\n",
        getpid(), (int)getuid(), sysconf(_SC_PAGESIZE), context, cpuConfigured.load(), sceneDetails,
        static_cast<long long>(milliseconds() - start), usage.ru_maxrss);
    if (length < 0 || static_cast<size_t>(length) >= sizeof(details)) _Exit(3);
    const char *slash = std::strrchr(enginePath, '/');
    if (slash == nullptr) fail(fd, request, CV_INCOMPATIBLE, "Missing installed library directory");
    LoadedObjects loaded{std::string(enginePath, static_cast<size_t>(slash - enginePath + 1)),
                         std::string(details, static_cast<size_t>(length))};
    if (dl_iterate_phdr(loadedObject, &loaded) != 0) {
        fail(fd, request, CV_INCOMPATIBLE, "Native mapping outside the installed library directory");
    }
    return loaded.metadata;
}

[[noreturn]] void sendResult(int fd, const CvRequest &request, const void *payload, uint32_t size,
                            const std::string &metadata, const std::vector<uint8_t> &foreground = {},
                            const std::vector<uint8_t> &coarse = {}, uint32_t scale = 1) {
    const CvResponse response{CV_MAGIC, CV_VERSION, request.id, 0, request.width * scale, request.height * scale,
                              size, static_cast<uint32_t>(foreground.size()), static_cast<uint32_t>(coarse.size()),
                              static_cast<uint32_t>(metadata.size())};
    const bool sent = cv_write(fd, &response, sizeof(response)) && cv_write(fd, metadata.data(), metadata.size()) &&
        cv_write(fd, payload, size) && cv_write(fd, foreground.data(), foreground.size()) &&
        cv_write(fd, coarse.data(), coarse.size());
    _Exit(sent ? 0 : 4);
}

const uint8_t *vectorData(const void *vector, size_t expectedBytes) {
    const auto begin = field<uintptr_t>(vector, 0);
    const auto end = field<uintptr_t>(vector, 8);
    const auto capacity = field<uintptr_t>(vector, 16);
    if (begin == 0 || end < begin || end - begin != expectedBytes || capacity < end) return nullptr;
    return reinterpret_cast<const uint8_t *>(begin);
}

size_t detectionCount(int fd, const CvRequest &request, const uint8_t *header, size_t stride, uint32_t failure) {
    const auto begin = field<uintptr_t>(header, 0);
    const auto end = field<uintptr_t>(header, 8);
    const auto capacity = field<uintptr_t>(header, 16);
    if (end < begin || capacity < end || end - begin > CV_MAX_DETECTIONS * stride || (end - begin) % stride != 0 ||
        (begin == 0 && capacity != 0)) {
        fail(fd, request, failure, "Unexpected detection vector layout");
    }
    return (end - begin) / stride;
}

bool validScore(float score) {
    return score >= 0.0f && score <= 1.0f;
}

bool detectionBounds(const uint8_t *record, const CvRequest &request, CvBounds &bounds) {
    const float left = field<float>(record, 0), top = field<float>(record, 4);
    const float right = left + field<float>(record, 8), bottom = top + field<float>(record, 12);
    if (!std::isfinite(left) || !std::isfinite(top) || !std::isfinite(right) || !std::isfinite(bottom)) return false;
    const float width = static_cast<float>(request.width), height = static_cast<float>(request.height);
    bounds = {std::clamp(left, 0.0f, width), std::clamp(top, 0.0f, height),
              std::clamp(right, 0.0f, width), std::clamp(bottom, 0.0f, height)};
    return true;
}

std::vector<CvObject> readObjects(int fd, const CvRequest &request, const uint8_t *result) {
    const auto *header = result + OBJECT_VECTOR_OFFSET;
    const auto count = detectionCount(fd, request, header, OBJECT_RECORD_BYTES, CV_OBJECT_DETECTION);
    const auto *data = field<const uint8_t *>(header, 0);
    std::vector<CvObject> objects;
    objects.reserve(count);
    for (size_t index = 0; index < count; ++index) {
        const auto *record = data + index * OBJECT_RECORD_BYTES;
        CvObject object{{}, field<float>(record, 20), field<int32_t>(record, 16)};
        if (!detectionBounds(record, request, object.bounds) || !validScore(object.score) || object.label < 0) {
            fail(fd, request, CV_OBJECT_DETECTION, "Invalid object geometry, score or label");
        }
        if (object.bounds.right > object.bounds.left && object.bounds.bottom > object.bounds.top) objects.push_back(object);
    }
    return objects;
}

std::vector<CvSkeleton> readSkeletons(int fd, const CvRequest &request, const uint8_t *result) {
    const auto *header = result + SKELETON_VECTOR_OFFSET;
    const auto count = detectionCount(fd, request, header, sizeof(CvSkeleton), CV_SKELETON_DETECTION);
    const auto *data = field<const uint8_t *>(header, 0);
    std::vector<CvSkeleton> skeletons;
    skeletons.reserve(count);
    for (size_t index = 0; index < count; ++index) {
        const auto *record = data + index * sizeof(CvSkeleton);
        CvSkeleton skeleton = field<CvSkeleton>(record, 0);
        if (!detectionBounds(record, request, skeleton.bounds) || !validScore(skeleton.score)) {
            fail(fd, request, CV_SKELETON_DETECTION, "Invalid skeleton geometry or score");
        }
        if (skeleton.bounds.right <= skeleton.bounds.left || skeleton.bounds.bottom <= skeleton.bounds.top) continue;
        for (auto &point : skeleton.points) {
            if (!validScore(point.score)) fail(fd, request, CV_SKELETON_DETECTION, "Invalid skeleton point score");
            point.horizontal = std::clamp(point.horizontal, 0, static_cast<int32_t>(request.width - 1));
            point.vertical = std::clamp(point.vertical, 0, static_cast<int32_t>(request.height - 1));
        }
        skeletons.push_back(skeleton);
    }
    return skeletons;
}

std::vector<CvFace> readFaces(int fd, const CvRequest &request, const uint8_t *result) {
    const auto count = field<uint32_t>(result, FACE_RESULT_OFFSET + 8);
    if (count > CV_MAX_FACES) fail(fd, request, CV_FACE_DETECTION, "Unexpected native face count");
    if (count == 0) return {};

    const auto *rects = vectorData(result + FACE_RESULT_OFFSET + 16, count * FACE_RECT_BYTES);
    const auto *landmarks = vectorData(result + LANDMARK_RESULT_OFFSET + 8, count * 10 * sizeof(int32_t));
    const auto *poses = vectorData(result + POSE_RESULT_OFFSET + 8, count * VECTOR_HEADER_BYTES);
    const auto *occlusion = vectorData(result + OCCLUSION_RESULT_OFFSET + 8, count * sizeof(int32_t));
    const auto *probabilities = vectorData(result + FACE_PROBABILITY_RESULT_OFFSET + 8, count * sizeof(float));
    if (!rects || !landmarks || !poses || !occlusion || !probabilities) {
        fail(fd, request, CV_FACE_DETECTION, "Unexpected native face result layout");
    }

    const float width = static_cast<float>(request.width), height = static_cast<float>(request.height);
    std::vector<CvFace> faces;
    faces.reserve(count);
    for (size_t index = 0; index < count; ++index) {
        const float probability = field<float>(probabilities, index * sizeof(float));
        if (!std::isfinite(probability) || probability < 0.0f || probability > 1.0f) {
            fail(fd, request, CV_FACE_DETECTION, "Invalid face probability");
        }
        if (probability <= 0.1f) continue;
        const size_t rect = index * FACE_RECT_BYTES;
        const float left = field<float>(rects, rect), top = field<float>(rects, rect + 4);
        const float right = left + field<float>(rects, rect + 8), bottom = top + field<float>(rects, rect + 12);
        const auto *rotation = vectorData(poses + index * VECTOR_HEADER_BYTES, 3 * sizeof(float));
        const int32_t occluded = field<int32_t>(occlusion, index * sizeof(int32_t));
        if (!std::isfinite(left) || !std::isfinite(top) || !std::isfinite(right) || !std::isfinite(bottom) ||
            !rotation || (occluded != 0 && occluded != 1)) {
            fail(fd, request, CV_FACE_DETECTION, "Invalid face geometry or occlusion result");
        }
        CvFace face{std::clamp(left, 0.0f, width), std::clamp(top, 0.0f, height),
                    std::clamp(right, 0.0f, width), std::clamp(bottom, 0.0f, height), probability, {},
                    field<float>(rotation, 0), field<float>(rotation, 4), field<float>(rotation, 8), occluded};
        if (!std::isfinite(face.yaw) || !std::isfinite(face.pitch) || !std::isfinite(face.roll)) {
            fail(fd, request, CV_FACE_DETECTION, "Invalid face pose");
        }
        if (face.right <= face.left || face.bottom <= face.top) continue;
        const auto *points = landmarks + index * 10 * sizeof(int32_t);
        for (size_t point = 0; point < 5; ++point) {
            face.landmarks[point][0] = std::clamp(field<int32_t>(points, point * sizeof(int32_t)),
                                                  0, static_cast<int32_t>(request.width - 1));
            face.landmarks[point][1] = std::clamp(field<int32_t>(points, (point + 5) * sizeof(int32_t)),
                                                  0, static_cast<int32_t>(request.height - 1));
        }
        faces.push_back(face);
    }
    const auto area = [](const CvFace &face) { return (face.right - face.left) * (face.bottom - face.top); };
    std::stable_sort(faces.begin(), faces.end(), [&](const CvFace &first, const CvFace &second) {
        return area(first) > area(second);
    });
    return faces;
}

[[noreturn]] void runDetection(int fd, const CvRequest &request, const char *enginePath, int64_t start,
                               const Api &api, CVAlgo::SceneFactory *factory, const Interpreter &interpreter,
                               const std::string &models, const std::string &cachePath, uint8_t *rgba) {
    unsigned int sceneId = FACE_SCENE, modelCount = 6;
    uint32_t failure = CV_FACE_DETECTION;
    if (request.operation == CV_DETECT_OBJECTS) {
        sceneId = OBJECT_SCENE;
        modelCount = 2;
        failure = CV_OBJECT_DETECTION;
    } else if (request.operation == CV_DETECT_SKELETONS) {
        sceneId = SKELETON_SCENE;
        modelCount = 11;
        failure = CV_SKELETON_DETECTION;
    } else if (request.operation == CV_IMAGE_FEATURES) {
        sceneId = IMAGE_RETRIEVAL_SCENE;
        modelCount = 3;
        failure = CV_IMAGE_SEARCH;
    }
    const Scene scene = api.createScene(factory, sceneId);
    if (!scene || api.initScene(scene.get(), interpreter, models, cachePath, cachePath) != 0) {
        fail(fd, request, failure, "Detection scene initialization failed");
    }
    Cache cache;
    const CacheArg args("backbone_640.ms", &cache);
    const Cell input = imageCell(api, request, rgba, "input pixelMap");
    if (api.runScene(scene.get(), input, args) != 0) {
        fail(fd, request, failure, "Detection scene submission failed");
    }
    alignas(16) uint8_t result[IMAGE_RESULT_SIZE];
    waitForResult(fd, request, api, scene, result, failure);
    if (cpuFailed || cpuConfigured < modelCount) fail(fd, request, CV_CPU_CONFIGURATION, "Missing detection scene CPU models");
    if (request.operation == CV_IMAGE_FEATURES) {
        const auto *features = vectorData(result + IMAGE_FEATURE_OFFSET, sizeof(SearchFeatures));
        if (!features) fail(fd, request, CV_IMAGE_SEARCH, "Unexpected image retrieval vector layout");
        sendResult(fd, request, features, sizeof(SearchFeatures), runtimeMetadata(fd, request, enginePath, start, ""));
    }
    if (request.operation == CV_DETECT_OBJECTS) {
        const auto objects = readObjects(fd, request, result);
        sendResult(fd, request, objects.data(), static_cast<uint32_t>(objects.size() * sizeof(CvObject)),
                   runtimeMetadata(fd, request, enginePath, start, ""));
    }
    if (request.operation == CV_DETECT_SKELETONS) {
        const auto skeletons = readSkeletons(fd, request, result);
        sendResult(fd, request, skeletons.data(), static_cast<uint32_t>(skeletons.size() * sizeof(CvSkeleton)),
                   runtimeMetadata(fd, request, enginePath, start, ""));
    }
    const auto faces = readFaces(fd, request, result);
    if (request.operation == CV_FACE_EMBEDDING) {
        if (faces.size() != 1) fail(fd, request, CV_INVALID_INPUT, "Face comparison requires one detected face per image");
        FaceFeatures features;
        if (!extractFaceFeatures(request, rgba, faces.front(), models, features)) {
            fail(fd, request, CV_FACE_COMPARISON, "Face feature inference failed");
        }
        sendResult(fd, request, features.data(), static_cast<uint32_t>(features.size() * sizeof(float)),
                   runtimeMetadata(fd, request, enginePath, start, ""));
    }
    sendResult(fd, request, faces.data(), static_cast<uint32_t>(faces.size() * sizeof(CvFace)),
               runtimeMetadata(fd, request, enginePath, start, ""));
}

[[noreturn]] void runSuperResolution(int fd, const CvRequest &request, const std::string &models,
                                     const std::string &cachePath, const char *enginePath, int64_t start,
                                     const uint8_t *rgba) {
    const auto nativeDir = std::string(enginePath).substr(0, std::string(enginePath).rfind('/') + 1);
    void *library = dlopen((nativeDir + "libimagesuperresolution.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!library) fail(fd, request, CV_INCOMPATIBLE, dlerror());
    void (*construct)(void *) = nullptr;
    int (*init)(void *, std::string, std::string) = nullptr;
    int (*infer)(void *, void *, void *, int) = nullptr;
    void (*matrix)(void *, int, int, int, void *, size_t) = nullptr;
    void (*empty)(void *) = nullptr;
    resolve(construct, library, "_ZN16SISRImgProcesserC1Ev");
    resolve(init, library, "_ZN16SISRImgProcesser4InitENSt4__n112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEES6_");
    resolve(infer, library, "_ZN16SISRImgProcesser7ImageSREN2cv3MatERS1_i");
    resolve(matrix, library, "_ZN2cv3MatC1EiiiPvm");
    resolve(empty, library, "_ZN2cv3MatC1Ev");
    if (!construct || !init || !infer || !matrix || !empty) {
        fail(fd, request, CV_INCOMPATIBLE, "Missing image super-resolution symbols");
    }

    constexpr size_t PROCESSOR_STORAGE_BYTES = 1024;
    constexpr size_t MAT_BYTES = 96;
    constexpr int MAT_U8C3 = 16;
    constexpr int MAT_U8C4 = 24;
    alignas(16) uint8_t processor[PROCESSOR_STORAGE_BYTES]{};
    construct(processor);
    if (init(processor, models, cachePath) != 0) {
        fail(fd, request, CV_IMAGE_SUPER_RESOLUTION, "Image super-resolution initialization failed");
    }
    if (cpuFailed || cpuConfigured < 1) fail(fd, request, CV_CPU_CONFIGURATION, "Missing super-resolution CPU model");

    const auto pixels = request.width * request.height;
    std::vector<uint8_t> bgr(pixels * 3);
    for (size_t pixel = 0; pixel < pixels; ++pixel) {
        bgr[pixel * 3] = rgba[pixel * 4 + 2];
        bgr[pixel * 3 + 1] = rgba[pixel * 4 + 1];
        bgr[pixel * 3 + 2] = rgba[pixel * 4];
    }
    alignas(16) uint8_t source[MAT_BYTES]{}, result[MAT_BYTES]{};
    matrix(source, request.height, request.width, MAT_U8C3, bgr.data(), request.width * 3);
    empty(result);
    const int status = infer(processor, source, result, 30'000);
    if (status != 0) {
        fail(fd, request, status == 9 ? CV_TIMEOUT : CV_IMAGE_SUPER_RESOLUTION, "Image super-resolution inference failed");
    }
    const uint32_t width = request.width * CV_SR_SCALE, height = request.height * CV_SR_SCALE;
    const auto *data = field<const uint8_t *>(result, 16);
    const auto *stride = field<const size_t *>(result, 72);
    constexpr int MAT_TYPE_MASK = 4095;
    if (!data || !stride || *stride < width * 4 || *stride > CV_MAX_DIMENSION * 4 ||
        field<uint32_t>(result, 8) != height || field<uint32_t>(result, 12) != width ||
        (field<int>(result, 0) & MAT_TYPE_MASK) != MAT_U8C4) {
        fail(fd, request, CV_IMAGE_SUPER_RESOLUTION, "Unexpected super-resolution image layout");
    }
    std::vector<uint8_t> output(width * height * 4);
    for (uint32_t row = 0; row < height; ++row) {
        const auto *inputRow = data + row * *stride;
        auto *outputRow = output.data() + row * width * 4;
        for (uint32_t column = 0; column < width; ++column) {
            const size_t offset = column * 4;
            outputRow[offset] = inputRow[offset + 2];
            outputRow[offset + 1] = inputRow[offset + 1];
            outputRow[offset + 2] = inputRow[offset];
            outputRow[offset + 3] = 255;
        }
    }
    sendResult(fd, request, nullptr, 0, runtimeMetadata(fd, request, enginePath, start, ""), output, {}, CV_SR_SCALE);
}

[[noreturn]] void run(int fd, const CvRequest &request, const char *enginePath,
                      const std::string &models, const std::string &cachePath) {
    const int64_t start = milliseconds();
    std::vector<uint8_t> inputBytes(request.bytes);
    if (!cv_read(STDIN_FILENO, inputBytes.data(), inputBytes.size())) _Exit(2);
    void *engine = dlopen(enginePath, RTLD_NOW | RTLD_LOCAL);
    if (!engine) fail(fd, request, CV_INCOMPATIBLE, dlerror());
    if (request.operation == CV_TEXT_FEATURES) {
        const std::string engineFile(enginePath);
        const std::string query(inputBytes.begin(), inputBytes.end());
        SearchFeatures features{};
        if (!extractTextFeatures(engineFile.substr(0, engineFile.rfind('/') + 1), models, query, features)) {
            fail(fd, request, CV_IMAGE_SEARCH, "Text retrieval inference failed");
        }
        ++cpuConfigured;
        sendResult(fd, request, features.data(), sizeof(features), runtimeMetadata(fd, request, enginePath, start, ""));
    }
    auto &rgba = inputBytes;
    if (request.operation == CV_SUPER_RESOLUTION) {
        runSuperResolution(fd, request, models, cachePath, enginePath, start, rgba.data());
    }
    if (request.operation == CV_RECOGNIZE_TEXT) {
        const std::string engineFile(enginePath);
        std::string text, error;
        const int status = recognizeText(engineFile.substr(0, engineFile.rfind('/') + 1), models, cachePath,
                                         request, rgba.data(), text, error);
        if (status != 0) fail(fd, request, status, error.c_str());
        if (cpuFailed || cpuConfigured < 3) fail(fd, request, CV_CPU_CONFIGURATION, "Missing OCR CPU models");
        sendResult(fd, request, text.data(), static_cast<uint32_t>(text.size()),
                   runtimeMetadata(fd, request, enginePath, start, ""));
    }

    Api api{};
    const auto base = bind(api, engine);
    if (!base || api.initFeature() != 0) fail(fd, request, CV_INCOMPATIBLE, "Engine ABI initialization failed");
    const auto &interpreter = *reinterpret_cast<const Interpreter *>(base + INTERPRETER_OFFSET);
    auto *factory = api.getFactory();
    if (!interpreter || !factory) fail(fd, request, CV_INCOMPATIBLE, "Missing interpreter or factory");
    if (request.operation != CV_SEGMENT) {
        runDetection(fd, request, enginePath, start, api, factory, interpreter, models, cachePath, rgba.data());
    }

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
    const bool details = (request.flags & CV_SUBJECT_DETAILS) != 0;
    const auto maxCount = (request.flags & CV_SUBJECT_COUNT_MASK) >> CV_SUBJECT_COUNT_SHIFT;
    const auto subjects = hasColorVariation(rgba)
        ? labelSubjects(mattingMask, maxCount ? maxCount : CV_DEFAULT_SUBJECTS, details) : SubjectMap{};
    std::vector<uint8_t> alpha(details ? pixels * 2 : pixels), foreground(foregroundBytes);
    render(mattingMask, request, rgba.data(), alpha, foregroundBytes ? &foreground : nullptr, &subjects);

    char scores[128];
    snprintf(scores, sizeof(scores), "scene301Score=%.9g\nscene302Score=%.9g\nsubjectCount=%u\n",
             segmentMask.score, mattingMask.score, subjects.count);
    sendResult(fd, request, alpha.data(), static_cast<uint32_t>(alpha.size()),
               runtimeMetadata(fd, request, enginePath, start, scores), foreground, coarse);
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
    if (!cv_read(STDIN_FILENO, &request, sizeof(request)) ||
        (!cv_valid(&request, CV_SEGMENT) && !cv_valid(&request, CV_DETECT_FACES) &&
         !cv_valid(&request, CV_FACE_EMBEDDING) && !cv_valid(&request, CV_DETECT_OBJECTS) &&
         !cv_valid(&request, CV_DETECT_SKELETONS) && !cv_valid(&request, CV_SUPER_RESOLUTION) &&
         !cv_valid(&request, CV_RECOGNIZE_TEXT) && !cv_valid(&request, CV_IMAGE_FEATURES) &&
         !cv_valid(&request, CV_TEXT_FEATURES))) return 2;
    if (sysconf(_SC_PAGESIZE) != 4096) fail(protocol, request, CV_INCOMPATIBLE, "Requires 4 KiB pages");
    try {
        run(protocol, request, argv[1], argv[2], argv[3]);
    } catch (...) {
        fail(protocol, request, CV_INCOMPATIBLE, "Worker C++ ABI or allocation failure");
    }
}
