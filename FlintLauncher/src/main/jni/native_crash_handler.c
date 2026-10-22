#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/wait.h>
#include <android/log.h>

#include "native_crash_handler.h"

//
// native_crash_handler.c  (2026-08-29)
//
// Problem this solves: a native SIGABRT (or SIGSEGV/SIGBUS/etc) from inside
// the JVM/ART -- e.g. the CheckJNI "JNI DETECTED ERROR IN APPLICATION" abort
// -- is invisible to Java's Thread.setDefaultUncaughtExceptionHandler (that
// only catches Java exceptions), and the descriptive text ART prints before
// aborting goes straight to the Android log driver, not to this app's own
// redirected stdout/stderr (see stdio_is.c's Logger_begin/logger_thread).
// The only way to see *why* a native crash happened has been pulling a full
// adb logcat off a PC.
//
// This installs a signal handler for the common fatal signals. When one
// fires, instead of trying to hand-roll a backtrace (which would only give
// raw addresses, and needs execinfo.h which isn't available before API 33),
// it shells out to the on-device `logcat` binary asking ONLY for this
// process's own PID's recent lines -- which Android allows without any
// special permission, because a process can always read its own log
// entries -- and writes that to a plain text file. That capture includes
// whatever ART/liblog already printed right before the abort (e.g. the
// "JNI DETECTED ERROR..." message), not just the bare signal.
//
// The file is written as "log_native_crash_<epoch>.txt" inside the crash
// log directory passed in from Java. That naming (starts with "log", ends
// with ".txt") matches the filter ZHTools.shareLogs() already uses to zip
// up DIR_LAUNCHER_LOG -- so this needs *zero* Java-side UI changes. The
// existing "Share Log" button on the crash screen will just start including
// it automatically.
//
// After writing the file, the signal is re-raised with the default handler
// restored (SA_RESETHAND), so the OS's own crash reporter / debuggerd still
// runs exactly as before and the process still terminates normally. This
// handler only adds a capture step in front of that -- it never swallows or
// changes how the crash itself is handled.
//
// Wire-up (two lines, see stdio_is.c's Logger_begin): call
// install_native_crash_handler(crashLogDir) once, early, with the absolute
// path to the directory ZHTools.shareLogs() zips (PathManager.DIR_LAUNCHER_LOG,
// i.e. "<gameHome>/launcher_log").
//

#define TAG "native_crash_handler"
#define CRASH_DIR_MAX 512

static char g_crash_dir[CRASH_DIR_MAX] = {0};
static struct sigaction g_old_handlers[NSIG];

static const int WATCHED_SIGNALS[] = { SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE };
#define NUM_WATCHED_SIGNALS (sizeof(WATCHED_SIGNALS) / sizeof(WATCHED_SIGNALS[0]))

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGABRT: return "SIGABRT";
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGFPE:  return "SIGFPE";
        default:      return "UNKNOWN";
    }
}

