#pragma once

#include <jni.h>
#include <cstdio>
#include <string>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#define BOCBANG_LOGW(tag, fmt, ...) __android_log_print(ANDROID_LOG_WARN, tag, fmt, ##__VA_ARGS__)
#define BOCBANG_LOGI(tag, fmt, ...) __android_log_print(ANDROID_LOG_INFO, tag, fmt, ##__VA_ARGS__)
#else
#define BOCBANG_LOGW(tag, fmt, ...) std::fprintf(stderr, "[WARN][%s] " fmt "\n", tag, ##__VA_ARGS__)
#define BOCBANG_LOGI(tag, fmt, ...) std::fprintf(stderr, "[INFO][%s] " fmt "\n", tag, ##__VA_ARGS__)
#endif

namespace bocbang {

inline std::string toUtf8(JNIEnv* env, jstring str) {
    if (str == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(str, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars, static_cast<size_t>(env->GetStringUTFLength(str)));
    env->ReleaseStringUTFChars(str, chars);
    return out;
}

inline jstring toJString(JNIEnv* env, const std::string& utf8) {
    return env->NewStringUTF(utf8.c_str());
}

/**
 * Copies a Java float[] into a reusable native buffer without re-allocating or zero-filling on
 * every call (a 5-minute 16 kHz VAD window is 19.2 MB). Avoids GetPrimitiveArrayCritical so the
 * JVM GC is never pinned while a long C++ ggml graph runs.
 */
inline const float* copyFloatArray(JNIEnv* env, jfloatArray arr, jsize len, std::vector<float>& scratch) {
    if (len <= 0) {
        return nullptr;
    }
    if (static_cast<size_t>(len) > scratch.size()) {
        scratch.resize(static_cast<size_t>(len));
    }
    env->GetFloatArrayRegion(arr, 0, len, scratch.data());
    return scratch.data();
}

inline void throwRecognitionException(JNIEnv* env, const std::string& message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass cls = env->FindClass("com/bocbang/backend/stt/SpeechRecognitionException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

}  // namespace bocbang
