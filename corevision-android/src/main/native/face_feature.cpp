#include "face_feature.h"

#include <dlfcn.h>
#include <mindspore/context.h>
#include <mindspore/model.h>
#include <mindspore/tensor.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace {
constexpr int FACE_SIZE = 112;
constexpr int FACE_CHANNELS = 3;
constexpr double FACE_TEMPLATE[5][2] = {{30.2946, 51.6963}, {65.5318, 51.5014}, {48.0252, 71.7366},
                                         {33.5493, 92.3655}, {62.7299, 92.2041}};

struct Alignment {
    double sourceX = 0.0, sourceY = 0.0;
    double targetX = 0.0, targetY = 0.0;
    double real = 0.0, imaginary = 0.0;
};

Alignment fitAlignment(const CvRequest &request, const CvFace &face) {
    Alignment alignment;
    double targets[5][2];
    for (size_t index = 0; index < 5; ++index) {
        targets[index][0] = FACE_TEMPLATE[index][0] * request.width / 96.0;
        targets[index][1] = FACE_TEMPLATE[index][1] * request.height / 112.0;
        alignment.sourceX += face.landmarks[index][0] / 5.0;
        alignment.sourceY += face.landmarks[index][1] / 5.0;
        alignment.targetX += targets[index][0] / 5.0;
        alignment.targetY += targets[index][1] / 5.0;
    }
    double variance = 0.0;
    for (size_t index = 0; index < 5; ++index) {
        const double sourceX = face.landmarks[index][0] - alignment.sourceX;
        const double sourceY = face.landmarks[index][1] - alignment.sourceY;
        const double targetX = targets[index][0] - alignment.targetX;
        const double targetY = targets[index][1] - alignment.targetY;
        alignment.real += sourceX * targetX + sourceY * targetY;
        alignment.imaginary += sourceX * targetY - sourceY * targetX;
        variance += sourceX * sourceX + sourceY * sourceY;
    }
    if (variance > 0.0) {
        alignment.real /= variance;
        alignment.imaginary /= variance;
    }
    return alignment;
}

double interpolateChannel(const CvRequest &request, const uint8_t *rgba, double sourceX, double sourceY, int channel) {
    const int width = static_cast<int>(request.width), height = static_cast<int>(request.height);
    if (sourceX <= -1.0 || sourceY <= -1.0 || sourceX >= width || sourceY >= height) return 0.0;
    const int left = static_cast<int>(std::floor(sourceX)), top = static_cast<int>(std::floor(sourceY));
    const double horizontal = sourceX - left, vertical = sourceY - top;
    const auto pixel = [&](int col, int row) -> double {
        if (col < 0 || row < 0 || col >= width || row >= height) return 0.0;
        return rgba[(static_cast<size_t>(row) * width + col) * 4 + channel];
    };
    const double upper = pixel(left, top) * (1.0 - horizontal) + pixel(left + 1, top) * horizontal;
    const double lower = pixel(left, top + 1) * (1.0 - horizontal) + pixel(left + 1, top + 1) * horizontal;
    return upper * (1.0 - vertical) + lower * vertical;
}

bool fillFaceTensor(const CvRequest &request, const uint8_t *rgba, const CvFace &face, float *tensor) {
    const auto transform = fitAlignment(request, face);
    const double determinant = transform.real * transform.real + transform.imaginary * transform.imaginary;
    if (determinant < 1e-12) return false;
    for (int row = 0; row < FACE_SIZE; ++row) {
        for (int col = 0; col < FACE_SIZE; ++col) {
            const double targetX = (col + 0.5) * request.width / FACE_SIZE - 0.5 - transform.targetX;
            const double targetY = (row + 0.5) * request.height / FACE_SIZE - 0.5 - transform.targetY;
            const double sourceX = (transform.real * targetX + transform.imaginary * targetY) / determinant + transform.sourceX;
            const double sourceY = (transform.real * targetY - transform.imaginary * targetX) / determinant + transform.sourceY;
            for (int channel = 0; channel < FACE_CHANNELS; ++channel) {
                const double sample = interpolateChannel(request, rgba, sourceX, sourceY, channel);
                *tensor++ = static_cast<float>((sample - 127.5) / 127.5);
            }
        }
    }
    return true;
}

