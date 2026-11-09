/* TazCore — frontend libretro minimal untuk TazPlay.
 * Memuat core .so (PCSX-ReARMed) lewat dlopen, menjalankan loop emulasi
 * di thread native, menggambar framebuffer software ke Surface,
 * audio via AAudio, input dari bitmask yang diatur Java (sentuh/keyboard).
 */
#define LOG_TAG "TazCore"
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
#include "libretro.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---------- simbol core ---------- */
typedef void (*void_fn)(void);
static void *core_handle = NULL;
static void (*p_retro_init)(void);
static void (*p_retro_deinit)(void);
static unsigned (*p_retro_api_version)(void);
static void (*p_retro_get_system_info)(struct retro_system_info *);
static void (*p_retro_get_system_av_info)(struct retro_system_av_info *);
static void (*p_retro_set_environment)(retro_environment_t);
static void (*p_retro_set_video_refresh)(retro_video_refresh_t);
static void (*p_retro_set_audio_sample)(retro_audio_sample_t);
static void (*p_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*p_retro_set_input_poll)(retro_input_poll_t);
static void (*p_retro_set_input_state)(retro_input_state_t);
static void (*p_retro_set_controller_port_device)(unsigned, unsigned);
static bool (*p_retro_load_game)(const struct retro_game_info *);
static void (*p_retro_unload_game)(void);
static void (*p_retro_run)(void);
static void (*p_retro_reset)(void);
static void *(*p_retro_get_memory_data)(unsigned);
static size_t (*p_retro_get_memory_size)(unsigned);
static size_t (*p_retro_serialize_size)(void);
static bool (*p_retro_serialize)(void *, size_t);
static bool (*p_retro_unserialize)(const void *, size_t);

#define LOAD_SYM(name) do { \
    p_##name = (void *)dlsym(core_handle, #name); \
    if (!p_##name) { LOGE("simbol hilang: %s", #name); return false; } } while (0)

/* ---------- state ---------- */
static char sys_dir[512] = "";
static char save_dir[512] = "";
static char sram_path[640] = "";
static enum retro_pixel_format pixel_fmt = RETRO_PIXEL_FORMAT_XRGB8888;
static struct retro_system_av_info av_info;
static bool av_valid = false;
static ANativeWindow *window = NULL;
static int surf_w = 0, surf_h = 0;          /* ukuran surface (buffer tujuan) */
static atomic_int aspect_mode = 0;          /* 0=Asli (letterbox) 1=Stretch 2=Zoom */
static int *xmap = NULL;                    /* peta kolom sumber per kolom tujuan */
static int xmap_w = 0;
static pthread_mutex_t window_lock = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *audio_stream = NULL;
static atomic_int buttons_mask = 0;
static atomic_int smooth_filter = 0;        /* 0 Tajam (nearest), 1 Halus (bilinear) */
static atomic_int audio_on = 1;
static atomic_int audio_mode = 0;           /* 0 latensi rendah, 1 stabil */
static atomic_int pad_type = RETRO_DEVICE_JOYPAD;
static atomic_int axis_lx = 0, axis_ly = 0, axis_rx = 0, axis_ry = 0;
static atomic_bool running = false;
static atomic_bool shutdown_req = false;
static pthread_t game_thread;
static bool game_loaded = false;
static long frame_count = 0;

/* ---------- logging dari core ---------- */
static void core_log(enum retro_log_level level, const char *fmt, ...) {
    (void)level;
    char buf[512];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    LOGI("[core] %s", buf);
}

/* ---------- environment ---------- */
static bool cb_environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            pixel_fmt = *(const enum retro_pixel_format *)data;
            LOGI("pixel format: %d", (int)pixel_fmt);
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **)data = sys_dir; return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **)data = save_dir; return true;
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            *(const char **)data = save_dir; return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE:
            ((struct retro_variable *)data)->value = NULL; return false;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = false; return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *m = (const struct retro_message *)data;
            if (m && m->msg) LOGI("[pesan core] %s", m->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            struct retro_log_callback *l = (struct retro_log_callback *)data;
            l->log = core_log; return true;
        }
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *(int *)data = (1 << 0) | (1 << 1); return true; /* video + audio */
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = true; return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            const struct retro_game_geometry *g = (const struct retro_game_geometry *)data;
            if (g && av_valid) {
                av_info.geometry.base_width = g->base_width;
                av_info.geometry.base_height = g->base_height;
                av_info.geometry.max_width = g->max_width;
                av_info.geometry.max_height = g->max_height;
                av_info.geometry.aspect_ratio = g->aspect_ratio;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
            return true;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            atomic_store(&shutdown_req, true); return true;
        default:
            return false;
    }
}

