#ifndef FLINTLAUNCHER_NATIVE_CRASH_HANDLER_H
#define FLINTLAUNCHER_NATIVE_CRASH_HANDLER_H

// Installs signal handlers for SIGABRT/SIGSEGV/SIGBUS/SIGILL/SIGFPE that,
// on a native crash, capture this process's own recent logcat output
// (self-log read, no permission required) into
// "<crash_log_dir>/log_native_crash_<pid>.txt", then re-raise the signal
// with the default handler restored so the crash proceeds normally.
//
// Call this once, early -- see the two added lines in Logger_begin()
// (stdio_is.c). crash_log_dir should be an absolute path; it does not need
// to exist yet (it will be created with mkdir() on first use, best-effort).
// For FlintLauncher specifically, pass PathManager.DIR_LAUNCHER_LOG's value
// (i.e. "<gameHome>/launcher_log") so the file lands exactly where
// ZHTools.shareLogs() already looks -- the existing in-app "Share Log"
// button then picks it up automatically, with no other code changes.
void install_native_crash_handler(const char *crash_log_dir);

#endif //FLINTLAUNCHER_NATIVE_CRASH_HANDLER_H
