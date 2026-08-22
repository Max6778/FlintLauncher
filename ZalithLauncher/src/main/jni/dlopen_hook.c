// Ported from Amethyst-Android's native_hooks/dlopen_hook.c.
//
// Why this exists: LWJGL loads libSDL3.so from the JRE VM side (its own
// SharedLibraryLoader), well before any of our other hooks get a chance to
// run. Android's dynamic linker calls a library's JNI_OnLoad exactly once,
// at that very first load -- so libSDL3.so's JNI_OnLoad ends up running
// against the JRE VM's JNIEnv, not the Dalvik/Android VM's. SDL3's JNI_OnLoad
// does RegisterNatives-based setup that needs the real Android classloader
// (org.libsdl.app.SDLActivity etc. only exist there, not on the JRE VM's
// classpath), so running it against the wrong VM leaves that setup silently
// broken. This hook intercepts dlsym(handle, "JNI_OnLoad") for the SDL3/SDL2
// handle and wraps it so the real JNI_OnLoad only actually runs when the
// Dalvik VM is the one doing the loading.
//
// WARNING (kept from upstream): hooking dlopen/dlsym does not work on all
// devices -- there have been reports of conflicts with the Turnip Vulkan
// driver loader. If that turns out to be a problem here, Dobby might fare
// better than bytehook for this specific hook.

#include "environ/environ.h"
#include "native_hooks.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

typedef void *(*dlopen_func_t)(const char *, int);
typedef void *(*dlsym_func_t)(void *, const char *);
typedef jint (*JNI_OnLoad_t)(JavaVM *vm, void *reserved);

#define SDL_LIBS \
    "libSDL3.so", \
    "libSDL2.so"

static const char *const sdl_libs[] = {
        SDL_LIBS
};

static const char *const redirected_libs[] = {
        SDL_LIBS,
};

// Strip the full paths of specific natives so we look inside LD_LIBRARY_PATH instead
static const char *redirect_dlopen_path(const char *filename) {
    if (filename == NULL)
        return NULL;

    const char *basename = strrchr(filename, '/');
    basename = basename ? basename + 1 : filename;

    for (size_t i = 0; i < sizeof(redirected_libs) / sizeof(*redirected_libs); ++i) {
        if (strcmp(basename, redirected_libs[i]) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "dlopen_hook", "Redirecting dlopen: %s -> %s", filename, redirected_libs[i]);
            return redirected_libs[i];
        }
    }

    return filename;
}

static void *sdl3_handle = NULL;
static JNI_OnLoad_t orig_sdl3_JNI_OnLoad;

typedef void *(*sdl_gl_getprocaddress_t)(const char *);
static sdl_gl_getprocaddress_t real_sdl_gl_getprocaddress = NULL;

//
// --- Core GL function resolution fix (SDL_GL_GetProcAddress) ---
//
// Since Minecraft 26.3-snapshot builds, RenderPearl's GlBackend.loadLibrary()
// does a sanity check before accepting the OpenGL backend:
//
//   if (GL.getFunctionProvider().getFunctionAddress("glGetError")
//           != SDLVideo.SDL_GL_GetProcAddress("glGetError")) {
//       this.libraryLoadFailure = new BackendCreationException(
//           "glGetError mismatch", Reason.OPENGL_MISSING);
//   }
//
// LWJGL's GL.getFunctionProvider() resolves "glGetError" via a direct
// dlsym() against the renderer library named by org.lwjgl.opengl.libname
// (e.g. libmobileglues.so). SDL_GL_GetProcAddress, on Android, routes
// through eglGetProcAddress() -- and per the EGL spec, eglGetProcAddress()
// is only *required* to correctly resolve extension functions. For a core
// function like glGetError (present since GL 1.0), an EGL implementation is
// free to return something other than the real symbol address -- a
// trampoline, a dispatch-table stub, etc (see e.g. Cogl's 2012 fix "Don't
// use eglGetProcAddress to retrieve core functions" for the same class of
// bug elsewhere). MobileGlues/Krypton bundle their own internal EGL
// implementation, and if its eglGetProcAddress hands back a different
// pointer than a direct dlsym() would for glGetError specifically, the two
// sides of Minecraft's comparison disagree even though the renderer library
// was only ever loaded once.
//
// This can't be fixed by hooking the exported "SDL_GL_GetProcAddress" symbol
// directly (tried that first) -- LWJGL's native bindings resolve it via a
// single dlsym() call at startup and then invoke the cached raw pointer
// directly for every future call, which never touches a PLT/GOT stub a
// symbol-level hook could intercept. Instead, exactly like the JNI_OnLoad
// interception above: hook the *resolution* (dlsym itself), and when
// something asks dlsym for "SDL_GL_GetProcAddress", hand back OUR wrapper's
// address instead of the real one. Whatever caches that pointer (LWJGL)
// ends up caching ours, so every future raw-pointer call is transparently
// routed through us.
//