/* ---------- video ---------- */
static void window_setup(int w, int h) {
    /* buffer mengikuti ukuran surface, BUKAN resolusi game,
       supaya tidak di-stretch compositor; penskalaan aspek di blit. */
    if (!window) return;
    int fmt = WINDOW_FORMAT_RGBX_8888;
    if (pixel_fmt == RETRO_PIXEL_FORMAT_RGB565) fmt = WINDOW_FORMAT_RGB_565;
    int tw = surf_w > 0 ? surf_w : w;
    int th = surf_h > 0 ? surf_h : h;
    ANativeWindow_setBuffersGeometry(window, tw, th, fmt);
}

static void cb_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    frame_count++;
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) return;
    pthread_mutex_lock(&window_lock);
    if (!window) { pthread_mutex_unlock(&window_lock); return; }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, NULL) != 0) { pthread_mutex_unlock(&window_lock); return; }
    const int BW = buf.width, BH = buf.height;
    const uint8_t *src = (const uint8_t *)data;
    uint8_t *dst = (uint8_t *)buf.bits;
    const int mode = atomic_load(&aspect_mode);

    double ga = (av_valid && av_info.geometry.aspect_ratio > 0.01)
            ? av_info.geometry.aspect_ratio : (double)width / (double)height;
    int dw, dh;
    if (mode == 1) { dw = BW; dh = BH; }                       /* stretch */
    else if (mode == 2) {                                      /* zoom: penuhi, potong sisi */
        if ((double)BW / BH > ga) { dw = BW; dh = (int)(BW / ga); }
        else { dh = BH; dw = (int)(BH * ga); }
    } else {                                                   /* asli: letterbox */
        if ((double)BW / BH > ga) { dh = BH; dw = (int)(BH * ga); }
        else { dw = BW; dh = (int)(BW / ga); }
    }
    if (dw < 1) dw = 1;
    if (dh < 1) dh = 1;
    const int ox = (BW - dw) / 2, oy = (BH - dh) / 2;

    /* latar hitam */
    const int bpp = (pixel_fmt == RETRO_PIXEL_FORMAT_RGB565) ? 2 : 4;
    for (int y = 0; y < BH; y++) memset(dst + y * buf.stride * bpp, 0, BW * bpp);

    /* peta kolom */
    if (xmap_w != BW) { free(xmap); xmap = (int *)malloc(sizeof(int) * BW); xmap_w = BW; }
    for (int x = 0; x < BW; x++) {
        int rx = x - ox;
        xmap[x] = (rx < 0 || rx >= dw) ? -1 : (int)((long)rx * (int)width / dw);
    }
    const int y0 = oy < 0 ? 0 : oy, y1 = (oy + dh) > BH ? BH : (oy + dh);
    for (int y = y0; y < y1; y++) {
        int sy = (int)((long)(y - oy) * (int)height / dh);
        const uint8_t *srow = src + sy * pitch;
        uint8_t *drow = dst + y * buf.stride * bpp;
        if (pixel_fmt == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *s = (const uint16_t *)srow;
            uint16_t *d = (uint16_t *)drow;
            for (int x = 0; x < BW; x++) if (xmap[x] >= 0) d[x] = s[xmap[x]];
        } else if (pixel_fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *s = (const uint32_t *)srow;
            uint32_t *d = (uint32_t *)drow;
            if (atomic_load(&smooth_filter) && dw > (int)width) {
                /* bilinear: baris sumber atas/bawah untuk baris tujuan ini */
                double fy = ((y - oy) + 0.5) * (double)height / dh - 0.5;
                int sy0 = (int)fy; if (sy0 < 0) sy0 = 0;
                if (sy0 > (int)height - 1) sy0 = (int)height - 1;
                int sy1 = sy0 + 1 < (int)height ? sy0 + 1 : sy0;
                double wy = fy - (int)fy; if (wy < 0) wy = 0; if (wy > 1) wy = 1;
                const uint32_t *sa = (const uint32_t *)(src + sy0 * pitch);
                const uint32_t *sb = (const uint32_t *)(src + sy1 * pitch);
                for (int x = 0; x < BW; x++) {
                    int rx = x - ox;
                    if (rx < 0 || rx >= dw) continue;
                    double fx = (rx + 0.5) * (double)width / dw - 0.5;
                    int sx0 = (int)fx; if (sx0 < 0) sx0 = 0;
                    if (sx0 > (int)width - 1) sx0 = (int)width - 1;
                    int sx1 = sx0 + 1 < (int)width ? sx0 + 1 : sx0;
                    double wx = fx - (int)fx; if (wx < 0) wx = 0; if (wx > 1) wx = 1;
                    uint32_t p00 = sa[sx0], p10 = sa[sx1], p01 = sb[sx0], p11 = sb[sx1];
                    uint32_t out = 0xFF000000u;
                    for (int ch = 0; ch < 3; ch++) {
                        int sh = ch * 8;
                        double top = ((p00 >> sh) & 0xFF) * (1 - wx) + ((p10 >> sh) & 0xFF) * wx;
                        double bot = ((p01 >> sh) & 0xFF) * (1 - wx) + ((p11 >> sh) & 0xFF) * wx;
                        out |= ((uint32_t)(top * (1 - wy) + bot * wy + 0.5)) << sh;
                    }
                    d[x] = out;
                }
            } else {
                for (int x = 0; x < BW; x++) if (xmap[x] >= 0) d[x] = s[xmap[x]];
            }
        } else { /* 0RGB1555 -> RGBX */
            const uint16_t *s = (const uint16_t *)srow;
            uint32_t *d = (uint32_t *)drow;
            for (int x = 0; x < BW; x++) {
                if (xmap[x] < 0) continue;
                uint16_t px = s[xmap[x]];
                uint32_t r = (px >> 10) & 0x1F, g = (px >> 5) & 0x1F, b = px & 0x1F;
                d[x] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
            }
        }
    }
    ANativeWindow_unlockAndPost(window);
    pthread_mutex_unlock(&window_lock);
}

