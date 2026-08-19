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
#include <bytehook.h>
#include <dlfcn.h>
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

