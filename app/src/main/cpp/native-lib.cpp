#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <mutex>
#include <algorithm>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#define LOG_TAG "IGameBoy-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "libretro.h"

// ============================================================
// VIDEO
// ============================================================

static uint16_t frame_buffer[240 * 160];

static uint16_t g_input_state = 0;

static enum retro_pixel_format g_pixel_format =
        RETRO_PIXEL_FORMAT_0RGB1555;

static ANativeWindow* g_native_window = nullptr;
static std::mutex g_window_mutex;


// ============================================================
// AUDIO RING BUFFER
// ============================================================

// 65536 stereo samples ~= sekitar 1 detik audio pada 32 kHz
#define AUDIO_RING_BUFFER_SIZE 65536

static int16_t g_audio_ring[AUDIO_RING_BUFFER_SIZE];

static size_t g_audio_head = 0;
static size_t g_audio_tail = 0;
static size_t g_audio_count = 0;

static std::mutex g_audio_mutex;


// ============================================================
// DEBUG
// ============================================================

static unsigned long long g_frame_count = 0;


// ============================================================
// AUDIO PUSH
// ============================================================

static void push_audio_sample(
        int16_t left,
        int16_t right) {

    std::lock_guard<std::mutex> lock(g_audio_mutex);

    // Pastikan selalu tersedia ruang untuk stereo pair.
    if (g_audio_count + 2 > AUDIO_RING_BUFFER_SIZE) {

        g_audio_head =
                (g_audio_head + 2)
                % AUDIO_RING_BUFFER_SIZE;

        g_audio_count -= 2;
    }

    g_audio_ring[g_audio_tail] = left;

    g_audio_tail =
            (g_audio_tail + 1)
            % AUDIO_RING_BUFFER_SIZE;

    g_audio_ring[g_audio_tail] = right;

    g_audio_tail =
            (g_audio_tail + 1)
            % AUDIO_RING_BUFFER_SIZE;

    g_audio_count += 2;
}


// ============================================================
// PIXEL CONVERSION
// ============================================================

static inline uint16_t convert_0rgb1555_to_rgb565(
        uint16_t color) {

    uint16_t r = (color >> 10) & 0x1F;
    uint16_t g = (color >> 5) & 0x1F;
    uint16_t b = color & 0x1F;

    return
            (r << 11) |
            (g << 6) |
            b;
}


// ============================================================
// VIDEO CALLBACK
// ============================================================

static void video_refresh_callback(
        const void* data,
        unsigned width,
        unsigned height,
        size_t pitch) {

    if (!data) {
        return;
    }

    const uint8_t* src =
            static_cast<const uint8_t*>(data);

    const unsigned copyWidth =
            std::min(width, 240u);

    const unsigned copyHeight =
            std::min(height, 160u);


    // --------------------------------------------------------
    // 0RGB1555
    // --------------------------------------------------------

    if (g_pixel_format ==
        RETRO_PIXEL_FORMAT_0RGB1555) {

        for (unsigned y = 0;
             y < copyHeight;
             y++) {

            const uint16_t* srcRow =
                    reinterpret_cast<const uint16_t*>(
                            src + (y * pitch));

            uint16_t* dstRow =
                    frame_buffer + (y * 240);

            for (unsigned x = 0;
                 x < copyWidth;
                 x++) {

                dstRow[x] =
                        convert_0rgb1555_to_rgb565(
                                srcRow[x]);
            }
        }
    }


    // --------------------------------------------------------
    // RGB565
    // --------------------------------------------------------

    else if (g_pixel_format ==
             RETRO_PIXEL_FORMAT_RGB565) {

        for (unsigned y = 0;
             y < copyHeight;
             y++) {

            memcpy(
                    frame_buffer + (y * 240),
                    src + (y * pitch),
                    copyWidth * sizeof(uint16_t));
        }
    }


    // --------------------------------------------------------
    // XRGB8888
    // --------------------------------------------------------

    else if (g_pixel_format ==
             RETRO_PIXEL_FORMAT_XRGB8888) {

        for (unsigned y = 0;
             y < copyHeight;
             y++) {

            const uint32_t* srcRow =
                    reinterpret_cast<const uint32_t*>(
                            src + (y * pitch));

            uint16_t* dstRow =
                    frame_buffer + (y * 240);

            for (unsigned x = 0;
                 x < copyWidth;
                 x++) {

                uint32_t c = srcRow[x];

                uint16_t r =
                        (c >> 19) & 0x1F;

                uint16_t g =
                        (c >> 10) & 0x3F;

                uint16_t b =
                        (c >> 3) & 0x1F;

                dstRow[x] =
                        (r << 11) |
                        (g << 5) |
                        b;
            }
        }
    }
}


