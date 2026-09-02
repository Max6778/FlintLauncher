//
// sdl_hook.c
//
// Hooks SDL_InitSubSystem (the first call SDL3 makes on startup) to notify
// the launcher's Java side the instant real SDL initialization begins, so
// CallbackBridge.notifyLauncher() can load libSDL3/libSDL2, wire up
// SDL.setupJNI(), and flip on SDL-routed input dispatch -- all *before*
// SDL's own init continues. See CallbackBridge.notifyLauncher() and
// MinecraftGLSurface.setupSDL() for the Java-side half of this.
//
// create_sdl_hooks() is called from exit_hook.c's shared init_hooks(), which
// owns the single process-wide bytehook_init() call -- see native_hooks.h.
// (This hook used to do its own independent bytehook_init(); consolidated so
// dlopen_hook.c and this hook share one bytehook instance, matching how
// Amethyst-Android -- the project this was ported from -- structures it.)
//

#include <jni.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdlib.h>
#include <bytehook.h>
#include <dlfcn.h>
#include <string.h>
#include <android/log.h>

#include <environ/environ.h>
#include "native_hooks.h"

static _Atomic bool sdl_init_notified = false;

typedef int (*sdl_init_subsystem_func)(unsigned long flags);

// Mirrors CallbackBridge's notification constants -- keep these in sync
// with org.lwjgl.glfw.CallbackBridge.NOTIF_TYPE_SDL / ACTION_INIT_LAUNCHER_INTEGRATION.
#define NOTIF_TYPE_SDL 0
#define ACTION_INIT_LAUNCHER_INTEGRATION 0

static void notify_launcher_sdl_init() {
    // We need to call CallbackBridge.notifyLauncher() on the Android-app (Dalvik) VM,
    // NOT the JRE VM, because:
    //   1. pojav_environ->bridgeClazz and method_notifyLauncher are GlobalRefs resolved
    //      against the Dalvik VM at JNI_OnLoad time by input_bridge_v3.c. Using them
    //      with a JRE JNIEnv is undefined behaviour and will crash.
    //   2. Using FindClass on a JRE-attached thread fails because the thread's classloader
    //      context doesn't know about lwjgl-glfw-classes.jar.
    // The correct pattern: AttachCurrentThread to the Dalvik VM, then call through the
    // already-resolved bridgeClazz/method_notifyLauncher GlobalRefs. This is exactly the
    // same approach input_bridge_v3.c uses when it sends GLFW events to the launcher side.
    JavaVM* dalvikVm = pojav_environ->dalvikJavaVMPtr;
    if (dalvikVm == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "dalvikJavaVMPtr not set, cannot notify launcher");
        return;
    }

    jclass callbackBridgeClass = (jclass) pojav_environ->bridgeClazz;
    if (callbackBridgeClass == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "bridgeClazz not yet resolved, cannot notify launcher");
        return;
    }

    jmethodID notifyLauncherMethod = pojav_environ->method_notifyLauncher;
    if (notifyLauncherMethod == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "method_notifyLauncher not yet resolved, cannot notify launcher");
        return;
    }

    JNIEnv* env;
    bool needsDetach = false;
    int getEnvStat = (*dalvikVm)->GetEnv(dalvikVm, (void**) &env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if ((*dalvikVm)->AttachCurrentThread(dalvikVm, &env, NULL) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "Failed to attach to Dalvik VM");
            return;
        }
        needsDetach = true;
    } else if (getEnvStat != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "Failed to get Dalvik JNIEnv (%i)", getEnvStat);
        return;
    }

    jintArray actionArray = (*env)->NewIntArray(env, 1);
    jint action = ACTION_INIT_LAUNCHER_INTEGRATION;
    (*env)->SetIntArrayRegion(env, actionArray, 0, 1, &action);

    jboolean result = (*env)->CallStaticBooleanMethod(env, callbackBridgeClass, notifyLauncherMethod,
        (jint) NOTIF_TYPE_SDL, actionArray);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    __android_log_print(ANDROID_LOG_INFO, "sdl_hook", "notifyLauncher(SDL init) returned %i", (int) result);

    (*env)->DeleteLocalRef(env, actionArray);

    detach:
    if (needsDetach) (*dalvikVm)->DetachCurrentThread(dalvikVm);
}

typedef void (*sdl_set_main_ready_func)(void);

