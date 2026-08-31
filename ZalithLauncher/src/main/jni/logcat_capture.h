#ifndef FLINTLAUNCHER_LOGCAT_CAPTURE_H
#define FLINTLAUNCHER_LOGCAT_CAPTURE_H

#include <stdbool.h>

// Starts continuously capturing this process's own logcat output
// (LOG_ID_MAIN, LOG_ID_SYSTEM, LOG_ID_CRASH) into out_path, via liblog.so
// directly -- no exec()/external process involved. Safe to call more than
// once; a second call while already running is a no-op returning true.
// Returns false only if the output file couldn't be opened or the
// capture thread couldn't be spawned -- never fatal to the caller.
bool start_logcat_capture(const char *out_path, int pid);

// Signals the capture thread to stop after its next log entry (or read
// timeout). Not required to be called -- like stdio_is.c's own logger
// thread, it's fine to just let it run for the rest of the process
// lifetime and let the OS clean it up on exit.
void stop_logcat_capture(void);

#endif //FLINTLAUNCHER_LOGCAT_CAPTURE_H