// ============================================================
// INPUT
// ============================================================

static int16_t input_state_callback(
        unsigned port,
        unsigned device,
        unsigned index,
        unsigned id) {

    if (port == 0 &&
        device == RETRO_DEVICE_JOYPAD) {

        return
                (g_input_state & (1 << id))
                ? 1
                : 0;
    }

    return 0;
}


static void input_poll_callback() {}


// ============================================================
// AUDIO CALLBACKS
// ============================================================

static void audio_sample_callback(
        int16_t left,
        int16_t right) {

    push_audio_sample(left, right);
}


static size_t audio_sample_batch_callback(
        const int16_t* data,
        size_t frames) {

    if (!data) {
        return 0;
    }

    for (size_t i = 0; i < frames; i++) {

        push_audio_sample(
                data[i * 2],
                data[i * 2 + 1]);
    }

    return frames;
}


// ============================================================
// LIBRETRO ENVIRONMENT
// ============================================================

static bool environment_callback(
        unsigned cmd,
        void* data) {

    switch (cmd) {

        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {

            auto* fmt =
                    static_cast<retro_pixel_format*>(data);

            g_pixel_format = *fmt;

            return true;
        }


        case RETRO_ENVIRONMENT_GET_CAN_DUPE: {

            bool* canDupe =
                    static_cast<bool*>(data);

            *canDupe = true;

            return true;
        }
    }

    return false;
}


// ============================================================
// INIT
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeInit(
        JNIEnv* env,
        jobject thiz) {

    retro_set_environment(
            environment_callback);

    retro_set_video_refresh(
            video_refresh_callback);

    retro_set_input_poll(
            input_poll_callback);

    retro_set_input_state(
            input_state_callback);

    retro_set_audio_sample(
            audio_sample_callback);

    retro_set_audio_sample_batch(
            audio_sample_batch_callback);

    retro_init();
}


// ============================================================
// LOAD ROM
// ============================================================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rycl_igba_GbaEngine_nativeLoadRom(
        JNIEnv* env,
        jobject thiz,
        jstring rom_path) {

    if (!rom_path) {
        return JNI_FALSE;
    }

    const char* path =
            env->GetStringUTFChars(
                    rom_path,
                    nullptr);

    FILE* file =
            fopen(path, "rb");

    if (!file) {

        env->ReleaseStringUTFChars(
                rom_path,
                path);

        return JNI_FALSE;
    }

    fseek(file, 0, SEEK_END);

    long size =
            ftell(file);

    fseek(file, 0, SEEK_SET);

    if (size <= 0) {

        fclose(file);

        env->ReleaseStringUTFChars(
                rom_path,
                path);

        return JNI_FALSE;
    }

    void* buffer =
            malloc(static_cast<size_t>(size));

    if (!buffer) {

        fclose(file);

        env->ReleaseStringUTFChars(
                rom_path,
                path);

        return JNI_FALSE;
    }

    size_t read =
            fread(
                    buffer,
                    1,
                    static_cast<size_t>(size),
                    file);

    fclose(file);

    if (read != static_cast<size_t>(size)) {

        free(buffer);

        env->ReleaseStringUTFChars(
                rom_path,
                path);

        return JNI_FALSE;
    }

    retro_game_info game_info{};

    game_info.path = path;
    game_info.data = buffer;
    game_info.size =
            static_cast<size_t>(size);

    bool loaded =
            retro_load_game(&game_info);

    free(buffer);

    env->ReleaseStringUTFChars(
            rom_path,
            path);

    return loaded
           ? JNI_TRUE
           : JNI_FALSE;
}