// Kept in sync with linkerhook.cpp's singleton_renderer_libs list (which
// filename substrings identify a renderer library) -- here we need the full
// .so filename instead, since dlopen(..., RTLD_NOLOAD) needs an exact (or at
// least resolvable) name, not a substring.
static const char *const renderer_libs_for_core_gl[] = {
    "libmobileglues.so",
    "libgl4es_114.so",
    "libng_gl4es.so",
    "libOSMesa_2300d.so",
    "libOSMesa_8.so",
    "libOSMesa_2121.so",
};

static void *dlsym_core_function_from_loaded_renderer(const char *name) {
    size_t count = sizeof(renderer_libs_for_core_gl) / sizeof(renderer_libs_for_core_gl[0]);
    for (size_t i = 0; i < count; i++) {
        // RTLD_NOLOAD: only succeeds if the library is already loaded --
        // we're looking it up, not loading a new/second instance of it.
        // Deliberately calling the real dlopen here, not custom_dlopen --
        // no redirection/tracking needed for a lookup-only call.
        void *handle = dlopen(renderer_libs_for_core_gl[i], RTLD_NOLOAD | RTLD_NOW);
        if (handle != NULL) {
            void *sym = dlsym(handle, name);
            if (sym != NULL) {
                __android_log_print(ANDROID_LOG_INFO, "dlopen_hook",
                    "Resolved core GL function '%s' via direct dlsym on %s (bypassing eglGetProcAddress)",
                    name, renderer_libs_for_core_gl[i]);
                return sym;
            }
        }
    }
    return NULL;
}

static void *custom_sdl_gl_getprocaddress(const char *proc) {
    void *real_result = real_sdl_gl_getprocaddress ? real_sdl_gl_getprocaddress(proc) : NULL;

    // Only "glGetError" is known to actually need this -- it's the one
    // function RenderPearl's sanity check compares. Deliberately not
    // widening this to other core functions without evidence they need it
    // too; MobileGlues/Krypton may have their own reasons to route other
    // lookups through their internal eglGetProcAddress.
    if (proc != NULL && strcmp(proc, "glGetError") == 0) {
        void *direct = dlsym_core_function_from_loaded_renderer(proc);
        if (direct != NULL && direct != real_result) {
            __android_log_print(ANDROID_LOG_WARN, "dlopen_hook",
                "glGetError address mismatch (SDL_GL_GetProcAddress=%p, direct dlsym=%p) -- "
                "returning the dlsym address so it matches LWJGL's own resolution",
                real_result, direct);
            return direct;
        }
    }

    return real_result;
}

static bool ifSdl(const char *filename) {
    if (filename == NULL)
        return false;

    const char *basename = strrchr(filename, '/');
    basename = basename ? basename + 1 : filename;

    for (size_t i = 0; i < sizeof(sdl_libs) / sizeof(sdl_libs[0]); ++i) {
        if (strcmp(basename, sdl_libs[i]) == 0){
            return true;
        }
    }
    return false;
}

// Skip if not in dalvik vm cause register_methods needs to be ran in android land
static jint custom_sdl3_JNI_OnLoad(JavaVM *vm, void *reserved){
    if (pojav_environ->dalvikJavaVMPtr == vm) {
        return orig_sdl3_JNI_OnLoad(vm, reserved);
    }
    return JNI_VERSION_1_4;
}

void *custom_dlopen(const char *filename, int flags) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlopen,
            dlopen_func_t,
            redirect_dlopen_path(filename),
            flags);
    if (ifSdl(filename)) sdl3_handle = result;

    BYTEHOOK_POP_STACK();
    return result;
}

void *custom_dlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlsym,
            dlsym_func_t,
            handle,
            symbol);
    BYTEHOOK_POP_STACK();

    if (sdl3_handle && handle == sdl3_handle && strcmp(symbol, "JNI_OnLoad") == 0) {
        orig_sdl3_JNI_OnLoad = (JNI_OnLoad_t) result;
        // This outputs in the minecraft logs
        __android_log_print(ANDROID_LOG_INFO, "dlopen_hook", "Intercepted SDL3 JNI_OnLoad: %p", result);
        return (void *) custom_sdl3_JNI_OnLoad;
    }

    if (result != NULL && symbol != NULL && strcmp(symbol, "SDL_GL_GetProcAddress") == 0
        && real_sdl_gl_getprocaddress == NULL) {
        real_sdl_gl_getprocaddress = (sdl_gl_getprocaddress_t) result;
        __android_log_print(ANDROID_LOG_INFO, "dlopen_hook", "Intercepted SDL_GL_GetProcAddress: %p", result);
        return (void *) custom_sdl_gl_getprocaddress;
    }

    return result;
}

void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    bytehook_stub_t stub_dlopen =
            bytehook_hook_all_p(NULL, "dlopen", &custom_dlopen, NULL, NULL);
    bytehook_stub_t stub_dlsym =
            bytehook_hook_all_p(NULL, "dlsym", &custom_dlsym, NULL, NULL);
    __android_log_print(ANDROID_LOG_INFO, "dlopen_hook", "Successfully initialized dlopen hooks, stub: %p %p", stub_dlopen, stub_dlsym);
}
