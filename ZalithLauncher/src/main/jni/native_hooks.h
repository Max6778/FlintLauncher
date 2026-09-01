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

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_gl_core_proc_hooks(bytehook_hook_all_t bytehook_hook_all_p);
void create_window_title_hook(bytehook_hook_all_t bytehook_hook_all_p);

#endif //FLINTLAUNCHER_NATIVE_HOOKS_H