// SDL's real SDL_InitSubSystem() (which we call through to below) refuses to
// run unless SDL_SetMainReady() was already called -- normally done for you
// by the SDL_main.h macro machinery around a real C main(). Since Minecraft
// reaches SDL_InitSubSystem from a JVM thread with no SDL-owned main() ever
// running, that flag is never set, and SDL_InitSubSystem fails immediately
// with "did you include SDL_main.h...". We're already inside libSDL3.so by
// the time this hook fires (SDL_InitSubSystem is one of its exports), so the
// library is guaranteed loaded and RTLD_NOLOAD is safe here.
static void sdl_set_main_ready_if_needed() {
    void *sdl_handle = dlopen("libSDL3.so", RTLD_NOLOAD);
    if (sdl_handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "libSDL3.so not loaded, cannot call SDL_SetMainReady");
        return;
    }
    sdl_set_main_ready_func SDL_SetMainReady_p = (sdl_set_main_ready_func) dlsym(sdl_handle, "SDL_SetMainReady");
    if (SDL_SetMainReady_p == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "sdl_hook", "SDL_SetMainReady not found in libSDL3.so");
        return;
    }
    SDL_SetMainReady_p();
}

static int custom_sdl_init_subsystem(unsigned long flags) {
    // Only notify once -- SDL_InitSubSystem can legitimately be called
    // multiple times for different subsystems over the app's lifetime.
    bool expected = false;
    if (atomic_compare_exchange_strong(&sdl_init_notified, &expected, true)) {
        notify_launcher_sdl_init();
        sdl_set_main_ready_if_needed();
    }
    int ret = BYTEHOOK_CALL_PREV(custom_sdl_init_subsystem, sdl_init_subsystem_func, flags);
    BYTEHOOK_POP_STACK();
    return ret;
}

static void create_sdl_hooks_impl(bytehook_hook_all_t bytehook_hook_all_p) {
    // Hook across all loaded libraries -- libSDL3.so isn't loaded yet at this
    // point (it's loaded lazily, from the Java side, only once we notify it to),
    // so we can't target it by name; BYTEHOOK_MODE_AUTOMATIC picks up libraries
    // loaded after the hook is installed too.
    bytehook_stub_t stub = bytehook_hook_all_p(NULL, "SDL_InitSubSystem", &custom_sdl_init_subsystem, NULL, NULL);
    __android_log_print(ANDROID_LOG_INFO, "sdl_hook", "Successfully initialized SDL init hook, stub=%p", stub);
}

// Public entrypoint called from exit_hook.c's shared init_hooks(), which owns
// the single bytehook_init() call for the whole process -- see native_hooks.h.
void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    create_sdl_hooks_impl(bytehook_hook_all_p);
}

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
// trampoline, a dispatch-table stub, etc. This is a known, long-documented
// class of bug (see e.g. Cogl's 2012 fix "Don't use eglGetProcAddress to
// retrieve core functions"). MobileGlues/Krypton bundle their own internal
// EGL implementation, and if its eglGetProcAddress hands back a different
// pointer than a direct dlsym() would for glGetError specifically, the two
// sides of Minecraft's comparison disagree even though the renderer library
// was only ever loaded once -- no double-load needed for this to happen.
//
// Fix: intercept SDL_GL_GetProcAddress, and when asked for "glGetError"
// specifically, bypass the real eglGetProcAddress path and dlsym() the
// symbol directly off whichever renderer library is already loaded (via
// RTLD_NOLOAD -- it's already loaded by this point, we're not loading a new
// copy), matching exactly what LWJGL's own resolution does. This keeps both
// sides of Minecraft's check in agreement.
//

typedef void* (*sdl_gl_getprocaddress_func)(const char* proc);

// Kept in sync with linkerhook.cpp's singleton_renderer_libs list (which
// filename substrings identify a renderer library) -- here we need the full
// .so filename instead, since dlopen(..., RTLD_NOLOAD) needs an exact (or at
// least resolvable) name, not a substring.
static const char* renderer_libs_for_core_gl[] = {
    "libmobileglues.so",
    "libgl4es_114.so",
    "libng_gl4es.so",
    "libOSMesa_2300d.so",
    "libOSMesa_8.so",
    "libOSMesa_2121.so",
};

