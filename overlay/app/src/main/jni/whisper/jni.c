#include <jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdint.h>
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

struct progress_data {
    JNIEnv * env;
    jclass clazz;
    jmethodID method;
    int64_t chunk_start_samples;
    int64_t chunk_samples;
    int64_t total_samples;
};

static void progress_callback(
        struct whisper_context * ctx,
        struct whisper_state * state,
        int progress,
        void * user_data) {
    (void) ctx;
    (void) state;
    struct progress_data * data = (struct progress_data *) user_data;
    if (data == NULL || data->env == NULL || data->clazz == NULL || data->method == NULL) return;

    if (progress < 0) progress = 0;
    if (progress > 100) progress = 100;
    int64_t processed = data->chunk_start_samples
            + (data->chunk_samples * (int64_t) progress) / 100L;
    (*data->env)->CallStaticVoidMethod(
            data->env, data->clazz, data->method,
            (jlong) processed, (jlong) data->total_samples);
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
        jfloatArray audio_data, jstring language_str, jstring vad_model_path_str,
        jlong chunk_start_samples, jlong total_samples) {
    (void) clazz;
    if (context_ptr == 0 || audio_data == NULL) return -1;

    struct whisper_context * ctx = (struct whisper_context *) context_ptr;
    jfloat * audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n = (*env)->GetArrayLength(env, audio_data);
    const char * language = "pl";
    const char * language_chars = NULL;
    const char * vad_model_path = NULL;
    const char * vad_model_path_chars = NULL;

    if (language_str != NULL) {
        language_chars = (*env)->GetStringUTFChars(env, language_str, NULL);
        if (language_chars != NULL && strlen(language_chars) > 0) language = language_chars;
    }
    if (vad_model_path_str != NULL) {
        vad_model_path_chars = (*env)->GetStringUTFChars(env, vad_model_path_str, NULL);
        if (vad_model_path_chars != NULL && strlen(vad_model_path_chars) > 0) {
            vad_model_path = vad_model_path_chars;
        }
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

    // Built-in whisper.cpp VAD (Silero) removes leading/non-speech audio from
    // inference while keeping segment/token timestamps mapped to the original
    // input timeline. This is the critical piece for subtitles that must not
    // start at 00:00 when speech actually begins a few seconds later.
    if (vad_model_path != NULL) {
        params.vad = true;
        params.vad_model_path = vad_model_path;
        params.vad_params = whisper_vad_default_params();
        params.vad_params.threshold = 0.50f;
        params.vad_params.min_speech_duration_ms = 120;
        params.vad_params.min_silence_duration_ms = 180;
        params.vad_params.speech_pad_ms = 30;
        params.vad_params.samples_overlap = 0.10f;
    }

    struct progress_data progress = {
        env, clazz, (*env)->GetStaticMethodID(
                env, clazz, "onNativeProgress", "(JJ)V"),
        (int64_t) chunk_start_samples,
        (int64_t) n,
        (int64_t) total_samples,
    };
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        progress.method = NULL;
    }
    params.progress_callback = progress.method == NULL ? NULL : progress_callback;
    params.progress_callback_user_data = &progress;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = NULL;

    whisper_reset_timings(ctx);
    int rc = whisper_full(ctx, params, audio, n);

    if (language_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language_chars);
    }
    if (vad_model_path_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_model_path_chars);
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
Java_com_whispercpp_java_whisper_WhisperLib_getVadSegmentCount(
        JNIEnv * env, jclass clazz, jlong context_ptr) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return 0;
    return whisper_full_n_vad_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getVadSegmentT0(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint index) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return -1;
    return (jlong) whisper_full_get_vad_segment_t0(
            (struct whisper_context *) context_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_java_whisper_WhisperLib_getVadSegmentT1(
        JNIEnv * env, jclass clazz, jlong context_ptr, jint index) {
    (void) env; (void) clazz;
    if (context_ptr == 0) return -1;
    return (jlong) whisper_full_get_vad_segment_t1(
            (struct whisper_context *) context_ptr, index);
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
