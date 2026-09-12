#include <jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <string.h>
#include "whisper.h"

#define TAG "WhisperFilesJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static atomic_bool g_abort_requested = ATOMIC_VAR_INIT(false);

static bool abort_callback(void * user_data) {
    (void) user_data;
    return atomic_load_explicit(&g_abort_requested, memory_order_relaxed);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_initContext(
        JNIEnv * env, jclass clazz, jstring model_path_str) {
    (void) clazz;
    const char * model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context * ctx = whisper_init_from_file(model_path);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_freeContext(
        JNIEnv * env, jclass clazz, jlong context_ptr) {
    (void) env; (void) clazz;
    if (context_ptr != 0) whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_setAbortRequested(
        JNIEnv * env, jclass clazz, jboolean requested) {
    (void) env; (void) clazz;
    atomic_store_explicit(&g_abort_requested, requested == JNI_TRUE, memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_fullTranscribe(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str) {
    (void) clazz;
    if (context_ptr == 0 || audio_data == NULL) return -1;

    struct whisper_context * ctx = (struct whisper_context *) context_ptr;
    jfloat * audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n = (*env)->GetArrayLength(env, audio_data);
    const char * language = "pl";
    const char * language_chars = NULL;

    if (language_str != NULL) {
        language_chars = (*env)->GetStringUTFChars(env, language_str, NULL);
        if (language_chars != NULL && strlen(language_chars) > 0) language = language_chars;
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language;
    params.n_threads = num_threads;
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.token_timestamps = true;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = NULL;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, audio, n);

    if (language_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language_chars);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv * env, jclass clazz, jlong context_ptr) {
    (void) env; (void) clazz;
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegment(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint index) {
    (void) clazz;
    const char * text = whisper_full_get_segment_text((struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint index) {
    (void) env; (void) clazz;
    return (jlong) whisper_full_get_segment_t0((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint index) {
    (void) env; (void) clazz;
    return (jlong) whisper_full_get_segment_t1((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentTokenCount(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint segment_index) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return 0;
    return whisper_full_n_tokens((struct whisper_context *) context_ptr, segment_index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentToken(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint segment_index, jint token_index) {
    (void) clazz;
    if (context_ptr == 0) return (*env)->NewStringUTF(env, "");
    const char * text = whisper_full_get_token_text(
            (struct whisper_context *) context_ptr, segment_index, token_index);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentTokenT0(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint segment_index, jint token_index) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return -1;
    return (jlong) whisper_full_get_token_t0(
            (struct whisper_context *) context_ptr, segment_index, token_index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getTextSegmentTokenT1(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint segment_index, jint token_index) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return -1;
    return (jlong) whisper_full_get_token_t1(
            (struct whisper_context *) context_ptr, segment_index, token_index);
}

JNIEXPORT jboolean JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_isTextSegmentToken(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint segment_index, jint token_index) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return JNI_FALSE;
    struct whisper_context * ctx = (struct whisper_context *) context_ptr;
    whisper_token token = whisper_full_get_token_id(ctx, segment_index, token_index);
    return token < whisper_token_eot(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getSystemInfo(
        JNIEnv * env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