/* ---------- audio ---------- */
static void audio_open(int rate) {
    if (audio_stream || !atomic_load(&audio_on)) return;
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, rate > 0 ? rate : 44100);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    int stable = atomic_load(&audio_mode) == 1;
    AAudioStreamBuilder_setPerformanceMode(b, stable ? AAUDIO_PERFORMANCE_MODE_NONE
                                                     : AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &audio_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { audio_stream = NULL; LOGE("AAudio open gagal: %d", r); return; }
    if (stable) {
        int32_t burst = AAudioStream_getFramesPerBurst(audio_stream);
        if (burst > 0) AAudioStream_setBufferSizeInFrames(audio_stream, burst * 4);
    }
    AAudioStream_requestStart(audio_stream);
}

static void audio_close(void) {
    if (audio_stream) {
        AAudioStream_requestStop(audio_stream);
        AAudioStream_close(audio_stream);
        audio_stream = NULL;
    }
}

static size_t cb_audio_batch(const int16_t *data, size_t frames) {
    if (!atomic_load(&audio_on)) return frames;
    if (!audio_stream) audio_open(av_valid ? (int)av_info.timing.sample_rate : 44100);
    if (audio_stream && data) {
        AAudioStream_write(audio_stream, data, (int32_t)frames, 0);
    }
    return frames;
}
static void cb_audio_sample(int16_t l, int16_t r) {
    int16_t pair[2] = {l, r};
    cb_audio_batch(pair, 1);
}

