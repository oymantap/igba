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

// Framebuffer 240x160 RGB565 (2 bytes per pixel)
static uint16_t frame_buffer[240 * 160];
static uint16_t g_input_state = 0;

// Callback Video: Menangani Pitch dengan benar agar tidak glitch/stride miring
static void video_refresh_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data && data != RETRO_SIMULATED_FRAME) {
        const uint8_t *src = (const uint8_t *)data;
        uint8_t *dst = (uint8_t *)frame_buffer;
        
        for (unsigned y = 0; y < height; y++) {
            memcpy(dst + (y * width * 2), src + (y * pitch), width * 2);
        }
    }
}

// Callback Input Bitmask Libretro Standard
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
    if (rom_path == nullptr) {
        LOGI("Error: rom_path is null");
        return JNI_FALSE;
    }

    const char *path = env->GetStringUTFChars(rom_path, nullptr);
    LOGI("Mencoba memuat ROM dari path: %s", path);

    // 1. Cek keberadaan file di cache internal Android
    FILE *file = fopen(path, "rb");
    if (!file) {
        LOGI("ERROR: File tidak ditemukan di path: %s", path);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    // 2. Cek ukuran file
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fclose(file);

    LOGI("Ukuran file ROM: %ld bytes", size);

    if (size <= 0) {
        LOGI("ERROR: File ROM kosong!");
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    // 3. Pasang ke struct retro_game_info (pakai path karena core dipasang CPULoadRom)
    struct retro_game_info game_info = {0};
    game_info.path = path;
    game_info.data = nullptr;
    game_info.size = static_cast<size_t>(size);

    // 4. Load ke core libretro
    bool loaded = retro_load_game(&game_info);
    LOGI("Hasil retro_load_game: %d", loaded);

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