template<typename Function>
Function symbol(void *runtime, const char *name) {
    auto resolved = reinterpret_cast<Function>(dlsym(runtime, name));
    if (resolved == nullptr) throw std::runtime_error(name);
    return resolved;
}

bool runModel(void *runtime, const std::string &path, const CvRequest &request, const uint8_t *rgba,
              const CvFace &face, FaceFeatures &features) {
#define LOAD_AI(name) symbol<decltype(&OH_AI_##name)>(runtime, "OH_AI_" #name)
    const auto createContext = LOAD_AI(ContextCreate);
    const auto destroyContext = LOAD_AI(ContextDestroy);
    const auto setThreads = LOAD_AI(ContextSetThreadNum);
    const auto addDevice = LOAD_AI(ContextAddDeviceInfo);
    const auto createDevice = LOAD_AI(DeviceInfoCreate);
    const auto setFp16 = LOAD_AI(DeviceInfoSetEnableFP16);
    const auto createModel = LOAD_AI(ModelCreate);
    const auto destroyModel = LOAD_AI(ModelDestroy);
    const auto buildModel = LOAD_AI(ModelBuildFromFile);
    const auto getInputs = LOAD_AI(ModelGetInputs);
    const auto predict = LOAD_AI(ModelPredict);
    const auto getType = LOAD_AI(TensorGetDataType);
    const auto getSize = LOAD_AI(TensorGetDataSize);
    const auto getMutableData = LOAD_AI(TensorGetMutableData);
    const auto getData = LOAD_AI(TensorGetData);
#undef LOAD_AI
    const auto closeContext = [&](void *handle) { destroyContext(&handle); };
    const auto closeModel = [&](void *handle) { destroyModel(&handle); };
    const std::unique_ptr<void, decltype(closeContext)> context(createContext(), closeContext);
    const std::unique_ptr<void, decltype(closeModel)> model(createModel(), closeModel);
    if (!context || !model) return false;
    const auto device = createDevice(OH_AI_DEVICETYPE_CPU);
    if (!device) return false;
    setThreads(context.get(), 2);
    setFp16(device, false);
    addDevice(context.get(), device);
    if (buildModel(model.get(), path.c_str(), OH_AI_MODELTYPE_MINDIR, context.get()) != OH_AI_STATUS_SUCCESS) return false;

    const auto inputs = getInputs(model.get());
    if (inputs.handle_num != 1 || !inputs.handle_list ||
        getType(inputs.handle_list[0]) != OH_AI_DATATYPE_NUMBERTYPE_FLOAT32 ||
        getSize(inputs.handle_list[0]) != FACE_SIZE * FACE_SIZE * FACE_CHANNELS * sizeof(float)) return false;
    auto *tensor = static_cast<float *>(getMutableData(inputs.handle_list[0]));
    if (!tensor || !fillFaceTensor(request, rgba, face, tensor)) return false;

    OH_AI_TensorHandleArray outputs{};
    if (predict(model.get(), inputs, &outputs, nullptr, nullptr) != OH_AI_STATUS_SUCCESS ||
        outputs.handle_num != 1 || !outputs.handle_list ||
        getType(outputs.handle_list[0]) != OH_AI_DATATYPE_NUMBERTYPE_FLOAT32 ||
        getSize(outputs.handle_list[0]) != features.size() * sizeof(float)) return false;
    const void *data = getData(outputs.handle_list[0]);
    if (!data) return false;
    std::memcpy(features.data(), data, features.size() * sizeof(float));
    return std::all_of(features.begin(), features.end(), [](float value) { return std::isfinite(value); });
}
}

bool extractFaceFeatures(const CvRequest &request, const uint8_t *rgba, const CvFace &face,
                         const std::string &modelDir, FaceFeatures &features) {
    void *runtime = dlopen("libmindspore_lite_ndk.so", RTLD_NOW | RTLD_NOLOAD);
    if (!runtime) return false;
    try {
        return runModel(runtime, modelDir + "/cv_faceClusterModel_01.ms", request, rgba, face, features);
    } catch (const std::runtime_error &) {
        return false;
    }
}