/* ---------- input ---------- */
static void cb_input_poll(void) { }
static int16_t cb_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
            if (id == RETRO_DEVICE_ID_ANALOG_X) return (int16_t)atomic_load(&axis_lx);
            if (id == RETRO_DEVICE_ID_ANALOG_Y) return (int16_t)atomic_load(&axis_ly);
        } else if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
            if (id == RETRO_DEVICE_ID_ANALOG_X) return (int16_t)atomic_load(&axis_rx);
            if (id == RETRO_DEVICE_ID_ANALOG_Y) return (int16_t)atomic_load(&axis_ry);
        }
        return 0;
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    if (id > 15) return 0;
    return (atomic_load(&buttons_mask) >> id) & 1;
}

/* ---------- sram / kartu memori ---------- */
static void sram_save(void) {
    if (!p_retro_get_memory_data || sram_path[0] == 0) return;
    size_t sz = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *mem = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!sz || !mem) return;
    FILE *f = fopen(sram_path, "wb");
    if (f) { fwrite(mem, 1, sz, f); fclose(f); }
}
static void sram_load(void) {
    if (!p_retro_get_memory_data || sram_path[0] == 0) return;
    size_t sz = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *mem = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!sz || !mem) return;
    FILE *f = fopen(sram_path, "rb");
    if (f) { size_t rd = fread(mem, 1, sz, f); (void)rd; fclose(f); }
}

/* ---------- loop ---------- */
static void *game_loop(void *arg) {
    (void)arg;
    double fps = (av_valid && av_info.timing.fps > 1) ? av_info.timing.fps : 60.0;
    const long frame_ns = (long)(1e9 / fps);
    struct timespec next;
    clock_gettime(CLOCK_MONOTONIC, &next);
    long since_save = 0;
    while (atomic_load(&running)) {
        p_retro_run();
        if (atomic_load(&shutdown_req)) { atomic_store(&running, false); break; }
        next.tv_nsec += frame_ns;
        while (next.tv_nsec >= 1000000000L) { next.tv_nsec -= 1000000000L; next.tv_sec++; }
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long remain = (next.tv_sec - now.tv_sec) * 1000000000L + (next.tv_nsec - now.tv_nsec);
        if (remain > 0) {
            struct timespec ts = { remain / 1000000000L, remain % 1000000000L };
            nanosleep(&ts, NULL);
        } else if (remain < -frame_ns * 3) {
            next = now; /* jauh tertinggal: sinkron ulang */
        }
        if (++since_save >= 1800) { since_save = 0; sram_save(); }
    }
    return NULL;
}

