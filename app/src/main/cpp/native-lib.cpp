#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "IGameBoy-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "libretro.h"

#ifndef RETRO_SIMULATED_FRAME
#define RETRO_SIMULATED_FRAME ((const void*)(intptr_t)-1)
#endif

static uint16_t frame_buffer[240 * 160];
static uint16_t g_input_state = 0;

static void video_refresh_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data && data != RETRO_SIMULATED_FRAME) {
        const uint16_t *src = (const uint16_t *)data;
        for (unsigned y = 0; y < height; y++) {
            memcpy(&frame_buffer[y * width], &src[y * (pitch / 2)], width * sizeof(uint16_t));
        }
    }
}

static int16_t input_state_callback(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port == 0 && device == RETRO_DEVICE_JOYPAD) {
        return (g_input_state & (1 << id)) ? 1 : 0;
    }
    return 0;
}

static void input_poll_callback(void) {}
static void audio_sample_callback(int16_t left, int16_t right) {}
static size_t audio_sample_batch_callback(const int16_t *data, size_t frames) { return frames; }

static bool environment_callback(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format *fmt = (enum retro_pixel_format *)data;
            return (*fmt == RETRO_PIXEL_FORMAT_RGB565);
        }
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {
            bool *b = (bool *)data;
            *b = true;
            return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeInit(JNIEnv *env, jobject thiz) {
    retro_set_environment(environment_callback);
    retro_set_video_refresh(video_refresh_callback);
    retro_set_input_poll(input_poll_callback);
    retro_set_input_state(input_state_callback);
    retro_set_audio_sample(audio_sample_callback);
    retro_set_audio_sample_batch(audio_sample_batch_callback);

    retro_init();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rycl_igba_GbaEngine_nativeLoadRom(JNIEnv *env, jobject thiz, jstring rom_path) {
    const char *path = env->GetStringUTFChars(rom_path, nullptr);

    // 1. Buka file ROM dari path
    FILE *file = fopen(path, "rb");
    if (!file) {
        LOGI("Gagal membuka file di path: %s", path);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    // 2. Hitung ukuran file ROM
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);

    if (size <= 0) {
        fclose(file);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    // 3. Alokasikan memory buffer
    void *buffer = malloc(size);
    if (!buffer) {
        fclose(file);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    // 4. Baca isi ROM ke buffer
    fread(buffer, 1, size, file);
    fclose(file);

    // 5. Isi struct Libretro secara lengkap
    struct retro_game_info game_info = {0};
    game_info.path = path;
    game_info.data = buffer;
    game_info.size = size;

    // 6. Load ROM ke core
    bool loaded = retro_load_game(&game_info);

    // 7. Cleanup
    free(buffer);
    env->ReleaseStringUTFChars(rom_path, path);

    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeStepFrame(JNIEnv *env, jobject thiz, jobject bitmap) {
    retro_run();

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) >= 0) {
        memcpy(pixels, frame_buffer, 240 * 160 * sizeof(uint16_t));
        AndroidBitmap_unlockPixels(env, bitmap);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeSendInput(JNIEnv *env, jobject thiz, jint keys) {
    g_input_state = (uint16_t)keys;
}
