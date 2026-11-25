#include "text_feature.h"

#include <dlfcn.h>
#include <mindspore/context.h>
#include <mindspore/model.h>
#include <mindspore/tensor.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

struct cJSON;

namespace {
constexpr size_t VOCABULARY_BYTES = 183748;
constexpr int32_t UNKNOWN_TOKEN = 11;
constexpr int32_t START_TOKEN = 12;
constexpr int32_t END_TOKEN = 13;
constexpr size_t MAX_WORD_LENGTH = 7;

struct Boundary {
    size_t bytes, utf16Units;
};

template<typename Function>
Function symbol(void *library, const char *name) {
    auto function = reinterpret_cast<Function>(dlsym(library, name));
    if (!function) throw std::runtime_error(name);
    return function;
}

std::vector<int32_t> tokenize(void *jsonLibrary, const std::string &models, const std::string &query) {
    const auto parse = symbol<cJSON *(*)(const char *)>(jsonLibrary, "cJSON_Parse");
    const auto release = symbol<void (*)(cJSON *)>(jsonLibrary, "cJSON_Delete");
    const auto lookup = symbol<cJSON *(*)(const cJSON *, const char *)>(jsonLibrary, "cJSON_GetObjectItemCaseSensitive");
    const auto number = symbol<double (*)(const cJSON *)>(jsonLibrary, "cJSON_GetNumberValue");
    const std::unique_ptr<FILE, decltype(&fclose)> stream(fopen((models + "/image_retrieval_vocab.json").c_str(), "rb"), fclose);
    std::string raw(VOCABULARY_BYTES, '\0');
    if (!stream || fread(raw.data(), 1, raw.size(), stream.get()) != raw.size() || fgetc(stream.get()) != EOF) return {};
    const std::unique_ptr<cJSON, decltype(release)> vocabulary(parse(raw.c_str()), release);
    if (!vocabulary) return {};

    std::vector<Boundary> boundaries{{0, 0}};
    for (size_t offset = 0; offset < query.size();) {
        const auto lead = static_cast<uint8_t>(query[offset]);
        size_t bytes = 1;
        size_t units = 1;
        if (lead == 0 || (lead >= 0x80 && lead < 0xc2) || lead > 0xf4) return {};
        if (lead >= 0xf0) {
            bytes = 4;
            units = 2;
        } else if (lead >= 0xe0) {
            bytes = 3;
        } else if (lead >= 0xc2) {
            bytes = 2;
        }
        if (offset + bytes > query.size()) return {};
        for (size_t index = 1; index < bytes; ++index) {
            if ((static_cast<uint8_t>(query[offset + index]) & 0xc0) != 0x80) return {};
        }
        offset += bytes;
        boundaries.push_back({offset, boundaries.back().utf16Units + units});
    }
    if (boundaries.size() < 2 || boundaries.back().utf16Units > CV_MAX_QUERY_UNITS) return {};

    std::vector<int32_t> reversed{END_TOKEN};
    for (size_t end = boundaries.size() - 1; end > 0;) {
        size_t start = end - 1;
        while (start > 0 && boundaries[end].utf16Units - boundaries[start - 1].utf16Units <= MAX_WORD_LENGTH) --start;
        int32_t token = UNKNOWN_TOKEN;
        for (; start < end; ++start) {
            const auto begin = boundaries[start].bytes;
            const std::string word = query.substr(begin, boundaries[end].bytes - begin);
            const auto *entry = lookup(vocabulary.get(), word.c_str());
            if (entry) {
                const double id = number(entry);
                if (!(id >= 0 && id <= 9360)) return {};
                token = static_cast<int32_t>(id);
                break;
            }
        }
        if (start == end) --start;
        reversed.push_back(token);
        end = start;
    }
    reversed.push_back(START_TOKEN);
    return {reversed.rbegin(), reversed.rend()};
}

bool infer(void *runtime, const std::string &modelPath, const std::vector<int32_t> &tokens, SearchFeatures &features) {
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
    const auto resize = LOAD_AI(ModelResize);
    const auto predict = LOAD_AI(ModelPredict);
    const auto getName = LOAD_AI(TensorGetName);
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
    if (buildModel(model.get(), modelPath.c_str(), OH_AI_MODELTYPE_MINDIR, context.get()) != OH_AI_STATUS_SUCCESS) return false;

    auto inputs = getInputs(model.get());
    const OH_AI_ShapeInfo shape{2, {1, static_cast<int64_t>(tokens.size())}};
    OH_AI_ShapeInfo shapes[2]{shape, shape};
    if (inputs.handle_num != 2 || !inputs.handle_list || resize(model.get(), inputs, shapes, 2) != OH_AI_STATUS_SUCCESS) return false;
    inputs = getInputs(model.get());
    if (inputs.handle_num != 2 || !inputs.handle_list) return false;
    const auto ids = inputs.handle_list[0];
    const auto mask = inputs.handle_list[1];
    if (!ids || !mask || !getName(ids) || std::strcmp(getName(ids), "in_ids") != 0 ||
        !getName(mask) || std::strcmp(getName(mask), "mask") != 0) return false;
    for (auto input : {ids, mask}) {
        if (getType(input) != OH_AI_DATATYPE_NUMBERTYPE_INT32 || getSize(input) != tokens.size() * sizeof(int32_t)) return false;
    }
    auto *idData = static_cast<int32_t *>(getMutableData(ids));
    auto *maskData = static_cast<int32_t *>(getMutableData(mask));
    if (!idData || !maskData) return false;
    std::copy(tokens.begin(), tokens.end(), idData);
    std::fill_n(maskData, tokens.size(), 1);

    OH_AI_TensorHandleArray outputs{};
    if (predict(model.get(), inputs, &outputs, nullptr, nullptr) != OH_AI_STATUS_SUCCESS ||
        outputs.handle_num != 3 || !outputs.handle_list) return false;
    const auto output = outputs.handle_list[0];
    if (!output || !getName(output) || std::strcmp(getName(output), "o1") != 0 ||
        getType(output) != OH_AI_DATATYPE_NUMBERTYPE_FLOAT32 || getSize(output) != sizeof(features) || !getData(output)) return false;
    std::memcpy(features.data(), getData(output), sizeof(features));
    return true;
}
}

bool extractTextFeatures(const std::string &nativeDir, const std::string &models,
                         const std::string &query, SearchFeatures &features) {
    void *runtime = dlopen((nativeDir + "libmindspore_lite_ndk.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    void *json = dlopen((nativeDir + "libcjson.z.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!runtime || !json) return false;
    const auto tokens = tokenize(json, models, query);
    return !tokens.empty() && infer(runtime, models + "/image_retrieval_text.ms", tokens, features);
}
