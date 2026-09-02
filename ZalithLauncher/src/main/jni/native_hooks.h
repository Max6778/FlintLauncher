//
// Shared declarations for FlintLauncher's bytehook-based native hooks.
// All hooks share ONE bytehook_init() call (done in exit_hook.c), rather than
// each hook independently calling bytehook_init() -- multiple bytehook_init()
// calls in one process is not the intended usage pattern.
//

#ifndef FLINTLAUNCHER_NATIVE_HOOKS_H
#define FLINTLAUNCHER_NATIVE_HOOKS_H

#include <bytehook.h>

typedef bytehook_stub_t (*bytehook_hook_all_t)(const char *callee_path_name, const char *sym_name, void *new_func,
                                               bytehook_hooked_t hooked, void *hooked_arg);
// NOT the same signature as bytehook_hook_all_t -- hook_single takes an
// EXTRA leading parameter, caller_path_name (the library whose calls you
// want to intercept), separate from callee_path_name (the library that
// actually exports/defines the symbol -- e.g. "libc.so" for pthread_create,
// or NULL to match any providing library). Confirmed against ByteHook's own
// README: bytehook_hook_single(caller_path_name, callee_path_name, sym_name,
// new_func, hooked, hooked_arg) -- six parameters. An earlier version of
// this typedef mirrored bytehook_hook_all_t's five-parameter shape, which
// compiled fine but silently passed arguments through a mismatched
// function-pointer type -- "libSDL3.so" landed in the wrong parameter slot
// entirely, so the hook call had no real effect despite running without
// error. See create_window_title_hook's use of this in sdl_hook.c.
typedef bytehook_stub_t (*bytehook_hook_single_t)(const char *caller_path_name, const char *callee_path_name,
                                               const char *sym_name, void *new_func,
                                               bytehook_hooked_t hooked, void *hooked_arg);

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_gl_core_proc_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_window_title_hook(bytehook_hook_single_t bytehook_hook_single_p);

#endif //FLINTLAUNCHER_NATIVE_HOOKS_H
