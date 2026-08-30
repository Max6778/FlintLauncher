#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <pthread.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <android/log.h>

//
// logcat_capture.c  (2026-08-30)
//
// Replaces the earlier ProcessBuilder("logcat", ...) approach in
// LogcatCapture.java, which was producing no output at all -- most likely
// an OEM/SELinux policy silently blocking a third-party app from exec'ing
// the logcat binary (device/Android-version dependent, and exactly the
// kind of failure that gives no useful error back).
//
// This instead reads log entries the same way the logcat binary itself
// does: liblog.so (already loaded in every Android process) exports a
// small reader API -- android_logger_list_alloc / android_logger_open /
// android_logger_list_read / android_logger_list_free. It's not declared
// in the NDK's public <android/log.h> (that header only covers *writing*
// logs), but it's a long-stable, widely-used ABI (AOSP's own logcat and
// many third-party log-viewer apps use exactly this), resolved here via
// dlsym so a missing symbol on some odd OEM build fails gracefully
// instead of crashing the game.
//
// Deliberately does NOT hard-code the logger_entry struct layout beyond
// its first 4 bytes (len, hdr_size), which have been stable since the
// very first version of the format and are self-describing: hdr_size
// tells us exactly where the payload starts, so this stays correct even
// if a future/older Android's entry header grew or shrank pid/tid/uid
// fields we don't otherwise touch.
//
// Runs in its own pthread -- mirrors stdio_is.c's logger_thread in shape
// (spawned once, writes to a file, detached) but reads from liblog
// instead of a stdout/stderr pipe.
//

#define TAG "logcat_capture"

// --- liblog's reader API, resolved via dlsym (see comment above) ---
typedef struct logger_list logger_list_t;

typedef logger_list_t *(*android_logger_list_alloc_t)(int mode, unsigned int tail, int pid);
typedef void (*android_logger_list_free_t)(logger_list_t *list);
typedef void *(*android_logger_open_t)(logger_list_t *list, int log_id);
typedef int (*android_logger_list_read_t)(logger_list_t *list, void *log_msg);

#define LOGCAT_CAPTURE_ANDROID_LOG_RDONLY 0x00
#define LOGCAT_CAPTURE_LOG_ID_MAIN 0
#define LOGCAT_CAPTURE_LOG_ID_SYSTEM 3
#define LOGCAT_CAPTURE_LOG_ID_CRASH 4

// Generous fixed buffer -- real entries are capped well under this by the
// logging framework itself (historically ~4KB payload max).
#define LOG_MSG_BUF_SIZE 8192

static android_logger_list_alloc_t p_logger_list_alloc = NULL;
static android_logger_list_free_t p_logger_list_free = NULL;
static android_logger_open_t p_logger_open = NULL;
static android_logger_list_read_t p_logger_list_read = NULL;

static pthread_t s_thread;
static _Atomic bool s_running = false;
static _Atomic bool s_should_stop = false;
static int s_out_fd = -1;
static int s_target_pid = 0;

static char priority_char(unsigned char prio) {
    // Matches android/log.h's android_LogPriority enum values.
    switch (prio) {
        case 2: return 'V';
        case 3: return 'D';
        case 4: return 'I';
        case 5: return 'W';
        case 6: return 'E';
        case 7: return 'F'; // ANDROID_LOG_FATAL -- what we're really after
        default: return '?';
    }
}

