#include "ocr_bridge.h"

#include <dlfcn.h>
#include <sys/stat.h>
#include <multimedia/image_framework/image_pixel_map_napi.h>
#include <napi/native_api.h>
#include <rawfile/raw_file_manager.h>

#include <algorithm>
#include <cstring>
#include <map>
#include <memory>
#include <vector>

struct napi_value__ {
    enum Kind { OBJECT, ARRAY, NUMBER, BOOLEAN, STRING } kind;
    double number = 0;
    std::string text;
    std::map<std::string, napi_value> properties;
    std::vector<napi_value> elements;

    explicit napi_value__(Kind kind) : kind(kind) {}
};

struct napi_env__ {
    std::vector<std::unique_ptr<napi_value__>> values;
    std::map<std::string, napi_callback> methods;
};

struct napi_callback_info__ {
    std::vector<napi_value> arguments;
};

namespace {
struct OcrContext {
    const std::string &models;
    const CvRequest &request;
    uint8_t *rgba;
    napi_module *module = nullptr;
    napi_env__ env;
};

OcrContext *ocr = nullptr;

napi_value make(napi_env env, napi_value__::Kind kind) {
    env->values.push_back(std::make_unique<napi_value__>(kind));
    return env->values.back().get();
}

template<typename Function>
Function original(const char *library, const char *symbol) {
    void *handle = dlopen(library, RTLD_NOW | RTLD_NOLOAD);
    if (!handle) return nullptr;
    const auto function = reinterpret_cast<Function>(dlsym(handle, symbol));
    dlclose(handle);
    return function;
}

napi_value invoke(napi_env env, const char *name, std::vector<napi_value> arguments) {
    const auto method = env->methods.find(name);
    if (method == env->methods.end()) return nullptr;
    napi_callback_info__ info{std::move(arguments)};
    return method->second(env, &info);
}

bool succeeded(napi_value result) {
    return result && result->kind == napi_value__::NUMBER && result->number == 0;
}
}

