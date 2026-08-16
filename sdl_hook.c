//
// FlintLauncher SDL native hook, ported from Amethyst-Android's
// app_pojavlauncher/src/main/jni/native_hooks/sdl_hook.c to FlintLauncher's
// existing environ/utils plumbing (method_notifyLauncher + bridgeClazz
// already existed in environ.h/input_bridge_v3.c, just weren't wired to
// anything real yet -- CallbackBridge.notifyLauncher() was a no-op stub).
//
// What this does: hooks SDL_InitSubSystem via bytehook (PLT/GOT hook) so
// that the moment LWJGL's SDL3 backend calls it, we get a callback into
// Java (CallbackBridge.notifyLauncher) *before* SDL actually finishes
// initializing. This lets the launcher side do JNI/hint setup at the exact
// right moment, instead of a separate Activity guessing when to do it based
// on version detection alone.
//
#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

typedef bool (*SDL_InitSubSystem_t)(unsigned int flags);
typedef void (*SDL_SetHint_t)(const char *name, const char *value);
typedef void (*SDL_SetError_t)(const char *fmt, ...);
typedef const char *(*SDL_GetError_t)(void);

static bool custom_SDL_InitSubSystem_Func(unsigned int flags) {
    // Call notifyLauncher on SDL_InitSubSystem -- this sets up all the JNI
    // stuff needed by SDL launcher-side, the same moment GLFW/LWJGL is
    // about to hand control to SDL.
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "SDL_InitSubSystem failed!",
            void *sdl_handle = dlopen("libSDL3.so", RTLD_NOLOAD);
            if (sdl_handle) {
                SDL_SetError_t SDL_SetError_p = (SDL_SetError_t) dlsym(sdl_handle, "SDL_SetError");
                if (SDL_SetError_p) SDL_SetError_p("Failed to load SDL launcher integration android-side. This is not an SDL bug, please contact the FlintLauncher developer.");
            }
            BYTEHOOK_POP_STACK();
            return false;
            );

    jint safeFlags = (flags > (unsigned int)INT32_MAX) ? -1 : (jint) flags;
    jintArray actionArray = (*dvm_env)->NewIntArray(dvm_env, 2);
    jint actions[2] = {ACTION_INIT_LAUNCHER_INTEGRATION, safeFlags};
    (*dvm_env)->SetIntArrayRegion(dvm_env, actionArray, 0, 2, actions);
    (*dvm_env)->CallStaticBooleanMethod(dvm_env, pojav_environ->bridgeClazz,
            pojav_environ->method_notifyLauncher, NOTIF_TYPE_SDL, actionArray);
    (*dvm_env)->DeleteLocalRef(dvm_env, actionArray);

    // This is the normal setting for the launcher; SDL's own default is false.
    void *sdl_handle = dlopen("libSDL3.so", RTLD_NOLOAD);
    if (sdl_handle) {
        SDL_SetHint_t SDL_SetHint_p = (SDL_SetHint_t) dlsym(sdl_handle, "SDL_SetHint");
        if (SDL_SetHint_p) {
            SDL_SetHint_p("SDL_RETURN_KEY_HIDES_IME", "true");
            // MobileGlues has issues with passing in the proper EGL params to make this work
            const char *egl = getenv("POJAVEXEC_EGL");
            if (egl && strcmp(egl, "libmobileglues.so") == 0) {
                SDL_SetHint_p("SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER", "0");
            }
        }
    }

    // Call the original SDL_InitSubSystem now that launcher-side setup is done.
    bool r = BYTEHOOK_CALL_PREV(custom_SDL_InitSubSystem_Func, SDL_InitSubSystem_t, flags);
    if (!r && sdl_handle) {
        SDL_GetError_t SDL_GetError_p = (SDL_GetError_t) dlsym(sdl_handle, "SDL_GetError");
        if (SDL_GetError_p) {
            printf("FlintLauncher: SDL_InitSubSystem Error: %s\n", SDL_GetError_p());
        }
    }
    BYTEHOOK_POP_STACK();
    return r;
}

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    // callee_path_name must stay NULL, or bytehook won't be able to find the symbol
    bytehook_stub_t stub_SDL_InitSubSystem = bytehook_hook_all_p(NULL, "SDL_InitSubSystem", (void *) &custom_SDL_InitSubSystem_Func, NULL, NULL);
    printf("FlintLauncher: initialized SDL hooks, stub=%p\n", stub_SDL_InitSubSystem);
}