// Deliberately NOT using snprintf/malloc/etc here where avoidable -- this
// runs from a signal handler, so we keep it to plain syscalls and a small
// fixed-size stack buffer. It's not textbook async-signal-safe (fork() and
// dup2() have edge cases mid-signal), but this is a diagnostic tool for a
// developer reproducing a crash on purpose, not a shipped crash reporter --
// the pragmatic tradeoff (this is essentially what many lightweight native
// crash reporters do) is worth it here for how much more useful the output
// is versus a bare backtrace.
static void crash_handler(int sig, siginfo_t *info, void *ucontext) {
    (void) ucontext;

    if (g_crash_dir[0] != '\0') {
        char path[CRASH_DIR_MAX + 64];
        int n = 0;
        {
            // Build "<dir>/log_native_crash_<pid>.txt" without snprintf.
            const char *prefix = "/log_native_crash_";
            const char *suffix = ".txt";
            int i = 0;
            while (g_crash_dir[i] != '\0' && i < CRASH_DIR_MAX - 1) { path[i] = g_crash_dir[i]; i++; }
            n = i;
            for (i = 0; prefix[i] != '\0'; i++) path[n++] = prefix[i];
            pid_t pid = getpid();
            char pidbuf[16]; int pidlen = 0;
            if (pid == 0) { pidbuf[pidlen++] = '0'; }
            else { pid_t p = pid; char tmp[16]; int t = 0;
                while (p > 0) { tmp[t++] = (char)('0' + (p % 10)); p /= 10; }
                while (t > 0) { pidbuf[pidlen++] = tmp[--t]; }
            }
            for (i = 0; i < pidlen; i++) path[n++] = pidbuf[i];
            for (i = 0; suffix[i] != '\0'; i++) path[n++] = suffix[i];
            path[n] = '\0';
        }

        mkdir(g_crash_dir, 0770); // best-effort, ignore EEXIST/errors

        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0660);
        if (fd >= 0) {
            static char header[256];
            int hn = 0;
            const char *h1 = "Native crash: signal ";
            const char *sn = signal_name(sig);
            const char *h2 = " (code ";
            const char *h3 = "), pid ";
            const char *h4 = "\nCapturing this process's recent logcat below "
                             "(includes any ART/JNI diagnostic printed just before the abort):\n\n";
            int i;
            for (i = 0; h1[i]; i++) header[hn++] = h1[i];
            for (i = 0; sn[i]; i++) header[hn++] = sn[i];
            for (i = 0; h2[i]; i++) header[hn++] = h2[i];
            {
                int code = info ? info->si_code : 0;
                char cb[16]; int cl = 0;
                if (code == 0) cb[cl++] = '0';
                else { int neg = code < 0; unsigned int c = neg ? (unsigned)(-code) : (unsigned)code;
                    char tmp[16]; int t = 0;
                    while (c > 0) { tmp[t++] = (char)('0' + (c % 10)); c /= 10; }
                    if (neg) cb[cl++] = '-';
                    while (t > 0) cb[cl++] = tmp[--t];
                }
                for (i = 0; i < cl; i++) header[hn++] = cb[i];
            }
            for (i = 0; h3[i]; i++) header[hn++] = h3[i];
            {
                pid_t pid = getpid();
                char pb[16]; int pl = 0; char tmp[16]; int t = 0; pid_t p = pid;
                if (p == 0) pb[pl++] = '0';
                else { while (p > 0) { tmp[t++] = (char)('0' + (p % 10)); p /= 10; } while (t > 0) pb[pl++] = tmp[--t]; }
                for (i = 0; i < pl; i++) header[hn++] = pb[i];
            }
            for (i = 0; h4[i]; i++) header[hn++] = h4[i];
            write(fd, header, (size_t) hn);

            // Ask logcat for only this process's own recent lines, dumped
            // (not followed) directly into our fd, then exit. This is the
            // self-log read every app is permitted to do without READ_LOGS.
            pid_t child = fork();
            if (child == 0) {
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                char pidArg[32] = "--pid=";
                {
                    pid_t pid = getppid();
                    char tmp[16]; int t = 0; pid_t p = pid;
                    if (p == 0) { pidArg[6] = '0'; pidArg[7] = '\0'; }
                    else {
                        while (p > 0) { tmp[t++] = (char)('0' + (p % 10)); p /= 10; }
                        int k = 6;
                        while (t > 0) pidArg[k++] = tmp[--t];
                        pidArg[k] = '\0';
                    }
                }
                execlp("logcat", "logcat", "-d", "-v", "threadtime", pidArg, (char *) NULL);
                _exit(127); // exec failed
            } else if (child > 0) {
                int status;
                waitpid(child, &status, 0);
            }

            close(fd);
        }

        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Native crash (signal %d / %s) captured to %s", sig, signal_name(sig), path);
    }

    // Restore default behavior and let the crash proceed normally --
    // we only wanted to capture, never to swallow or alter the crash.
    struct sigaction *old = &g_old_handlers[sig];
    sigaction(sig, old, NULL);
    raise(sig);
}

void install_native_crash_handler(const char *crash_log_dir) {
    if (crash_log_dir == NULL) return;

    size_t len = strlen(crash_log_dir);
    if (len >= CRASH_DIR_MAX) len = CRASH_DIR_MAX - 1;
    memcpy(g_crash_dir, crash_log_dir, len);
    g_crash_dir[len] = '\0';

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND; // one-shot: restores default handler once fired

    for (size_t i = 0; i < NUM_WATCHED_SIGNALS; i++) {
        int sig = WATCHED_SIGNALS[i];
        sigaction(sig, &sa, &g_old_handlers[sig]);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "Native crash handler installed, writing to %s", g_crash_dir);
}