static void* dlsym_core_function_from_loaded_renderer(const char* name) {
    size_t count = sizeof(renderer_libs_for_core_gl) / sizeof(renderer_libs_for_core_gl[0]);
    for (size_t i = 0; i < count; i++) {
        // RTLD_NOLOAD: only succeeds if the library is already loaded --
        // we're looking it up, not loading a new/second instance of it.
        void* handle = dlopen(renderer_libs_for_core_gl[i], RTLD_NOLOAD | RTLD_NOW);
        if (handle != NULL) {
            void* sym = dlsym(handle, name);
            if (sym != NULL) {
                __android_log_print(ANDROID_LOG_INFO, "sdl_hook",
                    "Resolved core GL function '%s' via direct dlsym on %s (bypassing eglGetProcAddress)",
                    name, renderer_libs_for_core_gl[i]);
                return sym;
            }
        }
    }
    return NULL;
}

static void* custom_sdl_gl_getprocaddress(const char* proc) {
    void* real_result = BYTEHOOK_CALL_PREV(custom_sdl_gl_getprocaddress, sdl_gl_getprocaddress_func, proc);

    // Only "glGetError" is known to actually need this -- it's the one
    // function RenderPearl's sanity check compares. Deliberately not
    // widening this to other core functions without evidence they need it
    // too; MobileGlues/Krypton may have their own reasons to route other
    // lookups through their internal eglGetProcAddress.
    if (proc != NULL && strcmp(proc, "glGetError") == 0) {
        void* direct = dlsym_core_function_from_loaded_renderer(proc);
        if (direct != NULL && direct != real_result) {
            __android_log_print(ANDROID_LOG_WARN, "sdl_hook",
                "glGetError address mismatch detected (SDL_GL_GetProcAddress=%p, direct dlsym=%p) -- "
                "returning the dlsym address so it matches LWJGL's own resolution",
                real_result, direct);
            BYTEHOOK_POP_STACK();
            return direct;
        }
    }

    BYTEHOOK_POP_STACK();
    return real_result;
}

static void create_gl_core_proc_hooks_impl(bytehook_hook_all_t bytehook_hook_all_p) {
    // Same BYTEHOOK_MODE_AUTOMATIC reasoning as create_sdl_hooks_impl above:
    // libSDL3.so isn't loaded at process-hook-install time, so we hook the
    // symbol name across all libraries rather than targeting libSDL3.so by
    // path -- automatic mode picks up the export once SDL3 does load.
    bytehook_stub_t stub = bytehook_hook_all_p(NULL, "SDL_GL_GetProcAddress", &custom_sdl_gl_getprocaddress, NULL, NULL);
    __android_log_print(ANDROID_LOG_INFO, "sdl_hook",
        "Successfully initialized SDL_GL_GetProcAddress core-function hook, stub=%p", stub);
}

void create_gl_core_proc_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    create_gl_core_proc_hooks_impl(bytehook_hook_all_p);
}

//
// --- pthread_create JNI-auto-attach fix (replaces an earlier, broken
//     attempt at this that hooked SDL_SetWindowTitle directly) ---
//
// Real crash, from an actual device: "JNI DETECTED ERROR IN APPLICATION: a
// thread is making JNI calls without being attached, in call to
// NewStringUTF", backtrace through SDL_SetWindowTitle's Android backend
// (it needs to call back into Java to actually apply the title). SDL3's own
// docs say SDL_SetWindowTitle() "should only be called on the main thread"
// -- but on Android its internal window/surface handling can end up
// invoking it from a thread that was never attached to the JVM at all.
//
// FIRST ATTEMPT (removed): hooking SDL_SetWindowTitle itself via bytehook.
// Verified on-device this had zero effect -- same crash, same offset.
// Root cause: bytehook is a *PLT* hook library only (its own docs: "If you
// need an inline hook library, use ShadowHook instead"). PLT hooking works
// by patching a *caller's* import-table entry for a function -- it can only
// intercept calls that cross a shared-library boundary. The actual crash
// backtrace shows SDL_SetWindowTitle's caller is *also* inside libSDL3.so
// (frame directly below it: "libSDL3.so (???)") -- an intra-library call,
// resolved via a direct branch at link time, which never goes through the
// PLT at all. There is structurally no way to intercept that specific call
// with the hooking mechanism this project uses.
//
// FIX: move the interception point from "the specific SDL3-internal call
// that happens to touch JNI" (unknowable/unhookable in general -- there
// could be others besides SDL_SetWindowTitle) to "the moment any new native
// thread is created" instead. pthread_create is a libc.so export; called
// from libSDL3.so, that *does* cross a library boundary and *is* PLT-
// hookable. Wrapping every new thread's start routine to attach to the
// Dalvik JVM the instant it begins running -- before any of its real work,
// whatever that turns out to be -- means it no longer matters which SDL3-
// internal code path eventually calls into JNI; the thread is already
// attached by the time it gets there.
//
// This hooks pthread_create process-wide (every new native thread, not
// just SDL3's), which is deliberate: it's the same class of bug this
// codebase already hit once before in stdio_is.c's own logger thread, so a
// general fix is more valuable here than a narrowly-targeted one. Extra
// attached-but-idle JNI threads have negligible cost; the failure mode
// we're guarding against is a hard process abort.
//
// Deliberately never detaches attached threads -- some may call into JNI
// more than once over their lifetime, and repeated attach/detach cycles on
// the same OS thread are both wasteful and have their own history of ART-
// version-specific bugs. A thread that's attached and never explicitly
// detached is cleaned up automatically by ART when it exits.
//