#define FORWARD_UNLESS(active, library, name, failure, ...) \
    if (!(active)) { \
        const auto function = original<decltype(&name)>(library, #name); \
        return function ? function(__VA_ARGS__) : failure; \
    }

#define NAPI_FORWARD(name, ...) \
    FORWARD_UNLESS(ocr && env == &ocr->env, "libace_napi.z.so", name, napi_invalid_arg, __VA_ARGS__)
#define PIXEL_FORWARD(name, ...) \
    FORWARD_UNLESS(ocr && env == &ocr->env, "libpixelmap_ndk.z.so", name, -1, __VA_ARGS__)
#define RAW_FORWARD(active, name, failure, ...) \
    FORWARD_UNLESS(active, "librawfile.z.so", name, failure, __VA_ARGS__)

extern "C" void napi_module_register(napi_module *registration) {
    if (ocr && registration && registration->nm_modname && std::strcmp(registration->nm_modname, "lite_common_ocr") == 0) {
        ocr->module = registration;
    } else if (const auto function = original<decltype(&napi_module_register)>("libace_napi.z.so", "napi_module_register")) {
        function(registration);
    }
}

extern "C" napi_status napi_define_properties(napi_env env, napi_value object, size_t count,
                                               const napi_property_descriptor *properties) {
    NAPI_FORWARD(napi_define_properties, env, object, count, properties);
    for (size_t index = 0; index < count; ++index) {
        const auto &property = properties[index];
        if (property.utf8name && property.method) env->methods.emplace(property.utf8name, property.method);
    }
    return napi_ok;
}

extern "C" napi_status napi_get_cb_info(napi_env env, napi_callback_info info, size_t *count, napi_value *args,
                                       napi_value *receiver, void **data) {
    NAPI_FORWARD(napi_get_cb_info, env, info, count, args, receiver, data);
    if (count) {
        if (args) {
            std::fill_n(args, *count, nullptr);
            std::copy_n(info->arguments.begin(), std::min(*count, info->arguments.size()), args);
        }
        *count = info->arguments.size();
    }
    if (receiver) *receiver = nullptr;
    if (data) *data = nullptr;
    return napi_ok;
}

extern "C" napi_status napi_get_named_property(napi_env env, napi_value value, const char *name, napi_value *out) {
    NAPI_FORWARD(napi_get_named_property, env, value, name, out);
    if (!value || value->kind != napi_value__::OBJECT) return napi_invalid_arg;
    const auto property = value->properties.find(name);
    *out = property == value->properties.end() ? nullptr : property->second;
    return *out ? napi_ok : napi_invalid_arg;
}

extern "C" napi_status napi_get_value_string_utf8(napi_env env, napi_value value, char *buffer, size_t size, size_t *out) {
    NAPI_FORWARD(napi_get_value_string_utf8, env, value, buffer, size, out);
    if (!value || value->kind != napi_value__::STRING) return napi_invalid_arg;
    const auto bytes = buffer && size ? std::min(value->text.size(), size - 1) : value->text.size();
    if (buffer && size) {
        std::memcpy(buffer, value->text.data(), bytes);
        buffer[bytes] = '\0';
    }
    if (out) *out = bytes;
    return napi_ok;
}

extern "C" napi_status napi_get_value_int32(napi_env env, napi_value value, int32_t *out) {
    NAPI_FORWARD(napi_get_value_int32, env, value, out);
    if (!value || value->kind != napi_value__::NUMBER) return napi_invalid_arg;
    *out = static_cast<int32_t>(value->number);
    return napi_ok;
}

extern "C" napi_status napi_get_value_double(napi_env env, napi_value value, double *out) {
    NAPI_FORWARD(napi_get_value_double, env, value, out);
    if (!value || value->kind != napi_value__::NUMBER) return napi_invalid_arg;
    *out = value->number;
    return napi_ok;
}

extern "C" napi_status napi_get_value_bool(napi_env env, napi_value value, bool *out) {
    NAPI_FORWARD(napi_get_value_bool, env, value, out);
    if (!value || value->kind != napi_value__::BOOLEAN) return napi_invalid_arg;
    *out = value->number != 0;
    return napi_ok;
}

extern "C" napi_status napi_get_array_length(napi_env env, napi_value value, uint32_t *out) {
    NAPI_FORWARD(napi_get_array_length, env, value, out);
    if (!value || value->kind != napi_value__::ARRAY) return napi_invalid_arg;
    *out = value->elements.size();
    return napi_ok;
}

extern "C" napi_status napi_get_element(napi_env env, napi_value value, uint32_t index, napi_value *out) {
    NAPI_FORWARD(napi_get_element, env, value, index, out);
    if (!value || value->kind != napi_value__::ARRAY || index >= value->elements.size()) return napi_invalid_arg;
    *out = value->elements[index];
    return napi_ok;
}

extern "C" napi_status napi_get_arraybuffer_info(napi_env env, napi_value value, void **buffer, size_t *size) {
    NAPI_FORWARD(napi_get_arraybuffer_info, env, value, buffer, size);
    return napi_invalid_arg;
}

extern "C" napi_status napi_create_array(napi_env env, napi_value *out) {
    NAPI_FORWARD(napi_create_array, env, out);
    *out = make(env, napi_value__::ARRAY);
    return napi_ok;
}

extern "C" napi_status napi_create_object(napi_env env, napi_value *out) {
    NAPI_FORWARD(napi_create_object, env, out);
    *out = make(env, napi_value__::OBJECT);
    return napi_ok;
}

extern "C" napi_status napi_create_double(napi_env env, double value, napi_value *out) {
    NAPI_FORWARD(napi_create_double, env, value, out);
    *out = make(env, napi_value__::NUMBER);
    (*out)->number = value;
    return napi_ok;
}

extern "C" napi_status napi_create_int32(napi_env env, int32_t value, napi_value *out) {
    NAPI_FORWARD(napi_create_int32, env, value, out);
    return napi_create_double(env, value, out);
}

extern "C" napi_status napi_create_string_utf8(napi_env env, const char *text, size_t bytes, napi_value *out) {
    NAPI_FORWARD(napi_create_string_utf8, env, text, bytes, out);
    if (bytes == NAPI_AUTO_LENGTH) bytes = strnlen(text, CV_MAX_OCR_BYTES + 1);
    if (bytes > CV_MAX_OCR_BYTES) return napi_invalid_arg;
    *out = make(env, napi_value__::STRING);
    (*out)->text.assign(text, bytes);
    return napi_ok;
}

extern "C" napi_status napi_set_element(napi_env env, napi_value value, uint32_t index, napi_value element) {
    NAPI_FORWARD(napi_set_element, env, value, index, element);
    if (!value || value->kind != napi_value__::ARRAY || index >= CV_MAX_OCR_BYTES / sizeof(napi_value)) return napi_invalid_arg;
    if (index >= value->elements.size()) value->elements.resize(index + 1);
    value->elements[index] = element;
    return napi_ok;
}

extern "C" napi_status napi_set_named_property(napi_env env, napi_value value, const char *name, napi_value property) {
    NAPI_FORWARD(napi_set_named_property, env, value, name, property);
    if (!value || value->kind != napi_value__::OBJECT || !property) return napi_invalid_arg;
    value->properties[name] = property;
    return napi_ok;
}

extern "C" int32_t OH_GetImageInfo(napi_env env, napi_value value, OHOS::Media::OhosPixelMapInfo *out) {
    PIXEL_FORWARD(OH_GetImageInfo, env, value, out);
    *out = {ocr->request.width, ocr->request.height, ocr->request.stride, 3};
    return 0;
}

extern "C" int32_t OH_AccessPixels(napi_env env, napi_value value, void **out) {
    PIXEL_FORWARD(OH_AccessPixels, env, value, out);
    *out = ocr->rgba;
    return 0;
}

extern "C" int32_t OH_UnAccessPixels(napi_env env, napi_value value) {
    PIXEL_FORWARD(OH_UnAccessPixels, env, value);
    return 0;
}

extern "C" NativeResourceManager *OH_ResourceManager_InitNativeResourceManager(napi_env env, napi_value value) {
    RAW_FORWARD(ocr && env == &ocr->env, OH_ResourceManager_InitNativeResourceManager, nullptr, env, value);
    return reinterpret_cast<NativeResourceManager *>(ocr);
}

extern "C" RawFile *OH_ResourceManager_OpenRawFile(const NativeResourceManager *manager, const char *name) {
    RAW_FORWARD(ocr && manager == reinterpret_cast<NativeResourceManager *>(ocr), OH_ResourceManager_OpenRawFile,
                nullptr, manager, name);
    if (!name || !*name || std::strchr(name, '/') || std::strcmp(name, ".") == 0 || std::strcmp(name, "..") == 0) return nullptr;
    return reinterpret_cast<RawFile *>(std::fopen((ocr->models + "/" + name).c_str(), "rb"));
}

extern "C" long OH_ResourceManager_GetRawFileSize(RawFile *file) {
    RAW_FORWARD(ocr, OH_ResourceManager_GetRawFileSize, -1, file);
    struct stat details{};
    return file && fstat(fileno(reinterpret_cast<FILE *>(file)), &details) == 0 ? details.st_size : -1;
}

extern "C" int OH_ResourceManager_ReadRawFile(const RawFile *file, void *buffer, size_t size) {
    RAW_FORWARD(ocr, OH_ResourceManager_ReadRawFile, -1, file, buffer, size);
    return file ? std::fread(buffer, 1, size, reinterpret_cast<FILE *>(const_cast<RawFile *>(file))) : -1;
}

extern "C" void OH_ResourceManager_CloseRawFile(RawFile *file) {
    if (ocr) {
        if (file) std::fclose(reinterpret_cast<FILE *>(file));
    } else if (const auto function = original<decltype(&OH_ResourceManager_CloseRawFile)>(
                   "librawfile.z.so", "OH_ResourceManager_CloseRawFile")) {
        function(file);
    }
}

int recognizeText(const std::string &nativeDir, const std::string &models, const std::string &cachePath,
                  const CvRequest &request, uint8_t *rgba, std::string &result, std::string &error) {
    OcrContext context{models, request, rgba, nullptr, {}};
    struct ClearContext {
        ~ClearContext() { ocr = nullptr; }
    } clearContext;
    ocr = &context;
    auto *env = &context.env;
    void *library = dlopen((nativeDir + "liblite_common_ocr.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        error = dlerror();
        return CV_INCOMPATIBLE;
    }
    if (!context.module || !context.module->nm_register_func) {
        error = "Original OCR module did not register";
        return CV_INCOMPATIBLE;
    }
    const auto object = [&] { return make(env, napi_value__::OBJECT); };
    const auto boolean = [&](bool value) { auto item = make(env, napi_value__::BOOLEAN); item->number = value; return item; };
    const auto string = [&](const std::string &value) { auto item = make(env, napi_value__::STRING); item->text = value; return item; };
    context.module->nm_register_func(env, object());
    if (!succeeded(invoke(env, "initLibrary", {object(), string(cachePath), string(models),
                                                boolean(false), boolean(false), make(env, napi_value__::NUMBER)}))) {
        error = "Original OCR initialization failed";
        return CV_TEXT_RECOGNITION;
    }
    const auto output = object();
    if (!succeeded(invoke(env, "runText", {object(), boolean((request.flags & CV_TEXT_DIRECTION) != 0), output}))) {
        error = "Original OCR inference failed";
        return CV_TEXT_RECOGNITION;
    }
    napi_value text = nullptr;
    if (napi_get_named_property(env, output, "ocrResults", &text) != napi_ok) {
        error = "Original OCR returned no result field";
        return CV_TEXT_RECOGNITION;
    }
    if (text->kind == napi_value__::ARRAY) {
        if (text->elements.size() > 1) {
            error = "Original OCR returned multiple payloads";
            return CV_TEXT_RECOGNITION;
        }
        text = text->elements.empty() ? nullptr : text->elements.front();
    }
    if (text && text->kind != napi_value__::STRING) {
        error = "Original OCR returned an invalid payload";
        return CV_TEXT_RECOGNITION;
    }
    if (text) result = std::move(text->text);
    return 0;
}
