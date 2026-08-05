#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <vector>
#include <mutex>
#include <android/bitmap.h>
#include <android/log.h>

#define LOG_TAG "IGameBoy-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "libretro.h"

static uint16_t frame_buffer[240 * 160];
static uint16_t g_input_state = 0;
static enum retro_pixel_format g_pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;

// Audio Ring Buffer
static std::vector<int16_t> audio_buffer;
static std::mutex audio_mutex;

// Convert 0RGB1555 (1-5-5-5) to RGB565 (5-6-5)
static inline uint16_t convert_0rgb1555_to_rgb565(uint16_t color) {
    uint16_t r = (color >> 10) & 0x1F;
    uint16_t g = (color >> 5) & 0x1F;
    uint16_t b = color & 0x1F;
    return (r << 11) | (g << 6) | b;
}

static void video_refresh_callback(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;

    const uint8_t *src = (const uint8_t *)data;

    if (g_pixel_format == RETRO_PIXEL_FORMAT_0RGB1555) {
        for (unsigned y = 0; y < height && y < 160; y++) {
            const uint16_t *src_row = (const uint16_t *)(src + (y * pitch));
            uint16_t *dst_row = frame_buffer + (y * 240);
            for (unsigned x = 0; x < width && x < 240; x++) {
                dst_row[x] = convert_0rgb1555_to_rgb565(src_row[x]);
            }
        }
    } else if (g_pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
        for (unsigned y = 0; y < height && y < 160; y++) {
            memcpy(frame_buffer + (y * 240), src + (y * pitch), (width > 240 ? 240 : width) * sizeof(uint16_t));
        }
    } else if (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (unsigned y = 0; y < height && y < 160; y++) {
            const uint32_t *src_row = (const uint32_t *)(src + (y * pitch));
            uint16_t *dst_row = frame_buffer + (y * 240);
            for (unsigned x = 0; x < width && x < 240; x++) {
                uint32_t c = src_row[x];
                uint16_t r = (c >> 19) & 0x1F;
                uint16_t g = (c >> 10) & 0x3F;
                uint16_t b = (c >> 3) & 0x1F;
                dst_row[x] = (r << 11) | (g << 5) | b;
            }
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

static void audio_sample_callback(int16_t left, int16_t right) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    audio_buffer.push_back(left);
    audio_buffer.push_back(right);
}

static size_t audio_sample_batch_callback(const int16_t *data, size_t frames) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    audio_buffer.insert(audio_buffer.end(), data, data + (frames * 2));
    return frames;
}

static bool environment_callback(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            enum retro_pixel_format *fmt = (enum retro_pixel_format *)data;
            g_pixel_format = *fmt;
            return true;
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
    if (!rom_path) return JNI_FALSE;

    const char *path = env->GetStringUTFChars(rom_path, nullptr);
    FILE *file = fopen(path, "rb");
    if (!file) {
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);

    if (size <= 0) {
        fclose(file);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    void *buffer = malloc(size);
    if (!buffer) {
        fclose(file);
        env->ReleaseStringUTFChars(rom_path, path);
        return JNI_FALSE;
    }

    fread(buffer, 1, size, file);
    fclose(file);

    struct retro_game_info game_info = {0};
    game_info.path = path;
    game_info.data = buffer;
    game_info.size = static_cast<size_t>(size);

    bool loaded = retro_load_game(&game_info);

    free(buffer);
    env->ReleaseStringUTFChars(rom_path, path);

    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeStepFrame(JNIEnv *env, jobject thiz, jobject bitmap) {
    retro_run();

    if (bitmap) {
        void *pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
            if (pixels) {
                memcpy(pixels, frame_buffer, 240 * 160 * sizeof(uint16_t));
            }
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeSendInput(JNIEnv *env, jobject thiz, jint keys) {
    g_input_state = (uint16_t)keys;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_rycl_igba_GbaEngine_nativeReadAudio(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(audio_mutex);
    if (audio_buffer.empty()) return nullptr;

    jshortArray result = env->NewShortArray(audio_buffer.size());
    if (result) {
        env->SetShortArrayRegion(result, 0, audio_buffer.size(), audio_buffer.data());
    }
    audio_buffer.clear();
    return result;
}

// ================= FITUR DEBUG HUD OVERLAY =================
extern "C" JNIEXPORT jstring JNICALL
Java_com_rycl_igba_GbaEngine_nativeDebugInfo(JNIEnv *env, jobject thiz) {
    char buf[512];
    
    std::lock_guard<std::mutex> lock(audio_mutex);
    size_t current_audio_size = audio_buffer.size();

    snprintf(buf, sizeof(buf),
        "=== IGBA DEBUG HUD ===\n"
        "Pixel Format : %d\n"
        "Audio Buffer : %zu samples\n"
        "Input Mask   : 0x%04X",
        g_pixel_format,
        current_audio_size,
        g_input_state
    );

    return env->NewStringUTF(buf);
}
