//
// FlintLauncher: declares the SDL native hooks, ported from Amethyst-Android's
// app_pojavlauncher/src/main/jni/native_hooks/sdl_hook.c.
//
// Amethyst's hooks are wired through their own native_hooks/native_hooks.h,
// which declares a handful of hook groups (exit, chmod, dlopen, sdl) that all
// get installed together from one bytehook_init() call. FlintLauncher already
// has its own working bytehook_init() call in exit_hook.c (used for the exit
// hook), so rather than duplicating that whole module we just add the SDL
// hook declaration here and call it from inside exit_hook.c's existing
// init_exit_hook(), right after the exit hook is installed -- same
// bytehook_hook_all_p pointer, no second bytehook_init().
//
#pragma once

#include <bytehook.h>

// Installs a PLT/GOT hook on SDL_InitSubSystem so we get a callback into
// Java (CallbackBridge.notifyLauncher) at the exact moment SDL/LWJGL
// initializes SDL, instead of guessing when that happens from Java side.
void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p);