/* ---------- JNI ---------- */
JNIEXPORT jboolean JNICALL
Java_id_taz_tazplay_NativeCore_init(JNIEnv *env, jclass cls,
        jstring corePath, jstring sysDir, jstring saveDir) {
    (void)cls;
    const char *cp = (*env)->GetStringUTFChars(env, corePath, NULL);
    const char *sd = (*env)->GetStringUTFChars(env, sysDir, NULL);
    const char *vd = (*env)->GetStringUTFChars(env, saveDir, NULL);
    strncpy(sys_dir, sd, sizeof(sys_dir) - 1);
    strncpy(save_dir, vd, sizeof(save_dir) - 1);
    core_handle = dlopen(cp, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, corePath, cp);
    (*env)->ReleaseStringUTFChars(env, sysDir, sd);
    (*env)->ReleaseStringUTFChars(env, saveDir, vd);
    if (!core_handle) { LOGE("dlopen gagal: %s", dlerror()); return JNI_FALSE; }
    LOAD_SYM(retro_init); LOAD_SYM(retro_deinit); LOAD_SYM(retro_api_version);
    LOAD_SYM(retro_get_system_info); LOAD_SYM(retro_get_system_av_info);
    LOAD_SYM(retro_set_environment); LOAD_SYM(retro_set_video_refresh);
    LOAD_SYM(retro_set_audio_sample); LOAD_SYM(retro_set_audio_sample_batch);
    LOAD_SYM(retro_set_input_poll); LOAD_SYM(retro_set_input_state);
    LOAD_SYM(retro_set_controller_port_device);
    LOAD_SYM(retro_load_game); LOAD_SYM(retro_unload_game);
    LOAD_SYM(retro_run); LOAD_SYM(retro_reset);
    LOAD_SYM(retro_get_memory_data); LOAD_SYM(retro_get_memory_size);
    LOAD_SYM(retro_serialize_size); LOAD_SYM(retro_serialize); LOAD_SYM(retro_unserialize);
    p_retro_set_environment(cb_environment);
    p_retro_set_video_refresh(cb_video_refresh);
    p_retro_set_audio_sample(cb_audio_sample);
    p_retro_set_audio_sample_batch(cb_audio_batch);
    p_retro_set_input_poll(cb_input_poll);
    p_retro_set_input_state(cb_input_state);
    p_retro_init();
    LOGI("core API v%u siap", p_retro_api_version());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_id_taz_tazplay_NativeCore_loadGame(JNIEnv *env, jclass cls, jstring romPath) {
    (void)cls;
    const char *rp = (*env)->GetStringUTFChars(env, romPath, NULL);
    struct retro_game_info gi;
    memset(&gi, 0, sizeof(gi));
    gi.path = rp;
    bool ok = p_retro_load_game(&gi);
    if (ok) {
        game_loaded = true;
        if (p_retro_set_controller_port_device)
            p_retro_set_controller_port_device(0, (unsigned)atomic_load(&pad_type));
        p_retro_get_system_av_info(&av_info);
        av_valid = true;
        LOGI("game termuat: %ux%u @ %.2f fps, audio %.0f Hz",
             av_info.geometry.base_width, av_info.geometry.base_height,
             av_info.timing.fps, av_info.timing.sample_rate);
        /* jalur sram: <saveDir>/<nama file tanpa ekstensi>.srm */
        const char *base = strrchr(rp, '/');
        base = base ? base + 1 : rp;
        char name[256];
        strncpy(name, base, sizeof(name) - 1); name[sizeof(name) - 1] = 0;
        char *dot = strrchr(name, '.');
        if (dot) *dot = 0;
        snprintf(sram_path, sizeof(sram_path), "%s/%s.srm", save_dir, name);
        sram_load();
        pthread_mutex_lock(&window_lock);
        if (window) window_setup((int)av_info.geometry.base_width, (int)av_info.geometry.base_height);
        pthread_mutex_unlock(&window_lock);
    } else {
        LOGE("retro_load_game gagal untuk %s", rp);
    }
    (*env)->ReleaseStringUTFChars(env, romPath, rp);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    pthread_mutex_lock(&window_lock);
    if (window) { ANativeWindow_release(window); window = NULL; }
    if (surface) {
        window = ANativeWindow_fromSurface(env, surface);
        if (window) {
            int w = ANativeWindow_getWidth(window), h = ANativeWindow_getHeight(window);
            if (w > 0 && h > 0) { surf_w = w; surf_h = h; }
            if (av_valid)
                window_setup((int)av_info.geometry.base_width, (int)av_info.geometry.base_height);
        }
    }
    pthread_mutex_unlock(&window_lock);
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setAspectMode(JNIEnv *env, jclass cls, jint mode) {
    (void)env; (void)cls;
    atomic_store(&aspect_mode, mode);
}

JNIEXPORT jint JNICALL
Java_id_taz_tazplay_NativeCore_getFrameCount(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)frame_count;
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setSmooth(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls;
    atomic_store(&smooth_filter, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setAudioEnabled(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls;
    atomic_store(&audio_on, on ? 1 : 0);
    if (!on) audio_close();
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setAudioMode(JNIEnv *env, jclass cls, jint mode) {
    (void)env; (void)cls;
    atomic_store(&audio_mode, mode);
    audio_close(); /* dibuka lagi dengan mode baru di batch berikutnya */
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setPadType(JNIEnv *env, jclass cls, jint type) {
    (void)env; (void)cls;
    atomic_store(&pad_type, type);
    if (game_loaded && p_retro_set_controller_port_device)
        p_retro_set_controller_port_device(0, (unsigned)type);
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setAxes(JNIEnv *env, jclass cls, jint lx, jint ly, jint rx, jint ry) {
    (void)env; (void)cls;
    atomic_store(&axis_lx, lx); atomic_store(&axis_ly, ly);
    atomic_store(&axis_rx, rx); atomic_store(&axis_ry, ry);
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_start(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (!game_loaded || atomic_load(&running)) return;
    atomic_store(&shutdown_req, false);
    atomic_store(&running, true);
    pthread_create(&game_thread, NULL, game_loop, NULL);
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_stop(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (atomic_load(&running)) {
        atomic_store(&running, false);
        pthread_join(game_thread, NULL);
    }
    sram_save();
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_setButtons(JNIEnv *env, jclass cls, jint mask) {
    (void)env; (void)cls;
    atomic_store(&buttons_mask, mask);
}

/* Save state: dipanggil dari thread UI saat loop berhenti (panel setelan
 * terbuka), jadi tidak balapan dengan retro_run. */
JNIEXPORT jboolean JNICALL
Java_id_taz_tazplay_NativeCore_saveState(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    if (!game_loaded || !p_retro_serialize_size) return JNI_FALSE;
    size_t sz = p_retro_serialize_size();
    if (sz == 0) { LOGE("serialize_size = 0"); return JNI_FALSE; }
    void *buf = malloc(sz);
    if (!buf) return JNI_FALSE;
    bool ok = p_retro_serialize(buf, sz);
    if (ok) {
        const char *pp = (*env)->GetStringUTFChars(env, path, NULL);
        FILE *f = fopen(pp, "wb");
        if (f) { fwrite(buf, 1, sz, f); fclose(f); } else ok = false;
        (*env)->ReleaseStringUTFChars(env, path, pp);
    }
    free(buf);
    LOGI("saveState: %s (%zu byte)", ok ? "ok" : "gagal", sz);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_id_taz_tazplay_NativeCore_loadState(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    if (!game_loaded || !p_retro_unserialize) return JNI_FALSE;
    const char *pp = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(pp, "rb");
    (*env)->ReleaseStringUTFChars(env, path, pp);
    if (!f) return JNI_FALSE;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz <= 0) { fclose(f); return JNI_FALSE; }
    void *buf = malloc((size_t)sz);
    if (!buf) { fclose(f); return JNI_FALSE; }
    size_t rd = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    bool ok = (rd == (size_t)sz) && p_retro_unserialize(buf, (size_t)sz);
    free(buf);
    LOGI("loadState: %s (%ld byte)", ok ? "ok" : "gagal", sz);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_id_taz_tazplay_NativeCore_destroy(JNIEnv *env, jclass cls) {    (void)env; (void)cls;
    Java_id_taz_tazplay_NativeCore_stop(env, cls);
    if (game_loaded && p_retro_unload_game) { p_retro_unload_game(); game_loaded = false; }
    if (audio_stream) {
        AAudioStream_requestStop(audio_stream);
        AAudioStream_close(audio_stream);
        audio_stream = NULL;
    }
    if (p_retro_deinit) p_retro_deinit();
    if (core_handle) { dlclose(core_handle); core_handle = NULL; }
}