// ============================================================
// SURFACE
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeSetSurface(
        JNIEnv* env,
        jobject thiz,
        jobject surface) {

    std::lock_guard<std::mutex> lock(
            g_window_mutex);

    if (g_native_window) {

        ANativeWindow_release(
                g_native_window);

        g_native_window = nullptr;
    }

    if (!surface) {
        return;
    }

    g_native_window =
            ANativeWindow_fromSurface(
                    env,
                    surface);

    if (g_native_window) {

        ANativeWindow_setBuffersGeometry(
                g_native_window,
                240,
                160,
                WINDOW_FORMAT_RGB_565);
    }
}


// ============================================================
// STEP FRAME
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeStepFrame(
        JNIEnv* env,
        jobject thiz) {

    // Jalankan satu frame emulator.
    // Audio callback libretro akan mengisi
    // ring buffer secara otomatis.
    retro_run();

    g_frame_count++;


    // --------------------------------------------------------
    // VIDEO
    // --------------------------------------------------------

    std::lock_guard<std::mutex> lock(
            g_window_mutex);

    if (!g_native_window) {
        return;
    }

    ANativeWindow_Buffer windowBuffer{};

    if (ANativeWindow_lock(
            g_native_window,
            &windowBuffer,
            nullptr) != 0) {

        return;
    }

    uint16_t* dst =
            static_cast<uint16_t*>(
                    windowBuffer.bits);

    const int copyWidth = 240;
    const int copyHeight = 160;

    for (int y = 0;
         y < copyHeight;
         y++) {

        memcpy(
                dst + (y * windowBuffer.stride),
                frame_buffer + (y * 240),
                copyWidth * sizeof(uint16_t));
    }

    ANativeWindow_unlockAndPost(
            g_native_window);
}


// ============================================================
// INPUT
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_rycl_igba_GbaEngine_nativeSendInput(
        JNIEnv* env,
        jobject thiz,
        jint keys) {

    g_input_state =
            static_cast<uint16_t>(keys);
}


// ============================================================
// AUDIO READ
// ============================================================

extern "C"
JNIEXPORT jint JNICALL
Java_com_rycl_igba_GbaEngine_nativeReadAudio(
        JNIEnv* env,
        jobject thiz,
        jshortArray buffer) {

    if (!buffer) {
        return 0;
    }

    std::lock_guard<std::mutex> lock(
            g_audio_mutex);

    if (g_audio_count == 0) {
        return 0;
    }

    jsize capacity =
            env->GetArrayLength(buffer);

    size_t samples =
            std::min(
                    static_cast<size_t>(capacity),
                    g_audio_count);

    jshort* dst =
            env->GetShortArrayElements(
                    buffer,
                    nullptr);

    if (!dst) {
        return 0;
    }

    // Bagian pertama sampai ujung ring buffer.
    size_t first =
            std::min(
                    samples,
                    AUDIO_RING_BUFFER_SIZE -
                    g_audio_head);

    memcpy(
            dst,
            &g_audio_ring[g_audio_head],
            first * sizeof(int16_t));


    // Kalau melewati ujung ring buffer,
    // lanjut dari index 0.
    if (samples > first) {

        memcpy(
                dst + first,
                g_audio_ring,
                (samples - first) *
                sizeof(int16_t));
    }

    env->ReleaseShortArrayElements(
            buffer,
            dst,
            0);

    g_audio_head =
            (g_audio_head + samples)
            % AUDIO_RING_BUFFER_SIZE;

    g_audio_count -= samples;

    return static_cast<jint>(samples);
}


// ============================================================
// DEBUG
// ============================================================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_rycl_igba_GbaEngine_nativeDebugInfo(
        JNIEnv* env,
        jobject thiz) {

    char buf[512];

    size_t audioSize;

    {
        std::lock_guard<std::mutex> lock(
                g_audio_mutex);

        audioSize =
                g_audio_count;
    }

    snprintf(
            buf,
            sizeof(buf),

            "=== IGBA DEBUG HUD ===\n"
            "Total Frames : %llu\n"
            "Pixel Format : %d\n"
            "Audio Buffer : %zu samples\n"
            "Input Mask   : 0x%04X",

            g_frame_count,
            g_pixel_format,
            audioSize,
            g_input_state);

    return env->NewStringUTF(buf);
}