typedef int (*pthread_create_func)(pthread_t *thread, const pthread_attr_t *attr,
                                    void *(*start_routine)(void *), void *arg);

typedef struct {
    void *(*real_start_routine)(void *);
    void *real_arg;
} attach_trampoline_args_t;

static void *attaching_thread_trampoline(void *arg) {
    attach_trampoline_args_t *targs = (attach_trampoline_args_t *) arg;
    void *(*real_start_routine)(void *) = targs->real_start_routine;
    void *real_arg = targs->real_arg;
    free(targs);

    JavaVM *dalvikVm = pojav_environ->dalvikJavaVMPtr;
    if (dalvikVm != NULL) {
        JNIEnv *env;
        int getEnvStat = (*dalvikVm)->GetEnv(dalvikVm, (void **) &env, JNI_VERSION_1_6);
        if (getEnvStat == JNI_EDETACHED) {
            if ((*dalvikVm)->AttachCurrentThread(dalvikVm, &env, NULL) != 0) {
                __android_log_print(ANDROID_LOG_WARN, "sdl_hook",
                    "Failed to auto-attach a newly created native thread to the Dalvik JVM");
            }
        }
        // getEnvStat == JNI_OK means some earlier mechanism already attached
        // this thread (or it's a JVM-created thread) -- nothing to do.
    }

    return real_start_routine(real_arg);
}

static int custom_pthread_create(pthread_t *thread, const pthread_attr_t *attr,
                                  void *(*start_routine)(void *), void *arg) {
    attach_trampoline_args_t *targs = malloc(sizeof(attach_trampoline_args_t));
    int ret;
    if (targs == NULL) {
        // OOM building the trampoline args -- fall back to creating the
        // thread unwrapped rather than failing thread creation entirely.
        ret = BYTEHOOK_CALL_PREV(custom_pthread_create, pthread_create_func, thread, attr, start_routine, arg);
        BYTEHOOK_POP_STACK();
        return ret;
    }
    targs->real_start_routine = start_routine;
    targs->real_arg = arg;
    ret = BYTEHOOK_CALL_PREV(custom_pthread_create, pthread_create_func, thread, attr, attaching_thread_trampoline, targs);
    BYTEHOOK_POP_STACK();
    if (ret != 0) free(targs); // thread was never actually created -- trampoline will never run to free it itself
    return ret;
}

static void create_thread_attach_hook_impl(bytehook_hook_single_t bytehook_hook_single_p) {
    // Scoped to libSDL3.so specifically -- see the long comment above and
    // the matching comment in exit_hook.c for why hook_all (every caller
    // process-wide) is NOT safe to use here: it previously double-attached
    // threads ART creates internally for real java.lang.Thread objects,
    // causing "Check failed: Thread::Current() == nullptr" in ART's own
    // thread.cc. hook_single only intercepts pthread_create calls made
    // *from* libSDL3.so's own code, leaving every other caller (ART/
    // libart.so included) completely untouched.
    bytehook_stub_t stub = bytehook_hook_single_p("libSDL3.so", "pthread_create", &custom_pthread_create, NULL, NULL);
    __android_log_print(ANDROID_LOG_INFO, "sdl_hook",
        "Successfully initialized pthread_create JNI-auto-attach hook (scoped to libSDL3.so), stub=%p", stub);
}

void create_window_title_hook(bytehook_hook_single_t bytehook_hook_single_p) {
    create_thread_attach_hook_impl(bytehook_hook_single_p);
}


