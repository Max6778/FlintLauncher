#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <android/log.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//
// FIX (2026-08-27): the logger thread was calling AttachCurrentThread()
// without checking its result, then unconditionally calling NewStringUTF /
// CallVoidMethod on whatever `env` it got back. When a huge burst of output
// hits the pipe in one go (e.g. Minecraft dumping dozens of shader compile
// errors back-to-back on world load), this thread was observed making JNI
// calls in a state ART's CheckJNI considers "not attached", which aborts the
// whole process with SIGABRT ("JNI DETECTED ERROR IN APPLICATION: a thread
// is making JNI calls without being attached, in call to NewStringUTF").
// This file now checks every JNI entry point it uses before touching it,
// and the logger thread bails out of forwarding to the on-screen log
// (while still writing to latestlog.txt) rather than crash the game.
//
// Also hardened: Logger_begin() no longer leaks the previous session's pipe
// fds / leaves a stale logger thread running with a dangling fd number if
// it's ever called a second time in the same process (defensive -- not the
// cause of the crash above, but the same failure class).
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2] = {-1, -1};
static pthread_t logger;
static _Atomic bool logger_running = false;
static jmethodID logger_onEventLogged = NULL;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;

#define LOG_TAG "stdio_is"

static bool recordBuffer(char* buf, ssize_t len) {
    if (strstr(buf, "Session ID is")) return false;
    if (latestlog_fd != -1)
    {
        // write()/fdatasync() can legitimately fail (disk full, fd closed from
        // under us during shutdown, etc.) -- that's not fatal, just stop trying
        // to persist to the file for the rest of this call.
        ssize_t written = write(latestlog_fd, buf, (size_t) len);
        if (written < 0) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                "write() to latestlog failed: %s", strerror(errno));
        } else {
            fdatasync(latestlog_fd);
        }
    }
    return true;
}

static void *logger_thread(__attribute__((unused)) void *arg) {
    JNIEnv *env = NULL;

    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    if (dvm == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "dalvikJavaVMPtr is NULL, logger thread cannot attach -- log will still be written to file only");
    } else {
        jint attachResult = (*dvm)->AttachCurrentThread(dvm, &env, NULL);
        if (attachResult != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                "AttachCurrentThread failed (%d) -- log will still be written to file only", attachResult);
            env = NULL; // Never trust *env after a failed attach, even if it wrote something to it.
        }
    }

    ssize_t rsize;
    char buf[2050];

    // Snapshot which fd we're reading so a concurrent Logger_begin() call
    // (see below) can't yank the global out from under an in-flight read.
    int readFd = pfd[0];

    while (readFd != -1 && (rsize = read(readFd, buf, sizeof(buf) - 1)) > 0)
    {
        bool shouldRecordString = recordBuffer(buf, rsize); //record with newline into latestlog
        if (buf[rsize - 1] == '\n')
        {
            rsize = rsize - 1; //truncate
        }
        buf[rsize] = 0x00;

        // Only attempt to forward to the on-screen log listener if we have
        // a genuinely attached env, a live listener, and a resolved method ID.
        // Any one of these being unset just means "no live UI log this run" --
        // never a reason to crash.
        if (shouldRecordString && env != NULL && logListener != NULL && logger_onEventLogged != NULL)
        {
            jstring writeString = (*env)->NewStringUTF(env, buf);
            if (writeString == NULL) {
                // OOM or invalid modified-UTF8 input -- clear whatever
                // pending exception NewStringUTF may have raised and move on.
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                continue;
            }
            (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, writeString);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionDescribe(env);
                (*env)->ExceptionClear(env);
            }
            (*env)->DeleteLocalRef(env, writeString);
        }
    }

    if (dvm != NULL && env != NULL) {
        (*dvm)->DetachCurrentThread(dvm);
    }
    logger_running = false;
    return NULL;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if (latestlog_fd != -1)
    {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }

    // Guard against a second call while a previous logger thread is still
    // alive: close its read end so its blocking read() unblocks with EOF
    // and it exits cleanly, instead of leaking that thread/fd forever and
    // leaving two threads racing over the global pfd/latestlog_fd state.
    if (logger_running) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "Logger_begin called while a previous logger thread is still running -- stopping it first");
        if (pfd[1] != -1) { close(pfd[1]); pfd[1] = -1; }
        if (pfd[0] != -1) { close(pfd[0]); pfd[0] = -1; }
        pthread_join(logger, NULL);
    }

    if (logger_onEventLogged == NULL)
    {
        jclass eventLogListener = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        if (eventLogListener == NULL) {
            // FindClass already threw a ClassNotFoundException/NoClassDefFoundError
            // into the Dalvik env -- let it propagate instead of calling
            // GetMethodID on a NULL class (which would itself be another
            // guaranteed CheckJNI abort).
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Could not find Logger$eventLogListener class");
            return;
        }
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
        if (logger_onEventLogged == NULL) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Could not find Logger$eventLogListener.onEventLogged method");
            return;
        }
    }

    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");
    if (ioeClass == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Could not find java/io/IOException class");
        return;
    }

    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* create the pipe and redirect stdout and stderr */
    if (pipe(pfd) != 0) {
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    if (logFilePath == NULL) {
        // OOM getting the UTF chars -- an exception is already pending, just bail.
        close(pfd[0]); close(pfd[1]);
        pfd[0] = pfd[1] = -1;
        return;
    }
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    if (latestlog_fd == -1)
    {
        latestlog_fd = 0;
        close(pfd[0]); close(pfd[1]);
        pfd[0] = pfd[1] = -1;
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }

    /* spawn the logging thread */
    logger_running = true;
    int result = pthread_create(&logger, 0, logger_thread, 0);

    if (result != 0)
    {
        logger_running = false;
        close(latestlog_fd);
        latestlog_fd = -1;
        close(pfd[0]); close(pfd[1]);
        pfd[0] = pfd[1] = -1;
        (*env)->ThrowNew(env, ioeClass, strerror(result));
        return;
    }
    pthread_detach(logger);
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);

    if (errorCode == JNI_EDETACHED)
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);

    if (errorCode != JNI_OK)
        killpg(getpgrp(), SIGTERM);

    if (code != 0)
        (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);

    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if (recordBuffer(newChars, appendStringLength+1) && logListener != NULL)
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;

    if (log_listener == NULL) logListener = NULL;
    else logListener = (*env)->NewGlobalRef(env, log_listener);

    if (logListenerLocal != NULL && logListenerLocal != logListener)
        (*env)->DeleteGlobalRef(env, logListenerLocal);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"com/movtery/zalithlauncher/ui/activity/ErrorActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}
