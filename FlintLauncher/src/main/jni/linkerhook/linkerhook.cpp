//
// Created by Vera-Firefly on 17.01.2025.
//

#include <android/dlext.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <atomic>
#include "linkerhook.h"

static void* (*dlopen_ext_impl)(const char* filename, int flags, const android_dlextinfo* extinfo, const void* caller_addr);
static struct android_namespace_t* (*get_exported_namespace_impl)(const char* name);

static void* ready_handle;
static std::atomic<void*> global_ready_handle{nullptr};

static const char* supported_namespaces[] = {"sphal", "vendor", "default"};

// Renderer libraries that must resolve to a single, identical loaded instance
// no matter which caller dlopen's them (LWJGL's own loader vs SDL3's internal
// SDL_GL_LoadLibrary). Without this, the same .so can end up mapped twice
// under different namespaces, giving two different addresses for the same
// symbol (e.g. glGetError) -- which is what RenderPearl's GlBackend.loadLibrary()
// sanity check on 26.3-snapshot+ detects and aborts on ("glGetError mismatch"),
// since it compares LWJGL's resolved address against SDL3's resolved address
// for the same symbol name.
static const char* singleton_renderer_libs[] = {
    "mobileglues", "gl4es_114", "ng_gl4es", "OSMesa_2300d", "OSMesa_8", "OSMesa_2121",
    "vulkan_freedreno", "vulkan_lavapipe"
};

// This build uses APP_STL := system (no linked libc++ runtime), so the cache
// below is plain C/POSIX -- a fixed-size array + pthread_mutex_t -- instead
// of std::mutex/std::string/std::unordered_map, which need libc++ symbols
// this module can't link against.
#define RENDERER_HANDLE_CACHE_MAX 16

typedef struct {
    char* filename;
    void* handle;
} renderer_handle_entry;

static renderer_handle_entry renderer_handle_cache[RENDERER_HANDLE_CACHE_MAX];
static int renderer_handle_cache_count = 0;
static pthread_mutex_t renderer_handle_cache_mutex = PTHREAD_MUTEX_INITIALIZER;

static bool is_singleton_renderer_library(const char* filename) {
    for (const char* needle : singleton_renderer_libs) {
        if (strstr(filename, needle) != nullptr) return true;
    }
    return false;
}

static void* cache_lookup_locked(const char* filename) {
    for (int i = 0; i < renderer_handle_cache_count; i++) {
        if (strcmp(renderer_handle_cache[i].filename, filename) == 0) {
            return renderer_handle_cache[i].handle;
        }
    }
    return nullptr;
}

// Returns a cached handle for `filename` if one exists; otherwise performs the
// real dlopen via `loader`, caches the result on success, and returns it.
// `loader` is either dlopen_ext_impl (namespaced) or the sphal path below --
// both funnel through here so a library loaded by either path is only ever
// mapped once.
static void* get_or_load_singleton(const char* filename,
                                    void* (*loader)(const char*)) {
    pthread_mutex_lock(&renderer_handle_cache_mutex);
    void* cached = cache_lookup_locked(filename);
    pthread_mutex_unlock(&renderer_handle_cache_mutex);
    if (cached != nullptr) {
        return cached;
    }

    void* handle = loader(filename);

    if (handle != nullptr) {
        pthread_mutex_lock(&renderer_handle_cache_mutex);
        // Another thread may have raced us and already cached a handle;
        // keep whichever was inserted first so every caller agrees.
        void* raced = cache_lookup_locked(filename);
        if (raced != nullptr) {
            pthread_mutex_unlock(&renderer_handle_cache_mutex);
            return raced;
        }
        if (renderer_handle_cache_count < RENDERER_HANDLE_CACHE_MAX) {
            renderer_handle_cache[renderer_handle_cache_count].filename = strdup(filename);
            renderer_handle_cache[renderer_handle_cache_count].handle = handle;
            renderer_handle_cache_count++;
        }
        pthread_mutex_unlock(&renderer_handle_cache_mutex);
    }

    return handle;
}

void set_handles(void* handle, void* dlopen_ext, void* get_namespace) {
    ready_handle = handle;
    global_ready_handle.store(handle);
    dlopen_ext_impl = (decltype(dlopen_ext_impl))dlopen_ext;
    get_exported_namespace_impl = (decltype(get_exported_namespace_impl))get_namespace;
}

static void* checkIfGlobalReadyHandle() {
    void* handle = global_ready_handle.load();
    if (handle == nullptr)
    {
        fprintf(stderr, "Global ready handle is null, falling back to ready_handle.\n");
        return ready_handle;
    }
    return handle;
}

void* dlopen_ext(const char* filename, int flags, const android_dlextinfo* extinfo) {
    if (strstr(filename, "vulkan."))
        return checkIfGlobalReadyHandle();

    if (is_singleton_renderer_library(filename)) {
        // Capture flags/extinfo for the real loader call while keeping
        // get_or_load_singleton's loader signature simple.
        static thread_local int captured_flags;
        static thread_local const android_dlextinfo* captured_extinfo;
        captured_flags = flags;
        captured_extinfo = extinfo;
        return get_or_load_singleton(filename, [](const char* fname) -> void* {
            return dlopen_ext_impl(fname, captured_flags, captured_extinfo,
                                    reinterpret_cast<const void*>(&dlopen_ext));
        });
    }

    return dlopen_ext_impl(filename, flags, extinfo, reinterpret_cast<const void*>(&dlopen_ext));
}

void* load_sphal_library(const char* filename, int flags) {
    if (strstr(filename, "vulkan."))
        return checkIfGlobalReadyHandle();

    if (is_singleton_renderer_library(filename)) {
        static thread_local int captured_flags;
        captured_flags = flags;
        return get_or_load_singleton(filename, [](const char* fname) -> void* {
            struct android_namespace_t* androidNamespace = nullptr;
            for (const char* namespace_name : supported_namespaces)
            {
                androidNamespace = get_exported_namespace_impl(namespace_name);
                if (androidNamespace != NULL) break;
            }

            android_dlextinfo extinfo = {
                .flags = ANDROID_DLEXT_USE_NAMESPACE,
                .library_namespace = androidNamespace
            };

            return dlopen_ext_impl(fname, captured_flags, &extinfo,
                                    reinterpret_cast<const void*>(&dlopen_ext));
        });
    }

    struct android_namespace_t* androidNamespace = nullptr;
    for (const char* namespace_name : supported_namespaces)
    {
        androidNamespace = get_exported_namespace_impl(namespace_name);
        if (androidNamespace != NULL) break;
    }

    android_dlextinfo extinfo = {
        .flags = ANDROID_DLEXT_USE_NAMESPACE,
        .library_namespace = androidNamespace
    };

    return dlopen_ext_impl(filename, flags, &extinfo, reinterpret_cast<const void*>(&dlopen_ext));
}

uint64_t hook_atrace_get_enabled_tags() {
    return 0;
}