static void write_line(const char *fmt, ...) {
    char buf[LOG_MSG_BUF_SIZE + 128];
    va_list args;
    va_start(args, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (n > 0) {
        if ((size_t) n >= sizeof(buf)) n = (int) sizeof(buf) - 1;
        write(s_out_fd, buf, (size_t) n);
    }
}

static void *capture_thread(__attribute__((unused)) void *arg) {
    void *liblog = dlopen("liblog.so", RTLD_NOW);
    if (liblog == NULL) {
        write_line("[logcat_capture] Could not dlopen liblog.so\n");
        s_running = false;
        return NULL;
    }

    p_logger_list_alloc = (android_logger_list_alloc_t) dlsym(liblog, "android_logger_list_alloc");
    p_logger_list_free  = (android_logger_list_free_t)  dlsym(liblog, "android_logger_list_free");
    p_logger_open        = (android_logger_open_t)        dlsym(liblog, "android_logger_open");
    p_logger_list_read   = (android_logger_list_read_t)   dlsym(liblog, "android_logger_list_read");

    if (!p_logger_list_alloc || !p_logger_list_free || !p_logger_open || !p_logger_list_read) {
        write_line("[logcat_capture] liblog.so is missing one or more expected "
                    "symbols (android_logger_list_alloc/open/read/free) -- this "
                    "device's liblog may be unusually old or restricted.\n");
        s_running = false;
        return NULL;
    }

    // tail=0, no ANDROID_LOG_NONBLOCK bit set -> like `logcat` without -d:
    // start from "now" and block waiting for new entries (follow mode).
    logger_list_t *list = p_logger_list_alloc(LOGCAT_CAPTURE_ANDROID_LOG_RDONLY, 0, s_target_pid);
    if (list == NULL) {
        write_line("[logcat_capture] android_logger_list_alloc failed\n");
        s_running = false;
        return NULL;
    }

    p_logger_open(list, LOGCAT_CAPTURE_LOG_ID_MAIN);
    p_logger_open(list, LOGCAT_CAPTURE_LOG_ID_SYSTEM);
    p_logger_open(list, LOGCAT_CAPTURE_LOG_ID_CRASH);

    write_line("[logcat_capture] Capture started for pid %d\n", s_target_pid);

    unsigned char msg_buf[LOG_MSG_BUF_SIZE];
    while (!s_should_stop) {
        // log_msg's first 2 bytes are "len" (payload length); the reader
        // API fills the whole union, but we only rely on that stable
        // prefix -- see the file-level comment on why.
        memset(msg_buf, 0, sizeof(msg_buf));
        int ret = p_logger_list_read(list, msg_buf);
        if (ret <= 0) {
            if (s_should_stop) break;
            continue; // transient read hiccup -- keep going
        }

        uint16_t len = *(uint16_t *) (msg_buf + 0);
        uint16_t hdr_size = *(uint16_t *) (msg_buf + 2);
        // Sanity-check hdr_size before trusting it as an offset.
        if (hdr_size < 4 || hdr_size > 128 || (size_t)(hdr_size + len) > sizeof(msg_buf)) {
            continue; // malformed/unexpected entry shape -- skip rather than misread
        }

        const unsigned char *payload = msg_buf + hdr_size;
        if (len < 1) continue;

        unsigned char prio = payload[0];
        const char *tag = (const char *) (payload + 1);
        size_t tag_len = strnlen(tag, len - 1);
        const char *message = "";
        if (tag_len + 2 <= len) {
            message = tag + tag_len + 1;
        }

        write_line("%c/%.*s: %s\n", priority_char(prio), (int) tag_len, tag, message);
    }

    p_logger_list_free(list);
    write_line("[logcat_capture] Capture stopped\n");
    s_running = false;
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_utils_LogcatCapture_nativeStart(JNIEnv *env, __attribute__((unused)) jclass clazz,
                                                          jstring outPath, jint pid) {
    if (s_running) return JNI_TRUE; // already capturing this session

    const char *path = (*env)->GetStringUTFChars(env, outPath, NULL);
    if (path == NULL) return JNI_FALSE;

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0660);
    (*env)->ReleaseStringUTFChars(env, outPath, path);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Failed to open output file for logcat capture");
        return JNI_FALSE;
    }

    s_out_fd = fd;
    s_target_pid = (int) pid;
    s_should_stop = false;
    s_running = true;

    if (pthread_create(&s_thread, NULL, capture_thread, NULL) != 0) {
        close(s_out_fd);
        s_out_fd = -1;
        s_running = false;
        return JNI_FALSE;
    }
    pthread_detach(s_thread);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_LogcatCapture_nativeStop(__attribute__((unused)) JNIEnv *env,
                                                         __attribute__((unused)) jclass clazz) {
    s_should_stop = true;
    // The blocking read inside android_logger_list_read won't wake up
    // immediately -- the thread notices s_should_stop on its next entry
    // or read timeout and exits on its own; s_out_fd is left open for it
    // to finish writing rather than closed here, to avoid a race.
}
